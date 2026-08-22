package com.team376.pulsemetry.persistence.enrollment.support

import com.team376.pulsemetry.persistence.enrollment.entity.Installation
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationCredential
import com.team376.pulsemetry.persistence.enrollment.entity.Invitation
import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.entity.Platform
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryToken
import com.team376.pulsemetry.persistence.enrollment.entity.Tenant
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 테스트용 엔티티 빌더. 저장하지 않은 인스턴스를 돌려준다.
 *
 * 해시 자리에는 SHA-256 hex 와 같은 모양(소문자 64자)의 무작위 값을 넣는다.
 * 실제 해시 계산은 P3 의 몫이고 여기서는 유니크 제약과 조회만 다룬다.
 */
object EnrollmentFixtures {

	fun randomHash(): String =
		(UUID.randomUUID().toString() + UUID.randomUUID().toString())
			.replace("-", "")
			.lowercase()

	fun tenant(name: String = "테스트 조직"): Tenant = Tenant(name = name)

	fun member(
		tenantId: UUID,
		email: String = "user-${UUID.randomUUID()}@example.com",
		role: MemberRole = MemberRole.owner,
		status: MemberStatus = MemberStatus.active,
	): Member = Member(tenantId = tenantId, email = email, role = role, status = status)

	fun invitation(
		tenantId: UUID,
		memberId: UUID,
		codeHash: String = randomHash(),
		expiresAt: Instant = Instant.now().plus(72, ChronoUnit.HOURS),
		usedAt: Instant? = null,
		revokedAt: Instant? = null,
	): Invitation = Invitation(
		tenantId = tenantId,
		targetMemberId = memberId,
		createdByMemberId = memberId,
		codeHash = codeHash,
		expiresAt = expiresAt,
		usedAt = usedAt,
		revokedAt = revokedAt,
	)

	fun installation(
		tenantId: UUID,
		memberId: UUID,
		invitationId: UUID,
		platform: Platform = Platform.macos,
		hostname: String? = "my-macbook",
		architecture: String? = "arm64",
		clientVersion: String? = "0.1.0",
	): Installation = Installation(
		tenantId = tenantId,
		memberId = memberId,
		invitationId = invitationId,
		platform = platform,
		hostname = hostname,
		architecture = architecture,
		clientVersion = clientVersion,
	)

	fun credential(
		installationId: UUID,
		credentialHash: String = randomHash(),
		revokedAt: Instant? = null,
	): InstallationCredential = InstallationCredential(
		installationId = installationId,
		credentialHash = credentialHash,
		revokedAt = revokedAt,
	)

	fun telemetryToken(
		installationId: UUID,
		tokenHash: String = randomHash(),
		revokedAt: Instant? = null,
	): TelemetryToken = TelemetryToken(
		installationId = installationId,
		tokenHash = tokenHash,
		revokedAt = revokedAt,
	)

	fun manifest(
		tenantId: UUID,
		createdByMemberId: UUID,
		version: Int = 1,
		isActive: Boolean = true,
		json: String = DEFAULT_MANIFEST_JSON,
	): Manifest = Manifest(
		tenantId = tenantId,
		version = version,
		manifest = json,
		createdByMemberId = createdByMemberId,
		isActive = isActive,
		activatedAt = if (isActive) Instant.now() else null,
	)

	/**
	 * `telemetryctl/contracts/enrollment-manifest.schema.json` 을 만족하는 manifest.
	 *
	 * 클라이언트가 `DisallowUnknownFields` 로 한 번 더 검증하므로 필수 키를 빠짐없이 넣는다:
	 * `signals` 는 logs·metrics·traces 셋 다, `privacy` 는 collect_* 다섯 개가 필수다.
	 * `otlp.endpoint` 는 https 여야 하고 `schema_version` 은 1 을 넘으면 안 된다 (PLAN.md §9 3·4번).
	 */
	const val DEFAULT_MANIFEST_JSON: String = """
		{
		  "schema_version": 1,
		  "config_revision": 1,
		  "otlp": {
		    "endpoint": "https://otlp.pulsemetry.example.com",
		    "protocol": "http/protobuf",
		    "compression": "gzip",
		    "timeout_ms": 10000
		  },
		  "signals": { "logs": false, "metrics": true, "traces": true },
		  "privacy": {
		    "collect_user_prompts": false,
		    "collect_assistant_responses": false,
		    "collect_tool_details": false,
		    "collect_tool_content": false,
		    "collect_user_email": false,
		    "collect_raw_api_bodies": false
		  }
		}
	"""
}
