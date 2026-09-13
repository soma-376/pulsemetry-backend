package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.persistence.telemetry.DashboardClickHouseReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

@Configuration(proxyBeanMethods = false)
class DashboardTelemetryConfig {
    @Bean fun dashboardReader(@Value("\${pulsemetry.dashboard.clickhouse-url:http://127.0.0.1:8123}") endpoint: String) =
        DashboardClickHouseReader(URI(endpoint))
}
