package com.team376.pulsemetry.telemetry.otlp

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/**
 * OTLP/HTTP 응답 본문. 상위 `receiver/otlpreceiver/otlphttp.go` 이식.
 *
 * 수집 모듈(ingest)과 Gateway 가 **같은 바이트**를 내야 해서 이 모듈에 둔다(ADR 0067). 옮기면서 바이트는 바꾸지 않았다 —
 * `OtlpResponsesTest` 가 고정하고, 수집 모듈의 `OtlpResponsesCompatibilityTest` 가 OTLP 메시지로 다시 파싱해 확인한다.
 *
 * ## 빈 성공 응답은 빈 바이트열이 아니다
 *
 * Go 는 non-nullable 임베디드 메시지를 **비어 있어도 항상 쓴다**. `ExportLogsServiceResponse` 의
 * `partial_success` 가 그런 필드다:
 *
 * ```go
 * func (orig *ExportLogsServiceResponse) MarshalProto(buf []byte) int {
 *     ... pos--; buf[pos] = 0xa   // 필드 1 을 길이 0 으로 항상 쓴다
 * }
 * func (orig *ExportLogsServiceResponse) MarshalJSON(dest *json.Stream) {
 *     dest.WriteObjectStart(); dest.WriteObjectField("partialSuccess"); ...
 * }
 * ```
 *
 * 그래서 성공 본문은 protobuf 가 **2바이트 `0a 00`**, JSON 이 **`{"partialSuccess":{}}`** 다. 세 시그널의 응답 메시지가
 * 모두 필드 1 하나(`partial_success`)라 시그널과 무관하게 같다. 파싱하면 어느 쪽이든 같은 메시지지만, 빈 배열이나 `{}` 를
 * 쓰면 현행 collector 가 내보내던 바이트와 달라진다.
 */
public object OtlpResponses {

	/**
	 * 상위 `fallbackMsg`. 공백까지 그대로다 — 상태 인코딩 자체가 실패했을 때만 나간다.
	 * main 에서 호출처가 없다 — 상위 표의 전사이고 테스트만 쓴다. [FALLBACK_CONTENT_TYPE] 도 같다.
	 */
	public val FALLBACK_BODY: ByteArray =
		"""{"code": 13, "message": "failed to marshal error message"}""".toByteArray(Charsets.UTF_8)

	public const val FALLBACK_CONTENT_TYPE: String = "application/json"

	/** 빈 `ExportServiceResponse` 의 JSON 표현. 세 시그널이 같다. */
	public const val SUCCESS_JSON: String = """{"partialSuccess":{}}"""

	/**
	 * 인증 실패의 단일 본문. ingest 의 `TelemetryTokenAuthenticationEntryPoint.BODY` 와 같은 바이트다 —
	 * 거부 사유를 가리지 않는다(허브 ADR 0001·0006). Gateway 는 `:libs:security`(JPA)에 의존하지 않으므로 여기 한 벌을 둔다.
	 * 두 값이 같은지는 ingest 테스트가 확인한다.
	 */
	public const val UNAUTHORIZED_BODY: String = """{"error":"unauthorized","message":"Invalid or expired credential"}"""

	/** 빈 `ExportServiceResponse`. protobuf 는 `0a 00`, JSON 은 `{"partialSuccess":{}}`. */
	public fun success(mediaType: OtlpMediaType): ByteArray = when (mediaType) {
		// protobuf-java 는 빈 임베디드 메시지를 생략하므로 Go 와 같은 바이트가 나오지 않는다.
		// 태그(필드 1, wire type 2)와 길이 0 을 직접 쓴다.
		OtlpMediaType.PROTOBUF -> byteArrayOf(0x0a, 0x00)
		OtlpMediaType.JSON -> SUCCESS_JSON.toByteArray(Charsets.UTF_8)
	}

	/**
	 * `google.rpc.Status` 를 요청과 같은 인코딩으로 쓴다.
	 *
	 * 타입 하나 때문에 `proto-google-common-protos`(구글 공통 proto 전부) 를 끌지 않고 두 필드를
	 * 직접 쓴다. varint·길이 접두는 protobuf-java 의 [CodedOutputStream] 이 계산하므로 손으로 만든
	 * 와이어 포맷은 없다. proto3 규칙대로 기본값은 생략한다 — `details` 는 우리가 채우지 않는다.
	 *
	 * ```proto
	 * message Status { int32 code = 1; string message = 2; repeated Any details = 3; }
	 * ```
	 */
	public fun status(mediaType: OtlpMediaType, code: GrpcCode, message: String): ByteArray = when (mediaType) {
		OtlpMediaType.PROTOBUF -> {
			val out = ByteArrayOutputStream()
			val cos = CodedOutputStream.newInstance(out)
			if (code.number != 0) cos.writeInt32(1, code.number)
			if (message.isNotEmpty()) cos.writeString(2, message)
			cos.flush()
			out.toByteArray()
		}

		OtlpMediaType.JSON -> {
			val sb = StringBuilder("{")
			if (code.number != 0) sb.append("\"code\":").append(code.number)
			if (message.isNotEmpty()) {
				if (sb.length > 1) sb.append(',')
				sb.append("\"message\":").append(jsonString(message))
			}
			sb.append('}').toString().toByteArray(Charsets.UTF_8)
		}
	}

	// ------------------------------------------------------------------ 응답 묶음

	public fun successResponse(mediaType: OtlpMediaType): OtlpHttpResponse =
		OtlpHttpResponse(status = 200, contentType = mediaType.contentType, body = success(mediaType))

	public fun statusResponse(mediaType: OtlpMediaType, httpStatus: Int, code: GrpcCode, message: String): OtlpHttpResponse =
		OtlpHttpResponse(status = httpStatus, contentType = mediaType.contentType, body = status(mediaType, code, message))

	/**
	 * 처리 **앞**에서 난 일시 장애의 503 본문 — 인증 조회의 DB 장애·Kafka 발행 실패가 그 경우다. 모르는 `Content-Type` 은
	 * JSON 으로 쓴다 — 415 판정 전에 죽은 요청에 415 를 낼 수는 없다.
	 */
	public fun unavailable(contentType: String?, message: String): OtlpHttpResponse =
		statusResponse(OtlpMediaType.ofContentType(contentType) ?: OtlpMediaType.JSON, 503, GrpcCode.UNAVAILABLE, message)

	/** 상위 `handleUnmatchedMethod` — 본문이 `text/plain` 이다. */
	public fun methodNotAllowed(): OtlpHttpResponse =
		plain(405, "405 method not allowed, supported: [POST]")

	/** 상위 `handleUnmatchedContentType` — 본문이 `text/plain` 이다. */
	public fun unsupportedMediaType(): OtlpHttpResponse =
		plain(
			415,
			"415 unsupported media type, supported: " +
				"[${OtlpMediaType.JSON.contentType}, ${OtlpMediaType.PROTOBUF.contentType}]",
		)

	/** 계약 밖 경로. ingest 의 기본 닫힘 체인과 Gateway 가 같은 본문을 쓴다(허브 계약 §8). */
	public fun notFound(): OtlpHttpResponse = plain(404, "404 not found")

	/** 원본 바디 상한 초과. 프레임워크 층의 거부라 405·415 와 같은 `text/plain` 이다. */
	public fun payloadTooLarge(): OtlpHttpResponse = plain(413, "413 request entity too large")

	/** 인증 실패. [UNAUTHORIZED_BODY] 하나다. `WWW-Authenticate` 는 붙이지 않는다. */
	public fun unauthorized(): OtlpHttpResponse =
		OtlpHttpResponse(status = 401, contentType = "application/json", body = UNAUTHORIZED_BODY.toByteArray(Charsets.UTF_8))

	private fun plain(status: Int, text: String) =
		OtlpHttpResponse(status = status, contentType = "text/plain", body = text.toByteArray(Charsets.UTF_8))

	/** JSON 문자열 리터럴. 제어문자는 `\uXXXX` 로 — 오류 메시지에 무엇이 섞여 들어올지 모른다. */
	private fun jsonString(value: String): String {
		val sb = StringBuilder(value.length + 2)
		sb.append('"')
		for (c in value) {
			when {
				c == '"' -> sb.append("\\\"")
				c == '\\' -> sb.append("\\\\")
				c == '\n' -> sb.append("\\n")
				c == '\r' -> sb.append("\\r")
				c == '\t' -> sb.append("\\t")
				c < ' ' -> sb.append("\\u%04x".format(c.code))
				else -> sb.append(c)
			}
		}
		return sb.append('"').toString()
	}
}
