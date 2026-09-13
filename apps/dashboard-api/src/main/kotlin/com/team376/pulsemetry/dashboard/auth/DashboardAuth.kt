package com.team376.pulsemetry.dashboard.auth

import com.team376.pulsemetry.dashboard.config.DashboardProperties
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserJwt
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DashboardProperties::class)
class DashboardAuth {
    @Bean fun repository(jdbc: JdbcClient) = UserAuthRepository(jdbc)
    @Bean fun jwt(p: DashboardProperties, clock: Clock): UserJwt {
        require(p.tenantId.toString().isNotBlank())
        require(p.audience == "pulsemetry-dashboard") { "대시보드 audience는 CLI와 분리한다" }
        return UserJwt(p.issuer, p.audience, p.activeKid, UserJwt.privateKey(Path.of(p.privateKeyFile)),
            p.publicKeyFiles.mapValues { UserJwt.publicKey(Path.of(it.value)) }, clock, "web")
    }
    @Bean fun auth(repository: UserAuthRepository, manager: PlatformTransactionManager, jwt: UserJwt, clock: Clock) =
        UserAuthService(repository, manager, jwt, clock)

    @Bean fun security(http: HttpSecurity, auth: UserAuthService, p: DashboardProperties, mapper: ObjectMapper): SecurityFilterChain {
        val cors = UrlBasedCorsConfigurationSource()
        cors.registerCorsConfiguration("/v1/**", CorsConfiguration().apply {
            allowedOrigins = p.allowedOrigins
            allowedMethods = listOf("GET", "POST", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "X-Audit-Reason", "X-Request-Id")
            exposedHeaders = listOf("Location", "Retry-After", "X-Request-Id", "Content-Disposition")
            allowCredentials = false
        })
        http.csrf { it.disable() }.cors { it.configurationSource(cors) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }.formLogin { it.disable() }.httpBasic { it.disable() }
            .authorizeHttpRequests { it.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll().requestMatchers("/v1/auth/login", "/v1/healthz").permitAll().anyRequest().authenticated() }
            .exceptionHandling {
                it.authenticationEntryPoint { _, res, _ -> error(res, mapper, 401, "invalid_credentials") }
                it.accessDeniedHandler { _, res, _ -> error(res, mapper, 403, "forbidden") }
            }
            .addFilterBefore(object : OncePerRequestFilter() {
                override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
                    val token = req.getHeader("Authorization")
                    if (token != null) {
                        val identity = try {
                            if (!token.startsWith("Bearer ")) throw UserAuthException("invalid_credentials")
                            auth.verify(token.removePrefix("Bearer ")).also {
                                if (it.tenantId != p.tenantId || it.role !in setOf("owner", "admin")) throw UserAuthException("forbidden", 403)
                            }
                        } catch (e: UserAuthException) { error(res, mapper, e.status, e.code); return }
                        catch (_: DataAccessException) { error(res, mapper, 503, "service_unavailable"); return }
                        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                            identity, null, listOf(SimpleGrantedAuthority("ROLE_${identity.role}")))
                    }
                    chain.doFilter(req, res)
                }
            }, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}

internal fun error(response: HttpServletResponse, mapper: ObjectMapper, status: Int, code: String) {
    response.status = status
    response.contentType = "application/json"
    if (status == 503) response.setHeader("Retry-After", "2")
    val id = UUID.randomUUID().toString()
    response.setHeader("X-Request-Id", id)
    response.writer.write(mapper.writeValueAsString(mapOf("error" to code, "message" to code, "request_id" to id)))
}
