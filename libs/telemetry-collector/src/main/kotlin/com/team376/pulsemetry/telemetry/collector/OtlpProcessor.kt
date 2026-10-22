package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.ArchivedDocument
import com.team376.pulsemetry.telemetry.collector.archive.ProductRouter
import com.team376.pulsemetry.telemetry.collector.masking.AttributeWalker
import com.team376.pulsemetry.telemetry.collector.masking.MaskingPolicy
import com.team376.pulsemetry.telemetry.collector.masking.SecretMasker
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.metrics.v1.Metric
import java.time.Instant
import java.util.UUID

/**
 * RDB·인증 계층이 **검증을 마친** 신원. 토큰·principal 타입은 여기 나타나지 않는다 — 조립 앱이 값만 꺼내 넘긴다(ADR 0011·0067).
 *
 * Gateway 의 claim(`GatewayAuthContext`)을 그대로 옮겨 담지 않는다. Kafka 경로는 RDB 판정의 결과(자격 기록의 행)로 만든다.
 */
public data class AuthorizedTelemetryIdentity(
	public val tenantId: UUID,
	public val installationId: UUID,
)

/**
 * 한 번의 처리에 쓰는 수신 문맥. 영수증의 receipt ID·수신 시각이 이 값이다(ADR 0067 Decision 1).
 *
 * - HTTP — 요청마다 새 UUID 와 진입 시각. 재전송은 새 영수증이다(ADR 0021).
 * - Kafka — Gateway 의 message ID 와 접수 시각. **재시도·재전달에도 같다** — row version 이 흔들리지 않는다.
 */
public class VerifiedIngestContext(
	public val receiptId: UUID,
	public val receivedAt: Instant,
	public val identity: AuthorizedTelemetryIdentity,
)

/**
 * 받은 본문이 처리할 수 없는 형태다 — 영구 실패. HTTP 400, Kafka 는 offset 확정이다.
 *
 * [reason] 은 로그용 코드다(`unsupported_content_encoding`·`body_too_large`·`decompress_failed`·`decode_failed`·`masking_failed`).
 * 메시지는 HTTP 응답 본문에만 실린다 — 지금까지와 같은 문자열이다.
 */
public class OtlpPayloadException(
	public val reason: String,
	message: String,
	cause: Throwable? = null,
) : PermanentIngestException(message, cause)

/**
 * 전송 독립 수집 코어(ADR 0067 Decision 1). HTTP 어댑터([OtlpIngestHandler])와 Kafka Consumer 가 같은 구현을 부른다.
 *
 * 영구 실패는 [PermanentIngestException](디코딩·마스킹은 [OtlpPayloadException]), 그 밖의 예외는 일시 실패다.
 * HTTP 상태도 Kafka offset 도 모른다. 재시도 루프를 두지 않는다.
 */
public fun interface OtlpProcessor {
	public fun process(payload: RawOtlpPayload, context: VerifiedIngestContext)
}

/**
 * 수집 단계의 본체. 압축 해제 → 디코딩 → 마스킹 → 신원 스탬프 → 원본 아카이브 → 다음 단계.
 *
 * 현행 collector 설정의 파이프라인 아홉을 한 경로로 접은 것이다. 실시간 스트림과 제품별 아카이브가
 * 별개 파이프라인이었던 것은 collector 가 그렇게밖에 표현할 수 없었기 때문이고, 홉이 사라진
 * 지금은 한 번 수신해 한 번 마스킹하고 두 갈래로 보내면 된다(허브 ADR 0005).
 *
 * ## 순서가 계약이다
 *
 * ```
 * 1. 마스킹      세 시그널 모두 (Signal.masked · AttributeWalker)
 * 2. 신원 스탬프  검증된 tenant·installation 을 리소스 속성으로 승격한다
 * 3. 아카이브     제품 구간별로 갈라 외부 저장소에 쓰고, 실제로 쓴 위치로 영수증을 만든다
 * 4. 다음 단계    변환·보강·적재 — 전체 요청과 영수증을 함께 받는다
 * ```
 *
 * **스탬프가 아카이브보다 먼저다.** 신원은 멱등 키의 재료이기 때문이다 — 분석 행의 키 `observation_id` 는 재료에
 * 항상 `tenant_id`·`installation_id` 를 넣는다(ADR 0020 §2). 재처리는 아카이브 원본에 심긴 신원으로 그 문맥을
 * 재현하므로, 신원 없는 원본을 재처리하면 실시간 경로가 만든 키와 **다른 키**가 나오고 합쳐져야 할 행이 중복으로
 * 쌓인다(ADR 0012 · 0016).
 *
 * **아카이브가 다음 단계보다 먼저다.** 허브 `architecture/overview.md` 2절이 "Adapter 이후 변환이
 * 실패하면 그 시그널은 Object Storage 에만 남는다. 이것이 흐름 D 의 복구 원천이며 별도 DLQ 에
 * 의존하지 않는다"고 못박았다. 순서를 뒤집으면 변환 실패가 곧 데이터 유실이 된다.
 * 같은 이유로 **아카이브가 실패하면 다음 단계를 부르지 않고 일시 실패로 올린다** — 복구 원천 없이 파이프라인을 진행시키지 않는다.
 *
 * ## 인증은 이 앞이다
 *
 * 검증을 통과한 신원([VerifiedIngestContext.identity])만 여기 닿는다(허브 ADR 0005). 폐기된 토큰이나 정지된 tenant 의
 * 요청처럼 거부될 데이터가 외부 저장소에 적재되면 안 되기 때문이다. **이 클래스는 인증을 하지 않는다.**
 *
 * ## 스테레오타입을 달지 않는다 (ADR 0011)
 */
public class OtlpIngestProcessor(
	private val archive: ArchiveWriter,
	private val next: SignalConsumer,
	maxDecompressedBytes: Long = DEFAULT_MAX_DECOMPRESSED_BYTES,
) : OtlpProcessor {

	private val decoder = OtlpRequestDecoder(maxDecompressedBytes)
	private val walker = AttributeWalker(SecretMasker())

	override fun process(payload: RawOtlpPayload, context: VerifiedIngestContext) {
		val signal = Signal.of(payload.signal)
		val encoding = OtlpEncoding.of(payload.mediaType)

		// 디코드·압축 해제 실패는 전부 영구다(허브 ADR 0006 — HTTP 400). IOException 계열도 잡는다 —
		// 깨진 gzip 은 ZipException, 깨진 protobuf 는 InvalidProtocolBufferException 이고 둘 다
		// RuntimeException 이 아니다. 놓치면 500 이 되어 데몬이 깨진 배치를 영구히 재시도한다.
		val body = try {
			decoder.decompress(payload.contentEncoding, payload.body)
		} catch (e: UnsupportedContentEncodingException) {
			throw OtlpPayloadException("unsupported_content_encoding", e.message.orEmpty(), e)
		} catch (e: OtlpBodyTooLargeException) {
			throw OtlpPayloadException("body_too_large", e.message.orEmpty(), e)
		} catch (e: Exception) {
			throw OtlpPayloadException("decompress_failed", e.message.orEmpty(), e)
		}

		val builder = signal.newRequestBuilder()
		try {
			encoding.decode(body, builder)
		} catch (e: Exception) {
			throw OtlpPayloadException("decode_failed", e.message.orEmpty(), e)
		}

		// 상위는 레코드가 0건이면 소비자를 부르지 않고 바로 성공을 돌려준다.
		if (recordCount(builder) == 0) return

		// 마스킹은 정규식 규칙이라 같은 입력이면 같게 실패한다 — 영구다(ADR 0067 Decision 1).
		if (signal.masked) {
			try {
				mask(signal, builder)
			} catch (e: RuntimeException) {
				throw OtlpPayloadException("masking_failed", "masking failed", e)
			}
		}

		// 아카이브보다 먼저다. 빌더에 찍으므로 아카이브와 다음 단계가 같은 값을 본다.
		val identity = context.identity
		IdentityStamper.stamp(builder, StampedIdentity(identity.tenantId.toString(), identity.installationId.toString()))

		val message = builder.build()

		// 모든 resource 가 정확히 한 문서에 들어간다. 하나라도 실패하면 영수증 없이 일시 실패다.
		val documents = ProductRouter.split(message).map { routed ->
			ArchivedDocument(
				product = routed.product,
				location = archive.write(routed.product, signal, OtlpJson.toJson(routed.document)),
				resourceIndexes = routed.resourceIndexes,
			)
		}
		val receipt = ArchiveReceipt(
			receiptId = context.receiptId.toString(),
			receivedAt = context.receivedAt,
			signal = signal,
			tenantId = identity.tenantId.toString(),
			installationId = identity.installationId.toString(),
			maskingVersion = MaskingPolicy.VERSION,
			identityVersion = IdentityStamper.VERSION,
			documents = documents,
		)
		next.consume(signal, message, receipt)
	}

	private fun mask(signal: Signal, builder: Message.Builder) {
		when (signal) {
			Signal.LOGS -> walker.maskLogs(builder as ExportLogsServiceRequest.Builder)
			Signal.TRACES -> walker.maskTraces(builder as ExportTraceServiceRequest.Builder)
			Signal.METRICS -> walker.maskMetrics(builder as ExportMetricsServiceRequest.Builder)
		}
	}

	/**
	 * 상위가 성공 단축에 쓰는 수 — logs 는 `LogRecordCount()`, traces 는 `SpanCount()`, metrics 는
	 * `DataPointCount()` 다. metrics 는 메트릭 수가 아니라 **데이터포인트 수**라, 데이터포인트 없는
	 * 메트릭만 담긴 요청은 소비자를 부르지 않고 성공이다.
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

	public companion object {
		/**
		 * 압축을 푼 뒤 본문의 기본 상한. 실제 상한은 생성자 인자가 정하고, 이 값은 조립 앱이
		 * 자기 설정의 기본값으로 쓰라고 공개한다 — 디코더 자체는 `internal` 이다.
		 */
		public const val DEFAULT_MAX_DECOMPRESSED_BYTES: Long =
			OtlpRequestDecoder.DEFAULT_MAX_DECOMPRESSED_BYTES
	}
}
