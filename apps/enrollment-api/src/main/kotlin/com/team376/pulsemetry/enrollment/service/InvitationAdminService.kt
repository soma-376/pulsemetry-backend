package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.contract.CreateInvitationRequest
import com.team376.pulsemetry.enrollment.contract.CreateInvitationResponse
import com.team376.pulsemetry.enrollment.contract.InstallCommands
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.persistence.enrollment.entity.Invitation
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 관리자용 초대 발급·폐기 (PLAN.md §6.5).
 *
 * 인증(`X-Admin-Token`)은 컨트롤러가 이미 통과시킨 상태다. 여기서 보는 것은 **권한**이다 —
 * 관리자 키를 아는 사람이라도 아무 tenant 에나 초대를 뿌릴 수는 없고,
 * `created_by_member_id` 가 그 tenant 의 owner·admin 이어야 한다.
 */
@Service
class InvitationAdminService(
	private val members: MemberRepository,
	private val invitations: InvitationRepository,
	private val properties: PulsemetryProperties,
	private val clock: Clock,
) {

	@Transactional
	fun create(request: CreateInvitationRequest): CreateInvitationResponse {
		val now = clock.instant()

		val email = request.email.trim()
		if (email.isEmpty()) throw EnrollmentException.invalidEmail()

		val ttlHours = request.expiresInHours ?: properties.invitation.defaultTtlHours
		if (ttlHours !in 1..MAX_TTL_HOURS) throw EnrollmentException.invalidExpiry()

		// 발급자는 그 tenant 소속의 owner·admin 이어야 한다. 없는 member 도 "소속이 아님" 이므로 403 이다.
		val creator = members.findById(request.createdByMemberId).orElseThrow {
			EnrollmentException.forbidden()
		}
		if (creator.tenantId != request.tenantId) throw EnrollmentException.forbidden()
		if (!creator.canInvite()) throw EnrollmentException.forbidden()

		// 정지된 사용자에게는 초대를 다시 내주지 않는다.
		// `invited` 는 정상이다 — 아직 설치하지 않은 사용자에게 코드를 재발급하는 경로다.
		val existing = members.findByTenantIdAndEmail(request.tenantId, email)
		if (existing != null && existing.status == MemberStatus.suspended) {
			throw EnrollmentException.forbidden()
		}

		val target = existing
			?: members.save(
				Member(
					tenantId = request.tenantId,
					email = email,
					displayName = request.displayName,
					role = MemberRole.member,
					status = MemberStatus.invited,
					createdAt = now,
					updatedAt = now,
				),
			)

		val code = InvitationCode.generate()
		val invitation = invitations.save(
			Invitation(
				tenantId = request.tenantId,
				targetMemberId = target.id,
				createdByMemberId = creator.id,
				codeHash = Sha256.hex(code),
				expiresAt = now.plus(ttlHours, ChronoUnit.HOURS),
				createdAt = now,
			),
		)

		return CreateInvitationResponse(
			invitationId = invitation.id,
			code = code,
			expiresAt = invitation.expiresAt,
			installCommands = installCommands(code),
		)
	}

	/**
	 * 아직 쓰지도 폐기되지도 않은 초대만 폐기한다.
	 *
	 * 조건부 UPDATE 의 영향 행 수가 곧 결과다. 0행일 때만 사유를 조회한다 —
	 * 먼저 조회해서 판단하면 그 사이에 누가 코드를 써 버릴 수 있다.
	 *
	 * **여기에는 [create] 같은 권한 검사가 없다.** `X-Admin-Token` 이 tenant 정보를 담지 않아
	 * tenant 범위를 판정할 근거가 없기 때문이다. role 기반 인가가 서면 [create] 와 같은 검사를 넣는다.
	 */
	@Transactional
	fun revoke(invitationId: UUID) {
		if (invitations.revoke(invitationId, clock.instant()) == 1) return

		val invitation = invitations.findById(invitationId).orElseThrow {
			EnrollmentException.invitationNotFound()
		}
		throw when {
			invitation.isUsed() -> EnrollmentException.invitationUsed()
			invitation.isRevoked() -> EnrollmentException.invitationRevoked()
			// 도달할 수 없다. 조건부 UPDATE 가 0행이면 둘 중 하나여야 한다.
			else -> EnrollmentException.invitationNotFound()
		}
	}

	/**
	 * 서버 주소는 설정값에서만 온다. `Host` 헤더에서 유도하면 헤더 주입으로
	 * 사용자가 공격자의 서버에서 바이너리를 내려받게 된다 (PLAN.md §6.6).
	 *
	 * 코드는 [InvitationCode.generate] 가 만든 값이라 정규식 화이트리스트를 이미 만족한다 —
	 * 셸·PowerShell 메타문자가 들어갈 여지가 없다.
	 */
	private fun installCommands(code: String): InstallCommands {
		val base = properties.baseUrl()
		return InstallCommands(
			windows = "irm '$base/windows?code=$code' | iex",
			unix = "curl -fsSL '$base/unix?code=$code' | sh",
		)
	}

	private companion object {
		/**
		 * 초대 TTL 상한(30일). 상한이 없으면 `Long.MAX_VALUE` 같은 값이 만료 계산
		 * (`now.plus`)에서 ArithmeticException → 500 으로 터진다.
		 * 720 은 잠정값이다 — 상한 정책은 팀 확정 대상.
		 */
		const val MAX_TTL_HOURS = 720L
	}
}
