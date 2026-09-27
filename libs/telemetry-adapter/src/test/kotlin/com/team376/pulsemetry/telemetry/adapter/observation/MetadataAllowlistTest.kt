package com.team376.pulsemetry.telemetry.adapter.observation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** allowlist 와 `attrs` 파생 (ADR 0020 §1). */
class MetadataAllowlistTest {

	@ParameterizedTest(name = "{0}")
	@ValueSource(
		strings = [
			"prompt", "gen_ai.prompt", "body", "content", "tool_parameters", "arguments", "codex.tool.arguments",
			"output", "command", "error.message", "exception.stacktrace", "cwd", "file_path", "code.file.path",
			"api_key", "auth.token", "Authorization", "http.url", "url.full", "db.statement",
		],
	)
	@DisplayName("본문·프롬프트·도구 인자/출력·명령·자유 오류·경로·자격증명 이름은 allowlist 에 올릴 수 없다")
	fun forbiddenNamesCannotBeAllowlisted(key: String) {
		assertThat(MetadataAllowlist.isForbidden(key)).isTrue()
		assertThatThrownBy { MetadataAllowlist(version = "t1", record = setOf(key)) }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining(key)
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(
		strings = [
			"event.kind", "prompt.id", "prompt_length", "input_token_count", "cache_read_tokens", "query_source",
			"model", "decision", "source", "success", "duration_ms", "tool_name", "terminal.type", "result",
		],
	)
	@DisplayName("식별자·길이·상태·분류 이름은 올릴 수 있다")
	fun safeNamesAreAllowed(key: String) {
		assertThat(MetadataAllowlist.isForbidden(key)).isFalse()
	}

	@Test
	@DisplayName("등록된 키만 남기고, 경계·순서·중복은 그대로다")
	fun filterKeepsOnlyRegisteredKeys() {
		val allowlist = MetadataAllowlist(version = "t1", resource = setOf("service.name"), record = setOf("event.kind", "thread.id"))
		val record = listOf(
			TypedAttribute("thread.id", TypedValue.Int(1)),
			TypedAttribute("prompt", TypedValue.Str("본문")),
			TypedAttribute("event.kind", TypedValue.Str("response.completed")),
			TypedAttribute("thread.id", TypedValue.Str("t")),
		)

		val metadata = allowlist.filter(
			resource = listOf(TypedAttribute("service.name", TypedValue.Str("codex")), TypedAttribute("host.name", TypedValue.Str("h"))),
			scope = listOf(TypedAttribute("event.kind", TypedValue.Str("scope-level"))),
			record = record,
		)

		assertThat(metadata.resource.map { it.key }).containsExactly("service.name")
		assertThat(metadata.scope).isEmpty()
		assertThat(metadata.record.map { it.key }).containsExactly("thread.id", "event.kind", "thread.id")
		assertThat(metadata.toJson()).doesNotContain("본문").doesNotContain("host.name")
	}

	@Test
	@DisplayName("attrs — 레코드의 스칼라만 문자열로 편다")
	fun attrsFlattenRecordScalars() {
		val derived = ScalarAttrs.derive(
			listOf(
				TypedAttribute("event.kind", TypedValue.Str("response.completed")),
				TypedAttribute("success", TypedValue.Bool(false)),
				TypedAttribute("count", TypedValue.Int(9_007_199_254_740_993L)),
				TypedAttribute("ratio", TypedValue.Double(0.5)),
				TypedAttribute("raw", TypedValue.bytes(byteArrayOf(1))),
				TypedAttribute("list", TypedValue.Array(listOf(TypedValue.Str("x")))),
			),
		)

		assertThat(derived.attrs).containsExactly(
			entry("count", "9007199254740993"),
			entry("event.kind", "response.completed"),
			entry("ratio", "0.5"),
			entry("success", "false"),
		)
		assertThat(derived.flags).isEmpty()
	}

	@Test
	@DisplayName("attrs — 같은 키·같은 값의 중복은 하나, 값이나 타입이 다르면 빼고 ambiguous_attribute")
	fun attrsDropConflictingDuplicates() {
		val derived = ScalarAttrs.derive(
			listOf(
				TypedAttribute("same", TypedValue.Str("a")),
				TypedAttribute("same", TypedValue.Str("a")),
				TypedAttribute("thread.id", TypedValue.Int(1)),
				TypedAttribute("thread.id", TypedValue.Str("1")),
				TypedAttribute("kind", TypedValue.Str("x")),
				TypedAttribute("kind", TypedValue.Str("y")),
			),
		)

		assertThat(derived.attrs).containsExactly(entry("same", "a"))
		assertThat(derived.flags).containsExactly(QualityFlag.AMBIGUOUS_ATTRIBUTE)
	}

	private fun entry(key: String, value: String) = org.assertj.core.api.Assertions.entry(key, value)
}
