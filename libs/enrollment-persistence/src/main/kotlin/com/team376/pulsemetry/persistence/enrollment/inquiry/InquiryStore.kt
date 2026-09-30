package com.team376.pulsemetry.persistence.enrollment.inquiry

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** 접수 정책의 운영 수치. 기본값이 없다 — 조립하는 앱이 설정에서 읽어 준다. */
data class InquiryLimits(val duplicateWindow: Duration, val rateLimitRequests: Int, val rateLimitWindow: Duration) {
    init {
        require(!duplicateWindow.isNegative && !duplicateWindow.isZero) { "문의 재전송 판정 시간은 0보다 커야 한다" }
        require(rateLimitRequests > 0) { "문의 요청 한도는 1 이상이어야 한다" }
        require(!rateLimitWindow.isNegative && !rateLimitWindow.isZero) { "문의 요청 한도의 창은 0보다 커야 한다" }
    }
}

data class InquiryReceipt(val inquiryId: UUID, val status: String, val receivedAt: Instant)

/** [field] 는 검증에 걸린 입력 이름, [retryAfter] 는 한도 초과 때 다시 시도할 수 있을 때까지의 초. */
class InquiryException(val code: String, val status: Int, val field: String? = null, val retryAfter: Long? = null) : RuntimeException(code)

/**
 * 도입 문의의 저장과 남용 제한. 문의는 조직에 속하지 않으며 조직·계정·초대를 만들지 않는다.
 * 출처 주소는 해시로만 남긴다.
 */
class InquiryStore(private val jdbc: JdbcClient, manager: PlatformTransactionManager, private val clock: Clock, private val limits: InquiryLimits) {
    private val tx = TransactionTemplate(manager)

    /** 출처 하나의 요청을 센다. 검증에 실패한 요청과 재전송도 센다. 한도를 넘으면 429 `rate_limited`. */
    fun limit(source: String) {
        val hash = sourceHash(source)
        val retry = requireNotNull(tx.execute {
            val now = clock.instant()
            jdbc.sql("INSERT INTO enrollment.inquiry_attempts(subject_hash,window_started_at) VALUES (:hash,:now) ON CONFLICT DO NOTHING")
                .param("hash", hash).param("now", Timestamp.from(now)).update()
            val (started, attempts) = jdbc.sql("SELECT window_started_at,attempts FROM enrollment.inquiry_attempts WHERE subject_hash=:hash FOR UPDATE")
                .param("hash", hash).query { rs, _ -> rs.getTimestamp(1).toInstant() to rs.getInt(2) }.single()
            val reset = now >= started.plus(limits.rateLimitWindow)
            val start = if (reset) now else started
            val count = if (reset) 0 else attempts
            if (count >= limits.rateLimitRequests) return@execute secondsUntil(now, start.plus(limits.rateLimitWindow))
            jdbc.sql("UPDATE enrollment.inquiry_attempts SET window_started_at=:start,attempts=:attempts WHERE subject_hash=:hash")
                .param("start", Timestamp.from(start)).param("attempts", count + 1).param("hash", hash).update()
            0L
        })
        if (retry > 0) throw InquiryException("rate_limited", 429, retryAfter = retry)
    }

    /**
     * 문의를 저장한다. 같은 회사·이메일이 재전송 판정 시간 안에 다시 오면 저장하지 않고 앞선 접수를 돌려준다.
     * 같은 입력의 동시 요청은 입력 해시의 잠금으로 줄을 세운다.
     */
    fun receive(company: String?, email: String?, source: String): InquiryReceipt {
        val name = company?.trim().orEmpty()
        if (name.isEmpty() || name.length > 100 || name.any(Char::isISOControl)) throw InquiryException("invalid_request", 400, "company")
        val address = email?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (address.length > 320 || !EMAIL.matches(address)) throw InquiryException("invalid_request", 400, "email")
        val request = sha256(Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(SPACES, " ") + "\n" + address)
        return requireNotNull(tx.execute {
            val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
            jdbc.sql("SELECT pg_advisory_xact_lock(:scope, hashtext(:hash))").param("scope", LOCK_SCOPE).param("hash", request).query { _, _ -> 0 }.list()
            val previous = jdbc.sql("""SELECT id,status,received_at FROM enrollment.inquiries
                WHERE request_hash=:hash AND received_at>:since ORDER BY received_at DESC LIMIT 1""")
                .param("hash", request).param("since", Timestamp.from(now.minus(limits.duplicateWindow)))
                .query { rs, _ -> InquiryReceipt(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant()) }.optional().orElse(null)
            if (previous != null) return@execute previous
            val id = UUID.randomUUID()
            jdbc.sql("""INSERT INTO enrollment.inquiries(id,company,email,request_hash,source_ip_hash,received_at)
                VALUES (:id,:company,:email,:hash,:source,:now)""")
                .param("id", id).param("company", name).param("email", address).param("hash", request)
                .param("source", sourceHash(source)).param("now", Timestamp.from(now)).update()
            InquiryReceipt(id, "received", now)
        })
    }

    private fun sourceHash(source: String) = sha256("inquiry-ip:$source")
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun secondsUntil(now: Instant, until: Instant) = maxOf(1L, (Duration.between(now, until).toMillis() + 999) / 1000)

    private companion object {
        /** 같은 입력의 동시 접수만 직렬화하는 advisory lock 의 구분값. */
        const val LOCK_SCOPE = 1_187_011
        val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        val SPACES = Regex("\\s+")
    }
}
