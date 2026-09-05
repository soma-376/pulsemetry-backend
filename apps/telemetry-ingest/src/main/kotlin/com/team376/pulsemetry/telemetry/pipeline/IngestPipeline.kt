package com.team376.pulsemetry.telemetry.pipeline

import com.google.protobuf.Message
import com.team376.pulsemetry.persistence.telemetry.EnrichedEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkRejectedException
import com.team376.pulsemetry.telemetry.adapter.Normalizer
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.SignalConsumer
import com.team376.pulsemetry.telemetry.enricher.Enricher
import org.springframework.dao.NonTransientDataAccessException
import org.springframework.dao.NonTransientDataAccessResourceException

/**
 * 수집 단계가 넘겨준 요청을 변환 → 보강 → 적재로 흘린다. **이 클래스가 seam 배선의 전부다.**
 *
 * 단계 모듈은 이웃의 seam 을 구현하지 않고(ADR 0013 · 0014) 데이터 타입만 간선으로 받는다.
 * 셋을 실제로 잇는 것은 조립 앱이고, 그 자리가 여기다.
 *
 * ## `@Transactional` 을 붙이지 마라
 *
 * 붙이면 ClickHouse HTTP 왕복(최대 30초) 동안 RDS 커넥션을 쥔 채로 있게 되어 Hikari 풀이
 * 마른다. 보강의 조회는 자기 트랜잭션으로 충분하다.
 *
 * ## 상태 계약 (허브 ADR 0006 · 허브 계약 `telemetry-ingest.md` §8)
 *
 * 예외 → 상태 매핑은 **이 표가 전부다.** 행을 더하거나 옮기면 허브 §8 표를 같은 커밋에서 고친다.
 * 400 은 [PermanentIngestException] 으로 감싸 올리고, 503 은 예외를 그대로 전파한다 —
 * 수집 진입점이 그 둘을 상태 코드로 바꾼다.
 *
 * | 예외 | 상태 | 이유 |
 * |---|---|---|
 * | `Normalizer` 가 던진 것 — 정규화 실패 | 400 | 같은 입력은 재시도해도 같다. 원본은 아카이브에 있다 |
 * | 보강의 `NonTransientDataAccessException`, 단 자원 계열(`NonTransientDataAccessResourceException`) 제외 | 400 | RDS 스키마 드리프트 같은 영구 오류. 자원 계열은 연결 실패라 일시 장애다 |
 * | `TelemetrySinkRejectedException` — ClickHouse 4xx | 400 | 요청이 거부됐다 |
 * | `EnrichmentUnavailableException` · `TelemetrySinkUnavailableException` | 503 | RDS·ClickHouse 에 닿지 못했다 |
 * | 그 밖의 예외 | 503 | 상태가 실리지 않은 오류의 기본. **기본을 영구 오류로 바꾸지 마라** — 잘못 재시도하는 비용은 데몬의 3회 예산으로 막혀 있지만, 잘못 폐기하는 비용은 되돌릴 수 없다 |
 */
class IngestPipeline(
	private val enricher: Enricher,
	private val sink: EnrichedEventsSink,
	private val schema: ClickHouseSchema,
	/** 변환 단계 진입점. 테스트가 정규화 실패를 흉내 낼 때만 바꾼다. */
	private val normalize: (Message) -> List<Normalized> = { Normalizer.normalize(it) },
) : SignalConsumer {

	override fun consume(signal: Signal, request: Message) {
		val events = try {
			normalize(request)
		} catch (exception: RuntimeException) {
			throw PermanentIngestException("normalize ${signal.path}: ${exception.message}", exception)
		}
		// 제품 namespace 밖의 요청이다. 아카이브는 이미 남았고 적재할 것이 없다.
		if (events.isEmpty()) return

		val enriched = try {
			enricher.enrich(events)
		} catch (exception: NonTransientDataAccessException) {
			// 자원 계열은 OrgProvider 가 이미 EnrichmentUnavailableException 으로 감싼다. 여기까지
			// 온 것이 있더라도 일시 장애이므로 503 으로 둔다.
			if (exception is NonTransientDataAccessResourceException) throw exception
			throw PermanentIngestException("enrich: ${exception.message}", exception)
		}

		// 테이블이 없는 채로 INSERT 하면 404 → 영구 오류 → 즉시 폐기다. 그 앞에서 막는다.
		schema.ensureApplied()

		try {
			sink.insert(enriched)
		} catch (exception: TelemetrySinkRejectedException) {
			throw PermanentIngestException(exception.message.orEmpty(), exception)
		}
	}
}
