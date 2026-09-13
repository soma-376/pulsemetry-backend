package com.team376.pulsemetry.security.user

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Date
import java.util.UUID

/** 허용된 공개키만 검증에 쓴다. 토큰의 jku/x5u로 외부 키를 가져오지 않는다 (ADR 0018). */
class UserJwt(
    private val issuer: String,
    private val audience: String,
    private val activeKid: String,
    private val privateKey: RSAPrivateKey,
    private val publicKeys: Map<String, RSAPublicKey>,
    private val clock: Clock,
    val sessionKind: String = "cli",
) {
    val lifetime: Duration = if (sessionKind == "web") Duration.ofHours(8) else Duration.ofMinutes(5)
    init {
        require(sessionKind in setOf("cli", "web"))
        require(issuer.isNotBlank() && audience.isNotBlank() && activeKid.isNotBlank())
        require(privateKey.modulus.bitLength() >= 2048)
        require(publicKeys.values.all { it.modulus.bitLength() >= 2048 })
        require(publicKeys[activeKid]?.modulus == privateKey.modulus) { "활성 서명키와 공개키가 일치해야 한다" }
    }

    fun issue(identity: UserIdentity): String {
        require(identity.sessionKind == sessionKind)
        require((sessionKind == "web") == (identity.revision == null))
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(identity.memberId.toString())
            .claim("tenant_id", identity.tenantId.toString()).claim("role", identity.role)
            .claim("session_kind", sessionKind).claim("sid", identity.sessionId.toString()).claim("manifest_revision", identity.revision)
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(lifetime))).jwtID(UUID.randomUUID().toString()).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(activeKid).type(JOSEObjectType.JWT).build(), claims)
            .also { it.sign(RSASSASigner(privateKey)) }.serialize()
    }

    fun verify(raw: String): UserIdentity {
        if (raw.length > 8192) invalid()
        try {
            val jwt = SignedJWT.parse(raw)
            if (jwt.header.algorithm != JWSAlgorithm.RS256 || jwt.header.type != JOSEObjectType.JWT ||
                !jwt.header.criticalParams.isNullOrEmpty()) invalid()
            val key = publicKeys[jwt.header.keyID] ?: invalid()
            if (!jwt.verify(RSASSAVerifier(key))) invalid()
            val c = jwt.jwtClaimsSet
            val now = clock.instant()
            val issued = c.issueTime?.toInstant() ?: invalid()
            val expires = c.expirationTime?.toInstant() ?: invalid()
            if (c.issuer != issuer || c.audience != listOf(audience) || c.jwtid.isNullOrBlank() ||
                !expires.isAfter(now.minusSeconds(30)) || issued.isAfter(now.plusSeconds(30)) ||
                expires <= issued || expires > issued.plus(lifetime) || c.notBeforeTime?.toInstant()?.isAfter(now.plusSeconds(30)) == true) invalid()
            val kind = c.getStringClaim("session_kind") ?: "cli"
            val revision = c.getIntegerClaim("manifest_revision")
            if (kind != sessionKind || (kind == "web") != (revision == null)) invalid()
            val role = c.getStringClaim("role")
            if (role !in setOf("owner", "admin", "member")) invalid()
            return UserIdentity(UUID.fromString(c.subject ?: invalid()), UUID.fromString(c.getStringClaim("tenant_id") ?: invalid()), role,
                UUID.fromString(c.getStringClaim("sid") ?: invalid()), revision, kind)
        } catch (_: ParseException) { invalid() }
        catch (_: IllegalArgumentException) { invalid() }
        catch (_: JOSEException) { invalid() }
    }

    companion object {
        fun privateKey(path: Path): RSAPrivateKey = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(pem(path, "PRIVATE KEY"))) as RSAPrivateKey
        fun publicKey(path: Path): RSAPublicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(pem(path, "PUBLIC KEY"))) as RSAPublicKey
        private fun pem(path: Path, label: String): ByteArray = Base64.getDecoder().decode(Files.readString(path)
            .replace("-----BEGIN $label-----", "").replace("-----END $label-----", "").replace(Regex("\\s"), ""))
    }
}
