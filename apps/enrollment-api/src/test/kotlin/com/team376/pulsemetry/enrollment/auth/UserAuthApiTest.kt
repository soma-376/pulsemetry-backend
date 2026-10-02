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

/** 실제 PostgreSQL·HTTP로 검증한다. 동시 소비는 서로 다른 커넥션의 트랜잭션이다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class UserAuthApiTest : AbstractUserAuthApiTest() {
    @Test fun `토큰 봉투와 각 role은 같은 발급 코어를 쓴다`() {
        provisionMember()
        for (role in listOf("owner","admin","member")) {
            sql("UPDATE enrollment.members SET role='$role'")
            val r=login()
            assertThat(r.statusCode()).isEqualTo(200)
            assertThat(mapper.readTree(r.body()).properties().map { it.key }.toSet())
                .containsExactlyInAnyOrder("access_token", "refresh_token", "token_type", "expires_in")
            val t=mapper.readTree(r.body())
            val identity=auth.verify(t["access_token"].asString())
            assertThat(identity.role).isEqualTo(role)
            assertThat(identity.tenantId).isEqualTo(tenant)
            assertThat(identity.revision).isEqualTo(3)
            assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
        }
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
        provisionMember()
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
            val callback=auth.authorizeOidc(tenant, oidcIssuer, oidcSubject, redirect, state, challenge, "S256")
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

    @Test fun `인증 DTO는 알려지지 않은 필드를 거부하고 비밀을 반환하지 않는다`() {
        val r=post("refresh",mapOf("refresh_token" to "a-secret-value", "role" to "owner"))
        assertThat(r.statusCode()).isEqualTo(400)
        assertThat(r.body()).doesNotContain("a-secret-value")
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
    }

    @Test fun `비밀번호 API는 410이고 컬럼이 존재하지 않는다`() {
        for (path in listOf("signup", "login", "cli/authorize")) {
            val response=post(path, mapOf("password" to "do-not-echo", "email" to email))
            assertThat(response.statusCode()).isEqualTo(410)
            assertThat(response.body()).contains("auth_method_removed").doesNotContain("do-not-echo", email)
        }
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='enrollment' AND table_name='members' AND column_name='password_hash'").query(Int::class.java).single()).isZero()
    }

    @Test fun `OIDC 등록은 정확한 issuer subject 조직과 활성 상태를 요구한다`() {
        assertThatThrownBy { authorize() }.isInstanceOf(UserAuthException::class.java)
        provisionMember()
        for ((t, iss, sub) in listOf(Triple(UUID.randomUUID(), oidcIssuer, oidcSubject),
            Triple(tenant, "https://other-idp.test", oidcSubject), Triple(tenant, oidcIssuer, "other-subject"))) {
            assertThatThrownBy { auth.authorizeOidc(t, iss, sub, redirect, "client-state-1234567890", challenge, "S256") }
                .isInstanceOf(UserAuthException::class.java).hasMessage("member_not_allowed")
        }
        sql("UPDATE enrollment.members SET status='suspended'")
        assertThatThrownBy { authorize() }.isInstanceOf(UserAuthException::class.java)
        sql("UPDATE enrollment.members SET status='active'")
        sql("UPDATE enrollment.tenants SET status='suspended'")
        assertThatThrownBy { authorize() }.isInstanceOf(UserAuthException::class.java)
    }

    @Test fun `코드는 60초 만료하고 허용하지 않은 주소와 PKCE를 거부한다`() {
        provisionMember()
        for (bad in listOf("https://evil.test/callback", "http://localhost:1234/callback",
            "http://127.0.0.1:1234/callback?x=1", "http://evil@127.0.0.1:1234/callback"))
            assertThatThrownBy { authorize(bad) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { auth.validateAuthorizationRequest(redirect, "client-state-1234567890", challenge, "plain") }
            .isInstanceOf(UserAuthException::class.java)
        val c=URI(authorize()).rawQuery.substringAfter("code=").substringBefore('&')
        clock.now=clock.now.plusSeconds(60)
        assertThat(post("token",mapOf("code" to c,"redirect_uri" to redirect,"code_verifier" to verifier)).statusCode()).isEqualTo(401)
    }

    @Test fun `DB 장애는 503이고 내부 내용은 응답하지 않는다`() {
        sql("ALTER TABLE enrollment.auth_attempts RENAME TO auth_attempts_unavailable")
        try {
            val r=post("refresh",mapOf("refresh_token" to "do-not-echo"))
            assertThat(r.statusCode()).isEqualTo(503)
            assertThat(r.headers().firstValue("Retry-After")).isPresent()
            assertThat(r.body()).doesNotContain("SQLException","do-not-echo")
        } finally { sql("ALTER TABLE enrollment.auth_attempts_unavailable RENAME TO auth_attempts") }
    }

    @Test fun `OIDC 로그인과 설치 초대 소비는 별개이며 설치 봉투는 4키다`() {
        provisionMember()
        assertThat(login().statusCode()).isEqualTo(200)
        val installed=enroll()
        assertThat(installed.statusCode()).isEqualTo(201)
        assertThat(mapper.readTree(installed.body()).size()).isEqualTo(4)
        assertThat(enroll().statusCode()).isEqualTo(409)
        assertThat(jdbc.sql("SELECT signup_used_at IS NULL FROM enrollment.invitations").query(Boolean::class.java).single()).isTrue()
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

}
