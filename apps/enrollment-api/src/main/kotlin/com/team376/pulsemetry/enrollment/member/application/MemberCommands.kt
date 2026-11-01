package com.team376.pulsemetry.enrollment.member.application

import java.time.Instant
import java.util.UUID

import com.team376.pulsemetry.persistence.enrollment.management.*
import com.team376.pulsemetry.persistence.enrollment.directory.DirectoryCommandStore
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.CommandRules.checkVersion
import com.team376.pulsemetry.enrollment.management.CommandRules.advance
import com.team376.pulsemetry.enrollment.management.CommandRules.fail

/** 기능의 명시적 관리 명령. 공통 실행 관문의 트랜잭션 안에서 호출한다. */
class MemberCommands(private val directory: DirectoryCommandStore) {
    fun editMember(tenant: UUID, actor: UUID, raw: String, command: MemberChange, now: Instant): Map<String, Any?> {
        val id = uuid(raw)
        val old = directory.lockMember(tenant, id) ?: fail("not_found", 404, "memberId")
        if (old.second == "suspended") fail("member_suspended", 409)
        checkVersion(old.third, command.expectedVersion)
        // 보내지 않은 필드는 바꾸지 않는다. teamId: null 은 미배정이다.
        if (!command.changesTeam && command.role == null && command.plannedVendors == null) fail("invalid_request", 400)
        val openTeams = directory.openTeams(id)
        var teamChanged = false
        var team: UUID? = null
        if (command.changesTeam) {
            team = command.teamId
            team?.let { team(tenant, it) }
            teamChanged = openTeams != setOfNotNull(team)
        }
        var role = old.first
        if (command.role != null) {
            val requested = command.role
            if (requested != old.first) {
                com.team376.pulsemetry.enrollment.member.domain.MemberRoles.rejection(old.first, requested, id, actor)
                    ?.let { fail(it, 422, "role") }
                role = requested
            }
        }
        val previousVendors = directory.plannedVendors(id)
        val vendors = command.plannedVendors ?: previousVendors
        if (!validPlannedVendors(tenant, vendors - previousVendors.toSet())) fail("vendor_not_found", 422, "plannedVendorIds")
        var updated = old.third
        if (teamChanged || role != old.first || vendors.toSet() != previousVendors.toSet()) {
            if (teamChanged) moveTeam(id, team, now)
            updated = advance(now, old.third)
            directory.updateMember(vendors, role, updated, id)
        }
        // 열린 소속이 하나일 때만 현재 팀으로 본다.
        val current = directory.currentTeam(id)
        return mapOf("memberId" to id, "team" to current, "role" to role, "status" to old.second, "version" to updated.toEpochMilli(), "plannedVendorIds" to vendors)
    }


    private fun team(tenant: UUID, id: UUID): Instant = directory.lockTeamVersion(tenant, id) ?: fail("not_found", 404, "teamId")

    private fun moveTeam(member: UUID, team: UUID?, now: Instant) {
        directory.closeMemberMemberships(now, member)
        if (team != null) addMembership(member, team, now)
    }


    private fun addMembership(member: UUID, team: UUID, now: Instant) {
        directory.insertMembership(member, team, now)
    }

    private fun validPlannedVendors(tenant: UUID, ids: List<String>): Boolean = ids.sorted().all { id ->
        directory.lockRegisteredVendor(tenant, id)
    }
}

data class MemberChange(val expectedVersion: Long, val changesTeam: Boolean, val teamId: UUID?, val role: String?, val plannedVendors: List<String>?)
