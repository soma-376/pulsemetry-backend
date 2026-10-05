package com.team376.pulsemetry.enrollment.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 서명/클레임/PKCE 실패를 만드는 테스트 전용 IdP. 운영 코드에는 mock 우회 경로가 없다. */
class MockOidcProvider : AutoCloseable {
    enum class Fault { NONE, ISSUER, AUDIENCE, NONCE, EXPIRED, SIGNATURE, UNAVAILABLE, EMAIL_UNVERIFIED, EMAIL_MISSING, EMAIL_VERIFICATION_MISSING }
    @Volatile var fault = Fault.NONE
    @Volatile var email = "user@example.com"
    @Volatile var subject = "test-subject-1"
    private val mapper = JsonMapper.builder().build()
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val key = RSAKey.Builder(pair.public as RSAPublicKey).privateKey(pair.private as RSAPrivateKey).keyID("test").build()
    private val codes = ConcurrentHashMap<String, Map<String, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val issuer = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/.well-known/openid-configuration") { exchange ->
            val body = mapper.writeValueAsBytes(mapOf("issuer" to issuer, "authorization_endpoint" to "$issuer/authorize",
                "token_endpoint" to "$issuer/token", "jwks_uri" to "$issuer/jwks", "response_types_supported" to listOf("code"),
                "subject_types_supported" to listOf("public"), "id_token_signing_alg_values_supported" to listOf("RS256"),
                "token_endpoint_auth_methods_supported" to listOf("client_secret_basic")))
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/jwks") { exchange ->
            val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/token") { exchange ->
            val form = OidcTestSupport.query(URI("http://form/?" + exchange.requestBody.bufferedReader().readText()))
            val saved = codes.remove(form["code"])
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(form["code_verifier"].orEmpty().toByteArray()))
            val basic = "Basic " + Base64.getEncoder().encodeToString("pulsemetry-backend:local-only-pulsemetry-oidc-secret".toByteArray())
            val valid = saved != null && saved["code_challenge"] == challenge && saved["redirect_uri"] == form["redirect_uri"] &&
                exchange.requestHeaders.getFirst("Authorization") == basic && form["grant_type"] == "authorization_code"
            val status = if (fault == Fault.UNAVAILABLE) 503 else if (valid) 200 else 400
            val body = if (status == 200) mapper.writeValueAsBytes(mapOf("access_token" to "idp-access-token-not-for-api",
                "token_type" to "Bearer", "expires_in" to 300, "id_token" to token(saved!!.getValue("nonce"))))
                else mapper.writeValueAsBytes(mapOf("error" to if (status == 503) "temporarily_unavailable" else "invalid_grant"))
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun authorize(uri: URI): URI {
        val fields = OidcTestSupport.query(uri)
        val code = UUID.randomUUID().toString()
        codes[code] = fields
        return URI(fields.getValue("redirect_uri") + "?code=$code&state=" + URLEncoder.encode(fields.getValue("state"), Charsets.UTF_8))
    }
    private fun token(nonce: String): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder().issuer(if (fault == Fault.ISSUER) "$issuer/wrong" else issuer)
            .subject(subject).audience(if (fault == Fault.AUDIENCE) "other-client" else "pulsemetry-backend")
            .issueTime(Date.from(now.minusSeconds(600))).expirationTime(Date.from(now.plusSeconds(if (fault == Fault.EXPIRED) -300 else 300)))
            .claim("nonce", if (fault == Fault.NONCE) "wrong" else nonce)
            .claim("email", if (fault == Fault.EMAIL_MISSING) null else email)
            .claim("email_verified", if (fault == Fault.EMAIL_VERIFICATION_MISSING) null else fault != Fault.EMAIL_UNVERIFIED)
            // 제공자 그룹으로 서비스 DB의 역할을 덮어쓰면 안 된다.
            .claim("cognito:groups", listOf("owner"))
            .claim("role", "owner").build()
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims)
        val signingKey = if (fault == Fault.SIGNATURE) KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private else pair.private
        token.sign(RSASSASigner(signingKey as RSAPrivateKey))
        return token.serialize()
    }
    override fun close() = server.stop(0)
}
