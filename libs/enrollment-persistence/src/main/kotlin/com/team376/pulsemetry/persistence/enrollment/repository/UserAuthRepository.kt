package com.team376.pulsemetry.persistence.enrollment.repository

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 사용자 인증 SQL. 잠금 메서드는 호출자가 연 트랜잭션 안에서만 쓴다 (ADR 0018). */
class UserAuthRepository(private val jdbc: JdbcClient) {
    fun member(tenant: UUID, email: String): AuthMember? = jdbc.sql("""
        SELECT m.*, t.status::text AS tenant_status FROM enrollment.members m
        JOIN enrollment.tenants t ON t.id=m.tenant_id WHERE m.tenant_id=:tenant AND m.email=:email
    """).param("tenant", tenant).param("email", email).query { rs, _ -> memberRow(rs) }.optional().orElse(null)

    fun member(id: UUID, lock: Boolean = false): AuthMember? = jdbc.sql("""
        SELECT m.*, t.status::text AS tenant_status FROM enrollment.members m
        JOIN enrollment.tenants t ON t.id=m.tenant_id WHERE m.id=:id
        ${if (lock) "FOR UPDATE OF m FOR SHARE OF t" else ""}
    """).param("id", id).query { rs, _ -> memberRow(rs) }.optional().orElse(null)

    fun lockInvitation(hash: String): SignupInvitation? = jdbc.sql("""
        SELECT * FROM enrollment.invitations WHERE code_hash=:hash FOR UPDATE
    """).param("hash", hash).query { r, _ -> SignupInvitation(
        r.getObject("id", UUID::class.java), r.getObject("tenant_id", UUID::class.java),
        r.getObject("target_member_id", UUID::class.java), r.getTimestamp("expires_at").toInstant(),
        r.getTimestamp("revoked_at")?.toInstant(), r.getTimestamp("signup_used_at")?.toInstant(),
    ) }.optional().orElse(null)

    fun signup(member: UUID, invitation: UUID, hash: String, now: Instant) {
        check(jdbc.sql("""UPDATE enrollment.members SET password_hash=:hash, status='active', updated_at=:now
            WHERE id=:id AND password_hash IS NULL AND status!='suspended'""")
            .param("hash", hash).param("now", Timestamp.from(now)).param("id", member).update() == 1)
        check(jdbc.sql("""UPDATE enrollment.invitations SET signup_used_at=:now
            WHERE id=:id AND signup_used_at IS NULL""")
            .param("now", Timestamp.from(now)).param("id", invitation).update() == 1)
    }

    fun activeRevision(tenant: UUID): Int? = jdbc.sql("""
        SELECT version FROM enrollment.manifests WHERE tenant_id=:id AND is_active=true
    """).param("id", tenant).query(Int::class.javaObjectType).optional().orElse(null)

    fun createSession(s: AuthSession) {
        jdbc.sql("""INSERT INTO enrollment.user_sessions(id,member_id,manifest_revision,created_at,expires_at)
            VALUES (:id,:member,:revision,:now,:expires)""")
            .param("id", s.id).param("member", s.memberId).param("revision", s.revision)
            .param("now", Timestamp.from(s.createdAt)).param("expires", Timestamp.from(s.expiresAt)).update()
    }

    fun session(id: UUID, lock: Boolean = false): AuthSession? = jdbc.sql("""
        SELECT * FROM enrollment.user_sessions WHERE id=:id ${if (lock) "FOR UPDATE" else ""}
    """).param("id", id).query { r, _ -> AuthSession(
        r.getObject("id", UUID::class.java), r.getObject("member_id", UUID::class.java),
        r.getInt("manifest_revision"), r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("expires_at").toInstant(), r.getTimestamp("revoked_at")?.toInstant(),
    ) }.optional().orElse(null)

    fun refresh(hash: String): RefreshRecord? = jdbc.sql("""
        SELECT * FROM enrollment.user_refresh_tokens WHERE token_hash=:hash
    """).param("hash", hash).query { r, _ -> RefreshRecord(
        r.getObject("session_id", UUID::class.java), r.getTimestamp("used_at")?.toInstant(),
    ) }.optional().orElse(null)

    fun addRefresh(hash: String, session: UUID, now: Instant) {
        jdbc.sql("INSERT INTO enrollment.user_refresh_tokens(token_hash,session_id,issued_at) VALUES (:hash,:id,:now)")
            .param("hash", hash).param("id", session).param("now", Timestamp.from(now)).update()
    }

    fun consumeRefresh(hash: String, now: Instant) {
        check(jdbc.sql("UPDATE enrollment.user_refresh_tokens SET used_at=:now WHERE token_hash=:hash AND used_at IS NULL")
            .param("hash", hash).param("now", Timestamp.from(now)).update() == 1)
    }

    fun revokeSession(id: UUID, now: Instant) {
        jdbc.sql("UPDATE enrollment.user_sessions SET revoked_at=COALESCE(revoked_at,:now) WHERE id=:id")
            .param("id", id).param("now", Timestamp.from(now)).update()
    }

    fun addCode(hash: String, member: UUID, redirect: String, challenge: String, expires: Instant) {
        jdbc.sql("""INSERT INTO enrollment.user_authorization_codes(code_hash,member_id,redirect_uri,code_challenge,expires_at)
            VALUES (:hash,:member,:redirect,:challenge,:expires)""")
            .param("hash", hash).param("member", member).param("redirect", redirect)
            .param("challenge", challenge).param("expires", Timestamp.from(expires)).update()
    }

    fun lockCode(hash: String): AuthorizationCode? = jdbc.sql("""
        SELECT * FROM enrollment.user_authorization_codes WHERE code_hash=:hash FOR UPDATE
    """).param("hash", hash).query { r, _ -> AuthorizationCode(
        r.getObject("member_id", UUID::class.java), r.getString("redirect_uri"), r.getString("code_challenge"),
        r.getTimestamp("expires_at").toInstant(), r.getTimestamp("used_at")?.toInstant(),
    ) }.optional().orElse(null)

    fun consumeCode(hash: String, now: Instant) {
        check(jdbc.sql("UPDATE enrollment.user_authorization_codes SET used_at=:now WHERE code_hash=:hash AND used_at IS NULL")
            .param("hash", hash).param("now", Timestamp.from(now)).update() == 1)
    }

    fun lockAttempt(hash: String, now: Instant): AuthAttempt {
        jdbc.sql("""INSERT INTO enrollment.auth_attempts(subject_hash,window_started_at) VALUES (:hash,:now)
            ON CONFLICT DO NOTHING""").param("hash", hash).param("now", Timestamp.from(now)).update()
        return jdbc.sql("SELECT * FROM enrollment.auth_attempts WHERE subject_hash=:hash FOR UPDATE")
            .param("hash", hash).query { r, _ -> AuthAttempt(r.getTimestamp("window_started_at").toInstant(),
                r.getInt("attempts"), r.getTimestamp("locked_until")?.toInstant()) }.single()
    }

    fun saveAttempt(hash: String, window: Instant, attempts: Int, locked: Instant?) {
        jdbc.sql("""UPDATE enrollment.auth_attempts SET window_started_at=:window, attempts=:attempts,
            locked_until=:locked WHERE subject_hash=:hash""")
            .param("hash", hash).param("window", Timestamp.from(window)).param("attempts", attempts)
            .param("locked", locked?.atOffset(java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update()
    }

    private fun memberRow(r: ResultSet) = AuthMember(r.getObject("id", UUID::class.java),
        r.getObject("tenant_id", UUID::class.java), r.getString("email"), r.getString("role"),
        r.getString("status"), r.getString("tenant_status"), r.getString("password_hash"))
}

data class AuthMember(val id: UUID, val tenantId: UUID, val email: String, val role: String,
    val status: String, val tenantStatus: String, val passwordHash: String?) {
    fun active() = status == "active" && tenantStatus == "active"
}
data class SignupInvitation(val id: UUID, val tenantId: UUID, val memberId: UUID, val expiresAt: Instant,
    val revokedAt: Instant?, val signupUsedAt: Instant?)
data class AuthSession(val id: UUID, val memberId: UUID, val revision: Int, val createdAt: Instant,
    val expiresAt: Instant, val revokedAt: Instant? = null)
data class RefreshRecord(val sessionId: UUID, val usedAt: Instant?)
data class AuthorizationCode(val memberId: UUID, val redirectUri: String, val challenge: String,
    val expiresAt: Instant, val usedAt: Instant?)
data class AuthAttempt(val windowStartedAt: Instant, val attempts: Int, val lockedUntil: Instant?)
