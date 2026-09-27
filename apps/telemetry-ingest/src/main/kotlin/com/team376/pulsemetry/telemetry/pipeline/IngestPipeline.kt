package com.team376.pulsemetry.telemetry.pipeline

import com.google.protobuf.Message
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryMetricPointsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkRejectedException
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationBatch
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationContext
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationPipeline
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.SignalConsumer
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt
import com.team376.pulsemetry.telemetry.enricher.observation.ObservationEnricher
import org.springframework.dao.NonTransientDataAccessException
import org.springframework.dao.NonTransientDataAccessResourceException

/**
 * 수집 단계가 넘겨준 요청을 정규화 → 가격 → 보강 → 적재 → 수집 운영 기록으로 흘린다(ADR 0020 §3 처리 순서 ·
 * ADR 0021). **이 클래스가 seam 배선의 전부다.** 단계 모듈은 이웃의 seam 을 구현하지 않고(ADR 0013 · 0014) 데이터
 * 타입만 간선으로 받는다.
 *
 * ## 한 push 의 흐름
 *
 * 1. 영수증의 아카이브 문서마다 조각을 만든다([ReceiptPart]). 조각이 정규화 실행과 ledger 행의 단위다.
 * 2. 조각마다 정규화 2판(가격·`analysis_hash` 포함, [ObservationPipeline])을 돈다. 신원·수신 시각·마스킹 버전은
 *    영수증에서만 온다 — 레코드의 자기신고 값은 신원이 아니다(ADR 0020 §5).
 * 3. 조각 전부를 한 번에 보강한다(push 단위 조회 캐시).
 * 4. 두 분석 테이블에 적재한다. 행은 모두 `RowVersioning.live(receipt 수신 시각)` 이다. 한 push 는 한 시그널이라
 *    실제로는 둘 중 하나만 쓰인다. 구 `enriched_events` 에는 쓰지 않는다.
 * 5. 수집 운영 기록(ledger·요약)을 쓴다([IngestOperations]). **정규화·보강·적재가 영구 실패해도 기록한 뒤
 *    400 을 낸다** — 인증·마스킹·아카이브를 통과한 push 는 수신 사실이다(ADR 0021 §1). 일시 실패(503)면 기록하지
 *    않는다 — 재전송이 새 receipt 로 기록된다. 기록이 실패하면 503 이다(성공을 돌려주지 않는다).
 *
 * 운영 기록은 ADR 0021 이 Proposed 인 동안 설정으로 켠다(`pulsemetry.telemetry.ops.enabled`). 꺼져 있으면 5 를
 * 건너뛴다 — [operations] 가 null 이다.
 *
 * ## `@Transactional` 을 붙이지 마라
 *
 * 붙이면 ClickHouse HTTP 왕복(최대 30초) 동안 RDS 커넥션을 쥔 채로 있게 되어 Hikari 풀이
 * 마른다. 보강의 조회와 요약 upsert 는 각자 자기 트랜잭션으로 충분하다.
 *
 * ## 상태 계약 (허브 ADR 0006 · 허브 계약 `telemetry-ingest.md` §8)
 *
 * 예외 → 상태 매핑은 **이 표가 전부다.** 행을 더하거나 옮기면 허브 §8 표를 같은 커밋에서 고친다.
 * 400 은 [PermanentIngestException] 으로 감싸 올리고, 503 은 예외를 그대로 전파한다 —
 * 수집 진입점이 그 둘을 상태 코드로 바꾼다.
 *
 * | 예외 | 상태 | 이유 |
 * |---|---|---|
 * | 정규화가 던진 것 — 정규화 실패 | 400 | 같은 입력은 재시도해도 같다. 원본은 아카이브에 있다. 개별 관측의 미지원·측정 오류는 예외가 아니라 generic 행·품질 플래그다 |
 * | 보강의 `NonTransientDataAccessException`, 단 자원 계열(`NonTransientDataAccessResourceException`) 제외 | 400 | RDS 스키마 드리프트 같은 영구 오류. 자원 계열은 연결 실패라 일시 장애다 |
 * | `TelemetrySinkRejectedException` — ClickHouse 4xx, 또는 컬럼 타입에 들어가지 않는 값의 적재 전 거부 | 400 | 요청이 거부됐다. 다시 보내도 같다 |
 * | `EnrichmentUnavailableException` · `TelemetrySinkUnavailableException` · `TelemetryOpsUnavailableException` | 503 | RDS·ClickHouse 에 닿지 못했거나 운영 스키마가 아직 적용되지 않았다 |
 * | 그 밖의 예외 | 503 | 상태가 실리지 않은 오류의 기본. **기본을 영구 오류로 바꾸지 마라** — 잘못 재시도하는 비용은 데몬의 3회 예산으로 막혀 있지만, 잘못 폐기하는 비용은 되돌릴 수 없다 |
 * | 인증 조회의 `RuntimeException` — 이 파이프라인 **앞**, 필터 단계 | 503 | RDS 에 닿지 못했다. `SecurityConfig` 가 필터에 넘긴 핸들러가 같은 본문·`Retry-After` 로 쓴다. 401 이면 데몬이 토큰을 폐기한다 |
 */
class IngestPipeline(
	/** 정규화 2판 진입점 — 보통 제품 프로파일을 등록한 [ObservationPipeline] 의 `run` 이다. 테스트가 실패를 흉내 낼 때 바꾼다. */
	private val normalize: (Message, ObservationContext) -> ObservationBatch,
	private val enricher: ObservationEnricher,
	private val events: TelemetryEventsSink,
	private val metricPoints: TelemetryMetricPointsSink,
	private val schema: ClickHouseSchema,
	private val operations: IngestOperations?,
) : SignalConsumer {

	override fun consume(signal: Signal, request: Message, receipt: ArchiveReceipt) {
		val parts = ReceiptPart.of(request, receipt)
		// 신원이 없으면 여기서 멈춘다(503) — 정규화 실패(400)로 섞지 않는다.
		val contexts = parts.map { context(receipt, it) }

		val batches = try {
			parts.zip(contexts).map { (part, context) -> normalize(part.request, context) }
		} catch (exception: RuntimeException) {
			throw permanent(receipt, parts, "normalize ${signal.path}: ${exception.message}", exception)
		}

		val enriched = try {
			enricher.enrich(batches)
		} catch (exception: NonTransientDataAccessException) {
			// 자원 계열은 ObservationEnricher 가 이미 EnrichmentUnavailableException 으로 감싼다. 여기까지
			// 온 것이 있더라도 일시 장애이므로 503 으로 둔다.
			if (exception is NonTransientDataAccessResourceException) throw exception
			throw permanent(receipt, parts, "enrich: ${exception.message}", exception)
		}

		// 테이블이 없는 채로 INSERT 하면 404 → 영구 오류 → 즉시 폐기다. 그 앞에서 막는다.
		schema.ensureApplied()

		val versioning = RowVersioning.live(receipt.receivedAt)
		try {
			events.insert(enriched.flatMap { it.events }, versioning)
			metricPoints.insert(enriched.flatMap { it.metricPoints }, versioning)
		} catch (exception: TelemetrySinkRejectedException) {
			throw permanent(receipt, parts, exception.message.orEmpty(), exception)
		}

		operations?.loaded(receipt, parts.zip(batches.map { it.stats }))
	}

	/** 영구 실패 — 수신 사실을 기록한 뒤 400 으로 올린다. 기록이 실패하면 그 예외(503)가 대신 나간다. */
	private fun permanent(receipt: ArchiveReceipt, parts: List<ReceiptPart>, message: String, cause: Throwable): PermanentIngestException {
		operations?.unloaded(receipt, parts)
		return PermanentIngestException(message, cause)
	}

	/** 수집 시점의 문맥. 신원은 인증이 검증해 영수증에 실은 값뿐이다 — 없으면 적재할 수 없다(503). */
	private fun context(receipt: ArchiveReceipt, part: ReceiptPart) = ObservationContext(
		tenantId = checkNotNull(receipt.tenantId) { "검증된 tenant 가 없는 영수증이다" },
		installationId = checkNotNull(receipt.installationId) { "검증된 installation 이 없는 영수증이다" },
		receivedTime = EpochNanos.of(receipt.receivedAt),
		maskingVersion = receipt.maskingVersion,
		stampVersion = receipt.identityVersion,
		archive = part.locator,
	)
}
