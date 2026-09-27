package com.team376.pulsemetry.dashboard.request

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeType
import java.util.Base64

/**
 * 목록의 다음 페이지 위치. 클라이언트에게는 불투명 문자열이다.
 *
 * cursor 는 원 요청에 묶인다 — [snapshotId], [scope](endpoint·필터·정렬을 나타내는 문자열)가 지금 요청과 다르면 쓸 수 없다.
 * 그 대조는 목록을 내는 쪽이 한다. [after] 는 마지막으로 낸 행의 정렬 키(지표 값·불변 ID)이고 값이 없는 지표는 `null` 이다.
 *
 * 서명하지 않는다. 위조한 cursor 로 얻을 수 있는 것은 같은 요청이 이미 허용한 snapshot 의 다른 위치뿐이고, 권한·snapshot 범위는
 * 매 요청 다시 검사한다(허브 ADR 0007 §4).
 */
data class PageCursor(
	val snapshotId: String,
	val scope: String,
	val after: List<String?>,
)

/** [PageCursor] ↔ 불투명 문자열. 형식: base64url(패딩 없음) JSON `{"v":1,"s":…,"c":…,"a":[…]}`. */
class PageCursorCodec(private val mapper: ObjectMapper) {

	fun encode(cursor: PageCursor): String {
		val json = mapper.createObjectNode()
			.put(VERSION, CURRENT_VERSION)
			.put(SNAPSHOT, cursor.snapshotId)
			.put(SCOPE, cursor.scope)
		val after = json.putArray(AFTER)
		cursor.after.forEach { if (it == null) after.addNull() else after.add(it) }
		return ENCODER.encodeToString(mapper.writeValueAsBytes(json))
	}

	/** 해석할 수 없으면 `null`. */
	fun decode(token: String): PageCursor? {
		if (token.isEmpty() || token.length > MAX_LENGTH) return null
		val json = runCatching { mapper.readTree(DECODER.decode(token)) }.getOrNull() ?: return null
		if (!json.isObject || json.size() != FIELD_COUNT) return null
		val version = json.get(VERSION)
		val snapshot = json.get(SNAPSHOT)
		val scope = json.get(SCOPE)
		val after = json.get(AFTER)
		if (version == null || !version.isInt || version.asInt() != CURRENT_VERSION) return null
		if (snapshot == null || !snapshot.isString || scope == null || !scope.isString) return null
		if (after == null || !after.isArray) return null
		val keys = ArrayList<String?>(after.size())
		for (index in 0 until after.size()) {
			val node = after.get(index)
			keys += when (node.nodeType) {
				JsonNodeType.NULL -> null
				JsonNodeType.STRING -> node.asString()
				else -> return null
			}
		}
		return PageCursor(snapshot.asString(), scope.asString(), keys)
	}

	private companion object {
		const val VERSION = "v"
		const val SNAPSHOT = "s"
		const val SCOPE = "c"
		const val AFTER = "a"
		const val FIELD_COUNT = 4
		const val CURRENT_VERSION = 1

		/** 정렬 키가 몇 개 안 되는 cursor 가 이보다 길 이유가 없다. 긴 입력을 해석하지 않는다. */
		const val MAX_LENGTH = 2048

		val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
		val DECODER: Base64.Decoder = Base64.getUrlDecoder()
	}
}
