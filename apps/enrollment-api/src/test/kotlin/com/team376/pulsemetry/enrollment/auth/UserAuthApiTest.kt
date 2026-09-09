package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.enrollment.support.ContractSchemas
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

/** 실제 PostgreSQL·HTTP로 검증한다. 동시 소비는 서로 다른 커넥션의 트랜잭션이다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class UserAuthApiTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var data: EnrollmentTestData
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var auth: UserAuthService
    @Autowired private lateinit var clock: AuthTestClock
    private val http = HttpClient.newHttpClient()
    private lateinit var tenant: UUID
    private lateinit var member: UUID
    private lateinit var code: String
    private val email = "user@example.com"
    private val password = "correct-password-123"

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

    private fun post(path: String, body: Any): HttpResponse<String> = http.send(HttpRequest.newBuilder(
        URI("http://localhost:$port/v1/auth/$path")).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
    private fun signup() = post("signup", mapOf("code" to code, "email" to " User@Example.com ", "password" to password))
    private fun login(pw: String = password) = post("login", mapOf("tenant_id" to tenant, "email" to email, "password" to pw))
    private fun tokens(): JsonNode { assertThat(signup().statusCode()).isEqualTo(201); val r=login(); assertThat(r.statusCode()).isEqualTo(200); return mapper.readTree(r.body()) }
    private fun refresh(rt: String) = post("refresh", mapOf("refresh_token" to rt))
    private fun sql(query: String) { jdbc.sql(query).update() }

    @Test fun `가입 뒤 설치와 설치 뒤 가입이 각각 성공한다`() {
        assertThat(signup().statusCode()).isEqualTo(201)
        val installed = enroll()
        assertThat(installed.statusCode()).isEqualTo(201)
        assertThat(mapper.readTree(installed.body()).size()).isEqualTo(4)
        assertThat(signup().statusCode()).isEqualTo(409)
        assertThat(enroll().statusCode()).isEqualTo(409)
        setup()
        assertThat(enroll().statusCode()).isEqualTo(201)
        assertThat(signup().statusCode()).isEqualTo(201)
        assertThat(login().statusCode()).isEqualTo(200)
    }
    private fun enroll(): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/enroll"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
            mapOf("code" to code, "platform" to "macos")))).build(), HttpResponse.BodyHandlers.ofString())

    @Test fun `가입 경합에서 정확히 한 요청만 비밀번호를 설정한다`() {
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val futures = (1..2).map { pool.submit(Callable { gate.await(); signup().statusCode() }) }
            gate.countDown()
            assertThat(futures.map { it.get() }).containsExactlyInAnyOrder(201,409)
        }
        assertThat(login().statusCode()).isEqualTo(200)
    }

    @Test fun `초대 만료 폐기 이메일 불일치와 정지 계정은 가입할 수 없다`() {
        assertThat(post("signup", mapOf("code" to code,"email" to "other@example.com","password" to password)).statusCode()).isEqualTo(409)
        sql("UPDATE enrollment.invitations SET revoked_at=now()")
        assertThat(signup().statusCode()).isEqualTo(409)
        sql("UPDATE enrollment.invitations SET revoked_at=NULL, expires_at='2020-01-01'")
        assertThat(signup().statusCode()).isEqualTo(409)
        sql("UPDATE enrollment.invitations SET expires_at='2030-01-01'")
        sql("UPDATE enrollment.members SET status='suspended'")
        assertThat(signup().statusCode()).isEqualTo(409)
        sql("UPDATE enrollment.members SET status='invited'")
        sql("UPDATE enrollment.tenants SET status='suspended'")
        assertThat(signup().statusCode()).isEqualTo(409)
    }

    @Test fun `비밀번호 길이를 문자와 바이트로 검사하고 기존 비밀번호를 덮어쓰지 않는다`() {
        for (pw in listOf("short", "a".repeat(73), "한".repeat(25)))
            assertThat(post("signup", mapOf("code" to code,"email" to email,"password" to pw)).statusCode()).isEqualTo(400)
        assertThat(signup().statusCode()).isEqualTo(201)
        val hash = jdbc.sql("SELECT password_hash FROM enrollment.members WHERE id=:id").param("id",member).query(String::class.java).single()
        assertThat(hash).startsWith("$2a$12$")
        val another = InvitationCode.generate()
        data.invitation(tenant,member,another,expiresAt=clock.now.plusSeconds(3600))
        assertThat(post("signup",mapOf("code" to another,"email" to email,"password" to "other-password-123")).statusCode()).isEqualTo(409)
        assertThat(login().statusCode()).isEqualTo(200)
    }

    @Test fun `토큰 봉투와 각 role은 같은 발급 코어를 쓴다`() {
        signup()
        for (role in listOf("owner","admin","member")) {
            sql("UPDATE enrollment.members SET role='$role'")
            val r=login()
            assertThat(r.statusCode()).isEqualTo(200)
            assertThat(ContractSchemas.validate(ContractSchemas.userTokensSchema(),r.body())).isEmpty()
            val t=mapper.readTree(r.body())
            val identity=auth.verify(t["access_token"].asString())
            assertThat(identity.role).isEqualTo(role)
            assertThat(identity.tenantId).isEqualTo(tenant)
            assertThat(identity.revision).isEqualTo(3)
            assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
        }
    }

    @Test fun `가입 미완료 오입력 다른 tenant는 같은 실패이고 DB 장애는 별개다`() {
        val before=login()
        assertThat(before.statusCode()).isEqualTo(401)
        signup()
        assertThat(login("wrong").body()).isEqualTo(before.body())
        assertThat(post("login",mapOf("tenant_id" to UUID.randomUUID(),"email" to email,"password" to password)).body()).isEqualTo(before.body())
        sql("ALTER TABLE enrollment.auth_attempts RENAME TO auth_attempts_unavailable")
        try {
            val r=login()
            assertThat(r.statusCode()).isEqualTo(503)
            assertThat(r.headers().firstValue("Retry-After")).isPresent()
            assertThat(r.body()).doesNotContain(password,email,"SQLException")
        } finally { sql("ALTER TABLE enrollment.auth_attempts_unavailable RENAME TO auth_attempts") }
    }

    @Test fun `refresh는 revision을 보존하고 재사용은 후속 토큰까지 폐기한다`() {
        val t=tokens()
        sql("UPDATE enrollment.manifests SET version=4")
        val r=refresh(t["refresh_token"].asString())
        assertThat(r.statusCode()).isEqualTo(200)
        val next=mapper.readTree(r.body())
        assertThat(auth.verify(next["access_token"].asString()).revision).isEqualTo(3)
        assertThat(refresh(t["refresh_token"].asString()).statusCode()).isEqualTo(401)
        assertThat(refresh(next["refresh_token"].asString()).statusCode()).isEqualTo(401)
        assertThatThrownBy { auth.verify(next["access_token"].asString()) }.isInstanceOf(UserAuthException::class.java)
    }

    @Test fun `동시 refresh는 하나만 성공하고 재사용 판정은 커밋된다`() {
        val t=tokens(); val gate=CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val futures=(1..2).map { pool.submit(Callable { gate.await(); refresh(t["refresh_token"].asString()) }) }
            gate.countDown()
            val responses=futures.map { it.get() }
            assertThat(responses.map { it.statusCode() }).containsExactlyInAnyOrder(200,401)
            val next=mapper.readTree(responses.first { it.statusCode()==200 }.body())
            assertThat(refresh(next["refresh_token"].asString()).statusCode()).isEqualTo(401)
        }
    }

    @Test fun `로그아웃 만료와 정지는 세션 사용을 막는다`() {
        val t=tokens()
        assertThat(post("logout",mapOf("refresh_token" to t["refresh_token"].asString())).statusCode()).isEqualTo(204)
        assertThat(refresh(t["refresh_token"].asString()).statusCode()).isEqualTo(401)
        val next=mapper.readTree(login().body())
        sql("UPDATE enrollment.members SET status='suspended'")
        assertThat(refresh(next["refresh_token"].asString()).statusCode()).isEqualTo(401)
        assertThatThrownBy { auth.verify(next["access_token"].asString()) }.isInstanceOf(UserAuthException::class.java)
        sql("UPDATE enrollment.members SET status='active'")
        clock.now=clock.now.plusSeconds(30L*86400)
        assertThat(refresh(next["refresh_token"].asString()).statusCode()).isEqualTo(401)
    }

    @Test fun `로그인 5회 실패 잠금과 15분 후 해제`() {
        signup()
        repeat(4) { assertThat(login("wrong").statusCode()).isEqualTo(401) }
        val locked=login("wrong")
        assertThat(locked.statusCode()).isEqualTo(429)
        assertThat(locked.headers().firstValue("Retry-After").orElse("")).isEqualTo("900")
        assertThat(login().statusCode()).isEqualTo(429)
        clock.now=clock.now.plusSeconds(900)
        assertThat(login().statusCode()).isEqualTo(200)
    }

    @Test fun `IP 제한은 DB 공유 상태를 사용하고 경계에서 초기화된다`() {
        repeat(30) { auth.limitIp("198.51.100.1") }
        assertThatThrownBy { auth.limitIp("198.51.100.1") }.isInstanceOf(UserAuthException::class.java)
        clock.now=clock.now.plusSeconds(60)
        auth.limitIp("198.51.100.1")
        val malformed=mapOf("refresh_token" to "not-a-token")
        repeat(30) { assertThat(post("refresh",malformed).statusCode()).isEqualTo(401) }
        val r=post("refresh",malformed)
        assertThat(r.statusCode()).isEqualTo(429)
        assertThat(r.headers().firstValue("Retry-After").orElse("")).isEqualTo("60")
    }

    @Test fun `모의 CLI가 loopback state와 PKCE를 검증하고 토큰을 교환한다`() {
        signup()
        val received = java.util.concurrent.CompletableFuture<String>()
        val state="state-12345678901234567890"
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/callback") { exchange ->
            val returnedState=exchange.requestURI.rawQuery.substringAfter("&state=", "")
            if (returnedState != state) {
                exchange.sendResponseHeaders(400,-1)
            } else {
                received.complete(exchange.requestURI.rawQuery)
                exchange.sendResponseHeaders(204,-1)
            }
            exchange.close()
        }
        server.start()
        try {
            val redirect="http://127.0.0.1:${server.address.port}/callback"
            val verifier="v".repeat(43)
            val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val response=post("cli/authorize",mapOf("tenant_id" to tenant,"email" to email,"password" to password,
                "redirect_uri" to redirect,"state" to state,"code_challenge" to challenge,"code_challenge_method" to "S256"))
            assertThat(response.statusCode()).isEqualTo(200)
            val callback=mapper.readTree(response.body())["callback_url"].asString()
            assertThat(callback).doesNotContain("urt_","access_token","refresh_token")
            val wrongState=callback.substringBefore("&state=")+"&state=unrelated-state"
            assertThat(http.send(HttpRequest.newBuilder(URI(wrongState)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400)
            assertThat(received.isDone).isFalse()
            http.send(HttpRequest.newBuilder(URI(callback)).GET().build(),HttpResponse.BodyHandlers.discarding())
            val params=received.get(5,java.util.concurrent.TimeUnit.SECONDS).split('&').associate {
                val parts=it.split('=',limit=2); parts[0] to URLDecoder.decode(parts[1],java.nio.charset.StandardCharsets.UTF_8)
            }
            assertThat(params["state"]).isEqualTo(state).isNotEqualTo("another-state")
            val body=mapOf("code" to params.getValue("code"),"redirect_uri" to redirect,"code_verifier" to verifier)
            assertThat(post("cli/token",body + ("code_verifier" to "x".repeat(43))).statusCode()).isEqualTo(401)
            assertThat(post("cli/token",body + ("redirect_uri" to "http://127.0.0.1:1/callback")).statusCode()).isEqualTo(401)
            val exchanged=post("cli/token",body)
            assertThat(exchanged.statusCode()).isEqualTo(200)
            assertThat(auth.verify(mapper.readTree(exchanged.body())["access_token"].asString()).memberId).isEqualTo(member)
            assertThat(post("cli/token",body).statusCode()).isEqualTo(401)
        } finally { server.stop(0) }
    }

    @Test fun `CLI 코드는 60초 뒤 만료되고 외부 callback은 거부한다`() {
        signup()
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest("v".repeat(43).toByteArray()))
        val body=mapOf("tenant_id" to tenant,"email" to email,"password" to password,"state" to "state-12345678901234567890",
            "redirect_uri" to "http://[::1]:1234/callback","code_challenge" to challenge,"code_challenge_method" to "S256")
        for (redirect in listOf("https://evil.example/callback","http://localhost:1234/callback","http://127.0.0.1:1234/callback?evil=1","http://evil@127.0.0.1:1234/callback"))
            assertThat(post("cli/authorize",body + ("redirect_uri" to redirect)).statusCode()).isEqualTo(400)
        val callback=mapper.readTree(post("cli/authorize",body).body())["callback_url"].asString()
        val code=URI(callback).rawQuery.substringAfter("code=").substringBefore('&')
        clock.now=clock.now.plusSeconds(60)
        assertThat(post("cli/token",mapOf("code" to code,"redirect_uri" to body.getValue("redirect_uri"),"code_verifier" to "v".repeat(43))).statusCode()).isEqualTo(401)
    }

    @Test fun `인증 DTO는 알려지지 않은 필드를 거부하고 비밀을 반환하지 않는다`() {
        val r=post("login",mapOf("tenant_id" to tenant,"email" to email,"password" to password,"role" to "owner"))
        assertThat(r.statusCode()).isEqualTo(400)
        assertThat(r.body()).doesNotContain(password,email)
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
    }

    @Test fun `MockMvc에서 위조 forwarded IP로 제한을 우회하지 못한다`() {
        repeat(30) { n ->
            mvc.perform(post("/v1/auth/refresh").servletPath("/v1/auth/refresh")
                .header("X-Forwarded-For", "198.51.100.$n").contentType("application/json")
                .content("{\"refresh_token\":\"invalid\"}")).andExpect(status().isUnauthorized)
        }
        mvc.perform(post("/v1/auth/refresh").servletPath("/v1/auth/refresh")
            .header("X-Forwarded-For", "203.0.113.1").contentType("application/json")
            .content("{\"refresh_token\":\"invalid\"}")).andExpect(status().isTooManyRequests)
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
