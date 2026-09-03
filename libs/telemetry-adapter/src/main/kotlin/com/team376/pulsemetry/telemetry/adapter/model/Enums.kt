package com.team376.pulsemetry.telemetry.adapter.model

/**
 * 정규화 스키마의 enum. 순수 상수이고 로직이 없다.
 *
 * **[wire] 값이 계약이다.** 직렬화가 이 문자열을 그대로 쓰고, golden fixture 와
 * ClickHouse `raw_json` 이 같은 값을 담는다. 상수 이름은 Kotlin 관례를 따르되
 * [wire] 는 이식 원본과 한 글자도 다르면 안 된다.
 */
public interface WireValued {
	public val wire: String
}

/** 어떤 표면에서 왔는가. 현재 두 소스 모두 [CLI] 로 고정이다. */
public enum class Surface(override val wire: String) : WireValued {
	UNKNOWN("unknown"),
	CLI("cli"),
	IDE("ide"),
	WEB_EXT("web_ext"),
	API("api"),
	CI("ci"),
}

/**
 * 어느 OTel 신호에서 왔는가. 리더가 요청 타입으로 분기해 스탬프한다.
 * 조인은 이 값이 아니라 `call_id` 로 한다.
 */
public enum class SignalType(override val wire: String) : WireValued {
	LOG("log"),
	METRIC("metric"),
	SPAN("span"),
}

/** 값이 벤더가 준 것인지 우리가 단가표로 계산한 것인지. */
public enum class ValueSource(override val wire: String) : WireValued {
	/** 툴이 직접 제공 (claude_code 의 `cost_usd`). */
	REPORTED("reported"),

	/** 단가표로 계산. */
	ESTIMATED("estimated"),
}

/** 로그 = 점(event). "무슨 일이 일어났다"는 순간 사실. */
public enum class LogKind(override val wire: String) : WireValued {
	USER_PROMPT("user_prompt"),
	LIFECYCLE("lifecycle"),
	LLM_CALL("llm_call"),

	/** 모델 응답 완료 또는 assistant message. */
	LLM_RESPONSE("llm_response"),
	TOOL_CALL("tool_call"),
	TOOL_DECISION("tool_decision"),
	OTHER("other"),
}

/**
 * 스팬 = 구간(interval). type 이 곧 역할이다. claude_code 스팬 이름 기준.
 *
 * **[LogKind.TOOL_CALL]·[LogKind.TOOL_DECISION] 과 겹치는 값이 하나도 없다.**
 * 페어링이 로그만 고르는 이유가 이것이다 — `CallIdPairing` KDoc 참고.
 */
public enum class SpanKind(override val wire: String) : WireValued {
	/** `claude_code.interaction` · `codex.conversation_starts` */
	TURN("turn"),

	/** `claude_code.llm_request` · `codex.api_request` */
	LLM_REQUEST("llm_request"),

	/** `claude_code.tool` — 권한 대기와 실행을 포함한 전체 구간 */
	TOOL("tool"),

	/** `claude_code.tool.blocked_on_user` · `codex.tool_decision` — 승인 대기 */
	TOOL_GATE("tool_gate"),

	/** `claude_code.tool.execution` · `codex.tool_result` — 본문 실행 */
	TOOL_EXECUTION("tool_execution"),

	/** `claude_code.hook` — 훅 실행(베타·게이트) */
	HOOK("hook"),
	OTHER("other"),
}

public enum class ToolKind(override val wire: String) : WireValued {
	UNKNOWN("unknown"),
	NATIVE("native"),
	MCP("mcp"),
	SKILL("skill"),
	SUBAGENT("subagent"),
	EXTENSION("extension"),
	API("api"),
	CUSTOM("custom"),
}

/** 툴이 어떤 작업을 수행했는가. */
public enum class ToolAction(override val wire: String) : WireValued {
	OTHER("other"),

	/** 파일·문서 읽기 */
	READ("read"),

	/** grep · glob · web search */
	SEARCH("search"),

	/** 새 파일 생성 */
	WRITE("write"),

	/** 기존 파일 수정 */
	EDIT("edit"),
	DELETE("delete"),

	/** bash · python 실행 */
	EXEC("exec"),

	/** API·MCP 호출, 외부 데이터 조회 */
	FETCH("fetch"),

	/** 이미지·문서·초안 생성 */
	GENERATE("generate"),
}

/** 툴 실행 요청에 내려진 최종 결정. */
public enum class Decision(override val wire: String) : WireValued {
	UNKNOWN("unknown"),
	ACCEPT("accept"),
	MODIFY("modify"),
	REJECT("reject"),
	ABORT("abort"),
}

/** 누가 또는 무엇이 결정했는가. */
public enum class DecisionSource(override val wire: String) : WireValued {
	UNKNOWN("unknown"),
	USER("user"),
	CONFIG("config"),
	HOOK("hook"),
	POLICY("policy"),
	SYSTEM("system"),
}

/** 결정이 어디까지, 얼마나 오래 적용되는가. */
public enum class DecisionScope(override val wire: String) : WireValued {
	UNKNOWN("unknown"),
	ONCE("once"),
	SESSION("session"),
	PROJECT("project"),
	WORKSPACE("workspace"),
	PERMANENT("permanent"),
}
