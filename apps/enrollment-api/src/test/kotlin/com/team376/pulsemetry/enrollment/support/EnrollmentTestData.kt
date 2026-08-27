package com.team376.pulsemetry.enrollment.support

import com.team376.pulsemetry.enrollment.secret.SecretToken
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.enrollment.secret.TelemetryTokenHasher
import com.team376.pulsemetry.persistence.enrollment.entity.Installation
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationCredential
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationStatus
import com.team376.pulsemetry.persistence.enrollment.entity.Invitation
import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.entity.Platform
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryToken
import com.team376.pulsemetry.persistence.enrollment.entity.Tenant
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationCredentialRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.ManifestRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TenantRepository
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * API 통합 테스트용 데이터 준비.
 *
 * HTTP 로 서버를 부르므로 요청은 테스트와 **다른 트랜잭션**에서 돈다.
 * 그래서 준비 데이터는 반드시 커밋돼야 하고, 테스트 클래스에 `@Transactional` 을 붙이면 안 된다.
 * 대신 매 테스트 시작 전에 [reset] 으로 스키마를 비운다.
 *
 * 컴포넌트 스캔에 걸리지 않도록 애노테이션을 붙이지 않는다. 쓰는 테스트가 `@Import` 로 가져간다 —
 * 테스트 전용 빈이 모든 컨텍스트에 딸려 들어가면 나중에 원인 모를 간섭이 생긴다.
 */
class EnrollmentTestData(
	private val tenants: TenantRepository,
	private val members: MemberRepository,
	private val invitations: InvitationRepository,
	private val manifests: ManifestRepository,
	private val installations: InstallationRepository,
	private val credentials: InstallationCredentialRepository,
	private val telemetryTokens: TelemetryTokenRepository,
	private val telemetryTokenHasher: TelemetryTokenHasher,
	private val jdbcClient: JdbcClient,
) {

	/** 트랜잭션 롤백에 기대지 않고 직접 비운다. FK 순서를 신경 쓰지 않도록 한 문장으로 자른다. */
	fun reset() {
		jdbcClient.sql(
			"""
			TRUNCATE
				enrollment.installation_manifest_assignments,
				enrollment.telemetry_tokens,
				enrollment.installation_credentials,
				enrollment.installations,
				enrollment.invitations,
				enrollment.manifests,
				enrollment.members,
				enrollment.tenants
			CASCADE
			""",
		).update()
	}

	fun tenant(): Tenant = tenants.save(Tenant(name = "테스트 조직"))

	fun member(
		tenantId: UUID,
		email: String = "user-${UUID.randomUUID()}@example.com",
		role: MemberRole = MemberRole.owner,
		status: MemberStatus = MemberStatus.active,
	): Member = members.save(Member(tenantId = tenantId, email = email, role = role, status = status))

	fun findMemberByEmail(tenantId: UUID, email: String): Member? =
		members.findByTenantIdAndEmail(tenantId, email)

	fun findInvitation(id: UUID): Invitation? = invitations.findById(id).orElse(null)

	fun activeManifest(tenantId: UUID, memberId: UUID, version: Int = 3): Manifest =
		manifestOf(tenantId, memberId, MANIFEST_JSON, version)

	fun brokenManifest(tenantId: UUID, memberId: UUID): Manifest =
		manifestOf(tenantId, memberId, """{"schema_version": 1, "surprise": true}""")

	/**
	 * 임의 JSON 을 tenant 의 **활성** manifest 로 넣는다.
	 *
	 * manifest 는 대시보드가 생길 때까지 수동 INSERT 로 들어가므로,
	 * 역직렬화는 되지만 값이 계약을 어기는 상황을 이걸로 재현한다.
	 */
	fun manifestOf(tenantId: UUID, memberId: UUID, json: String, version: Int = 1): Manifest =
		manifests.save(
			Manifest(
				tenantId = tenantId,
				version = version,
				manifest = json,
				createdByMemberId = memberId,
				isActive = true,
				activatedAt = Instant.now(),
			),
		)

	fun invitation(
		tenantId: UUID,
		memberId: UUID,
		code: String,
		expiresAt: Instant = Instant.now().plus(72, ChronoUnit.HOURS),
		usedAt: Instant? = null,
		revokedAt: Instant? = null,
	): Invitation = invitations.save(
		Invitation(
			tenantId = tenantId,
			targetMemberId = memberId,
			createdByMemberId = memberId,
			codeHash = Sha256.hex(code),
			expiresAt = expiresAt,
			usedAt = usedAt,
			revokedAt = revokedAt,
		),
	)

	fun installation(
		tenantId: UUID,
		memberId: UUID,
		invitationId: UUID,
		status: InstallationStatus = InstallationStatus.active,
	): Installation = installations.save(
		Installation(
			tenantId = tenantId,
			memberId = memberId,
			invitationId = invitationId,
			platform = Platform.macos,
			hostname = "my-macbook",
			architecture = "arm64",
			clientVersion = "0.1.0",
			status = status,
			revokedAt = if (status == InstallationStatus.revoked) Instant.now() else null,
		),
	)

	/** 원본 토큰을 돌려준다. DB 에는 해시만 들어간다. */
	fun credential(installationId: UUID, revoked: Boolean = false): String {
		val token = SecretToken.installationToken()
		credentials.save(
			InstallationCredential(
				installationId = installationId,
				credentialHash = Sha256.hex(token),
				revokedAt = if (revoked) Instant.now() else null,
			),
		)
		return token
	}

	/** 원본 토큰을 돌려준다. DB 에는 해시만 들어간다 — auth-proxy 계약대로 HMAC 이다. */
	fun telemetryToken(installationId: UUID): String {
		val token = SecretToken.telemetryToken()
		telemetryTokens.save(
			TelemetryToken(installationId = installationId, tokenHash = telemetryTokenHasher.hex(token)),
		)
		return token
	}

	fun activeTelemetryTokenCount(installationId: UUID): Int =
		telemetryTokens.findAllByInstallationIdAndRevokedAtIsNull(installationId).size

	/**
	 * 다른 세션에 막혀 대기 중인 세션들이 지금 실행 중인 SQL.
	 *
	 * 「얼마나 지나도 안 끝나더라」 식의 타이밍 단언 대신 이걸 쓰면 스케줄링 운이 개입하지 않는다 —
	 * 대기 사실과 **무엇을 기다리는지**를 DB 에 직접 묻는다.
	 */
	fun blockedSessionQueries(): List<String> =
		jdbcClient.sql(
			"""
			SELECT query FROM pg_stat_activity
			WHERE datname = current_database()
			  AND cardinality(pg_blocking_pids(pid)) > 0
			""",
		).query(String::class.java).list().filterNotNull()

	fun countRows(table: String): Long =
		jdbcClient.sql("SELECT count(*) FROM enrollment.$table")
			.query(Long::class.javaObjectType)
			.single()

	fun singleColumn(sql: String): String? =
		jdbcClient.sql(sql).query(String::class.java).optional().orElse(null)

	companion object {
		/** 계약을 만족하는 manifest. `config_revision` 은 서버가 `manifests.version` 으로 덮어쓴다. */
		const val MANIFEST_JSON: String = """
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
}
