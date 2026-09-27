package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationEnvelope
import java.time.Instant
import java.util.UUID

/**
 * 분석 행 쓰기의 삭제 경계 — 쓰는 쪽이 **이 쓰기 직전에** RDS 에서 읽은 tenant 의 경계와 정책 epoch 다(ADR 0024 §3).
 *
 * 두 sink 는 이것 없이 쓰지 않는다. 재처리 경로도 같은 sink 를 지나므로 경계를 우회할 수 없다. sink 는 [admits] 로 행을 한 번 더
 * 거르고, INSERT 는 서버에서 `telemetry_retention_fence` 를 다시 검사한다([AnalysisInsert]) — 이 값이 읽힌 뒤 경계가 움직여도
 * 늦게 등록된 INSERT 는 새 fence 로 판정된다.
 */
public data class AnalysisWriteBoundary(
	public val tenantId: String,
	/** 이보다 이른 `source_time` 은 쓰지 않는다. 경계가 없으면 null. */
	public val deletedBefore: Instant?,
	/** 이 경계를 읽은 정책 epoch. 경계가 없으면 0. INSERT 의 `query_id` 에 실린다. */
	public val policyEpoch: Long,
) {
	init {
		require(tenantId.isNotBlank()) { "분석 행은 검증된 tenant 가 있어야 쓴다" }
		require(policyEpoch >= 0) { "policy_epoch 는 음수가 아니다: $policyEpoch" }
	}

	private val floor: EpochNanos? = deletedBefore?.let(EpochNanos::of)

	/** 이 경계 안의 관측인가 — `source_time >= deleted_before`. 경계가 없으면 언제나 참이다. */
	public fun admits(sourceTime: EpochNanos): Boolean = floor == null || sourceTime >= floor

	/** INSERT 의 서버 `query_id` — `analysis-insert:{tenant}:{epoch}:{무작위}`(ADR 0024 §2). 진단·작업 기록용이다. */
	internal fun queryId(): String = "$QUERY_ID_PREFIX$tenantId:$policyEpoch:${UUID.randomUUID()}"

	/**
	 * [rows] 중 경계 안의 것. 경계의 tenant 와 다른 행이 하나라도 있으면 호출자의 결함이라 **요청을 보내기 전에** 실패한다 —
	 * 영구 오류(400)가 아니라 분류되지 않은 예외(503)다. 조용히 버리면 다른 tenant 의 관측이 사라진다.
	 */
	internal fun <T> admitted(rows: List<T>, envelope: (T) -> ObservationEnvelope): List<T> = rows.filter { row ->
		val observation = envelope(row)
		require(observation.tenantId == tenantId) { "경계의 tenant($tenantId)와 다른 행이다: ${observation.tenantId}" }
		admits(observation.sourceTime)
	}

	public companion object {
		public const val QUERY_ID_PREFIX: String = "analysis-insert:"
	}
}
