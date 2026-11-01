package com.team376.pulsemetry.enrollment.team.application

import java.time.Instant
import java.util.UUID

import com.team376.pulsemetry.persistence.enrollment.management.*
import com.team376.pulsemetry.persistence.enrollment.directory.DirectoryCommandStore
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.CommandRules.checkVersion
import com.team376.pulsemetry.enrollment.management.CommandRules.advance
import com.team376.pulsemetry.enrollment.management.CommandRules.fail

/** 기능의 명시적 관리 명령. 공통 실행 관문의 트랜잭션 안에서 호출한다. */
class TeamCommands(private val directory: DirectoryCommandStore) {
    fun createTeam(tenant: UUID, command: TeamChange, now: Instant): Map<String, Any?> {
        val name = teamName(tenant, command.name, null)
        val id = UUID.randomUUID()
        directory.insertTeam(id, tenant, name, now)
        return mapOf("teamId" to id, "teamName" to name, "version" to now.toEpochMilli())
    }

    private fun teamName(tenant: UUID, requested: String, except: UUID?): String {
        val name = requested
        val names = directory.teamNames(tenant)
        if (names.any { it.first != except && com.team376.pulsemetry.enrollment.team.domain.TeamNames.same(it.second, name) }) fail("team_name_conflict", 409, "teamName")
        return name
    }

    fun editTeam(tenant: UUID, raw: String, command: TeamChange, now: Instant): Map<String, Any?> {
        val id = uuid(raw)
        val old = team(tenant, id)
        checkVersion(old, command.expectedVersion!!)
        val name = teamName(tenant, command.name, id)
        val updated = advance(now, old)
        directory.renameTeam(name, updated, tenant, id)
        return mapOf("teamId" to id, "teamName" to name, "version" to updated.toEpochMilli())
    }

    fun archiveTeam(tenant: UUID, raw: String, version: Long?, now: Instant): Map<String, Any?> {
        val id = uuid(raw)
        val old = team(tenant, id)
        checkVersion(old, version ?: fail("invalid_request", 400, "If-Match"))
        directory.advanceTeamMembers(now, tenant, id)
        directory.closeTeamMemberships(now, id)
        directory.archiveTeam(advance(now, old), tenant, id)
        return emptyMap<String, String>()
    }

    private fun team(tenant: UUID, id: UUID): Instant = directory.lockTeamVersion(tenant, id) ?: fail("not_found", 404, "teamId")

    fun assignTeams(tenant: UUID, command: List<TeamAssignment>, now: Instant): Map<String, Any?> {
        val items = command
        val seen = mutableSetOf<UUID>()
        val changes = items.map { item ->
            val member = item.memberId
            if (!seen.add(member)) fail("invalid_request", 400, "assignments")
            val team = item.teamId
            team?.let { team(tenant, it) }
            val updated = directory.lockAssignableMemberVersion(tenant, member) ?: fail("not_found", 404, "memberId")
            checkVersion(updated, item.expectedVersion)
            Triple(member, team, advance(now, updated))
        }
        changes.forEach { (member, team, updated) ->
            moveTeam(member, team, now)
            directory.updateMemberVersion(updated, member)
        }
        return mapOf("effectiveAt" to now.toString(), "members" to changes.map { (member, team, updated) -> mapOf("memberId" to member, "teamId" to team, "version" to updated.toEpochMilli()) })
    }

    private fun moveTeam(member: UUID, team: UUID?, now: Instant) {
        directory.closeMemberMemberships(now, member)
        if (team != null) addMembership(member, team, now)
    }


    private fun addMembership(member: UUID, team: UUID, now: Instant) {
        directory.insertMembership(member, team, now)
    }
}

data class TeamChange(val name: String, val expectedVersion: Long? = null)
data class TeamAssignment(val memberId: UUID, val teamId: UUID?, val expectedVersion: Long)
