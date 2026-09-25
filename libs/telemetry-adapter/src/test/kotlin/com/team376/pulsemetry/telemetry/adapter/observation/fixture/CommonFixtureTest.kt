package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** `otlp-v2/common/synthetic/` — 벤더와 무관한 수용 사례. 제품 프로파일 없이 돈다. */
class CommonFixtureTest {

	private val suite = FixtureSuite()

	@TestFactory
	@DisplayName("common/synthetic 사례")
	fun commonSynthetic(): List<DynamicTest> {
		val cases = suite.load("common/synthetic")
		assertThat(cases).isNotEmpty()
		return cases.map { case ->
			DynamicTest.dynamicTest(case.name) {
				val errors = suite.check(case)
				assertThat(errors).describedAs(errors.joinToString("\n", prefix = "\n")).isEmpty()
			}
		}
	}

	@Test
	@DisplayName("하네스 자체 — 틀린 기대·모르는 필드·깨진 \$same·근거 절 누락·개수 불일치를 모두 잡는다")
	fun harnessReportsMismatches() {
		val document = JsonTree.parse(
			"""{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"x"}}]},
			   "scopeLogs":[{"logRecords":[{"observedTimeUnixNano":"5"},{"observedTimeUnixNano":"6"}]}]}]}""",
		) as Map<*, *>
		val wrong = JsonTree.parse(
			"""{"events":[{"event_type":"vendor.unknown","no_such_column":1,"observation_id":{"${'$'}same":"x"}},
			              {"observation_id":{"${'$'}same":"x"}}]}""",
		) as Map<*, *>
		val tooFew = JsonTree.parse("""{"spec":"t","events":[]}""") as Map<*, *>

		val errors = suite.check(FixtureCase("self-test", listOf(document, document), listOf(wrong, tooFew)))

		assertThat(errors.joinToString("\n"))
			.contains("근거 절(spec)이 없다")
			.contains("event_type: 기대 \"vendor.unknown\" · 실제 \"diagnostic\"")
			.contains("모르는 필드 no_such_column")
			.contains("관측 0개를 기대했는데 2개다")
			.contains("\$same:x 값이 다르다")
	}

	@Test
	@DisplayName("하네스 자체 — SENSITIVE: 로 표시한 원문이 관측에 남으면 잡는다(allowlist 된 event.name 에 실어 본다)")
	fun harnessCatchesLeakedSensitiveText() {
		val document = JsonTree.parse(
			"""{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"x"}}]},
			   "scopeLogs":[{"logRecords":[{"observedTimeUnixNano":"5",
			     "attributes":[{"key":"event.name","value":{"stringValue":"SENSITIVE:leak"}}]}]}]}]}""",
		) as Map<*, *>
		val expected = JsonTree.parse("""{"spec":"t","events":[{}]}""") as Map<*, *>

		assertThat(suite.check(FixtureCase("self-test", listOf(document), listOf(expected))).joinToString("\n"))
			.contains("원문이 분석 관측에 남았다")
	}
}
