package com.team376.pulsemetry.dashboard.support

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** 키 구조 검사 자체 — 예시의 키 + 경로별로 선언한 가산 키와 정확히 같아야 한다. */
class JsonStructureTest {

	private val mapper = JsonMapper.builder().build()
	private val example = mapper.readTree("""{"a":1,"items":[{"id":"x","name":"y"}],"nested":{"k":null}}""")
	private fun check(actual: String, extensions: Map<String, Set<String>> = emptyMap()) =
		JsonStructure.assertSameKeys("", example, mapper.readTree(actual), extensions)

	@Test
	@DisplayName("선언한 가산 키는 받고, 선언하지 않은 키는 실패한다 — 배열은 첫 원소의 경로로 선언한다")
	fun declaredExtensionsOnly() {
		val withExtra = """{"a":1,"items":[{"id":"x","name":"y","extra":true}],"nested":{"k":null},"top":0}"""
		assertThatCode { check(withExtra, mapOf("" to setOf("top"), "/items/0" to setOf("extra"))) }.doesNotThrowAnyException()
		assertThatThrownBy { check(withExtra, mapOf("" to setOf("top"))) }.isInstanceOf(AssertionError::class.java).hasMessageContaining("/items/0")
		assertThatThrownBy { check(withExtra) }.isInstanceOf(AssertionError::class.java)
	}

	@Test
	@DisplayName("예시의 키가 빠지거나 선언한 가산 키가 빠져도 실패한다")
	fun missingKeysFail() {
		assertThatThrownBy { check("""{"a":1,"items":[{"id":"x"}],"nested":{"k":null}}""") }.isInstanceOf(AssertionError::class.java).hasMessageContaining("/items/0")
		assertThatThrownBy { check("""{"a":1,"items":[{"id":"x","name":"y"}],"nested":{"k":null}}""", mapOf("" to setOf("top"))) }
			.isInstanceOf(AssertionError::class.java)
	}

	@Test
	@DisplayName("선언 목록의 예시 파일은 모두 있다")
	fun declaredExamplesExist() {
		for (name in JsonStructure.EXTENSIONS.keys) assertThatCode { JsonStructure.example(name) }.doesNotThrowAnyException()
	}
}
