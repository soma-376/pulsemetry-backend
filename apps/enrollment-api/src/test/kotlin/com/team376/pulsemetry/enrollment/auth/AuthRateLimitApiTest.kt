package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.repository.AuthSession
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import com.team376.pulsemetry.security.user.UserTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

private const val ORIGIN = "http://localhost:3000"

/** 인증 요청 제한 테스트의 공통 도구. 요청은 모두 같은 주소(127.0.0.1)에서 실제 HTTP 로 보낸다. */
abstract class AbstractAuthRateLimitApiTest : AbstractUserAuthApiTest() {
    @Autowired protected lateinit var repository: UserAuthRepository

    /** 세션 생성은 HTTP 를 거치지 않는다 — 진입 버킷을 쓰지 않고 세션을 여럿 만든다. */
    protected fun activate() = sql("UPDATE enrollment.members SET status='active'")
    protected fun newSession(): UserTokens {
        val row = requireNotNull(repository.member(member))
        val session = AuthSession(UUID.randomUUID(), member, 3, clock.now, clock.now.plus(Duration.ofDays(30)))
        repository.createSession(session)
        return auth.tokens(row, session)
    }

    protected fun send(method: String, path: String, body: Any? = null, bearer: String? = null, origin: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        origin?.let { builder.header("Origin", it) }
        if (body != null) builder.header("Content-Type", "application/json")
        return http.send(builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
    }
    protected fun refreshFrom(origin: String, rt: String) = send("POST", "/v1/auth/refresh", mapOf("refresh_token" to rt), origin = origin)
    protected fun logout(rt: String) = send("POST", "/v1/auth/logout", mapOf("refresh_token" to rt))
    protected fun me(at: String?) = send("GET", "/v1/auth/me", bearer = at)
    protected fun manifest(rt: String?) = send("GET", "/v1/manifest", bearer = rt)
    protected fun unknownLogin(n: Int, origin: String? = null) = send("POST", "/v1/auth/login",
        mapOf("tenant_id" to tenant, "email" to "nobody-$n@example.test", "password" to "wrong-password-123"), origin = origin)
    protected fun nextRefreshToken(response: HttpResponse<String>): String {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return mapper.readTree(response.body()).path("refresh_token").asString()
    }
    protected fun assertLimited(response: HttpResponse<String>, retryAfter: String) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(429)
        assertThat(mapper.readTree(response.body()).path("error").asString()).isEqualTo("rate_limited")
        assertThat(response.headers().firstValue("Retry-After")).hasValue(retryAfter)
    }
}

/**
 * ADR 0052: 토큰 없는 진입은 IP, 자격을 가진 요청(갱신·로그아웃·재조회·현재 사용자)은 세션 단위로 센다. 기본 한도는 둘 다 60초 30회다.
 * 시계는 멈춰 있어 한 테스트 안의 요청은 모두 같은 창에 든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.user-auth.allowed-origins=$ORIGIN"])
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class AuthRateLimitApiTest : AbstractAuthRateLimitApiTest() {
    @Test fun `한 IP에서 서로 다른 세션 40개가 갱신해도 429가 없고 진입 버킷도 쓰지 않는다`() {
        activate()
        repeat(40) { assertThat(refresh(newSession().refreshToken).statusCode()).isEqualTo(200) }
        repeat(30) { assertThat(unknownLogin(it).statusCode()).isEqualTo(401) }
        assertLimited(unknownLogin(30), "60")
    }

    @Test fun `한 세션이 한도를 넘으면 그 세션만 429이고 RT는 소비되지 않아 창이 지난 뒤 그대로 쓴다`() {
        activate()
        var rt = newSession().refreshToken
        repeat(30) { rt = nextRefreshToken(refresh(rt)) }
        val limited = refreshFrom(ORIGIN, rt)
        assertLimited(limited, "60")
        // 브라우저가 Retry-After 를 읽는다.
        assertThat(limited.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN)
        assertThat(limited.headers().firstValue("Access-Control-Expose-Headers").orElse("")).contains("Retry-After")
        // 다른 세션과 진입은 막히지 않는다.
        assertThat(refresh(newSession().refreshToken).statusCode()).isEqualTo(200)
        assertThat(unknownLogin(0).statusCode()).isEqualTo(401)
        clock.now = clock.now.plusSeconds(60)
        assertThat(refresh(rt).statusCode()).isEqualTo(200)
    }

    @Test fun `진입은 IP 한도 그대로 31회째 429이고 같은 IP의 세션 요청은 막지 않는다`() {
        activate()
        val tokens = newSession()
        repeat(30) { assertThat(unknownLogin(it).statusCode()).isEqualTo(401) }
        val limited = unknownLogin(30, origin = ORIGIN)
        assertLimited(limited, "60")
        assertThat(limited.headers().firstValue("Content-Type").orElse("")).isEqualToIgnoringCase("application/json;charset=UTF-8")
        assertThat(limited.headers().firstValue("Access-Control-Expose-Headers").orElse("")).contains("Retry-After")
        assertThat(me(tokens.accessToken).statusCode()).isEqualTo(200)
        val rt = nextRefreshToken(refresh(tokens.refreshToken))
        assertThat(manifest(rt).statusCode()).isEqualTo(200)
        assertThat(logout(newSession().refreshToken).statusCode()).isEqualTo(204)
    }

    @Test fun `형식이 틀리거나 모르는 토큰과 토큰 없는 요청은 진입 버킷으로 센다`() {
        activate()
        repeat(8) { assertThat(refresh("not-a-token").statusCode()).isEqualTo(401) }
        repeat(8) { assertThat(logout("urt_" + "A".repeat(43)).statusCode()).isEqualTo(401) }
        repeat(7) { assertThat(me("not-a-jwt").statusCode()).isEqualTo(401) }
        repeat(7) { assertThat(manifest(null).statusCode()).isEqualTo(401) }
        assertLimited(unknownLogin(0), "60")
        assertLimited(refresh("not-a-token"), "60")
        // 알려진 세션의 토큰은 세션 버킷이라 막히지 않는다.
        assertThat(refresh(newSession().refreshToken).statusCode()).isEqualTo(200)
    }

    @Test fun `로그아웃이 429면 같은 RT로 창이 지난 뒤 다시 시도해 성공한다`() {
        activate()
        var rt = newSession().refreshToken
        repeat(30) { rt = nextRefreshToken(refresh(rt)) }
        assertLimited(logout(rt), "60")
        clock.now = clock.now.plusSeconds(60)
        assertThat(logout(rt).statusCode()).isEqualTo(204)
        assertThat(refresh(rt).statusCode()).isEqualTo(401)
    }

    @Test fun `현재 사용자 조회와 재조회는 같은 세션 버킷을 쓴다`() {
        activate()
        val tokens = newSession()
        repeat(29) { assertThat(me(tokens.accessToken).statusCode()).isEqualTo(200) }
        val rotated = manifest(tokens.refreshToken)
        assertThat(rotated.statusCode()).withFailMessage(rotated.body()).isEqualTo(200)
        assertLimited(me(tokens.accessToken), "60")
        assertLimited(refresh(nextRefreshToken(rotated)), "60")
        assertThat(me(newSession().accessToken).statusCode()).isEqualTo(200)
    }
}

/** 한도는 설정 키 `pulsemetry.user-auth.rate-limit.{entry,session}.{requests,window}`로 바꾼다(ADR 0052). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.user-auth.allowed-origins=$ORIGIN",
        "pulsemetry.user-auth.rate-limit.entry.requests=3", "pulsemetry.user-auth.rate-limit.entry.window=PT10S",
        "pulsemetry.user-auth.rate-limit.session.requests=2", "pulsemetry.user-auth.rate-limit.session.window=PT20S"])
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class AuthRateLimitSettingsApiTest : AbstractAuthRateLimitApiTest() {
    @Test fun `설정한 진입 한도와 세션 한도가 적용된다`() {
        activate()
        repeat(3) { assertThat(unknownLogin(it).statusCode()).isEqualTo(401) }
        assertLimited(unknownLogin(3), "10")
        var rt = newSession().refreshToken
        repeat(2) { rt = nextRefreshToken(refresh(rt)) }
        assertLimited(refresh(rt), "20")
        clock.now = clock.now.plusSeconds(10)
        assertThat(unknownLogin(4).statusCode()).isEqualTo(401)
        assertLimited(refresh(rt), "10")
    }
}
