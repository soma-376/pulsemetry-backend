package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.http.HttpResponse
import java.util.UUID

/**
 * snapshot 을 HTTP 끝까지 — 앱 컨텍스트의 배선(캐시 계정·스키마 적용·오류 매핑)으로 만들고 다시 읽는다(ADR 0023 §4 · 허브 ADR 0007 AC5).
 */
class SnapshotApiTest : AbstractDashboardApiTest() {

	private val week = "startDate=2026-09-07&endDate=2026-09-13&compare=none"

	private fun get(path: String, principal: DashboardPrincipal): HttpResponse<String> =
		http.send(path, headers = mapOf("Authorization" to TestDashboardAuthenticator.header(principal)))

	private fun code(response: HttpResponse<String>) = DashboardHttp.json(response).path("error").path("code").asString()

	@Test
	@DisplayName("snapshot 을 만들고 같은 ID 로 다시 읽는다 — 날짜가 바뀌면 409 snapshot_expired")
	fun createReuseAndScopeMismatch() {
		val tenant = DashboardTestStores.insertTenant()
		val admin = DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN)

		val created = get("/api/v1/organizations/$tenant/probe/snapshot?$week", admin)
		assertThat(created.statusCode()).isEqualTo(200)
		val snapshotId = DashboardHttp.json(created).path("snapshotId").asString()
		assertThat(SnapshotIds.isWellFormed(snapshotId)).isTrue()

		val reused = get("/api/v1/organizations/$tenant/probe/snapshot?$week&snapshotId=$snapshotId", admin)
		assertThat(DashboardHttp.json(reused).path("snapshotId").asString()).isEqualTo(snapshotId)

		val otherDates = get("/api/v1/organizations/$tenant/probe/snapshot?startDate=2026-09-07&endDate=2026-09-14&compare=none&snapshotId=$snapshotId", admin)
		assertThat(otherDates.statusCode()).isEqualTo(409)
		assertThat(code(otherDates)).isEqualTo("snapshot_expired")
	}

	@Test
	@DisplayName("AC5 — 다른 tenant 의 snapshot ID 는 409, 권한이 회수된 사용자는 기존 ID 로도 403")
	fun snapshotIdGrantsNothing() {
		val tenant = DashboardTestStores.insertTenant()
		val admin = DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN)
		val snapshotId = DashboardHttp.json(get("/api/v1/organizations/$tenant/probe/snapshot?$week", admin)).path("snapshotId").asString()

		val other = DashboardTestStores.insertTenant()
		val otherAdmin = DashboardPrincipal(other, UUID.randomUUID(), Role.ADMIN)
		val foreign = get("/api/v1/organizations/$other/probe/snapshot?$week&snapshotId=$snapshotId", otherAdmin)
		assertThat(foreign.statusCode()).isEqualTo(409)
		assertThat(code(foreign)).isEqualTo("snapshot_expired")
		// 남의 조직 경로로는 snapshot 을 보기 전에 403 이다.
		assertThat(get("/api/v1/organizations/$tenant/probe/snapshot?$week&snapshotId=$snapshotId", otherAdmin).statusCode()).isEqualTo(403)

		val demoted = admin.copy(role = Role.MEMBER)
		assertThat(get("/api/v1/organizations/$tenant/probe/snapshot?$week&snapshotId=$snapshotId", demoted).statusCode()).isEqualTo(403)
	}

	@Test
	@DisplayName("모르는 snapshot ID 는 409 다 — 최신 값으로 다시 만들지 않는다")
	fun unknownSnapshotIs409() {
		val tenant = DashboardTestStores.insertTenant()
		val admin = DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN)

		val response = get("/api/v1/organizations/$tenant/probe/snapshot?$week&snapshotId=${SnapshotIds.next()}", admin)

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(DashboardTestStores.writer.sql("SELECT count(*) FROM dashboard_cache.snapshots WHERE tenant_id = :t").param("t", tenant)
			.query(Long::class.java).single()).isZero()
	}
}
