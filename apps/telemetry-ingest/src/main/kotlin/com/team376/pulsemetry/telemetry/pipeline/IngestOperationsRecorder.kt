package com.team376.pulsemetry.telemetry.pipeline

import com.team376.pulsemetry.persistence.telemetry.IngestLedgerEntry
import com.team376.pulsemetry.persistence.telemetry.IngestLedgerSink
import com.team376.pulsemetry.persistence.telemetry.LedgerReceipt
import com.team376.pulsemetry.persistence.telemetryops.SummaryOrigin
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.telemetry.adapter.observation.NormalizationStats
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationSignal
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt
import java.util.UUID

/** push 하나의 수집 운영 기록을 남기는 자리. [IngestPipeline] 이 적재 결과에 따라 둘 중 하나를 부른다(ADR 0021 §1). */
interface IngestOperations {

	/** 분석 테이블까지 적재한 push. 문서마다 정규화 집계를 옮긴다. */
	fun loaded(receipt: ArchiveReceipt, parts: List<Pair<ReceiptPart, NormalizationStats>>)

	/** 분석 테이블에 아무것도 넣지 않은 push(정규화·보강·적재의 영구 실패). 받은 것 전부가 거부다. */
	fun unloaded(receipt: ArchiveReceipt, parts: List<ReceiptPart>)
}

/**
 * push 하나의 수집 운영 기록 — ClickHouse 수신 ledger 행과 RDS tenant 생애 요약(ADR 0021 §1·§2).
 *
 * **둘 다 쓰여야 성공이다.** 어느 하나라도 실패하면 예외가 전파되어 push 는 503 이다 — 수신 기록의 내구성을 확인하지
 * 못한 요청에 성공을 돌려주지 않는다. ledger 를 먼저 쓴다. 요약이 실패해 재전송되면 ledger 에는 새 receipt 행이 하나 더
 * 남는다 — ledger 는 수신 사실의 기록이라 합산하지 않으므로 그것이 맞다.
 *
 * live 수신만 기록한다(요약 출처 [SummaryOrigin.LIVE]). 아카이브 재처리는 이 클래스를 거치지 않는다.
 */
class IngestOperationsRecorder(
	private val ledger: IngestLedgerSink,
	private val summaries: TenantIngestSummaryStore,
) : IngestOperations {

	override fun loaded(receipt: ArchiveReceipt, parts: List<Pair<ReceiptPart, NormalizationStats>>) {
		val ledgerReceipt = ledgerReceipt(receipt)
		record(
			receipt,
			parts.map { (part, stats) -> IngestLedgerEntry.normalized(ledgerReceipt, part.product, part.archived?.location?.uri, stats) },
		)
	}

	override fun unloaded(receipt: ArchiveReceipt, parts: List<ReceiptPart>) {
		val ledgerReceipt = ledgerReceipt(receipt)
		record(receipt, parts.map { IngestLedgerEntry.unnormalized(ledgerReceipt, it.product, it.archived?.location?.uri, it.recordCount) })
	}

	private fun record(receipt: ArchiveReceipt, entries: List<IngestLedgerEntry>) {
		ledger.insert(entries)
		val firstObserved = entries.mapNotNull { it.sourceTimeMin }.minOrNull()?.toInstant()
		try {
			summaries.record(UUID.fromString(receipt.tenantId), SummaryOrigin.LIVE, receipt.receivedAt, firstObserved)
		} catch (exception: RuntimeException) {
			throw exception
		} catch (exception: Exception) {
			// 분류되지 않은 SQLException(검사 예외)이다. 수집 진입점은 RuntimeException 만 503 으로 돌리므로 감싼다.
			throw IllegalStateException("telemetry_ops summary: ${exception.message}", exception)
		}
	}

	private fun ledgerReceipt(receipt: ArchiveReceipt) = LedgerReceipt(
		tenantId = requireNotNull(receipt.tenantId) { "검증된 tenant 가 없는 영수증이다" },
		installationId = requireNotNull(receipt.installationId) { "검증된 installation 이 없는 영수증이다" },
		receivedTime = receipt.receivedAt,
		receiptId = receipt.receiptId,
		signal = observationSignal(receipt.signal),
		maskingVersion = receipt.maskingVersion,
	)

	internal companion object {
		fun observationSignal(signal: Signal): ObservationSignal = when (signal) {
			Signal.LOGS -> ObservationSignal.LOG
			Signal.TRACES -> ObservationSignal.SPAN
			Signal.METRICS -> ObservationSignal.METRIC
		}
	}
}
