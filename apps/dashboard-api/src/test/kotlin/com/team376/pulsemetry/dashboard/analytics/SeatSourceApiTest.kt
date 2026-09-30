package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.JsonStructure
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * 설정의 벤더별 좌석 원천 (ADR 0048 §3·§6). 기대값은 ADR 의 우선순위 표와 노출 규칙에서 쓴다 — 활성 연결이 있으면 커넥터가 권위,
 * 커넥터가 있는 플랜인데 연결이 없으면 수동 임시, 커넥터가 없는 플랜은 수동. 자격증명은 설정됨 여부와 갱신 시각만 내고 암호문은 읽지 않는다.
 * 이 앱은 읽기만 한다 — enrollment 의 연결 명령이 남기는 행을 직접 넣는다.
 */
class SeatSourceApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() { SourceFixtures.completeBackfill() }

	private fun ok(tenant: UUID, path: String): JsonNode {
		val response = http.send("/api/v1/organizations/$tenant$path",
			headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))))
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	private val ciphertext = "b3BhcXVlLWNpcGhlcnRleHQtZm9yLXRlc3Q="

	private data class Org(val tenant: UUID, val admin: UUID)

	private fun organization(): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = LocalDateTime.parse("2026-09-01T00:00:00").atZone(QueryReader.SEOUL).toInstant())
		val admin = SourceFixtures.insertMember(tenant, "admin-${UUID.randomUUID()}@example.test", role = "admin")
		SourceFixtures.insertManifest(tenant, 2, admin)
		return Org(tenant, admin)
	}

	private fun register(org: Org, kind: String, plan: String): String {
		val id = "$kind-${UUID.randomUUID()}"
		DashboardTestStores.writer.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:tenant,:id,:kind,'manual','2026-01-01')")
			.param("tenant", org.tenant).param("id", id).param("kind", kind).update()
		val contract = """{"version":1,"planId":"$plan","effectiveFrom":"2026-01-01","effectiveTo":null,"termNote":null,"tiers":[{"tierId":"t1","label":"표준","seats":3,"monthlyFeePerSeatUsd":"19"}],"monthlySeatFeeUsd":"57","confirmedAt":"2026-01-01T00:00:00Z","confirmedBy":"${org.admin}"}"""
		DashboardTestStores.writer.sql("INSERT INTO enrollment.vendor_contract_versions (tenant_id,vendor_id,version,display_name,contract,recorded_at,recorded_by) VALUES (:tenant,:id,1,:kind,CAST(:c AS jsonb),'2026-01-01',:admin)")
			.param("tenant", org.tenant).param("id", id).param("kind", kind).param("c", contract).param("admin", org.admin).update()
		return id
	}

	private fun connect(org: Org, vendorId: String, connector: String, settings: String, deleted: Boolean = false, succeeded: Instant? = null, failed: Instant? = null): UUID =
		UUID.randomUUID().also { id ->
			val at = Timestamp.from(Instant.parse("2026-09-20T01:00:00Z"))
			DashboardTestStores.writer.sql("""INSERT INTO enrollment.vendor_connections (id, tenant_id, vendor_id, connector, settings, credential_ciphertext, credential_key_id,
					credential_updated_at, check_status, checked_at, last_sync_succeeded_at, last_sync_failed_at, last_sync_error, version, created_at, created_by, updated_at, updated_by,
					deleted_at, deleted_by)
				VALUES (:id, :tenant, :vendor, :connector, CAST(:settings AS jsonb), :ciphertext, :key, :credentialAt, 'verified', :at, :succeeded, :failed, :error, 3, :at, :admin, :at, :admin,
					:deletedAt, :deletedBy)""")
				.param("id", id).param("tenant", org.tenant).param("vendor", vendorId).param("connector", connector).param("settings", settings)
				.param("ciphertext", if (deleted) null else ciphertext, java.sql.Types.VARCHAR).param("key", if (deleted) null else "k1", java.sql.Types.VARCHAR)
				.param("credentialAt", if (deleted) null else at, java.sql.Types.TIMESTAMP).param("at", at)
				.param("succeeded", succeeded?.let(Timestamp::from), java.sql.Types.TIMESTAMP).param("failed", failed?.let(Timestamp::from), java.sql.Types.TIMESTAMP)
				.param("error", failed?.let { "vendor_unavailable" }, java.sql.Types.VARCHAR).param("admin", org.admin)
				.param("deletedAt", if (deleted) at else null, java.sql.Types.TIMESTAMP).param("deletedBy", if (deleted) org.admin else null, java.sql.Types.OTHER).update()
		}

	private fun sourceOf(body: JsonNode, vendorId: String): JsonNode =
		body.at("/vendors/items").toList().single { it.path("vendorId").asString() == vendorId }.path("seatSource")

	@Test
	@DisplayName("우선순위 표대로 권위를 낸다 — 연결 있음은 커넥터, 커넥터 플랜에 연결 없음은 수동 임시, 커넥터 없는 플랜은 수동")
	fun authority() {
		val org = organization()
		val copilot = register(org, "copilot", "copilot_business")
		val claude = register(org, "claude_team", "enterprise")
		val openai = register(org, "openai_biz", "business")
		val succeeded = Instant.parse("2026-09-21T00:00:00Z")
		val failed = Instant.parse("2026-09-22T00:00:00Z")
		val connectionId = connect(org, copilot, "copilot", """{"organization":"octo-org"}""", succeeded = succeeded, failed = failed)
		// 지운 연결은 보이지 않는다.
		connect(org, claude, "claude_enterprise", "{}", deleted = true)

		val settings = ok(org.tenant, "/settings")
		with(sourceOf(settings, copilot)) {
			assertThat(listOf(path("authority").asString(), path("provisional").asBoolean())).containsExactly("connector", false)
			assertThat(path("connector").path("capabilities").toList().map { it.asString() }).containsExactly("seat_list")
			assertThat(path("connector").path("supported").toList().map { it.asString() }).containsExactly("seat_list", "seat_release", "seat_restore")
			val connection = path("connection")
			assertThat(connection.path("connectionId").asString()).isEqualTo(connectionId.toString())
			assertThat(connection.path("settings").path("organization").asString()).isEqualTo("octo-org")
			assertThat(connection.path("credential").propertyNames().toList()).containsExactlyInAnyOrder("configured", "updatedAt")
			assertThat(connection.at("/check/status").asString()).isEqualTo("verified")
			// 마지막 시도가 실패면 failing — 마지막 성공 값은 남는다.
			assertThat(listOf(connection.at("/sync/status").asString(), connection.at("/sync/lastSucceededAt").asString(), connection.at("/sync/lastFailedAt").asString(),
				connection.at("/sync/lastError").asString())).containsExactly("failing", succeeded.toString(), failed.toString(), "vendor_unavailable")
		}
		with(sourceOf(settings, claude)) {
			assertThat(listOf(path("authority").asString(), path("provisional").asBoolean(), path("connector").path("connectorId").asString()))
				.containsExactly("manual", true, "claude_enterprise")
			assertThat(path("connection").isNull).isTrue()
		}
		with(sourceOf(settings, openai)) {
			assertThat(listOf(path("authority").asString(), path("provisional").asBoolean())).containsExactly("manual", false)
			assertThat(path("connector").isNull).isTrue()
		}
		assertThat(settings.toString()).doesNotContain(ciphertext).doesNotContain("credential_key")
		JsonStructure.assertMatches("settings-response.example.json", settings)

		// 목록·상세도 같은 값이다.
		val snapshot = settings.at("/meta/snapshotId").asString()
		assertThat(sourceOf(ok(org.tenant, "/vendors?snapshotId=$snapshot"), copilot)).isEqualTo(sourceOf(settings, copilot))
		assertThat(ok(org.tenant, "/vendors/$copilot?snapshotId=$snapshot").at("/vendor/seatSource")).isEqualTo(sourceOf(settings, copilot))
	}

	@Test
	@DisplayName("다른 조직의 연결은 섞이지 않고, 성공한 동기화가 없는 연결은 pending 이다")
	fun boundary() {
		val org = organization()
		val other = organization()
		val mine = register(org, "gemini", "gemini_standard")
		val theirs = register(other, "gemini", "gemini_enterprise")
		connect(other, theirs, "gemini", """{"billingAccount":"b","order":"o","project":"p"}""")
		assertThat(sourceOf(ok(org.tenant, "/settings"), mine).let { it.path("authority").asString() to it.path("connection").isNull }).isEqualTo("manual" to true)
		with(sourceOf(ok(other.tenant, "/settings"), theirs)) {
			assertThat(path("authority").asString()).isEqualTo("connector")
			assertThat(path("connection").at("/sync/status").asString()).isEqualTo("pending")
			assertThat(path("connection").path("settings").propertyNames().toList()).containsExactly("billingAccount", "order", "project")
		}
	}
}
