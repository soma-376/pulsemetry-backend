package com.team376.pulsemetry.security.user

import com.team376.pulsemetry.persistence.enrollment.repository.AuthMember
import com.team376.pulsemetry.persistence.enrollment.repository.AuthSession
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.dao.DuplicateKeyException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.Locale
import kotlin.math.max

/** 실패 카운터와 재사용 폐기는 반환값으로 커밋한 뒤 예외로 바꾼다. 서명/SQL 실패는 롤백한다. */
class UserAuthService(
    private val repository: UserAuthRepository,
    transactionManager: PlatformTransactionManager,
    private val jwt: UserJwt,
    private val clock: Clock,
    private val allowedRedirectUris: Set<String> = emptySet(),
) {
    private val tx = TransactionTemplate(transactionManager)

    /** IdP로 이동하기 전에 클라이언트의 복귀 주소와 별도 PKCE를 검증한다. */
    fun validateAuthorizationRequest(redirect: String, state: String, challenge: String, method: String) {
        validateRedirect(redirect)
        if (method != "S256" || !Regex("[A-Za-z0-9_-]{43}").matches(challenge) ||
            state.length !in 16..256 || state.any { it.code < 33 || it.code > 126 }) badRequest()
    }

    fun oidcLoginMember(tenant: UUID, email: String): UUID =
        repository.oidcLoginMember(tenant, email.trim().lowercase(Locale.ROOT))
            ?: throw UserAuthException("member_not_allowed", 403)

    /** verifiedEmail은 서명·issuer·audience·nonce와 email_verified=true 검증을 마친 ID Token에서만 받는다. */
    fun authorizeOidc(tenant: UUID, issuer: String, subject: String, redirect: String, state: String,
        challenge: String, method: String, loginMemberId: UUID? = null, loginEmail: String? = null,
        verifiedEmail: String? = null): String {
        validateAuthorizationRequest(redirect, state, challenge, method)
        if (issuer.isBlank() || issuer.length > 512 || subject.isBlank() || subject.length > 255) invalid()
        try {
            return requireNotNull(tx.execute {
                val member = (if (loginMemberId == null)
                    repository.oidcMember(tenant, issuer, subject)?.takeIf { it.active() }
                else connectOidcMember(tenant, issuer, subject, loginMemberId, loginEmail, verifiedEmail))
                    ?: throw UserAuthException("member_not_allowed", 403)
                val code = UserSecrets.token("uac_")
                repository.addCode(UserSecrets.hash(code), member.id, redirect, challenge, clock.instant().plusSeconds(60))
                "$redirect?code=$code&state=${URLEncoder.encode(state, StandardCharsets.UTF_8)}"
            })
        } catch (_: DuplicateKeyException) {
            // 다른 회원이 같은 sub를 먼저 연결했으면 활성화와 코드 발급도 롤백한다.
            throw UserAuthException("member_not_allowed", 403)
        }
    }

    private fun connectOidcMember(tenant: UUID, issuer: String, subject: String, memberId: UUID,
        loginEmail: String?, verifiedEmail: String?): AuthMember? {
        val member = repository.oidcLinkCandidate(tenant, issuer, memberId) ?: return null
        if (member.tenantStatus != "active" || member.status !in setOf("invited", "active")) return null
        if (member.oidcSubject != null) return member.takeIf { it.active() && it.oidcSubject == subject }
        val email = verifiedEmail?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (email.length > 254 || !email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) ||
            email != loginEmail || email != member.email.lowercase(Locale.ROOT) ||
            repository.oidcLoginMember(tenant, email) != member.id) return null
        if (!repository.linkOidcSubject(member.id, subject)) return null
        return member.copy(oidcSubject = subject, status = "active")
    }

    fun exchange(code: String, redirect: String, verifier: String): UserTokens {
        validateRedirect(redirect)
        if (!Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier) || !Regex("uac_[A-Za-z0-9_-]{43}").matches(code)) invalid()
        return requireNotNull(tx.execute {
            val hash = UserSecrets.hash(code)
            val c = repository.lockCode(hash) ?: invalid()
            if (c.usedAt != null || c.expiresAt <= clock.instant() || c.redirectUri != redirect ||
                !UserSecrets.equal(c.challenge, UserSecrets.challenge(verifier))) invalid()
            val member = repository.member(c.memberId, true)?.takeIf { it.active() } ?: invalid()
            repository.consumeCode(hash, clock.instant())
            newSession(member)
        })
    }

    fun refresh(token: String): UserTokens = rotate(token) { member, session -> tokens(member, session) }

    /** 동일 RT 세션의 변경과 후속 연산을 한 트랜잭션으로 실행하는 확장 지점. */
    fun <T : Any> rotate(token: String, operation: (AuthMember, AuthSession) -> T): T {
        validateRefresh(token)
        val hash = UserSecrets.hash(token)
        val outcome = requireNotNull(tx.execute {
            val reference = repository.refresh(hash) ?: invalid()
            val session = repository.session(reference.sessionId, true) ?: invalid()
            val now = clock.instant()
            val current = repository.refresh(hash) ?: invalid()
            if (current.usedAt != null) {
                repository.revokeSession(session.id, now)
                return@execute Outcome<T>(error = UserAuthException("invalid_credentials"))
            }
            if (session.revokedAt != null || session.expiresAt <= now) invalid()
            val member = repository.member(session.memberId, true)?.takeIf { it.active() } ?: invalid()
            repository.consumeRefresh(hash, now)
            Outcome(value = operation(member, session))
        })
        return outcome.unwrap()
    }

    fun logout(token: String) {
        validateRefresh(token)
        tx.executeWithoutResult {
            val reference = repository.refresh(UserSecrets.hash(token)) ?: invalid()
            val session = repository.session(reference.sessionId, true) ?: invalid()
            repository.revokeSession(session.id, clock.instant())
        }
    }

    fun verify(token: String): UserIdentity = UserAccessVerifier(jwt, repository, clock).verify(token)

    fun limitIp(ip: String) {
        val hash = UserSecrets.hash("ip:$ip")
        val retry = tx.execute {
            val now = clock.instant()
            val attempt = repository.lockAttempt(hash, now)
            val reset = now >= attempt.windowStartedAt.plusSeconds(60)
            val start = if (reset) now else attempt.windowStartedAt
            val count = if (reset) 0 else attempt.attempts
            if (count >= 30) return@execute secondsUntil(now, start.plusSeconds(60))
            repository.saveAttempt(hash, start, count + 1, null)
            0L
        }
        if (retry > 0) throw UserAuthException("rate_limited", 429, retry)
    }

    private fun newSession(member: AuthMember): UserTokens {
        // 수집 설정이 없는 조직도 로그인해 온보딩을 시작한다. 0은 미설정 세션이다(ADR 0033).
        val revision = repository.activeRevision(member.tenantId) ?: 0
        val now = clock.instant()
        val session = AuthSession(UUID.randomUUID(), member.id, revision, now, now.plus(Duration.ofDays(30)))
        repository.createSession(session)
        return tokens(member, session)
    }

    /** rotate 내부에서만 사용한다. 기존 토큰 소비와 새 토큰 INSERT가 같은 트랜잭션에 속한다. */
    fun tokens(member: AuthMember, session: AuthSession): UserTokens {
        val raw = UserSecrets.token("urt_")
        val access = jwt.issue(UserIdentity(member.id, member.tenantId, member.role, session.id, session.revision))
        repository.addRefresh(UserSecrets.hash(raw), session.id, clock.instant())
        return UserTokens(access, raw, jwt.lifetime.seconds)
    }

    private fun validateRefresh(token: String) {
        if (!Regex("urt_[A-Za-z0-9_-]{43}").matches(token)) invalid()
    }

    private fun validateRedirect(raw: String) {
        val uri = try { URI(raw) } catch (_: Exception) { badRequest() }
        if (raw.length > 256 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) badRequest()
        if (raw in allowedRedirectUris && (uri.scheme == "https" ||
            (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")))) return
        if (raw.length > 256 || uri.scheme != "http" || uri.host !in setOf("127.0.0.1", "[::1]") ||
            uri.port !in 1..65535 || uri.rawPath != "/callback" || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) badRequest()
    }

    private fun secondsUntil(now: Instant, end: Instant): Long = max(1, (Duration.between(now, end).toMillis() + 999) / 1000)
    private class Outcome<T : Any>(val value: T? = null, val error: UserAuthException? = null) {
        fun unwrap(): T { error?.let { throw it }; return requireNotNull(value) }
    }
}
