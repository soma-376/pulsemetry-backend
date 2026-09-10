package com.team376.pulsemetry.security.user

import com.team376.pulsemetry.persistence.enrollment.repository.AuthMember
import com.team376.pulsemetry.persistence.enrollment.repository.AuthSession
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.max

/** 실패 카운터와 재사용 폐기는 반환값으로 커밋한 뒤 예외로 바꾼다. 서명/SQL 실패는 롤백한다. */
class UserAuthService(
    private val repository: UserAuthRepository,
    transactionManager: PlatformTransactionManager,
    private val jwt: UserJwt,
    private val clock: Clock,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val passwords = BCryptPasswordEncoder(12)
    private val dummyHash = passwords.encode("not-a-real-member-password")

    fun signup(codeHash: String, email: String, password: String) {
        if (password.codePointCount(0, password.length) < 12 || password.toByteArray(StandardCharsets.UTF_8).size > 72 || email.length > 320) badRequest()
        // KDF는 DB 잠금을 잡기 전에 끝낸다.
        val passwordHash = requireNotNull(passwords.encode(password))
        tx.executeWithoutResult {
            val now = clock.instant()
            val invitation = repository.lockInvitation(codeHash) ?: throw UserAuthException("signup_unavailable", 409)
            val member = repository.member(invitation.memberId, true) ?: throw UserAuthException("signup_unavailable", 409)
            if (invitation.revokedAt != null || invitation.signupUsedAt != null || invitation.expiresAt <= now ||
                member.tenantId != invitation.tenantId || member.tenantStatus != "active" || member.status == "suspended" ||
                member.passwordHash != null || member.email != UserSecrets.email(email)) throw UserAuthException("signup_unavailable", 409)
            repository.signup(member.id, invitation.id, passwordHash, now)
        }
    }

    fun login(tenant: UUID, email: String, password: String): UserTokens = authenticated(tenant, email, password) { member ->
        newSession(member)
    }

    fun authorize(tenant: UUID, email: String, password: String, redirect: String, state: String,
        challenge: String, method: String): String {
        validateRedirect(redirect)
        if (method != "S256" || !Regex("[A-Za-z0-9_-]{43}").matches(challenge) ||
            state.length !in 16..256 || state.any { it.code < 33 || it.code > 126 }) badRequest()
        return authenticated(tenant, email, password) { member ->
            val code = UserSecrets.token("uac_")
            repository.addCode(UserSecrets.hash(code), member.id, redirect, challenge, clock.instant().plusSeconds(60))
            "$redirect?code=$code&state=${URLEncoder.encode(state, StandardCharsets.UTF_8)}"
        }
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

    fun verify(token: String): UserIdentity {
        val identity = jwt.verify(token)
        val session = repository.session(identity.sessionId) ?: invalid()
        val member = repository.member(identity.memberId)?.takeIf { it.active() } ?: invalid()
        if (session.revokedAt != null || session.expiresAt <= clock.instant() || session.memberId != member.id ||
            member.tenantId != identity.tenantId || member.role != identity.role) invalid()
        return identity
    }

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

    private fun <T : Any> authenticated(tenant: UUID, email: String, password: String, operation: (AuthMember) -> T): T {
        if (email.length > 320 || password.toByteArray(StandardCharsets.UTF_8).size > 72) invalid()
        val normalized = UserSecrets.email(email)
        val hash = UserSecrets.hash("account:$tenant:$normalized")
        val outcome = requireNotNull(tx.execute {
            val now = clock.instant()
            val attempt = repository.lockAttempt(hash, now)
            if (attempt.lockedUntil?.isAfter(now) == true) {
                return@execute Outcome<T>(error = UserAuthException("rate_limited", 429, secondsUntil(now, requireNotNull(attempt.lockedUntil))))
            }
            val reset = now >= attempt.windowStartedAt.plusSeconds(900)
            val start = if (reset) now else attempt.windowStartedAt
            val count = if (reset) 0 else attempt.attempts
            val found = repository.member(tenant, normalized)
            val member = found?.let { repository.member(it.id, true) }
            val matches = passwords.matches(password, member?.passwordHash ?: dummyHash)
            if (member == null || !member.active() || member.passwordHash == null || !matches) {
                val failures = count + 1
                repository.saveAttempt(hash, start, failures, if (failures >= 5) now.plusSeconds(900) else null)
                return@execute Outcome<T>(error = if (failures >= 5) UserAuthException("rate_limited", 429, 900)
                    else UserAuthException("invalid_credentials"))
            }
            repository.saveAttempt(hash, now, 0, null)
            Outcome(value = operation(member))
        })
        return outcome.unwrap()
    }

    private fun newSession(member: AuthMember): UserTokens {
        val revision = repository.activeRevision(member.tenantId) ?: throw UserAuthException("manifest_not_configured", 409)
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
        if (raw.length > 256 || uri.scheme != "http" || uri.host !in setOf("127.0.0.1", "[::1]") ||
            uri.port !in 1..65535 || uri.rawPath != "/callback" || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) badRequest()
    }

    private fun secondsUntil(now: Instant, end: Instant): Long = max(1, (Duration.between(now, end).toMillis() + 999) / 1000)
    private class Outcome<T : Any>(val value: T? = null, val error: UserAuthException? = null) {
        fun unwrap(): T { error?.let { throw it }; return requireNotNull(value) }
    }
}
