package com.team376.pulsemetry.dashboard

import com.fasterxml.jackson.annotation.JsonProperty
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class DashboardQueryFilters(
    @get:JsonProperty("team_ids") val teamIds: Set<UUID> = emptySet(),
    val products: Set<String> = emptySet(), val models: Set<String> = emptySet(),
    @get:JsonProperty("member_ids") val memberIds: Set<UUID> = emptySet(),
)
data class DashboardQueryFilterOverride(
    @get:JsonProperty("team_ids") val teamIds: Set<UUID>? = null,
    val products: Set<String>? = null, val models: Set<String>? = null,
    @get:JsonProperty("member_ids") val memberIds: Set<UUID>? = null,
) {
    fun merge(base: DashboardQueryFilters) = DashboardQueryFilters(teamIds ?: base.teamIds,
        products ?: base.products, models ?: base.models, memberIds ?: base.memberIds)
}
data class DashboardQueryItem(
    @get:JsonProperty("ref_id") val refId: String,
    @get:JsonProperty("metric_id") val metricId: String,
    @get:JsonProperty("group_by") val groupBy: List<String> = emptyList(),
    @get:JsonProperty("frame_type") val frameType: String? = null,
    val interval: String? = null, val source: String? = null, val limit: Int = 100,
    val order: String = "value_desc", val filters: DashboardQueryFilterOverride? = null,
    @get:JsonProperty("price_basis") val priceBasis: String? = null,
    val params: Map<String, JsonNode> = emptyMap(),
)
data class DashboardQueryRequest(
    val from: String, val to: String, val tz: String? = null, val compare: String = "none",
    val filters: DashboardQueryFilters = DashboardQueryFilters(),
    @get:JsonProperty("price_basis") val priceBasis: String = "list",
    @get:JsonProperty("max_data_points") val maxDataPoints: Int = 500,
    val queries: List<DashboardQueryItem>,
)

/** SQL 조각은 고정식만 허용한다. 필터 값과 신원 대응표는 타입이 있는 파라미터로 전달한다. */
@RestController
@RequestMapping("/v1")
class DashboardQuery(private val catalog: DashboardMetricCatalog, private val access: DashboardAccess,
    private val reader: DashboardClickHouseReader, private val jdbc: JdbcClient,
    private val mapper: ObjectMapper, private val clock: Clock) {
    private val pointMetrics = mapOf("sessions" to "claude_code.session.count",
        "active_time" to "claude_code.active_time.total", "lines_of_code" to "claude_code.lines_of_code.count",
        "commits" to "claude_code.commit.count", "pull_requests" to "claude_code.pull_request.count")
    private val populationMetrics = setOf("active_users", "adoption_rate", "telemetry_coverage")
    private val ratioMetrics = setOf("automation_ratio", "integration_depth", "command_prompt_ratio")
    private val intervals = linkedMapOf("1h" to "1 HOUR", "6h" to "6 HOUR", "1d" to "1 DAY", "1w" to "1 WEEK", "1M" to "1 MONTH")
    private val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    private data class Scope(val parameters: Map<String, String>, val members: Int, val teamNames: Map<String, String>, val teamMembers: Map<String, Int>, val installations: Int)
    private data class Rows(val data: List<JsonNode>, val sql: String)

    @PostMapping("/query")
    fun query(@AuthenticationPrincipal user: UserIdentity, @RequestBody body: DashboardQueryRequest,
        @RequestHeader(value = "X-Audit-Reason", required = false) audit: String?,
        @RequestHeader(value = "Accept", defaultValue = "application/json") accept: String): ResponseEntity<*> {
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
        if (Duration.between(from, to) > Duration.ofDays(366)) throw DashboardReadException("query_too_wide", 422)
        val comparison = time.compare(from, to, body.compare)
        // 권한·감사는 전체 요청에 선행한다. 쿼리 오류로 감춰서 다른 결과를 반환하지 않는다.
        body.queries.forEach { q ->
            require(q.refId.matches(Regex("[A-Z]")) && q.limit in 1..100)
            require(q.groupBy.size <= 2 && q.groupBy.distinct().size == q.groupBy.size)
            val definition = catalog.find(q.metricId) ?: throw IllegalArgumentException("invalid_metric")
            if (!definition.allowedGroupBy.containsAll(q.groupBy)) throw UserAuthException("group_by_not_allowed", 400)
            require(q.frameType == null || q.frameType in setOf("scalar", "table", "timeseries", "distribution"))
            require(q.order in setOf("value_desc", "value_asc", "label_asc"))
            require(q.interval == null || q.interval in intervals)
            require(q.priceBasis == null || q.priceBasis in setOf("list", "contract"))
            require(q.source == null || (q.metricId in setOf("cost", "tokens") && q.source in setOf("events", "metrics")))
            if (q.metricId in setOf("refusals", "vendor_account_mismatch") && user.role != "owner") throw UserAuthException("forbidden", 403)
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
        val scopes = filters.associateWith { scope(user, it) }
        val id = UUID.randomUUID().toString()
        val results = linkedMapOf<String, Any>()
        body.queries.forEach { q ->
            val definition = requireNotNull(catalog.find(q.metricId))
            // 미구현 계산을 빈 성공 프레임이나 수집 불가로 위장하지 않는다.
            if ((q.metricId !in pointMetrics && q.metricId !in populationMetrics && q.metricId !in ratioMetrics && q.metricId!="prompts_per_session") || (q.frameType == "distribution" && q.metricId!="prompts_per_session")) {
                results[q.refId] = error(id, 501, "metric_not_implemented")
            } else {
                require(q.params.isEmpty())
                val timeseries = (q.frameType ?: definition.defaultFrameType) == "timeseries"
                val interval = q.interval ?: if (!timeseries) "1d" else
                    intervals.keys.firstOrNull { buckets(time, from, to, it).size <= body.maxDataPoints }
                        ?: throw DashboardReadException("query_too_wide", 422)
                val ticks = if (timeseries) buckets(time, from, to, interval) else emptyList()
                if (ticks.size > body.maxDataPoints) throw DashboardReadException("query_too_wide", 422)
                val scope = scopes.getValue(q.filters?.merge(body.filters) ?: body.filters)
                try {
                    val current = read(q, scope, from, to, zone, interval, deadline)
                    val previous = comparison?.let { read(q, scope, it.first, it.second, zone, interval, deadline) }
                    results[q.refId] = mapOf("status" to 200, "frames" to frames(q, definition, current, previous, interval, ticks, comparison?.let { ticks.map { tick -> time.bucket(if (body.compare=="previous_period")
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
            if (first["status"] != 200) return ResponseEntity.status(first["status"] as Int).body(first["error"])
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
        val rows = jdbc.sql("""SELECT i.id,m.id AS member_id,i.status::text AS installation_status FROM enrollment.installations i
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
            .mapValues { (_, rows) -> rows.map { it["member_id"] }.distinct().size }
        val scopedMemberIds = if (unrestricted) selected.map { it["member_id"] }.toSet() else
            memberships.filter { it["team_id"] in teams }.map { it["member_id"] }.toSet()
        val installationCount = selected.count { it["installation_status"]=="active" && it["member_id"] in scopedMemberIds }
        val map = selected.joinToString(",", "{", "}") { "'${it["id"]}':'${it["member_id"]}'" }
        return Scope(mapOf("tenant" to user.tenantId.toString(), "teams" to array(teams.map { it.toString() }),
            "unrestricted" to if (unrestricted) "1" else "0", "products" to array(f.products), "models" to array(f.models),
            "members" to map, "personal" to if (f.memberIds.isNotEmpty()) "1" else "0"), members.size, names, teamMembers, installationCount)
    }
    private fun boundary(at: Instant) = utc.format(if (at.nano == 0) at else at.plusSeconds(1).minusNanos(at.nano.toLong()))
    private val activePoint = """signal='metric' AND product='claude_code'
        AND JSONExtractString(raw_json,'point','name')='claude_code.active_time.total'
        AND JSONExtractString(raw_json,'point','attrs','type')='user'
        AND JSONExtractInt(raw_json,'point','aggregation_temporality')!=2
        AND isNotNull(JSONExtract(raw_json,'point','value','Nullable(Float64)'))"""
    private val person = "{members:Map(String,String)}[installation_id]"
    private val known = "mapContains({members:Map(String,String)},installation_id)"
    private val people = """if(countIf($activePoint)>0,
        arrayCount(value -> value>0,tupleElement(sumMapIf([$person],[JSONExtractFloat(raw_json,'point','value')],$known AND $activePoint),2)),
        uniqExactIf($person,$known))"""
    private val base = """FROM enriched_events FINAL WHERE tenant_id={tenant:String}
        AND ts>={from:DateTime} AND ts<{to:DateTime}
        AND ({unrestricted:UInt8}=1 OR hasAny(team_ids_as_of,{teams:Array(String)}))
        AND (empty({products:Array(String)}) OR has({products:Array(String)},product))
        AND (empty({models:Array(String)}) OR has({models:Array(String)},
            coalesce(nullIf(JSONExtractString(raw_json,'payload','model'),''),JSONExtractString(raw_json,'point','attrs','model'))))
        AND ({personal:UInt8}=0 OR mapContains({members:Map(String,String)},installation_id))"""
    private fun read(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant, zone: String, interval: String, deadline: Long): Rows {
        if (q.metricId=="prompts_per_session") return readPrompts(q, scope, from, to, zone, interval, deadline)
        if (q.metricId in populationMetrics) return readPopulation(q, scope, from, to, zone, interval, deadline)
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = q.groupBy.mapIndexed { index, dim -> "${dimension(dim)} AS g$index" }
        val groupNames = q.groupBy.indices.map { "g$it" }
        val bucket = if (frameType == "timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val name = "JSONExtractString(raw_json,'point','name')"
        val attrType = "JSONExtractString(raw_json,'point','attrs','type')"
        val metric = when (q.metricId) {
            "automation_ratio" -> "signal='metric' AND product='claude_code' AND $name='claude_code.active_time.total' AND $attrType IN ('user','cli')"
            "integration_depth" -> "signal='metric' AND product='claude_code' AND ($name IN ('claude_code.commit.count','claude_code.pull_request.count') OR ($name='claude_code.session.count' AND JSONExtractString(raw_json,'point','attrs','start_type')='fresh'))"
            "command_prompt_ratio" -> "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='user_prompt' AND JSONExtractString(raw_json,'envelope','session_id') NOT IN ('','(unknown)')"
            else -> "signal='metric' AND product='claude_code' AND $name={metric:String}"
        }
        val prompt = q.metricId=="command_prompt_ratio"
        val valid = if (prompt) metric else "$metric AND JSONExtractInt(raw_json,'point','aggregation_temporality')!=2 AND isNotNull(JSONExtract(raw_json,'point','value','Nullable(Float64)'))"
        val cumulative = if (prompt) "0" else "countIf($metric AND JSONExtractInt(raw_json,'point','aggregation_temporality')=2)"
        val pointValue = "JSONExtract(raw_json,'point','value','Nullable(Float64)')"
        val numerator = when (q.metricId) {
            "automation_ratio" -> "sumIf($pointValue,$valid AND $attrType='cli')"
            "integration_depth" -> "sumIf($pointValue,$valid AND $name IN ('claude_code.commit.count','claude_code.pull_request.count'))"
            "command_prompt_ratio" -> "countIf($valid AND JSONExtractString(raw_json,'payload','command_name')!='')"
            else -> "sumIf($pointValue,$valid)"
        }
        val denominator = when (q.metricId) {
            "automation_ratio" -> "sumIf($pointValue,$valid)"
            "integration_depth" -> "sumIf($pointValue,$valid AND $name='claude_code.session.count')"
            else -> "countIf($valid)"
        }
        val result = if (q.metricId in ratioMetrics)
            "coalesce($numerator,0) AS numerator, coalesce($denominator,0) AS denominator, numerator/nullIf(denominator,0) AS value"
            else "$numerator AS value"
        val sql = """SELECT $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
            $people AS people, countIf($activePoint)>0 AS active_time_definition,
            countIf($valid) AS points, $cumulative AS cumulative,
            $result
            FROM (SELECT * $base)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
            GROUP BY bucket${if (groupNames.isEmpty()) "" else ","+groupNames.joinToString(",")}
            ORDER BY ${if (groupNames.isEmpty()) "" else groupNames.joinToString(",")+","}bucket"""
        val parameters = scope.parameters + mapOf("from" to boundary(from), "to" to boundary(to), "zone" to zone, "metric" to pointMetrics[q.metricId].orEmpty())
        val rows = mapper.readTree(reader.query(sql, parameters, remaining(deadline)))["data"].toList().filter { it["points"].asLong()>0 || it["cumulative"].asLong()>0 }
        if (rows.map { row -> q.groupBy.indices.map { row["g$it"].asString() } }.distinct().size > q.limit)
            throw DashboardReadException("query_too_wide", 422)
        return Rows(rows, sql)
    }
    private fun readPrompts(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = q.groupBy.mapIndexed { index, dim -> "${dimension(dim)} AS g$index" }
        val groups = listOf("bucket") + q.groupBy.indices.map { "g$it" }
        val groupSql = groups.joinToString(",")
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        // 설치·제품별 세션 이름 공간을 분리하고, 프롬프트가 관측된 기간 내 세션만 센다.
        val sql = """WITH observed AS (
            SELECT *, $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")}
            FROM (SELECT * $base)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
        ), privacy AS (
            SELECT $groupSql, $people AS people, countIf($activePoint)>0 AS active_time_definition
            FROM observed GROUP BY $groupSql
        ), sessions AS (
            SELECT $groupSql, installation_id, product, JSONExtractString(raw_json,'envelope','session_id') AS session_id,
                count() AS prompts FROM observed
            WHERE signal IN ('log','span') AND JSONExtractString(raw_json,'type')='user_prompt'
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
        val parameters = scope.parameters + mapOf("from" to boundary(from), "to" to boundary(to), "zone" to zone)
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
        if (rows.map { row -> q.groupBy.indices.map { row["g$it"].asString() } }.distinct().size>q.limit)
            throw DashboardReadException("query_too_wide",422)
        return Rows(rows,sql)
    }
    private fun readPopulation(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long): Rows {
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val dimensions = q.groupBy.mapIndexed { index, dim -> "${dimension(dim)} AS g$index" }
        val groups = q.groupBy.indices.map { "g$it" }
        val suffix = if (groups.isEmpty()) "" else ","+groups.joinToString(",")
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        // 설치 여러 개를 같은 사람으로 합친 뒤 활성 시간 합계로 활성 여부를 판정한다.
        val sql = """SELECT bucket$suffix, sum(observations) AS points, sum(cumulative_points) AS cumulative,
            sum(active_points)>0 AS active_time_definition,
            if(sum(active_points)>0,countIf(member_known AND active_value>0),countIf(member_known)) AS people,
            sum(installations) AS observed_installations
            FROM (SELECT $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
                $person AS member_key, $known AS member_known, count() AS observations,
                countIf($activePoint) AS active_points,
                countIf(signal='metric' AND product='claude_code'
                    AND JSONExtractString(raw_json,'point','name')='claude_code.active_time.total'
                    AND JSONExtractInt(raw_json,'point','aggregation_temporality')=2) AS cumulative_points,
                sumIf(JSONExtract(raw_json,'point','value','Nullable(Float64)'),$activePoint) AS active_value,
                uniqExactIf(installation_id,installation_id!='') AS installations
                FROM (SELECT * $base)
                ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
                GROUP BY bucket$suffix,member_key,member_known)
            GROUP BY bucket$suffix ORDER BY bucket$suffix"""
        val params = scope.parameters + mapOf("from" to boundary(from), "to" to boundary(to), "zone" to zone)
        val rows = mapper.readTree(reader.query(sql,params,remaining(deadline)))["data"].toList().map { row ->
            val node = row as tools.jackson.databind.node.ObjectNode
            val numerator = if (q.metricId=="telemetry_coverage") row["observed_installations"].asDouble() else row["people"].asDouble()
            val denominator = if (q.metricId=="telemetry_coverage") scope.installations else
                q.groupBy.indexOf("team").takeIf { it>=0 }?.let { scope.teamMembers[row["g$it"].asString()] ?: 0 } ?: scope.members
            if (q.metricId=="active_users") node.put("value",numerator) else {
                node.put("numerator",numerator)
                node.put("denominator",denominator)
                if (denominator==0) node.putNull("value") else node.put("value",numerator/denominator)
            }
            node
        }
        return Rows(rows,sql)
    }
    private fun dimension(dim: String): String = when (dim) {
        "team" -> "team"
        "product" -> "product"
        "start_type", "type", "model" -> "JSONExtractString(raw_json,'point','attrs','$dim')"
        else -> throw IllegalArgumentException("group_by_not_allowed")
    }
    private fun frames(q: DashboardQueryItem, definition: DashboardMetricDefinition, current: Rows, previous: Rows?,
        interval: String, ticks: List<Instant>, previousTicks: List<Instant>?, names: Map<String, String>): List<Map<String, Any>> {
        fun key(row: JsonNode) = q.groupBy.indices.map { row["g$it"].asString() }
        val groups = current.data.groupBy(::key)
        val comparisons = previous?.data?.groupBy(::key).orEmpty()
        val keys = (groups.keys + comparisons.keys).distinct()
        if (keys.size > q.limit) throw DashboardReadException("query_too_wide", 422)
        val frameType = q.frameType ?: definition.defaultFrameType
        val frames = keys.flatMap { group ->
            val rows = groups[group].orEmpty()
            val prior = comparisons[group].orEmpty()
            val labels = q.groupBy.zip(group).toMap().toMutableMap()
            labels["team"]?.let { labels["team_id"] = it; labels["team_name"] = names[it] ?: it }
            val fields = mutableListOf<Map<String, Any?>>()
            val values = mutableListOf<List<Any?>>()
            val timeseries = frameType == "timeseries"
            val suppressed = (rows+prior).any { it["people"].asLong() < 5 }
            if (timeseries) {
                fields += mapOf("name" to "time", "type" to "time")
                values += ticks.map { it.toEpochMilli() }
            }
            fun add(name: String, series: List<JsonNode>, seriesTicks: List<Instant>?, column: String = "value") {
                fields += mapOf("name" to name, "type" to "number", "labels" to labels,
                    "config" to mapOf("unit" to if (column=="value") definition.unit else if (q.metricId=="automation_ratio") "s" else "count", "suppressed" to suppressed,
                        "group_size" to if (suppressed) null else (rows+prior).minOfOrNull { it["people"].asLong() }))
                val byTime = series.associateBy { it["bucket"].asLong() }
                val aligned = if (timeseries) ticks.indices.map { index -> seriesTicks?.getOrNull(index)?.let { byTime[it.toEpochMilli()] } }
                    else listOf(series.firstOrNull())
                values += aligned.map { row -> row?.let {
                    if (suppressed || it["points"].asLong()==0L || it[column].isNull) null else it[column].asDouble()
                } }
            }
            if (q.metricId=="prompts_per_session") {
                for (column in listOf("p50","p90")) {
                    add(column,rows,ticks,column)
                    if (previous!=null) add("${column}_compare",prior,previousTicks,column)
                }
            } else {
                add("value", rows, ticks)
                if (previous != null) add("value_compare", prior, previousTicks)
            }
            if (q.metricId in ratioMetrics || q.metricId in setOf("adoption_rate", "telemetry_coverage")) {
                for (column in listOf("numerator", "denominator")) {
                    add(column,rows,ticks,column)
                    if (previous!=null) add("${column}_compare",prior,previousTicks,column)
                }
            }
            val cumulative = (rows+prior).sumOf { it["cumulative"].asLong() }
            val summary = mapOf("schema" to mapOf("ref_id" to q.refId, "metric_id" to q.metricId,
                "frame_type" to frameType, "fields" to fields,
                "meta" to mapOf("definition" to definition.definition, "caveat" to definition.caveat,
                    "resolved_interval" to interval, "source_columns" to definition.sourceColumns,
                    "active_user_definition" to if ((rows+prior).all { it["active_time_definition"].asInt()==1 }) "active_time_user" else "any_event",
                    "executed_sql" to current.sql, "suppressed_groups" to if (suppressed) listOf(group.joinToString("/").ifEmpty { "all" }) else emptyList(),
                    "data_quality" to if (cumulative>0) listOf(if (suppressed) "누적 temporality 포인트 제외" else "누적 temporality 포인트 ${cumulative}개 제외") else emptyList())),
                "data" to mapOf("values" to values))
            if (frameType=="distribution") {
                val histogramFields = mutableListOf<Map<String,Any?>>(mapOf("name" to "bucket", "type" to "string"),
                    fields.first()+mapOf("name" to "count"))
                fun counts(row: JsonNode?): List<Any?> = (1..5).map { if (suppressed) null else row?.get("b$it")?.asLong() }
                val histogramValues = mutableListOf<List<Any?>>(listOf("1","2–3","4–7","8–15","16+"),counts(rows.firstOrNull()))
                if (previous!=null) {
                    histogramFields += fields[1]+mapOf("name" to "count_compare")
                    histogramValues += counts(prior.firstOrNull())
                }
                val histogram = mapOf("schema" to ((summary.getValue("schema") as Map<*,*>) + mapOf("fields" to histogramFields)),
                    "data" to mapOf("values" to histogramValues))
                listOf(histogram,summary)
            } else listOf(summary)
        }
        // 마스킹된 실제 값으로 정렬하지 않는다. 순서도 값에 대한 단서가 될 수 있다.
        return frames.sortedWith(compareBy<Map<String, Any>> { frame ->
            if (q.order == "label_asc") 0.0 else {
                val node = mapper.valueToTree<JsonNode>(frame)
                val index = node["schema"]["fields"].toList().indexOfFirst { it["type"].asString()=="number" }
                val numbers = node["data"]["values"][index].toList().filter { !it.isNull }
                if (numbers.isEmpty()) Double.POSITIVE_INFINITY else numbers.sumOf { it.asDouble() } * if (q.order=="value_desc") -1 else 1
            }
        }.thenBy { mapper.valueToTree<JsonNode>(it)["schema"]["fields"].toString() })
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
    private fun remaining(deadline: Long) = Duration.ofNanos(deadline-System.nanoTime())
    private fun array(values: Collection<String>) = values.joinToString(",", "[", "]") { "'"+it.replace("\\", "\\\\").replace("'", "\\'")+"'" }
    private fun error(id: String, status: Int, code: String) = mapOf("status" to status, "frames" to emptyList<Any>(),
        "error" to mapOf("error" to code, "message" to code, "request_id" to id))
    private fun csv(value: String): String {
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
