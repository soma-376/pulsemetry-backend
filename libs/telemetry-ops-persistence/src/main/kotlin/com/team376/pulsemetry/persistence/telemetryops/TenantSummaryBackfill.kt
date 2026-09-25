package com.team376.pulsemetry.persistence.telemetryops

import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsUnavailableException.Companion.classified
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** 백필 완료 기록 한 행(`telemetry_ops.tenant_summary_backfill`). */
public data class BackfillCompletion(
	public val backfill: String,
	public val source: String,
	public val completedAt: Instant,
	public val tenantsMarked: Int,
)

/** 백필 한 번의 결과. [executed] 가 false 면 이미 완료 기록이 있어 원천을 읽지 않았다. */
public data class BackfillOutcome(
	public val completion: BackfillCompletion,
	public val executed: Boolean,
	/** UUID 가 아니라 요약 행이 될 수 없어 건너뛴 원천의 tenant 값. */
	public val skippedTenantIds: List<String>,
)

/**
 * 요약 도입 전 이력의 백필(ADR 0021 §2). 기존 분석 원천에 행이 있는 tenant 를 `has_pre_ledger_history = true` 로
 * 표시하고 완료를 기록한다. **시각을 지어내지 않는다** — 새로 만드는 요약 행의 시각은 NULL 이고, 이미 있는 행의 시각은
 * 건드리지 않는다.
 *
 * 표시와 완료 기록은 한 트랜잭션이다 — 완료 기록이 있으면 표시도 끝났다. 완료 기록이 있으면 원천을 다시 읽지 않는다
 * (재실행은 멱등). 두 실행이 동시에 돌아도 표시는 멱등이고 완료 기록은 먼저 커밋한 쪽이 남는다.
 *
 * 원천을 읽는 쪽은 이 모듈이 아니다 — ClickHouse 는 다른 아웃바운드 기술이라 조립 앱이 읽는 함수를 넘긴다.
 */
public class TenantSummaryBackfill(private val dataSource: DataSource) {

	/** [backfill] 의 완료 기록. 없으면 null — 그 백필은 끝나지 않았다. */
	public fun completion(backfill: String): BackfillCompletion? = classified("select backfill") {
		dataSource.connection.use { completion(it, backfill) }
	}

	/**
	 * [backfill] 이 끝나지 않았으면 [tenantIds] 를 읽어 표시하고 완료를 기록한다. 끝났으면 기록만 돌려준다.
	 *
	 * @param source 읽은 원천의 이름(예: 기존 분석 테이블 이름). 완료 기록에 남는다.
	 */
	public fun run(backfill: String, source: String, tenantIds: () -> Collection<String>): BackfillOutcome {
		completion(backfill)?.let { return BackfillOutcome(it, executed = false, skippedTenantIds = emptyList()) }

		val values = tenantIds().distinct().sorted()
		val parsed = values.associateWith { runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { uuid -> uuid.toString() == it } }
		val tenants = parsed.values.filterNotNull()
		val skipped = parsed.filterValues { it == null }.keys.toList()

		val completion = classified("backfill") {
			dataSource.connection.use { connection ->
				connection.autoCommit = false
				try {
					connection.prepareStatement(MARK).use { statement ->
						for (tenant in tenants) {
							statement.setObject(1, tenant)
							statement.addBatch()
						}
						statement.executeBatch()
					}
					connection.prepareStatement(COMPLETE).use { statement ->
						statement.setString(1, backfill)
						statement.setString(2, source)
						statement.setInt(3, tenants.size)
						statement.executeUpdate()
					}
					connection.commit()
				} catch (exception: Throwable) {
					connection.rollback()
					throw exception
				}
				completion(connection, backfill) ?: error("백필 완료 기록을 쓴 뒤 읽지 못했다: $backfill")
			}
		}
		return BackfillOutcome(completion, executed = true, skippedTenantIds = skipped)
	}

	private fun completion(connection: Connection, backfill: String): BackfillCompletion? =
		connection.prepareStatement(SELECT).use { statement ->
			statement.setString(1, backfill)
			statement.executeQuery().use { rows ->
				if (!rows.next()) return null
				BackfillCompletion(
					backfill = backfill,
					source = rows.getString("source"),
					completedAt = rows.getObject("completed_at", OffsetDateTime::class.java).toInstant(),
					tenantsMarked = rows.getInt("tenants_marked"),
				)
			}
		}

	public companion object {
		/** 기존 `enriched_events` 를 원천으로 하는 백필의 이름. */
		public const val PRE_LEDGER_ENRICHED_EVENTS: String = "pre-ledger-enriched-events"

		private val MARK = """
			INSERT INTO telemetry_ops.tenant_ingest_summary (tenant_id, has_pre_ledger_history, updated_at)
			VALUES (?, true, now())
			ON CONFLICT (tenant_id) DO UPDATE SET has_pre_ledger_history = true, updated_at = now()
		""".trimIndent()

		private val COMPLETE = """
			INSERT INTO telemetry_ops.tenant_summary_backfill (backfill, source, completed_at, tenants_marked)
			VALUES (?, ?, now(), ?)
			ON CONFLICT (backfill) DO NOTHING
		""".trimIndent()

		private val SELECT = "SELECT source, completed_at, tenants_marked FROM telemetry_ops.tenant_summary_backfill WHERE backfill = ?"
	}
}
