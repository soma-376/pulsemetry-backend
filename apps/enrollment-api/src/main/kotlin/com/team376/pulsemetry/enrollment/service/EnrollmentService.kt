package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.contract.EnrollRequest
import com.team376.pulsemetry.enrollment.contract.EnrollmentResponse
import com.team376.pulsemetry.enrollment.contract.ManifestPayload
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.enrollment.secret.SecretToken
import com.team376.pulsemetry.enrollment.secret.TelemetryTokenHasher
import com.team376.pulsemetry.persistence.enrollment.entity.Installation
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationCredential
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignment
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignmentId
import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Platform
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryToken
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationCredentialRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationManifestAssignmentRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.ManifestRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Locale

/**
 * `POST /v1/enroll` 의 본체.
 *
 * PLAN.md §6.2 가 못박은 10단계를 **그 순서 그대로** 수행한다. 순서를 바꾸지 마라 —
 * 예를 들어 초대 소비를 뒤로 미루면 그 사이에 같은 코드로 두 번 설치가 된다.
 *
 * 메서드 전체가 하나의 트랜잭션이다. 중간에 실패하면 초대 소비까지 함께 롤백되므로
 * 사용자는 같은 코드로 다시 시도할 수 있다.
 */
@Service
class EnrollmentService(
	private val invitations: InvitationRepository,
	private val members: MemberRepository,
	private val installations: InstallationRepository,
	private val credentials: InstallationCredentialRepository,
	private val telemetryTokens: TelemetryTokenRepository,
	private val manifests: ManifestRepository,
	private val assignments: InstallationManifestAssignmentRepository,
	private val telemetryTokenHasher: TelemetryTokenHasher,
	private val objectMapper: ObjectMapper,
	private val clock: Clock,
) {

	@Transactional
	fun enroll(request: EnrollRequest): EnrollmentResponse {
		val now = clock.instant()

		// 1. 코드 정규화. 형식이 어긋나면 DB 를 건드리지 않고 400 이다.
		val rawCode = request.effectiveCode() ?: throw EnrollmentException.missingCode()
		val code = InvitationCode.normalize(rawCode) ?: throw EnrollmentException.malformedCode()
		val codeHash = Sha256.hex(code)

		// 2. 원자적 소비. 조건부 UPDATE 한 방이라 동시 요청 중 정확히 하나만 1을 받는다 (A6).
		if (invitations.consume(codeHash, now) != 1) {
			// 3. 소비에 실패한 뒤에야 사유를 조회한다. 이 조회로 소비를 결정하지 않는다.
			throw consumptionFailure(codeHash, now)
		}
		val invitation = requireNotNull(invitations.findByCodeHash(codeHash)) {
			"방금 소비한 초대를 다시 찾지 못했다"
		}

		// 3.5. 설치 완료가 곧 `invited → active` 전환이다. auth-proxy 가 invited 를 거부하므로
		// 이 전환 없이는 아래에서 발급하는 telemetry token 이 전부 401 이 된다.
		// 0행(이미 active, 또는 suspended)이어도 enroll 은 그대로 진행한다.
		members.activateInvited(invitation.targetMemberId, now)

		// 4. platform 정규화. 클라이언트는 runtime.GOOS 를 그대로 보낸다.
		val platform = normalizePlatform(request.effectivePlatform())
			?: throw EnrollmentException.unsupportedPlatform()

		// 5. installation 생성
		val installation = installations.save(
			Installation(
				tenantId = invitation.tenantId,
				memberId = invitation.targetMemberId,
				invitationId = invitation.id,
				platform = platform,
				hostname = request.hostname,
				architecture = request.architecture,
				clientVersion = request.effectiveClientVersion(),
				createdAt = now,
				updatedAt = now,
			),
		)

		// 6. 장기 자격증명. 원본은 응답에만 싣고 DB 에는 해시만 남긴다.
		val installationToken = SecretToken.installationToken()
		credentials.save(
			InstallationCredential(
				installationId = installation.id,
				credentialHash = Sha256.hex(installationToken),
				issuedAt = now,
			),
		)

		// 7. telemetry token. 역시 해시만 저장한다 — 단 auth-proxy 가 조회하는 값이라 HMAC 이다.
		val telemetryToken = SecretToken.telemetryToken()
		telemetryTokens.save(
			TelemetryToken(
				installationId = installation.id,
				tokenHash = telemetryTokenHasher.hex(telemetryToken),
				issuedAt = now,
			),
		)

		// 8. tenant 의 활성 manifest
		val manifest = manifests.findByTenantIdAndIsActiveTrue(invitation.tenantId)
			?: throw EnrollmentException.manifestNotConfigured()

		// 9. 배포 이력. 클라이언트가 적용했다는 보고는 아직 없으므로 appliedAt 은 NULL 이다.
		assignments.save(
			InstallationManifestAssignment(
				id = InstallationManifestAssignmentId(installation.id, manifest.id),
				assignedAt = now,
			),
		)

		// 10. 봉투 조립
		return EnrollmentResponse(
			installationId = installation.id.toString(),
			installationToken = installationToken,
			telemetryToken = telemetryToken,
			manifest = readManifest(manifest),
		)
	}

	/**
	 * 소비 실패의 사유를 가려 §6.7 의 에러로 옮긴다.
	 *
	 * 우선순위는 사용 → 폐기 → 만료다. 동시 요청에서 진 쪽은 여기서 `usedAt` 을 보게 되므로
	 * 409 `invitation_used` 를 받는다 — 이게 "정확히 하나만 201" 의 나머지 절반이다.
	 */
	private fun consumptionFailure(codeHash: String, now: Instant): EnrollmentException {
		val invitation = invitations.findByCodeHash(codeHash)
			?: return EnrollmentException.invitationNotFound()

		return when {
			invitation.isUsed() -> EnrollmentException.invitationUsed()
			invitation.isRevoked() -> EnrollmentException.invitationRevoked()
			invitation.isExpiredAt(now) -> EnrollmentException.invitationExpired()
			// 도달할 수 없다. 그래도 성공으로 오해하게 두지 않는다.
			else -> EnrollmentException.invitationUsed()
		}
	}

	/** `darwin` → `macos`. 계약상 이미 정규화된 `macos` 도 들어올 수 있다. */
	private fun normalizePlatform(raw: String?): Platform? =
		when (raw?.trim()?.lowercase(Locale.ROOT)) {
			"darwin", "macos" -> Platform.macos
			"windows" -> Platform.windows
			"linux" -> Platform.linux
			else -> null
		}

	/**
	 * 저장된 jsonb 를 계약 타입으로 읽고 `config_revision` 만 `manifests.version` 으로 갈아 끼운다.
	 *
	 * 타입을 거치므로 봉투 필드가 manifest 안으로 새어 들어갈 수 없다 (A5).
	 * 저장된 manifest 가 계약을 어기고 있으면 클라이언트가 어차피 거부하므로,
	 * 여기서 `manifest_not_configured` 로 끊어 관리자가 고치도록 안내한다.
	 */
	private fun readManifest(manifest: Manifest): ManifestPayload {
		val payload = try {
			objectMapper.readValue(manifest.manifest, ManifestPayload::class.java)
		} catch (_: JacksonException) {
			throw EnrollmentException.manifestNotConfigured()
		}
		// 역직렬화만으로는 unknown field 밖에 못 거른다. 값 자체가 계약을 어기면 여기서 끊는다.
		if (!payload.satisfiesContract()) throw EnrollmentException.manifestNotConfigured()
		return payload.withConfigRevision(manifest.version)
	}
}
