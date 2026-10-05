package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.authentication.DashboardAuthenticationFilter
import com.team376.pulsemetry.dashboard.authentication.DashboardAuthenticator
import com.team376.pulsemetry.dashboard.authentication.RejectingDashboardAuthenticator
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.ErrorResponseWriter
import jakarta.servlet.DispatcherType
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.security.user.UserAccessVerifier
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserJwt
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.nio.file.Path
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter

/**
 * 대시보드 API 의 필터 체인 둘 (ADR 0022 §3·§6).
 *
 * ## 조직 경로 — 포트로 인증한다
 *
 * `/api/v1/organizations` 아래만 잡는다. [DashboardAuthenticator] 가 주체를 주지 않으면 익명 인증이 꺼져 있으므로
 * 인가 필터가 인증 없음으로 보고 진입점이 401 `unauthenticated` 를 쓴다. 인증된 요청이라도 매핑이 없으면
 * 컨트롤러 계층이 404 `not_found` 다. 세션을 만들지 않고 GET 만 있으므로 CSRF 를 끈다 — 쿠키 세션을 쓰게 되면
 * 다시 판정한다(ADR 0022 Follow-up).
 *
 * ## 나머지 — 닫힘
 *
 * 둘째 체인이 그 밖의 전부를 잡아 `/api/v1/healthz` 만 열고 `denyAll` 이다. 거부는 404 `not_found` 다 — API 자원이 아닌
 * 경로에 인증을 요구해 존재를 알리지 않는다. 명시적 `SecurityFilterChain` 빈이 있으면 Boot 의 기본 체인이 물러나므로,
 * 둘째 체인이 없으면 새로 얹는 경로가 인증 없이 열린다. 경로를 얹을 때는 첫 체인의 접두 아래에 두거나 여기에 명시한다.
 *
 * - **ERROR 디스패치는 통과시킨다.** 필터가 잡지 못한 예외가 `/error` 로 갔을 때 서버 오류가 경로 거부로 바뀌지
 *   않게 하는 이중 방어다(ADR 0016 과 같다). 밖에서 직접 부르는 `/error` 는 REQUEST 디스패치라 404 다.
 * - **로그아웃 필터를 끈다.** 기본 설정이 두 체인에 `/logout` 처리기를 얹어, 닫힌 체인이 그 경로에 리다이렉트로
 *   답하게 된다.
 * - 보안 응답 헤더는 기본값을 유지한다(ADR 0022 §6).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserAuthenticationProperties::class)
class SecurityConfig {

	/** 기본 런타임 구현 — 전부 거부. 사용자 인증이 서면 그 어댑터로 바꾼다(ADR 0022 §3). */
	@Bean
	fun dashboardAuthenticator(properties: UserAuthenticationProperties, jdbc: JdbcClient, clock: Clock): DashboardAuthenticator {
		if (!properties.enabled) return RejectingDashboardAuthenticator()
		val jwt = UserJwt.verifier(properties.issuer, properties.audience, properties.publicKeyFiles.filterValues { it.isNotBlank() }.mapValues { UserJwt.publicKey(Path.of(it.value)) }, clock)
		val verifier = UserAccessVerifier(jwt, UserAuthRepository(jdbc), clock)
		return DashboardAuthenticator { request ->
			val header = request.getHeader("Authorization")
			if (header == null || !header.startsWith("Bearer ")) null else try {
				val identity = verifier.verify(header.removePrefix("Bearer "))
				DashboardPrincipal(identity.tenantId, identity.memberId, if (identity.role in setOf("owner", "admin")) Role.ADMIN else Role.MEMBER)
			} catch (_: UserAuthException) { null }
		}
	}

	/**
	 * **필터를 이 메서드 안에서 만든다. 빈으로 노출하지 마라.** Boot 은 등록되지 않은 `Filter` 빈을 모든 경로에
	 * 자동 등록하므로, 빈이 되면 이 필터가 `/api/v1/healthz` 까지 잡는다.
	 */
	@Bean
	@Order(1)
	fun organizationSecurityFilterChain(
		http: HttpSecurity,
		authenticator: DashboardAuthenticator,
		errors: ErrorResponseWriter,
		properties: UserAuthenticationProperties,
	): SecurityFilterChain = http
		.securityMatcher(ORGANIZATION_PATHS, CATALOG_PATHS)
		.cors { cors -> cors.configurationSource(UrlBasedCorsConfigurationSource().apply {
			val policy = CorsConfiguration().apply {
				allowedOrigins = properties.allowedOrigins
				allowedMethods = listOf("GET", "OPTIONS")
				allowedHeaders = listOf("Authorization", "Content-Type", "X-Request-Id")
				exposedHeaders = listOf("X-Request-Id", "Retry-After", "ETag")
				allowCredentials = false
			}
			registerCorsConfiguration(ORGANIZATION_PATHS, policy)
			registerCorsConfiguration(CATALOG_PATHS, policy)
		}) }
		.csrf { it.disable() }
		.requestCache { it.disable() }
		.logout { it.disable() }
		.anonymous { it.disable() }
		.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		.exceptionHandling {
			it.authenticationEntryPoint { request, response, _ ->
				errors.write(request, response, ErrorCode.UNAUTHENTICATED)
			}
			it.accessDeniedHandler { request, response, _ -> errors.write(request, response, ErrorCode.FORBIDDEN) }
		}
		.addFilterBefore(DashboardAuthenticationFilter(authenticator, errors), AuthorizationFilter::class.java)
		.authorizeHttpRequests { it.anyRequest().authenticated() }
		.build()

	@Bean
	@Order(2)
	fun defaultDenyFilterChain(
		http: HttpSecurity,
		errors: ErrorResponseWriter,
	): SecurityFilterChain = http
		.csrf { it.disable() }
		.requestCache { it.disable() }
		.logout { it.disable() }
		.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		.exceptionHandling {
			it.authenticationEntryPoint { request, response, _ -> errors.write(request, response, ErrorCode.NOT_FOUND) }
			it.accessDeniedHandler { request, response, _ -> errors.write(request, response, ErrorCode.NOT_FOUND) }
		}
		.authorizeHttpRequests {
			// 내부 오류 디스패치는 통과시켜 원래 서버 오류 응답을 보존한다.
			it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
			it.requestMatchers(HEALTH_PATH).permitAll()
			it.anyRequest().denyAll()
		}
		.build()

	private companion object {
		const val ORGANIZATION_PATHS = "/api/v1/organizations/**"
		const val CATALOG_PATHS = "/api/v1/vendor-catalog/**"
		const val HEALTH_PATH = "/api/v1/healthz"
	}
}
