package com.team376.pulsemetry.telemetry.auth

import com.nimbusds.jwt.SignedJWT
import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** 발급기가 ADR 0065 Decision 1·3 의 형태로 서명하는지 본다. */
class TelemetryJwtIssuerTest {

	private val key = TelemetryJwtFixtures.signingKey("tk-1")

	@Test
	@DisplayName("header 는 RS256·전용 typ·kid 이고 claim 은 계약의 여덟 개뿐이다")
	fun shape() {
		val credentialId = UUID.randomUUID()
		val tenantId = UUID.randomUUID()
		val installationId = UUID.randomUUID()
		val issued = key.issuer().issue(credentialId, tenantId, installationId, Instant.parse("2026-10-21T01:02:03.987654Z"))

		val jwt = SignedJWT.parse(issued.token)
		assertThat(jwt.header.algorithm.name).isEqualTo("RS256")
		assertThat(jwt.header.type.type).isEqualTo("pulsemetry-telemetry+jwt")
		assertThat(jwt.header.keyID).isEqualTo("tk-1")

		val payload = String(Base64.getUrlDecoder().decode(issued.token.split('.')[1]))
		val claims = jwt.jwtClaimsSet.toJSONObject()
		assertThat(claims.keys).containsExactlyInAnyOrder("iss", "aud", "sub", "tenant_id", "scope", "jti", "iat", "exp")
		assertThat(claims["iss"]).isEqualTo("pulsemetry-enrollment")
		// 단일 audience 는 배열이 아니라 문자열로 나간다.
		assertThat(payload).contains("\"aud\":\"pulsemetry-telemetry-ingest\"")
		assertThat(claims["scope"]).isEqualTo("telemetry:write")
		assertThat(claims["sub"]).isEqualTo(installationId.toString())
		assertThat(claims["tenant_id"]).isEqualTo(tenantId.toString())
		assertThat(claims["jti"]).isEqualTo(credentialId.toString())
		assertThat(claims["iat"]).isEqualTo(Instant.parse("2026-10-21T01:02:03Z").epochSecond)
		assertThat(claims["exp"]).isEqualTo(Instant.parse("2026-10-21T04:02:03Z").epochSecond)
	}

	@Test
	@DisplayName("돌려준 발급·만료 시각은 초로 잘린 JWT 값과 같다 — DB 기록과 대조할 값이다")
	fun returnedTimes() {
		val issued = key.issuer().issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-21T01:02:03.5Z"))

		assertThat(issued.issuedAt).isEqualTo(Instant.parse("2026-10-21T01:02:03Z"))
		assertThat(issued.expiresAt).isEqualTo(Instant.parse("2026-10-21T04:02:03Z"))
		assertThat(issued.keyId).isEqualTo("tk-1")
		assertThat(issued.toString()).doesNotContain(issued.token)
	}

	@Test
	@DisplayName("활성 kid 의 공개키가 없거나 개인키와 짝이 아니면 발급기를 만들 수 없다")
	fun keyPairing() {
		val other = TelemetryJwtFixtures.signingKey("tk-2")
		assertThatThrownBy { TelemetryJwtIssuer("tk-1", key.privateKey, TelemetryJwtFixtures.publicKeys(other)) }
			.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("kid=tk-1")
		assertThatThrownBy { TelemetryJwtIssuer("tk-2", key.privateKey, TelemetryJwtFixtures.publicKeys(key, other)) }
			.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("짝이 아니다")
		assertThatThrownBy { TelemetryJwtIssuer(" ", key.privateKey, TelemetryJwtFixtures.publicKeys(key)) }
			.isInstanceOf(IllegalArgumentException::class.java)
		val small = TelemetryJwtFixtures.signingKey("small", bits = 1024)
		assertThatThrownBy { small.issuer() }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("수명은 60초 이상 3시간 이하로만 줄일 수 있다 — 검증기는 짧은 토큰도 받는다")
	fun shorterLifetime() {
		val now = Instant.parse("2026-10-21T01:02:03Z")
		val short = TelemetryJwtIssuer("tk-1", key.privateKey, TelemetryJwtFixtures.publicKeys(key), Duration.ofSeconds(60))
		val issued = short.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now)

		assertThat(issued.expiresAt).isEqualTo(now.plusSeconds(60))
		assertThat(key.verifier().verify(issued.token, now.plusSeconds(90)).expiresAt).isEqualTo(now.plusSeconds(60))
		assertThatThrownBy { TelemetryJwtIssuer("tk-1", key.privateKey, TelemetryJwtFixtures.publicKeys(key), Duration.ofSeconds(59)) }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { TelemetryJwtIssuer("tk-1", key.privateKey, TelemetryJwtFixtures.publicKeys(key), Duration.ofHours(3).plusSeconds(1)) }
			.isInstanceOf(IllegalArgumentException::class.java)
	}
}
