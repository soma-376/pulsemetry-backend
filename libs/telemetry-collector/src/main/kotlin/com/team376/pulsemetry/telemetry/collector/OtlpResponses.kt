package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/**
 * OTLP/HTTP 응답 본문. 상위 `receiver/otlpreceiver/otlphttp.go` 이식.
 *
 * ## 빈 성공 응답은 빈 바이트열이 아니다
 *
 * Go 는 non-nullable 임베디드 메시지를 **비어 있어도 항상 쓴다**. `ExportLogsServiceResponse` 의
 * `partial_success` 가 그런 필드다:
 *
 * ```go
 * func (orig *ExportLogsServiceResponse) MarshalProto(buf []byte) int {
 *     ... pos--; buf[pos] = 0xa   // 필드 1 을 길이 0 으로 항상 쓴다
 * }
 * func (orig *ExportLogsServiceResponse) MarshalJSON(dest *json.Stream) {
 *     dest.WriteObjectStart(); dest.WriteObjectField("partialSuccess"); ...
 * }
 * ```
 *
 * 그래서 성공 본문은 protobuf 가 **2바이트 `0a 00`**, JSON 이 **`{"partialSuccess":{}}`** 다.
 * 파싱하면 어느 쪽이든 같은 메시지지만, 빈 배열이나 `{}` 를 손으로 쓰면 현행 collector 가 내보내던
 * 바이트와 달라진다. `OtlpResponsesTest` 가 이 바이트를 고정한다.
 *
 * 우리는 [OtlpJson] 이 같은 규칙("단일 메시지 필드는 비어 있어도 항상 기록")을 그대로 갖고 있고
 * protobuf-java 는 반대로 빈 임베디드를 생략하므로, **protobuf 경로만** 직접 쓴다.
 */
internal object OtlpResponses {

	/** 상위 `fallbackMsg`. 공백까지 그대로다 — 상태 인코딩 자체가 실패했을 때만 나간다. */
	val FALLBACK_BODY: ByteArray =
		"""{"code": 13, "message": "failed to marshal error message"}""".toByteArray(Charsets.UTF_8)

	const val FALLBACK_CONTENT_TYPE: String = "application/json"

	/** 빈 `ExportServiceResponse`. protobuf 는 `0a 00`, JSON 은 `{"partialSuccess":{}}`. */
	fun success(signal: Signal, encoding: OtlpEncoding): ByteArray = when (encoding) {
		// protobuf-java 는 빈 임베디드 메시지를 생략하므로 Go 와 같은 바이트가 나오지 않는다.
		// 태그(필드 1, wire type 2)와 길이 0 을 직접 쓴다.
		OtlpEncoding.PROTOBUF -> byteArrayOf(0x0a, 0x00)
		OtlpEncoding.JSON -> OtlpJson.toJson(signal.emptyResponse())
	}

	/**
	 * `google.rpc.Status` 를 요청과 같은 인코딩으로 쓴다.
	 *
	 * 타입 하나 때문에 `proto-google-common-protos`(구글 공통 proto 전부) 를 끌지 않고 두 필드를
	 * 직접 쓴다. varint·길이 접두는 protobuf-java 의 [CodedOutputStream] 이 계산하므로 손으로 만든
	 * 와이어 포맷은 없다. proto3 규칙대로 기본값은 생략한다 — `details` 는 우리가 채우지 않는다.
	 *
	 * ```proto
	 * message Status { int32 code = 1; string message = 2; repeated Any details = 3; }
	 * ```
	 */
	fun status(encoding: OtlpEncoding, code: GrpcCode, message: String): ByteArray = when (encoding) {
		OtlpEncoding.PROTOBUF -> {
			val out = ByteArrayOutputStream()
			val cos = CodedOutputStream.newInstance(out)
			if (code.number != 0) cos.writeInt32(1, code.number)
			if (message.isNotEmpty()) cos.writeString(2, message)
			cos.flush()
			out.toByteArray()
		}

		OtlpEncoding.JSON -> {
			val sb = StringBuilder("{")
			if (code.number != 0) sb.append("\"code\":").append(code.number)
			if (message.isNotEmpty()) {
				if (sb.length > 1) sb.append(',')
				sb.append("\"message\":").append(jsonString(message))
			}
			sb.append('}').toString().toByteArray(Charsets.UTF_8)
		}
	}

	/** JSON 문자열 리터럴. 제어문자는 `\uXXXX` 로 — 오류 메시지에 무엇이 섞여 들어올지 모른다. */
	private fun jsonString(value: String): String {
		val sb = StringBuilder(value.length + 2)
		sb.append('"')
		for (c in value) {
			when {
				c == '"' -> sb.append("\\\"")
				c == '\\' -> sb.append("\\\\")
				c == '\n' -> sb.append("\\n")
				c == '\r' -> sb.append("\\r")
				c == '\t' -> sb.append("\\t")
				c < ' ' -> sb.append("\\u%04x".format(c.code))
				else -> sb.append(c)
			}
		}
		return sb.append('"').toString()
	}
}
