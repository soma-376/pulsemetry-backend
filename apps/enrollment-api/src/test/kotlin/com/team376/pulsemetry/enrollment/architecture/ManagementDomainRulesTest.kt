package com.team376.pulsemetry.enrollment.architecture

import com.team376.pulsemetry.enrollment.member.domain.MemberRoles
import com.team376.pulsemetry.enrollment.team.domain.TeamNames
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagementDomainRulesTest {
    @Test fun `팀 이름은 유니코드 호환 정규화와 대소문자 없이 비교한다`() {
        assertTrue(TeamNames.same("Ｐlatform", "platform"))
    }

    @Test fun `역할 변경은 소유자와 자기 자신의 권한을 보호하고 동일 값은 허용한다`() {
        val member = UUID.randomUUID()
        val actor = UUID.randomUUID()
        assertNull(MemberRoles.rejection("owner", "owner", member, member))
        assertEquals("owner_role_immutable", MemberRoles.rejection("owner", "member", member, actor))
        assertEquals("self_role_change", MemberRoles.rejection("admin", "member", member, member))
        assertEquals("role_not_assignable", MemberRoles.rejection("member", "owner", member, actor))
        assertNull(MemberRoles.rejection("member", "admin", member, actor))
    }
}
