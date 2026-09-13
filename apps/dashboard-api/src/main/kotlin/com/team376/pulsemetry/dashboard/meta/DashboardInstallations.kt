package com.team376.pulsemetry.dashboard.meta

import com.team376.pulsemetry.dashboard.api.decodeCursor
import com.team376.pulsemetry.dashboard.api.encodeCursor
import com.team376.pulsemetry.dashboard.auth.DashboardAccess
import com.team376.pulsemetry.persistence.telemetry.DashboardClickHouseReader
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** P3 설치 식별자 조회는 ADR 0019에 따라 owner·감사 후 수행한다. */
@RestController
@RequestMapping("/v1")
class DashboardInstallations(private val jdbc: JdbcClient, private val reader: DashboardClickHouseReader,
    private val mapper: ObjectMapper, private val access: DashboardAccess, private val clock: Clock) {
    @GetMapping("/installations")
    fun list(@AuthenticationPrincipal user: UserIdentity,
        @RequestHeader(value="X-Audit-Reason",required=false) audit: String?,
        @RequestParam(name="inactive_days",required=false) inactiveDays: Int?,
        @RequestParam(name="team_id",required=false) teamId: UUID?,
        @RequestParam(required=false) platform: String?, @RequestParam(required=false) status: String?,
        @RequestParam(defaultValue="50") limit: Int, @RequestParam(required=false) cursor: String?): Map<String,Any?> {
        val deadline = System.nanoTime()+Duration.ofSeconds(30).toNanos()
        require(limit in 1..500 && (inactiveDays==null || inactiveDays>=1))
        require(platform==null || platform in setOf("windows","macos","linux"))
        require(status==null || status in setOf("active","revoked"))
        val after = cursor?.takeIf { it.isNotEmpty() }?.let(::decodeCursor)
        access.personal(user,audit,"installations",teamId?.toString() ?: "all")
        access.teams(user,setOfNotNull(teamId))
        val now = clock.instant()
        val candidates = jdbc.sql("""SELECT i.id,i.hostname,i.platform::text,i.client_version,i.status::text,
            i.created_at,i.last_seen_at,m.email,
            ARRAY(SELECT tm.team_id::text FROM enrollment.team_memberships tm
                JOIN enrollment.teams t ON t.id=tm.team_id AND t.tenant_id=i.tenant_id
                WHERE tm.member_id=m.id AND tm.left_at IS NULL ORDER BY tm.team_id) AS team_ids
            FROM enrollment.installations i JOIN enrollment.members m ON m.id=i.member_id AND m.tenant_id=i.tenant_id
            WHERE i.tenant_id=:tenant AND (:first OR i.id>:after)
                AND (:allPlatforms OR i.platform::text=:platform) AND (:allStatuses OR i.status::text=:status)
                AND (:allTeams OR EXISTS (SELECT 1 FROM enrollment.team_memberships tm
                    WHERE tm.member_id=m.id AND tm.team_id=:team AND tm.left_at IS NULL))
            ORDER BY i.id LIMIT 5001""")
            .param("tenant",user.tenantId).param("first",after==null).param("after",after ?: UUID(0,0))
            .param("allPlatforms",platform==null).param("platform",platform ?: "")
            .param("allStatuses",status==null).param("status",status ?: "")
            .param("allTeams",teamId==null).param("team",teamId ?: UUID(0,0)).query().listOfRows()
        // 무활동 필터 전에 잘라 빈 페이지나 잘못된 next_cursor를 만들지 않는다.
        if (candidates.size>5000) throw DashboardReadException("query_too_wide",422)
        if (candidates.isEmpty()) return mapOf("items" to emptyList<Any>(),"next_cursor" to null,"total" to null)
        val remaining = deadline-System.nanoTime()
        if (remaining<=0) throw DashboardReadException("query_timeout",503)
        val telemetry = mapper.readTree(reader.query("""SELECT installation_id,product,
            toUnixTimestamp(max(ts)) AS last_ts,
            argMax(JSONExtractString(raw_json,'envelope','client','version'),tuple(ts,event_id)) AS version
            FROM enriched_events FINAL WHERE tenant_id={tenant:String}
                AND has({installations:Array(String)},installation_id) AND toUnixTimestamp(ts)<={now:UInt64}
            GROUP BY installation_id,product ORDER BY installation_id,product""",
            mapOf("tenant" to user.tenantId.toString(),"now" to now.epochSecond.toString(),
                "installations" to candidates.joinToString(",","[","]") { "'${it["id"]}'" }),Duration.ofNanos(remaining)))["data"]
            .toList().groupBy { it["installation_id"].asString() }
        val cutoff = inactiveDays?.let { now.minus(Duration.ofDays(it.toLong())) }
        val items = candidates.mapNotNull { row ->
            val observations = telemetry[row["id"].toString()].orEmpty()
            val last = observations.maxOfOrNull { it["last_ts"].asLong() }?.let(Instant::ofEpochSecond)
            val created = requireNotNull(instant(row["created_at"]))
            if (cutoff!=null && (last ?: created)>cutoff) null else mapOf(
                "installation_id" to row["id"].toString(),
                "member_email_masked" to (row["email"].toString().substringAfterLast('@',"").takeIf { it.isNotEmpty() }?.let { "***@$it" } ?: "***"),
                "team_ids" to ((row["team_ids"] as java.sql.Array).array as Array<*>).toList(),
                "hostname" to row["hostname"],"platform" to row["platform"],"client_version" to row["client_version"],
                "status" to row["status"],"created_at" to created.toString(),
                "last_seen_at" to instant(row["last_seen_at"])?.toString(),
                "last_event_at" to last?.toString(),
                "product_versions" to observations.filter { it["version"].asString().isNotEmpty() }
                    .associate { it["product"].asString() to it["version"].asString() })
        }
        val page = items.take(limit)
        return mapOf("items" to page,"next_cursor" to if (items.size>limit) encodeCursor(page.last().getValue("installation_id").toString()) else null,
            "total" to null)
    }
    private fun instant(value: Any?): Instant? = when (value) {
        null -> null
        is OffsetDateTime -> value.toInstant()
        is java.sql.Timestamp -> value.toInstant()
        else -> throw IllegalStateException("unexpected_timestamp_type")
    }

}
