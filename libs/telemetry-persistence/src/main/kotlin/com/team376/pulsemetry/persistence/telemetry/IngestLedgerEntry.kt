package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.NormalizationStats
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationSignal
import java.time.Instant

/**
 * 수신 ledger 행의 receipt 몫 — push 하나에 하나다(ADR 0021 §1). 검증된 신원과 서버 수신 시각이 여기 온다.
 */
public data class LedgerReceipt(
	public val tenantId: String,
	public val installationId: String,
	/** archive receipt 의 서버 수신 시각. 저장 재시도 시각이 아니다. */
	public val receivedTime: Instant,
	public val receiptId: String,
	public val signal: ObservationSignal,
	public val maskingVersion: String,
) {
	init {
		require(tenantId.isNotBlank() && installationId.isNotBlank()) { "ledger 는 검증된 신원이 있는 push 만 기록한다" }
		require(receiptId.isNotBlank()) { "receipt_id 가 비었다" }
	}
}

/**
 * 수신 ledger 한 행 — (receipt, signal, archive product) 하나의 수신 사실(ADR 0021 §1). 분석 행이 아니고 사용량도 아니다.
 *
 * 두 경로로 만든다. 정규화를 마친 문서는 [normalized] 로 그 집계를 옮기고, 정규화가 통째로 실패한 문서는 [unnormalized] 로
 * 받은 수만 남긴다 — 시각 범위는 null 이고 받은 것 전부가 거부다. 인증·마스킹·아카이브를 통과한 push 는 이후 정규화가
 * 실패해도 기록한다.
 */
public data class IngestLedgerEntry(
	public val receipt: LedgerReceipt,
	/** 아카이브 product 구간. 모르는 서비스면 그 경로의 값이다. */
	public val product: String,
	/** 분석 테이블로 간 관측의 `source_time` 범위. 하나도 없으면 null. */
	public val sourceTimeMin: EpochNanos?,
	public val sourceTimeMax: EpochNanos?,
	/** 받은 레코드 수(로그 레코드·스팬·metric point). 사용량이 아니다. */
	public val recordCount: Int,
	/** 분석 테이블에 넣지 않은 레코드 수 — `source_time` 거부, 허용 목록 밖 스팬, 삭제 경계 이전 관측(ADR 0024 §6). */
	public val rejectedCount: Int,
	/** 이 문서가 들어 있는 아카이브 객체. 없으면 null. */
	public val archiveRef: String?,
) {
	init {
		require(product.isNotBlank()) { "product 가 비었다" }
		require(recordCount >= 0 && rejectedCount in 0..recordCount) { "레코드 수가 맞지 않는다: 받음 $recordCount · 거부 $rejectedCount" }
		require((sourceTimeMin == null) == (sourceTimeMax == null)) { "source_time 범위는 양끝이 함께 있거나 함께 없다" }
		require(sourceTimeMin == null || sourceTimeMin <= sourceTimeMax!!) { "source_time_min 이 max 보다 늦다" }
	}

	public companion object {
		/**
		 * 정규화를 마친 문서 — 받은 수와 정규화의 거부 수는 [stats] 에서 옮긴다. 삭제 경계로 뺀 관측([beforeBoundary])은 거부에 더하고,
		 * 시각 범위는 실제로 적재한 관측의 것이다(ADR 0024 §6). 뺀 것이 없으면 [stats] 의 범위 그대로다.
		 */
		public fun normalized(
			receipt: LedgerReceipt,
			product: String,
			archiveRef: String?,
			stats: NormalizationStats,
			beforeBoundary: Int = 0,
			sourceTimeMin: EpochNanos? = stats.sourceTimeMin,
			sourceTimeMax: EpochNanos? = stats.sourceTimeMax,
		): IngestLedgerEntry {
			require(beforeBoundary >= 0) { "경계로 뺀 수가 음수다: $beforeBoundary" }
			return IngestLedgerEntry(receipt, product, sourceTimeMin, sourceTimeMax, stats.received, stats.rejectedCount + beforeBoundary, archiveRef)
		}

		/** 정규화가 통째로 실패한 문서 — 받은 것 전부가 분석 테이블 밖이다. */
		public fun unnormalized(receipt: LedgerReceipt, product: String, archiveRef: String?, received: Int): IngestLedgerEntry =
			IngestLedgerEntry(receipt, product, null, null, received, received, archiveRef)
	}
}
