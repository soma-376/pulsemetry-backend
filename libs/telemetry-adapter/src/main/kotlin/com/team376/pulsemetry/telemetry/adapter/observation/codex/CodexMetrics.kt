package com.team376.pulsemetry.telemetry.adapter.observation.codex

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
 * Codex 메트릭 family(ADR 0020 부록 A.5). 이름은 family 후보의 단서일 뿐이다 — 등록한 이름이라도 producer 가 쓰는
 * 계측 타입과 다르게 오면 family 를 붙이지 않고 `vendor.metric` 으로 둔다. 등록하지 않은 이름(approval·MCP·hook·
 * skill·multi-agent·task 계열 포함)은 전부 `vendor.metric` 이다 — 접두사로 묶어 승격하지 않는다.
 *
 * 모든 point 는 primary 가 아니다. 토큰 계열은 diagnostic·`usage_scope = interval` 이고 로그 사용량과 더하지 않는다.
 * `token_type` 라벨은 `token_component` 로 옮기되(총계·비캐시 input 은 성분이 아니라 unknown) 원래 라벨은 metadata 에
 * 남는다. 단위 registry 가 없으므로 `canonical_unit` 은 unknown 이다 — 이름 접미사로 추정하지 않는다.
 * `metric_profile` 은 측정 대상(`codex-v1/turn` 등)이다.
 */
internal object CodexMetrics : MetricProfile {

	private class Entry(
		val family: MetricFamily,
		val type: MetricType,
		val profile: String,
		val usageRole: UsageRole = UsageRole.NONE,
		val usageScope: UsageScope = UsageScope.UNKNOWN,
		val workload: WorkloadKind = WorkloadKind.UNKNOWN,
		val tokenLabel: Boolean = false,
	)

	private const val TURN = "codex-v1/turn"
	private const val TOOL_CALL = "codex-v1/tool_call"
	private const val THREAD = "codex-v1/thread"
	private const val CONVERSATION = "codex-v1/conversation"

	private val REGISTRY: Map<String, Entry> = mapOf(
		"codex.turn.token_usage" to Entry(
			MetricFamily.TOKEN_USAGE, MetricType.HISTOGRAM, TURN, UsageRole.DIAGNOSTIC, UsageScope.INTERVAL, tokenLabel = true,
		),
		"codex.turn.tool.call" to Entry(MetricFamily.TOOL_CALLS_PER_TURN, MetricType.HISTOGRAM, TURN),
		"codex.tool.call" to Entry(MetricFamily.TOOL_EXECUTION_COUNT, MetricType.SUM, TOOL_CALL),
		"codex.tool.call.duration_ms" to Entry(MetricFamily.TOOL_EXECUTION_DURATION, MetricType.HISTOGRAM, TOOL_CALL),
		"codex.turn.e2e_duration_ms" to Entry(MetricFamily.TURN_DURATION, MetricType.HISTOGRAM, TURN),
		"codex.turn.ttft.duration_ms" to Entry(MetricFamily.TURN_FIRST_TOKEN, MetricType.HISTOGRAM, TURN),
		"codex.turn.ttfm.duration_ms" to Entry(MetricFamily.TURN_FIRST_ITEM, MetricType.HISTOGRAM, TURN),
		"codex.thread.started" to Entry(MetricFamily.CONVERSATION_STARTED, MetricType.SUM, THREAD),
		"codex.conversation.turn.count" to Entry(MetricFamily.CONVERSATION_TURNS, MetricType.SUM, CONVERSATION),
		"codex.guardian.review.token_usage" to Entry(
			MetricFamily.TOKEN_USAGE, MetricType.HISTOGRAM, "codex-v1/guardian_review",
			UsageRole.DIAGNOSTIC, UsageScope.INTERVAL, WorkloadKind.GUARDIAN, tokenLabel = true,
		),
		"codex.guardian_v2.classification.token_usage" to Entry(
			MetricFamily.TOKEN_USAGE, MetricType.HISTOGRAM, "codex-v1/guardian_classification",
			UsageRole.DIAGNOSTIC, UsageScope.INTERVAL, WorkloadKind.GUARDIAN, tokenLabel = true,
		),
	)

	/** producer 의 `token_type` 라벨 → 성분. 라벨 이름의 대응이 포함관계의 검증은 아니다. */
	private val TOKEN_LABELS = mapOf(
		"input" to TokenComponent.INPUT,
		"output" to TokenComponent.OUTPUT,
		"cached_input" to TokenComponent.CACHE_READ,
		"cache_write_input" to TokenComponent.CACHE_CREATE,
		"reasoning_output" to TokenComponent.REASONING,
	)

	override val allowlist: MetadataAllowlist = MetadataAllowlist(
		version = "codex-metrics-v1",
		resource = setOf(
			"service.name", "service.version", "env", "os", "os_version", "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version",
		),
		// 연결·서버·플러그인·스킬 이름과 경로류 라벨은 뺀다(원형은 아카이브에 남는다).
		record = setOf(
			"token_type", "app.version", "auth_mode", "originator", "model", "model_slug", "session_source", "session_kind", "tmp_mem_enabled",
			"is_git", "reasoning_effort", "status", "success", "tool", "sandbox", "sandbox_policy", "command_category", "service_name",
			"guardian_model", "guardian_reasoning_effort", "had_prior_review_context", "decision", "outcome", "error", "error_type",
			"error_code", "phase", "kind", "handler_type", "execution_mode", "hook_name", "source", "role", "version",
		),
	)

	override fun map(view: MetricPointView, base: MetricPointObservation): MetricPointObservation? {
		val entry = REGISTRY[view.name] ?: return null
		if (base.metricType != entry.type) return null
		val fields = FieldReader(view.pointAttributes)
		val component = if (entry.tokenLabel) {
			fields.nonEmptyText("token_type")?.let { TOKEN_LABELS[it] ?: TokenComponent.UNKNOWN } ?: TokenComponent.UNKNOWN
		} else {
			TokenComponent.NONE
		}
		return base.copy(
			envelope = base.envelope.copy(
				mappingStatus = MappingStatus.MAPPED,
				usageRole = entry.usageRole,
				usageScope = entry.usageScope,
				workloadKind = entry.workload,
				model = fields.nonEmptyText("model"),
			),
			metricFamily = entry.family,
			metricProfile = entry.profile,
			tokenComponent = component,
		)
	}
}
