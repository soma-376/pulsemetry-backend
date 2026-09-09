package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * codex 세 신호의 golden 대조. 이벤트 11종 — 로그 6 · 스팬 4 · 메트릭 통과.
 *
 * ⚠️ codex 는 실 캡처가 한 건도 없다. 이 대조가 보증하는 것은 **이식본이 현행 Python 과 같은
 * 값을 낸다**는 것뿐이고, 그 값이 벤더 실데이터에 대해 옳다는 것은 아니다.
 */
internal class CodexGoldenTest {

	@Nested
	@DisplayName("로그")
	inner class Logs : GoldenFixtureTestBase(
		otlpFixture = "/otlp/codex/logs_synthetic.otlp.jsonl",
		goldenFixture = "/otlp/codex/logs_synthetic.normalized.jsonl",
		signal = GoldenFixtures.Signal.LOGS,
		expectedProduct = "codex",
		expectedAdapterVersion = 2,
	) {
		@Test
		@DisplayName("codex 로그가 낼 수 있는 LogKind 전부를 덮는다")
		fun coversEveryLogKind() {
			assertThat(golden.map {
				@Suppress("UNCHECKED_CAST")
				(it.event as Map<String, Any?>)["type"]
			}.toSet()).containsExactlyInAnyOrder(
				"llm_call",       // api_request, sse_event(토큰 있음)
				"other",          // sse_event(response.completed, 토큰 없음)
				"user_prompt",
				"lifecycle",      // conversation_starts
				"tool_call",      // tool_result
				"tool_decision",
			)
		}

		@Test
		@DisplayName("로그 본문이 아니라 event.name 속성이 매치를 정한다")
		fun eventNameAttributeDecidesTheMatch() {
			// 경계 레코드로 body 가 codex.user_prompt 인데 event.name 속성이 없는 것을
			// 넣어 뒀다. codex 소스는 그것을 물지 않고, claude_code 소스도 prefix 가 달라
			// 물지 않으므로 어디에도 매칭되지 않는다.
			val prompts = eventsOf(0).filter { it["type"] == "user_prompt" }

			assertThat(prompts).hasSize(1)
			assertThat(prompts.single().at("payload", "length")).isEqualTo(77L)
		}

		@Test
		@DisplayName("sse_event 는 kind 로 먼저 걸러지고 토큰 유무로 다시 갈린다")
		fun sseEventBranchesThreeWays() {
			// 1. response.completed 가 아니면 드롭  2. 토큰 없으면 other  3. 있으면 llm_call
			val events = eventsOf(0)

			assertThat(events.map { it["sequence"] }).doesNotContain(4L)
			val other = events.filter { it["type"] == "other" }
			assertThat(other).hasSize(1)
			assertThat(other.single()["sequence"]).isEqualTo(5L)
			assertThat(other.single()["payload"]).isNull()
		}

		@Test
		@DisplayName("cost_usd 가 오면 reported, 없으면 단가표 추정으로 estimated 다")
		fun reportedCostWinsOverEstimate() {
			val calls = eventsOf(0).filter { it["type"] == "llm_call" }.associateBy { it["sequence"] }

			assertThat(calls[2L]!!.at("payload", "cost_source")).isEqualTo("reported")
			assertThat(calls[2L]!!.at("payload", "cost_usd")).isEqualTo(0.0031)
			assertThat(calls[1L]!!.at("payload", "cost_source")).isEqualTo("estimated")
			assertThat(calls[1L]!!.at("payload", "cost_usd") as Double).isGreaterThan(0.0)
		}

		@Test
		@DisplayName("reasoning 은 실리되 별도 칸이고 cache_create 는 언제나 null 이다")
		fun reasoningTokensAreCarriedButSeparate() {
			// reasoning 을 billable 에 더하면 이중계산이다. codex 는 캐시 생성 토큰을
			// 구분하지 않으므로 cache_create 는 채워지지 않는다.
			val call = eventsOf(0).single { it["type"] == "llm_call" && it["sequence"] == 1L }

			assertThat(call.at("payload", "tokens")).isEqualTo(
				mapOf(
					"input" to 1500L, "output" to 400L, "cache_read" to 9000L,
					"cache_create" to null, "reasoning" to 220L, "tool" to null,
					"total_reported" to 11120L,
				),
			)
		}

		@Test
		@DisplayName("billable 이 0 이어도 total_reported 가 있으면 llm_call 이 된다")
		fun totalReportedAloneStillProducesAnLlmCall() {
			// 이때 추정 비용은 계산하지 않으므로 cost_usd 는 null 이다.
			val call = eventsOf(1).single { it["type"] == "llm_call" }

			assertThat(call.at("payload", "tokens", "total_reported")).isEqualTo(42L)
			assertThat(call.at("payload", "cost_usd")).isNull()
		}

		@Test
		@DisplayName("source 속성이 오면 결정 표가 정한 decided_by 를 덮어쓴다")
		fun decisionSourceOverridesTheMapping() {
			val decisions = eventsOf(0)
				.filter { it["type"] == "tool_decision" }
				.associateBy { it.at("payload", "tool_name") }

			// approved_for_session → (accept, user, session). source 속성 없음.
			assertThat(decisions["apply_patch"]!!.at("payload", "decision")).isEqualTo("accept")
			assertThat(decisions["apply_patch"]!!.at("payload", "decided_by")).isEqualTo("user")
			assertThat(decisions["apply_patch"]!!.at("payload", "scope")).isEqualTo("session")

			// denied → (reject, user, once) 인데 source=policy 가 decided_by 만 덮는다.
			assertThat(decisions["web_search"]!!.at("payload", "decision")).isEqualTo("reject")
			assertThat(decisions["web_search"]!!.at("payload", "decided_by")).isEqualTo("policy")
			assertThat(decisions["web_search"]!!.at("payload", "scope")).isEqualTo("once")
		}

		@Test
		@DisplayName("JSON 문자열 도구 인자에서 명령·파일을 뽑고 경로를 통일한다")
		fun toolArgumentsJsonIsMergedAndPathsNormalized() {
			val call = eventsOf(0).single { it["type"] == "tool_call" }

			assertThat(call.at("payload", "command")).isEqualTo("ls -al")
			assertThat(call.at("payload", "files")).isEqualTo(listOf("src/main"))
			assertThat(call.at("payload", "action")).isEqualTo("exec")
		}

		@Test
		@DisplayName("codex 는 턴 상관 ID 가 없어 turn_id 가 언제나 null 이다")
		fun turnIdIsAlwaysNull() {
			assertThat(golden.map {
				@Suppress("UNCHECKED_CAST")
				(it.event as Map<String, Any?>)["turn_id"]
			}).containsOnlyNulls()
		}

		@Test
		@DisplayName("13 레코드 중 9건만 방출된다 — 네 가지 다른 이유로 넷이 빠진다")
		fun unsupportedAndForeignEventsAreDropped() {
			// sse_event 비-completed / codex.some_future_event / event.name 없는 레코드 /
			// 제품 밖 이벤트가 각각 다른 이유로 빠진다.
			assertThat(eventsOf(0)).hasSize(9)
		}
	}

	@Nested
	@DisplayName("스팬")
	inner class Spans : GoldenFixtureTestBase(
		otlpFixture = "/otlp/codex/traces_synthetic.otlp.jsonl",
		goldenFixture = "/otlp/codex/traces_synthetic.normalized.jsonl",
		signal = GoldenFixtures.Signal.TRACES,
		expectedProduct = "codex",
		expectedAdapterVersion = 2,
	) {
		@Test
		@DisplayName("codex 스팬이 낼 수 있는 SpanKind 전부를 덮는다")
		fun coversEverySpanKind() {
			assertThat(eventsOf(0).map { it["type"] }).containsExactlyInAnyOrder(
				"turn", "llm_request", "tool_gate", "tool_execution",
			)
		}

		@Test
		@DisplayName("미지의 codex 스팬은 전달되지 않는다")
		fun unsupportedSpansAreDropped() {
			assertThat(eventsOf(0)).hasSize(4)
		}

		@Test
		@DisplayName("스팬은 토큰·비용을 담지 않는다 — 로그가 이미 싣고 있어 이중계산이 된다")
		fun llmRequestSpanCarriesNoTokensOrCost() {
			val span = eventsOf(0).single { it["type"] == "llm_request" }

			assertThat(span.at("payload", "cost_usd")).isNull()
			assertThat(span.at("payload", "tokens")).isEqualTo(
				mapOf(
					"input" to null, "output" to null, "cache_read" to null,
					"cache_create" to null, "reasoning" to null, "tool" to null,
					"total_reported" to null,
				),
			)
		}

		@Test
		@DisplayName("도구 스팬도 call_id 를 합성하고 그 사실을 표시한다")
		fun toolSpansSynthesizeACallId() {
			val tools = eventsOf(0).filter { it["type"] in setOf("tool_gate", "tool_execution") }

			assertThat(tools).hasSize(2)
			tools.forEach { event ->
				assertThat(event["call_id"] as String).startsWith("syn-")
				assertThat(event.at("envelope", "_ingest", "call_id_inferred")).isEqualTo(true)
			}
		}

		@Test
		@DisplayName("approval_policy·sandbox_policy 는 입력에 있어도 승격되지 않는다")
		fun policyAttributesAreNeverPromoted() {
			// 입력에 실려 있어도 읽지 않는다 — 현행 결함이고 golden 이 고정한다.
			val turn = eventsOf(0).single { it["type"] == "turn" }

			assertThat(turn.at("payload", "attrs")).isEqualTo(
				mapOf("duration_ms" to "12000", "reasoning_effort" to "medium"),
			)
		}
	}

	@Nested
	@DisplayName("메트릭")
	inner class Metrics : GoldenFixtureTestBase(
		otlpFixture = "/otlp/codex/metrics_synthetic.otlp.jsonl",
		goldenFixture = "/otlp/codex/metrics_synthetic.normalized.jsonl",
		signal = GoldenFixtures.Signal.METRICS,
		expectedProduct = "codex",
		expectedAdapterVersion = 2,
	) {
		@Test
		@DisplayName("메트릭 이름은 prefix 를 떼지 않고 그대로 실린다")
		fun metricNamesPassThroughUntouched() {
			assertThat(eventsOf(0).map { it.at("point", "name") })
				.containsExactlyInAnyOrder("codex.token.usage", "codex.turn.duration")
		}

		@Test
		@DisplayName("codex 는 conversation.id·thread.id 로도 세션을 찾는다")
		fun sessionIsFoundUnderCodexSpecificKeys() {
			assertThat(eventsOf(0).map { it.at("envelope", "session_id") })
				.containsOnly("conv-metric-0001")
		}
	}
}
