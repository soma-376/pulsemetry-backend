package com.team376.pulsemetry.telemetry.auth

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID

/**
 * 서명한 telemetry JWT 와 DB 자격 기록에 같이 남길 값.
 *
 * [issuedAt]·[expiresAt] 은 초 단위로 자른 값이다 — JWT 의 `iat`·`exp` 와 `telemetry_tokens.issued_at`·`expires_at` 이
 * 같은 값이어야 Consumer 의 대조(ADR 0065 Decision 6)가 성립한다.
 */
public class IssuedTelemetryJwt(
	public val token: String,
	public val credentialId: UUID,
	public val keyId: String,
	public val issuedAt: Instant,
	public val expiresAt: Instant,
) {
	/** 토큰 원문을 문자열 표현에 싣지 않는다. */
	override fun toString(): String = "IssuedTelemetryJwt(credentialId=$credentialId, keyId=$keyId)"
}

/**
 * telemetry JWT 발급기. enrollment 만 만든다 — 개인키는 그 앱에만 공급된다(ADR 0065 Decision 2).
 *
 * 발급 순서(행 id 를 먼저 만들고, 서명한 뒤, 그 문자열의 HMAC 을 `token_hash` 에 저장)는 호출자인 enrollment 가 지킨다.
 * 여기서는 받은 id 를 `jti` 로, 받은 시각을 초로 잘라 `iat` 로 쓴다.
 *
 * 기동 때 활성 kid 의 공개키와 개인키의 modulus 가 같은지 본다 — 엉뚱한 공개키를 활성 kid 에 걸면 발급한 토큰이 모든
 * 검증자에서 서명 오류가 된다. 이 검사는 Secret 버전과 파일의 대응까지 보장하지 않는다.
 *
 * **스테레오타입을 달지 않는다**(ADR 0011).
 */
public class TelemetryJwtIssuer(
	private val activeKid: String,
	private val privateKey: RSAPrivateKey,
	publicKeys: Map<String, RSAPublicKey>,
) {
	private val signer = RSASSASigner(privateKey)

	init {
		require(activeKid.isNotBlank()) { "telemetry JWT 활성 kid(pulsemetry.telemetry-jwt.active-kid)가 비어 있다" }
		require(privateKey.modulus.bitLength() >= TelemetryJwtContract.MIN_RSA_BITS) {
			"telemetry JWT 개인키가 ${TelemetryJwtContract.MIN_RSA_BITS}비트보다 작다"
		}
		val active = requireNotNull(publicKeys[activeKid]) {
			"telemetry JWT 활성 kid 의 공개키가 public-key-files 에 없다: kid=$activeKid"
		}
		require(active.modulus == privateKey.modulus) {
			"telemetry JWT 활성 kid 의 공개키와 개인키가 짝이 아니다: kid=$activeKid"
		}
	}

	/**
	 * [credentialId] 를 `jti` 로 서명한다. `iat` 는 [now] 를 초로 자른 값, `exp` 는 그 3시간 뒤다.
	 */
	public fun issue(credentialId: UUID, tenantId: UUID, installationId: UUID, now: Instant): IssuedTelemetryJwt {
		val issuedAt = now.truncatedTo(ChronoUnit.SECONDS)
		val expiresAt = issuedAt.plus(TelemetryJwtContract.LIFETIME)
		val claims = JWTClaimsSet.Builder()
			.issuer(TelemetryJwtContract.ISSUER)
			.audience(TelemetryJwtContract.AUDIENCE)
			.subject(installationId.toString())
			.claim(TelemetryJwtContract.TENANT_ID_CLAIM, tenantId.toString())
			.claim(TelemetryJwtContract.SCOPE_CLAIM, TelemetryJwtContract.SCOPE)
			.jwtID(credentialId.toString())
			.issueTime(Date.from(issuedAt))
			.expirationTime(Date.from(expiresAt))
			.build()
		val header = JWSHeader.Builder(JWSAlgorithm.RS256)
			.keyID(activeKid)
			.type(JOSEObjectType(TelemetryJwtContract.TYPE))
			.build()
		val jwt = SignedJWT(header, claims).also { it.sign(signer) }
		return IssuedTelemetryJwt(
			token = jwt.serialize(),
			credentialId = credentialId,
			keyId = activeKid,
			issuedAt = issuedAt,
			expiresAt = expiresAt,
		)
	}
}
