package com.team376.pulsemetry.dashboard.query

import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `/v1/query` 가 쓰는 SQL 조각 · 지표 집합 · 순수 헬퍼. 고정식만 허용한다 — 필터 값과 신원 대응표는
 * 타입이 있는 파라미터로 전달한다. `people` 은 `activePoint` · `person` · `known` 뒤에 선언돼야 한다.
 */
internal data class Scope(val parameters: Map<String, String>, val members: Int, val teamNames: Map<String, String>, val teamMembers: Map<String, Set<UUID>>, val installations: Int)
internal data class Rows(val data: List<JsonNode>, val sql: String)

internal object DashboardQuerySql {
    val pointMetrics = mapOf("sessions" to "claude_code.session.count",
        "active_time" to "claude_code.active_time.total", "lines_of_code" to "claude_code.lines_of_code.count",
        "commits" to "claude_code.commit.count", "pull_requests" to "claude_code.pull_request.count")
    val topRatioMetrics = setOf("automation_ratio","integration_depth","command_prompt_ratio","tool_failure_rate",
        "api_retry_attempts","auto_approval_ratio","api_error_rate","compaction_reduction","mcp_failure_ratio",
        "rubber_stamp_ratio","edit_acceptance_rate","cache_read_ratio","input_output_ratio")
    private val topCostRatioMetrics = setOf("cost_per_active_user","cost_per_user_hour","model_unit_price","subagent_cost_ratio")
    val topPeriodMetrics = topCostRatioMetrics + setOf("cost_anomaly","usage_concentration","onboarding_ttfu","abandoned_session_ratio","subagent_activity","adoption_rate","active_users","telemetry_coverage","prompts_per_session","read_tool_density","model_users","llm_duration_ms","turn_duration_ms","llm_ttft_ms","gate_wait_ms")
    val topGroupMetrics = pointMetrics.keys + topRatioMetrics + topPeriodMetrics + setOf("onboarding_retention","session_last_event","cost","tokens","refusals","hook_executions","tool_calls","rate_limit_events","tool_rejections",
        "usage_heatmap","compactions","mcp_connections","llm_stop_reasons","hook_blocking")
    val populationMetrics = setOf("active_users", "adoption_rate", "telemetry_coverage")
    val ratioMetrics = setOf("automation_ratio", "integration_depth", "command_prompt_ratio", "tool_failure_rate", "api_retry_attempts", "auto_approval_ratio", "api_error_rate", "compaction_reduction", "mcp_failure_ratio", "rubber_stamp_ratio", "edit_acceptance_rate", "cache_read_ratio", "input_output_ratio", "abandoned_session_ratio", "usage_concentration", "onboarding_retention", "subagent_cost_ratio", "cost_per_active_user", "cost_per_user_hour", "model_unit_price", "cost_anomaly", "contract_commitment_burn")
    val tokenTypes = listOf("input","output","cache_read","cache_create")
    val tokenRatios = setOf("cache_read_ratio","input_output_ratio")
    val durationMetrics = setOf("turn_duration_ms", "llm_duration_ms", "llm_ttft_ms", "gate_wait_ms", "onboarding_ttfu")
    val sessionMetrics = setOf("prompts_per_session", "read_tool_density")
    val intervals = linkedMapOf("1h" to "1 HOUR", "6h" to "6 HOUR", "1d" to "1 DAY", "1w" to "1 WEEK", "1M" to "1 MONTH")
    val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    fun boundary(at: Instant) = utc.format(if (at.nano == 0) at else at.plusSeconds(1).minusNanos(at.nano.toLong()))
    val activePoint = """signal='metric' AND product='claude_code'
        AND JSONExtractString(raw_json,'point','name')='claude_code.active_time.total'
        AND JSONExtractString(raw_json,'point','attrs','type')='user'
        AND JSONExtractInt(raw_json,'point','aggregation_temporality')!=2
        AND isNotNull(JSONExtract(raw_json,'point','value','Nullable(Float64)'))"""
    val person = "{members:Map(String,String)}[installation_id]"
    val known = "mapContains({members:Map(String,String)},installation_id)"
    val people = """if(countIf($activePoint)>0,
        arrayCount(value -> value>0,tupleElement(sumMapIf([$person],[JSONExtractFloat(raw_json,'point','value')],$known AND $activePoint),2)),
        uniqExactIf($person,$known))"""
    val base = """FROM enriched_events FINAL WHERE tenant_id={tenant:String}
        AND ts>={from:DateTime} AND ts<{to:DateTime}
        AND ({unrestricted:UInt8}=1 OR hasAny(team_ids_as_of,{teams:Array(String)}))
        AND (empty({products:Array(String)}) OR has({products:Array(String)},product))
        AND (empty({models:Array(String)}) OR has({models:Array(String)},
            coalesce(nullIf(JSONExtractString(raw_json,'payload','model'),''),JSONExtractString(raw_json,'point','attrs','model'))))
        AND (empty({premium_patterns:Array(String)}) OR
            (notEmpty(coalesce(nullIf(JSONExtractString(raw_json,'payload','model'),''),JSONExtractString(raw_json,'point','attrs','model')))
            AND arrayExists(pattern -> like(coalesce(nullIf(JSONExtractString(raw_json,'payload','model'),''),
                JSONExtractString(raw_json,'point','attrs','model')),pattern),{premium_patterns:Array(String)})))
        AND ({personal:UInt8}=0 OR mapContains({members:Map(String,String)},installation_id))"""
    /** 선택한 차원 조합을 유지하고 나머지는 원본 집계 전에 같은 그룹으로 묶는다.
     * retained 파라미터는 ClickHouse String 역이스케이프 후에도 JSON 이스케이프를 보존해야 한다. */
    fun groupDimensions(expressions: List<String>, retained: List<List<String>>?): List<String> =
        expressions.mapIndexed { index, expression ->
            val grouped = if (retained==null) expression else
                "if(has(JSONExtract({retained:String},'Array(Array(String))'),[${expressions.joinToString(",")}]), $expression, '__other__')"
            "$grouped AS g$index"
        }
    fun groupLimit(q: DashboardQueryItem): Int = q.limit ?: if (q.metricId=="usage_heatmap" &&
        q.groupBy.toSet()==setOf("weekday","hour")) 168 else 100
    fun dimension(dim: String): String = when (dim) {
        "team" -> "team"
        "product" -> "product"
        "platform" -> "{platforms:Map(String,String)}[installation_id]"
        "category" -> "coalesce(nullIf(JSONExtractString(raw_json,'payload','refusal_category'),''),'unspecified')"
        "language" -> "JSONExtractString(raw_json,'point','attrs','language')"
        "tool_name" -> "coalesce(nullIf(JSONExtractString(raw_json,'payload','tool_name'),''),JSONExtractString(raw_json,'point','attrs','tool_name'))"
        "tool_kind", "action", "error_type", "mcp_server", "decided_by", "stop_reason", "decision" -> "JSONExtractString(raw_json,'payload','$dim')"
        "hook_event", "trigger", "server_name", "server_scope", "transport_type", "is_plugin" -> "JSONExtractString(raw_json,'payload','attrs','$dim')"
        "weekday" -> "toString(toDayOfWeek(ts,0,{zone:String}))"
        "status_code" -> "toString(coalesce(JSONExtract(raw_json,'payload','status_code','Nullable(Int64)'),0))"
        "hour" -> "toString(toHour(ts,{zone:String}))"
        "model" -> "coalesce(nullIf(JSONExtractString(raw_json,'payload','model'),''),JSONExtractString(raw_json,'point','attrs','model'))"
        "start_type", "type" -> "JSONExtractString(raw_json,'point','attrs','$dim')"
        else -> throw IllegalArgumentException("group_by_not_allowed")
    }
    fun remaining(deadline: Long) = Duration.ofNanos(deadline-System.nanoTime())
    /** `*`만 와일드카드로 허용하고 SQL LIKE의 나머지 특수 문자는 이스케이프한다. */
    fun sqlPattern(pattern: String): String = pattern.replace("\\","\\\\").replace("%","\\%").replace("_","\\_").replace("*","%")
    fun quoted(value: String) = "'"+value.replace("\\", "\\\\").replace("'", "\\'")+"'"
    fun array(values: Collection<String>) = values.joinToString(",", "[", "]", transform = ::quoted)
}
