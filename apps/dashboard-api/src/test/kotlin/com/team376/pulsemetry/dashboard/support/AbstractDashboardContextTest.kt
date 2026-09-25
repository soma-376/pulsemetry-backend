package com.team376.pulsemetry.dashboard.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 앱 컨텍스트를 띄우는 테스트의 공통 뿌리 — 원천 저장소 연결만 채운다. 컨텍스트 구성(`@SpringBootTest`·`@Import`)은
 * 하위의 두 기반([AbstractDashboardApiTest], `DefaultAuthenticationTest`)이 각자 갖는다. 컨텍스트는 그 둘뿐이다.
 */
abstract class AbstractDashboardContextTest {

	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun sourceStores(registry: DynamicPropertyRegistry) {
			DashboardTestStores.register(registry)
		}
	}
}
