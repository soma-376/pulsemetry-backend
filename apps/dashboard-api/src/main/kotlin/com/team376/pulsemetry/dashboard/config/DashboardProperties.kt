package com.team376.pulsemetry.dashboard.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.UUID

@ConfigurationProperties("pulsemetry.dashboard")
class DashboardProperties {
    lateinit var tenantId: UUID
    var issuer = ""
    var audience = "pulsemetry-dashboard"
    var activeKid = ""
    var privateKeyFile = ""
    var publicKeyFiles: Map<String, String> = emptyMap()
    var allowedOrigins: List<String> = emptyList()
}
