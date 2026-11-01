package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.domain.enrollment.command.ManagementException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** 전송 형식과 무관한 명령 값. JSON 입력 해석은 CommandValues가 담당한다. */
object CommandRules {
    fun uuid(raw: String): UUID = try { UUID.fromString(raw).also { if (it.toString() != raw.lowercase()) fail("invalid_request", 400) } } catch (_: IllegalArgumentException) { fail("invalid_request", 400) }
    fun checkVersion(old: Instant, expected: Long) { if (old.toEpochMilli() != expected) fail("version_conflict", 409) }
    fun advance(now: Instant, old: Instant): Instant = Instant.ofEpochMilli(maxOf(now.toEpochMilli(), old.toEpochMilli() + 1))
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun fail(code: String, status: Int, field: String? = null): Nothing = throw ManagementException(code, status, field)
}
