package com.team376.pulsemetry.telemetry.pipeline

import com.google.protobuf.Message
import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationContext
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt
import com.team376.pulsemetry.telemetry.enricher.EnrichmentUnavailableException
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

/** [IngestStages.transformAll] 의 입력 하나 — Kafka 레코드 하나의 push. */
class TransformInput(
	val signal: Signal,
	val request: Message,
	val receipt: ArchiveReceipt,
)

/** [IngestStages.transformAll] 의 레코드 하나의 결과 — 바꾼 push 이거나, 그 레코드의 영구 실패다. */
sealed interface TransformOutcome {
	class Transformed(val push: TransformedPush) : TransformOutcome

	class Rejected(val error: TransformRejectedException) : TransformOutcome
}

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
		val push = normalizeParts(signal, request, receipt)
		emitNormalizeStats(push.batches)
		return enrichOne(push)
	}

	/**
	 * Kafka 배치 소비 호출 하나의 push 들을 바꾼다(PROJ-345 · ADR 0084). 결과는 입력과 같은 순서다.
	 *
	 * 레코드마다 하는 일은 [transform] 과 같다 — 정규화 실패는 그 레코드의 [TransformOutcome.Rejected] 다. 보강만 묶는다: 정규화에 성공한
	 * 레코드를 모아 [ObservationEnricher.enrichAll] 을 한 번 부르므로 구성원·소속 조회가 호출마다 IN 두 번이다. 그 결과는 호출을 넘지 않는다.
	 *
	 * 예외 분류는 [transform] 과 같다. 신원 없는 영수증과 보강의 일시 장애([EnrichmentUnavailableException]·자원 계열
	 * [NonTransientDataAccessResourceException])는 그대로 던진다 — 부르는 쪽이 기다린다. 그 밖의 [NonTransientDataAccessException] 은 묶음
	 * 조회로는 어느 레코드의 것인지 알 수 없으므로, 정규화한 레코드를 하나씩 [transform] 의 보강으로 다시 돌려 영구 오류를 그 레코드의
	 * `enrich` 거부로 귀속한다. 정규화는 다시 하지 않는다.
	 *
	 * 제외·사용량 계약 지표는 이 호출이 정상 반환할 때만, 정규화에 성공한 레코드마다 한 번 남긴다 — 보강이 일시 장애로 던지면 변환이 하나도
	 * 남지 않고 재호출이 다시 정규화하므로, 바로 남기면 재호출마다 같은 레코드를 다시 센다. 정규화 시간(`normalize`)은 실제로 쓴 시간이라
	 * 정규화할 때마다 잰다.
	 *
	 * @param beforeRecordEnrich 되돌림 경로에서 레코드마다 보강 조회를 시작하기 전에 부른다 — 배치 경로는 호출 마감 검사(`ensureTime`)를 넘겨,
	 *   마감 뒤에는 새 RDB 조회를 시작하지 않는다. 던진 예외는 그대로 나간다(변환도 지표도 남기지 않는다).
	 */
	fun transformAll(inputs: List<TransformInput>, beforeRecordEnrich: () -> Unit = {}): List<TransformOutcome> {
		val outcomes = arrayOfNulls<TransformOutcome>(inputs.size)
		val normalized = ArrayList<Pair<Int, Normalized>>(inputs.size)
		inputs.forEachIndexed { index, input ->
			try {
				normalized += index to normalizeParts(input.signal, input.request, input.receipt)
			} catch (exception: TransformRejectedException) {
				outcomes[index] = TransformOutcome.Rejected(exception)
			}
		}
		if (normalized.isNotEmpty()) {
			try {
				val enriched = observer.measure(PipelineStage.ENRICH_BATCH) { enricher.enrichAll(normalized.map { it.second.batches }) }
				normalized.zip(enriched).forEach { (record, batches) ->
					outcomes[record.first] = TransformOutcome.Transformed(TransformedPush(record.second.parts, batches))
				}
			} catch (exception: NonTransientDataAccessException) {
				// 자원 계열은 일시 장애다 — transform 과 같다. EnrichmentUnavailableException 은 여기 오지 않고 그대로 나간다.
				if (exception is NonTransientDataAccessResourceException) throw exception
				for ((index, record) in normalized) {
					beforeRecordEnrich()
					outcomes[index] = try {
						TransformOutcome.Transformed(enrichOne(record))
					} catch (rejected: TransformRejectedException) {
						TransformOutcome.Rejected(rejected)
					}
				}
			}
			// 보강까지 끝났다(성공 또는 그 레코드의 영구 거부) — 이제 재호출이 이 레코드들을 다시 정규화하지 않는다.
			normalized.forEach { (_, record) -> emitNormalizeStats(record.batches) }
		}
		return outcomes.map { checkNotNull(it) }
	}

	/** 정규화까지 마친 push 하나 — 문서 조각과, 조각마다의 정규화 배치(같은 순서). */
	private class Normalized(val parts: List<ReceiptPart>, val batches: List<ObservationBatch>)

	/** 문서 조각마다 정규화한다. 신원 없는 영수증은 그대로 던지고, 정규화 실패는 [TransformRejectedException] 이다. 지표는 [emitNormalizeStats] 가 남긴다. */
	private fun normalizeParts(signal: Signal, request: Message, receipt: ArchiveReceipt): Normalized {
		val parts = ReceiptPart.of(request, receipt)
		// 신원이 없으면 여기서 멈춘다(503) — 정규화 실패(400)로 섞지 않는다.
		val contexts = parts.map { context(receipt, it) }

		val batches = try {
			observer.measure(PipelineStage.NORMALIZE) { parts.zip(contexts).map { (part, context) -> normalize(part.request, context) } }
		} catch (exception: RuntimeException) {
			throw TransformRejectedException("normalize", parts, "normalize ${signal.path}: ${exception.message}", exception)
		}

		return Normalized(parts, batches)
	}

	/** 정규화 배치의 제외·사용량 계약 지표. [transform] 은 정규화 직후, [transformAll] 은 보강이 끝난 뒤에 부른다. */
	private fun emitNormalizeStats(batches: List<ObservationBatch>) {
		batches.forEach { batch ->
			observer.observe(TelemetryMetric.PIPELINE_EXCLUDED_OBSERVATIONS, batch.stats.excludedSpans.toDouble(), "reason" to "excluded_span")
			observer.observe(TelemetryMetric.PIPELINE_EXCLUDED_OBSERVATIONS, batch.stats.sourceTimeRejections.values.sum().toDouble(), "reason" to "source_time")
			batch.stats.usageContractResults.forEach { (result, count) ->
				observer.observe(TelemetryMetric.PIPELINE_USAGE_CONTRACT, count.toDouble(), "product" to result.product.wire, "outcome" to result.outcome.wire)
			}
		}
	}

	/** push 하나를 보강한다(push 단위 캐시). 비자원 `NonTransientDataAccessException` 만 그 push 의 [TransformRejectedException] 이다. */
	private fun enrichOne(push: Normalized): TransformedPush {
		val enriched = try {
			observer.measure(PipelineStage.ENRICH) { enricher.enrich(push.batches) }
		} catch (exception: NonTransientDataAccessException) {
			// 자원 계열은 ObservationEnricher 가 이미 EnrichmentUnavailableException 으로 감싼다. 여기까지
			// 온 것이 있더라도 일시 장애이므로 503 으로 둔다.
			if (exception is NonTransientDataAccessResourceException) throw exception
			throw TransformRejectedException("enrich", push.parts, "enrich: ${exception.message}", exception)
		}
		return TransformedPush(push.parts, enriched)
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
