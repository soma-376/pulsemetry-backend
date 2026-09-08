package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationEntryPoint
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationFilter
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationProvider
import com.team376.pulsemetry.security.TelemetryTokenHasher
import com.team376.pulsemetry.security.TelemetryTokenUnavailableHandler
import com.team376.pulsemetry.telemetry.api.OtlpResponseWriter
import com.team376.pulsemetry.telemetry.collector.OtlpIngestHandler
import com.team376.pulsemetry.telemetry.collector.Signal
import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
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
 * - **인증 조회의 DB 장애는 401 이 아니라 503 + `Retry-After` 다.** 데몬은 401 과 403 을 같은
 *   칸에 두고 토큰을 폐기·재발급하므로, 장애가 그 둘로 보이면 멀쩡한 토큰이 버려진다.
 *   [TelemetryTokenAuthenticationProvider] 는 예외를 그대로 올리고, 필터에 넘긴
 *   [TelemetryTokenUnavailableHandler] 가 수집 모듈의 503 본문을 [OtlpResponseWriter] 로 쓴다 —
 *   파이프라인의 503 과 같은 모양이다(허브 §8). 예외를 컨테이너까지 흘리면 안 된다: Boot 의 오류
 *   경로 `/error` 는 OTLP 체인 밖이라 둘째 체인이 받고, 그 `denyAll` 에 걸리면 서버 오류가 경로 거부 응답으로 바뀐다.
 * - **체인은 둘이고 기본은 닫힘이다.** 첫 체인이 [Signal] 의 세 경로를 잡고, 둘째 체인이 나머지
 *   전부를 잡아 `/v1/healthz` 만 열고 그 밖은 `denyAll` 이다. 명시적 `SecurityFilterChain` 빈이
 *   있으면 Boot 의 기본 체인은 물러나므로, 둘째 체인이 없으면 새로 얹는 경로가 인증 없이 열린다.
 *   거부 응답은 허브 계약의 404 이다. 관리 엔드포인트를 얹을 때는 둘째 체인에 그 경로를 명시한다.
 * - **둘째 체인은 ERROR 디스패치만 통과시킨다.** 필터가 잡지 못한 예외가 `/error` 로 갔을 때 Boot 의
 *   500 이 그대로 나가게 하는 이중 방어다. 밖에서 직접 부르는 `/error` 는 REQUEST 디스패치라
 *   404 로 거부한다.
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
		handler: OtlpIngestHandler,
		writer: OtlpResponseWriter,
	): SecurityFilterChain = http
		.securityMatcher(*OTLP_PATHS)
		.csrf { it.disable() }
		.headers { it.disable() }
		.requestCache { it.disable() }
		.anonymous { it.disable() }
		.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		.exceptionHandling { it.authenticationEntryPoint(entryPoint) }
		.addFilterBefore(
			TelemetryTokenAuthenticationFilter(authenticationManager, entryPoint, unavailable(handler, writer)),
			AuthorizationFilter::class.java,
		)
		.authorizeHttpRequests { it.anyRequest().authenticated() }
		.build()

	/**
	 * 인증 조회 장애 → 503. 원인은 로그에만 남기고 본문에는 싣지 않는다 — DB 오류 메시지는
	 * 클라이언트의 것이 아니다.
	 */
	private fun unavailable(handler: OtlpIngestHandler, writer: OtlpResponseWriter) =
		TelemetryTokenUnavailableHandler { request, response, cause ->
			log.error("telemetry token 조회가 실패했다 — 503 으로 돌린다", cause)
			writer.write(
				response,
				handler.unavailable(request.getHeader(HttpHeaders.CONTENT_TYPE), UNAVAILABLE_MESSAGE),
			)
		}

	/**
	 * OTLP 경로 밖의 기본값 — **닫힘.** 헬스 경로만 연다. 거부 응답은 404 이다.
	 * 403 으로 내면 데몬이 경로 오류를 토큰 오류로 보고 재발급하므로 허브 계약 §8을 따른다.
	 */
	@Bean
	@Order(2)
	fun defaultDenyFilterChain(
		http: HttpSecurity,
		handler: OtlpIngestHandler,
		writer: OtlpResponseWriter,
	): SecurityFilterChain = http
		.csrf { it.disable() }
		.headers { it.disable() }
		.requestCache { it.disable() }
		.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		.exceptionHandling {
			it.authenticationEntryPoint { _, response, _ -> writer.write(response, handler.notFound()) }
			it.accessDeniedHandler { _, response, _ -> writer.write(response, handler.notFound()) }
		}
		.authorizeHttpRequests {
			// 내부 오류 디스패치는 통과시켜 Boot 의 원래 서버 오류 응답을 보존한다.
			it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
			it.requestMatchers(HEALTH_PATH).permitAll()
			it.anyRequest().denyAll()
		}
		.build()

	private companion object {
		val log = LoggerFactory.getLogger(SecurityConfig::class.java)

		const val HEALTH_PATH = "/v1/healthz"
		const val UNAVAILABLE_MESSAGE = "authentication store unavailable"

		/** 경로의 진실원은 수집 모듈이다. 여기서 문자열을 다시 적지 않는다. */
		val OTLP_PATHS: Array<String> = Signal.entries.map { it.path }.toTypedArray()
	}
}
