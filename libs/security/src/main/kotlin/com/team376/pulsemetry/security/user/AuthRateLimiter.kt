package com.team376.pulsemetry.security.user

import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.max

/** 고정 창 하나의 한도. 창은 1초 이상이다 — `Retry-After`가 초 단위다. */
data class AuthRateLimit(val requests: Int, val window: Duration) {
    init {
        require(requests >= 1) { "인증 요청 한도는 1 이상이어야 한다" }
        require(window >= Duration.ofSeconds(1)) { "인증 요청 제한 창은 1초 이상이어야 한다" }
    }
    companion object {
        val DEFAULT = AuthRateLimit(30, Duration.ofSeconds(60))
    }
}

/**
 * 인증 요청 제한(ADR 0052). 토큰 없는 진입은 IP, 자격을 가진 요청은 세션 단위로 센다.
 * 토큰이 없거나 형식이 틀렸거나 모르는 토큰은 진입(IP) 버킷이다. 카운터는 자기 트랜잭션으로 먼저 커밋하고, 초과는 `rate_limited` 429 다.
 */
class AuthRateLimiter(
    private val repository: UserAuthRepository,
    transactionManager: PlatformTransactionManager,
    private val jwt: UserJwt,
    private val clock: Clock,
    private val entryLimit: AuthRateLimit = AuthRateLimit.DEFAULT,
    private val sessionLimit: AuthRateLimit = AuthRateLimit.DEFAULT,
) {
    private val tx = TransactionTemplate(transactionManager)

    /** 토큰 없는 진입. */
    fun entry(ip: String) = count(UserSecrets.hash("ip:$ip"), entryLimit)

    /** RT 를 가진 요청(갱신·로그아웃·재조회). 회전해도 세션은 같다. 이미 소비한 RT 도 그 세션으로 센다. */
    fun refreshToken(token: String?, ip: String) {
        val session = token?.takeIf { REFRESH_TOKEN.matches(it) }?.let { repository.refresh(UserSecrets.hash(it))?.sessionId }
        if (session == null) entry(ip) else session(session)
    }

    /** AT 를 가진 요청. 서명·만료만 확인해 세션을 찾는다 — 세션 상태 검사는 본 처리가 한다. */
    fun accessToken(token: String?, ip: String) {
        val session = token?.let { try { jwt.verify(it).sessionId } catch (_: UserAuthException) { null } }
        if (session == null) entry(ip) else session(session)
    }

    private fun session(id: UUID) = count(UserSecrets.hash("session:$id"), sessionLimit)

    private fun count(hash: String, limit: AuthRateLimit) {
        val retry = requireNotNull(tx.execute {
            val now = clock.instant()
            val attempt = repository.lockAttempt(hash, now)
            val reset = now >= attempt.windowStartedAt.plus(limit.window)
            val start = if (reset) now else attempt.windowStartedAt
            val count = if (reset) 0 else attempt.attempts
            if (count >= limit.requests) return@execute secondsUntil(now, start.plus(limit.window))
            repository.saveAttempt(hash, start, count + 1, null)
            0L
        })
        if (retry > 0) throw UserAuthException("rate_limited", 429, retry)
    }

    private fun secondsUntil(now: Instant, end: Instant): Long = max(1, (Duration.between(now, end).toMillis() + 999) / 1000)

    private companion object {
        val REFRESH_TOKEN = Regex("urt_[A-Za-z0-9_-]{43}")
    }
}
