package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class IngestStatusApiTest : AbstractDashboardApiTest() {
    @BeforeEach
    fun backfillDone() { SourceFixtures.completeBackfill() }

    private fun get(tenant: UUID, target: UUID = tenant, role: Role = Role.ADMIN) = http.send(
        "/api/v1/organizations/$target/ingest-status",
        headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), role))),
    )

    @Test fun `기간과 계약과 manifest가 없어도 빈 조직의 수집 상태를 조회한다`() {
        val tenant = DashboardTestStores.insertTenant()
        val response = get(tenant)
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        val body = DashboardHttp.json(response)
        assertThat(body.path("organizationId").asString()).isEqualTo(tenant.toString())
        assertThat(body.path("status").asString()).isEqualTo("empty")
        assertThat(body.path("lastReceivedAt").isNull).isTrue()
        assertThat(body.path("asOf").asString()).isNotBlank()
    }

    @Test fun `수신 시각만으로 정상이나 장애를 추정하지 않는다`() {
        val tenant = DashboardTestStores.insertTenant()
        val received = Instant.parse("2026-09-01T00:00:00Z")
        SourceFixtures.setSummary(tenant, firstReceivedAt = received, lastReceivedAt = received)
        val response = get(tenant)
        assertThat(response.statusCode()).isEqualTo(200)
        val body = DashboardHttp.json(response)
        assertThat(body.path("status").asString()).isEqualTo("unknown")
        assertThat(body.path("reason").asString()).isEqualTo("source_not_available")
        assertThat(Instant.parse(body.path("lastReceivedAt").asString())).isEqualTo(received)
    }

    @Test fun `조직 경계와 일반 구성원 접근을 차단한다`() {
        val tenant = DashboardTestStores.insertTenant()
        val other = DashboardTestStores.insertTenant()
        assertThat(get(tenant, other).statusCode()).isEqualTo(403)
        assertThat(get(tenant, role = Role.MEMBER).statusCode()).isEqualTo(403)
        assertThat(http.send("/api/v1/organizations/$tenant/ingest-status").statusCode()).isEqualTo(401)
    }
}
