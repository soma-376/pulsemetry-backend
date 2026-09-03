package com.team376.pulsemetry.telemetry.pipeline

import com.google.protobuf.Message
import com.team376.pulsemetry.persistence.telemetry.EnrichedEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkRejectedException
import com.team376.pulsemetry.telemetry.adapter.Normalizer
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.SignalConsumer
import com.team376.pulsemetry.telemetry.enricher.Enricher

/**
 * 수집 단계가 넘겨준 요청을 변환 → 보강 → 적재로 흘린다. **이 클래스가 seam 배선의 전부다.**
 *
 * 단계 모듈은 이웃의 seam 을 구현하지 않고(ADR 0013 · 0014) 데이터 타입만 간선으로 받는다.
 * 셋을 실제로 잇는 것은 조립 앱이고, ADR 0013 Follow-up 이 "그때 이 결정이 실제로 한 줄로
 * 끝나는지 확인한다"고 적어 둔 자리가 여기다 — 본문은 네 줄이고, 그중 둘은 상태 계약이다.
 *
 * ## `@Transactional` 을 붙이지 마라
 *
 * 붙이면 ClickHouse HTTP 왕복(최대 30초) 동안 RDS 커넥션을 쥔 채로 있게 되어 Hikari 풀이
 * 마른다. 보강의 조회는 자기 트랜잭션으로 충분하다.
 *
 * ## 상태 계약 (허브 ADR 0006)
 *
 * - `TelemetrySinkRejectedException`(ClickHouse 4xx) → [PermanentIngestException] → **400**.
 *   데몬이 즉시 폐기한다.
 * - `TelemetrySinkUnavailableException` · `EnrichmentUnavailableException` → 그대로 전파 → **503**.
 * - 그 밖의 예외도 503 이다. **기본을 영구 오류로 바꾸지 마라** — 잘못 재시도하는 비용은
 *   데몬의 3회 예산으로 막혀 있지만, 잘못 폐기하는 비용은 되돌릴 수 없다.
 */
class IngestPipeline(
	private val enricher: Enricher,
	private val sink: EnrichedEventsSink,
	private val schema: ClickHouseSchema,
) : SignalConsumer {

	override fun consume(signal: Signal, request: Message) {
		val events = Normalizer.normalize(request)
		// 제품 namespace 밖의 요청이다. 아카이브는 이미 남았고 적재할 것이 없다.
		if (events.isEmpty()) return

		val enriched = enricher.enrich(events)

		// 테이블이 없는 채로 INSERT 하면 404 → 영구 오류 → 즉시 폐기다. 그 앞에서 막는다.
		schema.ensureApplied()

		try {
			sink.insert(enriched)
		} catch (exception: TelemetrySinkRejectedException) {
			throw PermanentIngestException(exception.message.orEmpty(), exception)
		}
	}
}
