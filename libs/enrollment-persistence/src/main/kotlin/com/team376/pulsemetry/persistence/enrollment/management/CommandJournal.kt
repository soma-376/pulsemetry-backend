package com.team376.pulsemetry.persistence.enrollment.management

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.time.Instant
import java.sql.Timestamp

/** 명령 트랜잭션이 호출하는 저장 연산. HTTP 경로로 업무를 선택하지 않는다. */
class CommandJournal(private val jdbc: JdbcClient) {
    fun lockTenant(tenant: UUID) = jdbc.sql("SELECT id FROM enrollment.tenants WHERE id=:tenant AND status='active' AND deleted_at IS NULL FOR UPDATE")
                .param("tenant", tenant).query(UUID::class.java).optional().orElse(null)

    fun lockActorRole(active: UUID, actor: UUID) = jdbc.sql("SELECT role::text FROM enrollment.members WHERE tenant_id=:tenant AND id=:actor AND status='active' FOR SHARE")
                .param("tenant", active).param("actor", actor).query(String::class.java).optional().orElse(null)

    fun findReplay(tenant: UUID, actor: UUID, operation: String, idempotencyKey: String) = jdbc.sql("""SELECT request_hash,encrypted_response,expires_at FROM enrollment.management_commands
                    WHERE tenant_id=:tenant AND actor_id=:actor AND command_path=:path AND idempotency_key=:key""")
                    .param("tenant", tenant).param("actor", actor).param("path", operation).param("key", idempotencyKey)
                    .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()) }.optional().orElse(null)

    fun deleteReplay(tenant: UUID, actor: UUID, operation: String, idempotencyKey: String) = jdbc.sql("DELETE FROM enrollment.management_commands WHERE tenant_id=:tenant AND actor_id=:actor AND command_path=:path AND idempotency_key=:key")
                    .param("tenant", tenant).param("actor", actor).param("path", operation).param("key", idempotencyKey).update()

    fun saveReplay(tenant: UUID, actor: UUID, operation: String, idempotencyKey: String, hash: String, response: String, expires: Instant) = jdbc.sql("""INSERT INTO enrollment.management_commands VALUES (:tenant,:actor,:path,:key,:hash,:response,:expires)""")
                    .param("tenant", tenant).param("actor", actor).param("path", operation).param("key", idempotencyKey)
                    .param("hash", hash).param("response", response)
                    .param("expires", Timestamp.from(expires)).update()
}
