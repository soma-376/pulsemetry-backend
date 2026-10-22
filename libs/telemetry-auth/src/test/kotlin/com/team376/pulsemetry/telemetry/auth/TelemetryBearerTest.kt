package com.team376.pulsemetry.telemetry.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** ingest 필터의 Bearer 문법과 같은지 본다 — 스킴 대소문자 무시, 여러 공백 허용, 토큰 안 공백 거부. */
class TelemetryBearerTest {

	@ParameterizedTest
	@ValueSource(strings = ["Bearer a.b.c", "bearer a.b.c", "BEARER   a.b.c"])
	@DisplayName("스킴 대소문자와 사이 공백 수를 가리지 않는다")
	fun extracts(header: String) {
		assertThat(TelemetryBearer.extract(header)).isEqualTo("a.b.c")
	}

	@Test
	@DisplayName("헤더가 없거나 비어 있으면 missing_bearer 다")
	fun missing() {
		listOf(null, "").forEach { header ->
			assertThatThrownBy { TelemetryBearer.extract(header) }
				.isInstanceOfSatisfying(TelemetryJwtException::class.java) {
					assertThat(it.reason).isEqualTo(TelemetryJwtRejection.MISSING_BEARER)
				}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = ["Basic dXNlcjpwYXNz", "Bearer", "Bearer a b", "Bearera.b.c", " Bearer a.b.c"])
	@DisplayName("Bearer 문법이 아니면 malformed_bearer 다")
	fun malformed(header: String) {
		assertThatThrownBy { TelemetryBearer.extract(header) }
			.isInstanceOfSatisfying(TelemetryJwtException::class.java) {
				assertThat(it.reason).isEqualTo(TelemetryJwtRejection.MALFORMED_BEARER)
			}
	}
}
