package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummary
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantSummaryBackfill
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/**
 * 수집 운영 현황 — snapshot 밖의 **현재** 상태다(`ingest.asOf`). 원천 계정으로 읽기만 한다.
 *
 * - 수신 이력은 tenant 생애 요약(`telemetry_ops.tenant_ingest_summary` — ADR 0021)이다. 요약이 없으면 **백필 완료 기록이 있어야**
 *   "수신한 적 없음"으로 읽는다. 둘 다 없으면 판정할 근거가 없어 [IngestHistoryUnknownException](503)이다 — 요약 부재를 수집한 적 없음으로 바꾸지 않는다.
 *   요약을 읽지 못하는 것(장애)도 503 이다.
 * - 관측된 구성원은 최근 창(ledger 의 수신 시각) 안에 수신이 있는 설치를 `installations.member_id` 로 풀어 센다. 이 조회가 실패하면 0 이 아니라 null 이다.
 */
class IngestStatusReader(
	private val summaries: TenantIngestSummaryStore,
	private val backfill: TenantSummaryBackfill,
	private val ledger: ClickHouseSourceReader,
	private val source: JdbcClient,
) {

	private val log = LoggerFactory.getLogger(IngestStatusReader::class.java)

	data class History(val summary: TenantIngestSummary?) {
		/** 인증·마스킹을 통과한 수신이 한 번이라도 있었다(요약 도입 전 이력 표시 포함). */
		val hasReceipts: Boolean get() = summary != null && (summary.firstReceivedAt != null || summary.hasPreLedgerHistory)
	}

	fun history(tenantId: UUID): History {
		val summary = summaries.find(tenantId)
		if (summary == null && backfill.completion(TenantSummaryBackfill.PRE_LEDGER_ENRICHED_EVENTS) == null) {
			throw IngestHistoryUnknownException()
		}
		return History(summary)
	}

	fun observedMembers(tenantId: UUID, since: Instant): Long? = try {
		val installations = ledger.query(
			"SELECT DISTINCT installation_id AS i FROM telemetry_ingest_ledger " +
				"WHERE tenant_id = {tenant:String} AND received_time >= {since:DateTime64(9, 'UTC')}",
			mapOf("tenant" to ClickHouseParam.string(tenantId.toString()), "since" to ClickHouseParam.instant(since)),
		) { it.path("i").asString() }.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
		if (installations.isEmpty()) 0 else
			source.sql(
				"SELECT count(DISTINCT member_id) FROM enrollment.installations WHERE tenant_id = :tenant AND id = ANY(CAST(:ids AS uuid[]))",
			)
				.param("tenant", tenantId)
				.param("ids", installations.joinToString(",", "{", "}"))
				.query(Long::class.java)
				.single()
	} catch (e: RuntimeException) {
		log.warn("관측 구성원 조회 실패 — null 로 둔다", e)
		null
	}

	fun eligibleMembers(tenantId: UUID): Long? = try {
		source.sql("SELECT count(*) FROM enrollment.members WHERE tenant_id = :tenant AND status = 'active'")
			.param("tenant", tenantId)
			.query(Long::class.java)
			.single()
	} catch (e: RuntimeException) {
		log.warn("대상 구성원 조회 실패 — null 로 둔다", e)
		null
	}
}

/** 수신 이력을 판정할 근거(요약 또는 백필 완료 기록)가 없다. 응답은 503 이다 — 합의 전에는 `dataState` 에 unknown 이 없다. */
class IngestHistoryUnknownException : RuntimeException("수신 이력 판정 근거 없음 — 요약도 백필 완료 기록도 없다")
