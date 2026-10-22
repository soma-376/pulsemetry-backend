package com.team376.pulsemetry.telemetry.otlp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 경로·media type·Content-Encoding 판정. ingest 와 Gateway 가 같은 판정을 쓴다(ADR 0067). */
class OtlpRequestSurfaceTest {

	@Test
	@DisplayName("세 경로만 시그널이고 그 밖은 null 이다")
	fun signalPaths() {
		assertThat(OtlpSignal.PATHS).containsExactly("/v1/logs", "/v1/traces", "/v1/metrics")
		assertThat(OtlpSignal.ofPath("/v1/logs")).isEqualTo(OtlpSignal.LOGS)
		assertThat(OtlpSignal.ofPath("/v1/profiles")).isNull()
		assertThat(OtlpSignal.ofPath("/v1/logs/")).isNull()
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = ["application/json", "application/json; charset=utf-8", "APPLICATION/JSON", " application/json "])
	@DisplayName("Content-Type 의 파라미터·대소문자·공백을 무시한다 — 상위 mime.ParseMediaType 과 같다")
	fun mediaTypeParameters(contentType: String) {
		assertThat(OtlpMediaType.ofContentType(contentType)).isEqualTo(OtlpMediaType.JSON)
	}

	@Test
	@DisplayName("모르는 Content-Type 과 빈 값은 null 이다")
	fun unknownMediaType() {
		assertThat(OtlpMediaType.ofContentType("text/csv")).isNull()
		assertThat(OtlpMediaType.ofContentType(null)).isNull()
		assertThat(OtlpMediaType.ofContentType("application/x-protobuf")).isEqualTo(OtlpMediaType.PROTOBUF)
	}

	@Test
	@DisplayName("Content-Encoding 은 무압축·gzip·zlib·deflate 만 받고 정규화한다")
	fun contentEncodings() {
		assertThat(OtlpContentEncoding.normalize(" GZIP ")).isEqualTo("gzip")
		assertThat(OtlpContentEncoding.normalize(null)).isEmpty()
		listOf("", "identity", "none", "gzip", "zlib", "deflate").forEach {
			assertThat(OtlpContentEncoding.isSupported(it)).describedAs(it).isTrue()
		}
		listOf("br", "zstd", "snappy", "lz4", "x-snappy-framed").forEach {
			assertThat(OtlpContentEncoding.isSupported(it)).describedAs(it).isFalse()
		}
		assertThat(OtlpContentEncoding.unsupportedMessage("br")).isEqualTo("unsupported Content-Encoding: br")
	}
}
