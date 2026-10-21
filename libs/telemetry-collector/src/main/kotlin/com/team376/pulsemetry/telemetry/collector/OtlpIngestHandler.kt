package com.team376.pulsemetry.telemetry.collector

import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.otlp.GrpcCode
import com.team376.pulsemetry.telemetry.otlp.OtlpContentEncoding
import com.team376.pulsemetry.telemetry.otlp.OtlpHttpRequest
import com.team376.pulsemetry.telemetry.otlp.OtlpHttpResponse
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpResponses
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import java.time.Clock
import java.util.UUID

/**
 * 수집 단계의 HTTP 어댑터(ADR 0067 Decision 2). 메서드·경로·`Content-Type` 을 검사하고 [OtlpProcessor] 의 결과를 OTLP/HTTP
 * 응답으로 바꾼다. 압축 해제·디코딩·마스킹·스탬프·아카이브는 코어([OtlpIngestProcessor])의 몫이다.
 *
 * ## 상태 매핑
 *
 * | 결과 | 응답 |
 * |---|---|
 * | POST 가 아님 · 모르는 경로 · 모르는 `Content-Type` | 405 · 404 · 415 (상위 문자열 그대로) |
 * | [PermanentIngestException] — 디코딩·압축 해제·마스킹·하류 영구 실패 | 400, gRPC `INVALID_ARGUMENT`, 메시지 그대로 |
 * | 그 밖의 `RuntimeException` | 503, gRPC `UNAVAILABLE` |
 *
 * 503 의 `Retry-After` 는 앱의 응답 writer 가 붙인다.
 *
 * ## 영수증
 *
 * receipt ID 는 요청마다 새 UUID, 수신 시각은 요청이 여기 닿은 때다 — 디코드·마스킹에 걸린 시간을 섞지 않는다.
 * HTTP 재전송은 새 영수증이다(ADR 0021). Kafka 경로는 이 어댑터를 지나지 않는다.
 *
 * ## 인증은 이 앞이다
 *
 * 필터 체인이 통과시킨 요청만 여기 닿는다. 앱이 검증된 principal 에서 [AuthorizedTelemetryIdentity] 를 만들어
 * **명시적으로** 넘긴다(ADR 0067 — 스레드 지역 신원을 읽지 않는다).
 *
 * ## 스테레오타입을 달지 않는다 (ADR 0011)
 *
 * 빈 등록과 HTTP 라우팅은 앱이 한다. 앱은 자기 요청에서 [OtlpHttpRequest] 를 만들어 [handle] 을
 * 부르고 [OtlpHttpResponse] 를 그대로 내보내면 된다.
 */
public class OtlpIngestHandler(
	private val processor: OtlpProcessor,
	/** 영수증의 수신 시각을 재는 서버 시계. */
	private val clock: Clock = Clock.systemUTC(),
) {

	public fun handle(request: OtlpHttpRequest, identity: AuthorizedTelemetryIdentity): OtlpHttpResponse {
		// 서버 수신 시각은 요청이 여기 닿은 때다 — 디코드·마스킹에 걸린 시간을 섞지 않는다.
		val receivedAt = clock.instant()
		if (!request.method.equals("POST", ignoreCase = true)) return OtlpResponses.methodNotAllowed()

		// 상위는 설정된 경로에만 라우트를 등록하므로 그 밖의 경로는 mux 가 404 를 낸다.
		val signal = OtlpSignal.ofPath(request.path) ?: return notFound()

		val mediaType = OtlpMediaType.ofContentType(request.contentType) ?: return OtlpResponses.unsupportedMediaType()

		val payload = RawOtlpPayload(
			signal = signal,
			mediaType = mediaType,
			contentEncoding = OtlpContentEncoding.normalize(request.contentEncoding),
			body = request.body,
		)

		return try {
			processor.process(payload, VerifiedIngestContext(UUID.randomUUID(), receivedAt, identity))
			OtlpResponses.successResponse(mediaType)
		} catch (e: PermanentIngestException) {
			// 영구 오류. 재시도해도 같으므로 클라이언트가 배치를 버리게 한다 — 그러려면 4xx 여야 한다.
			// 데몬은 5xx 를 전부 재시도하므로 500 으로는 폐기가 만들어지지 않는다(허브 ADR 0006).
			OtlpResponses.statusResponse(mediaType, 400, GrpcCode.INVALID_ARGUMENT, e.message.orEmpty())
		} catch (e: RuntimeException) {
			// 상태가 실리지 않은 오류의 기본은 재시도 가능이다 — 상위 GetStatusFromError 와 같다.
			OtlpResponses.statusResponse(mediaType, 503, GrpcCode.UNAVAILABLE, e.message.orEmpty())
		}
	}

	/**
	 * 이 진입점 **앞**에서 난 일시 장애를 OTLP 503 본문으로 만든다 — 인증 조회의 DB 장애가 그 경우다.
	 * 모르는 `Content-Type` 은 JSON 으로 쓴다 — 415 판정은 인증 뒤의 일이고, 그 전에 죽은 요청에 415 를 낼 수는 없다.
	 */
	public fun unavailable(contentType: String?, message: String): OtlpHttpResponse =
		OtlpResponses.unavailable(contentType, message)

	/** 조립 앱의 기본 닫힘 체인도 같은 경로 거부 응답을 사용한다. */
	public fun notFound(): OtlpHttpResponse = OtlpResponses.notFound()
}
