package com.team376.pulsemetry.telemetry.collector

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * 응답 본문의 바이트 계약.
 *
 * 이 테스트는 **성능이 아니라 상호운용**을 지킨다. 클라이언트가 오늘 받는 바이트는 Go collector 가
 * 내보내던 것이고, 이식본이 다른 바이트를 내보내면 규격을 벗어난 클라이언트에서 깨진다.
 * 기대값의 출처는 상위 `pdata/internal/generated_proto_exportlogsserviceresponse.go` 다.
 */
class OtlpResponsesTest {

	@ParameterizedTest(name = "{0}")
	@EnumSource(Signal::class)
	@DisplayName("성공 protobuf 본문은 빈 배열이 아니라 2바이트 0a 00 이다")
	fun protobufSuccessBodyIsTwoBytes(signal: Signal) {
		// Go 는 non-nullable 임베디드 메시지를 비어 있어도 항상 쓴다.
		//   func (orig *ExportLogsServiceResponse) MarshalProto(buf []byte) int {
		//       ... pos--; buf[pos] = 0xa   // 필드 1 을 길이 0 으로 항상 쓴다
		//   }
		// protobuf-java 는 반대로 빈 임베디드를 생략하므로 손으로 쓰지 않으면 0바이트가 된다.
		val body = OtlpResponses.success(signal, OtlpEncoding.PROTOBUF)

		assertThat(body).containsExactly(0x0a, 0x00)
	}

	@Test
	@DisplayName("그 2바이트는 실제로 빈 ExportServiceResponse 로 파싱된다")
	fun protobufSuccessBodyParsesBack() {
		val parsed = ExportLogsServiceResponse.parseFrom(
			OtlpResponses.success(Signal.LOGS, OtlpEncoding.PROTOBUF),
		)

		assertThat(parsed.hasPartialSuccess()).isTrue()
		assertThat(parsed.partialSuccess.rejectedLogRecords).isZero()
		assertThat(parsed.partialSuccess.errorMessage).isEmpty()
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(Signal::class)
	@DisplayName("성공 JSON 본문은 {} 가 아니라 {\"partialSuccess\":{}} 다")
	fun jsonSuccessBodyCarriesPartialSuccess(signal: Signal) {
		// 같은 규칙의 JSON 쪽 표현이다. 파이썬 수신기가 b"{}" 를 돌려주지만 그것은 collector
		// 하류이고, 클라이언트가 오늘 받는 것은 이쪽이다.
		val body = OtlpResponses.success(signal, OtlpEncoding.JSON).toString(Charsets.UTF_8)

		assertThat(body).isEqualTo("""{"partialSuccess":{}}""")
	}

	@Test
	@DisplayName("오류 JSON 은 google.rpc.Status 다 — 기본값 필드는 생략한다")
	fun jsonStatusOmitsDefaults() {
		val body = OtlpResponses.status(OtlpEncoding.JSON, GrpcCode.INVALID_ARGUMENT, "bad body")

		assertThat(body.toString(Charsets.UTF_8)).isEqualTo("""{"code":3,"message":"bad body"}""")
	}

	@Test
	@DisplayName("오류 JSON 의 메시지는 이스케이프한다 — 무엇이 섞여 올지 모른다")
	fun jsonStatusEscapesMessage() {
		val body = OtlpResponses.status(OtlpEncoding.JSON, GrpcCode.INTERNAL, "he said \"no\"\n")

		assertThat(body.toString(Charsets.UTF_8))
			.isEqualTo("""{"code":13,"message":"he said \"no\"\n"}""")
	}

	@Test
	@DisplayName("오류 protobuf 는 code=1 · message=2 인 google.rpc.Status 바이트다")
	fun protobufStatusWireFormat() {
		val body = OtlpResponses.status(OtlpEncoding.PROTOBUF, GrpcCode.UNAVAILABLE, "hi")

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
}
