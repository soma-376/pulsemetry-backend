package com.team376.pulsemetry.telemetry.pipeline

import com.google.protobuf.Message
import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationContext
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt
import com.team376.pulsemetry.telemetry.enricher.observation.ObservationEnricher
import com.team376.pulsemetry.telemetry.model.EnrichedBatch
import com.team376.pulsemetry.telemetry.model.EpochNanos
import com.team376.pulsemetry.telemetry.model.ObservationBatch
import com.team376.pulsemetry.telemetry.observability.PipelineStage
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.measure
import com.team376.pulsemetry.telemetry.observability.observe
import org.springframework.dao.NonTransientDataAccessException
import org.springframework.dao.NonTransientDataAccessResourceException

/** push 하나를 적재 직전까지 바꾼 결과 — 문서 조각과, 조각마다 정규화·보강한 배치(같은 순서). */
class TransformedPush(
	val parts: List<ReceiptPart>,
	val enriched: List<EnrichedBatch>,
)

/**
 * 정규화 또는 보강이 영구 실패했다 — 같은 입력은 다시 해도 같다. 아직 아무것도 쓰지 않았다.
 *
 * [stage] 는 `normalize`·`enrich` 이고 [parts] 는 수신 ledger 에 미적재로 남길 조각이다. 메시지는 기존 HTTP 400 의 문구와 같다.
 */
class TransformRejectedException(
	val stage: String,
	val parts: List<ReceiptPart>,
	message: String,
	cause: Throwable,
) : RuntimeException(message, cause)

/**
 * 수집 단계가 넘긴 push 를 적재 직전까지 바꾸는 단계들 — 문서 조각 → 정규화 2판 → 보강, 그리고 삭제 경계로 거르기(ADR 0020 §3 · ADR 0024 §3).
 *
 * [IngestPipeline](push 하나)과 Kafka 배치 경로(PROJ-341)가 같은 인스턴스를 쓴다. 적재·운영 기록은 하지 않는다 — 어떤 단위로 쓸지는
 * 부르는 쪽이 정한다. 예외 분류는 [IngestPipeline] KDoc 의 표와 같다: 정규화 실패와 보강의 비자원 `NonTransientDataAccessException` 은
 * [TransformRejectedException], 그 밖의 예외(신원 없는 영수증·보강의 일시 장애)는 그대로 전파한다.
 */
class IngestStages(
	/** 정규화 2판 진입점 — 보통 제품 프로파일을 등록한 `ObservationPipeline` 의 `run` 이다. 테스트가 실패를 흉내 낼 때 바꾼다. */
	private val normalize: (Message, ObservationContext) -> ObservationBatch,
	private val enricher: ObservationEnricher,
	private val observer: TelemetryObserver = TelemetryObserver.NONE,
) {

	fun transform(signal: Signal, request: Message, receipt: ArchiveReceipt): TransformedPush {
		val parts = ReceiptPart.of(request, receipt)
		// 신원이 없으면 여기서 멈춘다(503) — 정규화 실패(400)로 섞지 않는다.
		val contexts = parts.map { context(receipt, it) }

		val batches = try {
			observer.measure(PipelineStage.NORMALIZE) { parts.zip(contexts).map { (part, context) -> normalize(part.request, context) } }
		} catch (exception: RuntimeException) {
			throw TransformRejectedException("normalize", parts, "normalize ${signal.path}: ${exception.message}", exception)
		}

		batches.forEach { batch ->
			observer.observe(TelemetryMetric.PIPELINE_EXCLUDED_OBSERVATIONS, batch.stats.excludedSpans.toDouble(), "reason" to "excluded_span")
			observer.observe(TelemetryMetric.PIPELINE_EXCLUDED_OBSERVATIONS, batch.stats.sourceTimeRejections.values.sum().toDouble(), "reason" to "source_time")
			batch.stats.usageContractResults.forEach { (result, count) ->
				observer.observe(TelemetryMetric.PIPELINE_USAGE_CONTRACT, count.toDouble(), "product" to result.product.wire, "outcome" to result.outcome.wire)
			}
		}
		val enriched = try {
			observer.measure(PipelineStage.ENRICH) { enricher.enrich(batches) }
		} catch (exception: NonTransientDataAccessException) {
			// 자원 계열은 ObservationEnricher 가 이미 EnrichmentUnavailableException 으로 감싼다. 여기까지
			// 온 것이 있더라도 일시 장애이므로 503 으로 둔다.
			if (exception is NonTransientDataAccessResourceException) throw exception
			throw TransformRejectedException("enrich", parts, "enrich: ${exception.message}", exception)
		}
		return TransformedPush(parts, enriched)
	}

	/** 삭제 경계 이전 관측을 뺀다. 뺀 수는 지표로 남긴다 — ledger 의 거부 수는 [RetainedBatch.loaded] 가 옮긴다. */
	fun retain(push: TransformedPush, boundary: AnalysisWriteBoundary): List<RetainedBatch> {
		val retained = push.enriched.map { RetainedBatch.of(it, boundary) }
		observer.observe(TelemetryMetric.PIPELINE_EXCLUDED_OBSERVATIONS, retained.sumOf { it.loaded.beforeBoundary }.toDouble(), "reason" to "retention")
		return retained
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
