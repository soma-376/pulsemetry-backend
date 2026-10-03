package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.request.DatePeriod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 하루의 완전 관측 판정 (ADR 0042). 기대값은 ADR 의 여섯 조건에서 쓴다. 시간대는 서울, 확정 대기 1시간.
 */
class CompletenessTest {

	private val seoul: ZoneId = ZoneId.of("Asia/Seoul")
	private val settle: Duration = Duration.ofHours(1)
	private val day: LocalDate = LocalDate.parse("2026-09-10")
	private val start: Instant = kst("2026-09-10T00:00:00")
	private val end: Instant = kst("2026-09-11T00:00:00")
	private val settled: Instant = end.plus(settle)
	private val asOf: Instant = kst("2026-09-30T00:00:00")
	private val alwaysLogs = listOf(Completeness.Policy(kst("2026-01-01T00:00:00"), collectsUsage = true))

	private fun kst(value: String): Instant = LocalDateTime.parse(value).atZone(seoul).toInstant()

	private fun installation(
		createdAt: Instant = kst("2026-08-01T00:00:00"),
		revokedAt: Instant? = null,
		vararg lossless: Pair<Instant, Instant> = arrayOf(kst("2026-08-01T00:00:00") to kst("2026-09-20T00:00:00")),
	) = Completeness.Installation(UUID.randomUUID(), createdAt, revokedAt, lossless.map { Completeness.Span(it.first, it.second) })

	private fun complete(
		vararg installations: Completeness.Installation,
		policies: List<Completeness.Policy> = alwaysLogs,
		deletedBefore: Instant? = null,
		at: Instant = asOf,
	): Boolean = day in Completeness.completeDates(listOf(day), seoul, Completeness.Evidence(installations.toList(), policies, deletedBefore), at, settle)

	@Test
	@DisplayName("모든 설치가 등록부터 확정 시각까지 손실 없이 덮였으면 완전하다")
	fun covered() {
		assertThat(complete(installation(), installation())).isTrue()
		// 맞닿은 구간(프로세스 안에서 손실 없이 이어진 두 구간)은 이어진 것이다.
		assertThat(complete(installation(lossless = arrayOf(start.minusSeconds(60) to kst("2026-09-10T13:00:00"), kst("2026-09-10T13:00:00") to settled)))).isTrue()
	}

	@Test
	@DisplayName("확정 시각 — 하루의 끝에서 확정 대기만큼 지나야 하고, 그때까지 덮여 있어야 한다")
	fun settleBoundary() {
		assertThat(complete(installation(lossless = arrayOf(start to settled)))).isTrue()
		assertThat(complete(installation(lossless = arrayOf(start to settled.minusSeconds(1))))).isFalse()
		// 아직 확정 시각 전이다.
		assertThat(complete(installation(), at = settled.minusNanos(1))).isFalse()
		assertThat(complete(installation(), at = settled)).isTrue()
	}

	@Test
	@DisplayName("빈틈 하나 — 프로세스가 바뀐 틈, 손실 구간, 보고가 없는 설치 — 가 있으면 완전하지 않다")
	fun anyGap() {
		// 두 프로세스 사이 10분.
		assertThat(complete(installation(lossless = arrayOf(start to kst("2026-09-10T12:00:00"), kst("2026-09-10T12:10:00") to settled)))).isFalse()
		// 손실 구간은 여기 넘어오지 않는다(손실 없는 구간만 근거다). 그 시간만큼 빈다.
		assertThat(complete(installation(lossless = arrayOf(start to kst("2026-09-10T12:00:00"), kst("2026-09-10T12:05:00") to settled)))).isFalse()
		// 보고가 없는 설치(직결·구버전)는 덮이지 않는다 — 다른 설치가 완벽해도 그날은 완전하지 않다.
		assertThat(complete(installation(), installation(lossless = arrayOf()))).isFalse()
		// 하루가 시작된 뒤에 시작한 구간.
		assertThat(complete(installation(lossless = arrayOf(start.plusSeconds(1) to settled)))).isFalse()
	}

	@Test
	@DisplayName("그날 등록한 설치는 등록한 때부터만 덮이면 된다")
	fun registeredThatDay() {
		val noon = kst("2026-09-10T12:00:00")
		assertThat(complete(installation(), installation(createdAt = noon, lossless = arrayOf(noon to settled)))).isTrue()
		assertThat(complete(installation(), installation(createdAt = noon, lossless = arrayOf(noon.plusSeconds(1) to settled)))).isFalse()
		// 다음 날 등록한 설치는 그날 수집해야 했던 설치가 아니다.
		assertThat(complete(installation(), installation(createdAt = end, lossless = arrayOf()))).isTrue()
	}

	@Test
	@DisplayName("그날(확정 시각까지) 폐기된 설치가 있으면 완전하지 않다 — 그 전에 폐기된 설치는 상관없다")
	fun revoked() {
		assertThat(complete(installation(), installation(revokedAt = kst("2026-09-10T15:00:00")))).isFalse()
		assertThat(complete(installation(), installation(revokedAt = settled))).isFalse()
		assertThat(complete(installation(), installation(revokedAt = settled.plusSeconds(1)))).isTrue()
		// 그날이 시작되기 전에 폐기됐다 — 그날 수집해야 했던 설치가 아니다.
		assertThat(complete(installation(), installation(revokedAt = start, lossless = arrayOf()))).isTrue()
	}

	@Test
	@DisplayName("수집해야 했던 설치가 없으면 0 은 관측이 아니다")
	fun noInstallations() {
		assertThat(complete()).isFalse()
		assertThat(complete(installation(createdAt = end, lossless = arrayOf()))).isFalse()
	}

	@Test
	@DisplayName("그날 효력이 있던 정책이 사용량(logs)을 수집하지 않으면 완전하지 않다")
	fun policyWithoutUsage() {
		val off = listOf(Completeness.Policy(kst("2026-01-01T00:00:00"), true), Completeness.Policy(kst("2026-09-10T09:00:00"), false),
			Completeness.Policy(kst("2026-09-10T18:00:00"), true))
		assertThat(complete(installation(), policies = off)).isFalse()
		// 그날이 시작되기 전에 끝난 판, 그날이 끝난 뒤에 시작한 판은 상관없다.
		val around = listOf(Completeness.Policy(kst("2026-01-01T00:00:00"), false), Completeness.Policy(start, true), Completeness.Policy(end, false))
		assertThat(complete(installation(), policies = around)).isTrue()
		// 정책이 없던 날은 수집이 없던 날이다.
		assertThat(complete(installation(), policies = listOf(Completeness.Policy(end, true)))).isFalse()
		assertThat(complete(installation(), policies = emptyList())).isFalse()
	}

	@Test
	@DisplayName("삭제 경계보다 앞선 부분이 있는 날은 완전하지 않다")
	fun retentionBoundary() {
		assertThat(complete(installation(), deletedBefore = start)).isTrue()
		assertThat(complete(installation(), deletedBefore = start.plusSeconds(1))).isFalse()
	}

	@Test
	@DisplayName("dataThrough 는 첫날부터 끊김 없이 이어진 완전한 날의 끝이다")
	fun dataThrough() {
		val period = DatePeriod(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-13"), seoul)
		val days = period.dates()
		assertThat(Completeness.dataThrough(period, days.toSet())).isEqualTo(kst("2026-09-14T00:00:00"))
		assertThat(Completeness.dataThrough(period, days.take(3).toSet() + days.drop(4))).isEqualTo(kst("2026-09-10T00:00:00"))
		assertThat(Completeness.dataThrough(period, days.drop(1).toSet())).isNull()
		assertThat(Completeness.dataThrough(period, emptySet())).isNull()
	}
}
