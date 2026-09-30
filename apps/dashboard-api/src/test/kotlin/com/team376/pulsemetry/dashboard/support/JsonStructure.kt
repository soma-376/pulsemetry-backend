package com.team376.pulsemetry.dashboard.support

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * 응답의 키 구조를 요청서의 예시 JSON(`test/resources/frontend-api/`)과 대조한다 — 값이 아니라 키.
 *
 * 예시 사본은 고치지 않는다. 요청서에 없던 필드를 가산으로 더했으면 [EXTENSIONS] 에 **예시 파일·경로 단위로** 선언한다(`frontend-api/README.md`).
 * 응답의 키는 "예시의 키 + 그 경로에 선언한 가산 키"와 정확히 같아야 한다 — 선언하지 않은 키가 있어도, 예시의 키나 선언한 키가 빠져도 실패다.
 */
object JsonStructure {

	/** 예시 파일 → (경로 → 가산 키). 경로는 [assertSameKeys] 의 표기(`/a/b`, 배열은 첫 원소 `/0`)다. */
	val EXTENSIONS: Map<String, Map<String, Set<String>>> = mapOf(
		// 관측 제품별 사용 — ADR 0045.
		"overview-response.example.json" to mapOf(
			"" to setOf("productUsage"),
			"/teamUsage/topTeams/0" to setOf("products"),
			"/teamUsage/unassigned" to setOf("products"),
		),
		"teams-response.example.json" to mapOf(
			"/teams/items/0" to setOf("products"),
			"/unassigned" to setOf("products"),
		),
		// 관측됐지만 등록하지 않은 제품·매핑 없는 관측 — ADR 0044.
		"settings-response.example.json" to mapOf(
			"/summary" to setOf("detectedProducts", "unmappedObservations"),
			// 조직 정책 설정의 판·저장 시각·출처·저장할 수 있는 값 — ADR 0046.
			"/collectionPolicy" to setOf("settingsVersion", "settingsUpdatedAt", "settingsUpdatedBy", "reclaimIdleDaysSource", "options"),
		),
	)

	fun example(name: String): JsonNode = JsonMapper.builder().build().readTree(Files.readString(Path.of("src/test/resources/frontend-api/$name")))

	/** [name] 예시와 그 예시에 선언한 가산 키로 [actual] 의 키 구조를 대조한다. */
	fun assertMatches(name: String, actual: JsonNode) = assertSameKeys("", example(name), actual, EXTENSIONS[name].orEmpty())

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
