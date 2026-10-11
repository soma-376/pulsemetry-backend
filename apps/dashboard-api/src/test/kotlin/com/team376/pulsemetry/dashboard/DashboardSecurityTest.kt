package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.http.HttpResponse
import java.util.UUID

/**
 * 필터 체인 둘과 오류 본문 (ADR 0022 §3·§5·§6). 기대값은 ADR 의 문장에서 쓴다.
 */
class DashboardSecurityTest : AbstractDashboardApiTest() {

	private val admin = DashboardPrincipal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN)
	private val authenticated = mapOf("Authorization" to TestDashboardAuthenticator.header(admin))

	@Test
	@DisplayName("생존 확인은 인증 없이 200 이다")
	fun healthzIsOpen() {
		val response = http.send("/api/v1/healthz")

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(DashboardHttp.json(response).path("status").asString()).isEqualTo("ok")
		assertThat(response.headers().firstValue("X-Request-Id")).isPresent
	}

	@Test
	@DisplayName("옛 /v1/healthz 경로는 열지 않는다")
	fun legacyHealthzIsClosed() {
		assertThat(http.send("/v1/healthz").statusCode()).isEqualTo(404)
	}

	@Test
	@DisplayName("조직 경로는 인증이 없으면 401 이고 본문은 {error:{code,message,fieldErrors},requestId} 다")
	fun organizationPathWithoutAuthenticationIs401() {
		val response = http.send("/api/v1/organizations/${UUID.randomUUID()}/analytics/overview")

		assertThat(response.statusCode()).isEqualTo(401)
		assertErrorBody(response, "unauthenticated")
		// 인증 방식이 정해지지 않았으므로 도전 헤더를 싣지 않는다 (ADR 0022 §3).
		assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty
	}

	@Test
	@DisplayName("조직 경로 접두 자체도 인증을 요구한다")
	fun organizationRootRequiresAuthentication() {
		assertThat(http.send("/api/v1/organizations").statusCode()).isEqualTo(401)
		assertThat(http.send("/api/v1/organizations/").statusCode()).isEqualTo(401)
	}

	@Test
	@DisplayName("인증된 요청이라도 매핑이 없는 조직 경로는 404 not_found 다")
	fun authenticatedUnmappedPathIs404() {
		val response = http.send("/api/v1/organizations/${admin.tenantId}/analytics/overview", headers = authenticated)

		assertThat(response.statusCode()).isEqualTo(404)
		assertErrorBody(response, "not_found")
	}

	@Test
	@DisplayName("인증 포트의 예외는 401 이 아니라 503 unavailable 이고 Retry-After 를 싣는다")
	fun authenticatorFailureIs503() {
		val response = http.send(
			"/api/v1/organizations/${UUID.randomUUID()}/analytics/overview",
			headers = mapOf("Authorization" to TestDashboardAuthenticator.unavailableHeader()),
		)

		assertThat(response.statusCode()).isEqualTo(503)
		assertErrorBody(response, "unavailable")
		// 테스트 JVM 의 pulsemetry.dashboard.retry-after = 2s (build.gradle.kts).
		assertThat(response.headers().firstValue("Retry-After")).hasValue("2")
	}

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource(
		"GET, /",
		"GET, /api/v1/teams",
		"GET, /api/v1/organization",
		"GET, /api/v1/enroll",
		"GET, /api/v1/healthz/extra",
		"GET, /actuator/health",
		"GET, /error",
		"POST, /logout",
		"GET, /logout",
		"GET, /login",
	)
	@DisplayName("계약 밖 경로는 404 not_found 로 거부한다")
	fun pathsOutsideTheContractAre404(method: String, path: String) {
		val response = http.send(path, method = method)

		assertThat(response.statusCode()).isEqualTo(404)
		assertErrorBody(response, "not_found")
	}

	@Test
	@DisplayName("계약 밖 경로는 인증이 있어도 404 다 — 인증 포트를 거치지 않는다")
	fun pathsOutsideTheContractIgnoreAuthentication() {
		assertThat(http.send("/api/v1/teams", headers = authenticated).statusCode()).isEqualTo(404)
		val unavailable = mapOf("Authorization" to TestDashboardAuthenticator.unavailableHeader())
		assertThat(http.send("/api/v1/teams", headers = unavailable).statusCode()).isEqualTo(404)
	}

	@Test
	@DisplayName("열린 경로에 다른 메서드를 쓰면 405 method_not_allowed 다")
	fun wrongMethodOnHealthzIs405() {
		val response = http.send("/api/v1/healthz", method = "POST")

		assertThat(response.statusCode()).isEqualTo(405)
		assertErrorBody(response, "method_not_allowed")
	}

	@Test
	@DisplayName("형식이 맞는 X-Request-Id 는 그대로 쓰고 본문의 requestId 와 같다")
	fun conformingRequestIdIsEchoed() {
		val response = http.send("/api/v1/organizations/x", headers = mapOf("X-Request-Id" to "gw-1.a_B"))

		assertThat(response.headers().firstValue("X-Request-Id")).hasValue("gw-1.a_B")
		assertThat(DashboardHttp.json(response).path("requestId").asString()).isEqualTo("gw-1.a_B")
	}

	@ParameterizedTest
	@ValueSource(strings = ["has space", "semi;colon", "slash/value"])
	@DisplayName("형식이 어긋난 X-Request-Id 는 서버 UUID 로 바꾼다")
	fun nonConformingRequestIdIsReplaced(incoming: String) {
		val response = http.send("/api/v1/healthz", headers = mapOf("X-Request-Id" to incoming))

		val id = response.headers().firstValue("X-Request-Id").orElseThrow()
		assertThat(id).isNotEqualTo(incoming)
		assertThat(UUID.fromString(id).toString()).isEqualTo(id)
	}

	@Test
	@DisplayName("128자를 넘는 X-Request-Id 는 받지 않는다")
	fun overlongRequestIdIsReplaced() {
		val incoming = "a".repeat(129)
		val response = http.send("/api/v1/healthz", headers = mapOf("X-Request-Id" to incoming))

		assertThat(response.headers().firstValue("X-Request-Id")).isNotEqualTo(incoming)
		val exact = "b".repeat(128)
		assertThat(http.send("/api/v1/healthz", headers = mapOf("X-Request-Id" to exact)).headers().firstValue("X-Request-Id"))
			.hasValue(exact)
	}

	@Test
	@DisplayName("보안 응답 헤더 기본값을 유지한다")
	fun securityHeadersAreKept() {
		val response = http.send("/api/v1/organizations/x")

		assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
		assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
	}

	@Test
	@DisplayName("세션을 만들지 않는다")
	fun noSessionCookie() {
		val response = http.send("/api/v1/organizations/${admin.tenantId}/x", headers = authenticated)

		assertThat(response.headers().allValues("Set-Cookie")).isEmpty()
	}

	private fun assertErrorBody(response: HttpResponse<String>, code: String) {
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json")
		val body = DashboardHttp.json(response)
		assertThat(body.propertyNames().asSequence().toSet()).containsExactlyInAnyOrder("error", "requestId")
		val error = body.path("error")
		assertThat(error.propertyNames().asSequence().toSet()).containsExactlyInAnyOrder("code", "message", "fieldErrors")
		assertThat(error.path("code").asString()).isEqualTo(code)
		assertThat(error.path("message").asString()).isNotBlank()
		assertThat(error.path("fieldErrors").isArray).isTrue()
		assertThat(error.path("fieldErrors").size()).isZero()
		assertThat(body.path("requestId").asString()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow())
	}
}
