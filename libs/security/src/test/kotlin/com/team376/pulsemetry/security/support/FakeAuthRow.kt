package com.team376.pulsemetry.security.support

import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenAuthRow
import java.util.UUID

/**
 * 판정 체인만 떼어 검사하기 위한 행.
 *
 * **DB 로는 만들 수 없는 값이 필요해서 존재한다.** `member_status='deactivated'` 나
 * `tenant_status='trial'` 은 native enum(ADR 0009)이라 INSERT 자체가 안 되는데, 원본이 그런 값을
 * **통과**시킨다는 사실이 이식에서 가장 놓치기 쉬운 지점이다. 실제 스키마 위에서만 검사하면
 * 이 성질이 영영 테스트되지 않는다.
 *
 * 이식 원본의 `row()` 팩토리와 같은 역할이다
 * (`ai-telemetry-pipeline` `apps/auth-proxy/tests/auth/credential.repository.test.ts`).
 */
data class FakeAuthRow(
	override val tokenId: UUID = UUID.randomUUID(),
	override val tokenRevoked: Boolean = false,
	override val tenantId: UUID = UUID.randomUUID(),
	override val installationId: UUID = UUID.randomUUID(),
	override val memberId: UUID = UUID.randomUUID(),
	override val installationStatus: String = "active",
	override val installationRevoked: Boolean = false,
	override val memberStatus: String = "active",
	override val tenantStatus: String = "active",
	override val tenantDeleted: Boolean = false,
) : TelemetryTokenAuthRow
