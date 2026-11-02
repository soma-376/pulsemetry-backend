package com.team376.pulsemetry.enrollment.invitation.application

import com.team376.pulsemetry.persistence.enrollment.mail.InvitationMailer
import com.team376.pulsemetry.persistence.enrollment.mail.MailDeliveryView
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

import com.team376.pulsemetry.persistence.enrollment.management.*
import com.team376.pulsemetry.persistence.enrollment.directory.DirectoryCommandStore
import com.team376.pulsemetry.persistence.enrollment.invitation.InvitationCommandStore
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.CommandRules.checkVersion
import com.team376.pulsemetry.enrollment.management.CommandRules.advance
import com.team376.pulsemetry.enrollment.management.CommandRules.hash
import com.team376.pulsemetry.enrollment.management.CommandRules.fail

/** 기능의 명시적 관리 명령. 공통 실행 관문의 트랜잭션 안에서 호출한다. */
class InvitationCommands(private val directory: DirectoryCommandStore, private val invitations: InvitationCommandStore, private val invitationMail: InvitationMailer?) {
    private val random = SecureRandom()
    fun invite(tenant: UUID, actor: UUID, command: List<InvitationInput>, now: Instant): Map<String, Any?> {
        val seen = mutableSetOf<String>()
        val results = command.map { item ->
            val email = item.email
            val role = item.role
            val vendors = item.plannedVendors
            val team = item.teamId
            var reason: String? = null
            if (!Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email) || email.any(Char::isISOControl)) reason = "invalid_email"
            if (!seen.add(email)) reason = "duplicate_email"
            if (role !in setOf("admin", "member")) reason = "role_not_assignable"
            if (team != null && directory.activeTeamCount(tenant, team) == 0) reason = "team_not_found"
            if (!validPlannedVendors(tenant, vendors)) reason = "vendor_not_found"
            val existing = directory.membersByEmail(tenant, email)
            if (existing.size > 1) reason = "ambiguous_email"
            val waiting = existing.firstOrNull()?.takeIf { it.second == "invited" }?.first
            // 초대가 취소돼 남은 초대가 없는 대기자는 같은 구성원으로 다시 초대한다. 만료만 된 초대는 재발급 대상이다.
            val again = reason == null && waiting != null && invitations.unrevokedCount(waiting) == 0
            val status = when { reason != null -> "rejected"; again -> "issued"; waiting != null -> "already_invited"; existing.isNotEmpty() -> "already_member"; else -> "issued" }
            var code: String? = null
            var invitation: UUID? = null
            var delivery: Map<String, Any?>? = null
            if (status == "issued") {
                val member = if (again) waiting!! else UUID.randomUUID()
                if (again) {
                    reinvite(member, role, team, now)
                } else {
                    directory.insertInvitedMember(member, tenant, email, role, now)
                    if (team != null) addMembership(member, team, now)
                }
                directory.updatePlannedVendors(vendors, member)
                code = newCode()
                invitation = UUID.randomUUID()
                invitations.insertInvitation(invitation, tenant, member, actor, hash(code), now.plusSeconds(72 * 3600), now)
                // 발급과 같은 트랜잭션에서 초대 메일을 적재한다. 발급은 발송이 아니다 — 상태는 따로 낸다(ADR 0038).
                delivery = mail(tenant, invitation, email, code, now.plusSeconds(72 * 3600))
            }
            mapOf("email" to email, "invitationId" to invitation, "status" to status, "reason" to reason,
                "expiresAt" to if (invitation != null) now.plusSeconds(72 * 3600).toString() else null, "code" to code, "delivery" to delivery)
        }
        return mapOf("results" to results)
    }

    private fun reinvite(member: UUID, role: String, team: UUID?, now: Instant) {
        val old = directory.lockMemberVersion(member)
        val open = directory.memberOpenTeams(member)
        if (open != setOfNotNull(team)) moveTeam(member, team, now)
        directory.updateInvitedRole(role, advance(now, old), member)
    }

    fun revoke(tenant: UUID, raw: String, now: Instant): Map<String, Any?> {
        val id = uuid(raw)
        val count = invitations.revoke(now, tenant, id)
        if (count == 0) fail("invitation_unavailable", 409)
        // 폐기한 코드의 메일이 아직 나가지 않았으면 보내지 않는다.
        invitationMail?.cancel(id)
        return emptyMap<String, String>()
    }

    private fun mail(tenant: UUID, invitation: UUID, email: String, code: String, expiresAt: Instant, signup: Boolean = true, install: Boolean = true): Map<String, Any?> {
        val mailer = invitationMail ?: return MailDeliveryView.notSent(false)
        val organization = directory.organizationName(tenant)
        return MailDeliveryView.of(mailer.enqueue(invitation, organization, email, code, expiresAt, signup, install))
    }

    private fun newCode() = (1..12).map { "0123456789ABCDEFGHJKMNPQRSTVWXYZ"[random.nextInt(32)] }.joinToString("").chunked(4).joinToString("-")

    fun reissue(tenant: UUID, actor: UUID, raw: String, now: Instant): Map<String, Any?> {
        val old = invitations.lockInvitation(tenant, uuid(raw)) ?: fail("invitation_unavailable", 409)
        if (old.second != null) fail("invitation_unavailable", 409)
        revoke(tenant, raw, now)
        val code = newCode()
        val id = UUID.randomUUID()
        val expires = now.plusSeconds(72 * 3600)
        invitations.insertReplacement(id, tenant, old.first, actor, hash(code), expires, now, old.second, old.third)
        val email = directory.memberEmail(old.first)
        return mapOf("invitationId" to id, "replacesInvitationId" to raw, "code" to code, "expiresAt" to expires.toString(),
            "delivery" to mail(tenant, id, email, code, expires, signup = old.third == null, install = old.second == null))
    }

    fun installationInvitation(tenant: UUID, actor: UUID, raw: String, expectedVersion: Long, now: Instant): Map<String, Any?> {
        val id = uuid(raw)
        val member = directory.lockMemberContact(tenant, id) ?: fail("not_found", 404, "memberId")
        if (member.second == "suspended") fail("member_suspended", 409)
        // 아직 합류하지 않은 구성원은 초대(일괄 초대·재발급)로 코드를 받는다.
        if (member.second != "active") fail("member_not_active", 409)
        checkVersion(member.third, expectedVersion)
        val replaced = invitations.revokeInstallationInvitations(now, tenant, id)
        replaced.forEach { invitationMail?.cancel(it) }
        val code = newCode()
        val invitation = UUID.randomUUID()
        val expires = now.plusSeconds(72 * 3600)
        invitations.insertInstallationInvitation(invitation, tenant, id, actor, hash(code), expires, now)
        return mapOf("invitationId" to invitation, "memberId" to id, "replacesInvitationIds" to replaced, "code" to code,
            "expiresAt" to expires.toString(), "delivery" to mail(tenant, invitation, member.first, code, expires, signup = false))
    }

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

data class InvitationInput(val email: String, val role: String, val plannedVendors: List<String>, val teamId: UUID?)
