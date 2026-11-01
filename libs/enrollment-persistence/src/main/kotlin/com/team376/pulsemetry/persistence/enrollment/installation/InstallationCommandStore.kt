package com.team376.pulsemetry.persistence.enrollment.installation

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import com.team376.pulsemetry.persistence.enrollment.installation.AppliedPolicyVersion
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationNotifier

/** 명령 트랜잭션이 호출하는 저장 연산. HTTP 경로로 업무를 선택하지 않는다. */
class InstallationCommandStore(private val jdbc: JdbcClient) {
    fun activePolicyVersion(tenant: UUID) = jdbc.sql("SELECT version FROM enrollment.manifests WHERE tenant_id=:tenant AND is_active").param("tenant", tenant)
            .query(Long::class.java).optional().orElse(null)

    fun notificationTargets(tenant: UUID, ids: List<UUID>, expected: Long) = jdbc.sql("""SELECT i.id, i.status::text AS status, i.hostname, i.platform::text AS platform, m.email, m.status::text AS member_status,
                ${AppliedPolicyVersion.SQL} AS applied_version
            FROM enrollment.installations i JOIN enrollment.members m ON m.id = i.member_id
            WHERE i.tenant_id = :tenant AND i.id = ANY(CAST(:ids AS uuid[]))""")
            .param("tenant", tenant).param("ids", ids.joinToString(",", "{", "}"))
            .query { rs, _ ->
                val applied = rs.getLong("applied_version").takeUnless { rs.wasNull() }
                NotificationTarget(rs.getString("status"), rs.getString("member_status"),
                    InstallationNotifier.Notice(rs.getObject("id", UUID::class.java), rs.getString("email"), "", rs.getString("hostname"),
                        rs.getString("platform"), expected, applied))
            }
            .list()
}

data class NotificationTarget(val installationStatus: String, val memberStatus: String, val notice: InstallationNotifier.Notice)
