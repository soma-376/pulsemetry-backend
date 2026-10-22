package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType

/**
 * OTLP/HTTP 본문 인코딩의 디코더·인코더. 상위 `receiver/otlpreceiver/encoder.go` 이식.
 *
 * 허용 목록과 `Content-Type` 판정은 [OtlpMediaType] 이 소유한다 — Gateway 도 같은 판정을 쓴다(ADR 0067).
 * 여기는 그 판정 위에 OTLP 메시지 디코딩을 얹는다. **오류 응답도 요청과 같은 인코딩으로 낸다** — OTLP 규약이다.
 */
internal enum class OtlpEncoding(val mediaType: OtlpMediaType) {
	PROTOBUF(OtlpMediaType.PROTOBUF),
	JSON(OtlpMediaType.JSON),
	;

	val contentType: String get() = mediaType.contentType

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
		fun of(mediaType: OtlpMediaType): OtlpEncoding = when (mediaType) {
			OtlpMediaType.PROTOBUF -> PROTOBUF
			OtlpMediaType.JSON -> JSON
		}

		/** 모르는 타입이면 null — 호출자가 415 를 낸다. 판정 규칙은 [OtlpMediaType.ofContentType] 이다. */
		fun ofContentType(contentType: String?): OtlpEncoding? = OtlpMediaType.ofContentType(contentType)?.let(::of)
	}
}
