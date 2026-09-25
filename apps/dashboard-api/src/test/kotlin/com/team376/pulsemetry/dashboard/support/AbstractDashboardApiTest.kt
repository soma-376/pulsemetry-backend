package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.dashboard.organization.OrganizationReader
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean

/**
 * 대시보드 앱 통합 테스트의 기반. **모든 애너테이션이 여기 있다** — 하위 클래스가 `@Import` 나 프로퍼티를 더하면
 * 컨텍스트 캐시 키가 갈린다.
 *
 * 인증 포트는 [TestDashboardAuthenticator] 다. 기본 런타임 구현의 동작은 `DefaultAuthenticationTest` 가 따로 본다.
 * 공통 요청 해석·인가는 테스트 전용 [QueryProbeController] 로 HTTP 끝까지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestAuthenticationConfig::class, QueryProbeController::class)
abstract class AbstractDashboardApiTest : AbstractDashboardContextTest() {

	@LocalServerPort
	private var port: Int = 0

	protected val http: DashboardHttp by lazy { DashboardHttp(port) }

	/** RDS 원천 장애를 흉내 낸다. 테스트마다 원복된다. */
	@MockitoSpyBean
	protected lateinit var organizations: OrganizationReader
}
