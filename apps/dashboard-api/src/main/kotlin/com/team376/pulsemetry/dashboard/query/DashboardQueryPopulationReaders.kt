package com.team376.pulsemetry.dashboard.query

import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.activePoint
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.base
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.boundary
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.dimension
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupDimensions
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.intervals
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.known
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.people
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.person
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.remaining
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.tokenTypes
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.utc
import java.time.Instant

// 본문은 원본 들여쓰기를 그대로 둔다 — SQL 리터럴이 executed_sql 로 응답에 실리므로 공백 한 칸도 바꾸지 않는다.
/** 인구 · 온보딩 리더 — 계정 불일치 · 리텐션 · 온보딩 · 집중도 · 활성 인구. */
    internal fun DashboardQueryReaders.readMismatch(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val sql = """WITH observed AS (
            SELECT *,$bucket AS bucket,JSONExtractString(raw_json,'envelope','identity','vendor_email') AS vendor_email
            FROM (SELECT * $base)
        ), privacy AS (
            SELECT bucket,$people AS people,countIf($activePoint)>0 AS active_time_definition
            FROM observed GROUP BY bucket
        ), latest AS (
            SELECT bucket,installation_id,argMax(vendor_email,
                tuple(ts,JSONExtractInt(raw_json,'sequence'),event_id)) AS latest_email
            FROM observed WHERE signal IN ('log','span') AND vendor_email!='' AND $known
                AND {emails:Map(String,String)}[installation_id]!=''
            GROUP BY bucket,installation_id
        ), stats AS (
            SELECT bucket,count() AS points,0 AS cumulative,uniqExact($person) AS observed_people,
                countIf(lowerUTF8(latest_email)!=lowerUTF8({emails:Map(String,String)}[installation_id])) AS value
            FROM latest GROUP BY bucket
        ) ${if(frameType=="table") """SELECT latest.*,{emails:Map(String,String)}[installation_id] AS registered_email,
            lowerUTF8(latest_email)!=lowerUTF8(registered_email) AS mismatch,
            least(privacy.people,stats.observed_people) AS people
            FROM latest INNER JOIN stats USING (bucket) INNER JOIN privacy USING (bucket) ORDER BY installation_id"""
            else "SELECT stats.*,least(privacy.people,stats.observed_people) AS people,privacy.active_time_definition FROM stats INNER JOIN privacy USING (bucket) ORDER BY bucket"}"""
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone)
        return Rows(mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList(),sql)
    }

    internal fun DashboardQueryReaders.readRetention(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val suffix = if (dimensions.isEmpty()) "" else ","+q.groupBy.indices.joinToString(",") { "g$it" }
        val cohortKey = "g${q.groupBy.size}"
        val weekKey = "g${q.groupBy.size+1}"
        val history = base.replace("ts>={from:DateTime} AND ","")
        val sql = """WITH observed AS (
            SELECT *${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")}
            FROM (SELECT * $history)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
        ), installations AS (
            SELECT installation_id$suffix,min(ts) AS first_at,
                groupUniqArray(toStartOfWeek(ts,1,{zone:String})) AS active_weeks
            FROM observed WHERE $known AND ts<{observed_to:DateTime} GROUP BY installation_id$suffix
        ), cohort AS (
            SELECT *,toStartOfWeek(first_at,1,{zone:String}) AS cohort_start FROM installations
            WHERE first_at>={from:DateTime}
        ) SELECT toUnixTimestamp(addWeeks(cohort_start,toInt32(week_number)))*1000 AS bucket$suffix,
            toString(dateDiff('week',toStartOfWeek({from:DateTime},1,{zone:String}),cohort_start)) AS $cohortKey,
            toString(week_number) AS $weekKey,toString(cohort_start) AS cohort_week,
            toDateTime(addWeeks(cohort_start,toInt32(week_number)+1),{zone:String})<={cutoff:DateTime} AS complete,
            count() AS points,count() AS denominator,
            if(complete,countIf(has(active_weeks,addWeeks(cohort_start,toInt32(week_number)))),NULL) AS numerator,
            numerator/denominator AS value,uniqExact($person) AS people,
            0 AS cumulative,0 AS active_time_definition
            FROM cohort ARRAY JOIN range(toUInt32(dateDiff('week',cohort_start,
                toStartOfWeek({to:DateTime}-INTERVAL 1 SECOND,1,{zone:String})))+1) AS week_number
            GROUP BY cohort_start,week_number$suffix ORDER BY cohort_start,week_number$suffix"""
        val cutoff = minOf(to,clock.instant())
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,
            "cutoff" to utc.format(cutoff),"observed_to" to boundary(cutoff),
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        return Rows(mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList(),sql)
    }

    internal fun DashboardQueryReaders.readOnboarding(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val suffix = if (dimensions.isEmpty()) "" else ","+q.groupBy.indices.joinToString(",") { "g$it" }
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        // 과거 사용을 신규 사용으로 오인하지 않도록 하한 이전의 보존 이력도 조회한다.
        val history = base.replace("ts>={from:DateTime} AND ","")
        val sql = """WITH observed AS (
            SELECT *${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")}
            FROM (SELECT * $history)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
        ), privacy AS (
            SELECT $bucket AS bucket$suffix,$people AS people,countIf($activePoint)>0 AS active_time_definition
            FROM observed WHERE ts>={from:DateTime} GROUP BY bucket$suffix
        ), first_events AS (
            SELECT installation_id$suffix,min(ts) AS first_at FROM observed
            WHERE $known GROUP BY installation_id$suffix
        ), cohort AS (
            SELECT installation_id$suffix,first_at AS ts,
                toInt64(toUnixTimestamp(first_at))-{created:Map(String,Int64)}[installation_id] AS seconds
            FROM first_events WHERE first_at>={from:DateTime} AND seconds>=0
        ), stats AS (
            SELECT $bucket AS bucket$suffix,count() AS points,0 AS cumulative,
                uniqExact($person) AS cohort_people,
                quantileExact(0.5)(seconds) AS p50,quantileExact(0.9)(seconds) AS p90,p50 AS value,
                countIf(seconds<3600) AS b1,countIf(seconds>=3600 AND seconds<86400) AS b2,
                countIf(seconds>=86400 AND seconds<604800) AS b3,
                countIf(seconds>=604800 AND seconds<2592000) AS b4,countIf(seconds>=2592000) AS b5
            FROM cohort GROUP BY bucket$suffix
        ) SELECT stats.*,least(privacy.people,stats.cohort_people) AS people,privacy.active_time_definition
            FROM stats INNER JOIN privacy USING (bucket$suffix) ORDER BY bucket$suffix"""
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        return Rows(mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList(),sql)
    }

    internal fun DashboardQueryReaders.readConcentration(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val suffix = if (dimensions.isEmpty()) "" else ","+q.groupBy.indices.joinToString(",") { "g$it" }
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val tokens = tokenTypes.map { "JSONExtract(raw_json,'payload','tokens','$it','Nullable(Float64)')" }
        val valid = tokens.joinToString(" AND ") { "isNotNull($it) AND $it>=0" }
        val sql = """WITH observed AS (
            SELECT DISTINCT ts,event_id,installation_id,product,signal,raw_json,$bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")}
            FROM (SELECT * $base)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
        ), privacy AS (
            SELECT bucket$suffix,$people AS people,countIf($activePoint)>0 AS active_time_definition
            FROM observed GROUP BY bucket$suffix
        ), persons AS (
            SELECT bucket$suffix,if($known,concat('member:',$person),concat('installation:',installation_id)) AS person_key,
                max(toUInt8($known)) AS mapped, countIf($valid) AS valid_calls,
                sumIf(${tokens.joinToString("+")},$valid) AS usage
            FROM observed WHERE signal IN ('log','span') AND JSONExtractString(raw_json,'type')='llm_call'
            GROUP BY bucket$suffix,person_key
        ), stats AS (
            SELECT bucket$suffix,sum(valid_calls) AS points,0 AS cumulative,
                countIf(mapped=1 AND valid_calls>0) AS valid_people,
                arraySort(groupArrayIf(usage,valid_calls>0)) AS ordered_usage,
                arraySum(ordered_usage) AS denominator,
                arraySum(arraySlice(arrayReverse(ordered_usage),1,greatest(1,toUInt32(ceil(length(ordered_usage)*0.1))))) AS numerator,
                if(denominator=0,NULL,numerator/denominator) AS value,
                arrayCumSum(ordered_usage) AS lorenz_cumulative
            FROM persons GROUP BY bucket$suffix
        ) SELECT stats.*,least(privacy.people,stats.valid_people) AS people,privacy.active_time_definition
            FROM stats INNER JOIN privacy USING (bucket$suffix) ORDER BY bucket$suffix"""
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        return Rows(mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList(),sql)
    }

    internal fun DashboardQueryReaders.readPopulation(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = groupDimensions(q.groupBy.map(::dimension),retained)
        val groups = q.groupBy.indices.map { "g$it" }
        val suffix = if (groups.isEmpty()) "" else ","+groups.joinToString(",")
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        // 설치 여러 개를 같은 사람으로 합친 뒤 활성 시간 합계로 활성 여부를 판정한다.
        val sql = """WITH observed AS (
            SELECT DISTINCT ts,event_id,installation_id,product,signal,raw_json${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")}
            FROM (SELECT * $base)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
        ) SELECT bucket$suffix, sum(observations) AS points, sum(cumulative_points) AS cumulative,
            sum(active_points)>0 AS active_time_definition,
            if(sum(active_points)>0,countIf(member_known AND active_value>0),countIf(member_known)) AS people,
            sum(installations) AS observed_installations
            FROM (SELECT $bucket AS bucket$suffix,
                $person AS member_key, $known AS member_known, count() AS observations,
                countIf($activePoint) AS active_points,
                countIf(signal='metric' AND product='claude_code'
                    AND JSONExtractString(raw_json,'point','name')='claude_code.active_time.total'
                    AND JSONExtractInt(raw_json,'point','aggregation_temporality')=2) AS cumulative_points,
                sumIf(JSONExtract(raw_json,'point','value','Nullable(Float64)'),$activePoint) AS active_value,
                uniqExactIf(installation_id,installation_id!='') AS installations
                FROM observed
                GROUP BY bucket$suffix,member_key,member_known)
            GROUP BY bucket$suffix ORDER BY bucket$suffix"""
        val params = scope.parameters + mapOf("from" to boundary(from), "to" to boundary(to), "zone" to zone,
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql,params,remaining(deadline)))["data"].toList().map { row ->
            val node = row as tools.jackson.databind.node.ObjectNode
            val numerator = if (q.metricId=="telemetry_coverage") row["observed_installations"].asDouble() else row["people"].asDouble()
            val denominator = if (q.metricId=="telemetry_coverage") scope.installations else
                q.groupBy.indexOf("team").takeIf { it>=0 }?.let { scope.teamMembers[row["g$it"].asString()]?.size ?: 0 } ?: scope.members
            if (q.metricId=="active_users") node.put("value",numerator) else {
                node.put("numerator",numerator)
                node.put("denominator",denominator)
                if (denominator==0) node.putNull("value") else node.put("value",numerator/denominator)
            }
            node
        }
        return Rows(rows,sql)
    }
