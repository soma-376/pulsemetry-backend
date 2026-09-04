package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.ProductRouter
import com.team376.pulsemetry.telemetry.collector.masking.AttributeWalker
import com.team376.pulsemetry.telemetry.collector.masking.SecretMasker
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.metrics.v1.Metric

/**
 * 수집 단계의 진입점. OTLP 수신 → 마스킹 → 원본 아카이브 → 다음 단계.
 *
 * 현행 collector 설정의 파이프라인 아홉을 한 경로로 접은 것이다. 실시간 스트림과 제품별 아카이브가
 * 별개 파이프라인이었던 것은 collector 가 그렇게밖에 표현할 수 없었기 때문이고, 홉이 사라진
 * 지금은 한 번 수신해 한 번 마스킹하고 두 갈래로 보내면 된다(허브 ADR 0005).
 *
 * ## 순서가 계약이다
 *
 * ```
 * 1. 마스킹      logs·traces 만. metrics 는 현행 설정에 redaction 이 없다 (Signal.masked)
 * 2. 신원 스탬프  검증된 tenant·installation 을 리소스 속성으로 승격한다
 * 3. 아카이브     제품별로 갈라 외부 저장소에 쓴다
 * 4. 다음 단계    변환·보강·적재
 * ```
 *
 * **스탬프가 아카이브보다 먼저다.** 신원은 멱등 키의 재료이기 때문이다 — 변환 단계가
 * `tenant.id` 를 `record_id` 해시의 첫 자리에 넣고, 그 값이 `enriched_events` 의 `ORDER BY` 키다.
 * 신원 없는 원본을 나중에 재처리하면 그 자리에 `(unknown)` 이 들어가 실시간 경로가 만든 키와
 * **다른 키**가 나오고, 합쳐져야 할 행이 중복으로 쌓인다(ADR 0012 · 0016).
 *
 * **아카이브가 다음 단계보다 먼저다.** 허브 `architecture/overview.md` 2절이 "Adapter 이후 변환이
 * 실패하면 그 시그널은 Object Storage 에만 남는다. 이것이 흐름 D 의 복구 원천이며 별도 DLQ 에
 * 의존하지 않는다"고 못박았다. 순서를 뒤집으면 변환 실패가 곧 데이터 유실이 된다.
 * 같은 이유로 **아카이브가 실패하면 다음 단계를 부르지 않고 503 을 낸다** — 복구 원천 없이
 * 파이프라인을 진행시키지 않는다.
 *
 * ## 인증은 이 앞이다
 *
 * 필터 체인이 통과시킨 요청만 여기 닿는다(허브 ADR 0005). 폐기된 토큰이나 정지된 tenant 의
 * 요청처럼 거부될 데이터가 외부 저장소에 적재되면 안 되기 때문이다. **이 클래스는 인증을 하지
 * 않는다** — 조립 앱이 `:libs:security` 를 앞에 세운다.
 *
 * ## 스테레오타입을 달지 않는다 (ADR 0011)
 *
 * 빈 등록과 HTTP 라우팅은 앱이 한다. 앱은 자기 요청에서 [OtlpHttpRequest] 를 만들어 [handle] 을
 * 부르고 [OtlpHttpResponse] 를 그대로 내보내면 된다.
 */
public class OtlpIngestHandler(
	private val archive: ArchiveWriter,
	private val next: SignalConsumer,
	private val identity: IdentitySource = IdentitySource { null },
	maxDecompressedBytes: Long = OtlpRequestDecoder.DEFAULT_MAX_DECOMPRESSED_BYTES,
) {

	private val decoder = OtlpRequestDecoder(maxDecompressedBytes)
	private val walker = AttributeWalker(SecretMasker())

	public fun handle(request: OtlpHttpRequest): OtlpHttpResponse {
		if (!request.method.equals("POST", ignoreCase = true)) return methodNotAllowed()

		// 상위는 설정된 경로에만 라우트를 등록하므로 그 밖의 경로는 mux 가 404 를 낸다.
		val signal = Signal.ofPath(request.path) ?: return notFound()

		val encoding = OtlpEncoding.ofContentType(request.contentType) ?: return unsupportedMediaType()

		// 디코드·압축 해제 실패는 전부 400 이다(허브 ADR 0006). IOException 계열도 잡는다 —
		// 깨진 gzip 은 ZipException, 깨진 protobuf 는 InvalidProtocolBufferException 이고 둘 다
		// RuntimeException 이 아니다. 놓치면 500 이 되어 데몬이 깨진 배치를 영구히 재시도한다.
		val body = try {
			decoder.decompress(request.contentEncoding, request.body)
		} catch (e: Exception) {
			return status(encoding, 400, GrpcCode.INVALID_ARGUMENT, e.message.orEmpty())
		}

		val builder = signal.newRequestBuilder()
		try {
			encoding.decode(body, builder)
		} catch (e: Exception) {
			return status(encoding, 400, GrpcCode.INVALID_ARGUMENT, e.message.orEmpty())
		}

		// 상위는 레코드가 0건이면 소비자를 부르지 않고 바로 성공을 돌려준다.
		if (recordCount(builder) == 0) return success(signal, encoding)

		if (signal.masked) mask(signal, builder)

		// 아카이브보다 먼저다. 빌더에 찍으므로 아카이브와 다음 단계가 같은 값을 본다.
		identity.current()?.let { IdentityStamper.stamp(builder, it) }

		val message = builder.build()

		return try {
			ProductRouter.split(message).forEach { (product, document) ->
				archive.write(product, signal, OtlpJson.toJson(document))
			}
			next.consume(signal, message)
			success(signal, encoding)
		} catch (e: PermanentIngestException) {
			// 영구 오류. 재시도해도 같으므로 클라이언트가 배치를 버리게 한다 — 그러려면 4xx 여야 한다.
			// 데몬은 5xx 를 전부 재시도하므로 500 으로는 폐기가 만들어지지 않는다(허브 ADR 0006).
			status(encoding, 400, GrpcCode.INVALID_ARGUMENT, e.message.orEmpty())
		} catch (e: RuntimeException) {
			// 상태가 실리지 않은 오류의 기본은 재시도 가능이다 — 상위 GetStatusFromError 와 같다.
			status(encoding, 503, GrpcCode.UNAVAILABLE, e.message.orEmpty())
		}
	}

	private fun mask(signal: Signal, builder: Message.Builder) {
		when (signal) {
			Signal.LOGS -> walker.maskLogs(builder as ExportLogsServiceRequest.Builder)
			Signal.TRACES -> walker.maskTraces(builder as ExportTraceServiceRequest.Builder)
			// Signal.masked 가 false 라 여기 오지 않는다. when 을 닫아 두려고 남긴다.
			Signal.METRICS -> Unit
		}
	}

	/**
	 * 상위가 성공 단축에 쓰는 수 — logs 는 `LogRecordCount()`, traces 는 `SpanCount()`, metrics 는
	 * `DataPointCount()` 다. metrics 는 메트릭 수가 아니라 **데이터포인트 수**라, 데이터포인트 없는
	 * 메트릭만 담긴 요청은 소비자를 부르지 않고 200 이다.
	 */
	private fun recordCount(builder: Message.Builder): Int = when (builder) {
		is ExportLogsServiceRequest.Builder ->
			builder.resourceLogsBuilderList.sumOf { rl ->
				rl.scopeLogsBuilderList.sumOf { it.logRecordsCount }
			}

		is ExportTraceServiceRequest.Builder ->
			builder.resourceSpansBuilderList.sumOf { rs ->
				rs.scopeSpansBuilderList.sumOf { it.spansCount }
			}

		is ExportMetricsServiceRequest.Builder ->
			builder.resourceMetricsBuilderList.sumOf { rm ->
				rm.scopeMetricsBuilderList.sumOf { sm -> sm.metricsBuilderList.sumOf { dataPointCount(it) } }
			}

		else -> error("모르는 요청 타입이다: ${builder.descriptorForType.fullName}")
	}

	/** 다섯 데이터 종류 중 하나만 설정된다(oneof). 설정되지 않은 것은 0 이다. */
	private fun dataPointCount(metric: Metric.Builder): Int = when (metric.dataCase) {
		Metric.DataCase.GAUGE -> metric.gauge.dataPointsCount
		Metric.DataCase.SUM -> metric.sum.dataPointsCount
		Metric.DataCase.HISTOGRAM -> metric.histogram.dataPointsCount
		Metric.DataCase.EXPONENTIAL_HISTOGRAM -> metric.exponentialHistogram.dataPointsCount
		Metric.DataCase.SUMMARY -> metric.summary.dataPointsCount
		Metric.DataCase.DATA_NOT_SET, null -> 0
	}

	private fun success(signal: Signal, encoding: OtlpEncoding) = OtlpHttpResponse(
		status = 200,
		contentType = encoding.contentType,
		body = OtlpResponses.success(signal, encoding),
	)

	private fun status(encoding: OtlpEncoding, httpStatus: Int, code: GrpcCode, message: String) =
		OtlpHttpResponse(
			status = httpStatus,
			contentType = encoding.contentType,
			body = OtlpResponses.status(encoding, code, message),
		)

	/** 상위 `handleUnmatchedMethod` — 본문이 `text/plain` 이다. */
	private fun methodNotAllowed() = OtlpHttpResponse(
		status = 405,
		contentType = "text/plain",
		body = "405 method not allowed, supported: [POST]".toByteArray(Charsets.UTF_8),
	)

	/** 상위 `handleUnmatchedContentType` — 본문이 `text/plain` 이다. */
	private fun unsupportedMediaType() = OtlpHttpResponse(
		status = 415,
		contentType = "text/plain",
		body = (
			"415 unsupported media type, supported: " +
				"[${OtlpEncoding.JSON.contentType}, ${OtlpEncoding.PROTOBUF.contentType}]"
			).toByteArray(Charsets.UTF_8),
	)

	private fun notFound() = OtlpHttpResponse(
		status = 404,
		contentType = "text/plain",
		body = "404 not found".toByteArray(Charsets.UTF_8),
	)

	public companion object {
		/**
		 * 압축을 푼 뒤 본문의 기본 상한. 실제 상한은 생성자 인자가 정하고, 이 값은 조립 앱이
		 * 자기 설정의 기본값으로 쓰라고 공개한다 — 디코더 자체는 `internal` 이다.
		 */
		public const val DEFAULT_MAX_DECOMPRESSED_BYTES: Long =
			OtlpRequestDecoder.DEFAULT_MAX_DECOMPRESSED_BYTES
	}
}
