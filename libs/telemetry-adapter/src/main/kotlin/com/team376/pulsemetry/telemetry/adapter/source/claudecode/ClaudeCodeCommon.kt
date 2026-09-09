package com.team376.pulsemetry.telemetry.adapter.source.claudecode

import com.team376.pulsemetry.telemetry.adapter.OtlpAttributes
import com.team376.pulsemetry.telemetry.adapter.model.Client
import com.team376.pulsemetry.telemetry.adapter.model.Decision
import com.team376.pulsemetry.telemetry.adapter.model.DecisionScope
import com.team376.pulsemetry.telemetry.adapter.model.DecisionSource
import com.team376.pulsemetry.telemetry.adapter.model.Identity
import com.team376.pulsemetry.telemetry.adapter.model.Surface
import com.team376.pulsemetry.telemetry.adapter.model.ToolAction

/** claude_code 어댑터가 세 신호에서 공유하는 상수와 신원 조립. */
internal object ClaudeCodeCommon {

	const val PREFIX = "claude_code."
	const val ADAPTER = "claude_code"
	const val ADAPTER_VERSION = 3

	/**
	 * 도구 이름 → 작업 종류.
	 *
	 * **대소문자를 구분한다.** claude_code 의 도구 이름이 파스칼 케이스이기 때문이다
	 * (codex 쪽은 소문자로 내려 매칭한다). 소문자화하면 전부 `other` 가 된다.
	 */
	val ACTIONS: Map<String, ToolAction> = mapOf(
		"Edit" to ToolAction.EDIT,
		"Write" to ToolAction.WRITE,
		"NotebookEdit" to ToolAction.EDIT,
		"MultiEdit" to ToolAction.EDIT,
		"Read" to ToolAction.READ,
		"Grep" to ToolAction.SEARCH,
		"Glob" to ToolAction.SEARCH,
		"WebFetch" to ToolAction.FETCH,
		"WebSearch" to ToolAction.SEARCH,
		"Bash" to ToolAction.EXEC,
		"PowerShell" to ToolAction.EXEC,
	)

	val FILE_KEYS = arrayOf("file_path", "notebook_path", "path", "pattern")
	val COMMAND_KEYS = arrayOf("command", "bash_command", "full_command")

	/** 결정·주체·범위 셋을 함께 나르는 매핑 결과. */
	class DecisionMapping(
		val decision: Decision = Decision.UNKNOWN,
		val decidedBy: DecisionSource = DecisionSource.UNKNOWN,
		val scope: DecisionScope = DecisionScope.UNKNOWN,
	)

	/**
	 * `tool_decision.source` → 결정 매핑.
	 *
	 * claude_code 의 `source` 는 결정 주체만이 아니라 **결과와 범위 정보도 함께 담는다** —
	 * `user_reject` 하나가 (reject, user, once) 셋을 정한다. 그래서 단순 주체 맵이 아니다.
	 */
	val DECISION_SOURCES: Map<String, DecisionMapping> = mapOf(
		"config" to DecisionMapping(decidedBy = DecisionSource.CONFIG),
		"hook" to DecisionMapping(decidedBy = DecisionSource.HOOK),
		"user_permanent" to DecisionMapping(
			decision = Decision.ACCEPT,
			decidedBy = DecisionSource.USER,
			scope = DecisionScope.PERMANENT,
		),
		"user_temporary" to DecisionMapping(
			decision = Decision.ACCEPT,
			decidedBy = DecisionSource.USER,
		),
		"user_reject" to DecisionMapping(
			decision = Decision.REJECT,
			decidedBy = DecisionSource.USER,
			scope = DecisionScope.ONCE,
		),
		"user_abort" to DecisionMapping(
			decision = Decision.ABORT,
			decidedBy = DecisionSource.USER,
			scope = DecisionScope.ONCE,
		),
	)

	val DECISION_VALUES: Map<String, Decision> = mapOf(
		"accept" to Decision.ACCEPT,
		"modify" to Decision.MODIFY,
		"reject" to Decision.REJECT,
		"abort" to Decision.ABORT,
	)

	/**
	 * 신원 조립.
	 *
	 * [Identity.installationId] 가 정본이다 — 프록시가 검증해 수집 단계가 리소스 속성으로
	 * 심는다. [Identity.memberId] 는 온보딩 때 회사가 박은 자기신고 값이고, `vendor*` 는
	 * 벤더가 준 정보성 값이다(섀도우 AI 탐지용).
	 */
	fun identity(
		resourceAttrs: Map<String, Any?>,
		attrs: Map<String, Any?>,
		tenantId: String?,
	): Identity = Identity(
		tenantId = tenantId,
		memberId = OtlpAttributes.optString(
			resourceAttrs, attrs, "developer.email", "developer.id",
		),
		installationId = OtlpAttributes.optString(
			resourceAttrs, attrs, "developer.installation_id",
		),
		vendorEmail = OtlpAttributes.optString(attrs, resourceAttrs, "user.email"),
		vendorAccountId = OtlpAttributes.optString(
			attrs, resourceAttrs, "user.account_uuid", "user.account_id",
		),
	)

	fun client(resourceAttrs: Map<String, Any?>, attrs: Map<String, Any?>): Client = Client(
		product = ADAPTER,
		surface = Surface.CLI,
		version = OtlpAttributes.optString(
			attrs, resourceAttrs, "app.version", "service.version",
		),
	)
}
