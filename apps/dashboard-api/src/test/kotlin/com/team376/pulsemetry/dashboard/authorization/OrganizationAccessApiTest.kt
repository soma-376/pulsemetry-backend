package com.team376.pulsemetry.dashboard.authorization

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.PageCursor
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 조직 경로의 공통 관문(ADR 0022 §3)과 요청 해석을 실제 필터 체인 뒤에서 본다 — 테스트 전용 프로브 컨트롤러를 거친다.
 */
class OrganizationAccessApiTest : AbstractDashboardApiTest() {

	@Autowired
	private lateinit var codec: PageCursorCodec

	@Autowired
	private lateinit var jdbc: JdbcClient

	private val week = "startDate=2026-09-07&endDate=2026-09-13"

	private fun principal(tenantId: UUID, role: Role = Role.ADMIN) = DashboardPrincipal(tenantId, UUID.randomUUID(), role)

	private fun get(path: String, principal: DashboardPrincipal): HttpResponse<String> =
		http.send(path, headers = mapOf("Authorization" to TestDashboardAuthenticator.header(principal)))

	private fun fieldErrors(response: HttpResponse<String>): List<Pair<String, String>> =
		DashboardHttp.json(response).path("error").path("fieldErrors").let { errors ->
			(0 until errors.size()).map { errors.get(it).path("field").asString() to errors.get(it).path("code").asString() }
		}

	@Test
	@DisplayName("자기 조직의 관리자는 통과하고, 기간은 [시작 자정, 종료 다음 날 자정) KST 의 UTC 로 해석된다")
	fun adminOfOwnOrganizationPasses() {
		val tenant = DashboardTestStores.insertTenant()

		val response = get("/api/v1/organizations/$tenant/probe/compared?$week&compare=prev_period", principal(tenant))

		assertThat(response.statusCode()).isEqualTo(200)
		val body = DashboardHttp.json(response)
		assertThat(body.path("organizationId").asString()).isEqualTo(tenant.toString())
		assertThat(body.path("days").asInt()).isEqualTo(7)
		assertThat(body.path("from").asString()).isEqualTo("2026-09-06T15:00:00Z")
		assertThat(body.path("until").asString()).isEqualTo("2026-09-13T15:00:00Z")
		assertThat(body.path("previousStartDate").asString()).isEqualTo("2026-08-31")
		assertThat(body.path("previousEndDate").asString()).isEqualTo("2026-09-06")
	}

	@Test
	@DisplayName("다른 조직은 있든 없든 403 이다 — 존재 여부를 드러내지 않는다")
	fun otherOrganizationIs403() {
		val mine = DashboardTestStores.insertTenant()
		val other = DashboardTestStores.insertTenant()

		for (target in listOf(other, UUID.randomUUID())) {
			val response = get("/api/v1/organizations/$target/probe/compared?$week", principal(mine))
			assertThat(response.statusCode()).isEqualTo(403)
			assertThat(DashboardHttp.json(response).path("error").path("code").asString()).isEqualTo("forbidden")
		}
	}

	@ParameterizedTest
	@EnumSource(value = Role::class, names = ["LEAD", "MEMBER", "VIEWER"])
	@DisplayName("관리자가 아닌 역할은 어떤 행위든 403 이다 (합의 전 기본 정책)")
	fun nonAdminRolesAreForbidden(role: Role) {
		val tenant = DashboardTestStores.insertTenant()

		for (action in DashboardAction.entries) {
			val response = get("/api/v1/organizations/$tenant/probe/action/${action.name}", principal(tenant, role))
			assertThat(response.statusCode()).describedAs("$role $action").isEqualTo(403)
		}
		assertThat(get("/api/v1/organizations/$tenant/probe/action/ORGANIZATION_ANALYTICS", principal(tenant)).statusCode())
			.isEqualTo(200)
	}

	@Test
	@DisplayName("권한 검사가 요청 형식 검사보다 먼저다 — 다른 조직에 틀린 파라미터를 보내도 403")
	fun authorizationPrecedesValidation() {
		val mine = DashboardTestStores.insertTenant()

		assertThat(get("/api/v1/organizations/${UUID.randomUUID()}/probe/compared?startDate=x", principal(mine)).statusCode())
			.isEqualTo(403)
	}

	@Test
	@DisplayName("자기 조직이 없거나 삭제 표시가 있으면 404")
	fun missingOrDeletedOrganizationIs404() {
		val missing = UUID.randomUUID()
		val deleted = DashboardTestStores.insertTenant(deleted = true)

		assertThat(get("/api/v1/organizations/$missing/probe/compared?$week", principal(missing)).statusCode()).isEqualTo(404)
		assertThat(get("/api/v1/organizations/$deleted/probe/compared?$week", principal(deleted)).statusCode()).isEqualTo(404)
	}

	@Test
	@DisplayName("organizationId 가 UUID 가 아니면 400 invalid_format")
	fun malformedOrganizationIdIs400() {
		val response = get("/api/v1/organizations/not-a-uuid/probe/compared?$week", principal(UUID.randomUUID()))

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(fieldErrors(response)).containsExactly("organizationId" to "invalid_format")
	}

	@Test
	@DisplayName("잘못된 조회 파라미터는 400 이고 필드 오류를 모두 싣는다")
	fun invalidParametersAre400() {
		val tenant = DashboardTestStores.insertTenant()

		val response = get(
			"/api/v1/organizations/$tenant/probe/compared?startDate=2026-09-13&endDate=2026-09-07&compare=weekly&timeZone=UTC",
			principal(tenant),
		)

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(DashboardHttp.json(response).path("error").path("code").asString()).isEqualTo("invalid_request")
		assertThat(fieldErrors(response)).containsExactlyInAnyOrder(
			"endDate" to "invalid_order",
			"compare" to "unsupported_value",
			"timeZone" to "unsupported_value",
		)
	}

	@Test
	@DisplayName("목록 파라미터 — limit·cursor·q 를 해석하고 깨진 값은 400")
	fun pageParameters() {
		val tenant = DashboardTestStores.insertTenant()
		val admin = principal(tenant)
		val cursor = codec.encode(PageCursor("snap-1", "members", listOf("a")))
		val q = URLEncoder.encode("김 철수", StandardCharsets.UTF_8)

		val ok = get("/api/v1/organizations/$tenant/probe/page?$week&limit=100&cursor=$cursor&q=$q", admin)
		assertThat(ok.statusCode()).isEqualTo(200)
		assertThat(DashboardHttp.json(ok).path("limit").asInt()).isEqualTo(100)
		assertThat(DashboardHttp.json(ok).path("cursorSnapshotId").asString()).isEqualTo("snap-1")
		assertThat(DashboardHttp.json(ok).path("q").asString()).isEqualTo("김 철수")

		val bad = get("/api/v1/organizations/$tenant/probe/page?$week&limit=101&cursor=broken", admin)
		assertThat(bad.statusCode()).isEqualTo(400)
		assertThat(fieldErrors(bad)).containsExactlyInAnyOrder("limit" to "out_of_range", "cursor" to "invalid_cursor")
	}

	@Test
	@DisplayName("RDS 원천 장애는 503 unavailable + Retry-After 다")
	fun rdsOutageIs503() {
		val tenant = DashboardTestStores.insertTenant()
		doThrow(DataAccessResourceFailureException("connection refused")).`when`(organizations).find(anyUuid())

		val response = get("/api/v1/organizations/$tenant/probe/compared?$week", principal(tenant))

		assertThat(response.statusCode()).isEqualTo(503)
		assertThat(DashboardHttp.json(response).path("error").path("code").asString()).isEqualTo("unavailable")
		assertThat(response.headers().firstValue("Retry-After")).hasValue("2")
		assertThat(response.body()).doesNotContain("connection refused")
	}

	@Test
	@DisplayName("앱의 RDS 연결은 읽기 전용이다 — 자동 커밋 문장도 쓰지 못한다")
	fun sourceConnectionIsReadOnly() {
		assertThat(jdbc.sql("SELECT count(*) FROM enrollment.tenants").query(Long::class.java).single()).isNotNull()
		assertThatThrownBy {
			jdbc.sql("INSERT INTO enrollment.tenants (id, name, timezone, status, created_at, updated_at) VALUES (gen_random_uuid(), 'x', 'Asia/Seoul', 'active', now(), now())")
				.update()
		}.isInstanceOf(DataAccessException::class.java)
	}

	/** Kotlin non-null 매개변수에 `any()` 를 넘기면 NPE 다 — matcher 를 등록하고 자리표시 값을 돌려준다. */
	private fun anyUuid(): UUID {
		any(UUID::class.java)
		return UUID(0, 0)
	}
}
