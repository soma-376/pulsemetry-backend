package com.team376.pulsemetry.telemetry.otlp

/**
 * OTLP/HTTP 본문의 media type 허용 목록. 상위 `receiver/otlpreceiver/encoder.go` 의 둘이다.
 *
 * **오류 응답도 요청과 같은 media type 으로 낸다** — OTLP 규약이다. 디코딩은 수집 모듈의 몫이고 여기는 판정만 한다.
 */
public enum class OtlpMediaType(public val contentType: String) {
	PROTOBUF("application/x-protobuf"),
	JSON("application/json"),
	;

	public companion object {
		/**
		 * `Content-Type` 에서 미디어 타입만 떼어 고른다. 상위 `getMimeTypeFromContentType` 은 `mime.ParseMediaType` 을
		 * 쓰므로 `application/json; charset=utf-8` 도 JSON 이다. 모르는 타입이면 null — 호출자가 415 를 낸다.
		 */
		public fun ofContentType(contentType: String?): OtlpMediaType? =
			when (contentType.orEmpty().substringBefore(';').trim().lowercase()) {
				PROTOBUF.contentType -> PROTOBUF
				JSON.contentType -> JSON
				else -> null
			}
	}
}
