package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.security.user.AuthRateLimit
import com.team376.pulsemetry.security.user.AuthRateLimiter
import com.team376.pulsemetry.security.user.UserJwt
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration

/** 인증 요청 제한의 한도(ADR 0052). 기본값은 진입·세션 모두 60초 30회다. */
@ConfigurationProperties("pulsemetry.user-auth.rate-limit")
class AuthRateLimitProperties {
    var entry = Limit()
    var session = Limit()

    class Limit {
        var requests: Int = AuthRateLimit.DEFAULT.requests
        var window: Duration = AuthRateLimit.DEFAULT.window
        fun toLimit() = AuthRateLimit(requests, window)
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.user-auth", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(AuthRateLimitProperties::class)
class AuthRateLimitConfig {
    @Bean
    fun authRateLimiter(repository: UserAuthRepository, manager: PlatformTransactionManager, jwt: UserJwt, clock: Clock,
        properties: AuthRateLimitProperties) =
        AuthRateLimiter(repository, manager, jwt, clock, properties.entry.toLimit(), properties.session.toLimit())
}
