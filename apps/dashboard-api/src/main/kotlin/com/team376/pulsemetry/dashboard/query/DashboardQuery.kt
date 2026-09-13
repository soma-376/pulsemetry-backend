package com.team376.pulsemetry.dashboard.query

import com.team376.pulsemetry.dashboard.auth.DashboardAccess
import com.team376.pulsemetry.dashboard.catalog.DashboardMetricCatalog
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.array
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.base
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.boundary
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.durationMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupLimit
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.intervals
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.known
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.people
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.pointMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.populationMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.quoted
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.ratioMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.remaining
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.sessionMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.sqlPattern
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.tokenTypes
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.topGroupMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.topPeriodMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.topRatioMetrics
import com.team376.pulsemetry.dashboard.time.DashboardTime
import com.team376.pulsemetry.persistence.telemetry.DashboardClickHouseReader
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** SQL 조각은 고정식만 허용한다. 필터 값과 신원 대응표는 타입이 있는 파라미터로 전달한다. */
@RestController
@RequestMapping("/v1")
class DashboardQuery(private val catalog: DashboardMetricCatalog, private val access: DashboardAccess,
    private val reader: DashboardClickHouseReader, private val jdbc: JdbcClient,
    private val mapper: ObjectMapper, private val clock: Clock) {
    private val readers = DashboardQueryReaders(reader, mapper, jdbc, catalog, clock)
    private val frames = DashboardQueryFrames(mapper)

    @PostMapping("/query")
    fun query(@AuthenticationPrincipal user: UserIdentity, @RequestBody body: DashboardQueryRequest,
        @RequestHeader(value = "X-Audit-Reason", required = false) audit: String?,
        @RequestHeader(value = "Accept", defaultValue = "application/json") accept: String): ResponseEntity<*> =
        execute(user, body, audit, accept, null)

    /** 워커가 고정한 전후 현지 날짜 범위. 공개 QRY 파라미터를 확장하지 않는다. */
    internal fun compareScenario(user: UserIdentity, body: DashboardQueryRequest,
        before: Pair<Instant, Instant>): ResponseEntity<*> {
        require(body.compare == "none" && body.queries.all { it.frameType == "table" &&
            (it.groupBy.isEmpty() || (it.metricId=="llm_stop_reasons" && it.groupBy==listOf("stop_reason"))) })
        return execute(user, body, null, "application/json", before)
    }

    /** S1-2만 사용하는 모델 패턴 필터. 공개 QRY 필터 계약은 유지한다. */
    internal fun premiumScenario(user: UserIdentity, body: DashboardQueryRequest, patterns: List<String>): ResponseEntity<*> {
        require(patterns.size in 1..100 && patterns.all { it.length in 1..200 })
        require(body.compare=="none" && body.queries.all { it.metricId in setOf("cost","tokens") })
        return execute(user,body,null,"application/json",null,patterns)
    }

    /** 선택 기간에 처음 관측된 설치만 같은 시점까지 추적한다. */
    internal fun onboardingScenario(user: UserIdentity, body: DashboardQueryRequest,
        cohort: Pair<Instant,Instant>): ResponseEntity<*> {
        require(user.role=="owner" && body.compare=="none" && body.queries.all {
            it.metricId in setOf("onboarding_ttfu","onboarding_retention","active_time") && it.groupBy.isEmpty() })
        require(cohort.first<cohort.second && cohort.first==Instant.parse(body.from) && cohort.second<=Instant.parse(body.to))
        return execute(user,body,null,"application/json",null,cohort=cohort)
    }

    private fun execute(user: UserIdentity, body: DashboardQueryRequest, audit: String?, accept: String,
        before: Pair<Instant, Instant>?, premiumPatterns: List<String> = emptyList(), cohort: Pair<Instant,Instant>? = null): ResponseEntity<*> {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        require(body.queries.size in 1..12 && body.maxDataPoints in 1..1000)
        require(body.queries.map { it.refId }.distinct().size == body.queries.size)
        require(body.priceBasis in setOf("list", "contract"))
        val zone = body.tz ?: jdbc.sql("SELECT timezone FROM enrollment.tenants WHERE id=:tenant")
            .param("tenant", user.tenantId).query(String::class.java).single()
        val time = try { DashboardTime(clock.instant(), ZoneId.of(zone)) } catch (_: Exception) { throw IllegalArgumentException("invalid_timezone") }
        val from = time.resolve(body.from)
        val to = time.resolve(body.to)
        require(from < to)
        // 계약을 명시한 약정 소진율만 장기 조회를 허용한다. DB 시간·행 수·포인트 제한은 계속 적용한다.
        val selectedCommitments = body.queries.isNotEmpty() && body.queries.all {
            it.metricId=="contract_commitment_burn" && !it.params["contract_id"]?.asString().isNullOrBlank()
        }
        if (!selectedCommitments && Duration.between(from, to) > Duration.ofDays(366)) throw DashboardReadException("query_too_wide", 422)
        val comparison = before ?: time.compare(from, to, body.compare)
        if (before != null) {
            require(before.first < before.second && before.second == from)
            if (Duration.between(before.first, before.second) > Duration.ofDays(366))
                throw DashboardReadException("query_too_wide", 422)
        }
        // 권한·감사는 전체 요청에 선행한다. 쿼리 오류로 감춰서 다른 결과를 반환하지 않는다.
        body.queries.forEach { q ->
            require(q.refId.matches(Regex("[A-Z]")) && (q.limit==null || q.limit in 1..100))
            require(q.groupBy.size <= 2 && q.groupBy.distinct().size == q.groupBy.size)
            val definition = catalog.find(q.metricId) ?: throw IllegalArgumentException("invalid_metric")
            if (!definition.allowedGroupBy.containsAll(q.groupBy)) throw UserAuthException("group_by_not_allowed", 400)
            require(q.frameType == null || q.frameType in setOf("scalar", "table", "timeseries", "distribution"))
            require(q.order in setOf("value_desc", "value_asc", "label_asc"))
            require(q.interval == null || q.interval in intervals)
            require(q.priceBasis == null || q.priceBasis in setOf("list", "contract"))
            require(q.source == null || (q.metricId in setOf("cost", "tokens") && q.source in setOf("events", "metrics")))
            if (q.metricId in setOf("refusals", "vendor_account_mismatch", "contract_commitment_burn") && user.role != "owner") throw UserAuthException("forbidden", 403)
        }
        val filters = (listOf(body.filters) + body.queries.mapNotNull { it.filters?.merge(body.filters) }).distinct()
        filters.forEach { f ->
            require(f.products.all { it in setOf("claude_code", "codex") })
            require(f.models.size <= 100 && f.models.all { it.length in 1..200 })
            require(f.teamIds.size <= 500 && f.memberIds.size <= 500)
            access.teams(user, f.teamIds)
        }
        val personal = filters.flatMap { it.memberIds }.distinct()
        if (personal.isNotEmpty() || body.queries.any { it.metricId == "vendor_account_mismatch" })
            access.personal(user, audit, "query", personal.sorted().joinToString(",").ifEmpty { "vendor_account_mismatch" })
        body.queries.filter { it.metricId=="contract_commitment_burn" }.forEach { q ->
            val f = q.filters?.merge(body.filters) ?: body.filters
            if (f.teamIds.isNotEmpty() || f.memberIds.isNotEmpty() || f.products.isNotEmpty() || f.models.isNotEmpty())
                throw UserAuthException("contract_scope_required",403)
        }
        val scopes = filters.associateWith { scope(user, it).let { scope ->
            val filtered = scope.copy(parameters=scope.parameters + ("premium_patterns" to array(premiumPatterns.map(::sqlPattern))))
            if(cohort==null) filtered else onboardingScope(filtered,cohort,deadline)
        } }
        val id = UUID.randomUUID().toString()
        val results = linkedMapOf<String, Any>()
        body.queries.forEach { q ->
            val definition = requireNotNull(catalog.find(q.metricId))
            // 미구현 계산을 빈 성공 프레임이나 수집 불가로 위장하지 않는다.
            if ((q.metricId !in pointMetrics && q.metricId !in populationMetrics && q.metricId !in ratioMetrics && q.metricId !in sessionMetrics && q.metricId !in durationMetrics && q.metricId !in setOf("tool_calls","rate_limit_events","tool_rejections","usage_heatmap","compactions","mcp_connections","llm_stop_reasons","subagent_activity","hook_blocking","hook_executions","refusals","model_users","tokens","session_last_event","vendor_account_mismatch","cost")) || (q.frameType == "distribution" && q.metricId !in sessionMetrics && q.metricId !in durationMetrics && q.metricId!="usage_concentration")) {
                results[q.refId] = error(id, 501, "metric_not_implemented")
            } else {
                if (q.metricId=="command_prompt_ratio") require(q.params.keys.all { it=="command_names" } &&
                    q.params.values.all { it.isArray && it.size()<=100 && it.all { name -> name.isString && name.asString().length in 1..200 } })
                else if (q.metricId=="tool_rejections") require(q.params.keys.all { it=="decided_by" } &&
                    q.params.values.all { it.isArray && it.size()<=3 && it.toList().distinct().size==it.size() &&
                        it.all { source -> source.isString && source.asString() in setOf("config","hook","user") } })
                else if (q.metricId=="edit_acceptance_rate") require(q.params.keys.all { it=="language" } &&
                    q.params.values.all { it.isString && it.asString().isNotBlank() && it.asString().length<=256 && it.asString().none { c -> c.isISOControl() } })
                else if (q.metricId=="tool_calls") require(q.params.keys.all { it=="success" } && q.params.values.all { it.isBoolean })
                else if (q.metricId=="mcp_connections") require(q.params.keys.all { it=="server_scope" } &&
                    q.params.values.all { it.isString && it.asString().length in 1..100 })
                else if (q.metricId=="rubber_stamp_ratio") require(q.params.keys.all { it=="threshold_ms" } &&
                    q.params.values.all { it.isIntegralNumber && it.canConvertToInt() && it.asInt() in 0..3600000 })
                else if (q.metricId=="contract_commitment_burn") {
                    require(q.params.keys.all { it=="contract_id" })
                    q.params["contract_id"]?.let { require(it.isString &&
                        runCatching { UUID.fromString(it.asString()).toString()==it.asString().lowercase() }.getOrDefault(false)) }
                }
                else if (q.metricId=="cost_anomaly") {
                    require(q.params.keys.all { it in setOf("moving_avg_days","window_days") } &&
                        q.params.values.all { it.isIntegralNumber && it.canConvertToInt() && it.asInt() in 1..90 })
                    require(q.params["moving_avg_days"]==null || q.params["window_days"]==null ||
                        q.params.getValue("moving_avg_days").asInt()==q.params.getValue("window_days").asInt())
                    require(q.interval==null || q.interval=="1d")
                }
                else if (q.metricId=="cost") {
                    require(q.params.isEmpty())
                    require(q.source=="metrics" || q.groupBy.none { it in setOf("agent_name","skill_name","plugin_name","speed") })
                }
                else if (q.metricId=="tokens") {
                    require(q.params.keys.all { it=="types" })
                    q.params["types"]?.let { types ->
                        require(types.isArray && types.toList().all { it.isString && it.asString() in tokenTypes } &&
                            types.toList().map { it.asString() }.distinct().size==types.size())
                    }
                    require(q.source=="metrics" || "agent_name" !in q.groupBy)
                }
                else require(q.params.isEmpty())
                val timeseries = (q.frameType ?: definition.defaultFrameType) == "timeseries"
                if(q.metricId=="onboarding_retention" && timeseries) require(q.interval==null || q.interval=="1w")
                val interval = q.interval ?: if(q.metricId=="onboarding_retention" && timeseries) "1w" else if (q.metricId=="cost_anomaly") "1d" else if (!timeseries) "1d" else
                    intervals.keys.firstOrNull { buckets(time, from, to, it).size <= body.maxDataPoints }
                        ?: throw DashboardReadException("query_too_wide", 422)
                val ticks = if (timeseries) buckets(time, from, to, interval) else emptyList()
                if (ticks.size > body.maxDataPoints) throw DashboardReadException("query_too_wide", 422)
                val scope = scopes.getValue(q.filters?.merge(body.filters) ?: body.filters)
                val calculation = q.copy(priceBasis=if (q.metricId=="contract_commitment_burn") "contract" else q.priceBasis ?: body.priceBasis)
                try {
                    var current = readers.read(calculation, scope, from, to, zone, interval, deadline)
                    var previous = comparison?.let { readers.read(calculation, scope, it.first, it.second, zone, interval, deadline) }
                    if (q.metricId in topGroupMetrics && q.groupBy.isNotEmpty()) {
                        fun key(row: JsonNode) = q.groupBy.indices.map { row["g$it"].asString() }
                        val grouped = current.data.groupBy(::key)
                        val priorGroups = previous?.data?.groupBy(::key).orEmpty()
                        val keys = (grouped.keys+priorGroups.keys).distinct()
                        if (keys.size>groupLimit(q)) {
                            // 사용자 수·백분위수처럼 더할 수 없는 값은 기간 전체에서 다시 계산해 순위를 정한다.
                            val period = if (q.metricId in topPeriodMetrics && timeseries)
                                readers.read(calculation.copy(frameType="scalar"),scope,from,to,zone,interval,deadline) else current
                            val periodGroups = period.data.associateBy(::key)
                            // 소집단의 숨겨진 수치가 상위 그룹의 선택이나 순서에 영향을 주지 않는다.
                            val retained = keys.filter { "__other__" !in it }.sortedWith(
                                compareByDescending<List<String>> { key ->
                                    val rows = grouped[key].orEmpty()
                                    if ((rows+priorGroups[key].orEmpty()).any { it["people"].asLong()<5 }) 0.0
                                    else if (q.metricId=="onboarding_retention") {
                                        // 완료된 코호트·주차 셀의 설치 수로 가중하며 미완료 주차는 순위에서 제외한다.
                                        val complete = rows.filter { !it["value"].isNull }
                                        val denominator = complete.sumOf { it["denominator"].asDouble() }
                                        if(denominator==0.0) 0.0 else complete.sumOf { it["numerator"].asDouble() }/denominator
                                    }
                                    else if (q.metricId in topPeriodMetrics) periodGroups[key]?.get("value")?.asDouble(0.0) ?: 0.0
                                    else if (q.metricId in topRatioMetrics) {
                                        // 일별 비율의 합계가 아니라 관측량으로 가중한 전체 기간 비율을 사용한다.
                                        val denominator = rows.sumOf { it["denominator"].asDouble(0.0) }
                                        if (denominator==0.0) 0.0 else rows.sumOf { it["numerator"].asDouble(0.0) }/denominator
                                    } else rows.sumOf { it["value"].asDouble(0.0) }
                                }.thenBy { mapper.writeValueAsString(it) }).take(groupLimit(q))
                            // 기타에 실제 합쳐지는 팀의 현재 구성원 합집합을 두 기간에 공통 적용한다.
                            val aggregateScope = if (q.metricId=="adoption_rate" && q.groupBy==listOf("team")) {
                                val otherMembers = keys.filter { it !in retained }.flatMap { scope.teamMembers[it.single()].orEmpty() }.toSet()
                                scope.copy(teamMembers=scope.teamMembers + ("__other__" to otherMembers))
                            } else scope
                            current = readers.read(calculation,aggregateScope,from,to,zone,interval,deadline,retained)
                            previous = comparison?.let { readers.read(calculation,aggregateScope,it.first,it.second,zone,interval,deadline,retained) }
                        }
                    }
                    results[q.refId] = mapOf("status" to 200, "frames" to frames.build(if (q.metricId=="contract_commitment_burn") calculation.copy(groupBy=listOf("contract_id")) else if (q.metricId=="session_last_event") q.copy(groupBy=q.groupBy+"last_event") else if (q.metricId=="onboarding_retention") q.copy(groupBy=q.groupBy+if(timeseries) listOf("cohort_index") else listOf("cohort_index","week_index")) else calculation, definition, current, previous, interval, ticks, comparison?.takeIf { before == null }?.let { ticks.map { tick -> time.bucket(if (body.compare=="previous_period")
                        tick.minus(Duration.between(from,to)) else tick.atZone(time.zone).minusWeeks(1).toInstant(), interval) } }, scope.teamNames))
                } catch (e: DashboardReadException) { results[q.refId] = error(id, e.status, e.code) }
            }
        }
        val coverage = coverage(scopes.getValue(body.filters), from, to, deadline)
        val response = mapOf("request_id" to id, "resolved_from" to from.toString(), "resolved_to" to to.toString(),
            "compare_from" to comparison?.first?.toString(), "compare_to" to comparison?.second?.toString(),
            "coverage" to coverage, "results" to results)
        if (accept.split(',').any { it.trim().startsWith("text/csv") }) {
            @Suppress("UNCHECKED_CAST") val first = results.values.first() as Map<String, Any>
            if (first["status"] != 200) return ResponseEntity.status(first["status"] as Int).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(first["error"])
            val node = mapper.valueToTree<JsonNode>(first)["frames"]
            val lines = mutableListOf("frame,field,labels,index,value")
            node.toList().forEachIndexed { index, frame ->
                frame["schema"]["fields"].toList().forEachIndexed { fieldIndex, field ->
                    frame["data"]["values"][fieldIndex].toList().forEachIndexed { valueIndex, value ->
                        lines += listOf(index.toString(), field["name"].asString(), field["labels"]?.toString() ?: "{}",
                            valueIndex.toString(), if (value.isNull) "" else if (value.isString) value.asString() else value.toString()).joinToString(",") { csv(it) }
                    }
                }
            }
            return ResponseEntity.ok().header("X-Request-Id", id).header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=dashboard.csv").body(lines.joinToString("\r\n"))
        }
        return ResponseEntity.ok().header("X-Request-Id", id).body(response)
    }

    private fun scope(user: UserIdentity, f: DashboardQueryFilters): Scope {
        val teams = access.teams(user, f.teamIds)
        val unrestricted = user.role == "owner" && f.teamIds.isEmpty()
        val rows = jdbc.sql("""SELECT i.id,m.id AS member_id,m.email AS registered_email, i.status::text AS installation_status, i.platform::text AS platform, floor(extract(epoch FROM i.created_at))::bigint AS created_epoch FROM enrollment.installations i
            JOIN enrollment.members m ON m.id=i.member_id AND m.tenant_id=i.tenant_id WHERE i.tenant_id=:tenant LIMIT 5001""")
            .param("tenant", user.tenantId).query().listOfRows()
        if (rows.size > 5000) throw DashboardReadException("query_too_wide", 422)
        val selected = rows.filter { f.memberIds.isEmpty() || it["member_id"] in f.memberIds }
        val members = jdbc.sql("""SELECT m.id FROM enrollment.members m WHERE m.tenant_id=:tenant AND m.status='active'
            AND (:all OR EXISTS (SELECT 1 FROM enrollment.team_memberships tm WHERE tm.member_id=m.id
                AND tm.left_at IS NULL AND tm.team_id IN (:teams)))""")
            .param("tenant", user.tenantId).param("all", unrestricted)
            .param("teams", teams.ifEmpty { setOf(UUID(0, 0)) }).query(UUID::class.java).list()
            .filter { f.memberIds.isEmpty() || it in f.memberIds }
        val names = jdbc.sql("SELECT id,name FROM enrollment.teams WHERE tenant_id=:tenant")
            .param("tenant", user.tenantId).query().listOfRows().associate { it["id"].toString() to it["name"].toString() }
        val memberships = jdbc.sql("""SELECT tm.team_id,tm.member_id FROM enrollment.team_memberships tm
            JOIN enrollment.teams t ON t.id=tm.team_id WHERE t.tenant_id=:tenant AND t.status='active' AND tm.left_at IS NULL""")
            .param("tenant", user.tenantId).query().listOfRows()
        val teamMembers = memberships.filter { it["member_id"] in members }.groupBy { it["team_id"].toString() }
            .mapValues { (_, rows) -> rows.map { it["member_id"] as UUID }.toSet() }
        val scopedMemberIds = if (unrestricted) selected.map { it["member_id"] }.toSet() else
            memberships.filter { it["team_id"] in teams }.map { it["member_id"] }.toSet()
        val installationCount = selected.count { it["installation_status"]=="active" && it["member_id"] in scopedMemberIds }
        val map = selected.joinToString(",", "{", "}") { "'${it["id"]}':'${it["member_id"]}'" }
        return Scope(mapOf("tenant" to user.tenantId.toString(), "teams" to array(teams.map { it.toString() }),
            "unrestricted" to if (unrestricted) "1" else "0", "products" to array(f.products), "models" to array(f.models), "premium_patterns" to "[]",
            "emails" to selected.joinToString(",","{","}") { "${quoted(it["id"].toString())}:${quoted(it["registered_email"].toString())}" },
            "created" to selected.joinToString(",","{","}") { "'${it["id"]}':${it["created_epoch"]}" },
            "platforms" to selected.joinToString(",","{","}") { "'${it["id"]}':'${it["platform"]}'" },
            "members" to map, "personal" to if (f.memberIds.isNotEmpty()) "1" else "0"), members.size, names, teamMembers, installationCount)
    }
    private fun onboardingScope(scope: Scope, cohort: Pair<Instant,Instant>, deadline: Long): Scope {
        val history = base.replace("ts>={from:DateTime} AND ","")
        val sql = """SELECT installation_id,{members:Map(String,String)}[installation_id] AS member_id
            $history AND $known GROUP BY installation_id HAVING min(ts)>={from:DateTime} LIMIT 5001"""
        val parameters = scope.parameters+mapOf("from" to boundary(cohort.first),"to" to boundary(cohort.second))
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
        if(rows.size>5000) throw DashboardReadException("query_too_wide",422)
        val selected = rows.joinToString(",","{","}") { "${quoted(it["installation_id"].asString())}:${quoted(it["member_id"].asString())}" }
        return scope.copy(parameters=scope.parameters+mapOf("members" to selected,"personal" to "1"),
            members=rows.map { it["member_id"].asString() }.distinct().size,installations=rows.size)
    }

    private fun coverage(scope: Scope, from: Instant, to: Instant, deadline: Long): Map<String, Any?> {
        val sql = """SELECT uniqExact(installation_id) AS installations,
            $people AS people,
            maxOrNull(ts) AS latest $base"""
        val row = mapper.readTree(reader.query(sql, scope.parameters + mapOf("from" to boundary(from), "to" to boundary(to)), remaining(deadline)))["data"][0]
        val hidden = row["people"].asLong()<5
        return mapOf("active_installations" to if (hidden) null else row["installations"].asLong(),
            "active_members" to if (hidden) null else scope.members,
            "ratio" to if (hidden || scope.members==0) null else row["installations"].asDouble()/scope.members,
            "last_ingested_at" to if (hidden || row["latest"].isNull) null else row["latest"].asString().replace(' ','T')+"Z")
    }
    private fun buckets(time: DashboardTime, from: Instant, to: Instant, interval: String): List<Instant> {
        val result = mutableListOf<Instant>()
        var at = time.bucket(from, interval)
        while (at < to && result.size <= 1000) { result += at; at = time.next(at, interval) }
        return result
    }
    private fun error(id: String, status: Int, code: String) = mapOf("status" to status, "frames" to emptyList<Any>(),
        "error" to mapOf("error" to code, "message" to code, "request_id" to id))
    private fun csv(value: String): String {
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
