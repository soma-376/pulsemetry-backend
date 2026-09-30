package com.team376.pulsemetry.dashboard.support

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** 응답의 키 구조를 요청서의 예시 JSON(`test/resources/frontend-api/`)과 대조한다 — 값이 아니라 키. */
object JsonStructure {

	fun example(name: String): JsonNode = JsonMapper.builder().build().readTree(Files.readString(Path.of("src/test/resources/frontend-api/$name")))

	/**
	 * 두 쪽이 모두 객체면 키 집합이 같아야 하고 재귀한다. 배열은 첫 원소끼리. 한쪽이 null 이면(nullable 필드) 거기서 멈춘다.
	 * [extensions] 는 경로마다 **선언한 가산 키**다 — 응답의 키는 "예시의 키 + 그 경로에 선언한 가산 키"와 정확히 같아야 한다(예시 사본은 고치지 않는다).
	 */
	fun assertSameKeys(path: String, expected: JsonNode, actual: JsonNode, extensions: Map<String, Set<String>> = emptyMap()) {
		if (expected.isNull || actual.isNull) return
		if (expected.isObject) {
			assertThat(actual.isObject).describedAs(path).isTrue()
			assertThat(actual.propertyNames().asSequence().toSet()).describedAs(path)
				.isEqualTo(expected.propertyNames().asSequence().toSet() + extensions[path].orEmpty())
			for (name in expected.propertyNames()) assertSameKeys("$path/$name", expected.get(name), actual.get(name), extensions)
		} else if (expected.isArray && expected.size() > 0 && actual.size() > 0) {
			assertSameKeys("$path/0", expected.get(0), actual.get(0), extensions)
		}
	}
}
