package com.team376.pulsemetry.telemetry.otlp

/**
 * 받는 `Content-Encoding` 허용 목록. 수집 모듈의 디코더가 실제로 풀 수 있는 넷(무압축·gzip·zlib·deflate)이다 —
 * zstd·snappy·lz4 는 네이티브 라이브러리가 필요해 의도적으로 좁혔다(`OtlpRequestDecoder` KDoc).
 *
 * Gateway 는 본문을 풀지 않지만 이 목록으로 거른다. 목록 밖이면 ingest 와 같은 400 `unsupported Content-Encoding` 이다.
 * **디코더와 이 목록은 함께 바꾼다** — 수집 모듈 테스트가 목록의 모든 값이 디코더를 통과하는지 확인한다.
 */
public object OtlpContentEncoding {

	/** 정규화한 값. 빈 문자열은 헤더가 없거나 비어 있는 경우다. */
	public val SUPPORTED: Set<String> = setOf("", "identity", "none", "gzip", "zlib", "deflate")

	/** 앞뒤 공백을 떼고 소문자로 만든다. envelope 에는 이 값이 실린다. */
	public fun normalize(header: String?): String = header.orEmpty().trim().lowercase()

	public fun isSupported(normalized: String): Boolean = normalized in SUPPORTED

	/** 상위 메시지 형태를 따른다 — `unsupported Content-Encoding: <값>`. */
	public fun unsupportedMessage(normalized: String): String = "unsupported Content-Encoding: $normalized"
}
