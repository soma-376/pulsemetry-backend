package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.connector.vendor.BilledAmount
import com.team376.pulsemetry.connector.vendor.BilledKind
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 저장된 청구 누계 한 기간 (ADR 0050). [source] 가 `seed` 면 개발 시드다 — 실제 청구의 증거가 아니다. */
data class BillingPeriod(val vendorId: String, val periodStart: Instant, val periodEnd: Instant, val amountUsd: BigDecimal, val kind: BilledKind,
	val finalized: Boolean, val source: String, val fetchedAt: Instant)

/**
 * 벤더 청구 누계의 기록 (ADR 0050). 좌석 동기화와 같은 실행이 커넥터의 [com.team376.pulsemetry.connector.vendor.BillingReader] 결과를 남긴다.
 * 같은 기간을 다시 읽으면 금액·끝·읽은 시각을 덮는다. 결과(성공·실패)는 연결의 청구 칸에 따로 남는다 — 좌석 동기화 결과와 섞지 않는다. 빈이 아니다.
 */
class VendorBillingStore(private val jdbc: JdbcClient, manager: PlatformTransactionManager, private val clock: Clock) {
	private val tx = TransactionTemplate(manager)

	/** 읽은 누계를 남긴다. 연결이 지워졌으면(같은 ID 의 활성 연결이 없으면) 쓰지 않고 false. */
	fun record(tenant: UUID, connectionId: UUID, billed: BilledAmount): Boolean = write {
		val vendorId = activeVendor(tenant, connectionId) ?: return@write false
		val now = now()
		jdbc.sql("""INSERT INTO enrollment.vendor_billing_periods (tenant_id, vendor_id, period_start, period_end, amount_usd, kind, finalized, source, connection_id, fetched_at)
				VALUES (:tenant, :vendor, :start, :end, :amount, :kind, :finalized, 'connector', :connection, :now)
				ON CONFLICT (tenant_id, vendor_id, period_start) DO UPDATE SET period_end = EXCLUDED.period_end, amount_usd = EXCLUDED.amount_usd, kind = EXCLUDED.kind,
					finalized = EXCLUDED.finalized, source = 'connector', connection_id = EXCLUDED.connection_id, fetched_at = EXCLUDED.fetched_at""")
			.param("tenant", tenant).param("vendor", vendorId).param("start", Timestamp.from(billed.from)).param("end", Timestamp.from(billed.to))
			.param("amount", billed.amount).param("kind", billed.kind.wire).param("finalized", billed.finalized).param("connection", connectionId)
			.param("now", Timestamp.from(now)).update()
		jdbc.sql("UPDATE enrollment.vendor_connections SET last_billing_succeeded_at = :now, last_billing_failed_at = NULL, last_billing_error = NULL WHERE id = :id")
			.param("now", Timestamp.from(now)).param("id", connectionId).update()
		true
	}

	/** 읽기가 실패했다. 저장된 누계는 그대로다(마지막 성공 값이 남는다). 연결이 없으면 false. */
	fun fail(tenant: UUID, connectionId: UUID, error: String): Boolean = write {
		require(ERROR.matches(error)) { "오류 코드는 [a-z_]{1,64} 여야 한다" }
		activeVendor(tenant, connectionId) ?: return@write false
		jdbc.sql("UPDATE enrollment.vendor_connections SET last_billing_failed_at = :now, last_billing_error = :error WHERE id = :id")
			.param("now", Timestamp.from(now())).param("error", error).param("id", connectionId).update()
		true
	}

	private fun activeVendor(tenant: UUID, connectionId: UUID): String? =
		jdbc.sql("SELECT vendor_id FROM enrollment.vendor_connections WHERE id = :id AND tenant_id = :tenant AND deleted_at IS NULL FOR UPDATE")
			.param("id", connectionId).param("tenant", tenant).query(String::class.java).optional().orElse(null)

	private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
	@Suppress("UNCHECKED_CAST")
	private fun <T> write(block: () -> T): T = tx.execute { block() } as T

	companion object {
		private val ERROR = Regex("[a-z_]{1,64}")

		/**
		 * 조직의 등록 제품마다 기준 시각 이전에 시작한 가장 최근 기간 — 조회 앱이 읽는다(읽기 전용 계정). 청구 누계는 판이 없는 현재 값이다.
		 */
		fun latest(jdbc: JdbcClient, tenant: UUID, asOf: Instant): Map<String, BillingPeriod> = jdbc.sql("""
			SELECT DISTINCT ON (vendor_id) vendor_id, period_start, period_end, amount_usd, kind, finalized, source, fetched_at
			FROM enrollment.vendor_billing_periods WHERE tenant_id = :tenant AND period_start <= :as_of
			ORDER BY vendor_id, period_start DESC""").param("tenant", tenant).param("as_of", Timestamp.from(asOf)).query { rs, _ ->
			BillingPeriod(rs.getString("vendor_id"), rs.getTimestamp("period_start").toInstant(), rs.getTimestamp("period_end").toInstant(), rs.getBigDecimal("amount_usd"),
				BilledKind.entries.first { it.wire == rs.getString("kind") }, rs.getBoolean("finalized"), rs.getString("source"), rs.getTimestamp("fetched_at").toInstant())
		}.list().associateBy { it.vendorId }
	}
}
