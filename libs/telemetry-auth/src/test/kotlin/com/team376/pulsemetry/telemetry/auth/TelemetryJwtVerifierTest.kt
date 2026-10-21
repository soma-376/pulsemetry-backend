package com.team376.pulsemetry.telemetry.auth

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures.sign
import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures.validClaims
import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures.validHeader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** 검증기가 ADR 0065 Decision 1·2 의 규칙을 하나씩 거부 사유로 가르는지 본다. */
class TelemetryJwtVerifierTest {

	private val key = TelemetryJwtFixtures.signingKey("tk-1")
	private val verifier = key.verifier()
	private val issuedAt: Instant = Instant.parse("2026-10-21T00:00:00Z")
	private val now: Instant = issuedAt.plusSeconds(60)

	private fun token(
		header: JWSHeader = validHeader(key.kid).build(),
		claims: JWTClaimsSet = validClaims(issuedAt = issuedAt).build(),
	): String = sign(header, claims, key.privateKey)

	private fun assertRejected(raw: String, reason: TelemetryJwtRejection, at: Instant = now) {
		assertThatThrownBy { verifier.verify(raw, at) }
			.isInstanceOfSatisfying(TelemetryJwtException::class.java) { assertThat(it.reason).isEqualTo(reason) }
	}

	@Test
	@DisplayName("발급기가 만든 토큰은 통과하고 claim 이 그대로 돌아온다")
	fun roundTrip() {
		val credentialId = UUID.randomUUID()
		val tenantId = UUID.randomUUID()
		val installationId = UUID.randomUUID()
		val issued = key.issuer().issue(credentialId, tenantId, installationId, issuedAt.plusMillis(900))

		val verified = verifier.verify(issued.token, now)

		assertThat(verified.credentialId).isEqualTo(credentialId)
		assertThat(verified.tenantId).isEqualTo(tenantId)
		assertThat(verified.installationId).isEqualTo(installationId)
		assertThat(verified.issuer).isEqualTo(TelemetryJwtContract.ISSUER)
		assertThat(verified.keyId).isEqualTo("tk-1")
		assertThat(verified.issuedAt).isEqualTo(issuedAt)
		assertThat(verified.expiresAt).isEqualTo(issuedAt.plusSeconds(3 * 3600))
	}

	@Test
	@DisplayName("서명을 바꾸거나 payload 를 바꾸면 서명 오류다")
	fun forged() {
		val valid = token()
		val parts = valid.split('.')
		val otherPayload = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(validClaims(issuedAt = issuedAt).build().toString().toByteArray())

		assertRejected("${parts[0]}.$otherPayload.${parts[2]}", TelemetryJwtRejection.SIGNATURE)
		assertRejected(sign(validHeader(key.kid).build(), validClaims(issuedAt = issuedAt).build(), TelemetryJwtFixtures.keyPair().private as java.security.interfaces.RSAPrivateKey), TelemetryJwtRejection.SIGNATURE)
	}

	@Test
	@DisplayName("알고리즘은 RS256 만 받는다 — 공개키를 HMAC 비밀로 쓰는 혼동 공격과 alg=none 을 거부한다")
	fun algorithm() {
		val claims = validClaims(issuedAt = issuedAt).build()
		val hs256 = SignedJWT(
			JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.kid).type(JOSEObjectType(TelemetryJwtContract.TYPE)).build(),
			claims,
		).also { it.sign(MACSigner(key.publicKey.encoded)) }.serialize()
		assertRejected(hs256, TelemetryJwtRejection.ALGORITHM)

		assertRejected(sign(JWSHeader.Builder(JWSAlgorithm.RS512).keyID(key.kid).type(JOSEObjectType(TelemetryJwtContract.TYPE)).build(), claims, key.privateKey), TelemetryJwtRejection.ALGORITHM)

		val encoder = Base64.getUrlEncoder().withoutPadding()
		val none = encoder.encodeToString("""{"alg":"none","typ":"${TelemetryJwtContract.TYPE}","kid":"tk-1"}""".toByteArray()) +
			"." + encoder.encodeToString(claims.toString().toByteArray()) + "."
		assertRejected(none, TelemetryJwtRejection.MALFORMED)
	}

	@Test
	@DisplayName("typ 가 없거나 JWT 면 거부한다 — 사용자 JWT 가 여기서 걸린다")
	fun type() {
		val claims = validClaims(issuedAt = issuedAt).build()
		assertRejected(sign(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.kid).build(), claims, key.privateKey), TelemetryJwtRejection.TYPE)
		assertRejected(sign(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.kid).type(JOSEObjectType.JWT).build(), claims, key.privateKey), TelemetryJwtRejection.TYPE)
	}

	@Test
	@DisplayName("사용자 로그인 JWT 모양의 토큰은 같은 키로 서명돼도 거부한다")
	fun userJwtRejected() {
		val userClaims = JWTClaimsSet.Builder().issuer("https://auth.example.com").audience("pulsemetry")
			.subject(UUID.randomUUID().toString()).claim("tenant_id", UUID.randomUUID().toString()).claim("role", "member")
			.claim("sid", UUID.randomUUID().toString()).jwtID(UUID.randomUUID().toString())
			.issueTime(Date.from(issuedAt)).expirationTime(Date.from(issuedAt.plusSeconds(300))).build()
		val userHeader = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.kid).type(JOSEObjectType.JWT).build()
		assertRejected(sign(userHeader, userClaims, key.privateKey), TelemetryJwtRejection.TYPE)

		// typ 를 맞춰도 사용자 audience 는 통과하지 않는다.
		assertRejected(sign(validHeader(key.kid).build(), userClaims, key.privateKey), TelemetryJwtRejection.ISSUER)
		val userAudience = validClaims(issuedAt = issuedAt).audience("pulsemetry").build()
		assertRejected(sign(validHeader(key.kid).build(), userAudience, key.privateKey), TelemetryJwtRejection.AUDIENCE)

		// 사용자 키(모르는 kid)로 서명한 토큰.
		val userKey = TelemetryJwtFixtures.signingKey("local")
		assertRejected(sign(validHeader("local").build(), validClaims(issuedAt = issuedAt).build(), userKey.privateKey), TelemetryJwtRejection.KID_UNKNOWN)
	}

	@Test
	@DisplayName("crit·jku·jwk·x5u header 가 있으면 거부한다 — 원격·내장 키를 따르지 않는다")
	fun header() {
		assertRejected(token(header = validHeader(key.kid).criticalParams(setOf("exp")).build()), TelemetryJwtRejection.HEADER)
		assertRejected(token(header = validHeader(key.kid).jwkURL(URI("https://evil.example/jwks")).build()), TelemetryJwtRejection.HEADER)
		assertRejected(token(header = validHeader(key.kid).jwk(RSAKey.Builder(key.publicKey).build()).build()), TelemetryJwtRejection.HEADER)
		assertRejected(token(header = validHeader(key.kid).x509CertURL(URI("https://evil.example/cert")).build()), TelemetryJwtRejection.HEADER)
	}

	@Test
	@DisplayName("kid 가 없거나 모르는 값이면 거부한다")
	fun kid() {
		val claims = validClaims(issuedAt = issuedAt).build()
		assertRejected(sign(JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType(TelemetryJwtContract.TYPE)).build(), claims, key.privateKey), TelemetryJwtRejection.KID_UNKNOWN)
		assertRejected(sign(validHeader("tk-unknown").build(), claims, key.privateKey), TelemetryJwtRejection.KID_UNKNOWN)
	}

	@Test
	@DisplayName("iss·aud·scope 가 계약과 다르면 각각의 사유로 거부한다")
	fun purpose() {
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).issuer("pulsemetry").build()), TelemetryJwtRejection.ISSUER)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).audience(listOf(TelemetryJwtContract.AUDIENCE, "other")).build()), TelemetryJwtRejection.AUDIENCE)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).audience(null as String?).build()), TelemetryJwtRejection.AUDIENCE)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).claim("scope", "telemetry:read").build()), TelemetryJwtRejection.SCOPE)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).claim("scope", listOf("telemetry:write")).build()), TelemetryJwtRejection.SCOPE)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).claim("scope", null).build()), TelemetryJwtRejection.SCOPE)
	}

	@Test
	@DisplayName("필수 claim 이 빠지면 claim_missing, 형식이 다르면 claim_format 이다")
	fun requiredClaims() {
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).subject(null).build()), TelemetryJwtRejection.CLAIM_MISSING)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).claim("tenant_id", null).build()), TelemetryJwtRejection.CLAIM_MISSING)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).jwtID(null).build()), TelemetryJwtRejection.CLAIM_MISSING)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).issueTime(null).build()), TelemetryJwtRejection.CLAIM_MISSING)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).expirationTime(null).build()), TelemetryJwtRejection.CLAIM_MISSING)

		assertRejected(token(claims = validClaims(issuedAt = issuedAt).subject("installation-1").build()), TelemetryJwtRejection.CLAIM_FORMAT)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).claim("tenant_id", 42).build()), TelemetryJwtRejection.CLAIM_FORMAT)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).jwtID(UUID.randomUUID().toString().uppercase()).build()), TelemetryJwtRejection.CLAIM_FORMAT)
		assertRejected(token(claims = validClaims(issuedAt = issuedAt).jwtID("1-1-1-1-1").build()), TelemetryJwtRejection.CLAIM_FORMAT)
		// iat 가 숫자가 아니면 claim 집합 자체가 파싱되지 않는다.
		val payload = validClaims(issuedAt = issuedAt).build().toJSONObject().toMutableMap().apply { put("iat", "yesterday") }
		val stringIat = JWSObject(validHeader(key.kid).build(), Payload(payload)).also { it.sign(RSASSASigner(key.privateKey)) }.serialize()
		assertRejected(stringIat, TelemetryJwtRejection.CLAIM_FORMAT)
	}

	@Test
	@DisplayName("수명은 3시간 이하이고 만료가 발급보다 뒤여야 한다")
	fun lifetime() {
		val tooLong = validClaims(issuedAt = issuedAt).expirationTime(Date.from(issuedAt.plusSeconds(3 * 3600 + 1))).build()
		assertRejected(token(claims = tooLong), TelemetryJwtRejection.LIFETIME)
		val zero = validClaims(issuedAt = issuedAt).expirationTime(Date.from(issuedAt)).build()
		assertRejected(token(claims = zero), TelemetryJwtRejection.LIFETIME)
	}

	@Test
	@DisplayName("시계 오차 30초 경계 — iat−30s 와 exp+30s 는 통과하고 그 밖은 거부한다")
	fun skewBoundary() {
		val raw = token()
		val expiresAt = issuedAt.plusSeconds(3 * 3600)

		assertThat(verifier.verify(raw, issuedAt.minusSeconds(30)).credentialId).isNotNull()
		assertThat(verifier.verify(raw, expiresAt.plusSeconds(30)).credentialId).isNotNull()
		assertRejected(raw, TelemetryJwtRejection.NOT_YET_VALID, issuedAt.minusSeconds(30).minusMillis(1))
		assertRejected(raw, TelemetryJwtRejection.EXPIRED, expiresAt.plusSeconds(30).plusMillis(1))
	}

	@Test
	@DisplayName("checkTime 은 접수 시각으로 같은 구간을 다시 본다")
	fun recheckAtAcceptance() {
		val verified = verifier.verify(token(), now)
		TelemetryJwtVerifier.checkTime(verified, issuedAt.plusSeconds(3 * 3600 + 30))
		assertThatThrownBy { TelemetryJwtVerifier.checkTime(verified, issuedAt.plusSeconds(3 * 3600 + 31)) }
			.isInstanceOfSatisfying(TelemetryJwtException::class.java) { assertThat(it.reason).isEqualTo(TelemetryJwtRejection.EXPIRED) }
	}

	@Test
	@DisplayName("nbf 가 오차 밖으로 미래면 거부한다")
	fun notBefore() {
		val claims = validClaims(issuedAt = issuedAt).notBeforeTime(Date.from(now.plusSeconds(31))).build()
		assertRejected(token(claims = claims), TelemetryJwtRejection.NOT_YET_VALID)
		val withinSkew = validClaims(issuedAt = issuedAt).notBeforeTime(Date.from(now.plusSeconds(30))).build()
		assertThat(verifier.verify(token(claims = withinSkew), now)).isNotNull()
	}

	@Test
	@DisplayName("키 회전 — 신·구 공개키를 함께 두면 둘 다 통과하고, 구 키를 빼면 kid 를 모른다")
	fun rotation() {
		val next = TelemetryJwtFixtures.signingKey("tk-2")
		val oldToken = key.issuer().issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), issuedAt).token
		val newToken = next.issuer(key).issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), issuedAt).token

		val both = key.verifier(next)
		assertThat(both.verify(oldToken, now).keyId).isEqualTo("tk-1")
		assertThat(both.verify(newToken, now).keyId).isEqualTo("tk-2")

		val onlyNew = next.verifier()
		assertThatThrownBy { onlyNew.verify(oldToken, now) }
			.isInstanceOfSatisfying(TelemetryJwtException::class.java) { assertThat(it.reason).isEqualTo(TelemetryJwtRejection.KID_UNKNOWN) }
	}

	@Test
	@DisplayName("형식이 아니거나 너무 긴 문자열은 malformed 이고 메시지에 원문이 없다")
	fun malformed() {
		assertRejected("ptt_abcdef", TelemetryJwtRejection.MALFORMED)
		assertRejected("a.b", TelemetryJwtRejection.MALFORMED)
		assertRejected("x".repeat(TelemetryJwtContract.MAX_TOKEN_LENGTH + 1), TelemetryJwtRejection.MALFORMED)
		val canary = "CANARY-${UUID.randomUUID()}"
		assertThatThrownBy { verifier.verify("$canary.$canary.$canary", now) }
			.isInstanceOf(TelemetryJwtException::class.java)
			.satisfies({ assertThat(it.message).doesNotContain(canary) })
	}

	@Test
	@DisplayName("compact JWS 모양 판정은 점 둘과 base64url 마디만 본다")
	fun compactShape() {
		assertThat(TelemetryJwtVerifier.isCompactJws(token())).isTrue()
		assertThat(TelemetryJwtVerifier.isCompactJws("ptt_" + "a".repeat(43))).isFalse()
		assertThat(TelemetryJwtVerifier.isCompactJws("a.b.")).isFalse()
		assertThat(TelemetryJwtVerifier.isCompactJws("a.b.c.d")).isFalse()
		assertThat(TelemetryJwtVerifier.isCompactJws("a b.c.d")).isFalse()
	}

	@Test
	@DisplayName("공개키가 없거나 2048비트보다 작으면 검증기를 만들 수 없다")
	fun verifierKeys() {
		assertThatThrownBy { TelemetryJwtVerifier(emptyMap()) }.isInstanceOf(IllegalArgumentException::class.java)
		val small = TelemetryJwtFixtures.signingKey("small", bits = 1024)
		assertThatThrownBy { small.verifier() }.isInstanceOf(IllegalArgumentException::class.java)
	}
}
