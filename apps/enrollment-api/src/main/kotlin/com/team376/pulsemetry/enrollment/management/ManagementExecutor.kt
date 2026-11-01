package com.team376.pulsemetry.enrollment.management

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

import com.team376.pulsemetry.persistence.enrollment.management.*
import com.team376.pulsemetry.enrollment.management.CommandRules.fail

/** 조직 잠금·역할 재검증·재시도·업무와 outbox 원자성을 조정한다. 업무는 호출자가 명시한다. */
class ManagementExecutor(private val journal: CommandJournal, manager: PlatformTransactionManager,
    private val clock: Clock, encryptionKey: String) {
    private val tx = TransactionTemplate(manager)
    private val random = SecureRandom()
    private val key = SecretKeySpec(Base64.getDecoder().decode(encryptionKey).also { require(it.size == 32) }, "AES")
    fun execute(tenant: UUID, actor: UUID, operation: String, requestHash: String, idempotencyKey: String?, replayable: Boolean, work: (Instant) -> String): String =
        requireNotNull(tx.execute {
            // 조직별 관리 명령만 직렬화한다. 읽기와 다른 조직의 명령은 잠그지 않는다.
            val active = journal.lockTenant(tenant) ?: fail("not_found", 404)
            val role = journal.lockActorRole(active, actor)
            if (role !in setOf("owner", "admin")) fail("forbidden", 403)
            val hash = requestHash
            val now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            if (replayable) {
                if (idempotencyKey == null || !Regex("[A-Za-z0-9_-]{8,128}").matches(idempotencyKey)) fail("invalid_request", 400, "Idempotency-Key")
                val previous = journal.findReplay(tenant, actor, operation, idempotencyKey)
                if (previous != null && previous.third > now) {
                    if (previous.first != hash) fail("idempotency_conflict", 409)
                    return@execute decrypt(previous.second, "$tenant/$actor/$operation/$idempotencyKey")
                }
                journal.deleteReplay(tenant, actor, operation, idempotencyKey)
            }
            val result = work(now)
            if (replayable) {
                journal.saveReplay(tenant, actor, operation, idempotencyKey!!, hash, encrypt(result, "$tenant/$actor/$operation/$idempotencyKey"), now.plusSeconds(86400))
            }
            result
        })
    private fun encrypt(value: String, aad: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(aad.toByteArray()) }
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray()))
    }
    private fun decrypt(value: String, aad: String): String {
        val bytes = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0,12))); updateAAD(aad.toByteArray()) }
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8)
    }
}
