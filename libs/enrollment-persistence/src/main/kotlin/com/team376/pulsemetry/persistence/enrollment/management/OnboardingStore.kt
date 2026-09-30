package com.team376.pulsemetry.persistence.enrollment.management

import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 쓰기는 ManagementStore의 조직 잠금·트랜잭션 안에서 실행한다. */
class OnboardingStore(private val jdbc: JdbcClient, private val mapper: ObjectMapper,
    private val initialManifest: () -> JsonNode) {
    private fun node(value: Any): JsonNode = mapper.valueToTree(value)
    fun state(tenant: UUID): JsonNode {
        val saved = jdbc.sql("""SELECT o.policy_confirmed_at,t.onboarding_completed_at
            FROM enrollment.tenants t LEFT JOIN enrollment.organization_onboarding o ON o.tenant_id=t.id
            WHERE t.id=:tenant""")
            .param("tenant", tenant).query { r, _ -> r.getTimestamp(1)?.toInstant() to r.getTimestamp(2)?.toInstant() }.optional().orElse(null)
        val manifest = currentManifest(tenant)
        val privacy = manifest?.second?.path("privacy")
        val prompts = privacy?.path("collect_user_prompts")?.booleanValue()
        val responses = privacy?.path("collect_assistant_responses")?.booleanValue()
        val selected = jdbc.sql("""SELECT count(*) FROM enrollment.managed_vendors v JOIN LATERAL (
            SELECT archived FROM enrollment.vendor_contract_versions WHERE tenant_id=v.tenant_id AND vendor_id=v.vendor_id
            ORDER BY version DESC LIMIT 1) c ON true WHERE v.tenant_id=:tenant AND NOT c.archived""")
            .param("tenant", tenant).query(Int::class.java).single()
        val confirmed = saved?.first != null
        val completed = saved?.second != null
        return node(mapOf("organizationId" to tenant, "completed" to completed, "completedAt" to saved?.second?.toString(),
            "policy" to mapOf("confirmed" to confirmed, "confirmedAt" to saved?.first?.toString(), "version" to (manifest?.first ?: 0),
                "collectRawContent" to if (prompts == responses) prompts else null),
            "selectedVendorCount" to selected, "canComplete" to (confirmed && manifest != null && selected > 0),
            "nextStep" to when { completed -> "complete"; !confirmed -> "collection"; selected == 0 -> "vendors"; else -> "team" }))
    }

    private fun currentManifest(tenant: UUID): Pair<Long, JsonNode>? = jdbc.sql("SELECT version,manifest::text FROM enrollment.manifests WHERE tenant_id=:tenant AND is_active ORDER BY version DESC LIMIT 1")
        .param("tenant", tenant).query { r, _ -> r.getLong(1) to mapper.readTree(r.getString(2)) }.optional().orElse(null)

    fun savePolicy(tenant: UUID, actor: UUID, body: JsonNode, now: Instant): JsonNode {
        val choice = body.path("collectRawContent")
        val expected = body.path("expectedVersion")
        if (!choice.isBoolean || !expected.isIntegralNumber || !expected.canConvertToLong()) throw ManagementException("invalid_request", 400)
        val current = currentManifest(tenant)
        if (expected.asLong() != (current?.first ?: 0L)) throw ManagementException("version_conflict", 409)
        val nextVersion = jdbc.sql("SELECT coalesce(max(version),0)+1 FROM enrollment.manifests WHERE tenant_id=:tenant")
            .param("tenant", tenant).query(Int::class.java).single()
        val next = (current?.second ?: initialManifest()).deepCopy() as? ObjectNode
            ?: throw ManagementException("manifest_not_configured", 409)
        val privacy = next.path("privacy") as? ObjectNode ?: throw ManagementException("manifest_not_configured", 409)
        privacy.put("collect_user_prompts", choice.booleanValue())
        privacy.put("collect_assistant_responses", choice.booleanValue())
        next.put("config_revision", nextVersion)
        jdbc.sql("UPDATE enrollment.manifests SET is_active=false WHERE tenant_id=:tenant AND is_active").param("tenant", tenant).update()
        jdbc.sql("""INSERT INTO enrollment.manifests(id,tenant_id,version,manifest,is_active,created_by_member_id,created_at,activated_at)
            VALUES (:id,:tenant,:version,CAST(:manifest AS jsonb),true,:actor,:now,:now)""")
            .param("id", UUID.randomUUID()).param("tenant", tenant).param("version", nextVersion).param("manifest", next.toString())
            .param("actor", actor).param("now", Timestamp.from(now)).update()
        jdbc.sql("""INSERT INTO enrollment.organization_onboarding(tenant_id,policy_confirmed_at,policy_confirmed_by)
            VALUES (:tenant,:now,:actor) ON CONFLICT (tenant_id) DO UPDATE
            SET policy_confirmed_at=excluded.policy_confirmed_at,policy_confirmed_by=excluded.policy_confirmed_by""")
            .param("tenant", tenant).param("now", Timestamp.from(now)).param("actor", actor).update()
        return node(mapOf("version" to nextVersion, "collectRawContent" to choice.booleanValue(), "confirmedAt" to now.toString(),
            "application" to "future_enrollments", "existingInstallationsUpdated" to false))
    }

    fun complete(tenant: UUID, actor: UUID, now: Instant): JsonNode {
        val current = state(tenant)
        if (current.path("completed").asBoolean()) return current
        if (!current.path("canComplete").asBoolean()) throw ManagementException("onboarding_incomplete", 409)
        jdbc.sql("UPDATE enrollment.tenants SET onboarding_completed_at=:now WHERE id=:tenant")
            .param("now", Timestamp.from(now)).param("tenant", tenant).update()
        jdbc.sql("UPDATE enrollment.organization_onboarding SET completed_by=:actor WHERE tenant_id=:tenant")
            .param("actor", actor).param("tenant", tenant).update()
        return state(tenant)
    }

    /** 상태는 SQL에서 정해 필터와 페이지가 같은 기준을 쓴다. 우선순위: revoked → used → expired → pending. */
    fun invitations(tenant: UUID, limit: Int, after: UUID?, status: String?, memberStatus: String?, now: Instant): JsonNode {
        if (limit !in 1..100) throw ManagementException("invalid_request", 400, "limit")
        if (status != null && status !in setOf("pending", "expired", "used", "revoked")) throw ManagementException("invalid_request", 400, "status")
        if (memberStatus != null && memberStatus !in setOf("invited", "active", "suspended")) throw ManagementException("invalid_request", 400, "memberStatus")
        val rows = jdbc.sql("""SELECT * FROM (
                SELECT i.id,i.created_at,i.expires_at,i.used_at,i.signup_used_at,i.revoked_at,
                    m.id AS member_id,m.email,m.role::text AS role,m.status::text AS member_status,m.updated_at AS member_updated_at,
                    CASE WHEN i.revoked_at IS NOT NULL THEN 'revoked'
                         WHEN i.used_at IS NOT NULL AND i.signup_used_at IS NOT NULL THEN 'used'
                         WHEN i.expires_at <= :now THEN 'expired' ELSE 'pending' END AS status,
                    team.open_count,team.team_id,team.team_name
                FROM enrollment.invitations i
                JOIN enrollment.members m ON m.id=i.target_member_id AND m.tenant_id=i.tenant_id
                LEFT JOIN LATERAL (SELECT count(*) AS open_count,min(t.id::text) AS team_id,min(t.name) AS team_name
                    FROM enrollment.team_memberships tm JOIN enrollment.teams t ON t.id=tm.team_id
                    WHERE tm.member_id=m.id AND tm.left_at IS NULL) team ON true
                WHERE i.tenant_id=:tenant AND (CAST(:after AS uuid) IS NULL OR i.id>CAST(:after AS uuid))
            ) listed WHERE (CAST(:status AS text) IS NULL OR listed.status=CAST(:status AS text))
                AND (CAST(:memberStatus AS text) IS NULL OR listed.member_status=CAST(:memberStatus AS text)) ORDER BY listed.id LIMIT :limit""")
            .param("tenant", tenant).param("after", after).param("status", status).param("memberStatus", memberStatus).param("now", Timestamp.from(now))
            .param("limit", limit + 1).query { r, _ ->
                // 열린 소속이 하나일 때만 현재 팀으로 본다.
                val team = if (r.getLong("open_count") == 1L) mapOf("teamId" to r.getString("team_id"), "teamName" to r.getString("team_name")) else null
                mapOf("invitationId" to r.getString("id"), "email" to r.getString("email"), "role" to r.getString("role"),
                    "createdAt" to r.getTimestamp("created_at").toInstant().toString(), "expiresAt" to r.getTimestamp("expires_at").toInstant().toString(),
                    "installationUsedAt" to r.getTimestamp("used_at")?.toInstant()?.toString(), "signupUsedAt" to r.getTimestamp("signup_used_at")?.toInstant()?.toString(),
                    "revokedAt" to r.getTimestamp("revoked_at")?.toInstant()?.toString(), "status" to r.getString("status"),
                    "memberId" to r.getString("member_id"), "memberStatus" to r.getString("member_status"), "team" to team,
                    "memberVersion" to r.getTimestamp("member_updated_at").toInstant().toEpochMilli())
            }.list()
        return node(mapOf("items" to rows.take(limit), "nextCursor" to if (rows.size > limit) rows[limit-1]["invitationId"] else null))
    }
}
