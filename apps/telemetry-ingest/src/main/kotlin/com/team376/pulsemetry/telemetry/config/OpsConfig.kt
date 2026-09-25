package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.IngestLedgerSink
import com.team376.pulsemetry.persistence.telemetry.PreLedgerHistorySource
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantSummaryBackfill
import com.team376.pulsemetry.telemetry.pipeline.IngestOperationsRecorder
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * 수집 운영 기록 — 수신 ledger(ClickHouse)와 tenant 생애 요약(RDS `telemetry_ops`)(ADR 0021).
 *
 * **`pulsemetry.telemetry.ops.enabled=true` 일 때만 조립한다.** ADR 0021 은 허브 ADR 0007 채택 전까지 Proposed 이고,
 * 그동안 이 테이블들에 쓰는 코드를 운영 경로에 연결하지 않는다. 채택되면 기본값을 켠다.
 *
 * `telemetry_ops` 의 DDL 은 이 앱이 적용하지 않는다 — `:apps:enrollment-api` 기동이 적용한다(ADR 0021 §3). 스키마가
 * 아직 없으면 요약 쓰기가 일시 장애(503)다. 요약은 보강과 같은 RDS 에 있어 같은 `DataSource` 를 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.telemetry.ops", name = ["enabled"], havingValue = "true")
class OpsConfig {

	@Bean
	fun ingestLedgerSink(client: ClickHouseHttpClient): IngestLedgerSink = IngestLedgerSink(client)

	@Bean
	fun tenantIngestSummaryStore(dataSource: DataSource): TenantIngestSummaryStore = TenantIngestSummaryStore(dataSource)

	@Bean
	fun ingestOperationsRecorder(ledger: IngestLedgerSink, summaries: TenantIngestSummaryStore): IngestOperationsRecorder =
		IngestOperationsRecorder(ledger, summaries)

	/**
	 * 요약 도입 전 이력의 백필 — 기동마다 한 번 시도한다(ADR 0021 §2). 완료 기록이 있으면 원천을 읽지 않으므로 그 뒤의
	 * 기동 비용은 RDS 조회 하나다. **실패해도 기동을 막지 않는다** — 수집은 백필과 독립이고, 완료 기록이 없는 동안
	 * 조회 계층은 요약 부재를 "수집한 적 없음"으로 판정하지 않는다. 다음 기동이 다시 시도한다.
	 */
	@Bean
	fun preLedgerBackfill(dataSource: DataSource, client: ClickHouseHttpClient): ApplicationRunner {
		val backfill = TenantSummaryBackfill(dataSource)
		val source = PreLedgerHistorySource(client)
		return ApplicationRunner {
			try {
				val outcome = backfill.run(TenantSummaryBackfill.PRE_LEDGER_ENRICHED_EVENTS, source.source) { source.tenantIds() }
				if (outcome.executed) {
					log.info(
						"pre-ledger backfill completed: {} tenants marked, {} skipped (not a UUID)",
						outcome.completion.tenantsMarked,
						outcome.skippedTenantIds.size,
					)
				}
			} catch (exception: Exception) {
				log.warn("pre-ledger backfill failed; retried at next startup: {}", exception.message)
			}
		}
	}

	private companion object {
		private val log = LoggerFactory.getLogger(OpsConfig::class.java)
	}
}
