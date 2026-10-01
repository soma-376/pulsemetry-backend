package com.team376.pulsemetry.connector.vendor

import com.team376.pulsemetry.connector.vendor.Capability.BILLING
import com.team376.pulsemetry.connector.vendor.Capability.SEAT_LIST
import com.team376.pulsemetry.connector.vendor.Capability.SEAT_RELEASE
import com.team376.pulsemetry.connector.vendor.Capability.SEAT_RESTORE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 커넥터 설명과 조립 규칙 (ADR 0048 §8). 기대값은 벤더 문서의 "결론 — 구현 방식" 표에서 쓴다 — `커넥터` 칸만 capability 가 되고,
 * `수동`·`미제공` 칸은 커넥터가 없다. 복원이 `수동`인 Cursor Enterprise 는 복원 capability 가 없다.
 */
class ConnectorDescriptorsTest {

	/** 카탈로그의 제품·플랜 전부(V10). */
	private val catalog = listOf(
		"claude_team" to "team", "claude_team" to "enterprise", "openai_biz" to "business", "openai_biz" to "enterprise",
		"cursor" to "cursor_teams", "cursor" to "cursor_enterprise", "copilot" to "copilot_business", "copilot" to "copilot_enterprise",
		"gemini" to "gemini_standard", "gemini" to "gemini_enterprise", "other" to "other_custom",
	)

	@Test
	fun `제품·플랜마다 커넥터와 벤더가 지원하는 기능은 벤더 문서의 결론 표와 같다`() {
		val expected = mapOf(
			"claude_team" to "enterprise" to setOf(SEAT_LIST, SEAT_RELEASE, SEAT_RESTORE, BILLING),
			"cursor" to "cursor_enterprise" to setOf(SEAT_LIST, SEAT_RELEASE, BILLING),
			"copilot" to "copilot_business" to setOf(SEAT_LIST, SEAT_RELEASE, SEAT_RESTORE),
			"copilot" to "copilot_enterprise" to setOf(SEAT_LIST, SEAT_RELEASE, SEAT_RESTORE),
			"gemini" to "gemini_standard" to setOf(SEAT_LIST, SEAT_RELEASE, SEAT_RESTORE),
			"gemini" to "gemini_enterprise" to setOf(SEAT_LIST, SEAT_RELEASE, SEAT_RESTORE),
		)
		val actual = catalog.associateWith { (product, plan) -> ConnectorDescriptors.forPlan(product, plan)?.supported }.filterValues { it != null }
		assertThat(actual).isEqualTo(expected)
		assertThat(ConnectorDescriptors.forPlan("copilot", null)).isNull()
		// 이 저장소가 구현한 기능(ADR 0049): 해제는 넷, 복원은 Copilot·Gemini 뿐이고 청구는 아직이다.
		assertThat(ConnectorDescriptors.ALL.associate { it.id to it.capabilities }).isEqualTo(mapOf(
			"claude_enterprise" to setOf(SEAT_LIST, SEAT_RELEASE), "cursor_enterprise" to setOf(SEAT_LIST, SEAT_RELEASE),
			"copilot" to setOf(SEAT_LIST, SEAT_RELEASE, SEAT_RESTORE), "gemini" to setOf(SEAT_LIST, SEAT_RELEASE, SEAT_RESTORE)))
		assertThatThrownBy { ConnectorDescriptor("x", "copilot", setOf("copilot_business"), AccountKind.GITHUB_LOGIN, emptyList(), setOf(SEAT_LIST), setOf(SEAT_LIST, BILLING)) }
			.describedAs("문서 근거가 없는 기능은 구현으로 선언하지 못한다").isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun `계정 종류는 Copilot 만 GitHub 로그인이고 커넥터가 없는 제품은 이메일이다`() {
		assertThat(listOf("claude_team", "openai_biz", "cursor", "copilot", "gemini", "other").map { ConnectorDescriptors.accountKind(it) })
			.containsExactly(AccountKind.EMAIL, AccountKind.EMAIL, AccountKind.EMAIL, AccountKind.GITHUB_LOGIN, AccountKind.EMAIL, AccountKind.EMAIL)
		assertThat(ConnectorDescriptors.COPILOT.settingKeys).containsExactly("organization")
		assertThat(ConnectorDescriptors.GEMINI.settingKeys).containsExactly("billingAccount", "order", "project")
	}

	@Test
	fun `계정 키는 소문자로 정규화하고 형식이 맞지 않으면 받지 않는다`() {
		assertThat(AccountKind.EMAIL.normalize("  Dana@Example.TEST ")).isEqualTo("dana@example.test")
		assertThat(AccountKind.EMAIL.normalize("octocat")).isNull()
		assertThat(AccountKind.GITHUB_LOGIN.normalize("OctoCat")).isEqualTo("octocat")
		assertThat(AccountKind.GITHUB_LOGIN.normalize("-octo")).isNull()
		assertThat(AccountKind.GITHUB_LOGIN.normalize("dana@example.test")).isNull()
		assertThat(AccountKind.GITHUB_LOGIN.normalize("a".repeat(40))).isNull()
	}

	private class Fake(override val descriptor: ConnectorDescriptor, withRelease: Boolean, withRestore: Boolean, withBilling: Boolean) : SeatConnector {
		override fun verify(target: ConnectionTarget) = Unit
		override fun listSeats(target: ConnectionTarget): List<VendorSeat> = emptyList()
		override val release: SeatRelease? = if (withRelease) SeatRelease { _, _ -> ControlResult(ControlStatus.COMPLETED) } else null
		override val restore: SeatRestore? = if (withRestore) SeatRestore { _, _ -> ControlResult(ControlStatus.AWAITING_ACCEPTANCE) } else null
		override val billing: BillingReader? = if (withBilling) BillingReader { _, _, _ -> emptyList() } else null
	}

	@Test
	fun `지원하지 않는 기능은 구현이 갖지 않는 것으로 드러나고 설명과 다르면 조립이 실패한다`() {
		// 해제·청구까지 구현했다고 선언한 설명(복원은 벤더 문서에 근거가 없다).
		val controlled = ConnectorDescriptors.CURSOR_ENTERPRISE.copy(capabilities = setOf(SEAT_LIST, SEAT_RELEASE, BILLING))
		val cursor = Fake(controlled, withRelease = true, withRestore = false, withBilling = true)
		val connectors = SeatConnectors(listOf(cursor))
		assertThat(connectors.forPlan("cursor", "cursor_enterprise")?.restore).isNull()
		assertThat(connectors.forPlan("cursor", "cursor_teams")).isNull()
		assertThat(connectors.byId("cursor_enterprise")).isSameAs(cursor)

		assertThatThrownBy { SeatConnectors(listOf(Fake(controlled, withRelease = true, withRestore = true, withBilling = true))) }
			.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("cursor_enterprise")
		assertThatThrownBy { SeatConnectors(listOf(Fake(ConnectorDescriptors.COPILOT, withRelease = false, withRestore = true, withBilling = false))) }
			.describedAs("설명은 해제·복원인데 복원만 구현").isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { SeatConnectors(listOf(cursor, Fake(controlled, withRelease = true, withRestore = false, withBilling = true))) }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThat(SeatConnectors(listOf(Fake(ConnectorDescriptors.COPILOT, withRelease = true, withRestore = true, withBilling = false))).byId("copilot")).isNotNull()
	}

	@Test
	fun `자격증명은 문자열 표현에 값을 내지 않는다`() {
		val secret = "fake-vendor-credential-" + "x".repeat(24)
		val target = ConnectionTarget(mapOf("organization" to "octo-org"), ConnectorCredential(secret))
		assertThat(target.toString()).doesNotContain(secret).contains("octo-org")
		assertThat(target.credential.reveal()).isEqualTo(secret)
		assertThat(ConnectorFailure(ConnectorFailure.Kind.INVALID_CREDENTIALS).message).isEqualTo("invalid_credentials")
	}

	@Test
	fun `요청 성공과 완료를 나눈다 — 예정 결과만 효력일을 갖고 해제 예정 좌석만 예정일을 갖는다`() {
		// 벤더가 날짜를 주지 않는 예정(Copilot 취소 응답)은 효력일을 모른다 — 다음 동기화가 채운다(ADR 0049).
		assertThat(ControlResult(ControlStatus.SCHEDULED).effectiveOn).isNull()
		assertThatThrownBy { ControlResult(ControlStatus.AWAITING_ACCEPTANCE, java.time.LocalDate.parse("2026-10-31")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { ControlResult(ControlStatus.COMPLETED, java.time.LocalDate.parse("2026-10-31")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { VendorSeat("octocat", releaseEffectiveOn = java.time.LocalDate.parse("2026-10-31")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThat(VendorSeat("octocat", state = VendorSeatState.PENDING_RELEASE, releaseEffectiveOn = java.time.LocalDate.parse("2026-10-31")).lastActivityAt).isNull()
	}
}
