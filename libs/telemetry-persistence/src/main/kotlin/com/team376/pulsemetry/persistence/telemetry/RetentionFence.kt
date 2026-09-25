package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import java.time.Instant

/**
 * ClickHouse `telemetry_retention_fence` 의 쓰기 주체(ADR 0024 §2). DDL 이 이 모듈 아래 있다.
 *
 * **보존 작업만 조립한다.** 값은 RDS 경계에 이미 커밋된 `(deleted_before, policy_epoch)` 이고, 커밋 **뒤에** 쓴다 — 그래서 fence 가
 * RDS 보다 앞서는 일이 없다. 분석 INSERT 는 이 테이블을 서버에서 읽기만 한다([AnalysisInsert]). 동기 INSERT 라 반환된 순간부터
 * 새로 등록되는 INSERT 가 이 값을 본다.
 *
 * 빈이 아니다(ADR 0011).
 */
public class RetentionFence(private val client: ClickHouseHttpClient) {

	/** fence 값 하나. */
	public data class Value(public val deletedBefore: Instant, public val policyEpoch: Long)

	/** RDS 에 커밋된 경계를 쓴다. 같은 값을 다시 써도 읽는 값(`max`)이 같아 무해하다. */
	public fun write(tenantId: String, value: Value) {
		require(tenantId.isNotBlank()) { "fence 는 tenant 가 있어야 쓴다" }
		require(value.deletedBefore.nano % NANOS_PER_MICRO == 0) { "fence 는 마이크로초 정밀도다: ${value.deletedBefore}" }
		require(value.policyEpoch >= 1) { "fence 는 발효된 경계만 쓴다: epoch ${value.policyEpoch}" }
		client.execute(
			WRITE,
			params = mapOf(
				"tenant" to tenantId,
				"before" to AnalysisRowWriter.formatTime(EpochNanos.of(value.deletedBefore)),
				"epoch" to value.policyEpoch.toString(),
			),
		)
	}

	/** 지금 fence — INSERT 가 읽는 것과 같은 `max` 다. 없으면 null. */
	public fun current(tenantId: String): Value? {
		val line = client.execute(CURRENT, params = mapOf("tenant" to tenantId)).trim()
		if (line.isEmpty()) return null
		val (before, epoch) = line.split('\t')
		return Value(Instant.parse(before.replace(' ', 'T') + "Z"), epoch.toLong())
	}

	public companion object {
		public const val TABLE: String = "telemetry_retention_fence"

		private const val NANOS_PER_MICRO = 1_000

		private val WRITE = "INSERT INTO $TABLE (tenant_id, deleted_before, policy_epoch) " +
			"SELECT {tenant:String}, {before:DateTime64(9, 'UTC')}, {epoch:UInt64}"

		/** 행이 없으면 `HAVING` 이 빈 결과를 낸다 — 빈 입력의 `max` 는 1970 이라 값처럼 보인다. */
		private val CURRENT = "SELECT toString(max(deleted_before)), max(policy_epoch) FROM $TABLE " +
			"WHERE tenant_id = {tenant:String} HAVING count() > 0 FORMAT TSV"
	}
}
