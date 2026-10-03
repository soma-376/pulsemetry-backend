package com.team376.pulsemetry.security.user

import com.team376.pulsemetry.persistence.enrollment.repository.AuthAttempt
import com.team376.pulsemetry.persistence.enrollment.repository.AuthMember
import com.team376.pulsemetry.persistence.enrollment.repository.AuthSession
import com.team376.pulsemetry.persistence.enrollment.repository.AuthorizationCode
import com.team376.pulsemetry.persistence.enrollment.repository.RefreshRecord
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.net.URI
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** DB는 대역으로 두고 검증된 OIDC 신원의 코드 교환·JWT·미설정 세션을 검증한다. */
class UserAuthWithoutManifestTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var member = AuthMember(UUID.randomUUID(), UUID.randomUUID(), "owner@example.test", "owner",
        "active", "active", "https://idp.test", "test-subject")
    private var revision: Int? = null
    private val sessions = mutableMapOf<UUID, AuthSession>()
    private val refreshes = mutableMapOf<String, RefreshRecord>()
    private val codes = mutableMapOf<String, AuthorizationCode>()
    private val repository = Mockito.mock(UserAuthRepository::class.java) { call ->
        val args = call.arguments
        when (call.method.name) {
            "lockAttempt" -> AuthAttempt(now, 0, null)
            "member" -> member
            "oidcMember" -> if (args[0] == member.tenantId && args[1] == member.oidcIssuer && args[2] == member.oidcSubject) member else null
            "activeRevision" -> revision
            "createSession" -> { val session = args[0] as AuthSession; sessions[session.id] = session; null }
            "session" -> sessions[args[0]]
            "addRefresh" -> { refreshes[args[0] as String] = RefreshRecord(args[1] as UUID, null); null }
            "refresh" -> refreshes[args[0]]
            "consumeRefresh" -> { val hash = args[0] as String; refreshes[hash] = refreshes.getValue(hash).copy(usedAt = now); null }
            "revokeSession" -> { val id = args[0] as UUID; sessions[id] = sessions.getValue(id).copy(revokedAt = now); null }
            "addCode" -> { codes[args[0] as String] = AuthorizationCode(args[1] as UUID, args[2] as String,
                args[3] as String, args[4] as Instant, null); null }
            "lockCode" -> codes[args[0]]
            "consumeCode" -> { val hash = args[0] as String; codes[hash] = codes.getValue(hash).copy(usedAt = now); null }
            else -> null
        }
    }
    private val manager = Mockito.mock(PlatformTransactionManager::class.java) { call ->
        if (call.method.name == "getTransaction") SimpleTransactionStatus() else null
    }
    private val jwt = UserJwt("https://auth.test", "pulsemetry", "test", key.private as RSAPrivateKey,
        mapOf("test" to key.public as RSAPublicKey), clock)
    private val auth = UserAuthService(repository, manager, jwt, clock)

    private fun login(): UserTokens {
        val redirect = "http://127.0.0.1:12345/callback"
        val verifier = "v".repeat(43)
        val callback = auth.authorizeOidc(member.tenantId, member.oidcIssuer!!, member.oidcSubject!!, redirect,
            "onboarding-state-123456", UserSecrets.challenge(verifier), "S256")
        return auth.exchange(URI(callback).rawQuery.substringAfter("code=").substringBefore('&'), redirect, verifier)
    }

    @Test fun `설정 없는 모든 활성 역할은 로그인 검증 갱신 후에도 미설정 revision을 유지한다`() {
        for (role in listOf("owner", "admin", "member")) {
            member = member.copy(role = role)
            revision = null
            val login = login()
            assertThat(auth.verify(login.accessToken).revision).isZero()
            assertThat(auth.verify(login.accessToken).role).isEqualTo(role)
            revision = 1
            val next = auth.refresh(login.refreshToken)
            assertThat(auth.verify(next.accessToken).revision).isZero()
            assertThat(next.refreshToken).isNotEqualTo(login.refreshToken)
            assertThat(auth.verify(login().accessToken).revision).isEqualTo(1)
            auth.logout(next.refreshToken)
            assertThatThrownBy { auth.verify(next.accessToken) }.isInstanceOf(UserAuthException::class.java)
        }
    }

    @Test fun `설정이 없어도 미등록 신원과 정지된 조직 구성원은 거부한다`() {
        assertThatThrownBy { auth.authorizeOidc(member.tenantId, "https://other-idp.test", "test-subject",
            "http://127.0.0.1:12345/callback", "onboarding-state-123456", UserSecrets.challenge("v".repeat(43)), "S256") }
            .isInstanceOf(UserAuthException::class.java).hasMessage("member_not_allowed")
        member = member.copy(status = "suspended")
        assertThatThrownBy { login() }.isInstanceOf(UserAuthException::class.java)
        member = member.copy(status = "active", tenantStatus = "suspended")
        assertThatThrownBy { login() }.isInstanceOf(UserAuthException::class.java)
        assertThat(sessions).isEmpty()
    }

    @Test fun `설정 없는 인증 코드 교환은 성공하고 코드는 재사용할 수 없다`() {
        val redirect = "http://127.0.0.1:12345/callback"
        val verifier = "v".repeat(43)
        val callback = auth.authorizeOidc(member.tenantId, member.oidcIssuer!!, member.oidcSubject!!, redirect,
            "onboarding-state-123456", UserSecrets.challenge(verifier), "S256")
        val code = URI(callback).rawQuery.substringAfter("code=").substringBefore('&')
        val tokens = auth.exchange(code, redirect, verifier)
        assertThat(auth.verify(tokens.accessToken).revision).isZero()
        assertThatThrownBy { auth.exchange(code, redirect, verifier) }.isInstanceOf(UserAuthException::class.java)
    }

    @Test fun `미설정 세션도 갱신 토큰 재사용 시 폐기한다`() {
        val login = login()
        val next = auth.refresh(login.refreshToken)
        assertThatThrownBy { auth.refresh(login.refreshToken) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { auth.verify(next.accessToken) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { auth.refresh(next.refreshToken) }.isInstanceOf(UserAuthException::class.java)
    }

    companion object {
        private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
