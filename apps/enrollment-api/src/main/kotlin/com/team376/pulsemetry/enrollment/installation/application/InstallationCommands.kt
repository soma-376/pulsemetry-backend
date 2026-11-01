package com.team376.pulsemetry.enrollment.installation.application

import com.team376.pulsemetry.persistence.enrollment.installation.InstallationNotifier
import com.team376.pulsemetry.persistence.enrollment.operation.Operation
import java.time.Clock
import java.util.UUID

import com.team376.pulsemetry.persistence.enrollment.management.*
import com.team376.pulsemetry.persistence.enrollment.directory.DirectoryCommandStore
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationCommandStore
import com.team376.pulsemetry.enrollment.management.CommandRules.fail

/** 기능의 명시적 관리 명령. 공통 실행 관문의 트랜잭션 안에서 호출한다. */
class InstallationCommands(private val installations: InstallationCommandStore, private val directory: DirectoryCommandStore, private val installationNotifier: InstallationNotifier?, private val clock: Clock) {
    fun notifyInstallations(tenant: UUID, actor: UUID, command: InstallationNoticeCommand): Map<String, Any?> {
        val notifier = installationNotifier ?: fail("notification_channel_unavailable", 422)
        val ids = command.installationIds
        if (ids.toSet().size != ids.size) fail("invalid_request", 400, "installationIds")
        val expected = command.expectedPolicyVersion
        if (expected < 1) fail("invalid_request", 400, "expectedPolicyVersion")
        val active = installations.activePolicyVersion(tenant)
        if (active != expected) fail("version_conflict", 409, "expectedPolicyVersion")
        val rows = installations.notificationTargets(tenant, ids, expected)
        if (rows.size != ids.size) fail("not_found", 404, "installationIds")
        if (rows.any { !it.first }) fail("installation_unavailable", 409, "installationIds")
        val organization = directory.tenantName(tenant)
        val order = ids.withIndex().associate { it.value to it.index }
        val notices = rows.map { it.second.copy(organization = organization) }.sortedBy { order.getValue(it.installationId) }
        return operationNode(notifier.notify(tenant, actor, notices))
    }

    private fun operationNode(operation: Operation): Map<String, Any?> = mapOf(
        "operationId" to operation.id, "kind" to operation.kind.wire, "status" to operation.status.wire,
        "createdAt" to operation.createdAt.toString(), "completedAt" to operation.completedAt?.toString(),
        "results" to operation.targets.map { mapOf("targetId" to it.targetId, "status" to it.status.wire, "reason" to it.reason, "action" to it.action) },
        "canRestore" to operation.canRestore(clock.instant()), "restoreUntil" to operation.restoreUntil?.toString(), "retention" to null,
    )
}

data class InstallationNoticeCommand(val installationIds: List<UUID>, val expectedPolicyVersion: Long)
