package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.persistence.dashboard.DashboardRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

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
