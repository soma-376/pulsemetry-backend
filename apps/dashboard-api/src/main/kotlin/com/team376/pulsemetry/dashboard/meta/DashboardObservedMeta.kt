package com.team376.pulsemetry.dashboard.meta

import com.team376.pulsemetry.dashboard.auth.DashboardAccess
import com.team376.pulsemetry.dashboard.time.DashboardTime
import com.team376.pulsemetry.persistence.telemetry.DashboardClickHouseReader
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

@RestController
@RequestMapping("/v1/meta")
class DashboardObservedMeta(private val reader: DashboardClickHouseReader, private val mapper: ObjectMapper,
    private val access: DashboardAccess, private val directory: DashboardDirectory, private val jdbc: JdbcClient, private val clock: Clock) {
    private fun observed(user: UserIdentity, from: String, to: String): List<Map<String, String>> {
        val zone = jdbc.sql("SELECT timezone FROM enrollment.tenants WHERE id=:tenant").param("tenant", user.tenantId)
            .query(String::class.java).single()
        val time = DashboardTime(clock.instant(), ZoneId.of(zone))
        val start = time.resolve(from)
        val end = time.resolve(to)
        require(start < end)
        val teams = access.teamIds(user)
        if (user.role == "admin" && teams.isEmpty()) return emptyList()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        val result = reader.query("""SELECT DISTINCT product,
            coalesce(nullIf(JSONExtractString(raw_json,'payload','model'),''),JSONExtractString(raw_json,'point','attrs','model')) AS model
            FROM enriched_events FINAL WHERE tenant_id={tenant:String}
            AND ts>={from:DateTime} AND ts<{to:DateTime}
            AND ({owner:UInt8}=1 OR hasAny(team_ids_as_of,{teams:Array(String)})) ORDER BY product,model""",
            mapOf("tenant" to user.tenantId.toString(), "from" to formatter.format(start), "to" to formatter.format(end),
                "owner" to if (user.role == "owner") "1" else "0", "teams" to teams.joinToString(",", "[", "]") { "'$it'" }))
        return mapper.readTree(result)["data"].toList().map { row ->
            val model = row["model"].asString()
            val vendor = when {
                model.startsWith("claude") -> "anthropic"
                model.startsWith("gpt") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4") || model.startsWith("codex") -> "openai"
                model.startsWith("gemini") -> "google"
                else -> "unknown"
            }
            mapOf("product" to row["product"].asString(), "model" to model, "vendor" to vendor)
        }
    }
    @GetMapping("/models") fun models(@AuthenticationPrincipal user: UserIdentity,
        @RequestParam(defaultValue = "now-7d") from: String, @RequestParam(defaultValue = "now") to: String) =
        mapOf("items" to observed(user, from, to).filter { it["model"] != "" })

    @GetMapping("/filters") fun filters(@AuthenticationPrincipal user: UserIdentity,
        @RequestParam(defaultValue = "now-7d") from: String, @RequestParam(defaultValue = "now") to: String): Map<String, Any> {
        val rows = observed(user, from, to)
        return mapOf("teams" to directory.teams(user, false),
            "products" to rows.mapNotNull { it["product"] }.distinct(), "models" to rows.filter { it["model"] != "" },
            "time_presets" to listOf("now-24h", "now-7d", "now-28d", "now-90d"), "price_bases" to listOf("list", "contract"))
    }
}
