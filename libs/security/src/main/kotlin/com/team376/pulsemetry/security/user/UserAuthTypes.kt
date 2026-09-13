package com.team376.pulsemetry.security.user

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** HTTP 매핑은 앱이 담당한다. 내부 예외/입력값을 메시지에 섞지 않는다. */
class UserAuthException(val code: String, val status: Int = 401, val retryAfter: Long? = null) : RuntimeException(code)

class UserTokens(val accessToken: String, val refreshToken: String, val expiresIn: Long)
data class UserIdentity(val memberId: UUID, val tenantId: UUID, val role: String, val sessionId: UUID, val revision: Int?, val sessionKind: String = "cli")

internal object UserSecrets {
    private val random = SecureRandom()
    fun token(prefix: String): String = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun challenge(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII)))
    fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    fun email(value: String): String = value.trim().lowercase(Locale.ROOT)
}
internal fun invalid(): Nothing = throw UserAuthException("invalid_credentials")
internal fun badRequest(): Nothing = throw UserAuthException("invalid_request", 400)
