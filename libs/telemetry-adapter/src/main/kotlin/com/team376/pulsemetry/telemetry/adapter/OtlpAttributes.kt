package com.team376.pulsemetry.telemetry.adapter

/**
 * OTLP 속성 추출 — 툴 무관 공용.
 *
 * **규칙: 값이 없으면 0 이 아니라 null 이다.** "0건"과 "측정 불가"는 다른 사실이다(ADR 0017).
 */
internal object OtlpAttributes {

	/**
	 * OTLP `AnyValue` 하나를 네이티브 값으로 편다.
	 *
	 * 확인 순서가 이식 원본과 같다. `arrayValue`·`kvlistValue`·`bytesValue` 는 **null 이다** —
	 * 원본이 그 셋을 보지 않는다.
	 */
	fun value(raw: Any?): Any? {
		val map = raw as? Map<*, *> ?: return null
		map["stringValue"]?.let { return it }
		map["intValue"]?.let { return (it as? String)?.toLongOrNull() ?: it }
		map["doubleValue"]?.let { return it }
		map["boolValue"]?.let { return it }
		return null
	}

	/** `{"attributes": [{"key":…, "value":…}]}` 를 평평한 맵으로 편다. */
	fun of(container: Map<String, Any?>?): Map<String, Any?> {
		val list = container?.get("attributes") as? List<*> ?: return emptyMap()
		val out = LinkedHashMap<String, Any?>(list.size)
		for (entry in list) {
			val attribute = entry as? Map<*, *> ?: continue
			val key = attribute["key"] as? String ?: continue
			out[key] = value(attribute["value"])
		}
		return out
	}

	/**
	 * 없으면 null. **0 으로 대체하지 않는다.**
	 *
	 * Boolean 은 건너뛴다 — 원본에서 Python 의 bool 이 int 의 하위 타입이라 명시적으로
	 * 걸러 냈고, 그 판정을 유지한다. 문자열은 전부 숫자일 때만 받는다(부호는 받지 않는다).
	 * NaN 과 Int 범위 밖의 수는 null 이다 — `toInt()` 가 NaN 을 0 으로, 큰 수를 감싸서
	 * 돌려주면 "없음" 이 "0건" 이 된다.
	 */
	fun optInt(attrs: Map<String, Any?>, vararg keys: String): Int? {
		for (key in keys) {
			when (val raw = attrs[key]) {
				is Boolean -> continue
				is Number -> return intOrNull(raw)
				is String -> {
					val text = raw.trim()
					if (text.isNotEmpty() && text.all { it.isDigit() }) {
						text.toIntOrNull()?.let { return it }
					}
				}
			}
		}
		return null
	}

	/** Int 로 정확히 담기는 수만 Int 로. NaN·무한·범위 밖은 null. */
	fun intOrNull(raw: Number): Int? = when (raw) {
		is Int -> raw
		is Long -> if (raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt() else null
		is Double, is Float -> {
			val value = raw.toDouble()
			if (value.isNaN() || value.isInfinite()) {
				null
			} else if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
				null
			} else {
				value.toInt()
			}
		}

		else -> intOrNull(raw.toLong())
	}

	/** 없으면 null. 문자열은 파싱되면 받는다(정수와 달리 부호·소수점을 허용한다). */
	fun optDouble(attrs: Map<String, Any?>, vararg keys: String): Double? {
		for (key in keys) {
			when (val raw = attrs[key]) {
				is Boolean -> continue
				is Number -> return raw.toDouble()
				is String -> if (raw.isNotEmpty()) raw.toDoubleOrNull()?.let { return it }
			}
		}
		return null
	}

	/**
	 * 없으면 null. **빈 문자열은 없는 것으로 본다.**
	 *
	 * 소스를 먼저 돌고 그 안에서 키를 돈다 — 순서가 뒤바뀌면 리소스 속성이 레코드 속성을
	 * 이기게 된다. 이식 원본의 `_opt_str` 과 같은 중첩 순서다.
	 */
	fun optString(attrs: Map<String, Any?>, vararg keys: String): String? =
		firstString(listOf(attrs), keys)

	/**
	 * 소스 둘을 순서대로 뒤진다. 앞의 것이 이긴다.
	 *
	 * 인자 이름이 아니라 **주는 순서**가 우선순위다 — 호출부마다 다르다. 신원은 리소스를
	 * 먼저 보고(회사가 박은 값이 정본), 세션은 레코드를 먼저 본다.
	 */
	fun optString(
		primary: Map<String, Any?>,
		secondary: Map<String, Any?>,
		vararg keys: String,
	): String? = firstString(listOf(primary, secondary), keys)

	private fun firstString(sources: List<Map<String, Any?>>, keys: Array<out String>): String? {
		for (source in sources) {
			for (key in keys) {
				val raw = source[key]
				if (raw is String && raw.isNotEmpty()) return raw
			}
		}
		return null
	}

	/** 없으면 null. `"true"`/`"false"` 문자열도 받는다. */
	fun optBoolean(attrs: Map<String, Any?>, vararg keys: String): Boolean? {
		for (key in keys) {
			when (val raw = attrs[key]) {
				is Boolean -> return raw
				is String -> when (raw.lowercase()) {
					"true" -> return true
					"false" -> return false
				}
			}
		}
		return null
	}

	/** JSON 문자열로 담긴 도구 인자 속성들을 하나의 맵으로 병합한다. `{` 로 시작할 때만 읽는다. */
	fun mergeJsonAttrs(attrs: Map<String, Any?>, vararg keys: String): Map<String, Any?> {
		val out = LinkedHashMap<String, Any?>()
		for (key in keys) {
			val raw = attrs[key] as? String ?: continue
			if (!raw.trim().startsWith("{")) continue
			runCatching { JsonReader.readObject(raw) }.getOrNull()?.let { out.putAll(it) }
		}
		return out
	}

	/** 도구 인자에서 파일 경로를 뽑는다. 경로 구분자는 `/` 로 통일한다. */
	fun extractFiles(payload: Map<String, Any?>, keys: Array<String>): List<String> {
		val files = mutableListOf<String>()
		for (key in keys) {
			when (val raw = payload[key]) {
				is String -> if (raw.isNotEmpty()) files += raw.replace("\\", "/")
				is List<*> -> for (item in raw) {
					if (item is String && item.isNotEmpty()) files += item.replace("\\", "/")
				}
			}
		}
		for (edit in payload["edits"] as? List<*> ?: emptyList<Any?>()) {
			// 비어 있지 않은 문자열만 경로다. 원본이 truthy 검사라 ""·0·false 를 버렸다.
			val path = (edit as? Map<*, *>)?.get("file_path") as? String ?: continue
			if (path.isEmpty()) continue
			files += path.replace("\\", "/")
		}
		return files
	}

	/** 도구 인자에서 명령 문자열을 뽑는다. */
	fun extractCommand(payload: Map<String, Any?>, keys: Array<String>): String? {
		for (key in keys) {
			val raw = payload[key]
			if (raw is String && raw.isNotEmpty()) return raw
		}
		return null
	}
}
