package com.team376.pulsemetry.telemetry.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.interfaces.RSAPublicKey
import java.text.ParseException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 서명·용도·형식을 통과한 telemetry JWT 의 내용. **아직 RDB 판정 전이다** — 이 값으로 바로 검증 완료 신원을 만들지 않는다
 * (ADR 0065 Decision 6).
 */
public class VerifiedTelemetryJwt(
	/** `jti` = `telemetry_tokens.id`. */
	public val credentialId: UUID,
	public val tenantId: UUID,
	/** `sub`. */
	public val installationId: UUID,
	public val issuer: String,
	public val keyId: String,
	public val issuedAt: Instant,
	public val expiresAt: Instant,
)

/**
 * telemetry JWT 검증기. Gateway 와 HTTP ingest 가 쓴다(ADR 0065 Decision 7 · ADR 0066 Decision 2).
 *
 * ## 검사 순서
 *
 * 1. 길이·compact JWS 형식. `alg=none` 은 파싱 단계에서 떨어진다.
 * 2. header — `alg=RS256`, 전용 `typ`, 쓰지 않는 header(`crit`·`jku`·`jwk`·`x5u`·`x5c`) 없음, 아는 `kid`.
 * 3. 서명. **claim 은 서명이 맞은 뒤에만 읽는다** — 위조 토큰의 claim 으로 거부 사유가 갈리지 않게 한다.
 * 4. `iss`·`aud`(단일 값)·`scope`·필수 claim 의 형식(UUID 정규 표기·정수 초)·수명(최대 3시간).
 * 5. 시간 — `now ∈ [iat − 30s, exp + 30s]`, `nbf` 가 있으면 `now + 30s` 이전.
 *
 * 공개키는 생성자로 받은 `kid → 키` 맵뿐이다. 토큰이 가리키는 URL·내장 키를 따르지 않는다.
 * 키 회전 동안 맵에 신·구 키가 함께 있다.
 *
 * **스테레오타입을 달지 않는다**(ADR 0011).
 */
public class TelemetryJwtVerifier(publicKeys: Map<String, RSAPublicKey>) {

	private val verifiers: Map<String, RSASSAVerifier>

	init {
		require(publicKeys.isNotEmpty()) { "telemetry JWT 공개키가 하나도 없다" }
		require(publicKeys.values.all { it.modulus.bitLength() >= TelemetryJwtContract.MIN_RSA_BITS }) {
			"telemetry JWT 공개키가 ${TelemetryJwtContract.MIN_RSA_BITS}비트보다 작다"
		}
		verifiers = publicKeys.mapValues { (_, key) -> RSASSAVerifier(key) }
	}

	/** 허용한 kid 목록. 로그·진단용이며 키 자체는 내보내지 않는다. */
	public val keyIds: Set<String> get() = verifiers.keys

	/** @throws TelemetryJwtException 거부 사유와 함께. 메시지에 토큰을 싣지 않는다. */
	public fun verify(raw: String, now: Instant): VerifiedTelemetryJwt {
		if (raw.length > TelemetryJwtContract.MAX_TOKEN_LENGTH) reject(TelemetryJwtRejection.MALFORMED)
		val jwt = try {
			SignedJWT.parse(raw)
		} catch (_: ParseException) {
			reject(TelemetryJwtRejection.MALFORMED)
		}

		val header = jwt.header
		if (header.algorithm != JWSAlgorithm.RS256) reject(TelemetryJwtRejection.ALGORITHM)
		if (header.type?.type != TelemetryJwtContract.TYPE) reject(TelemetryJwtRejection.TYPE)
		if (!header.criticalParams.isNullOrEmpty() || header.getJWKURL() != null || header.getJWK() != null ||
			header.getX509CertURL() != null || !header.getX509CertChain().isNullOrEmpty()
		) {
			reject(TelemetryJwtRejection.HEADER)
		}
		val keyId = header.keyID ?: reject(TelemetryJwtRejection.KID_UNKNOWN)
		val verifier = verifiers[keyId] ?: reject(TelemetryJwtRejection.KID_UNKNOWN)
		val signed = try {
			jwt.verify(verifier)
		} catch (_: JOSEException) {
			false
		}
		if (!signed) reject(TelemetryJwtRejection.SIGNATURE)

		val claims = try {
			jwt.jwtClaimsSet
		} catch (_: ParseException) {
			reject(TelemetryJwtRejection.CLAIM_FORMAT)
		}
		if (claims.issuer != TelemetryJwtContract.ISSUER) reject(TelemetryJwtRejection.ISSUER)
		if (claims.audience != listOf(TelemetryJwtContract.AUDIENCE)) reject(TelemetryJwtRejection.AUDIENCE)
		if (claims.getClaim(TelemetryJwtContract.SCOPE_CLAIM) != TelemetryJwtContract.SCOPE) reject(TelemetryJwtRejection.SCOPE)

		val token = VerifiedTelemetryJwt(
			credentialId = uuidClaim(claims.jwtid),
			tenantId = uuidClaim(stringClaim(claims, TelemetryJwtContract.TENANT_ID_CLAIM)),
			installationId = uuidClaim(claims.subject),
			issuer = TelemetryJwtContract.ISSUER,
			keyId = keyId,
			issuedAt = secondsClaim(claims, "iat"),
			expiresAt = secondsClaim(claims, "exp"),
		)
		if (!token.expiresAt.isAfter(token.issuedAt) ||
			Duration.between(token.issuedAt, token.expiresAt) > TelemetryJwtContract.LIFETIME
		) {
			reject(TelemetryJwtRejection.LIFETIME)
		}
		val notBefore = try {
			claims.notBeforeTime?.toInstant()
		} catch (_: Exception) {
			reject(TelemetryJwtRejection.CLAIM_FORMAT)
		}
		if (notBefore != null && notBefore.isAfter(now.plus(TelemetryJwtContract.CLOCK_SKEW))) {
			reject(TelemetryJwtRejection.NOT_YET_VALID)
		}
		checkTime(token, now)
		return token
	}

	public companion object {

		/**
		 * [at] 이 `[iat − 30s, exp + 30s]` 안인지 본다. Gateway 가 본문을 다 읽고 접수 시각을 확정한 뒤 다시 부른다
		 * (ADR 0066 Decision 2 의 4번) — 10 MiB 를 읽는 사이에 만료될 수 있다.
		 */
		public fun checkTime(token: VerifiedTelemetryJwt, at: Instant) {
			if (at.isBefore(token.issuedAt.minus(TelemetryJwtContract.CLOCK_SKEW))) reject(TelemetryJwtRejection.NOT_YET_VALID)
			if (at.isAfter(token.expiresAt.plus(TelemetryJwtContract.CLOCK_SKEW))) reject(TelemetryJwtRejection.EXPIRED)
		}

		/**
		 * compact JWS 모양(점 둘, 세 마디가 비어 있지 않은 base64url)인가. HTTP ingest 가 Bearer 를 해시 조회와 JWT 검증 중
		 * 어디로 보낼지 가르는 데 쓴다 — 서명은 보지 않는다.
		 */
		public fun isCompactJws(token: String): Boolean = COMPACT_JWS.matches(token)

		private val COMPACT_JWS = Regex("""[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
		private val CANONICAL_UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

		private fun reject(reason: TelemetryJwtRejection): Nothing = throw TelemetryJwtException(reason)

		private fun stringClaim(claims: JWTClaimsSet, name: String): String? =
			when (val value = claims.getClaim(name)) {
				null -> null
				is String -> value
				else -> reject(TelemetryJwtRejection.CLAIM_FORMAT)
			}

		/** 발급자는 `UUID.toString()` 의 소문자 표기만 쓴다. 느슨한 `UUID.fromString` 이 받는 짧은 표기를 받지 않는다. */
		private fun uuidClaim(value: String?): UUID {
			if (value == null) reject(TelemetryJwtRejection.CLAIM_MISSING)
			if (!CANONICAL_UUID.matches(value)) reject(TelemetryJwtRejection.CLAIM_FORMAT)
			return UUID.fromString(value)
		}

		/**
		 * `iat`·`exp` 는 NumericDate 다. 숫자가 아니면 claim 집합 파싱에서 이미 떨어지고(위 `CLAIM_FORMAT`), nimbus 는 소수 초를
		 * 정수 초로 읽는다 — 발급자도 정수 초만 쓰므로 DB 의 초 단위 값과 그대로 대조된다.
		 */
		private fun secondsClaim(claims: JWTClaimsSet, name: String): Instant {
			val date = try {
				claims.getDateClaim(name)
			} catch (_: ParseException) {
				reject(TelemetryJwtRejection.CLAIM_FORMAT)
			} ?: reject(TelemetryJwtRejection.CLAIM_MISSING)
			return date.toInstant()
		}
	}
}
