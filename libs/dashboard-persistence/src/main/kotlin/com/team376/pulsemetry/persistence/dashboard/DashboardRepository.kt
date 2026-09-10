package com.team376.pulsemetry.persistence.dashboard

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** 감사 INSERT는 개인 데이터 조회보다 먼저 커밋한다. 오류를 삼키지 않는다. */
class DashboardRepository(private val jdbc: JdbcClient) {
    fun audit(tenant: UUID, member: UUID, action: String, target: String, reason: String) {
        jdbc.sql("""INSERT INTO dashboard.audit_log(id,tenant_id,member_id,action,target,reason)
            VALUES (:id,:tenant,:member,:action,:target,:reason)""")
            .param("id", UUID.randomUUID()).param("tenant", tenant).param("member", member)
            .param("action", action).param("target", target).param("reason", reason).update()
    }
}
