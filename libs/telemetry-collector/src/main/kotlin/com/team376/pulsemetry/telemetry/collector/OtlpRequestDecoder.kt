package com.team376.pulsemetry.telemetry.collector

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * 요청 본문의 압축을 풀어 준다. 상위에서는 수신기가 아니라 `config/confighttp` 의 decompressor 가
 * 하는 일이고, 지원하지 않는 `Content-Encoding` 은 **400** 이다.
 *
 * ## 상위와 다른 점 — 지원 알고리즘을 좁혔다
 *
 * 상위 기본값은 `gzip` · `zstd` · `zlib` · `snappy` · `deflate` · `lz4` · `x-snappy-framed` 일곱이다.
 * 여기서는 **JDK 만으로 되는 넷**(무압축 · gzip · zlib · deflate)만 받고 나머지는 400 을 낸다.
 * zstd · snappy · lz4 는 각각 네이티브 라이브러리를 끌어야 하는데, 이 경로로 들어오는 클라이언트가
 * 쓰는 것은 gzip 아니면 무압축이다 — 벤더 OTel SDK 도 데몬 forwarder 도 그렇다.
 * **이것은 의도한 축소이고 관측된 필요가 생기면 넓힌다.** 넓힐 자리는 [decompress] 하나다.
 *
 * ## 압축 폭탄
 *
 * 상위는 압축된 본문에만 상한을 걸고 해제 결과는 스트리밍으로 흘려보내므로 해제 크기에 상한이 없다.
 * 여기서는 **해제 바이트에 상한을 건다** — 작은 gzip 이 힙을 다 먹는 경로를 열어 둘 이유가 없다.
 */
internal class OtlpRequestDecoder(
	/** 해제 후 허용하는 최대 바이트. 상위 `max_request_body_size` 기본값과 같은 20 MiB. */
	private val maxDecompressedBytes: Long = DEFAULT_MAX_DECOMPRESSED_BYTES,
) {

	/**
	 * @throws UnsupportedContentEncodingException 모르는 인코딩 — 호출자가 400 을 낸다.
	 * @throws OtlpBodyTooLargeException 해제 결과가 상한을 넘었다 — 호출자가 400 을 낸다.
	 */
	fun decompress(contentEncoding: String?, body: ByteArray): ByteArray {
		val encoding = contentEncoding.orEmpty().trim().lowercase()
		return when (encoding) {
			"", "identity", "none" -> body
			"gzip" -> readCapped(GZIPInputStream(ByteArrayInputStream(body)))
			// 상위도 zlib 과 deflate 를 같은 리더로 처리한다 — 둘 다 zlib 스트림이다.
			"zlib", "deflate" -> readCapped(InflaterInputStream(ByteArrayInputStream(body)))
			else -> throw UnsupportedContentEncodingException(encoding)
		}
	}

	private fun readCapped(input: java.io.InputStream): ByteArray = input.use { stream ->
		val out = java.io.ByteArrayOutputStream()
		val buffer = ByteArray(DEFAULT_CHUNK)
		var total = 0L
		while (true) {
			val read = stream.read(buffer)
			if (read < 0) break
			total += read
			if (total > maxDecompressedBytes) throw OtlpBodyTooLargeException(maxDecompressedBytes)
			out.write(buffer, 0, read)
		}
		out.toByteArray()
	}

	companion object {
		const val DEFAULT_MAX_DECOMPRESSED_BYTES: Long = 20L * 1024 * 1024
		private const val DEFAULT_CHUNK = 8192
	}
}

/** 상위 메시지 형태를 따른다 — `unsupported Content-Encoding: <값>`. */
internal class UnsupportedContentEncodingException(encoding: String) :
	RuntimeException("unsupported Content-Encoding: $encoding")

internal class OtlpBodyTooLargeException(limit: Long) :
	RuntimeException("decompressed body exceeds $limit bytes")
