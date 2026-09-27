package com.team376.pulsemetry.dashboard.authentication

import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RolesTest {

	@Test
	@DisplayName("owner·admin 은 admin, member 는 member — lead·viewer 로 옮겨지는 저장소 값은 없다")
	fun nameMappingOnly() {
		assertThat(Roles.of(MemberRole.owner)).isEqualTo(Role.ADMIN)
		assertThat(Roles.of(MemberRole.admin)).isEqualTo(Role.ADMIN)
		assertThat(Roles.of(MemberRole.member)).isEqualTo(Role.MEMBER)
		assertThat(MemberRole.entries.map(Roles::of)).doesNotContain(Role.LEAD, Role.VIEWER)
	}
}
