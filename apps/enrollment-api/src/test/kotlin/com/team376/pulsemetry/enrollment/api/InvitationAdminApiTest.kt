package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 관리자 초대 API 통합 테스트 (PLAN.md §6.5).
 *
 * 관리자 키는 Gradle 이 테스트 JVM 에 시스템 프로퍼티로 넣는다 (`build.gradle.kts` 참고).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class, EnrollmentTestData::class)
class InvitationAdminApiTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	@Autowired
	private lateinit var data: EnrollmentTestData

	private val http: HttpClient = HttpClient.newHttpClient()

	private lateinit var tenantId: UUID
	private lateinit var ownerId: UUID

	@BeforeEach
	fun setUp() {
		data.reset()
		tenantId = data.tenant().id
		ownerId = data.member(tenantId, "owner@example.com", MemberRole.owner).id
	}

	// ── X-Admin-Token ────────────────────────────────────────────────────────

	@Test
	@DisplayName("X-Admin-Token 이 없으면 401 unauthorized")
	fun missingAdminTokenIsUnauthorized() {
		val response = post("/v1/invitations", createBody(), adminToken = null)

		assertThat(response.statusCode()).isEqualTo(401)
		assertThat(errorCode(response)).isEqualTo("unauthorized")
	}

	@Test
	@DisplayName("X-Admin-Token 이 다르면 401 unauthorized")
	fun wrongAdminTokenIsUnauthorized() {
		val response = post("/v1/invitations", createBody(), adminToken = "not-the-token")

		assertThat(response.statusCode()).isEqualTo(401)
		assertThat(errorCode(response)).isEqualTo("unauthorized")
	}

	@Test
	@DisplayName("빈 X-Admin-Token 도 401 이다")
	fun blankAdminTokenIsUnauthorized() {
		assertThat(post("/v1/invitations", createBody(), adminToken = "").statusCode()).isEqualTo(401)
	}

	@Test
	@DisplayName("폐기 엔드포인트도 X-Admin-Token 을 요구한다")
	fun revokeRequiresAdminToken() {
		val invitationId = data.invitation(tenantId, ownerId, InvitationCode.generate()).id

		val response = post("/v1/invitations/$invitationId/revoke", null, adminToken = null)

		assertThat(response.statusCode()).isEqualTo(401)
	}

	@Test
	@DisplayName("인증 실패 응답이 관리자 키를 되돌려주지 않는다 (R4)")
	fun unauthorizedBodyLeaksNothing() {
		val response = post("/v1/invitations", createBody(), adminToken = "guess-1")

		assertThat(response.body()).doesNotContain("guess-1").doesNotContain(ADMIN_TOKEN)
	}

	// ── 발급 ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("owner 가 발급하면 201 이다")
	fun ownerCanCreateInvitation() {
		val response = post("/v1/invitations", createBody())

		assertThat(response.statusCode()).isEqualTo(201)
	}

	@Test
	@DisplayName("admin 도 발급할 수 있다")
	fun adminCanCreateInvitation() {
		val adminId = data.member(tenantId, "admin@example.com", MemberRole.admin).id

		val response = post("/v1/invitations", createBody(createdBy = adminId))

		assertThat(response.statusCode()).isEqualTo(201)
	}

	@Test
	@DisplayName("응답 최상위 키가 4개다")
	fun responseShape() {
		val body = objectMapper.readTree(post("/v1/invitations", createBody()).body())

		assertThat(body.propertyNames()).containsExactlyInAnyOrder(
			"invitation_id",
			"code",
			"expires_at",
			"install_commands",
		)
		assertThat(body.get("install_commands").propertyNames())
			.containsExactlyInAnyOrder("windows", "unix")
	}

	@Test
	@DisplayName("응답의 code 가 DB 의 code_hash 와 일치한다 — 원본은 저장하지 않는다")
	fun responseCodeMatchesStoredHash() {
		val body = objectMapper.readTree(post("/v1/invitations", createBody()).body())
		val code = body.get("code").asString()
		val invitationId = UUID.fromString(body.get("invitation_id").asString())

		val stored = data.findInvitation(invitationId)
		assertThat(stored).isNotNull()
		assertThat(stored!!.codeHash).isEqualTo(Sha256.hex(code)).isNotEqualTo(code)
	}

	@Test
	@DisplayName("발급된 code 가 정규 형식이다")
	fun issuedCodeMatchesPattern() {
		val code = objectMapper.readTree(post("/v1/invitations", createBody()).body())
			.get("code").asString()

		assertThat(InvitationCode.matches(code)).isTrue()
	}

	@Test
	@DisplayName("발급된 code 로 실제 enroll 이 가능하다")
	fun issuedCodeWorksForEnroll() {
		data.activeManifest(tenantId, ownerId)
		val code = objectMapper.readTree(post("/v1/invitations", createBody()).body())
			.get("code").asString()

		val enroll = post(
			"/v1/enroll",
			"""{"code":"$code","platform":"darwin","invite":""}""",
			adminToken = null,
		)

		assertThat(enroll.statusCode()).isEqualTo(201)
	}

	@Test
	@DisplayName("install_commands 에 코드와 설정된 base-url 이 들어간다")
	fun installCommandsUseConfiguredBaseUrl() {
		val body = objectMapper.readTree(post("/v1/invitations", createBody()).body())
		val code = body.get("code").asString()
		val commands = body.get("install_commands")

		assertThat(commands.get("windows").asString())
			.isEqualTo("irm 'https://get.pulsemetry.example.com/windows?code=$code' | iex")
		assertThat(commands.get("unix").asString())
			.isEqualTo("curl -fsSL 'https://get.pulsemetry.example.com/unix?code=$code' | sh")
	}

	@Test
	@DisplayName("expires_at 이 ISO-8601 UTC 문자열이다 — epoch 숫자가 아니다")
	fun expiresAtIsIsoInstant() {
		val expiresAt = objectMapper.readTree(post("/v1/invitations", createBody()).body())
			.get("expires_at")

		assertThat(expiresAt.isString).isTrue()
		assertThat(expiresAt.asString()).matches("^\\d{4}-\\d{2}-\\d{2}T.*Z$")
	}

	@Test
	@DisplayName("expires_in_hours 를 생략하면 기본 72시간이다")
	fun defaultTtlIsSeventyTwoHours() {
		val before = Instant.now()
		val body = objectMapper.readTree(post("/v1/invitations", createBody()).body())
		val expiresAt = Instant.parse(body.get("expires_at").asString())

		assertThat(expiresAt).isAfter(before.plus(71, ChronoUnit.HOURS))
		assertThat(expiresAt).isBefore(before.plus(73, ChronoUnit.HOURS))
	}

	@Test
	@DisplayName("expires_in_hours 를 지정하면 그대로 반영된다")
	fun explicitTtlIsHonoured() {
		val before = Instant.now()
		val body = objectMapper.readTree(
			post("/v1/invitations", createBody(expiresInHours = 2)).body(),
		)
		val expiresAt = Instant.parse(body.get("expires_at").asString())

		assertThat(expiresAt).isAfter(before.plus(1, ChronoUnit.HOURS))
		assertThat(expiresAt).isBefore(before.plus(3, ChronoUnit.HOURS))
	}

	// ── member 자동 생성 ─────────────────────────────────────────────────────

	@Test
	@DisplayName("신규 email 이면 member 를 invited 상태로 만든다")
	fun newEmailCreatesInvitedMember() {
		post("/v1/invitations", createBody(email = "newcomer@example.com"))

		val created = data.findMemberByEmail(tenantId, "newcomer@example.com")
		assertThat(created).isNotNull()
		assertThat(created!!.status).isEqualTo(MemberStatus.invited)
		assertThat(created.role).isEqualTo(MemberRole.member)
	}

	@Test
	@DisplayName("기존 member 면 새로 만들지 않고 그 member 를 대상으로 한다")
	fun existingMemberIsReused() {
		val existing = data.member(tenantId, "existing@example.com", MemberRole.member)

		val body = objectMapper.readTree(
			post("/v1/invitations", createBody(email = "existing@example.com")).body(),
		)
		val invitationId = UUID.fromString(body.get("invitation_id").asString())

		assertThat(data.countRows("members")).isEqualTo(2)
		assertThat(data.findInvitation(invitationId)!!.targetMemberId).isEqualTo(existing.id)
	}

	@Test
	@DisplayName("email 앞뒤 공백은 정리된다")
	fun emailIsTrimmed() {
		post("/v1/invitations", createBody(email = "  spaced@example.com  "))

		assertThat(data.findMemberByEmail(tenantId, "spaced@example.com")).isNotNull()
	}

	// ── 권한 ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("일반 member 가 발급을 시도하면 403 forbidden")
	fun plainMemberCannotInvite() {
		val plainId = data.member(tenantId, "plain@example.com", MemberRole.member).id

		val response = post("/v1/invitations", createBody(createdBy = plainId))

		assertThat(response.statusCode()).isEqualTo(403)
		assertThat(errorCode(response)).isEqualTo("forbidden")
	}

	@Test
	@DisplayName("다른 tenant 의 owner 가 발급을 시도하면 403")
	fun crossTenantOwnerCannotInvite() {
		val otherTenantId = data.tenant().id
		val otherOwnerId = data.member(otherTenantId, "other-owner@example.com", MemberRole.owner).id

		val response = post("/v1/invitations", createBody(createdBy = otherOwnerId))

		assertThat(response.statusCode()).isEqualTo(403)
	}

	@Test
	@DisplayName("존재하지 않는 created_by_member_id 는 403")
	fun unknownCreatorIsForbidden() {
		val response = post("/v1/invitations", createBody(createdBy = UUID.randomUUID()))

		assertThat(response.statusCode()).isEqualTo(403)
	}

	@Test
	@DisplayName("정지된 owner 가 발급을 시도하면 403 — 정지된 관리자 계정은 살아 있지 않다")
	fun suspendedCreatorCannotInvite() {
		val suspendedId = data.member(
			tenantId,
			"suspended-owner@example.com",
			MemberRole.owner,
			MemberStatus.suspended,
		).id

		val response = post("/v1/invitations", createBody(createdBy = suspendedId))

		assertThat(response.statusCode()).isEqualTo(403)
		assertThat(errorCode(response)).isEqualTo("forbidden")
	}

	@Test
	@DisplayName("정지된 사용자를 대상으로 한 발급은 403 — invited 는 정상 경로다")
	fun suspendedTargetCannotBeInvited() {
		data.member(tenantId, "suspended@example.com", MemberRole.member, MemberStatus.suspended)

		val response = post("/v1/invitations", createBody(email = "suspended@example.com"))

		assertThat(response.statusCode()).isEqualTo(403)
		assertThat(errorCode(response)).isEqualTo("forbidden")
		assertThat(data.countRows("invitations")).isZero()
	}

	@Test
	@DisplayName("아직 설치하지 않은 invited 사용자에게는 코드를 다시 발급한다")
	fun invitedTargetStillGetsInvitation() {
		data.member(tenantId, "pending@example.com", MemberRole.member, MemberStatus.invited)

		val response = post("/v1/invitations", createBody(email = "pending@example.com"))

		assertThat(response.statusCode()).isEqualTo(201)
		assertThat(data.countRows("invitations")).isEqualTo(1)
	}

	@Test
	@DisplayName("권한 실패 시 초대도 member 도 만들어지지 않는다")
	fun forbiddenLeavesNothingBehind() {
		val plainId = data.member(tenantId, "plain@example.com", MemberRole.member).id

		post("/v1/invitations", createBody(createdBy = plainId, email = "victim@example.com"))

		assertThat(data.countRows("invitations")).isZero()
		assertThat(data.findMemberByEmail(tenantId, "victim@example.com")).isNull()
	}

	// ── 요청 형식 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("필수 필드가 빠지면 400 invalid_request")
	fun missingFieldIsBadRequest() {
		val response = post("/v1/invitations", """{"tenant_id":"$tenantId"}""")

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(errorCode(response)).isEqualTo("invalid_request")
	}

	@Test
	@DisplayName("계약에 없는 필드가 오면 400")
	fun unknownFieldIsBadRequest() {
		val body = createBody().trimEnd().dropLast(1) + ""","surprise":true}"""

		assertThat(post("/v1/invitations", body).statusCode()).isEqualTo(400)
	}

	@Test
	@DisplayName("email 이 공백뿐이면 400")
	fun blankEmailIsBadRequest() {
		assertThat(post("/v1/invitations", createBody(email = "   ")).statusCode()).isEqualTo(400)
	}

	@Test
	@DisplayName("expires_in_hours 가 0 이하면 400")
	fun nonPositiveTtlIsBadRequest() {
		assertThat(post("/v1/invitations", createBody(expiresInHours = 0)).statusCode()).isEqualTo(400)
		assertThat(post("/v1/invitations", createBody(expiresInHours = -5)).statusCode()).isEqualTo(400)
	}

	// ── 폐기 ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("미사용 초대를 폐기하면 204 No Content 이고 본문이 없다")
	fun revokeUnusedInvitation() {
		val invitationId = data.invitation(tenantId, ownerId, InvitationCode.generate()).id

		val response = post("/v1/invitations/$invitationId/revoke", null)

		assertThat(response.statusCode()).isEqualTo(204)
		assertThat(response.body()).isEmpty()
		assertThat(data.findInvitation(invitationId)!!.revokedAt).isNotNull()
	}

	@Test
	@DisplayName("이미 사용된 초대를 폐기하면 409 invitation_used")
	fun revokeUsedInvitation() {
		val invitationId = data.invitation(
			tenantId, ownerId, InvitationCode.generate(), usedAt = Instant.now(),
		).id

		val response = post("/v1/invitations/$invitationId/revoke", null)

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("invitation_used")
	}

	@Test
	@DisplayName("이미 폐기된 초대를 다시 폐기하면 409 invitation_revoked")
	fun revokeAlreadyRevokedInvitation() {
		val invitationId = data.invitation(
			tenantId, ownerId, InvitationCode.generate(), revokedAt = Instant.now(),
		).id

		val response = post("/v1/invitations/$invitationId/revoke", null)

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("invitation_revoked")
	}

	@Test
	@DisplayName("존재하지 않는 초대를 폐기하면 404 invitation_not_found")
	fun revokeUnknownInvitation() {
		val response = post("/v1/invitations/${UUID.randomUUID()}/revoke", null)

		assertThat(response.statusCode()).isEqualTo(404)
		assertThat(errorCode(response)).isEqualTo("invitation_not_found")
	}

	@Test
	@DisplayName("폐기된 초대로는 enroll 이 안 된다")
	fun revokedInvitationCannotEnroll() {
		data.activeManifest(tenantId, ownerId)
		val body = objectMapper.readTree(post("/v1/invitations", createBody()).body())
		val code = body.get("code").asString()
		val invitationId = body.get("invitation_id").asString()

		post("/v1/invitations/$invitationId/revoke", null)
		val enroll = post(
			"/v1/enroll",
			"""{"code":"$code","platform":"darwin","invite":""}""",
			adminToken = null,
		)

		assertThat(enroll.statusCode()).isEqualTo(409)
		assertThat(errorCode(enroll)).isEqualTo("invitation_revoked")
	}

	@Test
	@DisplayName("UUID 가 아닌 경로 변수는 400 invalid_request 다")
	fun malformedPathVariable() {
		val response = post("/v1/invitations/not-a-uuid/revoke", null)

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(errorCode(response)).isEqualTo("invalid_request")
	}

	// ── 헬퍼 ─────────────────────────────────────────────────────────────────

	private fun createBody(
		createdBy: UUID = ownerId,
		email: String = "invitee@example.com",
		expiresInHours: Long? = null,
	): String = buildString {
		append("""{"tenant_id":"$tenantId","created_by_member_id":"$createdBy",""")
		append(""""email":"$email","display_name":"홍길동"""")
		expiresInHours?.let { append(""","expires_in_hours":$it""") }
		append("}")
	}

	private fun post(
		path: String,
		body: String?,
		adminToken: String? = ADMIN_TOKEN,
	): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
			.header("Content-Type", "application/json")
			.POST(
				body?.let { HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8) }
					?: HttpRequest.BodyPublishers.noBody(),
			)
		adminToken?.let { builder.header("X-Admin-Token", it) }
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
	}

	private fun errorCode(response: HttpResponse<String>): String =
		objectMapper.readTree(response.body()).get("error").asString()

	private companion object {
		/**
		 * 애플리케이션이 실제로 쓰는 값을 그대로 읽는다.
		 *
		 * `apps/enrollment-api/build.gradle.kts` 가 테스트 JVM 에 넣어 주는 systemProperty 다.
		 * 여기에 값을 손으로 적어 두면 빌드 쪽이 바뀌었을 때 401 만 잔뜩 나고 원인이 안 보인다.
		 */
		val ADMIN_TOKEN: String = requireNotNull(System.getProperty("pulsemetry.admin.api-token")) {
			"systemProperty pulsemetry.admin.api-token 이 없다 — build.gradle.kts 의 테스트 설정을 확인하라"
		}
	}
}
