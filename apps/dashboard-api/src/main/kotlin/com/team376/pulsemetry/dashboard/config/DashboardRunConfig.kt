package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.persistence.dashboard.DashboardRuns
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class DashboardRunConfig {
    @Bean fun dashboardRuns(jdbc: JdbcClient, manager: PlatformTransactionManager) = DashboardRuns(jdbc, manager)
}
