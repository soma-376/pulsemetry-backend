package com.team376.pulsemetry.persistence.telemetryops

import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsUnavailableException.Companion.classified
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore.Companion.instant
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** tenant 의 삭제 경계(ADR 0024 §1). 행이 없으면 [deletedBefore] 가 null 이고 [policyEpoch] 가 0 이다. */
public data class TenantRetentionBoundary(
	public val tenantId: UUID,
	/** 이보다 이른 `source_time` 의 분석 행은 지워졌거나 지워질 대상이다. */
	public val deletedBefore: Instant?,
	/** 삭제 정책의 세대. 경계가 움직일 때마다 1 오른다. */
	public val policyEpoch: Long,
) {
	init {
		require(policyEpoch >= 0) { "policy_epoch 는 음수가 아니다: $policyEpoch" }
	}

	public companion object {
		public fun none(tenantId: UUID): TenantRetentionBoundary = TenantRetentionBoundary(tenantId, null, 0)
	}
}

/**
 * RDS `telemetry_ops.tenant_retention_boundary` 의 읽기와 유일한 쓰기(ADR 0021 §2·§4 · ADR 0024 §1). DDL 이 이 모듈 아래 있다.
 *
 * ## MAX 로만 움직인다
 *
 * [advance] 는 upsert 문장 하나이고, 후보가 지금 경계보다 **늦을 때만** 경계를 옮기고 epoch 를 1 올린다 — 같거나 이른 후보는
 * 아무것도 바꾸지 않는다. 행 잠금이 같은 tenant 의 갱신을 줄 세우므로 동시에 불려도 경계가 뒤로 가지 않고, epoch 는 실제 이동
 * 수만큼만 오른다. 보존 기간 연장·재실행·재기동이 경계를 되돌리거나 기존 snapshot 을 괜히 무효화하지 않는 근거가 이것이다.
 * 경계를 낮추는 연산은 없다.
 *
 * 쓰기는 보존 작업만 조립한다. ingest·재처리·조회 계층은 [read] 만 쓴다(ADR 0021 §4).
 *
 * 빈이 아니다(ADR 0011). 실패 분류는 [TelemetryOpsUnavailableException] 이 담는다.
 */
public class TenantRetentionBoundaryStore(private val dataSource: DataSource) {

	/** 지금 경계. 행이 없으면 [TenantRetentionBoundary.none] 이다. */
	public fun read(tenantId: UUID): TenantRetentionBoundary = classified("boundary select") {
		dataSource.connection.use { connection ->
			connection.prepareStatement(SELECT).use { statement ->
				statement.setObject(1, tenantId)
				statement.executeQuery().use { rows ->
					if (!rows.next()) return@classified TenantRetentionBoundary.none(tenantId)
					TenantRetentionBoundary(tenantId, rows.instant("deleted_before"), rows.getLong("policy_epoch"))
				}
			}
		}
	}

	/** [advance] 의 결과 — 갱신 뒤의 경계와, 이 호출이 경계를 옮겼는지. */
	public data class Advance(public val boundary: TenantRetentionBoundary, public val moved: Boolean)

	/**
	 * 경계를 [candidate] 로 앞당긴다(MAX). 첫 행은 epoch 1 이다.
	 *
	 * 마이크로초 아래 자리가 있는 후보는 거부한다 — 컬럼 정밀도로 내리면 정책보다 덜 지우고, 올리면 더 지운다.
	 */
	public fun advance(tenantId: UUID, candidate: Instant): Advance {
		require(candidate.nano % NANOS_PER_MICRO == 0) { "삭제 경계는 마이크로초 정밀도다: $candidate" }
		val moved = classified("boundary advance") {
			dataSource.connection.use { connection ->
				connection.prepareStatement(ADVANCE).use { statement ->
					statement.setObject(1, tenantId)
					statement.setObject(2, OffsetDateTime.ofInstant(candidate, ZoneOffset.UTC))
					statement.executeQuery().use { rows ->
						if (!rows.next()) return@classified null
						TenantRetentionBoundary(tenantId, rows.instant("deleted_before"), rows.getLong("policy_epoch"))
					}
				}
			}
		}
		return if (moved != null) Advance(moved, moved = true) else Advance(read(tenantId), moved = false)
	}

	internal companion object {
		private const val NANOS_PER_MICRO = 1_000

		private val SELECT = """
			SELECT deleted_before, policy_epoch FROM telemetry_ops.tenant_retention_boundary WHERE tenant_id = ?
		""".trimIndent()

		/** 옮기지 않으면 `WHERE` 가 거짓이라 `RETURNING` 행이 없다. */
		private val ADVANCE = """
			INSERT INTO telemetry_ops.tenant_retention_boundary AS b (tenant_id, deleted_before, policy_epoch, updated_at)
			VALUES (?, ?, 1, now())
			ON CONFLICT (tenant_id) DO UPDATE SET
			    deleted_before = EXCLUDED.deleted_before,
			    policy_epoch = b.policy_epoch + 1,
			    updated_at = now()
			WHERE b.deleted_before < EXCLUDED.deleted_before
			RETURNING deleted_before, policy_epoch
		""".trimIndent()
	}
}
