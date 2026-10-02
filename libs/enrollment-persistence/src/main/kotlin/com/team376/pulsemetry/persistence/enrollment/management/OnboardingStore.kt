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

    fun invitations(tenant: UUID, limit: Int, after: UUID?, now: Instant): JsonNode {
        if (limit !in 1..100) throw ManagementException("invalid_request", 400, "limit")
        val rows = jdbc.sql("""SELECT i.*,m.email,m.role::text FROM enrollment.invitations i
            JOIN enrollment.members m ON m.id=i.target_member_id AND m.tenant_id=i.tenant_id
            WHERE i.tenant_id=:tenant AND (CAST(:after AS uuid) IS NULL OR i.id>CAST(:after AS uuid)) ORDER BY i.id LIMIT :limit""")
            .param("tenant", tenant).param("after", after).param("limit", limit + 1).query { r, _ ->
                val used = r.getTimestamp("used_at")?.toInstant()
                val signup = r.getTimestamp("signup_used_at")?.toInstant()
                val revoked = r.getTimestamp("revoked_at")?.toInstant()
                val expires = r.getTimestamp("expires_at").toInstant()
                mapOf("invitationId" to r.getString("id"), "email" to r.getString("email"), "role" to r.getString("role"),
                    "createdAt" to r.getTimestamp("created_at").toInstant().toString(), "expiresAt" to expires.toString(),
                    "installationUsedAt" to used?.toString(), "signupUsedAt" to signup?.toString(), "revokedAt" to revoked?.toString(),
                    "status" to when { revoked != null -> "revoked"; used != null -> "used"; expires <= now -> "expired"; else -> "pending" })
            }.list()
        return node(mapOf("items" to rows.take(limit), "nextCursor" to if (rows.size > limit) rows[limit-1]["invitationId"] else null))
    }
}
