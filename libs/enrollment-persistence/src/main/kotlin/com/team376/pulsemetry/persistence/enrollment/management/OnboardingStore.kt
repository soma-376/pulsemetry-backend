package com.team376.pulsemetry.persistence.enrollment.management

import com.team376.pulsemetry.persistence.enrollment.mail.MailDeliveryView
import com.team376.pulsemetry.persistence.enrollment.operation.RetentionCleanupRequests
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 쓰기는 ManagementStore의 조직 잠금·트랜잭션 안에서 실행한다. */
class OnboardingStore(private val jdbc: JdbcClient, private val mapper: ObjectMapper,
    private val initialManifest: () -> JsonNode,
    /** 집계 보존을 줄인 저장이 남기는 보존 정리 요청(ADR 0047). */
    private val cleanups: RetentionCleanupRequests,
    /** 초대 메일을 적재하는 배포인가. 메일이 없는 초대의 발송 상태 사유를 가른다. */
    private val mailEnabled: Boolean = false) {
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

    /**
     * 수집 정책 저장. 원문 선택(`collectRawContent`)은 새 manifest 판을 만들고, 회수 기준·집계 보존은 manifest 와 따로 저장한다 —
     * 설치에 배포하는 정책이 아니라 판을 올리지 않는다(ADR 0046). 보낸 필드만 바꾸고 보내지 않은 값은 그대로 둔다.
     * `expectedVersion` 은 언제나 manifest 판이고, 회수 기준·집계 보존을 보낼 때는 그 설정의 판(`expectedSettingsVersion`)도 맞아야 한다.
     */
    fun savePolicy(tenant: UUID, actor: UUID, body: JsonNode, now: Instant): JsonNode {
        val choice = body.path("collectRawContent")
        val expected = body.path("expectedVersion")
        val changesChoice = !choice.isMissingNode
        val changesReclaim = body.has("reclaimIdleDays")
        val changesRetention = body.has("aggregateRetentionMonths")
        if ((changesChoice && !choice.isBoolean) || !expected.isIntegralNumber || !expected.canConvertToLong()) throw ManagementException("invalid_request", 400)
        if (!changesChoice && !changesReclaim && !changesRetention) throw ManagementException("invalid_request", 400)
        val reclaim = if (changesReclaim) option(body.path("reclaimIdleDays"), OrganizationPolicySettings.RECLAIM_IDLE_DAYS, nullable = false, "reclaimIdleDays") else null
        val retention = if (changesRetention) option(body.path("aggregateRetentionMonths"), OrganizationPolicySettings.AGGREGATE_RETENTION_MONTHS, nullable = true, "aggregateRetentionMonths") else null
        val expectedSettings = body.path("expectedSettingsVersion")
        val changesSettings = changesReclaim || changesRetention
        if (changesSettings && (!expectedSettings.isIntegralNumber || !expectedSettings.canConvertToLong() || expectedSettings.asLong() < 0))
            throw ManagementException("invalid_request", 400, "expectedSettingsVersion")
        val current = currentManifest(tenant)
        if (expected.asLong() != (current?.first ?: 0L)) throw ManagementException("version_conflict", 409)
        val stored = OrganizationPolicySettings.read(jdbc, tenant, forUpdate = true)
        if (changesSettings && expectedSettings.asLong() != stored.version) throw ManagementException("version_conflict", 409, "expectedSettingsVersion")

        val manifestVersion = if (changesChoice) saveManifest(tenant, actor, current, choice.booleanValue(), now) else current?.first ?: 0L
        val settings = if (changesSettings) saveSettings(tenant, actor, stored,
            if (changesReclaim) reclaim else stored.reclaimIdleDays, if (changesRetention) retention else stored.aggregateRetentionMonths, now) else stored
        // 집계 보존이 바뀌었으면 같은 트랜잭션에서 보존 정리 요청을 정리한다 — 줄였으면 요청과 작업이 생긴다(ADR 0047).
        val cleanup = if (settings.aggregateRetentionMonths != stored.aggregateRetentionMonths)
            cleanups.onRetentionChanged(tenant, actor, stored.aggregateRetentionMonths, settings.aggregateRetentionMonths, now) else null
        val collect = if (changesChoice) choice.booleanValue() else current?.second?.path("privacy")?.let { privacy ->
            privacy.path("collect_user_prompts").booleanValue().takeIf { it == privacy.path("collect_assistant_responses").booleanValue() }
        }
        return node(mapOf("version" to manifestVersion, "collectRawContent" to collect, "confirmedAt" to (if (changesChoice) now else confirmedAt(tenant))?.toString(),
            "application" to "future_enrollments", "existingInstallationsUpdated" to false,
            "reclaimIdleDays" to settings.reclaimIdleDays, "aggregateRetentionMonths" to settings.aggregateRetentionMonths,
            "settingsVersion" to settings.version, "settingsUpdatedAt" to settings.updatedAt?.toString(),
            // 이 저장이 만든 보존 정리 작업. 진행은 작업 상태 조회로 본다.
            "cleanupOperationId" to cleanup))
    }

    /** 허용 목록의 정수(또는 [nullable] 이면 null). 그 밖은 400 이다. */
    private fun option(value: JsonNode, allowed: List<Int>, nullable: Boolean, field: String): Int? {
        if (nullable && value.isNull) return null
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.asInt() !in allowed) throw ManagementException("invalid_request", 400, field)
        return value.asInt()
    }

    private fun confirmedAt(tenant: UUID): Instant? = jdbc.sql("SELECT policy_confirmed_at FROM enrollment.organization_onboarding WHERE tenant_id=:tenant")
        .param("tenant", tenant).query { r, _ -> r.getTimestamp(1)?.toInstant() }.optional().orElse(null)

    /** 원문 선택을 새 manifest 판으로 저장하고 그 판을 돌려준다(정책 확인 시각도 남긴다). 나머지 manifest 는 이전 판 그대로다. */
    private fun saveManifest(tenant: UUID, actor: UUID, current: Pair<Long, JsonNode>?, choice: Boolean, now: Instant): Long {
        val nextVersion = jdbc.sql("SELECT coalesce(max(version),0)+1 FROM enrollment.manifests WHERE tenant_id=:tenant")
            .param("tenant", tenant).query(Int::class.java).single()
        val next = (current?.second ?: initialManifest()).deepCopy() as? ObjectNode
            ?: throw ManagementException("manifest_not_configured", 409)
        val privacy = next.path("privacy") as? ObjectNode ?: throw ManagementException("manifest_not_configured", 409)
        privacy.put("collect_user_prompts", choice)
        privacy.put("collect_assistant_responses", choice)
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
        return nextVersion.toLong()
    }

    /** 값이 실제로 바뀔 때만 판을 올린다 — 같은 값을 다시 보내면 같은 판이다. */
    private fun saveSettings(tenant: UUID, actor: UUID, stored: OrganizationPolicySettings, reclaim: Int?, retention: Int?, now: Instant): OrganizationPolicySettings {
        if (reclaim == stored.reclaimIdleDays && retention == stored.aggregateRetentionMonths) return stored
        val saved = OrganizationPolicySettings(reclaim, retention, stored.version + 1, now, actor)
        jdbc.sql("""INSERT INTO enrollment.organization_policy_settings(tenant_id,reclaim_idle_days,aggregate_retention_months,version,updated_at,updated_by)
            VALUES (:tenant,:reclaim,:retention,:version,:now,:actor) ON CONFLICT (tenant_id) DO UPDATE
            SET reclaim_idle_days=excluded.reclaim_idle_days,aggregate_retention_months=excluded.aggregate_retention_months,
                version=excluded.version,updated_at=excluded.updated_at,updated_by=excluded.updated_by""")
            .param("tenant", tenant).param("reclaim", reclaim, java.sql.Types.SMALLINT).param("retention", retention, java.sql.Types.SMALLINT)
            .param("version", saved.version).param("now", Timestamp.from(now)).param("actor", actor).update()
        return saved
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
                         WHEN i.used_at IS NOT NULL THEN 'used'
                         WHEN i.expires_at <= :now THEN 'expired' ELSE 'pending' END AS status,
                    team.open_count,team.team_id,team.team_name,
                    o.status AS mail_status,o.attempts AS mail_attempts,o.queued_at AS mail_queued_at,o.last_attempt_at AS mail_last_attempt_at,
                    o.finished_at AS mail_finished_at,o.failure_code AS mail_failure_code
                FROM enrollment.invitations i
                JOIN enrollment.members m ON m.id=i.target_member_id AND m.tenant_id=i.tenant_id
                LEFT JOIN enrollment.mail_outbox o ON o.dedup_key='invitation:' || i.id::text
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
                // 초대의 발급 상태와 메일의 발송 상태는 다른 사실이다(ADR 0038).
                val delivery = r.getString("mail_status")?.let { status ->
                    MailDeliveryView.of(status, r.getInt("mail_attempts"), r.getTimestamp("mail_queued_at").toInstant(), r.getTimestamp("mail_last_attempt_at")?.toInstant(),
                        r.getTimestamp("mail_finished_at")?.toInstant(), r.getString("mail_failure_code"))
                } ?: MailDeliveryView.notSent(mailEnabled)
                mapOf("invitationId" to r.getString("id"), "email" to r.getString("email"), "role" to r.getString("role"),
                    "createdAt" to r.getTimestamp("created_at").toInstant().toString(), "expiresAt" to r.getTimestamp("expires_at").toInstant().toString(),
                    "installationUsedAt" to r.getTimestamp("used_at")?.toInstant()?.toString(), "signupUsedAt" to r.getTimestamp("signup_used_at")?.toInstant()?.toString(),
                    "revokedAt" to r.getTimestamp("revoked_at")?.toInstant()?.toString(), "status" to r.getString("status"),
                    "memberId" to r.getString("member_id"), "memberStatus" to r.getString("member_status"), "team" to team,
                    "memberVersion" to r.getTimestamp("member_updated_at").toInstant().toEpochMilli(), "delivery" to delivery)
            }.list()
        return node(mapOf("items" to rows.take(limit), "nextCursor" to if (rows.size > limit) rows[limit-1]["invitationId"] else null))
    }
}
