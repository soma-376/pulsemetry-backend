package com.team376.pulsemetry.enrollment.policy.application

import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.persistence.enrollment.management.OnboardingStore
import com.team376.pulsemetry.persistence.enrollment.management.OrganizationPolicySettings
import com.team376.pulsemetry.persistence.enrollment.operation.RetentionCleanupRequests
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

data class PolicyChange(
    val expectedVersion: Long, val collectRawContent: Boolean?,
    val changesReclaim: Boolean, val reclaimIdleDays: Int?,
    val changesRetention: Boolean, val aggregateRetentionMonths: Int?, val expectedSettingsVersion: Long?,
)

/** 정책 판과 보존 정리 요청을 관리 명령의 같은 트랜잭션에서 조정한다. */
class PolicyCommands(
    private val store: OnboardingStore,
    private val cleanups: RetentionCleanupRequests,
    private val initialManifest: () -> JsonNode,
) {
    fun save(tenant: UUID, actor: UUID, command: PolicyChange, now: Instant): Map<String, Any?> {
        val changesChoice = command.collectRawContent != null
        val changesSettings = command.changesReclaim || command.changesRetention
        if (!changesChoice && !changesSettings) throw ManagementException("invalid_request", 400)
        val current = store.currentManifest(tenant)
        if (command.expectedVersion != (current?.first ?: 0L)) throw ManagementException("version_conflict", 409)
        val stored = store.lockSettings(tenant)
        if (changesSettings && command.expectedSettingsVersion != stored.version) throw ManagementException("version_conflict", 409, "expectedSettingsVersion")

        val manifestVersion = if (changesChoice) {
            val nextVersion = store.nextManifestVersion(tenant)
            val next = (current?.second ?: initialManifest()).deepCopy() as? ObjectNode
                ?: throw ManagementException("manifest_not_configured", 409)
            val privacy = next.path("privacy") as? ObjectNode ?: throw ManagementException("manifest_not_configured", 409)
            privacy.put("collect_user_prompts", command.collectRawContent!!)
            privacy.put("collect_assistant_responses", command.collectRawContent)
            next.put("config_revision", nextVersion)
            store.replaceManifest(tenant, actor, nextVersion, next, now)
            nextVersion.toLong()
        } else current?.first ?: 0L

        val reclaim = if (command.changesReclaim) command.reclaimIdleDays else stored.reclaimIdleDays
        val retention = if (command.changesRetention) command.aggregateRetentionMonths else stored.aggregateRetentionMonths
        val settings = if (changesSettings && (reclaim != stored.reclaimIdleDays || retention != stored.aggregateRetentionMonths)) {
            OrganizationPolicySettings(reclaim, retention, stored.version + 1, now, actor).also { store.writeSettings(tenant, it) }
        } else stored
        val cleanup = if (settings.aggregateRetentionMonths != stored.aggregateRetentionMonths)
            cleanups.onRetentionChanged(tenant, actor, stored.aggregateRetentionMonths, settings.aggregateRetentionMonths, now) else null
        val collect = if (changesChoice) command.collectRawContent else current?.second?.path("privacy")?.let { privacy ->
            privacy.path("collect_user_prompts").booleanValue().takeIf { it == privacy.path("collect_assistant_responses").booleanValue() }
        }
        return mapOf("version" to manifestVersion, "collectRawContent" to collect, "confirmedAt" to (if (changesChoice) now else store.confirmedAt(tenant))?.toString(),
            "application" to "future_enrollments", "existingInstallationsUpdated" to false,
            "reclaimIdleDays" to settings.reclaimIdleDays, "aggregateRetentionMonths" to settings.aggregateRetentionMonths,
            "settingsVersion" to settings.version, "settingsUpdatedAt" to settings.updatedAt?.toString(), "cleanupOperationId" to cleanup)
    }

    fun complete(tenant: UUID, actor: UUID, now: Instant): JsonNode {
        val current = store.state(tenant)
        if (current.path("completed").asBoolean()) return current
        if (!current.path("canComplete").asBoolean()) throw ManagementException("onboarding_incomplete", 409)
        store.markCompleted(tenant, actor, now)
        return store.state(tenant)
    }
}
