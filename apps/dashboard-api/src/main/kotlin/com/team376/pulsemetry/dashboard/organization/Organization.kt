package com.team376.pulsemetry.dashboard.organization

import java.util.UUID

/**
 * 조회 대상 조직. 화면 요청서의 `organizationId` 는 `enrollment.tenants.id` 다 — 서버가 부여한 불변 ID 이고 이름·slug 가 아니다.
 */
data class Organization(
	val id: UUID,
	val name: String,
	/** 조직에 저장된 시간대. v1 조회 기간은 요청의 `timeZone`(Asia/Seoul 만)으로 해석한다. */
	val timeZone: String,
)
