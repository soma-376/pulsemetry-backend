package com.team376.pulsemetry.persistence.enrollment.invitation

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.time.Instant
import java.sql.Timestamp

/** 명령 트랜잭션이 호출하는 저장 연산. HTTP 경로로 업무를 선택하지 않는다. */
class InvitationCommandStore(private val jdbc: JdbcClient) {
    fun unrevokedCount(waiting: UUID) = jdbc.sql("SELECT count(*) FROM enrollment.invitations WHERE target_member_id=:id AND revoked_at IS NULL")
                .param("id", waiting).query(Int::class.java).single()

    fun insertInvitation(invitation: UUID, tenant: UUID, member: UUID, actor: UUID, codeHash: String, expires: Instant, now: Instant) = jdbc.sql("INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at,created_at) VALUES (:id,:tenant,:member,:actor,:hash,:expires,:now)")
                    .param("id", invitation).param("tenant", tenant).param("member", member).param("actor", actor).param("hash", codeHash)
                    .param("expires", Timestamp.from(expires)).param("now", Timestamp.from(now)).update()

    fun revoke(now: Instant, tenant: UUID, id: UUID) = jdbc.sql("UPDATE enrollment.invitations SET revoked_at=:now WHERE tenant_id=:tenant AND id=:id AND revoked_at IS NULL AND used_at IS NULL")
            .param("now", Timestamp.from(now)).param("tenant", tenant).param("id", id).update()

    fun lockInvitation(tenant: UUID, id: UUID) = jdbc.sql("""SELECT target_member_id,used_at,signup_used_at FROM enrollment.invitations
            WHERE tenant_id=:tenant AND id=:id AND revoked_at IS NULL FOR UPDATE""")
            .param("tenant", tenant).param("id", id).query { r, _ -> Triple(r.getObject(1, UUID::class.java), r.getTimestamp(2), r.getTimestamp(3)) }
            .optional().orElse(null)

    fun insertReplacement(id: UUID, tenant: UUID, member: UUID, actor: UUID, codeHash: String, expires: Instant, now: Instant, used: Timestamp?, signup: Timestamp?) = jdbc.sql("""INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at,created_at,used_at,signup_used_at)
            VALUES (:id,:tenant,:member,:actor,:hash,:expires,:now,:used,:signup)""")
            .param("id", id).param("tenant", tenant).param("member", member).param("actor", actor).param("hash", codeHash)
            .param("expires", Timestamp.from(expires)).param("now", Timestamp.from(now)).param("used", used).param("signup", signup).update()

    fun revokeInstallationInvitations(now: Instant, tenant: UUID, id: UUID) = jdbc.sql("""UPDATE enrollment.invitations SET revoked_at=:now WHERE tenant_id=:tenant AND target_member_id=:id
            AND revoked_at IS NULL AND used_at IS NULL AND signup_used_at IS NOT NULL RETURNING id""")
            .param("now", Timestamp.from(now)).param("tenant", tenant).param("id", id).query { r, _ -> r.getObject(1, UUID::class.java) }.list().sorted()

    fun insertInstallationInvitation(invitation: UUID, tenant: UUID, id: UUID, actor: UUID, codeHash: String, expires: Instant, now: Instant) = jdbc.sql("""INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at,created_at,signup_used_at)
            VALUES (:id,:tenant,:member,:actor,:hash,:expires,:now,:now)""")
            .param("id", invitation).param("tenant", tenant).param("member", id).param("actor", actor).param("hash", codeHash)
            .param("expires", Timestamp.from(expires)).param("now", Timestamp.from(now)).update()
}
