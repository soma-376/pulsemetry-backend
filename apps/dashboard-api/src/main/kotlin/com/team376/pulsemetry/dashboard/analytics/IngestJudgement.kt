package com.team376.pulsemetry.dashboard.analytics

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 수집 상태 판정의 운영 수치 (ADR 0041). 기본값이 없는 필수 설정에서 온다. */
data class IngestThresholds(
	/** "지금"으로 보는 창. 이 안에 받은 설치 보고·수신·손실만 현재의 근거다. */
	val window: Duration,
	/** 전달 대기가 이보다 오래 이어지면 지연이다. */
	val delayedAfter: Duration,
	/** 전달 성공(또는 설치 보고)이 이보다 오래 없으면 중단이다. */
	val downAfter: Duration,
)

/**
 * 활성 설치 하나와 그 설치의 마지막 보고(ADR 0040). 보고한 적이 없으면 [report] 가 null 이다.
 */
data class InstallationState(
	val installationId: UUID,
	val memberId: UUID,
	/** 그 구성원이 지금 활성인가. */
	val memberActive: Boolean,
	val report: Report?,
) {
	/** 시각은 모두 서버 시각이다. */
	data class Report(
		val receivedAt: Instant,
		/** 벤더 도구가 로컬 수신기로 보내고 데몬이 회사로 전달한다. false 면 회사로 직접 보낸다(데몬은 그 전송을 보지 못한다). */
		val local: Boolean,
		val forwarding: Boolean,
		val receivingSince: Instant?,
		val lastDeliveredAt: Instant?,
		/** 전달 대기가 끊김 없이 이어지기 시작한 시각. 대기가 없으면 null. */
		val pendingSince: Instant?,
		/** 창 안에 끝난 손실 구간이 있다. */
		val recentLoss: Boolean,
	) {
		/** 회사로 가는 텔레메트리가 데몬을 지난다 — 전달 결과를 데몬이 보고한다. */
		val collecting: Boolean get() = local && forwarding && receivingSince != null

		/** 회사로 가는 경로가 켜져 있다고 보고했다. 로컬 배선인데 전달이나 수신기가 꺼진 설치는 아니다. */
		val companyBound: Boolean get() = collecting || !local
	}
}

/**
 * 수집 상태의 판정 (ADR 0041). 읽어 온 근거만으로 계산하는 순수 함수다 — 공통 헤더(`GET O/ingest-status`)와 분석 응답의 `ingest` 조각이 같은 계산을 쓴다.
 *
 * 원칙: **최신 수신 시각만으로 정상을 추정하지 않는다.** `healthy`·`delayed`·`down` 은 설치가 보고한 전달 결과가 있을 때만 낸다.
 * 근거가 모자라면 `unknown` 이고, 셀 근거가 없는 값은 0 이 아니라 null 이다.
 */
object IngestJudgement {

	data class Result(
		val status: String,
		val reason: String?,
		/** 창 안에 설치 보고가 있는 활성 설치 수. 그 조직의 활성 설치가 보고한 적이 없으면 null. */
		val activeInstallations: Long?,
		/** 커버리지의 분모 — 회사로 가는 경로가 켜져 있다고 보고한 활성 설치를 가진 활성 구성원 수. */
		val coverageTargetMembers: Long?,
		/** 커버리지의 분자 — 그 구성원 중 창 안에 수신이 확인된 사람 수. */
		val coverageObservedMembers: Long?,
	) {
		val coverageRatio: Double?
			get() = if (coverageTargetMembers == null || coverageObservedMembers == null || coverageTargetMembers == 0L) null
			else coverageObservedMembers.toDouble() / coverageTargetMembers
	}

	/**
	 * @param hasReceipts 그 조직에 수신 이력이 있는가(생애 요약 — ADR 0021).
	 * @param installations 그 조직의 활성 설치와 마지막 보고. 읽지 못했으면 null.
	 * @param observed 창 안에 수신(ledger)이 있는 설치. 읽지 못했으면 null.
	 */
	fun judge(hasReceipts: Boolean, installations: List<InstallationState>?, observed: Set<UUID>?, now: Instant, thresholds: IngestThresholds): Result {
		val since = now.minus(thresholds.window)
		val reports = installations?.mapNotNull { it.report }
		val activeInstallations = when {
			reports == null || reports.isEmpty() -> null
			else -> reports.count { !it.receivedAt.isBefore(since) }.toLong()
		}

		// 커버리지는 "회사로 가는 경로가 켜져 있다고 보고한" 설치만 대상으로 한다. 보고한 적 없는 설치와 경로가 꺼진 설치는
		// 관측됐어야 한다고 말할 근거가 없으므로 분모에 넣지 않는다.
		val targets = installations?.filter { it.memberActive && it.report?.companyBound == true }
		val targetMembers = targets?.map { it.memberId }?.toSet()
		val observedMembers = if (targets == null || observed == null) null
		else targets.filter { it.installationId in observed }.map { it.memberId }.toSet()

		val (status, reason) = status(hasReceipts, reports, since, now, thresholds)
		return Result(status, reason, activeInstallations, targetMembers?.size?.toLong(), observedMembers?.size?.toLong())
	}

	private fun status(hasReceipts: Boolean, reports: List<InstallationState.Report>?, since: Instant, now: Instant, thresholds: IngestThresholds): Pair<String, String?> {
		// 수신 이력이 없는 조직은 수신 대기다 (ADR 0034). 설치 보고가 있어도 이 뜻은 바뀌지 않는다.
		if (!hasReceipts) return EMPTY to null
		if (reports == null) return UNKNOWN to Availability.SOURCE_NOT_AVAILABLE

		// 판정 대상 — 지금 보고하고 있고, 전달 결과를 데몬이 보는 설치.
		val judged = reports.filter { it.collecting && !it.receivedAt.isBefore(since) }
		if (judged.isNotEmpty()) {
			val deliveries = judged.map { delivery(it, thresholds) }
			return when {
				deliveries.all { it == Delivery.STALLED } -> DOWN to DELIVERY_STALLED
				deliveries.any { it != Delivery.OK } -> DELAYED to DELIVERY_DELAYED
				else -> HEALTHY to null
			}
		}

		// 지금 보고하는 판정 대상이 없다. 수집 중이던 설치가 조용해진 지 얼마나 됐는지만 안다.
		val lastCollecting = reports.filter { it.collecting }.maxOfOrNull { it.receivedAt }
			?: return UNKNOWN to Availability.SOURCE_NOT_AVAILABLE
		return if (Duration.between(lastCollecting, now) > thresholds.downAfter) DOWN to INSTALLATIONS_SILENT
		else UNKNOWN to INSTALLATIONS_SILENT
	}

	private enum class Delivery { OK, IMPAIRED, STALLED }

	/**
	 * 판정 대상 설치 하나의 전달 상태.
	 *
	 * - 창 안에 손실이 있고 마지막 전달 성공이 `down-after` 보다 오래됐으면 **중단**이다(잃고 있고 그동안 아무것도 가지 못했다).
	 * - 창 안에 손실이 있거나, 전달 대기가 `delayed-after` 보다 오래 이어지면 **지연**이다.
	 * - 대기가 0 이 아니라는 것만으로는 아무 말도 하지 않는다 — 보고 순간에 마침 하나가 전송 중일 수 있다.
	 *
	 * 전달 성공이 한 번도 없었으면 듣기 시작한 시각부터 센다. 기준은 그 설치의 보고 시각이다(보고 뒤의 일은 모른다).
	 */
	private fun delivery(report: InstallationState.Report, thresholds: IngestThresholds): Delivery {
		val sinceDelivery = Duration.between(report.lastDeliveredAt ?: report.receivingSince ?: report.receivedAt, report.receivedAt)
		val backlog = report.pendingSince?.let { Duration.between(it, report.receivedAt) }
		return when {
			report.recentLoss && sinceDelivery > thresholds.downAfter -> Delivery.STALLED
			report.recentLoss -> Delivery.IMPAIRED
			backlog != null && backlog > thresholds.delayedAfter -> Delivery.IMPAIRED
			else -> Delivery.OK
		}
	}

	const val EMPTY = "empty"
	const val HEALTHY = "healthy"
	const val DELAYED = "delayed"
	const val DOWN = "down"
	const val UNKNOWN = "unknown"

	/** 판정 대상 설치 전부가 잃고 있고 `down-after` 넘게 전달에 성공하지 못했다. */
	const val DELIVERY_STALLED = "delivery_stalled"

	/** 판정 대상 설치 일부가 잃고 있거나 전달 대기가 이어진다. */
	const val DELIVERY_DELAYED = "delivery_delayed"

	/** 수집 중이던 설치가 창 안에 보고하지 않는다. */
	const val INSTALLATIONS_SILENT = "installations_silent"
}
