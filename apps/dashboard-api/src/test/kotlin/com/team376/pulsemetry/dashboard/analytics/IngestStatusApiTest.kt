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

    // ── 설치 보고를 근거로 한 판정 (ADR 0041). 테스트 설정: 창 15분 · 지연 5분 · 중단 3시간 ──────────────────

    /** 수신 이력이 있는 조직과 활성 구성원 한 명의 설치 하나. */
    private fun collectingOrganization(): Triple<UUID, UUID, UUID> {
        val tenant = DashboardTestStores.insertTenant()
        SourceFixtures.setSummary(tenant, firstReceivedAt = Instant.parse("2026-09-01T00:00:00Z"), lastReceivedAt = Instant.parse("2026-09-20T00:00:00Z"))
        val member = SourceFixtures.insertMember(tenant, "member-${UUID.randomUUID()}@example.test")
        return Triple(tenant, member, SourceFixtures.insertInstallation(tenant, member))
    }

    private fun status(tenant: UUID): tools.jackson.databind.JsonNode {
        val response = get(tenant)
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return DashboardHttp.json(response)
    }

    private fun analytics(tenant: UUID, path: String): tools.jackson.databind.JsonNode {
        val response = http.send("/api/v1/organizations/$tenant$path",
            headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))))
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return DashboardHttp.json(response)
    }

    @Test fun `지금 보고하는 수집 중 설치가 잃지도 밀리지도 않으면 정상이고 활성 설치와 커버리지가 채워진다`() {
        val (tenant, member, installation) = collectingOrganization()
        val now = Instant.now()
        // 설정 조회는 활성 manifest 가 있어야 한다. 수집 현황 판정과는 무관하다.
        SourceFixtures.insertManifest(tenant, 1, member)
        SourceFixtures.setHeartbeat(installation, now.minusSeconds(60))
        SourceFixtures.insertSegment(installation, now.minusSeconds(3600), now.minusSeconds(60))
        SourceFixtures.insertLedger(tenant, installation, now.minusSeconds(120))
        // 보고한 적 없는 설치를 가진 구성원은 커버리지의 분모에 들지 않는다.
        SourceFixtures.insertInstallation(tenant, SourceFixtures.insertMember(tenant, "silent@example.test"))

        val body = status(tenant)
        assertThat(body.path("status").asString()).isEqualTo("healthy")
        assertThat(body.path("reason").isNull).isTrue()
        assertThat(body.path("windowMinutes").asInt()).isEqualTo(15)
        assertThat(body.path("activeInstallations").asLong()).isEqualTo(1)
        assertThat(body.path("observedMembers").asLong()).isEqualTo(1)
        assertThat(body.path("eligibleMembers").asLong()).isEqualTo(2)
        assertThat(body.path("coverageTargetMembers").asLong()).isEqualTo(1)
        assertThat(body.path("coverageObservedMembers").asLong()).isEqualTo(1)
        assertThat(body.path("coverageRatio").asDouble()).isEqualTo(1.0)
        // 처음 계약의 다섯 필드는 그대로다.
        assertThat(body.path("organizationId").asString()).isEqualTo(tenant.toString())
        assertThat(Instant.parse(body.path("lastReceivedAt").asString())).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"))
        assertThat(body.path("asOf").asString()).isNotBlank()

        // 분석 응답과 설정 응답의 ingest 조각이 같은 계산을 쓴다.
        for (ingest in listOf(
            analytics(tenant, "/analytics/overview?startDate=2026-09-07&endDate=2026-09-13").path("ingest"),
            analytics(tenant, "/settings").path("ingest"),
        )) {
            assertThat(ingest.path("status").asString()).isEqualTo("healthy")
            assertThat(ingest.path("reason").isNull).isTrue()
            assertThat(ingest.path("activeInstallations").asLong()).isEqualTo(1)
            assertThat(ingest.path("coverageRatio").asDouble()).isEqualTo(1.0)
            assertThat(ingest.path("windowMinutes").asInt()).isEqualTo(15)
            // 조각의 모양은 화면 요청서 그대로다. 분자·분모는 공통 헤더 응답에만 있다.
            assertThat(ingest.has("coverageTargetMembers")).isFalse()
        }
    }

    @Test fun `설치 보고는 있지만 회사로 가지 못하고 있으면 기간에 따라 지연과 중단을 가른다`() {
        val (tenant, _, installation) = collectingOrganization()
        val reported = Instant.now().minusSeconds(60)

        // 전달 대기가 10분째 이어진다(지연 기준 5분). 잃지는 않았다.
        SourceFixtures.setHeartbeat(installation, reported, pendingSince = reported.minusSeconds(600))
        var body = status(tenant)
        assertThat(body.path("status").asString() to body.path("reason").asString()).isEqualTo("delayed" to "delivery_delayed")

        // 잃기 시작했지만 10분 전까지는 전달됐다.
        SourceFixtures.setHeartbeat(installation, reported, lastDeliveredAt = reported.minusSeconds(600))
        SourceFixtures.insertSegment(installation, reported.minusSeconds(600), reported, lost = 4)
        body = status(tenant)
        assertThat(body.path("status").asString() to body.path("reason").asString()).isEqualTo("delayed" to "delivery_delayed")

        // 네 시간째 아무것도 전달되지 않았다(중단 기준 3시간).
        SourceFixtures.setHeartbeat(installation, reported, lastDeliveredAt = reported.minusSeconds(4 * 3600), receivingSince = reported.minusSeconds(8 * 3600))
        body = status(tenant)
        assertThat(body.path("status").asString() to body.path("reason").asString()).isEqualTo("down" to "delivery_stalled")
        // 설치는 살아 있다. 멈춘 것은 전달이다.
        assertThat(body.path("activeInstallations").asLong()).isEqualTo(1)
        assertThat(body.path("coverageRatio").asDouble()).isEqualTo(0.0)
    }

    @Test fun `창 밖에서 끝난 손실 구간은 지금의 근거가 아니다`() {
        val (tenant, _, installation) = collectingOrganization()
        val now = Instant.now()
        SourceFixtures.setHeartbeat(installation, now.minusSeconds(60))
        SourceFixtures.insertSegment(installation, now.minusSeconds(7200), now.minusSeconds(3600), lost = 9)
        SourceFixtures.insertSegment(installation, now.minusSeconds(3600), now.minusSeconds(60))
        assertThat(status(tenant).path("status").asString()).isEqualTo("healthy")
    }

    @Test fun `수집 중이던 설치가 조용해지면 확인 불가이고 중단 기준을 넘기면 중단이다`() {
        val (tenant, _, installation) = collectingOrganization()
        SourceFixtures.setHeartbeat(installation, Instant.now().minusSeconds(3600))
        var body = status(tenant)
        assertThat(body.path("status").asString() to body.path("reason").asString()).isEqualTo("unknown" to "installations_silent")
        assertThat(body.path("activeInstallations").asLong()).isZero()

        SourceFixtures.setHeartbeat(installation, Instant.now().minusSeconds(4 * 3600))
        body = status(tenant)
        assertThat(body.path("status").asString() to body.path("reason").asString()).isEqualTo("down" to "installations_silent")
        assertThat(body.path("activeInstallations").asLong()).isZero()
    }

    @Test fun `수집 비활성 설치는 판정 대상과 커버리지 분모에서 빠진다`() {
        val (tenant, _, stalled) = collectingOrganization()
        val reported = Instant.now().minusSeconds(60)
        SourceFixtures.setHeartbeat(stalled, reported, lastDeliveredAt = reported.minusSeconds(5 * 3600), receivingSince = reported.minusSeconds(8 * 3600))
        SourceFixtures.insertSegment(stalled, reported.minusSeconds(600), reported, lost = 2)
        // 회사로 직접 보내는 설치 — 데몬이 전달 결과를 보지 못한다. 경로는 켜져 있으므로 커버리지 대상이다.
        val direct = SourceFixtures.insertInstallation(tenant, SourceFixtures.insertMember(tenant, "direct@example.test"))
        SourceFixtures.setHeartbeat(direct, reported, mode = "direct", forwarding = false, receivingSince = null, lastDeliveredAt = null)
        // 로컬 배선인데 전달을 꺼 둔 설치 — 회사로 가는 경로가 없다.
        val forwardingOff = SourceFixtures.insertInstallation(tenant, SourceFixtures.insertMember(tenant, "off@example.test"))
        SourceFixtures.setHeartbeat(forwardingOff, reported, forwarding = false, lastDeliveredAt = null)
        SourceFixtures.insertLedger(tenant, direct, Instant.now().minusSeconds(120))

        val body = status(tenant)
        // 판정 대상은 멈춘 설치 하나뿐이다. 나머지 둘이 살아 있다고 해서 "일부만 멈췄다"가 되지 않는다.
        assertThat(body.path("status").asString() to body.path("reason").asString()).isEqualTo("down" to "delivery_stalled")
        assertThat(body.path("activeInstallations").asLong()).isEqualTo(3)
        assertThat(body.path("coverageTargetMembers").asLong()).isEqualTo(2)
        assertThat(body.path("coverageObservedMembers").asLong()).isEqualTo(1)
        assertThat(body.path("coverageRatio").asDouble()).isEqualTo(0.5)
        assertThat(body.path("eligibleMembers").asLong()).isEqualTo(3)
    }

    @Test fun `수신 이력이 없으면 설치가 보고하고 있어도 수신 대기다`() {
        val tenant = DashboardTestStores.insertTenant()
        val installation = SourceFixtures.insertInstallation(tenant, SourceFixtures.insertMember(tenant, "new@example.test"))
        SourceFixtures.setHeartbeat(installation, Instant.now().minusSeconds(60), lastDeliveredAt = null)
        val body = status(tenant)
        assertThat(body.path("status").asString()).isEqualTo("empty")
        assertThat(body.path("reason").isNull).isTrue()
        // 상태와 별개로, 보고하는 설치는 센다.
        assertThat(body.path("activeInstallations").asLong()).isEqualTo(1)
    }

    @Test fun `설치 보고가 없는 조직은 세는 값을 0으로 채우지 않는다`() {
        val (tenant, _, _) = collectingOrganization()
        val body = status(tenant)
        assertThat(body.path("status").asString() to body.path("reason").asString()).isEqualTo("unknown" to "source_not_available")
        assertThat(listOf("activeInstallations", "coverageRatio").map { body.path(it).isNull }).containsOnly(true)
        assertThat(body.path("coverageTargetMembers").asLong()).isZero()
    }

    @Test fun `다른 조직의 설치 보고와 폐기된 설치의 보고는 판정에 들어오지 않는다`() {
        val (tenant, member, _) = collectingOrganization()
        val (other, _, otherInstallation) = collectingOrganization()
        val now = Instant.now()
        // 다른 조직은 정상이다.
        SourceFixtures.setHeartbeat(otherInstallation, now.minusSeconds(60))
        // 이 조직의 폐기된 설치가 남긴 보고.
        val revoked = SourceFixtures.insertInstallation(tenant, member, status = "revoked")
        SourceFixtures.setHeartbeat(revoked, now.minusSeconds(60))

        val body = status(tenant)
        assertThat(body.path("status").asString() to body.path("reason").asString()).isEqualTo("unknown" to "source_not_available")
        assertThat(body.path("activeInstallations").isNull).isTrue()
        assertThat(status(other).path("status").asString()).isEqualTo("healthy")

        // 반대로 다른 조직이 멈춰도 이 조직의 정상은 그대로다.
        val live = SourceFixtures.insertInstallation(tenant, member)
        SourceFixtures.setHeartbeat(live, now.minusSeconds(60))
        SourceFixtures.setHeartbeat(otherInstallation, now.minusSeconds(60), lastDeliveredAt = now.minusSeconds(5 * 3600), receivingSince = now.minusSeconds(8 * 3600))
        SourceFixtures.insertSegment(otherInstallation, now.minusSeconds(600), now.minusSeconds(60), lost = 1)
        assertThat(status(tenant).path("status").asString()).isEqualTo("healthy")
        assertThat(status(other).path("status").asString()).isEqualTo("down")
    }

    @Test fun `조직 경계와 일반 구성원 접근을 차단한다`() {
        val tenant = DashboardTestStores.insertTenant()
        val other = DashboardTestStores.insertTenant()
        assertThat(get(tenant, other).statusCode()).isEqualTo(403)
        assertThat(get(tenant, role = Role.MEMBER).statusCode()).isEqualTo(403)
        assertThat(http.send("/api/v1/organizations/$tenant/ingest-status").statusCode()).isEqualTo(401)
    }
}
