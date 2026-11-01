package com.team376.pulsemetry.enrollment.member.domain

import java.util.UUID

/** ADR 0036의 역할 변경 규칙. 같은 역할로 저장하면 상태 전이가 아니다. */
object MemberRoles {
    fun rejection(current: String, requested: String, member: UUID, actor: UUID): String? = when {
        current == requested -> null
        requested !in setOf("admin", "member") -> "role_not_assignable"
        current == "owner" -> "owner_role_immutable"
        member == actor -> "self_role_change"
        else -> null
    }
}
