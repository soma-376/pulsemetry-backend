package com.team376.pulsemetry.dashboard.authentication

import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole

/**
 * 저장소의 구성원 역할(`enrollment.member_role`)을 화면 어휘 [Role] 로 옮긴다 — **이름 대응까지만** 한다.
 *
 * `owner`·`admin` 은 [Role.ADMIN] 이다. 허브 dashboard API 계약 §2 가 "웹 대시보드에 들어오는 사람은 관리자
 * (`role` 이 `owner`·`admin`)"라고 적은 그 둘이다. `member` 는 [Role.MEMBER] 다. [Role.LEAD]·[Role.VIEWER] 에
 * 대응하는 저장소 값은 없다 — 새 역할을 지어내지 않는다.
 *
 * 인증 포트 구현이 AT 의 역할 클레임을 주체로 옮길 때 쓴다(ADR 0022 §3).
 */
object Roles {

	fun of(role: MemberRole): Role = when (role) {
		MemberRole.owner, MemberRole.admin -> Role.ADMIN
		MemberRole.member -> Role.MEMBER
	}
}
