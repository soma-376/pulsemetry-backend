package com.team376.pulsemetry.telemetry.ingress

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 로그 한 줄 형식(ADR 0066 Decision 10). infra metric filter 가 코드를 term 으로 센다. */
class TelemetryLogEventTest {

	@Test
	@DisplayName("코드는 계약 문자열 그대로다")
	fun codes() {
		assertThat(TelemetryLogEvent.entries.map { it.code }).containsExactly(
			"telemetry_gateway_rejected", "telemetry_publish_failed", "telemetry_permanent_failure",
			"telemetry_transient_failure", "telemetry_retry_exhausted", "telemetry_commit_failed",
		)
	}

	@Test
	@DisplayName("event 다음에 key=value 를 넘긴 순서로 잇고 null 은 - 다")
	fun format() {
		val line = TelemetryLogEvent.RETRY_EXHAUSTED.format(
			"messageId" to "m-1", "topic" to "pulsemetry-dev-telemetry-ingress-main", "partition" to 3, "offset" to 42L,
			"stage" to "process", "reason" to "unavailable", "attempt" to 4, "kid" to null,
		)

		assertThat(line).isEqualTo(
			"event=telemetry_retry_exhausted messageId=m-1 topic=pulsemetry-dev-telemetry-ingress-main partition=3 offset=42 " +
				"stage=process reason=unavailable attempt=4 kid=-",
		)
	}

	@Test
	@DisplayName("공백·따옴표·줄바꿈·= 은 _ 로 바뀌고 값은 128자에서 자른다 — 한 줄 형식이 깨지지 않는다")
	fun sanitizes() {
		val line = TelemetryLogEvent.PERMANENT_FAILURE.format("reason" to "a b\"c\nd=e", "long" to "x".repeat(300))

		assertThat(line).isEqualTo("event=telemetry_permanent_failure reason=a_b_c_d_e long=${"x".repeat(128)}")
		assertThat(line).doesNotContain("\n")
	}

	@Test
	@DisplayName("예외는 클래스 단순 이름만 남긴다")
	fun cause() {
		assertThat(TelemetryLogEvent.causeOf(IllegalStateException("secret payload"))).isEqualTo("IllegalStateException")
		assertThat(TelemetryLogEvent.causeOf(null)).isEqualTo("-")
	}
}
