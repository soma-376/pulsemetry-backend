package com.team376.pulsemetry.dashboard.query

import com.team376.pulsemetry.dashboard.catalog.DashboardMetricCatalog
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.activePoint
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.array
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.base
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.boundary
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.dimension
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.durationMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupDimensions
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupLimit
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.intervals
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.known
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.people
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.person
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.pointMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.populationMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.ratioMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.remaining
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.sessionMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.tokenRatios
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.topGroupMetrics
import com.team376.pulsemetry.persistence.telemetry.DashboardClickHouseReader
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant

// 본문은 원본 들여쓰기를 그대로 둔다 — SQL 리터럴이 executed_sql 로 응답에 실리므로 공백 한 칸도 바꾸지 않는다.
/**
 * ClickHouse 조회. 지표 id 로 리더를 고르고, 특화 리더가 없으면 범용 SELECT 를 만든다.
 * 특화 리더는 주제별 파일(`DashboardQueryCostReaders` · `…PopulationReaders` · `…SessionReaders`)의 확장 함수다 —
 * 그래서 의존 필드가 `internal` 이다.
 */
internal class DashboardQueryReaders(internal val reader: DashboardClickHouseReader, internal val mapper: ObjectMapper,
    internal val jdbc: JdbcClient, internal val catalog: DashboardMetricCatalog, internal val clock: Clock) {
    fun read(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant, zone: String, interval: String, deadline: Long,
        retained: List<List<String>>? = null): Rows {
        if (q.metricId=="contract_commitment_burn") return readCommitment(q,scope,from,to,zone,interval,deadline)
        if (q.metricId=="cost_anomaly") return readAnomaly(q,scope,from,to,zone,deadline,retained)
        if (q.metricId in setOf("cost","subagent_cost_ratio","cost_per_active_user","cost_per_user_hour","model_unit_price")) return readCost(q,scope,from,to,zone,interval,deadline,retained=retained)
        if (q.metricId=="vendor_account_mismatch") return readMismatch(q,scope,from,to,zone,interval,deadline)
        if (q.metricId=="onboarding_retention") return readRetention(q,scope,from,to,zone,deadline,retained)
        if (q.metricId=="onboarding_ttfu") return readOnboarding(q,scope,from,to,zone,interval,deadline,retained)
        if (q.metricId=="usage_concentration") return readConcentration(q,scope,from,to,zone,interval,deadline,retained)
        if (q.metricId=="session_last_event") return readLastEvent(q,scope,from,to,zone,interval,deadline,retained)
        if (q.metricId=="abandoned_session_ratio") return readAbandoned(q,scope,from,to,zone,interval,deadline,retained)
        if (q.metricId=="tokens") return readTokens(q,scope,from,to,zone,interval,deadline,retained)
        if (q.metricId=="hook_executions") return readHookExecutions(q,scope,from,to,zone,interval,deadline,retained)
        if (q.metricId in durationMetrics) return readDuration(q, scope, from, to, zone, interval, deadline,retained)
        if (q.metricId in sessionMetrics) return readSessionDistribution(q, scope, from, to, zone, interval, deadline,retained)
        if (q.metricId in populationMetrics) return readPopulation(q, scope, from, to, zone, interval, deadline,retained)
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val groupNames = q.groupBy.indices.map { "g$it" }
        val bucket = if (frameType == "timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val name = "JSONExtractString(raw_json,'point','name')"
        val attrType = "JSONExtractString(raw_json,'point','attrs','type')"
        val success = "JSONExtract(raw_json,'payload','success','Nullable(Bool)')"
        val tool = "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='tool_call'"
        val llm = "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='llm_call'"
        val attempt = "JSONExtract(raw_json,'payload','attempt','Nullable(Int64)')"
        val before = "JSONExtract(raw_json,'payload','tokens_before','Nullable(Int64)')"
        val after = "JSONExtract(raw_json,'payload','tokens_after','Nullable(Int64)')"
        val mcp = "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='lifecycle' AND JSONExtractString(raw_json,'payload','kind')='mcp_connection'"
        val blocked = "JSONExtract(raw_json,'payload','blocked_on_user_ms','Nullable(Float64)')"
        val editDecision = "JSONExtractString(raw_json,'point','attrs','decision')"
        val blocking = "toInt64OrNull(JSONExtractString(raw_json,'payload','attrs','num_blocking'))"
        val agentId = "JSONExtractString(raw_json,'payload','agent_id')"
        fun tokenValue(type: String) = "JSONExtract(raw_json,'payload','tokens','$type','Nullable(Float64)')"
        val inputTokens = tokenValue("input")
        val outputTokens = tokenValue("output")
        val cacheRead = tokenValue("cache_read")
        val cacheCreate = tokenValue("cache_create")
        val requiredTokens = if (q.metricId=="cache_read_ratio") listOf(inputTokens,cacheRead,cacheCreate) else listOf(inputTokens,outputTokens)
        val completeTokens = requiredTokens.joinToString(" AND ") { "isNotNull($it) AND $it>=0" }
        val metric = when (q.metricId) {
            "cache_read_ratio", "input_output_ratio" -> llm
            "model_users" -> "signal IN ('log','span','metric') AND ${dimension("model")}!=''"
            "refusals" -> "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='llm_response' AND JSONExtractString(raw_json,'payload','stop_reason')='refusal'"
            "hook_blocking" -> "signal='span' AND JSONExtractString(raw_json,'type')='hook'"
            "subagent_activity" -> tool
            "edit_acceptance_rate" -> "signal='metric' AND product='claude_code' AND $name='claude_code.code_edit_tool.decision' AND JSONExtractString(raw_json,'point','attrs','source') IN ('user_temporary','user_permanent','user_reject','user_abort')" +
                if(q.params.containsKey("language")) " AND JSONExtractString(raw_json,'point','attrs','language')={language:String}" else ""
            "rubber_stamp_ratio" -> "signal='span' AND JSONExtractString(raw_json,'type')='tool_gate' AND JSONExtractString(raw_json,'payload','decided_by')='user' AND isNotNull($blocked) AND $blocked>=0"
            "llm_stop_reasons" -> "signal IN ('log','span') AND JSONExtractString(raw_json,'type') IN ('llm_call','llm_response')"
            "mcp_connections", "mcp_failure_ratio" -> mcp + if (q.params.containsKey("server_scope"))
                " AND JSONExtractString(raw_json,'payload','attrs','server_scope')={server_scope:String}" else ""
            "compactions", "compaction_reduction" -> "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='lifecycle' AND JSONExtractString(raw_json,'payload','kind')='compaction'"
            "usage_heatmap" -> "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='user_prompt'"
            "auto_approval_ratio", "tool_rejections" -> "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='tool_decision'" +
                if(q.metricId=="tool_rejections") " AND (empty({decided_by:Array(String)}) OR has({decided_by:Array(String)},JSONExtractString(raw_json,'payload','decided_by')))" else ""
            "api_retry_attempts", "rate_limit_events", "api_error_rate" -> llm
            "tool_calls" -> tool + if (q.params.containsKey("success")) " AND $success={success:Bool}" else ""
            "tool_failure_rate" -> tool
            "automation_ratio" -> "signal='metric' AND product='claude_code' AND $name='claude_code.active_time.total' AND $attrType IN ('user','cli')"
            "integration_depth" -> "signal='metric' AND product='claude_code' AND ($name IN ('claude_code.commit.count','claude_code.pull_request.count') OR ($name='claude_code.session.count' AND JSONExtractString(raw_json,'point','attrs','start_type')='fresh'))"
            "command_prompt_ratio" -> "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='user_prompt' AND JSONExtractString(raw_json,'envelope','session_id') NOT IN ('','(unknown)')"
            else -> "signal='metric' AND product='claude_code' AND $name={metric:String}"
        }
        val event = q.metricId in setOf("command_prompt_ratio","tool_calls","tool_failure_rate","api_retry_attempts","rate_limit_events","auto_approval_ratio","tool_rejections","api_error_rate","usage_heatmap","compactions","compaction_reduction","mcp_connections","mcp_failure_ratio","llm_stop_reasons","rubber_stamp_ratio","subagent_activity","hook_blocking","refusals")
        val valid = if (q.metricId in tokenRatios) "$metric AND $completeTokens" else if (q.metricId=="model_users") "$metric AND (signal!='metric' OR JSONExtractInt(raw_json,'point','aggregation_temporality')!=2)" else if (q.metricId=="hook_blocking") "$metric AND isNotNull($blocking) AND $blocking>=0" else if (q.metricId=="compaction_reduction") "$metric AND isNotNull($before) AND isNotNull($after)" else if (event) metric else "$metric AND JSONExtractInt(raw_json,'point','aggregation_temporality')!=2 AND isNotNull(JSONExtract(raw_json,'point','value','Nullable(Float64)'))"
        val observed = if (q.metricId in tokenRatios) metric else if (q.metricId=="tool_calls") tool else if (q.metricId=="compaction_reduction") metric else valid
        val cumulative = if (event || q.metricId in tokenRatios) "0" else "countIf($metric AND JSONExtractInt(raw_json,'point','aggregation_temporality')=2)"
        val pointValue = "JSONExtract(raw_json,'point','value','Nullable(Float64)')"
        val numerator = when (q.metricId) {
            "cache_read_ratio" -> "sumIf($cacheRead,$valid)"
            "input_output_ratio" -> "sumIf($inputTokens,$valid)"
            "model_users" -> "uniqExactIf($person,$valid AND $known)"
            "refusals" -> "countIf($valid)"
            "hook_blocking" -> "sumIf($blocking,$valid)"
            "edit_acceptance_rate" -> "sumIf($pointValue,$valid AND $editDecision='accept')"
            "rubber_stamp_ratio" -> "countIf($valid AND JSONExtractString(raw_json,'payload','decision')='accept' AND $blocked<{threshold:UInt32})"
            "compactions", "mcp_connections", "llm_stop_reasons" -> "countIf($valid)"
            "mcp_failure_ratio" -> "countIf($valid AND JSONExtractString(raw_json,'payload','attrs','status')!='connected')"
            "compaction_reduction" -> "sumIf($before-$after,$valid)"
            "api_error_rate" -> "countIf($valid AND JSONExtractString(raw_json,'payload','error_type')!='')"
            "usage_heatmap" -> "countIf($valid)"
            "auto_approval_ratio" -> "countIf($valid AND JSONExtractString(raw_json,'payload','decided_by') IN ('config','hook'))"
            "tool_rejections" -> "countIf($valid AND JSONExtractString(raw_json,'payload','decision')='reject')"
            "api_retry_attempts" -> "countIf($valid AND $attempt>=2)"
            "rate_limit_events" -> "countIf($valid AND JSONExtract(raw_json,'payload','status_code','Nullable(Int64)')=429)"
            "tool_calls" -> "countIf($valid)"
            "tool_failure_rate" -> "countIf($valid AND $success=false)"
            "automation_ratio" -> "sumIf($pointValue,$valid AND $attrType='cli')"
            "integration_depth" -> "sumIf($pointValue,$valid AND $name IN ('claude_code.commit.count','claude_code.pull_request.count'))"
            "command_prompt_ratio" -> "countIf($valid AND JSONExtractString(raw_json,'payload','command_name')!='' AND (empty({command_names:Array(String)}) OR has({command_names:Array(String)},JSONExtractString(raw_json,'payload','command_name'))))"
            else -> "sumIf($pointValue,$valid)"
        }
        val denominator = when (q.metricId) {
            "cache_read_ratio" -> "sumIf($inputTokens+$cacheRead+$cacheCreate,$valid)"
            "input_output_ratio" -> "sumIf($outputTokens,$valid)"
            "edit_acceptance_rate" -> "sumIf($pointValue,$valid AND $editDecision IN ('accept','reject'))"
            "rubber_stamp_ratio" -> "countIf($valid AND JSONExtractString(raw_json,'payload','decision')='accept')"
            "compaction_reduction" -> "sumIf($before,$valid)"
            "tool_failure_rate" -> "countIf($valid AND isNotNull($success))"
            "automation_ratio" -> "sumIf($pointValue,$valid)"
            "integration_depth" -> "sumIf($pointValue,$valid AND $name='claude_code.session.count')"
            else -> "countIf($valid)"
        }
        val result = if (q.metricId=="subagent_activity")
            "uniqExactIf($agentId,$valid AND $agentId!='') AS value, uniqExactIf(tuple(ts,event_id,installation_id,product,signal),$valid AND $agentId!='') AS numerator, uniqExactIf(tuple(ts,event_id,installation_id,product,signal),$valid) AS denominator, numerator/nullIf(denominator,0) AS ratio"
            else if (q.metricId in setOf("compaction_reduction","usage_concentration") || q.metricId in tokenRatios)
            "if(countIf($valid)=0,NULL,$numerator) AS numerator, if(countIf($valid)=0,NULL,$denominator) AS denominator, numerator/nullIf(denominator,0) AS value"
            else if (q.metricId in ratioMetrics)
            "coalesce($numerator,0) AS numerator, coalesce($denominator,0) AS denominator, numerator/nullIf(denominator,0) AS value"
            else "$numerator AS value"
        // 언어 선택으로 분모가 좁아지면 선택 언어의 유효 관측 인원으로 마스킹한다.
        val selectedPeople = if(q.metricId in setOf("refusals","subagent_activity") || (q.metricId=="edit_acceptance_rate" && q.params.containsKey("language")))
            "uniqExactIf($person,$known AND $valid)" else people
        val sql = """SELECT $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
            $selectedPeople AS people, countIf($activePoint)>0 AS active_time_definition,
            countIf($observed) AS points, $cumulative AS cumulative,
            $result,
            ${if (q.metricId=="api_retry_attempts") "countIf($llm AND isNull($attempt))" else "0"} AS unknown_attempts,
            ${if (q.metricId=="compaction_reduction") "countIf($metric AND (isNull($before) OR isNull($after)))" else "0"} AS incomplete_compactions
            FROM (SELECT * $base)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
            GROUP BY bucket${if (groupNames.isEmpty()) "" else ","+groupNames.joinToString(",")}
            ORDER BY ${if (groupNames.isEmpty()) "" else groupNames.joinToString(",")+","}bucket"""
        val parameters = scope.parameters + mapOf("from" to boundary(from), "to" to boundary(to), "zone" to zone, "metric" to pointMetrics[q.metricId].orEmpty(),
            "threshold" to (q.params["threshold_ms"]?.asInt() ?: 2000).toString(),
            "language" to (q.params["language"]?.asString() ?: ""),
            "decided_by" to array(q.params["decided_by"]?.toList()?.map { it.asString() }?.toSet().orEmpty()),
            "command_names" to array(q.params["command_names"]?.toList()?.map { it.asString() }?.toSet().orEmpty()),
            "server_scope" to (q.params["server_scope"]?.asString() ?: ""),
            "success" to if (q.params["success"]?.asBoolean()==true) "1" else "0",
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql, parameters, remaining(deadline)))["data"].toList().filter { it["points"].asLong()>0 || it["cumulative"].asLong()>0 }
        if (q.metricId !in topGroupMetrics && rows.map { row -> q.groupBy.indices.map { row["g$it"].asString() } }.distinct().size > groupLimit(q))
            throw DashboardReadException("query_too_wide", 422)
        return Rows(rows, sql)
    }
}
