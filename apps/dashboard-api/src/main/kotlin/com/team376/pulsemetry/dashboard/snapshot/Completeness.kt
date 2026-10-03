package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.request.DatePeriod
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 하루가 **완전 관측**인가의 판정 (ADR 0042). snapshot build 때 원천에서 읽은 근거로 한 번 계산하고, 그 결과(완전한 날짜)를 snapshot 에
 * 고정한다 — 같은 snapshot 의 모든 endpoint 가 같은 판정을 쓴다.
 *
 * 하루 D(조회 시간대의 [자정, 다음 자정))는 다음을 **모두** 만족할 때만 완전하다.
 *
 * 1. 확정 시각(D 의 끝 + [settle])이 build 의 기준 시각 이전이다 — 아직 오고 있을 수 있는 날은 미확정이다.
 * 2. 삭제 경계보다 앞선 부분이 없다 — 지워진 날은 다시 셀 수 없다.
 * 3. 그날 수집해야 했던 설치가 하나 이상 있다 — 설치가 없는 날의 0 은 관측이 아니다.
 * 4. 그 설치들 모두가, 등록한 때(그날 안이면 그때)부터 확정 시각까지 **손실 없는 수집 구간**으로 빈틈없이 덮여 있다.
 *    구간은 설치 보고로 확인된 시간이다(ADR 0040). 프로세스가 바뀐 틈, 손실 구간, 보고가 없는 설치(직결·구버전)는 덮지 않는다.
 * 5. 그날 폐기된 설치가 없다 — 폐기 직전의 데이터가 전해졌는지 확인할 보고가 더는 오지 않는다.
 * 6. 그날 효력이 있던 수집 정책이 모두 사용량을 싣는 시그널(logs)을 수집한다 — 정책이 끈 값의 부재는 0 이 아니다.
 *
 * 설치 보고만으로 과거를 증명하지 않는다: 보고가 말하는 것은 "그 시간 동안 수집·전달 경로가 떠 있었고 잃지 않았다"이고,
 * 확정 시각까지 손실 없이 이어졌다는 조건이 그날의 전송이 끝났음을, 확정 시각이 지났다는 조건이 적재가 끝났음을 대신한다.
 */
object Completeness {

	/** 판정의 근거. 모두 서버 시각이다. */
	data class Evidence(
		val installations: List<Installation>,
		/** 활성화된 적 있는 수집 정책 판, 활성화 시각 오름차순. 한 판은 다음 판이 활성화될 때까지 효력이 있다. */
		val policies: List<Policy>,
		val deletedBefore: Instant?,
	)

	/** [lossless] 는 그 설치의 손실 없는 수집 구간이다(양 끝 포함). */
	data class Installation(val id: UUID, val createdAt: Instant, val revokedAt: Instant?, val lossless: List<Span>)

	data class Span(val from: Instant, val to: Instant)

	/** [collectsUsage] 는 그 판이 사용량을 싣는 시그널(logs)을 수집하는가다. */
	data class Policy(val activatedAt: Instant, val collectsUsage: Boolean)

	fun completeDates(dates: Collection<LocalDate>, zone: ZoneId, evidence: Evidence, asOf: Instant, settle: Duration): Set<LocalDate> =
		dates.filterTo(sortedSetOf()) { complete(it, zone, evidence, asOf, settle) }

	private fun complete(date: LocalDate, zone: ZoneId, evidence: Evidence, asOf: Instant, settle: Duration): Boolean {
		val start = date.atStartOfDay(zone).toInstant()
		val end = date.plusDays(1).atStartOfDay(zone).toInstant()
		val settled = end.plus(settle)
		if (settled.isAfter(asOf)) return false
		if (evidence.deletedBefore != null && start.isBefore(evidence.deletedBefore)) return false
		if (!policiesCollectUsage(evidence.policies, start, end)) return false

		// 그날 수집해야 했던 설치 — 끝나기 전에 등록했고, 시작 전에 폐기되지 않았다.
		val required = evidence.installations.filter { it.createdAt.isBefore(end) && (it.revokedAt == null || it.revokedAt.isAfter(start)) }
		if (required.isEmpty()) return false
		return required.all { installation ->
			// 확정 시각까지 사이에 폐기됐으면 마지막 데이터를 확인할 수 없다.
			if (installation.revokedAt != null && !installation.revokedAt.isAfter(settled)) return@all false
			covered(installation.lossless, maxOf(start, installation.createdAt), settled)
		}
	}

	/** [from, until] 이 구간들의 합집합으로 빈틈없이 덮이는가. 맞닿은 구간(앞의 끝 = 뒤의 시작)은 이어진 것이다. */
	private fun covered(spans: List<Span>, from: Instant, until: Instant): Boolean {
		var cursor = from
		for (span in spans.sortedBy { it.from }) {
			if (span.from.isAfter(cursor)) break
			if (span.to.isAfter(cursor)) cursor = span.to
			if (!cursor.isBefore(until)) return true
		}
		return !cursor.isBefore(until)
	}

	/** [start, end) 와 겹치는 효력 기간을 가진 정책 판이 하나 이상 있고, 그 모두가 사용량을 수집한다. */
	private fun policiesCollectUsage(policies: List<Policy>, start: Instant, end: Instant): Boolean {
		val sorted = policies.sortedBy { it.activatedAt }
		val inForce = sorted.filterIndexed { index, policy ->
			val until = sorted.getOrNull(index + 1)?.activatedAt
			policy.activatedAt.isBefore(end) && (until == null || until.isAfter(start))
		}
		return inForce.isNotEmpty() && inForce.all { it.collectsUsage }
	}

	/**
	 * `dataThrough` — 현재 기간의 첫날부터 끊김 없이 이어진 완전한 날짜의 마지막 날의 끝(다음 날 자정)이다. 이 시각까지의 사용량은 확정이다.
	 * 첫날이 완전하지 않으면 없다.
	 */
	fun dataThrough(period: DatePeriod, complete: Set<LocalDate>): Instant? {
		val through = period.dates().takeWhile { it in complete }.lastOrNull() ?: return null
		return through.plusDays(1).atStartOfDay(period.zone).toInstant()
	}
}
