package com.team376.pulsemetry.persistence.enrollment.repository

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 사용자 인증 SQL. 잠금 메서드는 호출자가 연 트랜잭션 안에서만 쓴다 (ADR 0018). */
class UserAuthRepository(private val jdbc: JdbcClient) {
    fun loginOrganizations(email: String): List<LoginOrganization> = jdbc.sql("""
        SELECT DISTINCT t.id, t.name, t.oidc_issuer FROM enrollment.members m
        JOIN enrollment.tenants t ON t.id=m.tenant_id
        WHERE lower(m.email)=:email AND m.status IN ('invited','active') AND t.status='active'
          AND t.sso_enabled
          AND (SELECT count(*) FROM enrollment.members other WHERE other.tenant_id=t.id AND lower(other.email)=:email)=1
        ORDER BY t.name, t.id
    """).param("email", email).query { r, _ ->
        LoginOrganization(r.getObject("id", UUID::class.java), r.getString("name"), r.getString("oidc_issuer"))
    }.list()
    fun tenantOidc(tenant: UUID): TenantOidcConfiguration? = jdbc.sql("""
        SELECT id,oidc_issuer,oidc_client_id,oidc_client_secret_ref,oidc_require_verified_email
        FROM enrollment.tenants WHERE id=:id AND status='active' AND sso_enabled
    """).param("id", tenant).query { r, _ -> TenantOidcConfiguration(
        r.getObject("id", UUID::class.java), r.getString("oidc_issuer"), r.getString("oidc_client_id"),
        r.getString("oidc_client_secret_ref"), r.getBoolean("oidc_require_verified_email")) }.optional().orElse(null)

    fun member(tenant: UUID, email: String): AuthMember? = jdbc.sql("""
        SELECT m.*, t.status::text AS tenant_status, t.oidc_issuer FROM enrollment.members m
        JOIN enrollment.tenants t ON t.id=m.tenant_id WHERE m.tenant_id=:tenant AND m.email=:email
    """).param("tenant", tenant).param("email", email).query { rs, _ -> memberRow(rs) }.optional().orElse(null)

    fun member(id: UUID, lock: Boolean = false): AuthMember? = jdbc.sql("""
        SELECT m.*, t.status::text AS tenant_status, t.oidc_issuer FROM enrollment.members m
        JOIN enrollment.tenants t ON t.id=m.tenant_id WHERE m.id=:id
        ${if (lock) "FOR UPDATE OF m FOR SHARE OF t" else ""}
    """).param("id", id).query { rs, _ -> memberRow(rs) }.optional().orElse(null)

    fun oidcMember(tenant: UUID, issuer: String, subject: String): AuthMember? = jdbc.sql("""
        SELECT m.*, t.status::text AS tenant_status, t.oidc_issuer FROM enrollment.members m
        JOIN enrollment.tenants t ON t.id=m.tenant_id
        WHERE m.tenant_id=:tenant AND t.sso_enabled AND t.oidc_issuer=:issuer AND m.oidc_subject=:subject
        FOR UPDATE OF m FOR SHARE OF t
    """).param("tenant", tenant).param("issuer", issuer).param("subject", subject)
        .query { rs, _ -> memberRow(rs) }.optional().orElse(null)

    /** 대소문자만 다른 중복 이메일도 임의 회원으로 연결하지 않는다. */
    fun oidcLoginMember(tenant: UUID, email: String): UUID? = jdbc.sql("""
        SELECT m.id FROM enrollment.members m JOIN enrollment.tenants t ON t.id=m.tenant_id
        WHERE m.tenant_id=:tenant AND lower(m.email)=:email AND m.status IN ('invited','active')
          AND t.status='active' AND t.sso_enabled
          AND (SELECT count(*) FROM enrollment.members other WHERE other.tenant_id=:tenant AND lower(other.email)=:email)=1
    """).param("tenant", tenant).param("email", email).query(UUID::class.java).optional().orElse(null)

    fun oidcLinkCandidate(tenant: UUID, issuer: String, member: UUID): AuthMember? = jdbc.sql("""
        SELECT m.*, t.status::text AS tenant_status, t.oidc_issuer FROM enrollment.members m
        JOIN enrollment.tenants t ON t.id=m.tenant_id
        WHERE m.id=:member AND m.tenant_id=:tenant AND t.sso_enabled AND t.oidc_issuer=:issuer
        FOR UPDATE OF m FOR SHARE OF t
    """).param("member", member).param("tenant", tenant).param("issuer", issuer)
        .query { rs, _ -> memberRow(rs) }.optional().orElse(null)

    fun linkOidcSubject(member: UUID, subject: String): Boolean = jdbc.sql("""
        UPDATE enrollment.members SET oidc_subject=:subject,status='active'
        WHERE id=:member AND oidc_subject IS NULL AND status IN ('invited','active')
    """).param("member", member).param("subject", subject).update() == 1

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
        r.getString("status"), r.getString("tenant_status"), r.getString("oidc_issuer"), r.getString("oidc_subject"))
}

data class AuthMember(val id: UUID, val tenantId: UUID, val email: String, val role: String,
    val status: String, val tenantStatus: String, val oidcIssuer: String? = null, val oidcSubject: String? = null) {
    fun active() = status == "active" && tenantStatus == "active"
}
data class AuthSession(val id: UUID, val memberId: UUID, val revision: Int, val createdAt: Instant,
    val expiresAt: Instant, val revokedAt: Instant? = null)
data class RefreshRecord(val sessionId: UUID, val usedAt: Instant?)
data class AuthorizationCode(val memberId: UUID, val redirectUri: String, val challenge: String,
    val expiresAt: Instant, val usedAt: Instant?)
data class AuthAttempt(val windowStartedAt: Instant, val attempts: Int, val lockedUntil: Instant?)
data class LoginOrganization(val id: UUID, val name: String, val issuer: String)

/** 비밀 원문은 포함하지 않는다. 로그인 왕복 중 설정 변경 검증에도 사용한다. */
data class TenantOidcConfiguration(val tenantId: UUID, val issuer: String, val clientId: String,
    val secretRef: String, val requireVerifiedEmail: Boolean) : java.io.Serializable
