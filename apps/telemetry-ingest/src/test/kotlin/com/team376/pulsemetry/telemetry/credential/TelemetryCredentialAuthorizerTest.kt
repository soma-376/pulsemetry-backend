package com.team376.pulsemetry.telemetry.credential

import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryTokenRevokeReason
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtContract
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestJwtKeys
import com.team376.pulsemetry.telemetry.support.IngestTestData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * RDB 판정의 표(ADR 0065 Decision 6)를 실제 PostgreSQL 에서 본다. 시각 경계는 DB 의 저장 정밀도(마이크로초)와
 * 접수 시각(나노초)의 비교까지 포함한다.
 */
class TelemetryCredentialAuthorizerTest : AbstractIngestIntegrationTest() {

	@Autowired
	private lateinit var authorizer: TelemetryCredentialAuthorizer

	@Autowired
	private lateinit var data: IngestTestData

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private val issuedAt: Instant = Instant.parse("2026-10-21T00:00:00Z")

	@BeforeEach
	fun reset() = data.clear()

	@AfterEach
	fun cleanUp() = data.clear()

	private fun context(seeded: IngestTestData.Seeded) = GatewayAuthContext(
		credentialId = seeded.credentialId!!,
		tenantId = seeded.tenantId,
		installationId = seeded.installationId,
		issuer = TelemetryJwtContract.ISSUER,
		keyId = IngestJwtKeys.KID,
		issuedAt = seeded.issuedAt!!,
		expiresAt = seeded.expiresAt!!,
	)

	private fun assertRejected(auth: GatewayAuthContext, acceptedAt: Instant, code: String) {
		assertThatThrownBy { authorizer.authorize(auth, acceptedAt) }
			.isInstanceOfSatisfying(TelemetryCredentialRejectedException::class.java) { assertThat(it.code).isEqualTo(code) }
	}

	@Test
	@DisplayName("행이 맞으면 신원은 행의 tenant·installation·member 다")
	fun authorizes() {
		val seeded = data.seedJwt(issuedAt)

		val credential = authorizer.authorize(context(seeded), issuedAt.plusSeconds(60))

		assertThat(credential.credentialId).isEqualTo(seeded.credentialId)
		assertThat(credential.identity().tenantId).isEqualTo(seeded.tenantId)
		assertThat(credential.identity().installationId).isEqualTo(seeded.installationId)
		assertThat(credential.memberId).isEqualTo(seeded.memberId)
	}

	@Test
	@DisplayName("접수 당시 유효했으면 지금 만료됐어도 허용한다 — 판정 시각은 접수 시각이다")
	fun expiredWhileQueued() {
		val longAgo = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
		val seeded = data.seedJwt(longAgo)

		assertThat(authorizer.authorize(context(seeded), longAgo.plusSeconds(3600)).credentialId).isEqualTo(seeded.credentialId)
	}

	@Test
	@DisplayName("접수 시각 경계 — DB 의 [iat−30s, exp+30s] 밖이면 credential_expired 다")
	fun validityBoundary() {
		val seeded = data.seedJwt(issuedAt)
		val expiresAt = seeded.expiresAt!!

		authorizer.authorize(context(seeded), issuedAt.minusSeconds(30))
		authorizer.authorize(context(seeded), expiresAt.plusSeconds(30))
		assertRejected(context(seeded), issuedAt.minusSeconds(30).minusNanos(1), "credential_expired")
		assertRejected(context(seeded), expiresAt.plusSeconds(30).plusNanos(1), "credential_expired")
	}

	@Test
	@DisplayName("접수 뒤 정상 회전은 허용하고, 회전 시각 이후 접수는 거부한다 — 마이크로초 경계까지")
	fun rotation() {
		val revokedAt = issuedAt.plusSeconds(600).plusNanos(123_456_000)
		val seeded = data.seedJwt(issuedAt, revokedAt = revokedAt, revokeReason = TelemetryTokenRevokeReason.rotated)

		authorizer.authorize(context(seeded), revokedAt.minusNanos(1))
		assertRejected(context(seeded), revokedAt, "credential_rotated")
		assertRejected(context(seeded), revokedAt.plusSeconds(1), "credential_rotated")
	}

	@Test
	@DisplayName("보안 폐기는 접수 시각과 무관하게 거부하고, 사유 없는 폐기도 거부한다")
	fun securityAndUnknownRevocation() {
		val security = data.seedJwt(issuedAt, revokedAt = issuedAt.plusSeconds(600), revokeReason = TelemetryTokenRevokeReason.security)
		assertRejected(context(security), issuedAt.plusSeconds(1), "credential_security_revoked")

		val unknown = data.seedJwt(issuedAt, revokedAt = issuedAt.plusSeconds(600))
		assertRejected(context(unknown), issuedAt.plusSeconds(1), "credential_revoked_unknown")
	}

	@Test
	@DisplayName("이미 회전된 토큰에 보안 폐기를 덮으면 접수가 회전보다 앞서도 거부한다")
	fun securityAfterRotation() {
		val rotatedAt = issuedAt.plusSeconds(600)
		val seeded = data.seedJwt(issuedAt, revokedAt = rotatedAt, revokeReason = TelemetryTokenRevokeReason.rotated)
		authorizer.authorize(context(seeded), issuedAt.plusSeconds(1))

		jdbc.update(
			"UPDATE enrollment.telemetry_tokens SET revoked_at = COALESCE(revoked_at, now()), revoke_reason = 'security' WHERE id = ?",
			seeded.credentialId,
		)

		assertRejected(context(seeded), issuedAt.plusSeconds(1), "credential_security_revoked")
	}

	@Test
	@DisplayName("자격 기록이 없거나 jwt 가 아니면 거부한다")
	fun unknownOrOpaque() {
		val seeded = data.seedJwt(issuedAt)
		assertRejected(context(seeded).copy(credentialId = UUID.randomUUID()), issuedAt, "credential_unknown")

		val opaque = data.seed()
		val opaqueId = jdbc.queryForObject(
			"SELECT id FROM enrollment.telemetry_tokens WHERE installation_id = ?", UUID::class.java, opaque.installationId,
		)!!
		assertRejected(
			context(seeded).copy(credentialId = opaqueId, tenantId = opaque.tenantId, installationId = opaque.installationId),
			issuedAt,
			"credential_format",
		)
	}

	@Test
	@DisplayName("tenant·installation·iss·kid·iat·exp 중 하나라도 행과 다르면 credential_mismatch 다")
	fun mismatch() {
		val seeded = data.seedJwt(issuedAt)
		val base = context(seeded)
		listOf(
			base.copy(tenantId = UUID.randomUUID()),
			base.copy(installationId = UUID.randomUUID()),
			base.copy(issuer = "pulsemetry"),
			base.copy(keyId = "tk-other"),
			base.copy(issuedAt = base.issuedAt.plusSeconds(1)),
			base.copy(expiresAt = base.expiresAt.minusSeconds(1)),
		).forEach { assertRejected(it, issuedAt.plusSeconds(1), "credential_mismatch") }
	}

	@Test
	@DisplayName("설치·회원·조직이 지금 차단돼 있으면 기존 판정 코드로 거부한다")
	fun holderBlocked() {
		val cases = listOf(
			"UPDATE enrollment.installations SET status = 'revoked', revoked_at = now() WHERE id = ?" to "installation_revoked",
			"UPDATE enrollment.members SET status = 'suspended' WHERE id = (SELECT member_id FROM enrollment.installations WHERE id = ?)" to "member_suspended",
			"UPDATE enrollment.tenants SET status = 'suspended' WHERE id = (SELECT tenant_id FROM enrollment.installations WHERE id = ?)" to "tenant_suspended",
			"UPDATE enrollment.tenants SET deleted_at = now() WHERE id = (SELECT tenant_id FROM enrollment.installations WHERE id = ?)" to "tenant_deleted",
		)
		cases.forEach { (sql, code) ->
			val seeded = data.seedJwt(issuedAt)
			jdbc.update(sql, seeded.installationId)
			assertRejected(context(seeded), issuedAt.plusSeconds(1), code)
		}
	}

	@Test
	@DisplayName("DB 장애는 거부가 아니다 — 조회 예외를 그대로 올린다")
	fun storeFailureIsNotRejection() {
		val seeded = data.seedJwt(issuedAt)
		doThrow(DataAccessResourceFailureException("db down")).`when`(telemetryTokens)
			.findCredentialRowById(ArgumentMatchers.any() ?: UUID.randomUUID())

		assertThatThrownBy { authorizer.authorize(context(seeded), issuedAt) }
			.isInstanceOf(DataAccessResourceFailureException::class.java)
	}
}
