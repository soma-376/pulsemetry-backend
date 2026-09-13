package com.team376.pulsemetry.dashboard.meta

import com.team376.pulsemetry.dashboard.api.decodeCursor
import com.team376.pulsemetry.dashboard.api.encodeCursor
import com.team376.pulsemetry.dashboard.auth.DashboardAccess
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** 조직 데이터는 tenant 및 현재 팀 범위를 SQL 단계에서 제한한다. */
@RestController
@RequestMapping("/v1/meta")
class DashboardMeta(private val jdbc: JdbcClient, private val access: DashboardAccess, private val mapper: ObjectMapper,
    private val directory: DashboardDirectory) {
    @GetMapping("/teams") fun teams(@AuthenticationPrincipal user: UserIdentity,
        @RequestParam(defaultValue = "false", name = "include_archived") archived: Boolean): Map<String, Any> =
        mapOf("items" to directory.teams(user, archived))
    @GetMapping("/members") fun members(@AuthenticationPrincipal user: UserIdentity,
        @RequestParam(name = "team_id", required = false) team: UUID?,
        @RequestParam(required = false) status: String?, @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @RequestHeader(name = "X-Audit-Reason", required = false) reason: String?): Map<String, Any?> {
        access.personal(user, reason, "members.read", "members")
        require(limit in 1..500 && (status == null || status in setOf("active", "invited", "suspended")))
        if (team != null) access.teams(user, setOf(team))
        val after = cursor?.let { decodeCursor(it) } ?: UUID(0,0)
        val rows = jdbc.sql("""SELECT m.id AS member_id,m.email,m.display_name,m.role::text,m.status::text,
            COALESCE((SELECT jsonb_agg(tm.team_id ORDER BY tm.team_id) FROM enrollment.team_memberships tm
                JOIN enrollment.teams t ON t.id=tm.team_id WHERE tm.member_id=m.id AND tm.left_at IS NULL
                AND t.tenant_id=:tenant AND (:owner OR EXISTS (SELECT 1 FROM enrollment.team_memberships own
                WHERE own.team_id=t.id AND own.member_id=:member AND own.left_at IS NULL))), '[]'::jsonb)::text AS team_ids_json,
            (SELECT count(*) FROM enrollment.installations i WHERE i.member_id=m.id AND i.tenant_id=:tenant AND i.status='active') AS installation_count,
            (SELECT max(i.last_seen_at)::text FROM enrollment.installations i WHERE i.member_id=m.id AND i.tenant_id=:tenant) AS last_seen_at
            FROM enrollment.members m WHERE m.tenant_id=:tenant AND m.id>:after
            AND (:status IS NULL OR m.status::text=:status)
            AND (CAST(:team AS uuid) IS NULL OR EXISTS (SELECT 1 FROM enrollment.team_memberships tm
                WHERE tm.member_id=m.id AND tm.team_id=:team AND tm.left_at IS NULL))
            AND (:owner OR EXISTS (SELECT 1 FROM enrollment.team_memberships tm
                JOIN enrollment.team_memberships own ON own.team_id=tm.team_id
                JOIN enrollment.teams t ON t.id=tm.team_id
                WHERE tm.member_id=m.id AND own.member_id=:member AND tm.left_at IS NULL
                AND own.left_at IS NULL AND t.tenant_id=:tenant)) ORDER BY m.id LIMIT :limit""")
            .param("tenant", user.tenantId).param("member", user.memberId).param("owner", user.role == "owner")
            .param("status", status, java.sql.Types.VARCHAR).param("team", team, java.sql.Types.OTHER)
            .param("after", after).param("limit", limit + 1).query().listOfRows()
        val items = rows.take(limit).map { row -> row.toMutableMap().apply {
            put("team_ids", mapper.readTree(remove("team_ids_json") as String))
        } }
        return mapOf("items" to items, "next_cursor" to if (rows.size > limit) encodeCursor(items.last()["member_id"].toString()) else null,
            "total" to null)
    }
    @GetMapping("/manifests") fun manifests(@AuthenticationPrincipal user: UserIdentity): Map<String, Any?> {
        val rows = jdbc.sql("""SELECT id AS manifest_id,version,activated_at::text,created_at::text,is_active,
            COALESCE(manifest->'signals','{}'::jsonb)::text AS signals_json FROM enrollment.manifests
            WHERE tenant_id=:tenant ORDER BY version DESC""").param("tenant", user.tenantId).query().listOfRows()
        val active = rows.firstOrNull { it["is_active"] == true }?.toMutableMap()?.apply {
            put("signals", mapper.readTree(remove("signals_json") as String))
            val counts = jdbc.sql("""SELECT count(*) AS assigned_installations,
                count(*) FILTER (WHERE a.applied_at IS NOT NULL) AS applied_installations
                FROM enrollment.installation_manifest_assignments a JOIN enrollment.installations i ON i.id=a.installation_id
                WHERE a.manifest_id=:manifest AND i.tenant_id=:tenant AND (:owner OR EXISTS (
                SELECT 1 FROM enrollment.team_memberships tm JOIN enrollment.team_memberships own ON own.team_id=tm.team_id
                JOIN enrollment.teams t ON t.id=tm.team_id WHERE tm.member_id=i.member_id AND own.member_id=:member
                AND tm.left_at IS NULL AND own.left_at IS NULL AND t.tenant_id=:tenant))""")
                .param("manifest", get("manifest_id")).param("tenant", user.tenantId).param("owner", user.role == "owner")
                .param("member", user.memberId).query().singleRow()
            putAll(counts)
        }
        return mapOf("active" to active, "history" to rows.map { mapOf("version" to it["version"], "created_at" to it["created_at"], "is_active" to it["is_active"]) })
    }
    @GetMapping("/contracts") fun contracts(@AuthenticationPrincipal user: UserIdentity): Map<String, Any> {
        val rows = jdbc.sql("""SELECT c.id AS contract_id,c.vendor::text,c.contract_type::text,c.name,c.contract_no,
            c.starts_at::text,c.ends_at::text,c.status::text,
            (SELECT count(DISTINCT cm.member_id) FROM enrollment.contract_memberships cm
                JOIN enrollment.members m ON m.id=cm.member_id WHERE cm.contract_id=c.id AND cm.released_at IS NULL
                AND m.tenant_id=:tenant AND (:owner OR EXISTS (SELECT 1 FROM enrollment.team_memberships tm
                JOIN enrollment.team_memberships own ON own.team_id=tm.team_id JOIN enrollment.teams t ON t.id=tm.team_id
                WHERE tm.member_id=m.id AND own.member_id=:member AND tm.left_at IS NULL AND own.left_at IS NULL AND t.tenant_id=:tenant))) AS member_count,
            (SELECT jsonb_build_object('commitment_months',tc.commitment_months,'commitment_amount',tc.commitment_amount,
                'currency',tc.currency,'auto_renew',tc.auto_renew)::text FROM enrollment.contract_term_commitments tc WHERE tc.contract_id=c.id) AS commitment_json,
            COALESCE((SELECT jsonb_agg(jsonb_build_object('model_pattern',d.model_pattern,'token_type',d.token_type,
                'discount_rate',d.discount_rate,'effective_from',d.effective_from,'effective_to',d.effective_to))
                FROM enrollment.contract_token_discounts d WHERE d.contract_id=c.id),'[]'::jsonb)::text AS discounts_json
            FROM enrollment.contracts c WHERE c.tenant_id=:tenant ORDER BY c.id""")
            .param("tenant", user.tenantId).param("owner", user.role == "owner").param("member", user.memberId).query().listOfRows()
        return mapOf("items" to rows.map { it.toMutableMap().apply {
            put("term_commitment", (remove("commitment_json") as String?)?.let(mapper::readTree))
            put("token_discounts", mapper.readTree(remove("discounts_json") as String))
        } })
    }
}
