package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationEntryPoint
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationFilter
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationProvider
import com.team376.pulsemetry.security.TelemetryTokenHasher
import com.team376.pulsemetry.telemetry.collector.Signal
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter

/**
 * OTLP 경로의 인증을 세운다 — 이 저장소의 **첫 필터 체인**이다.
 *
 * `:apps:enrollment-api` 는 해시 함수 하나 때문에 `:libs:security` 에 의존할 뿐 체인을 켜지
 * 않는다. 라이브러리가 스테레오타입을 달지 않으므로(ADR 0011) 필터·프로바이더·진입점·해셔를
 * 여기서 손으로 엮는다.
 *
 * ## 인증이 파이프라인의 가장 앞이다
 *
 * 통과한 요청만 수집 단계에 닿아야 한다 — 폐기된 토큰이나 정지된 tenant 의 데이터가 외부
 * 저장소에 적재되면 안 되기 때문이다(허브 ADR 0005).
 *
 * ## 지켜야 하는 것
 *
 * - **거부 사유 열한 가지가 하나의 401 본문으로 접힌다.** 본문은
 *   [TelemetryTokenAuthenticationEntryPoint] 의 상수이고 `WWW-Authenticate` 는 붙지 않는다.
 * - **DB 오류는 401 이 아니라 500 이다.** [TelemetryTokenAuthenticationProvider] 가 예외를
 *   그대로 올린다. 401 로 접으면 장애가 "토큰이 틀렸다"로 보여 데몬의 재발급 루프가 헛돈다.
 * - **체인은 둘이고 기본은 닫힘이다.** 첫 체인이 [Signal] 의 세 경로를 잡고, 둘째 체인이 나머지
 *   전부를 잡아 `/v1/healthz` 만 열고 그 밖은 `denyAll` 이다. 명시적 `SecurityFilterChain` 빈이
 *   있으면 Boot 의 기본 체인은 물러나므로, 둘째 체인이 없으면 새로 얹는 경로가 인증 없이 열린다.
 *   관리 엔드포인트를 얹을 때는 둘째 체인에 그 경로를 명시한다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	fun telemetryTokenHasher(properties: TelemetryIngestProperties): TelemetryTokenHasher =
		TelemetryTokenHasher(properties.tokenHashSecret)

	@Bean
	fun telemetryTokenAuthenticationProvider(
		hasher: TelemetryTokenHasher,
		telemetryTokens: TelemetryTokenRepository,
	): TelemetryTokenAuthenticationProvider =
		TelemetryTokenAuthenticationProvider(hasher, telemetryTokens)

	@Bean
	fun telemetryAuthenticationManager(
		provider: TelemetryTokenAuthenticationProvider,
	): AuthenticationManager = ProviderManager(provider)

	@Bean
	fun telemetryTokenAuthenticationEntryPoint(): AuthenticationEntryPoint =
		TelemetryTokenAuthenticationEntryPoint()

	/**
	 * **필터를 이 메서드 안에서 만든다. 빈으로 노출하지 마라.**
	 *
	 * Boot 은 컨테이너에 등록되지 않은 `Filter` 빈을 발견하면 서블릿 필터로 **모든 경로**에 자동
	 * 등록한다. 그러면 이 필터가 `/v1/healthz` 까지 잡아 401 을 낸다 — ADR 0011 이 막으려던
	 * 사고를 손으로 재현하는 셈이다.
	 *
	 * 헤더 기본값을 끈 것은 응답을 계약 그대로 두기 위해서다. OTLP 응답의 본문·상태·
	 * `Content-Type` 이 바이트 계약이고, 구 auth-proxy 도 보안 헤더를 붙이지 않았다.
	 */
	@Bean
	@Order(1)
	fun otlpSecurityFilterChain(
		http: HttpSecurity,
		authenticationManager: AuthenticationManager,
		entryPoint: AuthenticationEntryPoint,
	): SecurityFilterChain = http
		.securityMatcher(*OTLP_PATHS)
		.csrf { it.disable() }
		.headers { it.disable() }
		.requestCache { it.disable() }
		.anonymous { it.disable() }
		.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		.exceptionHandling { it.authenticationEntryPoint(entryPoint) }
		.addFilterBefore(
			TelemetryTokenAuthenticationFilter(authenticationManager, entryPoint),
			AuthorizationFilter::class.java,
		)
		.authorizeHttpRequests { it.anyRequest().authenticated() }
		.build()

	/**
	 * OTLP 경로 밖의 기본값 — **닫힘.** 헬스 경로만 연다. 매핑되지 않은 경로는 404 가 아니라 403 이다.
	 * 데몬은 세 경로만 부르므로 처분에 영향이 없다.
	 */
	@Bean
	@Order(2)
	fun defaultDenyFilterChain(http: HttpSecurity): SecurityFilterChain = http
		.csrf { it.disable() }
		.headers { it.disable() }
		.requestCache { it.disable() }
		.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		.authorizeHttpRequests {
			it.requestMatchers(HEALTH_PATH).permitAll()
			it.anyRequest().denyAll()
		}
		.build()

	private companion object {
		const val HEALTH_PATH = "/v1/healthz"

		/** 경로의 진실원은 수집 모듈이다. 여기서 문자열을 다시 적지 않는다. */
		val OTLP_PATHS: Array<String> = Signal.entries.map { it.path }.toTypedArray()
	}
}
