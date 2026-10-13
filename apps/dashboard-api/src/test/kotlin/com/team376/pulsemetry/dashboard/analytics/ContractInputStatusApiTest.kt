package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.config.AnalyticsConfig
import com.team376.pulsemetry.dashboard.config.DashboardApiProperties
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** 현재 계약 알림은 정책·관측·좌석 없이 등록 계약만으로 판정한다(허브 ADR 0020). */
class ContractInputStatusApiTest : AbstractDashboardApiTest() {
	@Autowired private lateinit var properties: DashboardApiProperties
	@Autowired private lateinit var source: JdbcClient
	@Autowired private lateinit var observations: VendorObservations
	@Autowired private lateinit var tokens: CurrentStateTokens
	@Autowired private lateinit var codec: PageCursorCodec
	@Autowired private lateinit var mapper: ObjectMapper
	@Autowired private lateinit var seats: SeatService

	private data class Org(val tenant: UUID, val actor: UUID)

	private fun organization(): Org {
		val tenant = DashboardTestStores.insertTenant()
		return Org(tenant, SourceFixtures.insertMember(tenant, "admin@example.test", role = "admin"))
	}

	private fun request(org: Org, target: UUID = org.tenant, role: Role = Role.ADMIN) = http.send(
		"/api/v1/organizations/$target/contract-input-status",
		headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(org.tenant, org.actor, role))),
	)

	private fun status(org: Org): JsonNode {
		val response = request(org)
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	private fun contract(org: Org, endDate: LocalDate?, startDate: LocalDate = LocalDate.parse("2026-01-01")) = VendorContract(
		1, "team", startDate.toString(), endDate?.toString(), null,
		listOf(VendorTier("standard", "표준", 2, "0")), "0", "2026-01-01T00:00:00Z", org.actor.toString(),
	)

	private fun register(org: Org, vendorId: String, name: String, contract: VendorContract?, archived: Boolean = false) {
		DashboardTestStores.writer.sql("""
			INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at,archived)
			VALUES (:tenant,:id,:id,'manual','2026-01-01',:archived)
		""").param("tenant", org.tenant).param("id", vendorId).param("archived", archived).update()
		save(org, vendorId, name, contract, 1, archived)
	}

	private fun save(org: Org, vendorId: String, name: String, contract: VendorContract?, version: Long, archived: Boolean = false) {
		DashboardTestStores.writer.sql("""
			INSERT INTO enrollment.vendor_contract_versions (tenant_id,vendor_id,version,display_name,contract,archived,recorded_at,recorded_by)
			VALUES (:tenant,:id,:version,:name,CAST(:contract AS jsonb),:archived,'2026-01-01',:actor)
		""").param("tenant", org.tenant).param("id", vendorId).param("version", version).param("name", name)
			.param("contract", contract?.let(mapper::writeValueAsString), Types.VARCHAR).param("archived", archived).param("actor", org.actor).update()
	}

	@Test
	fun emptyOrganizationNeedsNoManifestOrObservations() {
		val body = status(organization())
		assertThat(body.path("requiredCount").asInt()).isZero()
		assertThat(body.path("vendors").size()).isZero()
		assertThat(body.path("expiredCount").asInt()).isZero()
		assertThat(body.path("expiredContracts").size()).isZero()
	}

	@Test
	fun onlyExpiredCurrentRegisteredContractsAreListedOnceInStableOrder() {
		val org = organization()
		val today = LocalDate.now(QueryReader.SEOUL)
		register(org, "later", "Alpha", contract(org, today.minusDays(1)))
		register(org, "same-name-b", "Alpha", contract(org, today.minusDays(2)))
		register(org, "same-name-a", "Alpha", contract(org, today.minusDays(2)))
		register(org, "other-name", "Beta", contract(org, today.minusDays(2)))
		register(org, "missing", "Missing", null)
		register(org, "active", "Active", contract(org, today))
		register(org, "future-end", "Future end", contract(org, today.plusDays(1)))
		register(org, "scheduled", "Scheduled", contract(org, today.plusDays(10), today.plusDays(1)))
		register(org, "no-end", "No end", contract(org, null))
		register(org, "archived", "Archived", contract(org, today.minusDays(3)), archived = true)
		val other = organization()
		register(other, "other-tenant", "Other tenant", contract(other, today.minusDays(5)))

		val body = status(org)
		assertThat(body.path("organizationId").asString()).isEqualTo(org.tenant.toString())
		assertThat(body.path("requiredCount").asInt()).isEqualTo(1)
		assertThat(body.at("/vendors/0/vendorId").asString()).isEqualTo("missing")
		assertThat(body.path("expiredCount").asInt()).isEqualTo(4)
		val expired = body.path("expiredContracts").toList()
		assertThat(expired.map { it.path("vendorId").asString() }).containsExactly("same-name-a", "same-name-b", "other-name", "later")
		assertThat(expired.map { it.path("endDate").asString() }).containsExactly(
			today.minusDays(2).toString(), today.minusDays(2).toString(), today.minusDays(2).toString(), today.minusDays(1).toString(),
		)
		assertThat(expired.map { it.propertyNames().toSet() }).containsOnly(setOf("vendorId", "displayName", "endDate"))
	}

	@Test
	fun expirationChangesAtKstMidnightAfterTheInclusiveEndDate() {
		val org = organization()
		register(org, "boundary", "Boundary", contract(org, LocalDate.parse("2026-09-30")))
		val organization = requireNotNull(organizations.find(org.tenant))
		fun at(instant: String): ContractInputStatusResponse = AnalyticsConfig().settingsService(
			false, false, properties, source, observations, tokens, codec, mapper, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), seats,
		).contractInputStatus(organization)

		assertThat(at("2026-09-30T14:59:59Z").expiredContracts).isEmpty()
		val nextDay = at("2026-09-30T15:00:00Z")
		assertThat(nextDay.expiredCount).isEqualTo(1)
		assertThat(nextDay.expiredContracts).containsExactly(ExpiredContract("boundary", "Boundary", "2026-09-30"))
	}

	@Test
	fun latestRenewalRemovalAndArchivalImmediatelyReplacePreviousExpiration() {
		val org = organization()
		val today = LocalDate.now(QueryReader.SEOUL)
		register(org, "renewed", "Renewed", contract(org, today.minusDays(1)))
		register(org, "removed", "Removed", contract(org, today.minusDays(1)))
		register(org, "archived", "Archived", contract(org, today.minusDays(1)))
		assertThat(status(org).path("expiredCount").asInt()).isEqualTo(3)

		save(org, "renewed", "Renewed", contract(org, today.plusDays(30)), 2)
		save(org, "removed", "Removed", null, 2)
		save(org, "archived", "Archived", contract(org, today.minusDays(1)), 2, archived = true)
		DashboardTestStores.writer.sql("UPDATE enrollment.managed_vendors SET archived=true WHERE tenant_id=:tenant AND vendor_id='archived'")
			.param("tenant", org.tenant).update()
		val body = status(org)
		assertThat(body.path("expiredCount").asInt()).isZero()
		assertThat(body.path("expiredContracts").size()).isZero()
		assertThat(body.path("requiredCount").asInt()).isEqualTo(1)
		assertThat(body.at("/vendors/0/vendorId").asString()).isEqualTo("removed")
	}

	@Test
	fun currentNoticesKeepSettingsRoleAndTenantAuthorization() {
		val org = organization()
		val other = organization()
		assertThat(request(org).statusCode()).isEqualTo(200)
		for (role in listOf(Role.LEAD, Role.MEMBER, Role.VIEWER)) {
			assertThat(request(org, role = role).statusCode()).describedAs(role.wire).isEqualTo(403)
		}
		assertThat(request(org, target = other.tenant).statusCode()).isEqualTo(403)
		assertThat(request(org, target = UUID.randomUUID()).statusCode()).isEqualTo(403)
	}
}
