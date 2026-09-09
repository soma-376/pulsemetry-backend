package com.team376.pulsemetry.security

import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenAuthRow

/**
 * 조회된 행 하나로 거부 사유를 판정한다.
 *
 * **순서가 곧 사양이다.** 이식 원본
 * (`ai-telemetry-pipeline` `apps/auth-proxy/src/auth/credential.repository.ts`)의
 * if 체인을 그대로 옮긴 것이고, 특성화 테스트가 우선순위 쌍까지 고정해 두었다 (PROJ-100).
 *
 * **극성이 비대칭인 것은 실수가 아니다.**
 * - `installation` 은 **허용 목록**이다 — `active` 가 아니면 전부 거부.
 * - `member` · `tenant` 는 **차단 목록**이다 — 명시된 값일 때만 거부하고 모르는 값은 통과.
 *
 * 그래서 상태를 enum 이 아니라 `String` 으로 받는다. enum 으로 매핑하면 모르는 값이 통과가 아니라
 * 역직렬화 예외가 되어 원본과 동작이 달라진다.
 *
 * 폐기 시각 검사가 상태 검사보다 앞선다는 것도 원본 그대로다 — 토큰·installation·tenant 셋 다.
 */
object TelemetryTokenDecision {

	private const val INSTALLATION_ACTIVE = "active"
	private const val MEMBER_SUSPENDED = "suspended"
	private const val MEMBER_INVITED = "invited"
	private const val TENANT_SUSPENDED = "suspended"
	private const val TENANT_TERMINATED = "terminated"

	/** 거부 사유. 통과하면 `null`. */
	fun reject(row: TelemetryTokenAuthRow): TelemetryTokenRejectionReason? = when {
		row.tokenRevoked -> TelemetryTokenRejectionReason.TOKEN_REVOKED
		row.installationRevoked -> TelemetryTokenRejectionReason.INSTALLATION_REVOKED
		row.installationStatus != INSTALLATION_ACTIVE -> TelemetryTokenRejectionReason.INSTALLATION_INACTIVE
		row.memberStatus == MEMBER_SUSPENDED -> TelemetryTokenRejectionReason.MEMBER_SUSPENDED
		row.memberStatus == MEMBER_INVITED -> TelemetryTokenRejectionReason.MEMBER_INVITED
		row.tenantDeleted -> TelemetryTokenRejectionReason.TENANT_DELETED
		row.tenantStatus == TENANT_SUSPENDED -> TelemetryTokenRejectionReason.TENANT_SUSPENDED
		row.tenantStatus == TENANT_TERMINATED -> TelemetryTokenRejectionReason.TENANT_TERMINATED
		else -> null
	}
}
