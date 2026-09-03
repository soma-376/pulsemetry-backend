package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.ProductRouter
import com.team376.pulsemetry.telemetry.collector.masking.AttributeWalker
import com.team376.pulsemetry.telemetry.collector.masking.SecretMasker
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest

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
 * 2. 아카이브     제품별로 갈라 외부 저장소에 쓴다
 * 3. 다음 단계    변환·보강·적재
 * ```
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
	maxDecompressedBytes: Long = OtlpRequestDecoder.DEFAULT_MAX_DECOMPRESSED_BYTES,
) {

	private val decoder = OtlpRequestDecoder(maxDecompressedBytes)
	private val walker = AttributeWalker(SecretMasker())

	public fun handle(request: OtlpHttpRequest): OtlpHttpResponse {
		if (!request.method.equals("POST", ignoreCase = true)) return methodNotAllowed()

		// 상위는 설정된 경로에만 라우트를 등록하므로 그 밖의 경로는 mux 가 404 를 낸다.
		val signal = Signal.ofPath(request.path) ?: return notFound()

		val encoding = OtlpEncoding.ofContentType(request.contentType) ?: return unsupportedMediaType()

		val body = try {
			decoder.decompress(request.contentEncoding, request.body)
		} catch (e: RuntimeException) {
			return status(encoding, 400, GrpcCode.INVALID_ARGUMENT, e.message.orEmpty())
		}

		val builder = signal.newRequestBuilder()
		try {
			encoding.decode(body, builder)
		} catch (e: RuntimeException) {
			return status(encoding, 400, GrpcCode.INVALID_ARGUMENT, e.message.orEmpty())
		}

		// 상위는 레코드가 0건이면 소비자를 부르지 않고 바로 성공을 돌려준다.
		if (recordCount(builder) == 0) return success(signal, encoding)

		if (signal.masked) mask(signal, builder)

		val message = builder.build()

		return try {
			ProductRouter.split(message).forEach { (product, document) ->
				archive.write(product, signal, OtlpJson.toJson(document))
			}
			next.consume(signal, message)
			success(signal, encoding)
		} catch (e: PermanentIngestException) {
			// 영구 오류. 재시도해도 같으므로 클라이언트가 배치를 버리게 한다.
			status(encoding, 500, GrpcCode.INTERNAL, e.message.orEmpty())
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

	/** 상위가 성공 단축에 쓰는 수 — logs 는 레코드, traces 는 span, metrics 는 데이터포인트다. */
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
				rm.scopeMetricsBuilderList.sumOf { it.metricsCount }
			}

		else -> error("모르는 요청 타입이다: ${builder.descriptorForType.fullName}")
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
}
