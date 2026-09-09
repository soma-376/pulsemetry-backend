package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message

/**
 * OTLP/HTTP 본문 인코딩. 상위 `receiver/otlpreceiver/encoder.go` 이식.
 *
 * **오류 응답도 요청과 같은 인코딩으로 낸다** — OTLP 규약이다.
 */
internal enum class OtlpEncoding(val contentType: String) {
	PROTOBUF("application/x-protobuf"),
	JSON("application/json"),
	;

	fun decode(body: ByteArray, builder: Message.Builder) {
		when (this) {
			PROTOBUF -> builder.mergeFrom(body)
			JSON -> OtlpJson.fromJson(body, builder)
		}
	}

	fun encode(message: Message): ByteArray = when (this) {
		PROTOBUF -> message.toByteArray()
		JSON -> OtlpJson.toJson(message)
	}

	companion object {
		/**
		 * `Content-Type` 에서 미디어 타입만 떼어 고른다. 상위 `getMimeTypeFromContentType` 은
		 * `mime.ParseMediaType` 을 쓰므로 `application/json; charset=utf-8` 도 JSON 이다.
		 * 모르는 타입이면 null — 호출자가 415 를 낸다.
		 */
		fun ofContentType(contentType: String?): OtlpEncoding? =
			when (contentType.orEmpty().substringBefore(';').trim().lowercase()) {
				PROTOBUF.contentType -> PROTOBUF
				JSON.contentType -> JSON
				else -> null
			}
	}
}
