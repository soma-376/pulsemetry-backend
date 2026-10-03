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
 * GitHub Copilot·Gemini Code Assist 는 연동 대상이 아니다(ADR 0054) — 카탈로그 플랜은 있어도 커넥터가 없다.
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
		)
		val actual = catalog.associateWith { (product, plan) -> ConnectorDescriptors.forPlan(product, plan)?.supported }.filterValues { it != null }
		assertThat(actual).isEqualTo(expected)
		assertThat(ConnectorDescriptors.forPlan("cursor", null)).isNull()
		// 연동 대상이 아닌 제품의 카탈로그 플랜에는 커넥터가 없다(ADR 0054).
		for (plan in listOf("copilot" to "copilot_business", "copilot" to "copilot_enterprise", "gemini" to "gemini_standard", "gemini" to "gemini_enterprise")) {
			assertThat(ConnectorDescriptors.forPlan(plan.first, plan.second)).describedAs("%s", plan).isNull()
		}
		assertThat(ConnectorDescriptors.ALL.map { it.id }).containsExactly("claude_enterprise", "cursor_enterprise")
		// 이 저장소가 구현한 기능(ADR 0049·0050·0054): 해제·청구는 둘 다, 복원은 구현한 커넥터가 없다.
		assertThat(ConnectorDescriptors.ALL.associate { it.id to it.capabilities }).isEqualTo(mapOf(
			"claude_enterprise" to setOf(SEAT_LIST, SEAT_RELEASE, BILLING), "cursor_enterprise" to setOf(SEAT_LIST, SEAT_RELEASE, BILLING)))
		assertThatThrownBy { ConnectorDescriptor("x", "cursor", setOf("cursor_teams"), AccountKind.EMAIL, emptyList(), setOf(SEAT_LIST), setOf(SEAT_LIST, BILLING)) }
			.describedAs("문서 근거가 없는 기능은 구현으로 선언하지 못한다").isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun `계정 종류는 모든 제품이 이메일이고 GitHub 로그인 계정 종류는 없다`() {
		assertThat(listOf("claude_team", "openai_biz", "cursor", "copilot", "gemini", "other").map { ConnectorDescriptors.accountKind(it) })
			.containsOnly(AccountKind.EMAIL)
		// API 가 만들거나 받는 계정 종류는 email 하나다(ADR 0054). 남은 커넥터는 비밀 아닌 설정이 없다.
		assertThat(AccountKind.entries.map { it.wire }).containsExactly("email")
		assertThat(ConnectorDescriptors.ALL.flatMap { it.settingKeys }).isEmpty()
	}

	@Test
	fun `계정 키는 소문자로 정규화하고 형식이 맞지 않으면 받지 않는다`() {
		assertThat(AccountKind.EMAIL.normalize("  Dana@Example.TEST ")).isEqualTo("dana@example.test")
		assertThat(AccountKind.EMAIL.normalize("octocat")).isNull()
		assertThat(AccountKind.EMAIL.normalize("dana@example")).isNull()
		assertThat(AccountKind.EMAIL.normalize("a".repeat(309) + "@example.test")).isNull()
	}

	private class Fake(override val descriptor: ConnectorDescriptor, withRelease: Boolean, withRestore: Boolean, withBilling: Boolean) : SeatConnector {
		override fun verify(target: ConnectionTarget) = Unit
		override fun listSeats(target: ConnectionTarget): List<VendorSeat> = emptyList()
		override val release: SeatRelease? = if (withRelease) SeatRelease { _, _ -> ControlResult(ControlStatus.COMPLETED) } else null
		override val restore: SeatRestore? = if (withRestore) SeatRestore { _, _ -> ControlResult(ControlStatus.AWAITING_ACCEPTANCE) } else null
		override val billing: BillingReader? = if (withBilling) BillingReader { _, now, start -> BilledAmount(start, now, java.math.BigDecimal.ZERO, "USD", BilledKind.USAGE_SPEND, false) } else null
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
		// 복원은 벤더 문서가 근거를 준 Claude Enterprise 재초대로 본다 — 구현한 커넥터는 아직 없지만 포트와 조립 규칙은 같다.
		val restoring = ConnectorDescriptors.CLAUDE_ENTERPRISE.copy(capabilities = setOf(SEAT_LIST, SEAT_RELEASE, SEAT_RESTORE))
		assertThatThrownBy { SeatConnectors(listOf(Fake(restoring, withRelease = false, withRestore = true, withBilling = false))) }
			.describedAs("설명은 해제·복원인데 복원만 구현").isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { SeatConnectors(listOf(cursor, Fake(controlled, withRelease = true, withRestore = false, withBilling = true))) }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThat(SeatConnectors(listOf(Fake(restoring, withRelease = true, withRestore = true, withBilling = false))).byId("claude_enterprise")).isNotNull()
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
		// 벤더가 날짜를 주지 않는 예정은 효력일을 모른다 — 다음 동기화가 채운다(ADR 0049).
		assertThat(ControlResult(ControlStatus.SCHEDULED).effectiveOn).isNull()
		assertThatThrownBy { ControlResult(ControlStatus.AWAITING_ACCEPTANCE, java.time.LocalDate.parse("2026-10-31")) }.isInstanceOf(IllegalArgumentException::class.java)
		// 청구 누계는 USD 만, 비어 있지 않은 구간만(ADR 0050 — 환율 원천이 없다).
		val start = java.time.Instant.parse("2026-09-30T15:00:00Z")
		assertThatThrownBy { BilledAmount(start, start.plusSeconds(60), java.math.BigDecimal.ONE, "EUR", BilledKind.USAGE_COST, false) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { BilledAmount(start, start, java.math.BigDecimal.ONE, "USD", BilledKind.USAGE_COST, false) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { ControlResult(ControlStatus.COMPLETED, java.time.LocalDate.parse("2026-10-31")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { VendorSeat("octocat", releaseEffectiveOn = java.time.LocalDate.parse("2026-10-31")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThat(VendorSeat("octocat", state = VendorSeatState.PENDING_RELEASE, releaseEffectiveOn = java.time.LocalDate.parse("2026-10-31")).lastActivityAt).isNull()
	}
}
