package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryTokenFormat
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryTokenRevokeReason
import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * telemetry token 자격 기록(V33, ADR 0065 Decision 4·5)을 실제 PostgreSQL 에서 본다.
 *
 * 트랜잭션으로 감싸지 않는다 — CHECK 위반이 같은 트랜잭션의 다음 문장까지 막기 때문이다. 대신 테스트마다 새 조직·설치를 만든다.
 */
class TelemetryCredentialRecordTest : AbstractPersistenceIntegrationTest() {

	@Autowired
	private lateinit var telemetryTokens: TelemetryTokenRepository

	@Autowired
	private lateinit var installations: InstallationRepository

	@Autowired
	private lateinit var invitations: InvitationRepository

	@Autowired
	private lateinit var tenants: TenantRepository

	@Autowired
	private lateinit var members: MemberRepository

	@Autowired
	private lateinit var jdbc: JdbcClient

	@Autowired
	private lateinit var transactions: PlatformTransactionManager

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID
	private lateinit var installationId: UUID

	@BeforeEach
	fun setUp() {
		tenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant()).id
		memberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId, status = MemberStatus.active)).id
		val invitationId = invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId)).id
		installationId = installations.saveAndFlush(EnrollmentFixtures.installation(tenantId, memberId, invitationId)).id
	}

	// ── 스키마 ──────────────────────────────────────────────────────────────

	@Test
	@DisplayName("기존 방식으로 넣은 행은 opaque 이고 만료·kid·사유가 없다 — 구 이미지의 INSERT 가 그대로 통과한다")
	fun legacyInsertDefaultsToOpaque() {
		val id = UUID.randomUUID()
		jdbc.sql("INSERT INTO enrollment.telemetry_tokens (id, installation_id, token_hash) VALUES (:id, :installation, :hash)")
			.param("id", id).param("installation", installationId).param("hash", EnrollmentFixtures.randomHash()).update()

		val row = telemetryTokens.findById(id).orElseThrow()
		assertThat(row.tokenFormat).isEqualTo(TelemetryTokenFormat.opaque)
		assertThat(row.expiresAt).isNull()
		assertThat(row.signingKid).isNull()
		assertThat(row.revokeReason).isNull()
	}

	@Test
	@DisplayName("구 이미지의 회전 UPDATE(사유 없음)도 통과한다 — 폐기 시각만 있는 행은 사유 불명 폐기로 남는다")
	fun legacyRotationWithoutReasonIsAllowed() {
		telemetryTokens.saveAndFlush(EnrollmentFixtures.jwtCredential(installationId))

		val affected = jdbc.sql(
			"UPDATE enrollment.telemetry_tokens SET revoked_at = now() WHERE installation_id = :installation AND revoked_at IS NULL",
		).param("installation", installationId).update()

		assertThat(affected).isEqualTo(1)
		val row = telemetryTokens.findAllByInstallationIdAndRevokedAtIsNull(installationId)
		assertThat(row).isEmpty()
		assertThat(jdbc.sql("SELECT count(*) FROM enrollment.telemetry_tokens WHERE installation_id = :i AND revoke_reason IS NULL AND revoked_at IS NOT NULL")
			.param("i", installationId).query(Long::class.java).single()).isEqualTo(1L)
	}

	@Test
	@DisplayName("형식은 opaque·jwt 뿐이다")
	fun formatCheck() {
		assertThatThrownBy { insertRaw(format = "paseto") }.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("사유는 rotated·security 뿐이고 폐기 시각 없이 쓸 수 없다")
	fun reasonCheck() {
		assertThatThrownBy { insertRaw(revokedAt = Instant.now(), reason = "expired") }
			.isInstanceOf(DataIntegrityViolationException::class.java)
		assertThatThrownBy { insertRaw(reason = "security") }
			.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("jwt 는 만료와 kid 가 있어야 하고 만료가 발급보다 뒤다")
	fun jwtClaimsCheck() {
		val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
		assertThatThrownBy { insertRaw(format = "jwt", issuedAt = issuedAt, kid = "k") }
			.isInstanceOf(DataIntegrityViolationException::class.java)
		assertThatThrownBy { insertRaw(format = "jwt", issuedAt = issuedAt, expiresAt = issuedAt.plusSeconds(10800)) }
			.isInstanceOf(DataIntegrityViolationException::class.java)
		assertThatThrownBy { insertRaw(format = "jwt", issuedAt = issuedAt, expiresAt = issuedAt, kid = "k") }
			.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("jwt 행도 설치당 활성 하나 제약(V3)을 따른다")
	fun singleActiveIncludesJwt() {
		telemetryTokens.saveAndFlush(EnrollmentFixtures.telemetryToken(installationId))

		assertThatThrownBy { telemetryTokens.saveAndFlush(EnrollmentFixtures.jwtCredential(installationId)) }
			.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	// ── 회전과 보안 폐기 ───────────────────────────────────────────────────

	@Test
	@DisplayName("재발급의 일괄 폐기는 활성 행에만 rotated 를 쓰고 이미 폐기된 행의 사유를 덮지 않는다")
	fun rotationOnlyTouchesActive() {
		val securityAt = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS)
		val security = telemetryTokens.saveAndFlush(
			EnrollmentFixtures.jwtCredential(installationId, revokedAt = securityAt, revokeReason = TelemetryTokenRevokeReason.security),
		).id
		val legacy = telemetryTokens.saveAndFlush(EnrollmentFixtures.telemetryToken(installationId, revokedAt = securityAt)).id
		val active = telemetryTokens.saveAndFlush(EnrollmentFixtures.jwtCredential(installationId)).id
		val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

		// 재발급 서비스처럼 트랜잭션 안에서 부른다 — @Modifying 문장은 트랜잭션을 요구한다.
		assertThat(TransactionTemplate(transactions).execute { telemetryTokens.revokeActiveByInstallationId(installationId, now) }).isEqualTo(1)

		val rotated = telemetryTokens.findById(active).orElseThrow()
		assertThat(rotated.revokedAt).isEqualTo(now)
		assertThat(rotated.revokeReason).isEqualTo(TelemetryTokenRevokeReason.rotated)
		assertThat(telemetryTokens.findById(security).orElseThrow().revokeReason).isEqualTo(TelemetryTokenRevokeReason.security)
		assertThat(telemetryTokens.findById(security).orElseThrow().revokedAt).isEqualTo(securityAt)
		assertThat(telemetryTokens.findById(legacy).orElseThrow().revokeReason).isNull()
	}

	@Test
	@DisplayName("보안 폐기 SQL 은 활성 토큰의 폐기 시각을 채우고, 이미 회전된 토큰은 시각을 두고 사유만 security 로 바꾼다")
	fun securityRevocation() {
		val rotatedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS)
		val rotated = telemetryTokens.saveAndFlush(
			EnrollmentFixtures.jwtCredential(installationId, revokedAt = rotatedAt, revokeReason = TelemetryTokenRevokeReason.rotated),
		).id
		val active = telemetryTokens.saveAndFlush(EnrollmentFixtures.jwtCredential(installationId)).id

		// docs/telemetry-kafka-operations.md 의 "설치 단위 보안 폐기" SQL 과 같은 문장이다.
		val affected = jdbc.sql(
			"""
			UPDATE enrollment.telemetry_tokens
			   SET revoked_at = COALESCE(revoked_at, now()), revoke_reason = 'security'
			 WHERE installation_id = :installation_id
			""".trimIndent(),
		).param("installation_id", installationId).update()

		assertThat(affected).isEqualTo(2)
		val wasRotated = telemetryTokens.findById(rotated).orElseThrow()
		assertThat(wasRotated.revokeReason).isEqualTo(TelemetryTokenRevokeReason.security)
		assertThat(wasRotated.revokedAt).isEqualTo(rotatedAt)
		val wasActive = telemetryTokens.findById(active).orElseThrow()
		assertThat(wasActive.revokeReason).isEqualTo(TelemetryTokenRevokeReason.security)
		assertThat(wasActive.revokedAt).isNotNull()
		// 폐기 시각이 채워졌으므로 해시 조회(구 ingest 경로)도 거부 대상이다.
		assertThat(telemetryTokens.findAuthRowByTokenHash(wasActive.tokenHash)!!.tokenRevoked).isTrue()

		// 토큰 하나의 보안 폐기 SQL 도 재실행에 안전하다.
		jdbc.sql(
			"""
			UPDATE enrollment.telemetry_tokens
			   SET revoked_at = COALESCE(revoked_at, now()), revoke_reason = 'security'
			 WHERE id = :token_id
			""".trimIndent(),
		).param("token_id", rotated).update()
		assertThat(telemetryTokens.findById(rotated).orElseThrow().revokedAt).isEqualTo(rotatedAt)
	}

	// ── jti 조회 ─────────────────────────────────────────────────────────────

	@Test
	@DisplayName("jti 로 자격 기록과 설치·회원·조직 상태를 한 번에 읽고 시각은 마이크로초까지 그대로다")
	fun findCredentialRow() {
		val issuedAt = Instant.parse("2026-10-21T01:02:03Z")
		val revokedAt = Instant.parse("2026-10-21T02:00:00.123456Z")
		val id = telemetryTokens.saveAndFlush(
			EnrollmentFixtures.jwtCredential(
				installationId,
				issuedAt = issuedAt,
				signingKid = "tk-1",
				revokedAt = revokedAt,
				revokeReason = TelemetryTokenRevokeReason.rotated,
			),
		).id

		val row = telemetryTokens.findCredentialRowById(id)!!

		assertThat(row.tokenId).isEqualTo(id)
		assertThat(row.tenantId).isEqualTo(tenantId)
		assertThat(row.installationId).isEqualTo(installationId)
		assertThat(row.memberId).isEqualTo(memberId)
		assertThat(row.tokenFormat).isEqualTo("jwt")
		assertThat(row.issuedAt).isEqualTo(issuedAt)
		assertThat(row.expiresAt).isEqualTo(issuedAt.plus(3, ChronoUnit.HOURS))
		assertThat(row.signingKid).isEqualTo("tk-1")
		assertThat(row.revokedAt).isEqualTo(revokedAt)
		assertThat(row.revokeReason).isEqualTo("rotated")
		assertThat(row.tokenRevoked).isTrue()
		assertThat(row.installationStatus).isEqualTo("active")
		assertThat(row.memberStatus).isEqualTo("active")
		assertThat(row.tenantStatus).isEqualTo("active")
		assertThat(row.installationRevoked).isFalse()
		assertThat(row.tenantDeleted).isFalse()
	}

	@Test
	@DisplayName("없는 jti 는 null 이다")
	fun unknownCredential() {
		assertThat(telemetryTokens.findCredentialRowById(UUID.randomUUID())).isNull()
	}

	private fun insertRaw(
		format: String = "opaque",
		issuedAt: Instant = Instant.now(),
		expiresAt: Instant? = null,
		kid: String? = null,
		revokedAt: Instant? = null,
		reason: String? = null,
	) {
		jdbc.sql(
			"""
			INSERT INTO enrollment.telemetry_tokens
			    (id, installation_id, token_hash, issued_at, token_format, expires_at, signing_kid, revoked_at, revoke_reason)
			VALUES (:id, :installation, :hash, :issued, :format, :expires, :kid, :revoked, :reason)
			""".trimIndent(),
		)
			.param("id", UUID.randomUUID()).param("installation", installationId).param("hash", EnrollmentFixtures.randomHash())
			.param("issued", java.sql.Timestamp.from(issuedAt)).param("format", format)
			.param("expires", expiresAt?.let { java.sql.Timestamp.from(it) }).param("kid", kid)
			.param("revoked", revokedAt?.let { java.sql.Timestamp.from(it) }).param("reason", reason)
			.update()
	}
}
