package com.team376.pulsemetry.telemetry.adapter.source.codex

import com.team376.pulsemetry.telemetry.adapter.OtlpAttributes
import com.team376.pulsemetry.telemetry.adapter.model.Client
import com.team376.pulsemetry.telemetry.adapter.model.Decision
import com.team376.pulsemetry.telemetry.adapter.model.DecisionScope
import com.team376.pulsemetry.telemetry.adapter.model.DecisionSource
import com.team376.pulsemetry.telemetry.adapter.model.Identity
import com.team376.pulsemetry.telemetry.adapter.model.Surface
import com.team376.pulsemetry.telemetry.adapter.model.ToolAction

/**
 * codex 어댑터가 세 신호에서 공유하는 상수와 신원 조립.
 *
 * ⚠️ **아래 속성 키는 공식 문서 스키마 기준이며 실데이터로 검증되지 않았다.** 구 레포에
 * codex 실 캡처가 한 건도 없다. golden fixture 는 현행 매핑의 **동작**을 고정할 뿐 벤더
 * 실데이터와의 일치를 보증하지 않는다.
 */
internal object CodexCommon {

	const val PREFIX = "codex."
	const val ADAPTER = "codex"
	const val ADAPTER_VERSION = 2

	/**
	 * 도구 이름 → 작업 종류.
	 *
	 * **소문자로 내려 매칭한다** — claude_code 쪽(파스칼 케이스, 대소문자 구분)과 다르다.
	 */
	val ACTIONS: Map<String, ToolAction> = mapOf(
		"apply_patch" to ToolAction.EDIT,
		"edit" to ToolAction.EDIT,
		"write" to ToolAction.WRITE,
		"write_file" to ToolAction.WRITE,
		"read_file" to ToolAction.READ,
		"read" to ToolAction.READ,
		"grep" to ToolAction.SEARCH,
		"web_search" to ToolAction.SEARCH,
		"web.search" to ToolAction.SEARCH,
		"shell" to ToolAction.EXEC,
		"shell_command" to ToolAction.EXEC,
		"local_shell" to ToolAction.EXEC,
		"exec" to ToolAction.EXEC,
		"bash" to ToolAction.EXEC,
	)

	val FILE_KEYS = arrayOf("path", "file_path", "file", "changed_files", "paths")
	val COMMAND_KEYS = arrayOf("command", "cmd", "shell_command", "full_command")

	/** 세션 ID 가 올 수 있는 자리. 앞의 것이 이긴다. */
	val SESSION_KEYS = arrayOf("conversation.id", "conversation_id", "thread.id", "session.id")

	/** 결정·주체·범위 셋. */
	class DecisionTriple(
		val decision: Decision,
		val decidedBy: DecisionSource,
		val scope: DecisionScope,
	)

	/** codex 의 승인 결과를 결정·주체·적용 범위로 분리한다. */
	val DECISIONS: Map<String, DecisionTriple> = mapOf(
		"approved" to DecisionTriple(Decision.ACCEPT, DecisionSource.USER, DecisionScope.ONCE),
		"approved_for_session" to
			DecisionTriple(Decision.ACCEPT, DecisionSource.USER, DecisionScope.SESSION),
		"approved_with_amendment" to
			DecisionTriple(Decision.MODIFY, DecisionSource.USER, DecisionScope.ONCE),
		"denied" to DecisionTriple(Decision.REJECT, DecisionSource.USER, DecisionScope.ONCE),
		"abort" to DecisionTriple(Decision.ABORT, DecisionSource.USER, DecisionScope.ONCE),
	)

	val UNKNOWN_DECISION =
		DecisionTriple(Decision.UNKNOWN, DecisionSource.UNKNOWN, DecisionScope.UNKNOWN)

	val DECISION_SOURCES: Map<String, DecisionSource> = mapOf(
		"user" to DecisionSource.USER,
		"config" to DecisionSource.CONFIG,
		"hook" to DecisionSource.HOOK,
		"policy" to DecisionSource.POLICY,
		"system" to DecisionSource.SYSTEM,
	)

	/**
	 * 신원 조립.
	 *
	 * codex 는 벤더 이메일·계정을 거의 주지 않으므로 [Identity.memberId] 는 온보딩 때 회사가
	 * 박은 리소스 속성에 의존한다. 정본 신뢰 키는 프록시가 검증한
	 * [Identity.installationId] 다.
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
		// claude_code 와 달리 account_uuid 를 보지 않는다.
		vendorAccountId = OtlpAttributes.optString(
			attrs, resourceAttrs, "user.account_id",
		),
	)

	fun client(resourceAttrs: Map<String, Any?>, attrs: Map<String, Any?>): Client = Client(
		product = ADAPTER,
		surface = Surface.CLI,
		// claude_code 와 달리 service.version 폴백이 없다.
		version = OtlpAttributes.optString(attrs, resourceAttrs, "app.version"),
	)

	/** 결정 문자열과 주체 속성에서 최종 (결정, 주체, 범위) 를 만든다. */
	fun resolveDecision(attrs: Map<String, Any?>): DecisionTriple {
		val base = DECISIONS[OtlpAttributes.optString(attrs, "decision") ?: ""] ?: UNKNOWN_DECISION
		val rawSource = OtlpAttributes.optString(attrs, "decision_source", "source")
			?: return base
		// source 가 오면 결정 표가 정한 주체를 **덮어쓴다.** 모르는 값이면 unknown 이 된다.
		return DecisionTriple(
			decision = base.decision,
			decidedBy = DECISION_SOURCES[rawSource.lowercase()] ?: DecisionSource.UNKNOWN,
			scope = base.scope,
		)
	}
}
