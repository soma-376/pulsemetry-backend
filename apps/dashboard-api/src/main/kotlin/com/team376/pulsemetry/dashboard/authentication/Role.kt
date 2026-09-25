package com.team376.pulsemetry.dashboard.authentication

/**
 * 화면 요청서의 `Role` (`admin | lead | member | viewer`). [wire] 가 응답·요청에 나가는 표기다.
 *
 * 역할마다 무엇을 볼 수 있는지는 인가가 정한다(ADR 0022 §3) — 이 타입은 어휘만 담는다.
 */
enum class Role(val wire: String) {
	ADMIN("admin"),
	LEAD("lead"),
	MEMBER("member"),
	VIEWER("viewer"),
}
