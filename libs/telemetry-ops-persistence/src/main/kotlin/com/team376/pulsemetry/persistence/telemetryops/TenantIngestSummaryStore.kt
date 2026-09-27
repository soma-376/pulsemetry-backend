package com.team376.pulsemetry.persistence.telemetryops

import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsUnavailableException.Companion.classified
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/** 요약 갱신의 출처(ADR 0021 §2). 출처마다 움직일 수 있는 시각이 다르다. */
public enum class SummaryOrigin {
	/** 새 live push. 수신 시각과 `first_observed_at` 을 움직인다. */
	LIVE,

	/** 아카이브 재처리. 새 수신도 새 관측도 아니므로 **아무 시각도 움직이지 않는다.** */
	REPLAY,

	/** 누락된 요약의 복구. 호출자가 **원래 receipt 의 수신 시각**을 넘긴다 — 복구 실행 시각이 아니다. */
	RECOVERY,
}

/** tenant 생애 요약 한 행. 시각은 마이크로초(`timestamptz`)다. */
public data class TenantIngestSummary(
	public val tenantId: UUID,
	public val firstReceivedAt: Instant?,
	public val firstObservedAt: Instant?,
	public val lastReceivedAt: Instant?,
	public val hasPreLedgerHistory: Boolean,
)

/**
 * RDS `telemetry_ops.tenant_ingest_summary` 의 쓰기 주체(ADR 0021 §2·§4). DDL 이 이 모듈 아래 있다.
 *
 * ## 원자적 MIN·MAX
 *
 * 갱신은 upsert 문장 하나다 — `first_* = LEAST(기존, 새 값)`, `last_received_at = GREATEST(기존, 새 값)`. PostgreSQL 의
 * `LEAST`·`GREATEST` 는 NULL 을 무시하므로 NULL 이 기존 시각을 지우지 못하고, 행 잠금이 같은 tenant 의 갱신을 줄 세우므로
 * 동시 요청·재시도가 최초 시각을 뒤로, 마지막 시각을 앞으로 옮기지 못한다. 같은 receipt 를 다시 적용해도 결과가 같다.
 *
 * `first_observed_at` 은 유효한 `source_time` 의 최솟값이라 늦게 도착한 과거 데이터로 앞당겨질 수 있다. 수신 시각은
 * 재처리로 움직이지 않는다([SummaryOrigin]). 분석·ledger 의 보존 정리는 이 행을 건드리지 않는다.
 *
 * 시각은 마이크로초로 내린다 — 컬럼 정밀도다. 실패 분류는 [TelemetryOpsUnavailableException] 이 담는다.
 *
 * 빈이 아니다(ADR 0011). `DataSource` 를 생성자로 받는다.
 */
public class TenantIngestSummaryStore(private val dataSource: DataSource) {

	/**
	 * 수신 하나를 요약에 반영한다. [SummaryOrigin.REPLAY] 는 아무것도 쓰지 않고 false 를 돌려준다.
	 *
	 * @param receivedAt receipt 의 서버 수신 시각.
	 * @param firstObservedAt 그 수신에서 분석 테이블로 간 관측의 `source_time` 최솟값. 없으면 null.
	 */
	public fun record(tenantId: UUID, origin: SummaryOrigin, receivedAt: Instant, firstObservedAt: Instant?): Boolean {
		if (origin == SummaryOrigin.REPLAY) return false
		classified("upsert") {
			dataSource.connection.use { connection ->
				connection.prepareStatement(UPSERT).use { statement ->
					statement.setObject(1, tenantId)
					statement.setTime(2, receivedAt)
					statement.setTime(3, firstObservedAt)
					statement.setTime(4, receivedAt)
					statement.executeUpdate()
				}
			}
		}
		return true
	}

	/** tenant 의 요약. 행이 없으면 null — "수집한 적 없음"으로 해석하려면 백필 완료 기록도 봐야 한다(ADR 0021 §2). */
	public fun find(tenantId: UUID): TenantIngestSummary? = classified("select") {
		dataSource.connection.use { connection ->
			connection.prepareStatement(SELECT).use { statement ->
				statement.setObject(1, tenantId)
				statement.executeQuery().use { rows ->
					if (!rows.next()) return@classified null
					TenantIngestSummary(
						tenantId = tenantId,
						firstReceivedAt = rows.instant("first_received_at"),
						firstObservedAt = rows.instant("first_observed_at"),
						lastReceivedAt = rows.instant("last_received_at"),
						hasPreLedgerHistory = rows.getBoolean("has_pre_ledger_history"),
					)
				}
			}
		}
	}

	internal companion object {
		private val UPSERT = """
			INSERT INTO telemetry_ops.tenant_ingest_summary AS s
			    (tenant_id, first_received_at, first_observed_at, last_received_at, updated_at)
			VALUES (?, ?, ?, ?, now())
			ON CONFLICT (tenant_id) DO UPDATE SET
			    first_received_at = LEAST(s.first_received_at, EXCLUDED.first_received_at),
			    first_observed_at = LEAST(s.first_observed_at, EXCLUDED.first_observed_at),
			    last_received_at = GREATEST(s.last_received_at, EXCLUDED.last_received_at),
			    updated_at = now()
		""".trimIndent()

		private val SELECT = """
			SELECT first_received_at, first_observed_at, last_received_at, has_pre_ledger_history
			FROM telemetry_ops.tenant_ingest_summary WHERE tenant_id = ?
		""".trimIndent()

		/** 컬럼 정밀도(마이크로초)로 내린다. `Instant` 의 나노 부분은 언제나 0 이상이라 내림이다. */
		fun micros(value: Instant): Instant = value.truncatedTo(ChronoUnit.MICROS)

		fun PreparedStatement.setTime(index: Int, value: Instant?) {
			if (value == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
			else setObject(index, OffsetDateTime.ofInstant(micros(value), ZoneOffset.UTC))
		}

		fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
	}
}
