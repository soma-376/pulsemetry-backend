package com.team376.pulsemetry.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Bearer 문법과 **단일 401** 의 특성화 테스트.
 *
 * 이식 원본: `ai-telemetry-pipeline` `apps/auth-proxy/tests/auth/auth.middleware.test.ts` (PROJ-100).
 */
class TelemetryTokenAuthenticationFilterTest {

	private val principal = TelemetryTokenPrincipal(
		tokenId = UUID.randomUUID(),
		tenantId = UUID.randomUUID(),
		installationId = UUID.randomUUID(),
		memberId = UUID.randomUUID(),
	)

	@AfterEach
	fun clearContext() {
		SecurityContextHolder.clearContext()
	}

	@ParameterizedTest(name = "[{0}]")
	@ValueSource(
		strings = [
			"Bearer ptt_token",
			// 스킴의 대소문자를 가리지 않는다.
			"bearer ptt_token",
			"BeArEr ptt_token",
			// 사이 공백은 여러 개여도 된다.
			"Bearer    ptt_token",
			"Bearer\tptt_token",
		],
	)
	@DisplayName("Bearer 문법을 통과하면 토큰만 떼어 인증에 넘긴다")
	fun acceptsBearerGrammar(header: String) {
		val manager = RecordingAuthenticationManager(succeedWith = principal)
		val chain = MockFilterChain()

		val response = invoke(filter(manager), header, chain)

		assertThat(manager.received).isEqualTo("ptt_token")
		assertThat(response.status).isEqualTo(200)
		// 통과한 요청만 다음 단계에 닿는다.
		assertThat(chain.request).isNotNull()
		assertThat(SecurityContextHolder.getContext().authentication?.principal).isEqualTo(principal)
	}

	@ParameterizedTest(name = "[{0}]")
	@ValueSource(
		strings = [
			// 스킴이 없다.
			"ptt_token",
			// 토큰이 없다.
			"Bearer",
			"Bearer ",
			// 다른 스킴.
			"Basic ptt_token",
			// 토큰에 공백이 섞였다 — 양끝이 앵커라 통째로 형식 불일치다.
			"Bearer ptt_token extra",
		],
	)
	@DisplayName("문법에 맞지 않으면 조회까지 가지 않는다")
	fun rejectsMalformedBearerWithoutLookup(header: String) {
		val manager = RecordingAuthenticationManager(succeedWith = principal)
		val chain = MockFilterChain()

		val response = invoke(filter(manager), header, chain)

		assertThat(response.status).isEqualTo(401)
		// 파싱에서 끊었으므로 DB 를 건드리지 않는다.
		assertThat(manager.received).isNull()
		assertThat(chain.request).isNull()
	}

	@Test
	@DisplayName("헤더가 없는 것과 빈 문자열은 같게 다룬다")
	fun missingAndEmptyHeaderAreTheSame() {
		listOf(null, "").forEach { header ->
			val manager = RecordingAuthenticationManager(succeedWith = principal)

			val response = invoke(filter(manager), header, MockFilterChain())

			assertThat(response.status).isEqualTo(401)
			assertThat(manager.received).isNull()
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(TelemetryTokenRejectionReason::class)
	@DisplayName("거부 사유 열한 가지가 전부 같은 401 로 접힌다 — 사유를 노출하지 않는다")
	fun allReasonsCollapseIntoOneResponse(reason: TelemetryTokenRejectionReason) {
		// 파싱 단계 사유 둘은 헤더로, 나머지 아홉은 조회 단계에서 만든다.
		val header = when (reason) {
			TelemetryTokenRejectionReason.MISSING_BEARER -> null
			TelemetryTokenRejectionReason.MALFORMED_BEARER -> "Basic nope"
			else -> "Bearer ptt_token"
		}
		val manager = RecordingAuthenticationManager(failWith = reason)
		val chain = MockFilterChain()

		val response = invoke(filter(manager), header, chain)

		assertThat(response.status).isEqualTo(401)
		assertThat(response.contentAsString).isEqualTo(TelemetryTokenAuthenticationEntryPoint.BODY)
		assertThat(response.contentType).startsWith("application/json")
		// 사유가 응답 어디에도 새지 않는다 — 헤더까지 포함해서.
		assertThat(response.contentAsString).doesNotContain(reason.code)
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull()
		// 거부된 요청은 다음 단계에 닿지 않는다.
		assertThat(chain.request).isNull()
		assertThat(SecurityContextHolder.getContext().authentication).isNull()
	}

	// ------------------------------------------------------------------ 조회 장애

	@Test
	@DisplayName("조회 장애는 기본적으로 그대로 올라간다 — 401 로 접지 않고, 다음 단계에도 닿지 않는다")
	fun aLookupFailureIsRethrownByDefault() {
		val manager = RecordingAuthenticationManager(crashWith = IllegalStateException("db down"))
		val chain = MockFilterChain()

		assertThatThrownBy { invoke(filter(manager), "Bearer ptt_token", chain) }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessage("db down")

		assertThat(chain.request).isNull()
		assertThat(SecurityContextHolder.getContext().authentication).isNull()
	}

	@Test
	@DisplayName("앱이 핸들러를 넘기면 조회 장애의 응답은 그 핸들러가 쓴다")
	fun aLookupFailureGoesToTheHandlerWhenWired() {
		val cause = IllegalStateException("db down")
		val manager = RecordingAuthenticationManager(crashWith = cause)
		val chain = MockFilterChain()
		var received: RuntimeException? = null
		val handler = TelemetryTokenUnavailableHandler { _, response, e ->
			received = e
			response.status = 503
		}

		val response = invoke(
			TelemetryTokenAuthenticationFilter(manager, TelemetryTokenAuthenticationEntryPoint(), handler),
			"Bearer ptt_token",
			chain,
		)

		assertThat(response.status).isEqualTo(503)
		assertThat(received).isSameAs(cause)
		assertThat(chain.request).isNull()
	}

	private fun filter(manager: AuthenticationManager) =
		TelemetryTokenAuthenticationFilter(manager, TelemetryTokenAuthenticationEntryPoint())

	private fun invoke(
		filter: TelemetryTokenAuthenticationFilter,
		header: String?,
		chain: MockFilterChain,
	): MockHttpServletResponse {
		val request = MockHttpServletRequest("POST", "/v1/logs")
		header?.let { request.addHeader(HttpHeaders.AUTHORIZATION, it) }
		val response = MockHttpServletResponse()

		filter.doFilter(request, response, chain)
		return response
	}

	/** 필터가 무엇을 넘겼는지 기록하는 스텁. 조회 단계는 [TelemetryTokenAuthenticationProviderTest] 가 본다. */
	private class RecordingAuthenticationManager(
		private val succeedWith: TelemetryTokenPrincipal? = null,
		private val failWith: TelemetryTokenRejectionReason? = null,
		/** 토큰 판정이 아니라 조회 자체의 실패 — Provider 가 DB 예외를 그대로 올리는 경우다. */
		private val crashWith: RuntimeException? = null,
	) : AuthenticationManager {

		var received: String? = null
			private set

		override fun authenticate(authentication: Authentication): Authentication {
			received = authentication.credentials as String
			crashWith?.let { throw it }
			failWith?.let { throw TelemetryTokenAuthenticationException(it) }
			return TelemetryTokenAuthenticationToken.authenticated(succeedWith!!)
		}
	}
}
