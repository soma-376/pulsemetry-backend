package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
abstract class AbstractUserAuthApiTest {

    @LocalServerPort protected var port: Int = 0
    @Autowired protected lateinit var mvc: MockMvc
    @Autowired protected lateinit var data: EnrollmentTestData
    @Autowired protected lateinit var jdbc: JdbcClient
    @Autowired protected lateinit var mapper: ObjectMapper
    @Autowired protected lateinit var limiter: com.team376.pulsemetry.security.user.AuthRateLimiter
    @Autowired protected lateinit var auth: UserAuthService
    @Autowired protected lateinit var clock: AuthTestClock
    protected val http = HttpClient.newHttpClient()
    protected lateinit var tenant: UUID
    protected lateinit var member: UUID
    protected lateinit var code: String
    protected val email = "user@example.com"
    protected val oidcIssuer = "https://idp.example.test"
    protected val oidcSubject = "test-subject-1"
    protected val verifier = "v".repeat(43)
    protected val redirect = "http://127.0.0.1:12345/callback"
    protected val challenge: String get() = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    @BeforeEach fun setup() {
        data.reset()
        jdbc.sql("TRUNCATE enrollment.auth_attempts").update()
        clock.now = Instant.parse("2026-09-09T12:00:00Z")
        tenant = data.tenant().id
        member = data.member(tenant, email, role = MemberRole.member, status = MemberStatus.invited).id
        code = InvitationCode.generate()
        data.invitation(tenant, member, code, expiresAt = clock.now.plusSeconds(3600))
        data.activeManifest(tenant, member, 3)
    }

    protected fun post(path: String, body: Any): HttpResponse<String> = http.send(HttpRequest.newBuilder(
        URI("http://localhost:$port/v1/auth/$path")).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
    protected fun provisionMember() {
        jdbc.sql("UPDATE enrollment.tenants SET oidc_issuer=:issuer,oidc_client_id='test',oidc_client_secret_ref='config:mock',sso_enabled=true WHERE id=:id")
            .param("issuer", oidcIssuer).param("id", tenant).update()
        jdbc.sql("UPDATE enrollment.members SET status='active',oidc_subject=:subject WHERE id=:id")
            .param("subject", oidcSubject).param("id", member).update()
    }
    protected fun authorize(redirectUri: String = redirect): String = auth.authorizeOidc(tenant, oidcIssuer, oidcSubject,
        redirectUri, "client-state-1234567890", challenge, "S256")
    protected fun login(): HttpResponse<String> {
        val authorizationCode = URI(authorize()).rawQuery.substringAfter("code=").substringBefore('&')
        return post("token", mapOf("code" to authorizationCode, "redirect_uri" to redirect, "code_verifier" to verifier))
    }
    /** 신규 업무 테스트도 비밀번호 없이 검증된 OIDC 신원으로 최초 연결한다. */
    protected fun loginMember(id: UUID): HttpResponse<String> {
        jdbc.sql("UPDATE enrollment.tenants SET oidc_issuer=:issuer,oidc_client_id='test',oidc_client_secret_ref='config:mock',sso_enabled=true WHERE id=:id")
            .param("issuer", oidcIssuer).param("id", tenant).update()
        val address = jdbc.sql("SELECT email FROM enrollment.members WHERE id=:id").param("id", id).query(String::class.java).single()
        val callback = auth.authorizeOidc(tenant, oidcIssuer, "subject-$id", redirect, "client-state-1234567890", challenge, "S256", id, address, address)
        return post("token", mapOf("code" to URI(callback).rawQuery.substringAfter("code=").substringBefore('&'), "redirect_uri" to redirect, "code_verifier" to verifier))
    }
    protected fun tokens(): JsonNode { provisionMember(); val r=login(); assertThat(r.statusCode()).isEqualTo(200); return mapper.readTree(r.body()) }
    protected fun refresh(rt: String) = post("refresh", mapOf("refresh_token" to rt))
    protected fun sql(query: String) { jdbc.sql(query).update() }

    protected fun enroll(): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/enroll"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
            mapOf("code" to code, "platform" to "macos")))).build(), HttpResponse.BodyHandlers.ofString())

    protected fun adminToken(): String {
        provisionMember()
        jdbc.sql("UPDATE enrollment.members SET role='owner' WHERE id=:id").param("id", member).update()
        return mapper.readTree(login().body()).path("access_token").asString()
    }
    protected fun manage(method: String, path: String, body: Any?, token: String, key: String = UUID.randomUUID().toString(), etag: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/organizations/$tenant$path"))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json").header("Idempotency-Key", key)
        etag?.let { builder.header("If-Match", it) }
        return http.send(builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val key=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val dir=Files.createTempDirectory("pulsemetry-auth-test-")
        private fun pem(label:String, bytes:ByteArray)="-----BEGIN $label-----\n"+Base64.getMimeEncoder(64,byteArrayOf(10)).encodeToString(bytes)+"\n-----END $label-----\n"
        private val privateFile=dir.resolve("private.pem").also { Files.writeString(it,pem("PRIVATE KEY",key.private.encoded)); it.toFile().deleteOnExit() }
        private val publicFile=dir.resolve("public.pem").also { Files.writeString(it,pem("PUBLIC KEY",key.public.encoded)); it.toFile().deleteOnExit() }
        @JvmStatic @DynamicPropertySource fun config(registry:DynamicPropertyRegistry) {
            registry.add("pulsemetry.user-auth.issuer") { "https://auth.test" }
            registry.add("pulsemetry.user-auth.audience") { "pulsemetry" }
            registry.add("pulsemetry.user-auth.active-kid") { "key-1" }
            registry.add("pulsemetry.user-auth.private-key-file") { privateFile.toString() }
            registry.add("pulsemetry.user-auth.public-key-files.key-1") { publicFile.toString() }
        }
    }
}

class AuthTestClock : Clock() {
    @Volatile var now: Instant = Instant.parse("2026-09-09T12:00:00Z")
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone:ZoneId):Clock = this
    override fun instant():Instant = now
}
@TestConfiguration(proxyBeanMethods=false)
class AuthClockConfig {
    @Bean @Primary fun authTestClock() = AuthTestClock()
}
