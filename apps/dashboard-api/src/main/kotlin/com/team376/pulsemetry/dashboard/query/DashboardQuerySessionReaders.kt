package com.team376.pulsemetry.dashboard.query

import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.activePoint
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.array
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.base
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.boundary
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.dimension
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupDimensions
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupLimit
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.intervals
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.known
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.people
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.person
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.remaining
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.tokenTypes
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.topGroupMetrics
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import java.time.Instant

// 본문은 원본 들여쓰기를 그대로 둔다 — SQL 리터럴이 executed_sql 로 응답에 실리므로 공백 한 칸도 바꾸지 않는다.
/** 세션 리더 — 마지막 이벤트 · 이탈 · 토큰 · 훅 · 지연 · 세션 분포. */
    internal fun DashboardQueryReaders.readLastEvent(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val suffix = if (dimensions.isEmpty()) "" else ","+q.groupBy.indices.joinToString(",") { "g$it" }
        val category = "g${q.groupBy.size}"
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        // 마지막 유형과 오류를 같은 행에서 고른다. 시각·sequence 동률만 event_id로 안정화한다.
        val sql = """WITH observed AS (
            SELECT *${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
                JSONExtractString(raw_json,'envelope','session_id') AS session_id
            FROM (SELECT * $base)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
        ), privacy AS (
            SELECT $bucket AS bucket$suffix, $people AS people,
                countIf($activePoint)>0 AS active_time_definition FROM observed GROUP BY bucket$suffix
        ), sessions AS (
            SELECT installation_id,product,session_id$suffix, max(ts) AS last_ts,
                argMax(tuple(JSONExtractString(raw_json,'type'),JSONExtractString(raw_json,'payload','error_type')),
                    tuple(ts,JSONExtractInt(raw_json,'sequence'),event_id)) AS last_event
            FROM observed WHERE signal='log' AND session_id NOT IN ('','(unknown)')
            GROUP BY installation_id,product,session_id$suffix
        ), stats AS (
            SELECT ${bucket.replace("ts", "last_ts")} AS bucket$suffix,
                if(tupleElement(last_event,2)!='','api_error',tupleElement(last_event,1)) AS $category,
                count() AS points, count() AS value, 0 AS cumulative,
                uniqExactIf($person,$known) AS category_people
            FROM sessions GROUP BY bucket$suffix,$category
        ) SELECT stats.*,least(privacy.people,stats.category_people) AS people,privacy.active_time_definition
            FROM stats INNER JOIN privacy USING (bucket$suffix) ORDER BY bucket$suffix,$category"""
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
        return Rows(rows,sql)
    }

    internal fun DashboardQueryReaders.readAbandoned(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val groupNames = q.groupBy.indices.map { "g$it" }
        val suffix = if (groupNames.isEmpty()) "" else ","+groupNames.joinToString(",")
        val groups = "bucket$suffix"
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val name = "JSONExtractString(raw_json,'point','name')"
        val output = "signal='metric' AND product='claude_code' AND (($name='claude_code.code_edit_tool.decision' AND JSONExtractString(raw_json,'point','attrs','decision')='accept') OR $name IN ('claude_code.lines_of_code.count','claude_code.commit.count','claude_code.pull_request.count'))"
        val cumulative = "JSONExtractInt(raw_json,'point','aggregation_temporality')=2"
        // 산출은 기간 전체의 같은 설치·제품·세션과 연결하고 마지막 로그 시각의 버킷에 한 번 배치한다.
        val sql = """WITH observed AS (
            SELECT DISTINCT ts,event_id,installation_id,product,signal,raw_json${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
                JSONExtractString(raw_json,'envelope','session_id') AS session_id
            FROM (SELECT * $base)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
        ), privacy AS (
            SELECT $bucket AS bucket$suffix, $people AS people,
                countIf($activePoint)>0 AS active_time_definition FROM observed GROUP BY $groups
        ), sessions AS (
            SELECT installation_id,product,session_id$suffix, maxIf(ts,signal='log') AS ts,
                countIf(signal='log') AS logs,
                sumIf(JSONExtract(raw_json,'point','value','Nullable(Float64)'),$output AND NOT ($cumulative)) AS output_value,
                countIf($output AND $cumulative) AS cumulative_points
            FROM observed WHERE session_id NOT IN ('','(unknown)')
            GROUP BY installation_id,product,session_id$suffix
        ), stats AS (
            SELECT $bucket AS bucket$suffix, count() AS points, countIf(coalesce(output_value,0)<=0) AS numerator,
                count() AS denominator, numerator/denominator AS value, sum(cumulative_points) AS cumulative,
                uniqExactIf($person,$known) AS session_people
            FROM sessions WHERE logs>0 GROUP BY $groups
        ) SELECT stats.*,least(privacy.people,stats.session_people) AS people,privacy.active_time_definition
            FROM stats INNER JOIN privacy USING ($groups) ORDER BY $groups"""
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
        return Rows(rows,sql)
    }

    internal fun DashboardQueryReaders.readTokens(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val metrics = q.source=="metrics"
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val dimensions = groupDimensions(q.groupBy.map { dim ->
            when (dim) {
                "type" -> "token_kind"
                "query_source" -> if (metrics) "JSONExtractString(raw_json,'point','attrs','query_source')" else "JSONExtractString(raw_json,'payload','source')"
                "agent_name" -> "JSONExtractString(raw_json,'point','attrs','agent.name')"
                else -> dimension(dim)
            }
        },retained)
        val groups = (listOf("bucket")+q.groupBy.indices.map { "g$it" }).joinToString(",")
        val kind = "JSONExtractString(raw_json,'point','attrs','type')"
        val expanded = if (metrics) """SELECT *,
            transform($kind,['input','output','cacheRead','cacheCreation'],['input','output','cache_read','cache_create'],'') AS token_kind,
            JSONExtract(raw_json,'point','value','Nullable(Float64)') AS token_value
            $base""" else """SELECT *, tupleElement(token_entry,1) AS token_kind, tupleElement(token_entry,2) AS token_value
            FROM (SELECT * $base) ARRAY JOIN [${tokenTypes.joinToString(",") { "('$it',JSONExtract(raw_json,'payload','tokens','$it','Nullable(Float64)'))" }}] AS token_entry"""
        val source = if (metrics) "signal='metric' AND product='claude_code' AND JSONExtractString(raw_json,'point','name')='claude_code.token.usage'"
            else "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='llm_call'"
        val selected = "$source AND has({token_types:Array(String)},token_kind)"
        val observed = if (metrics) "$selected AND JSONExtractInt(raw_json,'point','aggregation_temporality')!=2" else selected
        val valid = "$observed AND isNotNull(token_value) AND token_value>=0"
        val sql = """SELECT $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
            $people AS people, countIf($activePoint)>0 AS active_time_definition,
            countIf($observed) AS points,
            ${if (metrics) "countIf($selected AND JSONExtractInt(raw_json,'point','aggregation_temporality')=2)" else "0"} AS cumulative,
            sumOrNullIf(token_value,$valid) AS value
            FROM ($expanded)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
            GROUP BY $groups ORDER BY $groups"""
        val types = q.params["types"]?.toList()?.map { it.asString() }?.takeIf { it.isNotEmpty() } ?: tokenTypes
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,"token_types" to array(types),
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
            .filter { it["points"].asLong()>0 || it["cumulative"].asLong()>0 }
        return Rows(rows,sql)
    }

    internal fun DashboardQueryReaders.readHookExecutions(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val groupSql = (listOf("bucket")+q.groupBy.indices.map { "g$it" }).joinToString(",")
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        // 훅 종류를 나누기 전의 전체 관측 세션을 분모로 사용한다.
        val sql = """WITH observed AS (
            SELECT *, $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
                JSONExtractString(raw_json,'envelope','session_id') AS session_id,
                tuple(installation_id,product,session_id) AS session_key,
                session_id NOT IN ('','(unknown)') AS valid_session,
                signal='span' AND JSONExtractString(raw_json,'type')='hook' AS is_hook
            FROM (SELECT * $base)
        ), totals AS (
            SELECT bucket, uniqExactIf(session_key,valid_session) AS denominator
            FROM observed GROUP BY bucket
        ), stats AS (
            SELECT $groupSql, uniqExactIf($person,$known AND is_hook) AS people, countIf($activePoint)>0 AS active_time_definition,
                countIf(is_hook) AS points, countIf(is_hook) AS value,
                uniqExactIf(session_key,valid_session AND is_hook) AS numerator
            FROM observed GROUP BY $groupSql
        ) SELECT stats.*, totals.denominator, numerator/nullIf(denominator,0) AS ratio, 0 AS cumulative
            FROM stats INNER JOIN totals USING (bucket) ORDER BY $groupSql"""
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
            .filter { it["points"].asLong()>0 }
        return Rows(rows,sql)
    }

    internal fun DashboardQueryReaders.readDuration(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val groups = listOf("bucket") + q.groupBy.indices.map { "g$it" }
        val groupSql = groups.joinToString(",")
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val gate = q.metricId=="gate_wait_ms"
        val turn = q.metricId=="turn_duration_ms"
        val ttft = q.metricId=="llm_ttft_ms"
        // 턴 attrs는 정규화 문자열이고 LLM payload는 숫자다. 누락을 0으로 바꾸지 않는다.
        val value = if (turn) "toFloat64OrNull(JSONExtractString(raw_json,'payload','attrs','duration_ms'))"
            else "JSONExtract(raw_json,'payload','${if (ttft) "ttft_ms" else if (gate) "blocked_on_user_ms" else "duration_ms"}','Nullable(Float64)')"
        val logValid = "signal='log' AND JSONExtractString(raw_json,'type')='llm_call' AND JSONExtractString(raw_json,'payload','error_type')='' AND isNotNull($value) AND $value>=0"
        // 시간 버킷을 나누기 전에 소스를 선택하므로 같은 요청의 로그와 스팬이 버킷 경계에서 중복되지 않는다.
        val requestKey = "if(JSONExtractString(raw_json,'payload','request_id')!='',concat('request:',JSONExtractString(raw_json,'payload','request_id')),concat('session:',toJSONString(tuple(JSONExtractString(raw_json,'envelope','session_id'),${dimension("model")}))))"
        val input = if (ttft) "SELECT *, countIf($logValid) OVER (PARTITION BY installation_id,product,$requestKey) AS preferred_logs $base"
            else "SELECT * $base"
        val source = if (ttft) "(($logValid) OR (preferred_logs=0 AND signal='span' AND JSONExtractString(raw_json,'type')='llm_request' AND JSONExtractString(raw_json,'payload','error_type')=''))"
            else if (gate) "signal='span' AND JSONExtractString(raw_json,'type')='tool_gate'"
            else if (turn) "signal='span' AND JSONExtractString(raw_json,'type')='turn'"
            else "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='llm_call' AND JSONExtractString(raw_json,'payload','error_type')=''"
        val valid = "$source AND isNotNull($value) AND $value>=0"
        val sql = """SELECT $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
            $people AS people, countIf($activePoint)>0 AS active_time_definition,
            countIf($valid) AS points, 0 AS cumulative,
            quantileExactIf(0.5)($value,$valid) AS p50, quantileExactIf(0.9)($value,$valid) AS p90,
            quantileExactIf(0.95)($value,$valid) AS p95, quantileExactIf(0.99)($value,$valid) AS p99,
            p50 AS value FROM ($input)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
            GROUP BY $groupSql ORDER BY $groupSql"""
        val parameters = scope.parameters + mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
            .filter { it["points"].asLong()>0 }
        if (q.metricId !in topGroupMetrics && rows.map { row -> q.groupBy.indices.map { row["g$it"].asString() } }.distinct().size>groupLimit(q))
            throw DashboardReadException("query_too_wide",422)
        return Rows(rows,sql)
    }

    internal fun DashboardQueryReaders.readSessionDistribution(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val groups = listOf("bucket") + q.groupBy.indices.map { "g$it" }
        val groupSql = groups.joinToString(",")
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val eventType = if (q.metricId=="read_tool_density") "tool_call" else "user_prompt"
        val count = if (q.metricId=="read_tool_density") "uniqExactIf(tuple(ts,event_id,signal),JSONExtractString(raw_json,'payload','action') IN ('read','search','fetch'))" else "uniqExact(tuple(ts,event_id,signal))"
        // 설치·제품별 세션을 구분한다. 읽기 밀도는 읽기 0회인 도구 호출 세션도 포함한다.
        val sql = """WITH observed AS (
            SELECT *, $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")}
            FROM (SELECT * $base)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
        ), privacy AS (
            SELECT $groupSql, least($people,uniqExactIf($person,$known AND signal IN ('log','span')
                AND JSONExtractString(raw_json,'type')={event_type:String}
                AND JSONExtractString(raw_json,'envelope','session_id') NOT IN ('','(unknown)'))) AS people,
                countIf($activePoint)>0 AS active_time_definition
            FROM observed GROUP BY $groupSql
        ), sessions AS (
            SELECT $groupSql, installation_id, product, JSONExtractString(raw_json,'envelope','session_id') AS session_id,
                $count AS prompts FROM observed
            WHERE signal IN ('log','span') AND JSONExtractString(raw_json,'type')={event_type:String}
                AND session_id NOT IN ('','(unknown)')
            GROUP BY $groupSql,installation_id,product,session_id
        ), stats AS (
            SELECT $groupSql, count() AS points, quantileExact(0.5)(prompts) AS p50, quantileExact(0.9)(prompts) AS p90,
                countIf(prompts=1) AS b1, countIf(prompts BETWEEN 2 AND 3) AS b2,
                countIf(prompts BETWEEN 4 AND 7) AS b3, countIf(prompts BETWEEN 8 AND 15) AS b4,
                countIf(prompts>=16) AS b5
            FROM sessions GROUP BY $groupSql
        ) SELECT stats.*, privacy.people, privacy.active_time_definition, 0 AS cumulative, p50 AS value
            FROM stats INNER JOIN privacy USING ($groupSql) ORDER BY $groupSql"""
        val parameters = scope.parameters + mapOf("from" to boundary(from), "to" to boundary(to), "zone" to zone, "event_type" to eventType,
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
        return Rows(rows,sql)
    }
