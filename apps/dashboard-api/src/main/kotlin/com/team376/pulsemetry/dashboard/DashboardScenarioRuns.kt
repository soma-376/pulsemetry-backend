package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.persistence.dashboard.DashboardRuns
import com.team376.pulsemetry.persistence.dashboard.DashboardRunRow
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class DashboardRunConfig {
    @Bean fun dashboardRuns(jdbc: JdbcClient, manager: PlatformTransactionManager) = DashboardRuns(jdbc, manager)
}

@Service
class DashboardScenarioRuns(private val runs: DashboardRuns, private val catalog: DashboardScenarioCatalog,
    private val inputs: DashboardScenarioInputs, private val access: DashboardAccess, private val query: DashboardQuery,
    private val jdbc: JdbcClient, private val mapper: ObjectMapper, private val clock: Clock,
    @Value("\${pulsemetry.dashboard.worker-enabled:true}") private val workerEnabled: Boolean) {
    fun start(id: String, body: JsonNode, user: UserIdentity, audit: String?): DashboardRunRow {
        val scenario = catalog.detail(id)
        if (scenario["availability"].asString()=="unavailable") throw UserAuthException("scenario_unavailable",409)
        // 실행 계획이 없는 시나리오를 일반 지표 조회만으로 성공 처리하지 않는다.
        if (id !in setOf("S1-1","S1-3","S1-4","S1-5","S1-6","S2-1","S2-2","S3-1","S3-2","S3-4","S3-5","S4-1","S4-2","S4-6","S4-8","S6-5","S7-1","S7-3","S8-1","S8-4")) throw UserAuthException("scenario_not_implemented",501)
        val zone = jdbc.sql("SELECT timezone FROM enrollment.tenants WHERE id=:tenant").param("tenant",user.tenantId)
            .query(String::class.java).single()
        val input = inputs.prepare(id,body,user,zone,clock.instant(),audit)
        if (!input.organizationScope && input.teamIds.isEmpty()) throw UserAuthException("forbidden",403)
        val execution = mapper.writeValueAsString(mapOf("tz" to input.zone.id,"price_basis" to input.priceBasis,
            "team_ids" to input.teamIds.map { it.toString() },"organization_scope" to input.organizationScope,"actor_role" to user.role))
        // admin의 빈 선택값은 실행 당시의 실제 팀으로 고정해 frontend 결과 범위에도 전달한다.
        if (!input.organizationScope) input.params.set("team_ids",mapper.valueToTree<JsonNode>(input.teamIds.map { it.toString() }))
        val runId = UUID.randomUUID()
        if (!runs.enqueue(runId,user.tenantId,id,input.params.toString(),execution,input.times.getValue("from"),input.times.getValue("to"),user.memberId))
            throw UserAuthException("run_limit_exceeded",429,2)
        return requireNotNull(runs.get(user.tenantId,runId))
    }

    fun get(id: UUID, user: UserIdentity): DashboardRunRow {
        val row = runs.get(user.tenantId,id) ?: throw UserAuthException("not_found",404)
        if (user.role!="owner") {
            val execution = mapper.readTree(row.execution)
            val teams = execution.path("team_ids").toList().map { UUID.fromString(it.asString()) }.toSet()
            if (user.role!="admin" || row.creator!=user.memberId || execution.path("actor_role").asString("")!="admin" ||
                execution.path("organization_scope").asBoolean(false) || teams.isEmpty() || !access.teamIds(user).containsAll(teams))
                throw UserAuthException("not_found",404)
        }
        return row
    }

    fun list(user: UserIdentity, scenario: String?, status: String?, creator: UUID?, limit: Int, cursor: String?): Map<String,Any?> {
        require(limit in 1..500)
        require(scenario==null || scenario.matches(Regex("S[1-8]-[1-9][0-9]?")))
        require(status==null || status in setOf("queued","running","succeeded","failed","cancelled"))
        if (user.role !in setOf("owner","admin")) throw UserAuthException("forbidden",403)
        val teams = access.teamIds(user).map { it.toString() }.sorted()
        val scope = mapper.writeValueAsString(listOf("run-list-v1",user.tenantId,user.memberId,user.role,teams,scenario,status,creator))
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        val after = cursor?.takeIf { it.isNotEmpty() }?.let {
            require(it.length<=2048)
            try {
                val decoded = mapper.readTree(java.util.Base64.getUrlDecoder().decode(it))
                require(decoded["scope"].asString()==fingerprint)
                java.time.Instant.parse(decoded["at"].asString()) to UUID.fromString(decoded["id"].asString())
            } catch (e: Exception) { throw IllegalArgumentException("invalid_cursor") }
        }
        val rows = runs.list(user.tenantId,user.memberId,user.role=="owner",mapper.writeValueAsString(teams),scenario,status,creator,
            after?.first,after?.second,limit+1)
        val page = rows.take(limit)
        val next = if (rows.size>limit) encodeCursor(mapper.writeValueAsString(mapOf(
            "scope" to fingerprint,"at" to page.last()["created_at"],"id" to page.last()["run_id"]))) else null
        return mapOf("items" to page,"next_cursor" to next,"total" to null)
    }

    fun cancel(id: UUID, user: UserIdentity): DashboardRunRow {
        get(id,user)
        if (!runs.cancel(user.tenantId,id)) throw UserAuthException("conflict",409)
        return get(id,user)
    }

    fun response(row: DashboardRunRow): Map<String,Any?> {
        val result = row.result?.let { mapper.readTree(it) }
        val counts = listOf("info","warning","anomaly").associateWith { severity ->
            result?.path("findings")?.count { it.path("severity").asString("")==severity } ?: 0
        }
        return mapOf("run_id" to row.id,"scenario_id" to row.scenario,
        "status" to row.status,"params" to mapper.readTree(row.params),"resolved_from" to row.from.toString(),"resolved_to" to row.to.toString(),
        "created_at" to row.created.toString(),"finished_at" to row.finished?.toString(),"created_by" to mapOf("member_id" to row.creator),
        "progress" to mapOf("step" to row.step,"total" to when(row.scenario) { "S8-1" -> 7; "S3-1" -> 5; "S4-1","S4-6","S6-5","S8-4" -> 2; "S1-1","S1-4","S1-5","S2-1","S2-2","S3-2","S4-2","S4-8","S7-3" -> 3; else -> 4 },"label" to when(row.status) { "queued" -> "대기"; "running" -> "지표 조회"; else -> "종료" }),
        "result" to result,"findings_count" to counts,"error" to row.error?.let { mapper.readTree(it) })
    }

    @Scheduled(fixedDelayString = "1000", initialDelayString = "1000")
    fun scheduled() { if (workerEnabled) runOne() }

    /** 재시작 후 queued는 이어서 처리하고, 만료된 running은 실패시킨다. 취소된 결과는 저장하지 않는다. */
    fun runOne() {
        val row = runs.claim() ?: return
        try {
            if (row.scenario in setOf("S1-1","S1-4","S1-5","S1-6","S2-1","S2-2","S3-1","S3-2","S3-4","S3-5","S4-1","S4-2","S4-6","S4-8","S6-5","S7-1","S7-3","S8-1","S8-4")) { runCatalogScenario(row); return }
            if (row.scenario!="S1-3") throw UserAuthException("scenario_not_implemented",501)
            val execution = mapper.readTree(row.execution)
            val params = mapper.readTree(row.params)
            val teamIds = execution["team_ids"].toList().map { UUID.fromString(it.asString()) }.toSet()
            val frames = linkedMapOf<String,JsonNode>()
            for ((index, metric) in listOf("cost","cost_anomaly","api_retry_attempts").withIndex()) {
                if (!runs.progress(row,index)) return
                val user = actor(row)
                val request = DashboardQueryRequest(row.from.toString(),row.to.toString(),execution["tz"].asString(),
                    filters=DashboardQueryFilters(teamIds=if(execution["organization_scope"].asBoolean()) emptySet() else teamIds),
                    priceBasis=execution["price_basis"].asString(),maxDataPoints=1000,
                    queries=listOf(DashboardQueryItem("A",metric,groupBy=if(metric=="cost") listOf("model") else emptyList(),
                        frameType="timeseries",interval="1d",limit=100,
                        params=if(metric=="cost_anomaly") mapOf("moving_avg_days" to params["moving_avg_days"]) else emptyMap())))
                val result = mapper.valueToTree<JsonNode>(query.query(user,request,null,"application/json").body)["results"]["A"]
                if (result["status"].asInt()!=200) throw UserAuthException(result.path("error").path("error").asString("query_failed"),result["status"].asInt())
                frames[metric] = result
            }
            val findings = DashboardSpikeFindings.evaluate(frames,params["spike_threshold_pct"].asDouble())
            if (!runs.progress(row,3)) return
            val teamCostRequest = DashboardQueryRequest(row.from.toString(),row.to.toString(),execution["tz"].asString(),
                filters=DashboardQueryFilters(teamIds=if(execution["organization_scope"].asBoolean()) emptySet() else teamIds),
                priceBasis=execution["price_basis"].asString(),maxDataPoints=1000,
                queries=listOf(DashboardQueryItem("A","cost",groupBy=listOf("team"),frameType="table",limit=100)))
            val teamCost = mapper.valueToTree<JsonNode>(query.query(actor(row),teamCostRequest,null,"application/json").body)["results"]["A"]
            if (teamCost["status"].asInt()!=200) throw UserAuthException(teamCost.path("error").path("error").asString("query_failed"),teamCost["status"].asInt())
            // 판정은 모델별 일 시계열로 끝내고, 팀별 기간 합계는 화면용 프레임에만 합친다.
            val cost = frames.getValue("cost").deepCopy() as tools.jackson.databind.node.ObjectNode
            (cost["frames"] as tools.jackson.databind.node.ArrayNode).addAll(teamCost["frames"] as tools.jackson.databind.node.ArrayNode)
            frames["cost"] = cost
            actor(row)
            if (!runs.progress(row,4)) return
            val result = mapOf("target_page" to "P1","highlight_widgets" to listOf("W1.2","W1.3","W2.5"),
                "applied_filters" to mapOf("from" to row.from.toString(),"to" to row.to.toString(),"tz" to execution["tz"].asString(),
                    "price_basis" to execution["price_basis"].asString(),"filters" to mapOf("team_ids" to if(execution["organization_scope"].asBoolean()) emptySet() else teamIds)),
                "frames" to frames,"findings" to findings)
            runs.finish(row,mapper.writeValueAsString(result),null)
        } catch (e: Exception) {
            val code = when(e) {
                is UserAuthException -> e.code
                is com.team376.pulsemetry.persistence.telemetry.DashboardReadException -> e.code
                else -> "scenario_execution_failed"
            }
            runs.finish(row,null,mapper.writeValueAsString(mapOf("error" to code,"message" to code,"request_id" to UUID.randomUUID().toString())))
        }
    }

    /** 임계값 기반 시나리오는 명시한 지표만 순서대로 읽고 관측 한계를 판정에 포함한다. */
    private fun runCatalogScenario(row: DashboardRunRow) {
        val execution = mapper.readTree(row.execution)
        val teams = execution["team_ids"].toList().map { UUID.fromString(it.asString()) }.toSet()
        val scope = if(row.scenario=="S1-1") mapper.readTree(row.params)["budget_by_team"].properties().map { UUID.fromString(it.key) }.toSet()
            else if(execution["organization_scope"].asBoolean()) emptySet() else teams
        val frames = linkedMapOf<String,JsonNode>()
        val definition = catalog.detail(row.scenario)
        val metricIds = if(row.scenario=="S1-6") listOf("cost","cost","tokens","sessions") else definition["metric_ids"].toList().map { it.asString() }
        for ((index, metric) in metricIds.withIndex()) {
            if (!runs.progress(row,index)) return
            val request = DashboardQueryRequest(row.from.toString(),row.to.toString(),execution["tz"].asString(),
                filters=DashboardQueryFilters(teamIds=scope),priceBasis=execution["price_basis"].asString(),
                queries=listOf(DashboardQueryItem("A",metric,source=if(row.scenario=="S1-6" && metric=="cost") "metrics" else null,frameType=if(row.scenario=="S1-6" && metric=="cost") "table" else if(row.scenario=="S3-2" && metric=="usage_concentration") "distribution" else if(row.scenario in setOf("S1-1","S3-1","S3-4","S4-6","S8-4") || (row.scenario=="S2-1" && metric=="usage_heatmap")) "table" else "timeseries",
                    groupBy=when { row.scenario=="S1-6" && metric=="cost" -> listOf("model",if(index==0) "effort" else "speed"); row.scenario=="S2-1" && metric=="usage_heatmap" -> listOf("weekday","hour"); row.scenario in setOf("S1-1","S3-1","S3-4") -> listOf("team"); row.scenario=="S4-6" && metric=="tool_calls" -> listOf("action"); row.scenario=="S8-4" -> if(metric=="tokens") listOf("product","model") else listOf("product"); else -> emptyList() },interval="1d",limit=if(row.scenario=="S2-1" && metric=="usage_heatmap") null else 100)))
            val result = mapper.valueToTree<JsonNode>(query.query(actor(row),request,null,"application/json").body)["results"]["A"]
            if(result["status"].asInt()!=200) throw UserAuthException(result.path("error").path("error").asString("query_failed"),result["status"].asInt())
            if(row.scenario=="S1-6" && metric=="cost" && frames.containsKey(metric)) {
                // 같은 비용의 두 분류이며 합산하지 않는다. 각 필드 라벨로 분류 축을 보존한다.
                val combined = frames.getValue(metric).deepCopy() as tools.jackson.databind.node.ObjectNode
                (combined["frames"] as tools.jackson.databind.node.ArrayNode).addAll(result["frames"] as tools.jackson.databind.node.ArrayNode)
                frames[metric] = combined
            } else frames[metric] = result
        }
        val thresholdKey = if(row.scenario=="S1-5") "io_ratio_threshold" else "failure_threshold"
        val findings = if(row.scenario=="S1-1") DashboardBudgetFindings.evaluate(frames,mapper.readTree(row.params)["budget_by_team"])
            else if(row.scenario=="S8-4") DashboardVendorFindings.evaluate(frames.getValue("cost"))
            else if(row.scenario=="S1-6") DashboardModelEffortFindings.evaluate(frames.getValue("cost"))
            else if(row.scenario=="S3-2") DashboardConcentrationFindings.evaluate(frames.getValue("usage_concentration"))
            else if(row.scenario=="S3-1") DashboardAdoptionFindings.evaluate(frames.getValue("adoption_rate"))
            else if(row.scenario=="S3-4") DashboardTeamUsageFindings.evaluate(frames.getValue("sessions"))
            else if(row.scenario=="S4-6") DashboardActionFindings.evaluate(frames.getValue("tool_calls"))
            else if(row.scenario=="S4-8") DashboardGateFindings.evaluate(frames.getValue("gate_wait_ms"),mapper.readTree(row.params)["wait_thresholds_min"])
            else DashboardThresholdFindings.evaluate(row.scenario,frames.getValue(when(row.scenario) { "S2-1" -> "rate_limit_events"; "S1-4" -> "cache_read_ratio"; "S3-5" -> "subagent_cost_ratio"; else -> metricIds.first() }),
                when(row.scenario) { "S1-4","S2-1","S2-2","S3-5","S4-2","S6-5","S7-3","S8-1" -> 0.0; "S4-1" -> 1.0; else -> mapper.readTree(row.params)[thresholdKey].asDouble() })
        actor(row)
        if (!runs.progress(row,metricIds.size)) return
        runs.finish(row,mapper.writeValueAsString(mapOf("target_page" to definition["target_page"].asString(),
            "highlight_widgets" to definition["highlight_widgets"].toList().map { it.asString() },
            "applied_filters" to mapOf("from" to row.from.toString(),"to" to row.to.toString(),"tz" to execution["tz"].asString(),
                "price_basis" to execution["price_basis"].asString(),"filters" to mapOf("team_ids" to scope)),
            "frames" to frames,"findings" to findings)),null)
    }

    private fun actor(row: DashboardRunRow): UserIdentity {
        val role = jdbc.sql("""SELECT m.role::text FROM enrollment.members m JOIN enrollment.tenants t ON t.id=m.tenant_id
            WHERE m.id=:member AND m.tenant_id=:tenant AND m.status='active' AND t.status='active'""")
            .param("member",row.creator).param("tenant",row.tenant).query(String::class.java).optional().orElse(null)
            ?: throw UserAuthException("forbidden",403)
        val execution = mapper.readTree(row.execution)
        if (role!=execution.path("actor_role").asString("") || role !in setOf("owner","admin")) throw UserAuthException("forbidden",403)
        val user = UserIdentity(row.creator,row.tenant,role,UUID(0,0),null,"web")
        val teams = execution["team_ids"].toList().map { UUID.fromString(it.asString()) }.toSet()
        if ((!execution["organization_scope"].asBoolean() && teams.isEmpty()) || !access.teamIds(user).containsAll(teams))
            throw UserAuthException("forbidden",403)
        return user
    }
}

@RestController
@RequestMapping("/v1")
class DashboardRunController(private val service: DashboardScenarioRuns) {
    @GetMapping("/scenario-runs")
    fun list(@AuthenticationPrincipal user: UserIdentity,
        @RequestParam(name="scenario_id",required=false) scenario: String?, @RequestParam(required=false) status: String?,
        @RequestParam(name="created_by",required=false) creator: String?, @RequestParam(defaultValue="50") limit: String,
        @RequestParam(required=false) cursor: String?) = service.list(user,scenario,status,creator?.let(::runId),requireNotNull(limit.toIntOrNull()),cursor)

    @PostMapping("/scenarios/{scenarioId}/runs")
    fun start(@PathVariable scenarioId: String, @RequestBody body: JsonNode, @AuthenticationPrincipal user: UserIdentity,
        @RequestHeader(value="X-Audit-Reason",required=false) audit: String?, @RequestParam(defaultValue="false") wait: String): ResponseEntity<*> {
        require(wait in setOf("true","false"))
        var row = service.start(scenarioId,body,user,audit)
        val deadline = System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos()
        while(wait=="true" && row.status in setOf("queued","running") && System.nanoTime()<deadline) {
            Thread.sleep(100)
            row = service.get(row.id,user)
        }
        return ResponseEntity.status(if(row.status in setOf("queued","running")) 202 else 200)
            .header("Location","/v1/scenario-runs/${row.id}").header("Retry-After","2").body(service.response(row))
    }
    @GetMapping("/scenario-runs/{id}")
    fun get(@PathVariable id: String, @AuthenticationPrincipal user: UserIdentity): ResponseEntity<*> {
        val row = service.get(runId(id),user)
        val response = ResponseEntity.ok()
        if(row.status in setOf("queued","running")) response.header("Retry-After","2")
        return response.body(service.response(row))
    }
    @PostMapping("/scenario-runs/{id}/cancel")
    fun cancel(@PathVariable id: String, @AuthenticationPrincipal user: UserIdentity) = service.response(service.cancel(runId(id),user))
    private fun runId(value: String): UUID = UUID.fromString(value).also {
        require(it.toString().equals(value,ignoreCase=true))
    }
}
