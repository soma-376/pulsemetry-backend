package com.team376.pulsemetry.enrollment.auth

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8

/** 외부 IdP와의 HTTP 토큰 교환은 실제로 실행하고 브라우저의 callback 이동만 MockMvc로 전달한다. */
abstract class OidcTestSupport : AbstractUserAuthApiTest() {
    @Autowired lateinit var oidcProperties: OidcProperties
    abstract val testIssuer: String
    open val testSubject = "test-subject-1"

    @BeforeEach fun bindIdentity() {
        configureTenant(tenant)
        jdbc.sql("TRUNCATE enrollment.oidc_login_sessions CASCADE").update()
        jdbc.sql("UPDATE enrollment.members SET status='active', oidc_subject=:subject WHERE id=:id")
            .param("subject", testSubject).param("id", member).update()
    }

    protected fun configureTenant(id: java.util.UUID) {
        jdbc.sql("""UPDATE enrollment.tenants SET oidc_issuer=:issuer,oidc_client_id='pulsemetry-backend',
            oidc_client_secret_ref='config:mock',sso_enabled=true,oidc_require_verified_email=true WHERE id=:id""")
            .param("issuer", testIssuer).param("id", id).update()
    }

    data class RoundTrip(val location: URI, val cookie: Cookie)
    protected fun begin(loginHint: String? = null): RoundTrip {
        val result = mvc.perform(get("/api/v1/auth/oidc/authorize")
            .param("tenant_id", tenant.toString()).param("redirect_uri", redirect)
            .param("state", "client-state-1234567890").param("code_challenge", challenge)
            .param("code_challenge_method", "S256").apply {
                loginHint?.let { param("login_hint", it) }
            }).andReturn().response
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(302)
        assertThat(result.getHeader("Cache-Control")).contains("no-store")
        val cookie = result.getHeaders("Set-Cookie").single { it.startsWith("PULSEMETRY_OIDC=") }
        assertThat(cookie).contains("HttpOnly", "SameSite=Lax", "Path=/api/v1/auth/oidc")
        val uri = URI(result.getHeader("Location")!!)
        assertThat(query(uri)).containsKeys("state", "nonce", "code_challenge")
        assertThat(query(uri)["code_challenge_method"]).isEqualTo("S256")
        assertThat(query(uri)["state"]).isNotEqualTo("client-state-1234567890")
        assertThat(query(uri)["code_challenge"]).isNotEqualTo(challenge)
        return RoundTrip(uri, Cookie("PULSEMETRY_OIDC", cookie.substringAfter('=').substringBefore(';')))
    }

    protected fun callback(location: URI, cookie: Cookie? = null): MvcResult {
        val request = get(location)
        cookie?.let { request.cookie(it) }
        return mvc.perform(request).andReturn()
    }

    protected fun assertTokens(result: MvcResult) {
        assertThat(result.response.status).withFailMessage(result.response.contentAsString).isEqualTo(302)
        val location = URI(result.response.getHeader("Location")!!)
        assertThat(location.toString()).startsWith("$redirect?")
        assertThat(query(location).keys).containsExactlyInAnyOrder("code", "state")
        assertThat(query(location)["state"]).isEqualTo("client-state-1234567890")
        val body = mapOf("code" to query(location).getValue("code"), "redirect_uri" to redirect, "code_verifier" to verifier)
        val response = post("token", body)
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        val accessToken = mapper.readTree(response.body()).path("access_token").asString()
        assertThat(accessToken).isNotBlank()
        val me = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/auth/me"))
            .header("Authorization", "Bearer $accessToken").GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(me.statusCode()).withFailMessage(me.body()).isEqualTo(200)
        assertThat(mapper.readTree(me.body()).path("memberId").asString()).isEqualTo(member.toString())
        val databaseRole = jdbc.sql("SELECT role::text FROM enrollment.members WHERE id=:id").param("id", member).query(String::class.java).single()
        assertThat(mapper.readTree(me.body()).path("role").asString()).isEqualTo(databaseRole)
        assertThat(post("token", body).statusCode()).isEqualTo(401)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.oidc_login_sessions").query(Long::class.java).single()).isZero()
    }

    companion object {
        fun properties(registry: DynamicPropertyRegistry, issuer: String) {
            registry.add("pulsemetry.oidc.enabled") { true }
            registry.add("pulsemetry.oidc.allow-insecure-localhost") { true }
            registry.add("pulsemetry.oidc.callback-base-url") { "http://localhost:8080" }
            registry.add("pulsemetry.oidc.failure-redirect-uri") { "http://localhost:3000/auth/callback" }
            registry.add("pulsemetry.user-auth.allowed-redirect-uris") { "http://localhost:3000/auth/callback" }
            registry.add("pulsemetry.user-auth.allowed-origins") { "http://localhost:3000" }
            registry.add("pulsemetry.oidc.callback-registration-id") { "mock" }
            registry.add("pulsemetry.oidc.client-secrets.mock") { "local-only-pulsemetry-oidc-secret" }
        }
        fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), UTF_8) to URLDecoder.decode(it.substringAfter('='), UTF_8)
        }
    }
}
