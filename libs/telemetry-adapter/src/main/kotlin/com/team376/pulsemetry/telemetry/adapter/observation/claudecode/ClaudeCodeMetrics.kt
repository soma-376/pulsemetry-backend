package com.team376.pulsemetry.telemetry.adapter.observation.claudecode

import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.MetricFamily
import com.team376.pulsemetry.telemetry.adapter.observation.MetricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetricType
import com.team376.pulsemetry.telemetry.adapter.observation.TokenComponent
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.WorkloadKind
import com.team376.pulsemetry.telemetry.adapter.observation.profile.FieldReader
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricPointView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile

/**
 * Claude Code 메트릭 family(ADR 0020 부록 A.5). 이름과 실제 계측 타입(전부 sum)이 함께 맞을 때만 family 를 붙이고, 그 밖은
 * `vendor.metric` 이다. wire 타입·temporality·단위는 원형 그대로 남는다.
 *
 * 비용·토큰 계열은 로그 사용량의 대조용이다 — diagnostic·`usage_scope = interval` 이고 KPI 에 더하지 않는다. `token.usage` 의
 * `type` 라벨은 `token_component` 로 옮기고(`cacheRead` → cache_read, `cacheCreation` → cache_create) 원래 라벨은 metadata 에
 * 남긴다. 라벨의 범주 `query_source`(main/subagent/auxiliary)는 작업 목적으로 옮긴다.
 */
internal object ClaudeCodeMetrics : MetricProfile {

	private class Entry(val family: MetricFamily, val profile: String, val usage: Boolean = false, val tokenLabel: Boolean = false)

	private val REGISTRY: Map<String, Entry> = mapOf(
		"claude_code.session.count" to Entry(MetricFamily.SESSION_STARTED, "claude-code-v1/session"),
		"claude_code.lines_of_code.count" to Entry(MetricFamily.CODE_LINES_CHANGED, "claude-code-v1/code"),
		"claude_code.pull_request.count" to Entry(MetricFamily.SCM_PULL_REQUEST_CREATED, "claude-code-v1/scm"),
		"claude_code.commit.count" to Entry(MetricFamily.SCM_COMMIT_CREATED, "claude-code-v1/scm"),
		"claude_code.cost.usage" to Entry(MetricFamily.COST_REPORTED, "claude-code-v1/api_request", usage = true),
		"claude_code.token.usage" to Entry(MetricFamily.TOKEN_USAGE, "claude-code-v1/api_request", usage = true, tokenLabel = true),
		"claude_code.code_edit_tool.decision" to Entry(MetricFamily.TOOL_EDIT_DECISION_COUNT, "claude-code-v1/tool_decision"),
		"claude_code.active_time.total" to Entry(MetricFamily.ACTIVITY_ACTIVE_DURATION, "claude-code-v1/session"),
	)

	/** producer 의 `type` 라벨 → 성분. 라벨 이름의 대응이 포함관계의 검증은 아니다. */
	private val TOKEN_LABELS = mapOf(
		"input" to TokenComponent.INPUT,
		"output" to TokenComponent.OUTPUT,
		"cacheRead" to TokenComponent.CACHE_READ,
		"cacheCreation" to TokenComponent.CACHE_CREATE,
	)

	override val allowlist: MetadataAllowlist = MetadataAllowlist(
		version = "claude-code-metrics-v1",
		resource = setOf("service.name", "service.version", "host.arch", "os.type", "os.version"),
		// 계정·조직·이메일·사용자 해시는 뺀다.
		record = setOf(
			"session.id", "terminal.type", "type", "model", "query_source", "speed", "effort", "agent.name", "skill.name", "plugin.name",
			"marketplace.name", "mcp_server.name", "mcp_tool.name", "decision", "source", "language", "tool_name", "start_type",
		),
	)

	override fun map(view: MetricPointView, base: MetricPointObservation): MetricPointObservation? {
		val entry = REGISTRY[view.name] ?: return null
		if (base.metricType != MetricType.SUM) return null
		val fields = FieldReader(view.pointAttributes)
		return base.copy(
			envelope = base.envelope.copy(
				mappingStatus = MappingStatus.MAPPED,
				usageRole = if (entry.usage) UsageRole.DIAGNOSTIC else UsageRole.NONE,
				usageScope = if (entry.usage) UsageScope.INTERVAL else UsageScope.UNKNOWN,
				workloadKind = if (entry.usage) workload(fields.nonEmptyText("query_source")) else WorkloadKind.UNKNOWN,
				model = fields.nonEmptyText("model"),
			),
			metricFamily = entry.family,
			metricProfile = entry.profile,
			tokenComponent = if (entry.tokenLabel) {
				fields.nonEmptyText("type")?.let { TOKEN_LABELS[it] ?: TokenComponent.UNKNOWN } ?: TokenComponent.UNKNOWN
			} else {
				TokenComponent.NONE
			},
		)
	}

	/** 카운터의 `query_source` 는 범주 셋이다(main/subagent/auxiliary). auxiliary 와 모르는 값은 unknown. */
	private fun workload(category: String?): WorkloadKind = when (category) {
		"main" -> WorkloadKind.MAIN
		"subagent" -> WorkloadKind.SUBAGENT
		else -> WorkloadKind.UNKNOWN
	}
}
