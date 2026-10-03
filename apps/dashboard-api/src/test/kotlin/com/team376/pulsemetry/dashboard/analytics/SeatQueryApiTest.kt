package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.connector.vendor.VendorSeat
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.config.AnalyticsConfig
import com.team376.pulsemetry.dashboard.config.DashboardApiProperties
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.PageRequest
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.JsonStructure
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger
import com.team376.pulsemetry.persistence.enrollment.seat.SeatSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 좌석 조회와 회수 후보 (ADR 0048 §7, 구성원 요청서 "숫자의 의미"). 기대값은 ADR 의 판정 표와 요청서의 문장에서 쓴다:
 * 후보는 배정·구성원에 이어짐·관측 가능한 제품·유휴 일수 ≥ 회수 기준(14일)·관측 충분(검토 창이 완전하고 그 구성원의 설치가 있음)을 모두 만족할 때만이고,
 * 사용 이벤트가 없다는 것만으로 후보가 되지 않는다. 좌석 값은 원장을 기준 시각으로 다시 세운 것이다(snapshot 에 복제하지 않는다).
 *
 * 원장은 enrollment 의 저장 연산(SeatLedger)으로 채운다 — 이 앱은 읽기만 한다. 시각은 지금 기준의 상대값이다(검토 창은 확정된 날만 쓴다).
 */
class SeatQueryApiTest : AbstractDashboardApiTest() {

	@Autowired private lateinit var properties: DashboardApiProperties
	@Autowired private lateinit var frames: AnalyticsFrames
	@Autowired private lateinit var aggregator: UsageAggregator
	@Autowired private lateinit var references: SnapshotReferences
	@Autowired private lateinit var snapshots: com.team376.pulsemetry.dashboard.snapshot.SnapshotService
	@Autowired private lateinit var codec: PageCursorCodec
	@Autowired private lateinit var tokens: CurrentStateTokens
	@Autowired private lateinit var source: JdbcClient
	@Autowired private lateinit var seatService: SeatService

	@BeforeEach
	fun backfillDone() { SourceFixtures.completeBackfill() }

	private val now: Instant = Instant.now()
	private fun ago(days: Long, hours: Long = 0): Instant = now.minus(Duration.ofDays(days)).minus(Duration.ofHours(hours))

	private val dataSource = DriverManagerDataSource(DashboardTestStores.postgres.jdbcUrl, DashboardTestStores.postgres.username, DashboardTestStores.postgres.password)
	private val jdbc = JdbcClient.create(dataSource)
	private val manager = DataSourceTransactionManager(dataSource)
	private fun ledger(at: Instant) = SeatLedger(jdbc, manager, Clock.fixed(at, ZoneOffset.UTC))

	private fun get(tenant: UUID, path: String, role: Role = Role.ADMIN): HttpResponse<String> = http.send("/api/v1/organizations/$tenant$path",
		headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), role))))
	private fun ok(tenant: UUID, path: String): JsonNode = get(tenant, path).let {
		assertThat(it.statusCode()).describedAs(it.body()).isEqualTo(200)
		DashboardHttp.json(it)
	}

	private data class Org(val tenant: UUID, val admin: UUID, val members: Map<String, UUID>, val claude: String, val cursor: String?, val seats: Map<String, UUID>)

	private fun register(tenant: UUID, admin: UUID, kind: String, plan: String, seats: Int, effectiveFrom: String = "2026-01-01"): String {
		val id = "$kind-${UUID.randomUUID()}"
		DashboardTestStores.writer.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,:id,:kind,'manual',:at)")
			.param("t", tenant).param("id", id).param("kind", kind).param("at", Timestamp.from(ago(90))).update()
		val contract = """{"version":1,"planId":"$plan","effectiveFrom":"$effectiveFrom","effectiveTo":null,"termNote":null,
			"tiers":[{"tierId":"tier-$kind","label":"Standard","seats":$seats,"monthlyFeePerSeatUsd":"30"}],"monthlySeatFeeUsd":"${30 * seats}","confirmedAt":"2026-01-01T00:00:00Z","confirmedBy":"$admin"}"""
		DashboardTestStores.writer.sql("INSERT INTO enrollment.vendor_contract_versions (tenant_id,vendor_id,version,display_name,contract,recorded_at,recorded_by) VALUES (:t,:id,1,:name,CAST(:c AS jsonb),:at,:admin)")
			.param("t", tenant).param("id", id).param("name", "$kind 계약").param("c", contract).param("at", Timestamp.from(ago(90))).param("admin", admin).update()
		return id
	}

	/** 60일 전부터 지금까지 손실 없이 수집한 설치. */
	private fun installed(tenant: UUID, member: UUID) {
		val installation = SourceFixtures.insertInstallation(tenant, member)
		SourceFixtures.setInstallationTimes(installation, ago(60))
		SourceFixtures.insertSegment(installation, ago(60), now)
	}

	private fun used(tenant: UUID, member: UUID, at: Instant, name: String) =
		Event("$tenant-$name", at, memberId = member, sessionId = "session-$name", costEstimatedUsd = java.math.BigDecimal("1"), pricingVersion = "v1")

	/**
	 * Claude Team(수동 원천) 10석에 dana(사용 없음 — 후보), eli(2일 전 사용), fox(설치 없음), hana(14일 1시간 전 사용 — 경계 안), ivy(13일 23시간 전 — 경계 밖),
	 * 구성원이 없는 외부 계정, gil(관리자 조치로 해제). [withCursor] 이면 Cursor Enterprise(연결·2시간 전 성공) 5석에 admin 이 이은 octo.
	 */
	private fun organization(withCursor: Boolean = true): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = ago(90))
		val admin = SourceFixtures.insertMember(tenant, "admin-${UUID.randomUUID()}@example.test", role = "admin")
		SourceFixtures.insertManifest(tenant, 1, admin, signals = """{"logs":true,"metrics":true,"traces":true}""", activatedAt = ago(90))
		val names = listOf("dana", "eli", "fox", "hana", "ivy", "gil", "nobody")
		val members = names.associateWith { SourceFixtures.insertMember(tenant, "$it-${tenant.toString().take(8)}@example.test") }
		(names - "fox" - "nobody").forEach { installed(tenant, members.getValue(it)) }
		installed(tenant, admin)
		val claude = register(tenant, admin, "claude_team", "team", 10)
		val seats = mutableMapOf<String, UUID>()
		(names - "nobody").forEach { name -> seats[name] = ledger(ago(60)).assign(tenant, claude, admin, SeatSource.MANUAL, "$name-${tenant.toString().take(8)}@example.test").id }
		seats["outside"] = ledger(ago(60)).assign(tenant, claude, admin, SeatSource.MANUAL, "outside@elsewhere.example.test").id
		// gil 의 좌석은 관리자가 벤더 콘솔에서 회수했다고 확인했다(조치 확인은 원장의 전이 표 — 여기서는 판을 직접 남긴다).
		val gil = seats.getValue("gil")
		DashboardTestStores.writer.sql("UPDATE enrollment.seat_assignments SET state='released', source='admin_action', released_at=:at, version=2, updated_at=:at WHERE id=:id")
			.param("at", Timestamp.from(ago(5))).param("id", gil).update()
		DashboardTestStores.writer.sql("""INSERT INTO enrollment.seat_assignment_events (seat_assignment_id, version, tenant_id, state, source, member_id, member_link, actor_id, recorded_at, assigned_at)
			VALUES (:id, 2, :t, 'released', 'admin_action', :m, 'email_match', :admin, :at, :assigned)""")
			.param("id", gil).param("t", tenant).param("m", members.getValue("gil")).param("admin", admin).param("at", Timestamp.from(ago(5))).param("assigned", Timestamp.from(ago(60))).update()
		SourceFixtures.insertEvents(tenant,
			used(tenant, members.getValue("eli"), ago(2), "eli"),
			used(tenant, members.getValue("hana"), ago(14, 1), "hana"),
			used(tenant, members.getValue("ivy"), ago(13, 23), "ivy"),
		)
		var cursor: String? = null
		if (withCursor) {
			cursor = register(tenant, admin, "cursor", "cursor_enterprise", 5)
			val connection = UUID.randomUUID()
			DashboardTestStores.writer.sql("""INSERT INTO enrollment.vendor_connections (id, tenant_id, vendor_id, connector, settings, credential_ciphertext, credential_key_id,
					credential_updated_at, check_status, version, created_at, created_by, updated_at, updated_by)
				VALUES (:id, :t, :v, 'cursor_enterprise', '{}', 'opaque', 'k1', :at, 'verified', 1, :at, :admin, :at, :admin)""")
				.param("id", connection).param("t", tenant).param("v", cursor).param("at", Timestamp.from(ago(61))).param("admin", admin).update()
			val sync = ledger(ago(60)).startRun(tenant, connection, "worker-test", Duration.ofMinutes(5))!!
			ledger(ago(60)).applyListing(sync, listOf(VendorSeat("octo@example.test", vendorAccountRef = "user_octo")))
			val octo = ledger(ago(60)).seats(tenant, cursor).single()
			ledger(ago(59)).correct(tenant, octo.id, admin, octo.version, member = SeatLedger.MemberChoice.Member(admin))
			seats["octo"] = octo.id
			DashboardTestStores.writer.sql("UPDATE enrollment.vendor_connections SET last_sync_succeeded_at = :at WHERE id = :id")
				.param("at", Timestamp.from(ago(0, 2))).param("id", connection).update()
		}
		return Org(tenant, admin, members + ("admin" to admin), claude, cursor, seats)
	}

	private val period: String get() {
		val today = now.atZone(QueryReader.SEOUL).toLocalDate()
		return "startDate=${today.minusDays(8)}&endDate=${today.minusDays(2)}&timeZone=Asia/Seoul"
	}

	@Test
	@DisplayName("회수 후보 — 유휴 일수 ≥ 기준(경계 14일은 들고 13일은 빠진다)·관측 충분한 좌석만, 유휴 일수 내림차순이고 같은 토큰으로 다음 페이지를 잇는다")
	fun candidates() {
		val org = organization()
		val first = ok(org.tenant, "/seat-reclaim-candidates?limit=1")
		// 설치가 없는 fox·구성원이 없는 좌석·관측 매핑이 없는 Cursor 은 판정하지 못했다 — 목록에서 빼고 partial 이다.
		assertThat(first.at("/candidates/availability").asString() to first.at("/candidates/reason").asString()).isEqualTo("partial" to "observation_incomplete")
		assertThat(first.at("/candidates/data/totalCount").asInt()).isEqualTo(2)
		val dana = first.at("/candidates/data/items/0")
		assertThat(listOf(dana.path("seatAssignmentId").asString(), dana.path("memberId").asString(), dana.path("idleDays").asLong(), dana.path("canReclaim").asBoolean(),
			dana.path("reason").asString())).containsExactly(org.seats.getValue("dana").toString(), org.members.getValue("dana").toString(), 60L, false, "management_disabled")
		assertThat(dana.path("lastUsedAt").isNull).describedAs("사용 이력이 없으면 배정 시각부터 센다").isTrue()
		assertThat(dana.path("tierId").isNull && dana.path("estimatedMonthlySavingsUsd").isNull).isTrue()
		assertThat(dana.path("vendorAccount").asString()).startsWith("dana-")
		val snapshotId = first.at("/meta/snapshotId").asString()
		val second = ok(org.tenant, "/seat-reclaim-candidates?limit=1&cursor=${first.at("/candidates/data/nextCursor").asString()}")
		assertThat(second.at("/meta/snapshotId").asString()).isEqualTo(snapshotId)
		assertThat(second.at("/candidates/data/items").toList().map { it.path("memberId").asString() to it.path("idleDays").asLong() })
			.containsExactly(org.members.getValue("hana").toString() to 14L)
		assertThat(second.at("/candidates/data/nextCursor").isNull).isTrue()
		// 다른 조직에서 이 토큰은 409, 남의 cursor 는 400.
		assertThat(get(organization(withCursor = false).tenant, "/seat-reclaim-candidates?snapshotId=$snapshotId").statusCode()).isEqualTo(409)
	}

	@Test
	@DisplayName("검토 창에 완전하지 않은 날이 있으면 아무도 후보가 아니다 — 수집이 끊긴 설치 하나가 그날의 관측을 깨뜨린다")
	fun incompleteWindow() {
		val org = organization(withCursor = false)
		assertThat(ok(org.tenant, "/seat-reclaim-candidates").at("/candidates/data/totalCount").asInt()).isEqualTo(2)
		// 좌석이 없는 구성원의 설치가 5일 전부터 보고하지 않는다 — 그 뒤의 날은 조직 전체가 완전하지 않다.
		val quiet = SourceFixtures.insertInstallation(org.tenant, org.members.getValue("nobody"))
		SourceFixtures.setInstallationTimes(quiet, ago(60))
		SourceFixtures.insertSegment(quiet, ago(60), ago(5))
		val body = ok(org.tenant, "/seat-reclaim-candidates")
		assertThat(body.at("/candidates/data/totalCount").asInt()).isZero()
		assertThat(body.at("/candidates/availability").asString() to body.at("/candidates/reason").asString()).isEqualTo("partial" to "observation_incomplete")
		assertThat(ok(org.tenant, "/members/${org.members.getValue("dana")}/seats").at("/seats/0/reviewReason").asString()).isEqualTo("observation_incomplete")
	}

	@Test
	@DisplayName("구성원 좌석 — 좌석마다 원장 가용성·마지막 사용·유휴 일수·검토 사유·회수 가능 여부, 해제된 좌석은 뒤에")
	fun memberSeats() {
		val org = organization()
		fun seats(name: String) = ok(org.tenant, "/members/${org.members.getValue(name)}/seats").path("seats").toList()
		with(seats("dana").single()) {
			assertThat(listOf(path("state").asString(), path("source").asString(), path("ledgerAvailability").asString(), path("tierLabel").isNull,
				path("reclaimCandidate").asBoolean(), path("reviewReason").isNull, path("idleDays").asLong(), path("canReclaim").asBoolean(), path("reclaimReason").asString()))
				.containsExactly("assigned", "manual", "available", true, true, true, 60L, false, "management_disabled")
		}
		assertThat(seats("eli").single().let { it.path("reviewReason").asString() to it.path("idleDays").asLong() }).describedAs("2일 전 사용 — 완전한 24시간 둘").isEqualTo("in_use" to 2L)
		assertThat(seats("fox").single().let { it.path("reviewReason").asString() to it.path("reclaimCandidate").asBoolean() }).isEqualTo("observation_incomplete" to false)
		assertThat(seats("ivy").single().let { it.path("reviewReason").asString() to it.path("idleDays").asLong() }).isEqualTo("in_use" to 13L)
		assertThat(seats("gil").single().let { Triple(it.path("state").asString(), it.path("source").asString(), it.path("reviewReason").asString()) })
			.isEqualTo(Triple("released", "admin_action", "not_assigned"))
		with(seats("admin").single()) {
			assertThat(listOf(path("kind").asString(), path("memberLink").asString(), path("reviewReason").asString(), path("idleDays").isNull))
				.containsExactly("cursor", "admin", "product_unobservable", true)
		}
		assertThat(seats("nobody")).isEmpty()
		assertThat(get(org.tenant, "/members/${UUID.randomUUID()}/seats").statusCode()).isEqualTo(404)
		assertThat(get(org.tenant, "/members/not-a-uuid/seats").statusCode()).isEqualTo(404)
		assertThat(get(org.tenant, "/members/${org.members.getValue("dana")}/seats", Role.MEMBER).statusCode()).isEqualTo(403)
		assertThat(get(organization(withCursor = false).tenant, "/members/${org.members.getValue("dana")}/seats").statusCode()).describedAs("다른 조직의 구성원").isEqualTo(404)
	}

	@Test
	@DisplayName("구성원 화면 — 좌석 요약·구성원별 좌석 상태·후보 첫 페이지가 같은 기준 시각의 원장이고 후보 목록과 같다")
	fun membersDashboard() {
		val org = organization()
		val body = ok(org.tenant, "/members/dashboard?$period")
		with(body.at("/summary/seats")) {
			assertThat(path("availability").asString()).isEqualTo("available")
			// 계약 10 + 5, 보유 = Claude 6(해제된 gil 제외) + Cursor 1, 미배정 = (10-6) + (5-1). 미연결·관측 불가 좌석이 있어 기간 중 사용은 판정하지 않는다.
			assertThat(listOf(at("/data/contracted").asLong(), at("/data/assigned").asLong(), at("/data/unallocated").asLong(), at("/data/reclaimCandidates").asLong()))
				.containsExactly(15L, 7L, 8L, 2L)
			assertThat(listOf(at("/data/activeInPeriod").isNull, at("/data/inactiveAssigned").isNull, at("/data/estimatedMonthlySavingsUsd").isNull)).containsOnly(true)
		}
		val states = body.at("/members/items").toList().associate { it.path("memberId").asString() to it.path("seatState").asString() }
		assertThat(listOf("dana", "admin", "gil", "nobody").map { states.getValue(org.members.getValue(it).toString()) }).containsExactly("assigned", "assigned", "reclaimed", "unassigned")
		assertThat(body.at("/reclaimCandidates/data/items").toList().map { it.path("seatAssignmentId").asString() })
			.isEqualTo(ok(org.tenant, "/seat-reclaim-candidates").at("/candidates/data/items").toList().map { it.path("seatAssignmentId").asString() })
		JsonStructure.assertMatches("members-response.example.json", body)

		// 좌석을 기록하지 않은 제품(OpenAI)을 등록하면 그 제품만 unavailable — 요약은 partial, 좌석 없는 사람은 없다고 말할 수 없어 unknown 이다.
		register(org.tenant, org.admin, "openai_biz", "business", 3)
		val later = ok(org.tenant, "/members/dashboard?$period")
		assertThat(later.at("/summary/seats/availability").asString() to later.at("/summary/seats/reason").asString()).isEqualTo("partial" to "seat_source_not_recorded")
		assertThat(later.at("/members/items").toList().first { it.path("memberId").asString() == org.members.getValue("nobody").toString() }.path("seatState").asString())
			.isEqualTo("unknown")
	}

	@Test
	@DisplayName("기간 중 사용·비활성 — 모든 보유 좌석이 구성원에 이어지고 관측 가능한 제품이며 기간이 완전할 때만 센다")
	fun periodActivity() {
		val org = organization(withCursor = false)
		// 판정할 수 없는 좌석(미연결 외부 계정·설치 없는 fox)을 해제해 둔다.
		listOf("outside", "fox").forEach { name ->
			val seat = ledger(ago(1)).seat(org.tenant, org.seats.getValue(name))!!
			ledger(ago(1)).release(org.tenant, seat.id, org.admin, SeatSource.MANUAL, seat.version)
		}
		val eliInPeriod = now.atZone(QueryReader.SEOUL).toLocalDate().minusDays(3).atTime(10, 0).atZone(QueryReader.SEOUL).toInstant()
		SourceFixtures.insertEvents(org.tenant, used(org.tenant, org.members.getValue("eli"), eliInPeriod, "eli-period"))
		val seats = ok(org.tenant, "/members/dashboard?$period").at("/summary/seats/data")
		// 보유 = dana·eli·hana·ivy. 기간(8일 전~2일 전) 안에 쓴 것은 eli 하나.
		assertThat(listOf(seats.path("assigned").asLong(), seats.path("activeInPeriod").asLong(), seats.path("inactiveAssigned").asLong())).containsExactly(4L, 1L, 3L)
	}

	@Test
	@DisplayName("연결의 동기화가 실패 중이거나 기준보다 오래되면 그 제품의 원장은 낡았다고 표시한다 — 성공한 적이 없으면 없다")
	fun staleConnector() {
		val org = organization()
		fun seatSection() = ok(org.tenant, "/members/dashboard?$period").at("/summary/seats").let { it.path("availability").asString() to it.path("reason").asString() }
		fun connection(sql: String, at: Instant? = null) = DashboardTestStores.writer.sql("UPDATE enrollment.vendor_connections SET $sql WHERE vendor_id = :v")
			.param("v", org.cursor!!).also { if (at != null) it.param("at", Timestamp.from(at)) }.update()
		connection("last_sync_failed_at = :at, last_sync_error = 'vendor_unavailable'", ago(0, 1))
		assertThat(seatSection()).isEqualTo("partial" to "seat_sync_failing")
		connection("last_sync_failed_at = NULL, last_sync_error = NULL, last_sync_succeeded_at = :at", ago(2))
		assertThat(seatSection()).isEqualTo("partial" to "seat_sync_outdated")
		connection("last_sync_succeeded_at = NULL")
		assertThat(seatSection()).describedAs("Claude 는 여전히 쓸 수 있다").isEqualTo("partial" to "seat_sync_pending")
		val cursorSeat = ok(org.tenant, "/members/${org.members.getValue("admin")}/seats").at("/seats/0")
		assertThat(cursorSeat.path("ledgerAvailability").asString() to cursorSeat.path("ledgerReason").asString()).isEqualTo("unavailable" to "seat_sync_pending")
	}

	@Test
	@DisplayName("설정 — 벤더마다 좌석 수(보유·계약·미배정)와 보유 좌석 합계, 활성 좌석(7일)은 모든 보유 좌석을 판정할 수 있을 때만")
	fun settingsSeats() {
		val org = organization()
		val settings = ok(org.tenant, "/settings")
		assertThat(settings.at("/summary/assignedSeats").asLong()).isEqualTo(7)
		assertThat(ok(org.tenant, "/members/dashboard?$period").at("/summary/seats/data/assigned").asLong()).describedAs("구성원 화면과 같은 원장").isEqualTo(7)
		assertThat(settings.at("/summary/activeSeats7d").isNull).describedAs("미연결·관측 불가 좌석이 있다").isTrue()
		val vendors = settings.at("/vendors/items").toList().associateBy { it.path("vendorId").asString() }
		assertThat(vendors.getValue(org.claude).path("seats").let { listOf(it.path("availability").asString(), it.at("/data/assigned").asLong(), it.at("/data/contracted").asLong(), it.at("/data/unallocated").asLong()) })
			.containsExactly("available", 6L, 10L, 4L)
		assertThat(vendors.getValue(org.cursor!!).path("seats").let { listOf(it.path("availability").asString(), it.at("/data/assigned").asLong(), it.at("/data/unallocated").asLong()) })
			.containsExactly("available", 1L, 4L)
		JsonStructure.assertMatches("settings-response.example.json", settings)

		// 관측 가능한 제품뿐이어도 구성원에 잇지 않은 좌석(외부 계정)이 있으면 판정할 수 없다.
		val only = organization(withCursor = false)
		fun activeSeats7d() = ok(only.tenant, "/settings").at("/summary/activeSeats7d")
		fun release(name: String) {
			val seat = ledger(ago(1)).seat(only.tenant, only.seats.getValue(name))!!
			ledger(ago(1)).release(only.tenant, seat.id, only.admin, SeatSource.MANUAL, seat.version)
		}
		assertThat(activeSeats7d().isNull).describedAs("구성원 없는 좌석이 있다").isTrue()
		// 판정할 수 없는 좌석을 해제하면 지난 7일 안에 쓴 보유 좌석(eli)만 센다.
		listOf("outside", "fox").forEach(::release)
		assertThat(activeSeats7d().asLong()).isEqualTo(1)
		// eli 의 좌석도 해제하면 7일 안에 쓴 보유 좌석이 없다 — 창(기준일 전 7일)이 모두 완전하면 모름이 아니라 0 이다.
		// 창의 마지막 날(어제)은 그날 끝 + 확정 대기(테스트 설정 1시간, ADR 0042)가 지나야 완전하다 — 기준 시각이 서울 0시부터 1시간 안이면
		// 창이 아직 완전하지 않아 센 좌석이 없으니 null 이다. 같은 응답의 기준 시각으로 어느 쪽인지 정한다.
		release("eli")
		val released = ok(only.tenant, "/settings")
		val asOf = Instant.parse(released.at("/meta/asOf").asString())
		val yesterdaySettled = !asOf.isBefore(asOf.atZone(QueryReader.SEOUL).toLocalDate().atStartOfDay(QueryReader.SEOUL).toInstant().plus(Duration.ofHours(1)))
		val zero = released.at("/summary/activeSeats7d")
		if (yesterdaySettled) assertThat(zero.isNumber && zero.asLong() == 0L).describedAs(zero.toString()).isTrue()
		else assertThat(zero.isNull).describedAs("어제가 확정 대기 안 — $asOf").isTrue()
		// 좌석 없는 구성원의 설치가 5일 전부터 보고하지 않으면 창이 완전하지 않다 — 센 좌석이 없으니 0 이라고 말할 수 없다.
		val quiet = SourceFixtures.insertInstallation(only.tenant, only.members.getValue("nobody"))
		SourceFixtures.setInstallationTimes(quiet, ago(60))
		SourceFixtures.insertSegment(quiet, ago(60), ago(5))
		assertThat(activeSeats7d().isNull).isTrue()
		// 좌석을 기록하지 않은 제품은 그 제품만 unavailable 이다.
		val openai = register(only.tenant, only.admin, "openai_biz", "business", 3, effectiveFrom = "2099-01-01")
		fun openaiSeats() = ok(only.tenant, "/settings").at("/vendors/items").toList().single { it.path("vendorId").asString() == openai }.path("seats")
		assertThat(openaiSeats().let { it.path("availability").asString() to it.path("reason").asString() }).isEqualTo("unavailable" to "seat_source_not_recorded")
		// 좌석을 기록하면 쓸 수 있다 — 계약이 아직 시작하지 않았으므로 계약 좌석·미배정은 모른다.
		ledger(ago(1)).assign(only.tenant, openai, only.admin, SeatSource.MANUAL, "dana-${only.tenant.toString().take(8)}@example.test")
		assertThat(openaiSeats().let { listOf(it.path("availability").asString(), it.at("/data/assigned").asLong(), it.at("/data/contracted").isNull, it.at("/data/unallocated").isNull) })
			.containsExactly("available", 1L, true, true)

		// 모든 제품의 원장을 쓸 수 없으면 보유 좌석 합계도 모른다 — 0 이 아니다.
		val unrecorded = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(unrecorded, firstReceivedAt = ago(90))
		val owner = SourceFixtures.insertMember(unrecorded, "owner-${UUID.randomUUID()}@example.test", role = "admin")
		SourceFixtures.insertManifest(unrecorded, 1, owner, signals = """{"logs":true,"metrics":true,"traces":true}""", activatedAt = ago(90))
		register(unrecorded, owner, "openai_biz", "business", 3)
		with(ok(unrecorded, "/settings").path("summary")) {
			assertThat(listOf(path("assignedSeats").isNull, path("activeSeats7d").isNull)).containsOnly(true)
		}
	}

	@Test
	@DisplayName("개요 — 좌석 범위는 유효한 계약·쓸 수 있는 원장·관측 매핑이 있는 제품이고, 좌석료는 월 요금 × 일수 / 30 추정이며 회수 검토 수를 싣는다")
	fun overviewSeats() {
		val org = organization()
		val seats = ok(org.tenant, "/analytics/overview?$period").path("seats")
		// Cursor 은 관측 매핑이 없어 범위 밖 — partial.
		assertThat(listOf(seats.path("availability").asString(), seats.path("reason").asString(), seats.path("allocationMethod").asString()))
			.containsExactly("partial", "product_unobservable", "estimated_30_day")
		assertThat(seats.path("scopeVendorIds").toList().map { it.asString() }).containsExactly(org.claude)
		with(seats.path("current")) {
			assertThat(path("contractedSeats").asLong()).isEqualTo(10)
			assertThat(path("activeSeats").isNull).describedAs("미연결 좌석이 있다").isTrue()
			assertThat(path("monthlyFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("300")
			assertThat(path("allocatedFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("70")
			// 기간(8일 전~2일 전) 안의 claude_code 사용은 eli 의 1달러뿐이다.
			assertThat(path("equivalentCostUsd").asString().toBigDecimal()).isEqualByComparingTo("1")
			assertThat(path("efficiency").asDouble()).isCloseTo(1.0 / 70.0, Offset.offset(1e-9))
		}
		// 비교 기간(앞 주)도 완전해 그 기간 끝의 계약·원장으로 따로 계산한다 — 그 주의 claude_code 사용은 hana·ivy 의 2달러다.
		assertThat(seats.at("/previous/contractedSeats").asLong()).isEqualTo(10)
		assertThat(seats.at("/previous/equivalentCostUsd").asString().toBigDecimal()).isEqualByComparingTo("2")
		assertThat(seats.path("reclaimEstimate").isNull).describedAs("회수 가능 조건이 없다").isTrue()
		assertThat(seats.path("reclaimCandidates").asLong()).isEqualTo(2)
		assertThat(ok(org.tenant, "/members/dashboard?$period").at("/summary/seats/data/reclaimCandidates").asLong()).describedAs("구성원 화면과 같은 후보").isEqualTo(2)
		JsonStructure.assertMatches("overview-response.example.json", ok(org.tenant, "/analytics/overview?$period"))

		// 사용이 없는 완전한 기간의 환산가치는 0(효율 0)이고, 수집 근거가 없는 기간은 좌석 값을 만들지 않는다 — 환산가치를 모르면 비교할 수 없다.
		val today = now.atZone(QueryReader.SEOUL).toLocalDate()
		fun overviewSeats(from: Long, to: Long) = ok(org.tenant, "/analytics/overview?startDate=${today.minusDays(from)}&endDate=${today.minusDays(to)}&timeZone=Asia/Seoul").path("seats")
		with(overviewSeats(40, 34).path("current")) {
			assertThat(path("equivalentCostUsd").asString().toBigDecimal()).isEqualByComparingTo("0")
			assertThat(path("efficiency").asDouble()).isZero()
		}
		val beforeInstall = overviewSeats(75, 69)
		assertThat(beforeInstall.path("availability").asString() to beforeInstall.path("reason").asString()).isEqualTo("unavailable" to "source_not_available")
		assertThat(beforeInstall.path("current").isNull).isTrue()

		// 계약이 아직 시작하지 않은 제품(OpenAI)의 후보는 범위 밖이다 — 조직의 후보는 셋이지만 개요의 회수 검토 수는 범위(Claude)의 둘이다.
		val openai = register(org.tenant, org.admin, "openai_biz", "business", 3, effectiveFrom = "2099-01-01")
		ledger(ago(60)).assign(org.tenant, openai, org.admin, SeatSource.MANUAL, "dana-${org.tenant.toString().take(8)}@example.test")
		assertThat(ok(org.tenant, "/seat-reclaim-candidates").at("/candidates/data/totalCount").asInt()).isEqualTo(3)
		val scoped = ok(org.tenant, "/analytics/overview?$period").path("seats")
		assertThat(scoped.path("scopeVendorIds").toList().map { it.asString() }).containsExactly(org.claude)
		assertThat(scoped.path("reclaimCandidates").asLong()).isEqualTo(2)

		// 유효한 계약이 없으면 좌석 section 자체가 해당 없다.
		val none = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(none, firstReceivedAt = ago(90))
		val body = ok(none, "/analytics/overview?$period").path("seats")
		assertThat(body.path("availability").asString() to body.path("reason").asString()).isEqualTo("unavailable" to "not_applicable")
		assertThat(body.path("current").isNull).isTrue()
	}

	@Test
	@DisplayName("등록 제품의 좌석 — 구성원에 잇지 않은 좌석까지 계정 순서로, 같은 토큰으로 다음 페이지를 잇고, 없는 제품은 404")
	fun vendorSeats() {
		val org = organization()
		val first = ok(org.tenant, "/vendors/${org.claude}/seats?limit=4")
		assertThat(first.path("ledgerAvailability").asString() to first.path("ledgerReason").isNull).isEqualTo("available" to true)
		assertThat(first.at("/seats/totalCount").asInt()).describedAs("해제된 gil·외부 계정 포함 7").isEqualTo(7)
		val items = first.at("/seats/items").toList() + ok(org.tenant, "/vendors/${org.claude}/seats?limit=4&cursor=${first.at("/seats/nextCursor").asString()}").at("/seats/items").toList()
		assertThat(items.map { it.path("account").asString().substringBefore("-").substringBefore("@") })
			.containsExactly("dana", "eli", "fox", "gil", "hana", "ivy", "outside")
		with(items.single { it.path("account").asString().startsWith("outside") }) {
			assertThat(listOf(path("memberId").isNull, path("memberAccount").isNull, path("state").asString(), path("canReclaim").asBoolean())).containsExactly(true, true, "assigned", false)
		}
		with(items.single { it.path("account").asString().startsWith("gil") }) {
			assertThat(listOf(path("state").asString(), path("source").asString(), path("memberAccount").asString().startsWith("gil-"))).containsExactly("released", "admin_action", true)
		}
		assertThat(get(org.tenant, "/vendors/no-such-vendor/seats").statusCode()).isEqualTo(404)
		assertThat(get(organization(withCursor = false).tenant, "/vendors/${org.claude}/seats").statusCode()).describedAs("다른 조직의 제품").isEqualTo(404)
	}

	@Test
	@DisplayName("회수 가능 여부(ADR 0049) — 관리 기능이 있으면 연결된 제품은 벤더 제어, 없으면 관리자 조치이고 배정 좌석만·진행 중인 회수가 없을 때만이다")
	fun reclaimControl() {
		val org = organization()
		// 앱 조립과 같은 경로로 만든다 — 관리 기능이 켜진 배포.
		val service = AnalyticsConfig().membersService(true, properties, frames, aggregator, references, snapshots, codec, tokens, java.time.Clock.systemUTC(), source, seatService)
		val organization = requireNotNull(organizations.find(org.tenant))
		fun seat(name: String) = service.memberSeats(organization, org.members.getValue(name), null).seats.single()
		assertThat(seat("dana").let { Triple(it.canReclaim, it.reclaimReason, it.reclaimMethod) }).describedAs("Team 플랜 수동 원장").isEqualTo(Triple(true, null, "admin_action"))
		assertThat(seat("admin").let { Triple(it.canReclaim, it.reclaimReason, it.reclaimMethod) }).describedAs("Cursor 활성 연결").isEqualTo(Triple(true, null, "vendor_control"))
		assertThat(seat("gil").let { Triple(it.canReclaim, it.reclaimReason, it.reclaimMethod) }).isEqualTo(Triple(false, "not_assigned", null))
		val candidates = service.reclaimCandidates(organization, PageRequest(10, null), null).candidates.data!!.items
		assertThat(candidates.map { Triple(it.canReclaim, it.reason, it.reclaimMethod) }).containsOnly(Triple(true, null, "admin_action"))
		val today = now.atZone(QueryReader.SEOUL).toLocalDate()
		val dashboard = service.dashboard(organization, org.admin, com.team376.pulsemetry.dashboard.request.ComparedPeriod(
			com.team376.pulsemetry.dashboard.request.DatePeriod(today.minusDays(8), today.minusDays(2), QueryReader.SEOUL), com.team376.pulsemetry.dashboard.request.CompareMode.NONE))
		assertThat(dashboard.capabilities.let { it.reclaimSeats to it.restoreSeats }).isEqualTo(true to true)

		// dana 의 좌석에 끝나지 않은 회수(관리자 조치 대기)가 있다 — 다시 회수할 수 없다.
		val operation = UUID.randomUUID()
		DashboardTestStores.writer.sql("INSERT INTO enrollment.operations (id, tenant_id, kind, status, requested_by, created_at) VALUES (:id, :t, 'seat_reclaim', 'awaiting_admin_action', :admin, now())")
			.param("id", operation).param("t", org.tenant).param("admin", org.admin).update()
		DashboardTestStores.writer.sql("""INSERT INTO enrollment.operation_targets (operation_id, target_id, position, status, action)
			VALUES (:id, :seat, 0, 'awaiting_admin_action', 'release_in_vendor_console')""").param("id", operation).param("seat", org.seats.getValue("dana").toString()).update()
		DashboardTestStores.writer.sql("""INSERT INTO enrollment.seat_controls (operation_id, seat_assignment_id, tenant_id, action, method, requested_at)
			VALUES (:id, :seat, :t, 'release', 'admin_action', now())""").param("id", operation).param("seat", org.seats.getValue("dana")).param("t", org.tenant).update()
		assertThat(seat("dana").let { it.canReclaim to it.reclaimReason }).isEqualTo(false to "control_in_progress")
		assertThat(seat("dana").lastControl).describedAs("새로고침 뒤에도 그 작업을 찾는다").isEqualTo(SeatControlRef(operation.toString(), "seat_reclaim"))
		assertThat(seat("eli").lastControl).isNull()
		assertThat(service.reclaimCandidates(organization, PageRequest(10, null), null).candidates.data!!.items
			.single { it.seatAssignmentId == org.seats.getValue("dana").toString() }.let { it.canReclaim to it.reason }).isEqualTo(false to "control_in_progress")
	}
}
