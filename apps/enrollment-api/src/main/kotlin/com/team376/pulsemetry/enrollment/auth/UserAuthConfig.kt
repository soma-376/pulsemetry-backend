package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.security.user.UserJwt
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path
import java.time.Clock

@ConfigurationProperties("pulsemetry.user-auth")
class UserAuthProperties {
    var enabled: Boolean = false
    var issuer: String = ""
    var audience: String = ""
    var activeKid: String = ""
    var privateKeyFile: String = ""
    var publicKeyFiles: Map<String, String> = emptyMap()
    var allowedOrigins: List<String> = emptyList()
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.user-auth", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(UserAuthProperties::class)
class UserAuthConfig {
    @Bean
    fun userJwt(p: UserAuthProperties, clock: Clock): UserJwt {
        require(p.privateKeyFile.isNotBlank() && p.publicKeyFiles.isNotEmpty()) { "사용자 인증 키 파일 설정이 필요하다" }
        return UserJwt(p.issuer, p.audience, p.activeKid, UserJwt.privateKey(Path.of(p.privateKeyFile)),
            p.publicKeyFiles.mapValues { UserJwt.publicKey(Path.of(it.value)) }, clock)
    }
    @Bean
    fun userAuthRepository(jdbc: JdbcClient) = UserAuthRepository(jdbc)
    @Bean
    fun userAuthService(repository: UserAuthRepository, manager: PlatformTransactionManager, jwt: UserJwt, clock: Clock) =
        UserAuthService(repository, manager, jwt, clock)
}
