package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.persistence.dashboard.DashboardRepository
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Configuration(proxyBeanMethods = false)
class DashboardPersistenceConfig {
    @Bean
    @org.springframework.context.annotation.DependsOn("flywayInitializer")
    fun dashboardMigrations(dataSource: javax.sql.DataSource) = org.springframework.beans.factory.InitializingBean {
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/dashboard")
            .schemas("dashboard").defaultSchema("dashboard").load().migrate()
    }
    @Bean fun dashboardRepository(jdbc: JdbcClient) = DashboardRepository(jdbc)
}

@Service
class DashboardAccess(private val jdbc: JdbcClient, private val repository: DashboardRepository) {
    fun teamIds(identity: UserIdentity): Set<UUID> = jdbc.sql("""SELECT t.id FROM enrollment.teams t
        WHERE t.tenant_id=:tenant AND (:owner OR EXISTS (SELECT 1 FROM enrollment.team_memberships tm
        WHERE tm.team_id=t.id AND tm.member_id=:member AND tm.left_at IS NULL))""")
        .param("tenant", identity.tenantId).param("owner", identity.role == "owner").param("member", identity.memberId)
        .query(UUID::class.java).list().filterNotNull().toSet()

    fun teams(identity: UserIdentity, requested: Set<UUID>): Set<UUID> {
        val allowed = teamIds(identity)
        if (!allowed.containsAll(requested)) throw UserAuthException("forbidden", 403)
        return requested.ifEmpty { allowed }
    }

    fun personal(identity: UserIdentity, header: String?, action: String, target: String): String {
        if (identity.role != "owner") throw UserAuthException("forbidden", 403)
        val reason = try { URLDecoder.decode(header ?: "", StandardCharsets.UTF_8).trim() }
            catch (_: IllegalArgumentException) { throw UserAuthException("invalid_audit_reason", 400) }
        if (reason.codePointCount(0, reason.length) !in 10..500 || reason.any { it.isISOControl() })
            throw UserAuthException("audit_reason_required", 403)
        repository.audit(identity.tenantId, identity.memberId, action, target, reason)
        return reason
    }
}
