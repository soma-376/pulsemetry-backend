package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 구성원 팀·역할 변경(ADR 0036)과 초대 목록의 구성원 식별자. 실제 PostgreSQL·HTTP로 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class MemberEditApiTest : AbstractUserAuthApiTest() {
    private val earlier = Instant.parse("2026-09-01T00:00:00Z")

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun errorCode(response: HttpResponse<String>) = json(response).path("error").path("code").asString()
    private fun team(token: String, name: String): String {
        val created = manage("POST", "/teams", mapOf("teamName" to name), token)
        assertThat(created.statusCode()).isEqualTo(201)
        return json(created).path("teamId").asString()
    }
    /** 저장된 버전. dashboard-api 의 구성원 목록이 같은 컬럼(`members.updated_at`)의 밀리초를 version 으로 낸다. */
    private fun storedVersion(id: UUID) = jdbc.sql("SELECT updated_at FROM enrollment.members WHERE id=:id").param("id", id)
        .query { r, _ -> r.getTimestamp(1).toInstant().toEpochMilli() }.single()
    private fun storedRole(id: UUID) = jdbc.sql("SELECT role::text FROM enrollment.members WHERE id=:id").param("id", id).query(String::class.java).single()
    private fun openTeams(id: UUID) = jdbc.sql("SELECT team_id::text FROM enrollment.team_memberships WHERE member_id=:id AND left_at IS NULL")
        .param("id", id).query(String::class.java).list()
    private fun membershipRows(id: UUID) = jdbc.sql("SELECT count(*) FROM enrollment.team_memberships WHERE member_id=:id").param("id", id).query(Int::class.java).single()
    /** 버전을 시계보다 이른 값으로 고정한 활성 구성원. */
    private fun activeMember(email: String, role: MemberRole = MemberRole.member): UUID {
        val id = data.member(tenant, email, role = role, status = MemberStatus.active).id
        jdbc.sql("UPDATE enrollment.members SET updated_at=:at WHERE id=:id").param("at", Timestamp.from(earlier)).param("id", id).update()
        return id
    }
    private fun patch(id: Any, body: Map<String, Any?>, token: String) = manage("PATCH", "/members/$id", body, token)

    @Test fun `팀만 바꾸면 소속을 옮기고 역할은 그대로 둔다`() {
        val token = adminToken()
        val platform = team(token, "플랫폼")
        val target = activeMember("team-only@example.test")

        val moved = patch(target, mapOf("expectedVersion" to earlier.toEpochMilli(), "teamId" to platform), token)

        assertThat(moved.statusCode()).isEqualTo(200)
        val body = json(moved)
        assertThat(body.path("memberId").asString()).isEqualTo(target.toString())
        assertThat(body.path("team").path("teamId").asString()).isEqualTo(platform)
        assertThat(body.path("team").path("teamName").asString()).isEqualTo("플랫폼")
        assertThat(body.path("role").asString()).isEqualTo("member")
        assertThat(body.path("status").asString()).isEqualTo("active")
        // 재조회: 저장소의 값이 응답과 같다.
        assertThat(body.path("version").asLong()).isEqualTo(storedVersion(target)).isNotEqualTo(earlier.toEpochMilli())
        assertThat(openTeams(target)).containsExactly(platform)
        assertThat(storedRole(target)).isEqualTo("member")

        // teamId: null 은 미배정이다. 닫힌 소속 구간은 남는다.
        val cleared = patch(target, mapOf("expectedVersion" to body.path("version").asLong(), "teamId" to null), token)
        assertThat(cleared.statusCode()).isEqualTo(200)
        assertThat(json(cleared).path("team").isNull).isTrue()
        assertThat(openTeams(target)).isEmpty()
        assertThat(membershipRows(target)).isEqualTo(1)
    }

    @Test fun `역할만 바꾸면 소속 이력은 건드리지 않는다`() {
        val token = adminToken()
        val platform = team(token, "플랫폼")
        val target = activeMember("role-only@example.test")
        val first = json(patch(target, mapOf("expectedVersion" to earlier.toEpochMilli(), "teamId" to platform), token))

        val promoted = patch(target, mapOf("expectedVersion" to first.path("version").asLong(), "role" to "admin"), token)

        assertThat(promoted.statusCode()).isEqualTo(200)
        assertThat(json(promoted).path("role").asString()).isEqualTo("admin")
        assertThat(json(promoted).path("team").path("teamId").asString()).isEqualTo(platform)
        assertThat(storedRole(target)).isEqualTo("admin")
        assertThat(membershipRows(target)).isEqualTo(1)
        assertThat(openTeams(target)).containsExactly(platform)
        // admin → member 도 허용 전이다.
        val demoted = patch(target, mapOf("expectedVersion" to json(promoted).path("version").asLong(), "role" to "member"), token)
        assertThat(demoted.statusCode()).isEqualTo(200)
        assertThat(storedRole(target)).isEqualTo("member")
    }

    @Test fun `팀과 역할을 함께 바꾸면 한 번에 저장하고 버전은 한 번만 오른다`() {
        val token = adminToken()
        val product = team(token, "제품")
        val target = activeMember("both@example.test")

        val saved = patch(target, mapOf("expectedVersion" to earlier.toEpochMilli(), "teamId" to product, "role" to "admin"), token)

        assertThat(saved.statusCode()).isEqualTo(200)
        // 이전 버전이 서버 시각보다 이르면 새 버전은 서버 시각의 밀리초다 — 두 번 올렸다면 그보다 크다.
        assertThat(json(saved).path("version").asLong()).isEqualTo(clock.now.toEpochMilli()).isEqualTo(storedVersion(target))
        assertThat(storedRole(target)).isEqualTo("admin")
        assertThat(openTeams(target)).containsExactly(product)
    }

    @Test fun `같은 값을 다시 보내면 아무것도 바꾸지 않는다`() {
        val token = adminToken()
        val platform = team(token, "플랫폼")
        val target = activeMember("same@example.test")
        val first = json(patch(target, mapOf("expectedVersion" to earlier.toEpochMilli(), "teamId" to platform), token))

        val again = patch(target, mapOf("expectedVersion" to first.path("version").asLong(), "teamId" to platform, "role" to "member"), token)

        assertThat(again.statusCode()).isEqualTo(200)
        assertThat(json(again).path("version").asLong()).isEqualTo(first.path("version").asLong())
        assertThat(membershipRows(target)).isEqualTo(1)
    }

    @Test fun `오래된 version은 409이고 저장하지 않는다`() {
        val token = adminToken()
        val target = activeMember("stale@example.test")

        val stale = patch(target, mapOf("expectedVersion" to earlier.toEpochMilli() - 1, "role" to "admin"), token)

        assertThat(stale.statusCode()).isEqualTo(409)
        assertThat(errorCode(stale)).isEqualTo("version_conflict")
        assertThat(storedRole(target)).isEqualTo("member")
        assertThat(storedVersion(target)).isEqualTo(earlier.toEpochMilli())
    }

    @Test fun `초대 대기자는 목록의 구성원 식별자와 버전으로 편집한다`() {
        val token = adminToken()
        val platform = team(token, "플랫폼")
        val issued = manage("POST", "/invitations/batch", mapOf("invitations" to listOf(mapOf("email" to "pending@example.test", "teamId" to null, "role" to "member"))), token)
        val invitationId = json(issued).path("results")[0].path("invitationId").asString()
        fun listed() = json(manage("GET", "/invitations?limit=100", null, token)).path("items").first { it.path("invitationId").asString() == invitationId }
        val before = listed()
        assertThat(before.path("team").isNull).isTrue()
        assertThat(before.path("role").asString()).isEqualTo("member")
        val memberId = UUID.fromString(before.path("memberId").asString())
        assertThat(before.path("memberVersion").asLong()).isEqualTo(storedVersion(memberId))

        val edited = patch(memberId, mapOf("expectedVersion" to before.path("memberVersion").asLong(), "teamId" to platform, "role" to "admin"), token)

        assertThat(edited.statusCode()).isEqualTo(200)
        assertThat(json(edited).path("status").asString()).isEqualTo("invited")
        val after = listed()
        assertThat(after.path("role").asString()).isEqualTo("admin")
        assertThat(after.path("team").path("teamId").asString()).isEqualTo(platform)
        assertThat(after.path("team").path("teamName").asString()).isEqualTo("플랫폼")
        assertThat(after.path("memberVersion").asLong()).isEqualTo(json(edited).path("version").asLong())
        assertThat(after.path("status").asString()).isEqualTo("pending")
    }

    @Test fun `오너의 역할은 바꿀 수 없지만 팀은 바꿀 수 있다`() {
        val token = adminToken()
        val platform = team(token, "플랫폼")
        val owner = activeMember("second-owner@example.test", MemberRole.owner)

        val demote = patch(owner, mapOf("expectedVersion" to earlier.toEpochMilli(), "role" to "member"), token)
        assertThat(demote.statusCode()).isEqualTo(422)
        assertThat(errorCode(demote)).isEqualTo("owner_role_immutable")
        assertThat(json(demote).path("error").path("fieldErrors")[0].path("field").asString()).isEqualTo("role")
        assertThat(storedRole(owner)).isEqualTo("owner")

        val moved = patch(owner, mapOf("expectedVersion" to earlier.toEpochMilli(), "teamId" to platform), token)
        assertThat(moved.statusCode()).isEqualTo(200)
        assertThat(json(moved).path("role").asString()).isEqualTo("owner")
        assertThat(openTeams(owner)).containsExactly(platform)
    }

    @Test fun `자기 역할은 바꿀 수 없지만 자기 팀은 바꿀 수 있다`() {
        assertThat(signup().statusCode()).isEqualTo(201)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id", member).update()
        val token = mapper.readTree(login().body()).path("access_token").asString()
        val platform = team(token, "플랫폼")
        val version = storedVersion(member)

        val self = patch(member, mapOf("expectedVersion" to version, "role" to "member"), token)
        assertThat(self.statusCode()).isEqualTo(422)
        assertThat(errorCode(self)).isEqualTo("self_role_change")
        assertThat(storedRole(member)).isEqualTo("admin")

        val moved = patch(member, mapOf("expectedVersion" to version, "teamId" to platform, "role" to "admin"), token)
        assertThat(moved.statusCode()).isEqualTo(200)
        assertThat(openTeams(member)).containsExactly(platform)
    }

    @Test fun `정지 구성원과 없는 역할과 빈 요청과 없는 팀을 거부한다`() {
        val token = adminToken()
        val suspended = data.member(tenant, "suspended@example.test", role = MemberRole.member, status = MemberStatus.suspended).id
        val blocked = patch(suspended, mapOf("expectedVersion" to storedVersion(suspended), "role" to "admin"), token)
        assertThat(blocked.statusCode()).isEqualTo(409)
        assertThat(errorCode(blocked)).isEqualTo("member_suspended")

        val target = activeMember("rules@example.test")
        val version = earlier.toEpochMilli()
        val lead = patch(target, mapOf("expectedVersion" to version, "role" to "lead"), token)
        assertThat(lead.statusCode()).isEqualTo(422)
        assertThat(errorCode(lead)).isEqualTo("role_not_assignable")
        val toOwner = patch(target, mapOf("expectedVersion" to version, "role" to "owner"), token)
        assertThat(toOwner.statusCode()).isEqualTo(422)
        assertThat(errorCode(toOwner)).isEqualTo("role_not_assignable")
        val empty = patch(target, mapOf("expectedVersion" to version), token)
        assertThat(empty.statusCode()).isEqualTo(400)
        assertThat(errorCode(empty)).isEqualTo("invalid_request")
        val missingTeam = patch(target, mapOf("expectedVersion" to version, "teamId" to UUID.randomUUID().toString()), token)
        assertThat(missingTeam.statusCode()).isEqualTo(404)
        assertThat(errorCode(missingTeam)).isEqualTo("not_found")
        assertThat(storedVersion(target)).isEqualTo(version)
        assertThat(storedRole(target)).isEqualTo("member")
    }

    @Test fun `다른 조직의 구성원과 팀은 404이고 일반 구성원은 403이다`() {
        val token = adminToken()
        val otherTenant = data.tenant().id
        val outsider = data.member(otherTenant, "outsider@example.test", role = MemberRole.member, status = MemberStatus.active).id
        val foreign = patch(outsider, mapOf("expectedVersion" to storedVersion(outsider), "role" to "admin"), token)
        assertThat(foreign.statusCode()).isEqualTo(404)
        assertThat(errorCode(foreign)).isEqualTo("not_found")
        assertThat(storedRole(outsider)).isEqualTo("member")

        val foreignTeam = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'남의 팀')").param("id", foreignTeam).param("tenant", otherTenant).update()
        val target = activeMember("mine@example.test")
        assertThat(patch(target, mapOf("expectedVersion" to earlier.toEpochMilli(), "teamId" to foreignTeam.toString()), token).statusCode()).isEqualTo(404)
        assertThat(openTeams(target)).isEmpty()

        // 관리자 권한이 없는 호출자.
        val plain = session("plain@example.test")
        val denied = patch(target, mapOf("expectedVersion" to earlier.toEpochMilli(), "role" to "admin"), plain.path("access_token").asString())
        assertThat(denied.statusCode()).isEqualTo(403)
        assertThat(errorCode(denied)).isEqualTo("forbidden")
        assertThat(storedRole(target)).isEqualTo("member")
    }

    @Test fun `역할이 바뀐 구성원의 기존 AT는 거부되고 갱신한 AT는 새 역할로 판정된다`() {
        val token = adminToken()
        val promoted = session("promoted@example.test")
        val promotedId = jdbc.sql("SELECT id FROM enrollment.members WHERE email='promoted@example.test'").query(UUID::class.java).single()
        fun onboarding(at: String) = manage("GET", "/onboarding", null, at).statusCode()
        assertThat(onboarding(promoted.path("access_token").asString())).isEqualTo(403)

        assertThat(patch(promotedId, mapOf("expectedVersion" to storedVersion(promotedId), "role" to "admin"), token).statusCode()).isEqualTo(200)

        // 클레임의 역할이 저장된 역할과 다르다 — 기존 AT는 더 이상 인증되지 않는다.
        assertThat(onboarding(promoted.path("access_token").asString())).isEqualTo(401)
        val renewed = refresh(promoted.path("refresh_token").asString())
        assertThat(renewed.statusCode()).isEqualTo(200)
        val admin = mapper.readTree(renewed.body())
        assertThat(onboarding(admin.path("access_token").asString())).isEqualTo(200)

        // 강등도 같다: 관리자 AT는 거부되고, 갱신한 AT는 관리 권한이 없다.
        assertThat(patch(promotedId, mapOf("expectedVersion" to storedVersion(promotedId), "role" to "member"), token).statusCode()).isEqualTo(200)
        assertThat(onboarding(admin.path("access_token").asString())).isEqualTo(401)
        val demoted = mapper.readTree(refresh(admin.path("refresh_token").asString()).body())
        assertThat(onboarding(demoted.path("access_token").asString())).isEqualTo(403)
    }

    @Test fun `초대 목록은 상태로 거르고 필터를 유지한 채 페이지를 넘긴다`() {
        val token = adminToken()
        fun invite(email: String): String = json(manage("POST", "/invitations/batch",
            mapOf("invitations" to listOf(mapOf("email" to email, "teamId" to null, "role" to "member"))), token)).path("results")[0].path("invitationId").asString()
        val pendingA = invite("pending-a@example.test")
        val pendingB = invite("pending-b@example.test")
        val revoked = invite("revoked@example.test")
        val expired = invite("expired@example.test")
        val used = invite("used@example.test")
        assertThat(manage("POST", "/invitations/$revoked/revoke", emptyMap<String, String>(), token).statusCode()).isEqualTo(204)
        jdbc.sql("UPDATE enrollment.invitations SET expires_at=:at WHERE id=:id").param("at", Timestamp.from(clock.now.minusSeconds(1))).param("id", UUID.fromString(expired)).update()
        jdbc.sql("UPDATE enrollment.invitations SET used_at=:at,signup_used_at=:at WHERE id=:id").param("at", Timestamp.from(clock.now)).param("id", UUID.fromString(used)).update()
        // JsonNode.map 은 노드 하나를 변환한다 — 배열 요소를 돌려면 먼저 목록으로 편다.
        fun ids(response: JsonNode): MutableList<String> = response.path("items").toList().map { it.path("invitationId").asString() }.toMutableList()
        fun ids(query: String) = ids(json(manage("GET", "/invitations?$query", null, token)))

        assertThat(ids("limit=100&status=revoked")).containsExactly(revoked)
        assertThat(ids("limit=100&status=expired")).containsExactly(expired)
        assertThat(ids("limit=100&status=used")).containsExactly(used)
        // setup 의 초대 하나도 대기 상태다. 가입만 소비했고 설치는 남았다.
        val pending = ids("limit=100&status=pending")
        assertThat(pending).contains(pendingA, pendingB).doesNotContain(revoked, expired, used).hasSize(3)
        // 필터가 없으면 전부다.
        assertThat(ids("limit=100")).hasSize(6)

        val firstPage = json(manage("GET", "/invitations?limit=2&status=pending", null, token))
        assertThat(firstPage.path("items")).hasSize(2)
        val cursor = firstPage.path("nextCursor").asString()
        val secondPage = json(manage("GET", "/invitations?limit=2&status=pending&cursor=$cursor", null, token))
        assertThat(ids(firstPage).apply { addAll(ids(secondPage)) }).containsExactlyElementsOf(pending)
        assertThat(secondPage.path("nextCursor").isNull).isTrue()

        val invalid = manage("GET", "/invitations?status=queued", null, token)
        assertThat(invalid.statusCode()).isEqualTo(400)
        assertThat(errorCode(invalid)).isEqualTo("invalid_request")
    }

    @Test fun `초대 목록은 대상 구성원의 상태를 싣고 그 상태로 거른다`() {
        val token = adminToken()
        val waiting = json(manage("POST", "/invitations/batch",
            mapOf("invitations" to listOf(mapOf("email" to "waiting@example.test", "teamId" to null, "role" to "member"))), token)).path("results")[0].path("invitationId").asString()
        fun items(query: String) = json(manage("GET", "/invitations?$query", null, token)).path("items").toList()

        // setup 의 초대는 가입을 마친 구성원(active)의 것이고, 방금 발급한 초대의 대상은 아직 invited 다.
        val all = items("limit=100")
        assertThat(all.first { it.path("invitationId").asString() == waiting }.path("memberStatus").asString()).isEqualTo("invited")
        assertThat(all.first { it.path("memberId").asString() == member.toString() }.path("memberStatus").asString()).isEqualTo("active")
        // 둘 다 초대 상태는 pending 이다 — 아직 합류하지 않은 사람은 구성원 상태로 가른다.
        assertThat(all.map { it.path("status").asString() }.toSet()).containsExactly("pending")
        assertThat(items("limit=100&status=pending&memberStatus=invited").map { it.path("invitationId").asString() }.toMutableList()).containsExactly(waiting)
        assertThat(items("limit=100&memberStatus=active").map { it.path("memberId").asString() }.toMutableList()).containsExactly(member.toString())
        assertThat(items("limit=100&memberStatus=suspended")).isEmpty()

        val invalid = manage("GET", "/invitations?memberStatus=removed", null, token)
        assertThat(invalid.statusCode()).isEqualTo(400)
        assertThat(errorCode(invalid)).isEqualTo("invalid_request")
    }

    /** 초대 → 가입 → 로그인한 일반 구성원의 토큰 봉투. */
    private fun session(email: String): JsonNode {
        val id = data.member(tenant, email, role = MemberRole.member, status = MemberStatus.invited).id
        val invitation = InvitationCode.generate()
        data.invitation(tenant, id, invitation, expiresAt = clock.now.plusSeconds(3600))
        assertThat(post("signup", mapOf("code" to invitation, "email" to email, "password" to password)).statusCode()).isEqualTo(201)
        val login = post("login", mapOf("tenant_id" to tenant, "email" to email, "password" to password))
        assertThat(login.statusCode()).isEqualTo(200)
        return mapper.readTree(login.body())
    }
}
