package com.team376.pulsemetry.telemetry.collector

import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpResponses
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * `:libs:telemetry-otlp-http` 로 옮긴 성공 본문이 OTLP 메시지로서도 옳은지 본다(ADR 0067).
 *
 * 그 모듈은 OTLP 타입을 모른 채 상수로 쓰므로, 메시지 타입을 가진 이 모듈이 시그널마다 다시 파싱하고
 * 우리 JSON 코덱의 출력과 바이트를 맞춰 본다. 옮기기 전에는 `OtlpJson.toJson(signal.emptyResponse())` 로 만들던 값이다.
 */
class OtlpResponsesCompatibilityTest {

	@ParameterizedTest(name = "{0}")
	@EnumSource(Signal::class)
	@DisplayName("JSON 성공 본문은 빈 응답 메시지를 우리 코덱으로 쓴 바이트와 같다")
	fun jsonMatchesCodec(signal: Signal) {
		assertThat(OtlpResponses.success(OtlpMediaType.JSON)).isEqualTo(OtlpJson.toJson(signal.emptyResponse()))
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(Signal::class)
	@DisplayName("protobuf 성공 본문 0a 00 은 세 시그널 모두 partial_success 가 있는 빈 응답으로 파싱된다")
	fun protobufParsesBack(signal: Signal) {
		val body = OtlpResponses.success(OtlpMediaType.PROTOBUF)
		val hasPartialSuccess = when (signal) {
			Signal.LOGS -> ExportLogsServiceResponse.parseFrom(body).hasPartialSuccess()
			Signal.TRACES -> ExportTraceServiceResponse.parseFrom(body).hasPartialSuccess()
			Signal.METRICS -> ExportMetricsServiceResponse.parseFrom(body).hasPartialSuccess()
		}
		assertThat(hasPartialSuccess).isTrue()
	}
}
