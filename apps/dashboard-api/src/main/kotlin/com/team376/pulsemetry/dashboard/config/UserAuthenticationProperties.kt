package com.team376.pulsemetry.dashboard.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 조회 앱에는 공개키만 설정한다. 사용자 세션 조회는 기존 읽기 전용 RDS를 사용한다. */
@ConfigurationProperties("pulsemetry.user-auth")
class UserAuthenticationProperties {
    var enabled: Boolean = false
    var issuer: String = ""
    var audience: String = ""
    var publicKeyFiles: Map<String, String> = emptyMap()
    var allowedOrigins: List<String> = emptyList()
}
