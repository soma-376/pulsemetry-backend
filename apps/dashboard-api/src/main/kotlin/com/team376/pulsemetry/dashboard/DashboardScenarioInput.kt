package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.*
import java.util.UUID

/** 워커가 해석할 원본 파라미터와 요청 시점에 고정한 시간·인가 범위. */
data class DashboardScenarioInput(
    val params: ObjectNode,
    val priceBasis: String,
    val zone: ZoneId,
    val now: Instant,
    val times: Map<String, Instant>,
    val teamIds: Set<UUID>,
    val organizationScope: Boolean = false,
    val auditReason: String? = null,
)

/** 큐에 넣기 전 검증 경계. 재실행·워커에서도 현재 권한을 다시 확인해야 한다. */
@Component
class DashboardScenarioInputs(private val scenarios: DashboardScenarioCatalog, private val access: DashboardAccess) {
    fun prepare(scenarioId: String, body: JsonNode, user: UserIdentity, tenantZone: String,
        now: Instant, auditReason: String?): DashboardScenarioInput {
        val scenario = scenarios.detail(scenarioId)
        if (scenario["availability"].asString() == "unavailable") throw UserAuthException("scenario_unavailable", 409)
        val parsed = ScenarioParameterValidator.validate(scenario, body, tenantZone, now)
        if (user.role !in setOf("owner", "admin")) throw UserAuthException("forbidden", 403)
        if(scenarioId in setOf("S6-1","S6-3","S6-4")) require(parsed.params.path("models").size()<=100 &&
            parsed.params.path("models").all { it.asString().length<=200 })
        if(scenarioId=="S1-2") require(parsed.params["premium_model_patterns"].size() in 1..100 &&
            parsed.params["premium_model_patterns"].all { it.asString().length<=200 })
        if(scenarioId=="S8-3") require(listOf("model_a","model_b").all { parsed.params[it].asString().length<=200 })
        if(scenarioId=="S4-5") require(parsed.params.path("command_names").size()<=100 &&
            parsed.params.path("command_names").all { it.asString().length<=200 })
        val requested = parsed.params.path("team_ids").toList().map { UUID.fromString(it.asString()) }.toSet()
        val budgets = parsed.params.path("budget_by_team").properties().map { UUID.fromString(it.key) }.toSet()
        val allowed = access.teams(user, requested)
        if (!allowed.containsAll(budgets)) throw UserAuthException("forbidden", 403)
        val reason = if (scenario["target_page"].asString() == "P3" || scenario["metric_ids"].any { it.asString() == "refusals" })
            access.personal(user, auditReason, "scenario_run", scenarioId) else null
        return parsed.copy(teamIds = allowed, organizationScope = user.role == "owner" && requested.isEmpty(),auditReason=reason)
    }
}

/** 외부 JSON Schema를 실행하지 않고 서버 카탈로그에서 사용하는 제한된 타입만 검사한다. */
internal object ScenarioParameterValidator {
    fun validate(scenario: JsonNode, body: JsonNode, tenantZone: String, now: Instant): DashboardScenarioInput {
        require(body.isObject && body.properties().all { it.key in setOf("params", "price_basis", "tz") })
        require(body.path("params").isObject)
        require(!body.has("price_basis") || body["price_basis"].isString)
        require(!body.has("tz") || body["tz"].isString)
        val basis = if (body.has("price_basis")) body["price_basis"].asString() else "list"
        require(basis in setOf("list", "contract"))
        val zone = ZoneId.of(if (body.has("tz")) body["tz"].asString() else tenantZone)
        val params = body["params"].deepCopy() as ObjectNode
        val schema = scenario["params_schema"]
        for ((key, property) in schema["properties"].properties())
            if (!params.has(key) && property.has("default")) params.set(key, property["default"].deepCopy())
        check(schema, params)
        val time = DashboardTime(now, zone)
        val times = listOf("from", "to", "as_of", "pivot_date", "cohort_from", "cohort_to")
            .filter { params.has(it) }.associateWith { time.resolve(params[it].asString()) }.toMutableMap()
        for ((start, end) in listOf("from" to "to", "cohort_from" to "cohort_to")) {
            if (times.containsKey(start) && times.containsKey(end)) {
                require(times.getValue(start) < times.getValue(end))
                require(Duration.between(times.getValue(start), times.getValue(end)) <= Duration.ofDays(366))
            }
        }
        if (scenario["scenario_id"].asString() in setOf("S4-4", "S6-3", "S8-6", "S8-7")) {
            val pivot = LocalDate.parse(params["pivot_date"].asString())
            val weeks = params.path("window_weeks").asLong(4)
            times["from"] = pivot.atStartOfDay(zone).toInstant()
            times["to"] = pivot.plusWeeks(weeks).atStartOfDay(zone).toInstant()
            times["compare_from"] = pivot.minusWeeks(weeks).atStartOfDay(zone).toInstant()
            times["compare_to"] = times.getValue("from")
        }
        if (scenario["scenario_id"].asString()=="S5-6") {
            val pivot = times.getValue("pivot_date")
            require(times.getValue("from") < pivot && pivot < times.getValue("to"))
            times["compare_from"] = times.getValue("from")
            times["compare_to"] = pivot
            times["from"] = pivot
        }
        if (scenario["scenario_id"].asString()=="S1-7") {
            val at = times.getValue("as_of")
            require(at <= now)
            times["to"] = at
            times["from"] = at.minus(Duration.ofDays(params["inactive_days"].asLong()))
        }
        params.path("team_ids").forEach { canonicalUuid(it.asString()) }
        if (params.has("budget_by_team")) {
            val budgets = params["budget_by_team"]
            require(budgets.size() in 1..100)
            val selected = params.path("team_ids").toList().map { UUID.fromString(it.asString()) }.toSet()
            for ((id, budget) in budgets.properties()) {
                canonicalUuid(id)
                require(selected.isEmpty() || UUID.fromString(id) in selected)
                require(budget.has("usd") xor budget.has("tokens_m"))
                require(budget.properties().single().value.asDouble() > 0)
            }
        }
        if (params.has("model_a") && params.has("model_b")) require(params["model_a"] != params["model_b"])
        return DashboardScenarioInput(params, basis, zone, now, times, emptySet())
    }

    private fun canonicalUuid(value: String) {
        require(UUID.fromString(value).toString().equals(value, ignoreCase = true))
    }

    private fun check(schema: JsonNode, value: JsonNode, depth: Int = 0) {
        require(depth <= 8)
        when (schema.path("type").asString("")) {
            "object" -> {
                require(value.isObject && value.size() <= 100)
                schema.path("required").forEach { require(value.has(it.asString())) }
                for ((key, child) in value.properties()) {
                    val property = schema.path("properties").path(key)
                    when {
                        !property.isMissingNode -> check(property, child, depth + 1)
                        schema.path("additionalProperties").isObject -> check(schema["additionalProperties"], child, depth + 1)
                        else -> require(false) { "unknown_parameter" }
                    }
                }
            }
            "array" -> {
                require(value.isArray && value.size() <= 100)
                require(value.toList().distinct().size == value.size())
                value.forEach { check(schema["items"], it, depth + 1) }
            }
            "string" -> {
                require(value.isString)
                val text = value.asString()
                val length = text.codePointCount(0, text.length)
                require(text.isNotBlank() && text.none { it.isISOControl() })
                require(length >= schema.path("minLength").asInt(0))
                require(length <= minOf(500, schema.path("maxLength").asInt(500)))
                if (schema.path("format").asString("") == "date") {
                    require(text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                    LocalDate.parse(text)
                }
            }
            "integer", "number" -> {
                require(value.isNumber && value.asDouble().isFinite())
                if (schema["type"].asString() == "integer") require(value.isIntegralNumber)
                if (schema.has("minimum")) require(value.asDouble() >= schema["minimum"].asDouble())
                if (schema.has("maximum")) require(value.asDouble() <= schema["maximum"].asDouble())
            }
            else -> throw IllegalArgumentException("unsupported_parameter_schema")
        }
        if (schema.has("enum")) require(schema["enum"].any { it == value })
    }
}
