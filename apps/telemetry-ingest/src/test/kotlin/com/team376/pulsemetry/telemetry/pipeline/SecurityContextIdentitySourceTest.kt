package com.team376.pulsemetry.telemetry.pipeline

import com.team376.pulsemetry.security.TelemetryTokenAuthenticationToken
import com.team376.pulsemetry.security.TelemetryTokenPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/** 신원이 보안 컨텍스트에서 오고 헤더에서 오지 않는다 (허브 ADR 0005). */
class SecurityContextIdentitySourceTest {

	private val source = SecurityContextIdentitySource()

	@AfterEach
	fun clearContext() {
		SecurityContextHolder.clearContext()
	}

	@Test
	@DisplayName("인증된 요청은 tenant 와 installation 을 준다")
	fun anAuthenticatedRequestYieldsBothKeys() {
		val principal = TelemetryTokenPrincipal(
			tokenId = UUID.randomUUID(),
			tenantId = UUID.randomUUID(),
			installationId = UUID.randomUUID(),
			memberId = UUID.randomUUID(),
		)
		authenticate(principal)

		val identity = source.current()

		assertThat(identity?.tenantId).isEqualTo(principal.tenantId.toString())
		assertThat(identity?.installationId).isEqualTo(principal.installationId.toString())
	}

	@Test
	@DisplayName("인증이 없으면 null 이다 — 아무것도 심지 않는다")
	fun anUnauthenticatedContextYieldsNothing() {
		assertThat(source.current()).isNull()
	}

	private fun authenticate(principal: TelemetryTokenPrincipal) {
		val context = SecurityContextHolder.createEmptyContext()
		context.authentication = TelemetryTokenAuthenticationToken.authenticated(principal)
		SecurityContextHolder.setContext(context)
	}
}
