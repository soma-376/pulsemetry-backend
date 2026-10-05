package com.team376.pulsemetry.persistence.enrollment.mail

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 적재할 메일. [subject] 는 평문으로 저장되므로 비밀을 넣지 않는다. [body] 는 암호화해 저장하고 끝나면 지운다.
 * 비밀을 담으므로 data class 가 아니다 — 자동 toString 으로 본문을 출력하지 않는다.
 */
class MailDraft(val dedupKey: String, val kind: String, val recipient: String, val subject: String, val body: String)

/** 메일 한 통의 발송 상태. [finishedAt] 은 sent·failed·cancelled 가 된 시각이다. */
data class MailDelivery(val id: UUID, val dedupKey: String, val kind: String, val status: String, val attempts: Int,
    val queuedAt: Instant, val lastAttemptAt: Instant?, val finishedAt: Instant?, val failureCode: String?, val failureDetail: String?)

/** 발송 작업이 선점한 메일. [attempt] 는 이번이 몇 번째 시도인지다. */
class ClaimedMail(val id: UUID, val kind: String, val recipient: String, val subject: String, val body: String, val attempt: Int)

/** 실제로 보내는 쪽. 구현(SMTP·SES)은 앱이 준다(ADR 0057). 보내지 못하면 [MailTransportFailure] 를 던진다. */
fun interface MailTransport {
    fun send(mail: ClaimedMail)
}

/**
 * [permanent] 면 다시 시도하지 않는다. [code] 는 API 에 내는 분류 코드, [detail] 은 API 에 내지 않는 세부 — SMTP 응답 코드나 SES 의 정해진 분류 토큰이다.
 * 공급자 응답 원문을 넣지 않는다.
 */
class MailTransportFailure(val permanent: Boolean, val code: String, val detail: String? = null) : RuntimeException(code)

/** 재시도 정책. 기본값이 없다 — 조립하는 앱이 설정에서 읽어 준다. */
data class MailPolicy(val retryInterval: Duration, val maxAttempts: Int, val lease: Duration) {
    init {
        require(!retryInterval.isNegative && !retryInterval.isZero) { "메일 재시도 간격은 0보다 커야 한다" }
        require(maxAttempts > 0) { "메일 최대 시도는 1 이상이어야 한다" }
        require(!lease.isNegative && !lease.isZero) { "메일 선점 임대 시간은 0보다 커야 한다" }
    }
}

/**
 * 메일 outbox 의 적재·선점·결과 기록 (ADR 0037). 빈·발송 공급자 의존성은 없다.
 * 적재는 호출자의 트랜잭션에 참여하고, 선점과 결과 기록은 각각 자기 트랜잭션이다.
 */
class MailOutbox(private val jdbc: JdbcClient, manager: PlatformTransactionManager, private val clock: Clock,
    encryptionKey: String, private val policy: MailPolicy) {
    private val tx = TransactionTemplate(manager)
    private val random = SecureRandom()
    private val key = SecretKeySpec(Base64.getDecoder().decode(encryptionKey).also { require(it.size == 32) { "메일 암호화 키는 Base64 32바이트여야 한다" } }, "AES")

    /**
     * 메일을 적재한다. 자기 트랜잭션을 열지 않는다 — 호출자의 업무 쓰기와 함께 커밋되거나 함께 롤백된다.
     * 같은 [MailDraft.dedupKey] 가 이미 있으면 새로 만들지 않고 그 메일의 상태를 돌려준다(실패·취소된 메일도 되살리지 않는다).
     */
    fun enqueue(draft: MailDraft): MailDelivery {
        require(draft.dedupKey.isNotBlank() && draft.dedupKey.length <= 200) { "메일 중복 방지 키는 1~200자다" }
        require(draft.kind.isNotBlank() && draft.kind.length <= 40) { "메일 종류는 1~40자다" }
        require(draft.recipient.isNotBlank() && draft.recipient.length <= 320 && draft.recipient.none(Char::isISOControl)) { "메일 수신자가 올바르지 않다" }
        // 제목은 헤더에 실린다. 줄바꿈을 받으면 헤더를 끼워 넣을 수 있다.
        require(draft.subject.isNotBlank() && draft.subject.length <= 200 && draft.subject.none(Char::isISOControl)) { "메일 제목은 제어 문자 없는 1~200자다" }
        require(draft.body.isNotBlank()) { "메일 본문이 비어 있다" }
        val id = UUID.randomUUID()
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        jdbc.sql("""INSERT INTO enrollment.mail_outbox(id,dedup_key,kind,recipient,subject,encrypted_body,next_attempt_at,queued_at)
            VALUES (:id,:key,:kind,:recipient,:subject,:body,:now,:now) ON CONFLICT (dedup_key) DO NOTHING""")
            .param("id", id).param("key", draft.dedupKey).param("kind", draft.kind).param("recipient", draft.recipient)
            .param("subject", draft.subject).param("body", encrypt(draft.body, id)).param("now", Timestamp.from(now)).update()
        return requireNotNull(delivery(draft.dedupKey))
    }

    fun delivery(dedupKey: String): MailDelivery? = jdbc.sql("$DELIVERY WHERE dedup_key=:key").param("key", dedupKey).query(::deliveryRow).optional().orElse(null)

    /** 아직 보내지 않은 메일을 취소한다. 보내는 중이거나 이미 끝난 메일은 취소하지 못하고 false 다. */
    fun cancel(dedupKey: String): Boolean = jdbc.sql("""UPDATE enrollment.mail_outbox SET status='cancelled',encrypted_body=NULL,finished_at=:now
        WHERE dedup_key=:key AND status='queued'""").param("key", dedupKey).param("now", Timestamp.from(clock.instant())).update() == 1

    /**
     * 보낼 때가 된 메일 하나를 선점한다. 없으면 null.
     * 다른 작업이 잡고 있는 행은 건너뛴다(`SKIP LOCKED`). 임대가 끝난 `sending` 도 다시 잡는다.
     */
    fun claim(): ClaimedMail? = tx.execute { claimOne(clock.instant()) }

    private fun claimOne(now: Instant): ClaimedMail? {
        // 최대 시도를 채운 채 임대가 끝난 메일은 결과를 알 수 없다. 다시 보내지 않고 닫는다.
        jdbc.sql("""UPDATE enrollment.mail_outbox SET status='failed',failure_code='outcome_unknown',failure_detail=NULL,
            encrypted_body=NULL,locked_until=NULL,finished_at=:now WHERE status='sending' AND locked_until<=:now AND attempts>=:max""")
            .param("now", Timestamp.from(now)).param("max", policy.maxAttempts).update()
        while (true) {
            val row = jdbc.sql("""SELECT id,kind,recipient,subject,encrypted_body,attempts FROM enrollment.mail_outbox
                WHERE (status='queued' AND next_attempt_at<=:now) OR (status='sending' AND locked_until<=:now)
                ORDER BY next_attempt_at,queued_at LIMIT 1 FOR UPDATE SKIP LOCKED""").param("now", Timestamp.from(now))
                .query { rs, _ -> Candidate(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)) }
                .optional().orElse(null) ?: return null
            jdbc.sql("UPDATE enrollment.mail_outbox SET status='sending',attempts=attempts+1,locked_until=:until,last_attempt_at=:now WHERE id=:id")
                .param("until", Timestamp.from(now.plus(policy.lease))).param("now", Timestamp.from(now)).param("id", row.id).update()
            // 키가 바뀌었거나 암호문이 깨졌으면 읽지 못한다. 예외 원문은 남기지 않는다.
            val body = try { decrypt(row.body, row.id) } catch (_: GeneralSecurityException) { null } catch (_: IllegalArgumentException) { null }
            if (body != null) return ClaimedMail(row.id, row.kind, row.recipient, row.subject, body, row.attempts + 1)
            // 보낼 수 없는 시도로 기록하고 다음 메일을 본다.
            log.warn("메일 본문을 복호화하지 못했다 id={} kind={}", row.id, row.kind)
            close(row.id, MailTransportFailure(false, "send_error"), now)
        }
    }

    /** 선점한 메일을 보냈다. 본문을 지운다. */
    fun sent(id: UUID) {
        jdbc.sql("""UPDATE enrollment.mail_outbox SET status='sent',encrypted_body=NULL,locked_until=NULL,failure_code=NULL,failure_detail=NULL,finished_at=:now
            WHERE id=:id AND status='sending'""").param("now", Timestamp.from(clock.instant())).param("id", id).update()
    }

    /** 선점한 메일을 보내지 못했다. 일시 실패이고 시도가 남았으면 재시도 간격 뒤로 되돌리고, 아니면 실패로 닫는다. */
    fun failed(id: UUID, failure: MailTransportFailure) {
        tx.executeWithoutResult { close(id, failure, clock.instant()) }
    }

    private fun close(id: UUID, failure: MailTransportFailure, now: Instant) {
        val attempts = jdbc.sql("SELECT attempts FROM enrollment.mail_outbox WHERE id=:id AND status='sending' FOR UPDATE").param("id", id)
            .query(Int::class.java).optional().orElse(null) ?: return
        val detail = failure.detail?.take(200)
        if (failure.permanent || attempts >= policy.maxAttempts) {
            jdbc.sql("""UPDATE enrollment.mail_outbox SET status='failed',encrypted_body=NULL,locked_until=NULL,failure_code=:code,failure_detail=:detail,finished_at=:now
                WHERE id=:id""").param("code", failure.code.take(40)).param("detail", detail).param("now", Timestamp.from(now)).param("id", id).update()
        } else {
            jdbc.sql("""UPDATE enrollment.mail_outbox SET status='queued',locked_until=NULL,failure_code=:code,failure_detail=:detail,next_attempt_at=:next
                WHERE id=:id""").param("code", failure.code.take(40)).param("detail", detail)
                .param("next", Timestamp.from(now.plus(policy.retryInterval))).param("id", id).update()
        }
    }

    private fun deliveryRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = MailDelivery(rs.getObject("id", UUID::class.java), rs.getString("dedup_key"),
        rs.getString("kind"), rs.getString("status"), rs.getInt("attempts"), rs.getTimestamp("queued_at").toInstant(),
        rs.getTimestamp("last_attempt_at")?.toInstant(), rs.getTimestamp("finished_at")?.toInstant(), rs.getString("failure_code"), rs.getString("failure_detail"))

    private fun encrypt(value: String, id: UUID): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(id.toString().toByteArray()) }
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray()))
    }
    private fun decrypt(value: String, id: UUID): String {
        val bytes = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(id.toString().toByteArray()) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private class Candidate(val id: UUID, val kind: String, val recipient: String, val subject: String, val body: String, val attempts: Int)

    private companion object {
        val log = LoggerFactory.getLogger(MailOutbox::class.java)
        const val DELIVERY = "SELECT id,dedup_key,kind,status,attempts,queued_at,last_attempt_at,finished_at,failure_code,failure_detail FROM enrollment.mail_outbox"
    }
}

/**
 * 선점 → 발송 → 결과 기록의 순서 (ADR 0037). 발송은 트랜잭션 밖이다.
 * 여러 작업이 동시에 돌아도 한 메일은 한 작업만 잡는다.
 */
class MailDispatcher(private val outbox: MailOutbox, private val transport: MailTransport) {
    /** 보낼 때가 된 메일이 없을 때까지 하나씩 처리한다. 처리한 수(성공·실패 모두)를 돌려준다. */
    fun runOnce(): Int {
        var handled = 0
        while (true) {
            val mail = outbox.claim() ?: return handled
            val failure = try {
                transport.send(mail)
                null
            } catch (failure: MailTransportFailure) {
                failure
            } catch (error: RuntimeException) {
                // 분류하지 못한 오류의 원문에는 본문이 섞일 수 있어 종류만 남긴다.
                log.warn("메일 발송 중 분류하지 못한 오류 id={} kind={} error={}", mail.id, mail.kind, error.javaClass.simpleName)
                MailTransportFailure(false, "send_error")
            }
            // 결과 기록이 실패하면 예외를 그대로 올린다. 메일은 sending 에 남아 임대가 끝난 뒤 다시 선점된다.
            if (failure == null) {
                outbox.sent(mail.id)
                log.info("메일을 보냈다 id={} kind={} attempt={}", mail.id, mail.kind, mail.attempt)
            } else {
                outbox.failed(mail.id, failure)
                log.warn("메일을 보내지 못했다 id={} kind={} attempt={} code={} permanent={}", mail.id, mail.kind, mail.attempt, failure.code, failure.permanent)
            }
            handled++
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MailDispatcher::class.java)
    }
}
