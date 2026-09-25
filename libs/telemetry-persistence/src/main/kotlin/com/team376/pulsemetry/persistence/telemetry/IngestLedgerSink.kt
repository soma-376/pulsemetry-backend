package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos

/**
 * ClickHouse `telemetry_ingest_ledger` 에 쓰는 유일한 주체(ADR 0021 §1·§4). DDL 이 이 모듈 아래 있다.
 *
 * 배치 하나를 INSERT 하나로 보낸다. 정렬 키가 (tenant, installation, received_time, receipt_id, signal, product) 라 같은
 * receipt 의 **저장 재시도**는 한 행으로 수렴한다(`FINAL`). HTTP 재전송은 새 receipt 라 새 행이다.
 *
 * 행 표기는 분석 테이블과 같은 작성기([AnalysisRowWriter])를 쓴다 — 모든 컬럼 명시, 정수 토큰, 나노초 문자열.
 * 실패 분류는 [ClickHouseHttpClient] 그대로다(4xx 영구, 연결·5xx·429·408 일시). 기록의 내구성을 확인하지 못한 push 에
 * 성공을 돌려주지 않는 것은 부르는 쪽의 몫이다(ADR 0021 §1).
 *
 * 빈이 아니다(ADR 0011).
 */
public class IngestLedgerSink(private val client: ClickHouseHttpClient) {

	/** 배치를 적재하고 적재한 행 수를 돌려준다. 빈 배치는 요청을 보내지 않는다. */
	public fun insert(entries: List<IngestLedgerEntry>): Int {
		if (entries.isEmpty()) return 0
		val body = entries.joinToString(separator = "\n", postfix = "\n") { toJson(it) }
		client.execute(QUERY, body.toByteArray(Charsets.UTF_8))
		return entries.size
	}

	internal companion object {
		const val TABLE: String = "telemetry_ingest_ledger"

		/** `V3__telemetry_ingest_ledger.sql` 의 컬럼 선언. 실제 테이블과 같은지는 컨테이너 테스트가 본다. */
		val COLUMNS: List<ColumnSpec> = listOf(
			ColumnSpec("tenant_id", "LowCardinality(String)"),
			ColumnSpec("installation_id", "String"),
			ColumnSpec("received_time", "DateTime64(9, 'UTC')"),
			ColumnSpec("receipt_id", "String"),
			ColumnSpec("signal", "LowCardinality(String)"),
			ColumnSpec("product", "LowCardinality(String)"),
			ColumnSpec("source_time_min", "Nullable(DateTime64(9, 'UTC'))"),
			ColumnSpec("source_time_max", "Nullable(DateTime64(9, 'UTC'))"),
			ColumnSpec("record_count", "UInt32"),
			ColumnSpec("rejected_count", "UInt32"),
			ColumnSpec("archive_ref", "Nullable(String)"),
			ColumnSpec("masking_version", "String"),
		)

		private val QUERY: String = AnalysisInsert.query(TABLE, COLUMNS)

		fun toJson(entry: IngestLedgerEntry): String {
			val receipt = entry.receipt
			val w = AnalysisRowWriter(COLUMNS)
			w.lowCardinality("tenant_id", receipt.tenantId)
			w.string("installation_id", receipt.installationId)
			w.time("received_time", EpochNanos.of(receipt.receivedTime))
			w.string("receipt_id", receipt.receiptId)
			w.lowCardinality("signal", receipt.signal)
			w.lowCardinality("product", entry.product)
			w.time("source_time_min", entry.sourceTimeMin)
			w.time("source_time_max", entry.sourceTimeMax)
			w.uint32("record_count", entry.recordCount.toUInt())
			w.uint32("rejected_count", entry.rejectedCount.toUInt())
			w.string("archive_ref", entry.archiveRef)
			w.string("masking_version", receipt.maskingVersion)
			return w.build()
		}
	}
}
