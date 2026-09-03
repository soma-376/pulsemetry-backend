package com.team376.pulsemetry.telemetry.collector

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class OtlpRequestDecoderTest {

	private val decoder = OtlpRequestDecoder()

	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = ["", "identity", "none"])
	@DisplayName("무압축은 본문을 그대로 준다")
	fun passesThroughUncompressed(encoding: String) {
		assertThat(decoder.decompress(encoding, BODY)).isEqualTo(BODY)
	}

	@Test
	@DisplayName("헤더가 없어도(null) 그대로 준다")
	fun handlesMissingHeader() {
		assertThat(decoder.decompress(null, BODY)).isEqualTo(BODY)
	}

	@Test
	@DisplayName("gzip 을 푼다 — 실제 클라이언트가 쓰는 유일한 압축이다")
	fun decompressesGzip() {
		val compressed = ByteArrayOutputStream().also { out ->
			GZIPOutputStream(out).use { it.write(BODY) }
		}.toByteArray()

		assertThat(decoder.decompress("gzip", compressed)).isEqualTo(BODY)
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = ["zlib", "deflate"])
	@DisplayName("zlib 과 deflate 는 같은 리더로 푼다 — 상위도 그렇다")
	fun decompressesZlibAndDeflate(encoding: String) {
		val compressed = ByteArrayOutputStream().also { out ->
			DeflaterOutputStream(out).use { it.write(BODY) }
		}.toByteArray()

		assertThat(decoder.decompress(encoding, compressed)).isEqualTo(BODY)
	}

	@Test
	@DisplayName("헤더의 대소문자와 공백을 무시한다")
	fun normalizesHeaderValue() {
		val compressed = ByteArrayOutputStream().also { out ->
			GZIPOutputStream(out).use { it.write(BODY) }
		}.toByteArray()

		assertThat(decoder.decompress("  GZIP ", compressed)).isEqualTo(BODY)
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = ["br", "zstd", "snappy", "lz4", "x-snappy-framed"])
	@DisplayName("지원하지 않는 인코딩은 거절한다 — 상위보다 좁힌 것이고 의도한 축소다")
	fun rejectsUnsupportedEncodings(encoding: String) {
		// 상위 기본값은 일곱을 받지만 zstd·snappy·lz4 는 네이티브 라이브러리가 필요하고
		// 이 경로의 클라이언트는 gzip 아니면 무압축이다. 넓힐 자리는 decompress 하나다.
		assertThatThrownBy { decoder.decompress(encoding, BODY) }
			.isInstanceOf(UnsupportedContentEncodingException::class.java)
			.hasMessage("unsupported Content-Encoding: $encoding")
	}

	@Test
	@DisplayName("압축 폭탄을 막는다 — 해제 바이트에 상한이 있다. 상위에는 없는 방어다")
	fun capsDecompressedSize() {
		// 상위는 압축된 본문에만 상한을 걸고 해제 결과는 스트리밍으로 흘려보낸다.
		// 작은 gzip 이 힙을 다 먹는 경로를 열어 둘 이유가 없어 여기서 막는다.
		val limit = 64L * 1024
		val bomb = ByteArrayOutputStream().also { out ->
			GZIPOutputStream(out).use { it.write(ByteArray(8 * 1024 * 1024)) }
		}.toByteArray()

		// 압축된 본문은 상한보다 한참 작아서 요청 크기 제한에 걸리지 않는다.
		assertThat(bomb.size.toLong()).isLessThan(limit)

		assertThatThrownBy { OtlpRequestDecoder(maxDecompressedBytes = limit).decompress("gzip", bomb) }
			.isInstanceOf(OtlpBodyTooLargeException::class.java)
			.hasMessageContaining(limit.toString())
	}

	private companion object {
		val BODY = """{"resourceLogs":[]}""".toByteArray()
	}
}
