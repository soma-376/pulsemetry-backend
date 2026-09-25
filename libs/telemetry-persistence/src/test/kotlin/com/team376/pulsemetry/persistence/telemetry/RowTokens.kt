package com.team376.pulsemetry.persistence.telemetry

/**
 * JSONEachRow 한 줄을 최상위 키 → **원문 토큰** 으로 나눈다(키 순서 보존). 값을 해석하지 않으므로 정수·Decimal 의 표기를
 * 그대로 볼 수 있다 — JSON 라이브러리는 숫자를 Double 로 읽어 이 테스트가 보려는 것을 지운다.
 */
internal object RowTokens {

	fun parse(line: String): LinkedHashMap<String, String> {
		val result = LinkedHashMap<String, String>()
		var i = 1
		require(line.first() == '{' && line.last() == '}') { "객체 한 줄이 아니다" }
		while (i < line.length - 1) {
			if (line[i] == ',') i++
			val keyEnd = stringEnd(line, i)
			val key = line.substring(i + 1, keyEnd - 1)
			require(line[keyEnd] == ':') { "키 뒤에 ':' 가 없다: $key" }
			val start = keyEnd + 1
			val end = valueEnd(line, start)
			result[key] = line.substring(start, end)
			i = end
		}
		return result
	}

	/** 따옴표로 시작하는 문자열의 닫는 따옴표 다음 위치. */
	private fun stringEnd(line: String, start: Int): Int {
		require(line[start] == '"') { "문자열이 아니다: ${line.substring(start).take(20)}" }
		var i = start + 1
		while (line[i] != '"') i += if (line[i] == '\\') 2 else 1
		return i + 1
	}

	private fun valueEnd(line: String, start: Int): Int {
		if (line[start] == '"') return stringEnd(line, start)
		var depth = 0
		var i = start
		while (i < line.length) {
			when (line[i]) {
				'"' -> { i = stringEnd(line, i); continue }
				'[', '{' -> depth++
				']', '}' -> { if (depth == 0) return i; depth-- }
				',' -> if (depth == 0) return i
			}
			i++
		}
		return i
	}
}
