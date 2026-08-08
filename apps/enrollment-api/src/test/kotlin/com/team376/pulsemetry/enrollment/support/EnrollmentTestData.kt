package com.team376.pulsemetry.enrollment.support

import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.persistence.enrollment.entity.Invitation
import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.Tenant
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.ManifestRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
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

	fun member(tenantId: UUID, email: String = "user-${UUID.randomUUID()}@example.com"): Member =
		members.save(Member(tenantId = tenantId, email = email, role = MemberRole.owner))

	fun activeManifest(tenantId: UUID, memberId: UUID, version: Int = 3): Manifest =
		manifests.save(
			Manifest(
				tenantId = tenantId,
				version = version,
				manifest = MANIFEST_JSON,
				createdByMemberId = memberId,
				isActive = true,
				activatedAt = Instant.now(),
			),
		)

	fun brokenManifest(tenantId: UUID, memberId: UUID): Manifest =
		manifests.save(
			Manifest(
				tenantId = tenantId,
				version = 1,
				manifest = """{"schema_version": 1, "surprise": true}""",
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
