package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.dashboard.authentication.DashboardAuthenticator
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.RejectingDashboardAuthenticator
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.support.AbstractDashboardContextTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID

/**
 * **기본 런타임 구현**으로 뜬 앱 (ADR 0022 §3). 테스트 인증을 끼우지 않으므로 컨텍스트가 하나 더 뜬다 —
 * 원천 저장소 컨테이너는 공유하고, 배포될 배선을 그대로 보는 유일한 자리다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DefaultAuthenticationTest : AbstractDashboardContextTest() {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var authenticator: DashboardAuthenticator

	@Test
	@DisplayName("배포 배선의 인증 포트는 전부 거부하는 구현이다")
	fun runtimeAuthenticatorRejects() {
		assertThat(authenticator).isInstanceOf(RejectingDashboardAuthenticator::class.java)
	}

	@Test
	@DisplayName("무엇을 실어 보내도 조직 경로는 401 이다")
	fun everyOrganizationRequestIs401() {
		val http = DashboardHttp(port)
		val principal = DashboardPrincipal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN)
		val path = "/api/v1/organizations/${principal.tenantId}/analytics/overview"

		for (authorization in listOf(null, "Bearer eyJhbGciOiJub25lIn0.e30.", TestDashboardAuthenticator.header(principal))) {
			val headers = authorization?.let { mapOf("Authorization" to it) } ?: emptyMap()
			val response = http.send(path, headers = headers)

			assertThat(response.statusCode()).isEqualTo(401)
			assertThat(DashboardHttp.json(response).path("error").path("code").asString()).isEqualTo("unauthenticated")
		}
	}
}
