package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** claude_code 세 신호의 golden 대조. 이벤트 16종 — 로그 9 · 스팬 6 · 메트릭 통과. */
internal class ClaudeCodeGoldenTest {

	@Nested
	@DisplayName("실측 캡처 (로그)")
	inner class RealCapture : GoldenFixtureTestBase(
		otlpFixture = "/otlp/claude_code/logs_real.otlp.jsonl",
		goldenFixture = "/otlp/claude_code/logs_real.normalized.jsonl",
		signal = GoldenFixtures.Signal.LOGS,
		expectedProduct = "claude_code",
		expectedAdapterVersion = 3,
	) {
		@Test
		@DisplayName("캡처가 48문서 × 3레코드 = 144 이벤트라는 사실을 고정한다")
		fun captureShapeIsPinned() {
			assertThat(requests).hasSize(48)
			assertThat(golden).hasSize(144)
		}

		@Test
		@DisplayName("이 캡처는 user_prompt 한 종류뿐이다 — 커버리지 한계를 테스트로 못박는다")
		fun captureContainsOnlyUserPrompt() {
			assertThat(eventsOf(0).map { it["type"] }).containsOnly("user_prompt")
		}

		@Test
		@DisplayName("캡처의 payload.prompt.length 는 어댑터가 읽는 키가 아니라 전부 null 이다")
		fun promptLengthIsNullForEveryRecord() {
			// 어댑터는 `prompt_length` 를 읽는데 캡처는 `payload.prompt.length` 를 싣는다.
			// 현행 결함이고 동작 동일성 때문에 그대로 고정한다.
			val lengths = golden.map { row ->
				@Suppress("UNCHECKED_CAST")
				(row.event as Map<String, Any?>).at("payload", "length")
			}
			assertThat(lengths).containsOnlyNulls()
		}

		@Test
		@DisplayName("144 레코드가 서로 다른 record_id 를 6개만 만든다")
		fun recordsCollapseIntoSixRecordIds() {
			// 같은 (tenant, product, session, sequence, ts, type, 판별자) 가 반복되기 때문이다.
			// ReplacingMergeTree 는 이들을 한 행으로 합치므로 이 캡처를 그대로 적재하면
			// 144건이 6건이 된다.
			val ids = golden.map { row ->
				@Suppress("UNCHECKED_CAST")
				(row.event as Map<String, Any?>).at("envelope", "record_id")
			}
			assertThat(ids.toSet()).hasSize(6)
		}
	}

	@Nested
	@DisplayName("합성 커버리지 (로그)")
	inner class SyntheticLogs : GoldenFixtureTestBase(
		otlpFixture = "/otlp/claude_code/logs_synthetic.otlp.jsonl",
		goldenFixture = "/otlp/claude_code/logs_synthetic.normalized.jsonl",
		signal = GoldenFixtures.Signal.LOGS,
		expectedProduct = "claude_code",
		expectedAdapterVersion = 3,
	) {
		@Test
		@DisplayName("로그 9종이 내는 LogKind 전부를 덮는다")
		fun coversEveryLogKind() {
			assertThat(golden.map { row ->
				@Suppress("UNCHECKED_CAST")
				(row.event as Map<String, Any?>)["type"]
			}.toSet()).containsExactlyInAnyOrder(
				"llm_call",       // api_request, api_error
				"llm_response",   // assistant_response, api_refusal
				"tool_call",      // tool_result
				"tool_decision",  // tool_decision
				"lifecycle",      // mcp_server_connection, compaction
				"user_prompt",    // user_prompt
			)
		}

		@Test
		@DisplayName("어댑터가 모르는 이벤트와 제품 밖 이벤트는 전달되지 않는다")
		fun unsupportedAndForeignEventsAreDropped() {
			// 경계 문서에는 레코드가 5건 있지만 나오는 것은 user_prompt 3건뿐이다 —
			// claude_code.some_future_event 는 어댑터가 null 을 반환해서,
			// some_other_tool.user_prompt 는 어느 소스에도 매칭되지 않아서 각각 빠진다.
			val boundary = eventsOf(4)
			assertThat(boundary).hasSize(3)
			assertThat(boundary.map { it["type"] }).containsOnly("user_prompt")
		}

		@Test
		@DisplayName("session.id 가 없으면 \"(unknown)\" 으로 떨어진다")
		fun missingSessionFallsBackToUnknown() {
			assertThat(eventsOf(4).map { it.at("envelope", "session_id") })
				.contains("(unknown)")
		}

		@Test
		@DisplayName("event.timestamp(ISO8601)가 timeUnixNano 를 이긴다")
		fun isoTimestampWinsOverTimeUnixNano() {
			val matched = golden.map {
				@Suppress("UNCHECKED_CAST")
				it.event as Map<String, Any?>
			}.filter { it["sequence"] == 53L }

			assertThat(matched).hasSize(1)
			// 2026-03-04T05:06:07Z
			assertThat(matched.single().at("envelope", "timestamp")).isEqualTo(1772600767.0)
		}

		@Test
		@DisplayName("tenant.id 가 없어도 폴백 키로 record_id 가 만들어진다")
		fun missingTenantStillProducesARecordId() {
			val anonymous = golden.map {
				@Suppress("UNCHECKED_CAST")
				it.event as Map<String, Any?>
			}.filter { it.at("envelope", "identity", "tenant_id") == null }

			assertThat(anonymous).hasSize(1)
			assertThat(anonymous.single().at("envelope", "record_id") as String)
				.startsWith("idem-")
		}
	}

	@Nested
	@DisplayName("스팬")
	inner class Spans : GoldenFixtureTestBase(
		otlpFixture = "/otlp/claude_code/traces_synthetic.otlp.jsonl",
		goldenFixture = "/otlp/claude_code/traces_synthetic.normalized.jsonl",
		signal = GoldenFixtures.Signal.TRACES,
		expectedProduct = "claude_code",
		expectedAdapterVersion = 3,
	) {
		@Test
		@DisplayName("스팬 6종이 내는 SpanKind 전부를 덮는다")
		fun coversEverySpanKind() {
			assertThat(eventsOf(0).map { it["type"] }).containsExactlyInAnyOrder(
				"turn", "llm_request", "tool", "tool_execution", "tool_gate", "hook",
			)
		}

		@Test
		@DisplayName("미지의 스팬과 제품 밖 스팬은 전달되지 않는다")
		fun unsupportedAndForeignSpansAreDropped() {
			assertThat(eventsOf(1)).isEmpty()
		}

		@Test
		@DisplayName("내용·시각이 같고 span_id 만 다른 두 스팬이 다른 record_id 를 받는다")
		fun spanIdBreaksTheTieBetweenIdenticalSpans() {
			// 스팬은 sequence 가 없어 (session, ts) 만으로 겹칠 수 있다. record_id 는
			// ReplacingMergeTree 의 멱등 키이므로 여기가 어긋나면 서로 다른 두 스팬이
			// 한 행으로 합쳐져 과소집계된다.
			val twins = eventsOf(3).filter { it["type"] == "tool_execution" }

			assertThat(twins).hasSize(2)
			assertThat(twins[0].at("envelope", "timestamp"))
				.isEqualTo(twins[1].at("envelope", "timestamp"))
			assertThat(twins[0]["span_id"]).isNotEqualTo(twins[1]["span_id"])
			assertThat(twins[0].at("envelope", "record_id"))
				.isNotEqualTo(twins[1].at("envelope", "record_id"))
		}

		@Test
		@DisplayName("startTimeUnixNano 가 없으면 timestamp 가 0.0 이 된다")
		fun missingStartTimeFallsBackToZero() {
			val tool = eventsOf(3).single { it["type"] == "tool" }

			assertThat(tool.at("envelope", "timestamp")).isEqualTo(0.0)
			assertThat(tool.at("envelope", "session_id")).isEqualTo("(unknown)")
		}

		@Test
		@DisplayName("스팬은 tool_use_id 가 없어도 call_id 를 합성하지 않는다")
		fun spanCallIdIsNeverSynthesized() {
			// 로그 어댑터는 합성하지만 스팬 어댑터는 읽기만 한다 — 그래서 스팬에서는
			// call_id_inferred 가 언제나 false 다.
			assertThat(golden.map {
				@Suppress("UNCHECKED_CAST")
				(it.event as Map<String, Any?>).at("envelope", "_ingest", "call_id_inferred")
			}).containsOnly(false)

			assertThat(eventsOf(3).single { it["type"] == "tool" }["call_id"]).isNull()
		}

		@Test
		@DisplayName("스팬의 파일 경로는 구분자를 통일하지 않는다 — 로그 경로와 다르다")
		fun spanPathKeepsTheBackslash() {
			// 로그 어댑터는 `\` 를 `/` 로 바꾸지만 스팬 어댑터는 file_path 를 그대로 담는다.
			// 같은 파일이 신호에 따라 다른 문자열이 되는 현행 결함이고, 이식은 동작 동일성이
			// 판정 기준이라 고치지 않는다.
			val tool = eventsOf(0).single { it["type"] == "tool" }
			assertThat(tool.at("payload", "files")).isEqualTo(listOf("src\\main\\App.kt"))
		}
	}

	@Nested
	@DisplayName("메트릭")
	inner class Metrics : GoldenFixtureTestBase(
		otlpFixture = "/otlp/claude_code/metrics_synthetic.otlp.jsonl",
		goldenFixture = "/otlp/claude_code/metrics_synthetic.normalized.jsonl",
		signal = GoldenFixtures.Signal.METRICS,
		expectedProduct = "claude_code",
		expectedAdapterVersion = 3,
	) {
		@Test
		@DisplayName("리더가 분기하는 본문 네 종류를 전부 덮는다")
		fun coversEveryMetricBodyKind() {
			assertThat(golden.map {
				@Suppress("UNCHECKED_CAST")
				(it.event as Map<String, Any?>).at("point", "metric_type")
			}.toSet()).containsExactlyInAnyOrder(
				"sum", "gauge", "histogram", "exponentialHistogram",
			)
		}

		@Test
		@DisplayName("asInt 는 정수로, asDouble 은 실수로 읽는다")
		fun intAndDoubleValuesKeepTheirKind() {
			val byName = eventsOf(0).associateBy { it.at("point", "name") }

			assertThat(byName["claude_code.token.usage"]!!.at("point", "value")).isEqualTo(12345L)
			assertThat(byName["claude_code.session.count"]!!.at("point", "value")).isEqualTo(3.5)
		}

		@Test
		@DisplayName("히스토그램의 count·sum·min·max·버킷·경계가 그대로 실린다")
		fun histogramCarriesBucketsAndBounds() {
			val point = eventsOf(0).single { it.at("point", "name") == "claude_code.response.duration" }

			assertThat(point.at("point", "count")).isEqualTo(7L)
			assertThat(point.at("point", "sum")).isEqualTo(1234.5)
			assertThat(point.at("point", "min")).isEqualTo(12.0)
			assertThat(point.at("point", "max")).isEqualTo(800.25)
			assertThat(point.at("point", "bucket_counts")).isEqualTo(listOf(1L, 2L, 3L, 1L))
			assertThat(point.at("point", "explicit_bounds")).isEqualTo(listOf(10.0, 100.0, 500.0))
		}

		@Test
		@DisplayName("제품 namespace 밖 메트릭은 전달되지 않는다")
		fun foreignMetricsAreDropped() {
			assertThat(eventsOf(1)).isEmpty()
		}

		@Test
		@DisplayName("데이터포인트 속성이 Python str() 표기로 눕는다 — bool 은 \"True\" 다")
		fun datapointAttrsAreStringifiedTheHardWay() {
			// Kotlin 기본 표기("true")를 쓰면 이 속성을 가진 이벤트가 전부 어긋난다.
			val point = eventsOf(2).single()
			assertThat(point.at("point", "attrs")).isEqualTo(
				mapOf("enabled" to "True", "ratio" to "0.25", "count" to "7"),
			)
		}

		@Test
		@DisplayName("asInt·asDouble 이 둘 다 없으면 value 가 0 이 아니라 null 이다")
		fun missingValueStaysNull() {
			val point = eventsOf(2).single()
			assertThat(point.at("point", "value")).isNull()
			assertThat(point.at("point", "start_time")).isNull()
		}
	}
}
