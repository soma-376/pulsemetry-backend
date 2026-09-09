package com.team376.pulsemetry.security

import com.team376.pulsemetry.security.support.FakeAuthRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * 판정 체인의 특성화 테스트.
 *
 * 이식 원본의 케이스 표를 그대로 옮긴 것이다 —
 * `ai-telemetry-pipeline` `apps/auth-proxy/tests/auth/credential.repository.test.ts` (PROJ-100).
 * 원본이 언어 중립 fixture 가 아니라 vitest 인라인 표라서 파일로 공유하지 않고 전사했다.
 * auth-proxy 는 폐기 예정이므로 이 표의 두 번째 소비자는 생기지 않는다.
 *
 * **여기서 검사하는 것은 순서와 극성이지 스키마가 아니다.** DB 왕복은
 * [TelemetryTokenAuthenticationProviderTest] 가 본다.
 */
class TelemetryTokenDecisionTest {

	@Test
	@DisplayName("정상 행은 거부하지 않는다")
	fun healthyRowPasses() {
		assertThat(TelemetryTokenDecision.reject(FakeAuthRow())).isNull()
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("singleBrokenField")
	@DisplayName("한 필드만 깨진 행은 그 사유로 거부된다")
	fun rejectsSingleBrokenField(reason: TelemetryTokenRejectionReason, row: FakeAuthRow) {
		assertThat(TelemetryTokenDecision.reject(row)).isEqualTo(reason)
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("precedencePairs")
	@DisplayName("두 사유가 겹치면 앞선 사유가 이긴다 — 순서가 곧 사양이다")
	fun earlierReasonWins(reason: TelemetryTokenRejectionReason, row: FakeAuthRow) {
		assertThat(TelemetryTokenDecision.reject(row)).isEqualTo(reason)
	}

	@Test
	@DisplayName("전부 깨진 행은 가장 앞선 token_revoked 다")
	fun allBrokenYieldsFirstReason() {
		val row = FakeAuthRow(
			tokenRevoked = true,
			installationRevoked = true,
			installationStatus = "revoked",
			memberStatus = "suspended",
			tenantStatus = "terminated",
			tenantDeleted = true,
		)

		assertThat(TelemetryTokenDecision.reject(row))
			.isEqualTo(TelemetryTokenRejectionReason.TOKEN_REVOKED)
	}

	@Test
	@DisplayName("installation 은 허용 목록이다 — 대소문자가 다르면 거부다")
	fun installationStatusIsAnAllowList() {
		// 원본이 고정한 케이스다. 이 저장소의 스키마는 native enum 이라 DB 로는 이 값을 만들 수 없지만,
		// 판정이 문자열 동등 비교라는 사실 자체는 지켜져야 한다 — 대소문자를 관용하면
		// 폐기된 installation 이 통과할 여지가 생긴다.
		val row = FakeAuthRow(installationStatus = "ACTIVE")

		assertThat(TelemetryTokenDecision.reject(row))
			.isEqualTo(TelemetryTokenRejectionReason.INSTALLATION_INACTIVE)
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("unknownStatusesThatPass")
	@DisplayName("member·tenant 는 차단 목록이다 — 모르는 상태는 통과한다")
	fun memberAndTenantAreDenyLists(label: String, row: FakeAuthRow) {
		// 극성이 installation 과 반대라는 것이 이식에서 가장 놓치기 쉬운 지점이다.
		// 허용 목록으로 바꿔 쓰면 상태 값이 추가되는 순간 전 사용자가 401 이 된다.
		assertThat(TelemetryTokenDecision.reject(row)).isNull()
	}

	private companion object {

		@JvmStatic
		fun singleBrokenField(): Stream<Arguments> = Stream.of(
			Arguments.of(TelemetryTokenRejectionReason.TOKEN_REVOKED, FakeAuthRow(tokenRevoked = true)),
			Arguments.of(
				TelemetryTokenRejectionReason.INSTALLATION_REVOKED,
				FakeAuthRow(installationRevoked = true),
			),
			Arguments.of(
				TelemetryTokenRejectionReason.INSTALLATION_INACTIVE,
				FakeAuthRow(installationStatus = "revoked"),
			),
			Arguments.of(
				TelemetryTokenRejectionReason.MEMBER_SUSPENDED,
				FakeAuthRow(memberStatus = "suspended"),
			),
			Arguments.of(TelemetryTokenRejectionReason.MEMBER_INVITED, FakeAuthRow(memberStatus = "invited")),
			Arguments.of(TelemetryTokenRejectionReason.TENANT_DELETED, FakeAuthRow(tenantDeleted = true)),
			Arguments.of(
				TelemetryTokenRejectionReason.TENANT_SUSPENDED,
				FakeAuthRow(tenantStatus = "suspended"),
			),
			Arguments.of(
				TelemetryTokenRejectionReason.TENANT_TERMINATED,
				FakeAuthRow(tenantStatus = "terminated"),
			),
		)

		@JvmStatic
		fun precedencePairs(): Stream<Arguments> = Stream.of(
			Arguments.of(
				TelemetryTokenRejectionReason.TOKEN_REVOKED,
				FakeAuthRow(tokenRevoked = true, installationRevoked = true),
			),
			Arguments.of(
				TelemetryTokenRejectionReason.INSTALLATION_REVOKED,
				FakeAuthRow(installationRevoked = true, installationStatus = "revoked"),
			),
			Arguments.of(
				TelemetryTokenRejectionReason.INSTALLATION_INACTIVE,
				FakeAuthRow(installationStatus = "pending", memberStatus = "suspended"),
			),
			Arguments.of(
				TelemetryTokenRejectionReason.MEMBER_SUSPENDED,
				FakeAuthRow(memberStatus = "suspended", tenantDeleted = true),
			),
			Arguments.of(
				TelemetryTokenRejectionReason.MEMBER_INVITED,
				FakeAuthRow(memberStatus = "invited", tenantDeleted = true),
			),
			Arguments.of(
				TelemetryTokenRejectionReason.TENANT_DELETED,
				FakeAuthRow(tenantDeleted = true, tenantStatus = "suspended"),
			),
		)

		@JvmStatic
		fun unknownStatusesThatPass(): Stream<Arguments> = Stream.of(
			Arguments.of("member_status=deactivated", FakeAuthRow(memberStatus = "deactivated")),
			Arguments.of("tenant_status=trial", FakeAuthRow(tenantStatus = "trial")),
		)
	}
}
