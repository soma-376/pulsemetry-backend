package com.team376.pulsemetry.telemetry.auth.support

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtContract
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtIssuer
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * telemetry JWT 테스트 도구. 키쌍 생성·PEM 파일 쓰기·발급기와 검증기 조립·규칙을 어긴 토큰 위조.
 *
 * 키는 매번 새로 만든다 — 저장소에 키 파일을 두지 않는다.
 */
object TelemetryJwtFixtures {

	/** 키 하나와 그 kid. */
	class SigningKey(val kid: String, val keyPair: KeyPair) {
		val privateKey: RSAPrivateKey get() = keyPair.private as RSAPrivateKey
		val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

		fun issuer(vararg extraVerifierKeys: SigningKey): TelemetryJwtIssuer =
			TelemetryJwtIssuer(kid, privateKey, publicKeys(this, *extraVerifierKeys))

		fun verifier(vararg extra: SigningKey): TelemetryJwtVerifier = TelemetryJwtVerifier(publicKeys(this, *extra))

		/** `private.pem` 과 `<kid>.pem` 을 [dir] 에 쓰고 두 경로를 돌려준다. */
		fun writePem(dir: Path): Pair<Path, Path> {
			Files.createDirectories(dir)
			val privatePath = dir.resolve("$kid-private.pem")
			val publicPath = dir.resolve("$kid.pem")
			Files.writeString(privatePath, privatePem(privateKey))
			Files.writeString(publicPath, publicPem(publicKey))
			return privatePath to publicPath
		}
	}

	fun signingKey(kid: String = "telemetry-test", bits: Int = 2048): SigningKey = SigningKey(kid, keyPair(bits))

	fun keyPair(bits: Int = 2048): KeyPair =
		KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

	fun publicKeys(vararg keys: SigningKey): Map<String, RSAPublicKey> = keys.associate { it.kid to it.publicKey }

	fun privatePem(key: RSAPrivateKey): String = pem("PRIVATE KEY", key.encoded)

	fun publicPem(key: RSAPublicKey): String = pem("PUBLIC KEY", key.encoded)

	private fun pem(label: String, der: ByteArray): String =
		"-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"

	/** 발급기가 쓰는 것과 같은 header·claim. 위조 테스트가 한 항목만 바꿔 쓴다. */
	fun validHeader(kid: String): JWSHeader.Builder =
		JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).type(JOSEObjectType(TelemetryJwtContract.TYPE))

	fun validClaims(
		credentialId: UUID = UUID.randomUUID(),
		tenantId: UUID = UUID.randomUUID(),
		installationId: UUID = UUID.randomUUID(),
		issuedAt: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
	): JWTClaimsSet.Builder = JWTClaimsSet.Builder()
		.issuer(TelemetryJwtContract.ISSUER)
		.audience(TelemetryJwtContract.AUDIENCE)
		.subject(installationId.toString())
		.claim(TelemetryJwtContract.TENANT_ID_CLAIM, tenantId.toString())
		.claim(TelemetryJwtContract.SCOPE_CLAIM, TelemetryJwtContract.SCOPE)
		.jwtID(credentialId.toString())
		.issueTime(Date.from(issuedAt))
		.expirationTime(Date.from(issuedAt.plus(TelemetryJwtContract.LIFETIME)))

	/** header·claim 을 그대로 RS256 으로 서명한다. 검증기가 거부해야 하는 토큰을 만든다. */
	fun sign(header: JWSHeader, claims: JWTClaimsSet, key: RSAPrivateKey): String =
		SignedJWT(header, claims).also { it.sign(RSASSASigner(key)) }.serialize()
}
