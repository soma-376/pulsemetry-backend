package com.team376.pulsemetry.telemetry.otlp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 응답 본문의 바이트 계약.
 *
 * 이 테스트는 **성능이 아니라 상호운용**을 지킨다. 클라이언트가 오늘 받는 바이트는 Go collector 가
 * 내보내던 것이고, 이식본이 다른 바이트를 내보내면 규격을 벗어난 클라이언트에서 깨진다.
 * 기대값의 출처는 상위 `pdata/internal/generated_proto_exportlogsserviceresponse.go` 다.
 *
 * 수집 모듈에서 옮겨 왔다(ADR 0067). OTLP 메시지로 다시 파싱하는 확인은 OTLP 타입을 가진 수집 모듈의
 * `OtlpResponsesCompatibilityTest` 에 있다.
 */
class OtlpResponsesTest {

	@Test
	@DisplayName("성공 protobuf 본문은 빈 배열이 아니라 2바이트 0a 00 이다")
	fun protobufSuccessBodyIsTwoBytes() {
		// Go 는 non-nullable 임베디드 메시지를 비어 있어도 항상 쓴다.
		//   func (orig *ExportLogsServiceResponse) MarshalProto(buf []byte) int {
		//       ... pos--; buf[pos] = 0xa   // 필드 1 을 길이 0 으로 항상 쓴다
		//   }
		// protobuf-java 는 반대로 빈 임베디드를 생략하므로 손으로 쓰지 않으면 0바이트가 된다.
		assertThat(OtlpResponses.success(OtlpMediaType.PROTOBUF)).containsExactly(0x0a, 0x00)
	}

	@Test
	@DisplayName("성공 JSON 본문은 {} 가 아니라 {\"partialSuccess\":{}} 다")
	fun jsonSuccessBodyCarriesPartialSuccess() {
		// 같은 규칙의 JSON 쪽 표현이다. 파이썬 수신기가 b"{}" 를 돌려주지만 그것은 collector
		// 하류이고, 클라이언트가 오늘 받는 것은 이쪽이다.
		assertThat(OtlpResponses.success(OtlpMediaType.JSON).toString(Charsets.UTF_8)).isEqualTo("""{"partialSuccess":{}}""")
	}

	@Test
	@DisplayName("오류 JSON 은 google.rpc.Status 다 — 기본값 필드는 생략한다")
	fun jsonStatusOmitsDefaults() {
		val body = OtlpResponses.status(OtlpMediaType.JSON, GrpcCode.INVALID_ARGUMENT, "bad body")

		assertThat(body.toString(Charsets.UTF_8)).isEqualTo("""{"code":3,"message":"bad body"}""")
	}

	@Test
	@DisplayName("오류 JSON 의 메시지는 이스케이프한다 — 무엇이 섞여 올지 모른다")
	fun jsonStatusEscapesMessage() {
		val body = OtlpResponses.status(OtlpMediaType.JSON, GrpcCode.INTERNAL, "he said \"no\"\n")

		assertThat(body.toString(Charsets.UTF_8))
			.isEqualTo("""{"code":13,"message":"he said \"no\"\n"}""")
	}

	@Test
	@DisplayName("오류 protobuf 는 code=1 · message=2 인 google.rpc.Status 바이트다")
	fun protobufStatusWireFormat() {
		val body = OtlpResponses.status(OtlpMediaType.PROTOBUF, GrpcCode.UNAVAILABLE, "hi")

		// 08 0e  → 필드 1(varint) = 14(UNAVAILABLE)
		// 12 02  → 필드 2(length-delimited) 길이 2
		// 68 69  → "hi"
		assertThat(body).containsExactly(0x08, 0x0e, 0x12, 0x02, 0x68, 0x69)
	}

	@Test
	@DisplayName("fallback 본문은 상위 문자열 그대로다 — 공백까지 같다")
	fun fallbackBodyMatchesUpstream() {
		assertThat(OtlpResponses.FALLBACK_BODY.toString(Charsets.UTF_8))
			.isEqualTo("""{"code": 13, "message": "failed to marshal error message"}""")
	}

	@Test
	@DisplayName("405·415·404·413 은 상위 문자열 그대로의 text/plain 이다")
	fun plainResponses() {
		val cases = mapOf(
			OtlpResponses.methodNotAllowed() to (405 to "405 method not allowed, supported: [POST]"),
			OtlpResponses.unsupportedMediaType() to (415 to "415 unsupported media type, supported: [application/json, application/x-protobuf]"),
			OtlpResponses.notFound() to (404 to "404 not found"),
			OtlpResponses.payloadTooLarge() to (413 to "413 request entity too large"),
		)
		cases.forEach { (response, expected) ->
			assertThat(response.status).isEqualTo(expected.first)
			assertThat(response.contentType).isEqualTo("text/plain")
			assertThat(response.body.toString(Charsets.UTF_8)).isEqualTo(expected.second)
		}
	}

	@Test
	@DisplayName("401 은 사유 없는 단일 JSON 본문이다")
	fun unauthorized() {
		val response = OtlpResponses.unauthorized()

		assertThat(response.status).isEqualTo(401)
		assertThat(response.contentType).isEqualTo("application/json")
		assertThat(response.body.toString(Charsets.UTF_8))
			.isEqualTo("""{"error":"unauthorized","message":"Invalid or expired credential"}""")
	}

	@Test
	@DisplayName("앞단 503 은 요청의 media type 을 따르고 모르는 타입이면 JSON 이다")
	fun unavailable() {
		assertThat(OtlpResponses.unavailable("application/x-protobuf", "x").contentType).isEqualTo("application/x-protobuf")
		val unknown = OtlpResponses.unavailable("text/csv", "authentication store unavailable")
		assertThat(unknown.status).isEqualTo(503)
		assertThat(unknown.body.toString(Charsets.UTF_8)).isEqualTo("""{"code":14,"message":"authentication store unavailable"}""")
	}
}
