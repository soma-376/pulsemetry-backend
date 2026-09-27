package com.team376.pulsemetry.telemetry.enricher.observation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `enrichment_json` 표기 — `json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)` 와 같은 바이트. */
class EnrichmentJsonTest {

	@Test
	@DisplayName("키는 중첩까지 정렬하고 구분자에 공백이 없다 — 넣은 순서와 무관하다")
	fun sortsKeys() {
		val value = linkedMapOf("org" to mapOf("team_ids" to listOf("b", "a")), "github" to emptyMap<String, Any?>(), "ai_analysis" to mapOf("z" to 1, "a" to true))

		assertThat(EnrichmentJson.sorted(value))
			.isEqualTo("""{"ai_analysis":{"a":true,"z":1},"github":{},"org":{"team_ids":["b","a"]}}""")
	}

	@Test
	@DisplayName("따옴표·역슬래시·제어문자만 이스케이프하고 비 ASCII 는 원문이다")
	fun escapesLikePython() {
		assertThat(EnrichmentJson.sorted(mapOf("k" to "팀 \"A\"\\\n\u0001", "n" to null)))
			.isEqualTo("{\"k\":\"팀 \\\"A\\\"\\\\\\n\\u0001\",\"n\":null}")
	}

	@Test
	@DisplayName("실수는 거부한다 — 구 표기를 옮기지 않은 채 받으면 저장 값이 갈린다")
	fun rejectsDoubles() {
		assertThatThrownBy { EnrichmentJson.sorted(mapOf("x" to 0.1)) }.isInstanceOf(IllegalArgumentException::class.java)
	}
}
