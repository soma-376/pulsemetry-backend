package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.connector.vendor.AccountKind
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCompleteness
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import com.team376.pulsemetry.persistence.enrollment.management.ContractStatus
import com.team376.pulsemetry.persistence.enrollment.seat.ConnectionRecord
import com.team376.pulsemetry.persistence.enrollment.seat.MemberLink
import com.team376.pulsemetry.persistence.enrollment.seat.SeatSource
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState
import com.team376.pulsemetry.persistence.enrollment.seat.SyncStatus
import com.team376.pulsemetry.persistence.enrollment.seat.VendorConnections
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 기준 시각의 좌석 원장 읽기 (ADR 0048 §7). **좌석은 snapshot 에 복제하지 않는다** — 원장은 판마다 이력(`seat_assignment_events`)을 남기므로,
 * 기준 시각(구성원 화면은 snapshot 의 asOf, 현재 상태 목록은 토큰의 asOf) 이전의 마지막 판으로 그 시각의 좌석을 다시 세운다. 같은 기준 시각의
 * 목록·다음 페이지·상세가 같은 좌석을 본다. 벤더 활동 시각과 연결의 동기화 상태는 판이 없는 현재 값이다.
 */
class SeatLedgerReader(
	private val source: JdbcClient,
	private val clickHouse: ClickHouseSourceReader,
	private val mapper: ObjectMapper,
	private val boundaries: RetentionBoundaryReader,
	private val completeness: SnapshotCompleteness,
) {
	/** 기준 시각의 등록 제품과 그 계약. */
	data class Product(val vendorId: String, val kind: String, val displayName: String, val contract: VendorContract?, val contractStatus: ContractStatus) {
		val plan: String? get() = contract?.planId
		val connectorPlan: Boolean get() = ConnectorDescriptors.forPlan(kind, plan) != null
		fun tier(id: String?): VendorTier? = id?.let { tierId -> contract?.tiers?.firstOrNull { it.tierId == tierId } }
	}

	/** 기준 시각의 좌석 한 자리(그 시각의 마지막 판). */
	data class SeatAt(
		val id: UUID,
		val vendorId: String,
		val account: String,
		val accountKind: AccountKind,
		val state: SeatState,
		val source: SeatSource,
		val memberId: UUID?,
		val memberLink: MemberLink?,
		val tierId: String?,
		val vendorTier: String?,
		val assignedAt: Instant,
		val releaseEffectiveOn: LocalDate?,
		val releasedAt: Instant?,
		val vendorLastActivityAt: Instant?,
		val note: String?,
		val version: Long,
	)

	data class Ledger(
		val asOf: Instant,
		val products: List<Product>,
		val seats: List<SeatAt>,
		val connections: Map<String, ConnectionRecord>,
		/** 관측 제품 → 카탈로그 제품(ADR 0044). */
		val mapping: Map<String, String>,
	) {
		val observableKinds: Set<String> get() = mapping.values.toSet()
		fun product(vendorId: String): Product? = products.firstOrNull { it.vendorId == vendorId }
	}

	data class RosterEntry(val id: UUID, val account: String, val status: String, val teamIds: List<UUID>)

	fun ledger(tenant: UUID, asOf: Instant): Ledger {
		val at = Timestamp.from(asOf)
		val products = source.sql("""
			SELECT v.vendor_id, v.kind, c.display_name, c.contract::text AS contract FROM enrollment.managed_vendors v
			JOIN LATERAL (SELECT display_name, contract, archived FROM enrollment.vendor_contract_versions
			              WHERE tenant_id = v.tenant_id AND vendor_id = v.vendor_id AND recorded_at <= :as_of ORDER BY version DESC LIMIT 1) c ON true
			WHERE v.tenant_id = :tenant AND v.created_at <= :as_of AND NOT c.archived ORDER BY v.vendor_id
		""").param("tenant", tenant).param("as_of", at).query { rs, _ ->
			val contract = rs.getString("contract")?.let { mapper.readValue(it, VendorContract::class.java) }
			Product(rs.getString("vendor_id"), rs.getString("kind"), rs.getString("display_name"), contract,
				ContractStatus.at(contract?.effectiveFrom?.let(LocalDate::parse), contract?.effectiveTo?.let(LocalDate::parse), asOf))
		}.list()
		val seats = source.sql("""
			SELECT s.id, s.vendor_id, s.account, s.account_kind, s.vendor_last_activity_at, e.version, e.state, e.source, e.member_id, e.member_link,
			       e.tier_id, e.vendor_tier, e.release_effective_on, e.note, e.assigned_at, e.recorded_at
			FROM enrollment.seat_assignments s
			JOIN LATERAL (SELECT * FROM enrollment.seat_assignment_events e WHERE e.tenant_id = s.tenant_id AND e.seat_assignment_id = s.id AND e.recorded_at <= :as_of
			              ORDER BY e.version DESC LIMIT 1) e ON true
			WHERE s.tenant_id = :tenant ORDER BY s.id
		""").param("tenant", tenant).param("as_of", at).query { rs, _ ->
			val state = SeatState.of(rs.getString("state"))
			SeatAt(rs.getObject("id", UUID::class.java), rs.getString("vendor_id"), rs.getString("account"),
				AccountKind.entries.first { it.wire == rs.getString("account_kind") }, state, SeatSource.of(rs.getString("source")),
				rs.getObject("member_id", UUID::class.java), MemberLink.of(rs.getString("member_link")), rs.getString("tier_id"), rs.getString("vendor_tier"),
				rs.getTimestamp("assigned_at").toInstant(), rs.getObject("release_effective_on", LocalDate::class.java),
				rs.getTimestamp("recorded_at").toInstant().takeIf { state == SeatState.RELEASED },
				rs.getTimestamp("vendor_last_activity_at")?.toInstant(), rs.getString("note"), rs.getLong("version"))
		}.list()
		val mapping = source.sql("SELECT observed_product, product_id FROM enrollment.vendor_catalog_observed_products ORDER BY observed_product")
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
		return Ledger(asOf, products, seats, VendorConnections.active(source, mapper, tenant).associateBy { it.vendorId }, mapping)
	}

	/** 현재 로스터(활성·정지)와 기준 시각의 소속 팀. 구성원 화면이 아닌 현재 상태 목록이 쓴다. */
	fun roster(tenant: UUID, asOf: Instant): Map<UUID, RosterEntry> = source.sql("""
		SELECT m.id, m.email, m.status::text AS status,
		       ARRAY(SELECT DISTINCT tm.team_id FROM enrollment.team_memberships tm
		             WHERE tm.member_id = m.id AND tm.joined_at <= :as_of AND (tm.left_at IS NULL OR tm.left_at > :as_of) ORDER BY tm.team_id) AS teams
		FROM enrollment.members m WHERE m.tenant_id = :tenant AND m.status IN ('active', 'suspended')
	""").param("tenant", tenant).param("as_of", Timestamp.from(asOf)).query { rs, _ ->
		RosterEntry(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("status"), (rs.getArray("teams").array as Array<*>).map { it as UUID })
	}.list().associateBy { it.id }

	fun teamNames(tenant: UUID): Map<UUID, String> = source.sql("SELECT id, name FROM enrollment.teams WHERE tenant_id = :tenant").param("tenant", tenant)
		.query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list().toMap()

	/** 구성원·카탈로그 제품별 마지막 사용(기준 시각 전, 삭제 경계 뒤). 매핑 없는 관측은 넣지 않는다 — 어느 좌석에도 귀속하지 않는다. */
	fun lastUse(tenant: UUID, asOf: Instant, mapping: Map<String, String>): Map<Pair<UUID, String>, Instant> {
		if (mapping.isEmpty()) return emptyMap()
		val deletedBefore = boundaries.read(tenant).deletedBefore
		val params = linkedMapOf(
			"tenant" to ClickHouseParam.string(tenant.toString()),
			"as_of" to ClickHouseParam.instant(asOf),
			"observed" to ClickHouseParam.stringArray(mapping.keys.toList()),
			"catalog" to ClickHouseParam.stringArray(mapping.keys.map { mapping.getValue(it) }),
		)
		deletedBefore?.let { params["deleted_before"] = ClickHouseParam.instant(it) }
		val sql = """
			SELECT assumeNotNull(member_id) AS m, transform(product, {observed:Array(String)}, {catalog:Array(String)}, '') AS kind, toString(max(source_time)) AS t
			FROM telemetry_events FINAL
			WHERE tenant_id = {tenant:String} AND record_status = 'active' AND signal = 'log' AND isNotNull(member_id)
			  AND source_time < {as_of:DateTime64(9, 'UTC')}${if (deletedBefore != null) " AND source_time >= {deleted_before:DateTime64(9, 'UTC')}" else ""}
			GROUP BY m, kind
			HAVING kind != ''
			SETTINGS do_not_merge_across_partitions_select_final = 1
		""".trimIndent()
		return clickHouse.query(sql, params) { row ->
			val member = runCatching { UUID.fromString(row.path("m").asString()) }.getOrNull()
			member?.let { (it to row.path("kind").asString()) to Instant.parse(row.path("t").asString().replace(' ', 'T') + "Z") }
		}.filterNotNull().toMap()
	}

	/**
	 * 기준 시각의 서울 날짜 전날까지 [days] 일 동안 쓴 (구성원, 카탈로그 제품)과 그 창이 모두 완전한가(ADR 0042) — 설정의 활성 좌석(7일)이 쓴다.
	 * 창은 설정의 벤더 관측 지표(ADR 0044)와 같다.
	 */
	fun activity(tenant: UUID, asOf: Instant, days: Int, mapping: Map<String, String>): Pair<Set<Pair<UUID, String>>, Boolean> {
		val today = asOf.atZone(QueryReader.SEOUL).toLocalDate()
		val dates = (1..days).map { today.minusDays(it.toLong()) }
		val deletedBefore = boundaries.read(tenant).deletedBefore
		val complete = completeness.completeDates(tenant, dates, QueryReader.SEOUL, asOf, deletedBefore).containsAll(dates)
		if (mapping.isEmpty()) return emptySet<Pair<UUID, String>>() to complete
		val params = linkedMapOf(
			"tenant" to ClickHouseParam.string(tenant.toString()),
			"from" to ClickHouseParam.instant(today.minusDays(days.toLong()).atStartOfDay(QueryReader.SEOUL).toInstant()),
			"until" to ClickHouseParam.instant(today.atStartOfDay(QueryReader.SEOUL).toInstant()),
			"observed" to ClickHouseParam.stringArray(mapping.keys.toList()),
			"catalog" to ClickHouseParam.stringArray(mapping.keys.map { mapping.getValue(it) }),
		)
		deletedBefore?.let { params["deleted_before"] = ClickHouseParam.instant(it) }
		val sql = """
			SELECT DISTINCT assumeNotNull(member_id) AS m, transform(product, {observed:Array(String)}, {catalog:Array(String)}, '') AS kind
			FROM telemetry_events FINAL
			WHERE tenant_id = {tenant:String} AND record_status = 'active' AND signal = 'log' AND isNotNull(member_id)
			  AND source_time >= {from:DateTime64(9, 'UTC')} AND source_time < {until:DateTime64(9, 'UTC')}${if (deletedBefore != null) " AND source_time >= {deleted_before:DateTime64(9, 'UTC')}" else ""}
			  AND kind != ''
			SETTINGS do_not_merge_across_partitions_select_final = 1
		""".trimIndent()
		val used = clickHouse.query(sql, params) { row -> runCatching { UUID.fromString(row.path("m").asString()) }.getOrNull()?.let { it to row.path("kind").asString() } }
			.filterNotNull().toSet()
		return used to complete
	}

	/** 구성원별 설치(등록·폐기 시각). 관측 근거 — 설치가 없던 사람의 사용 없음은 관측이 아니다. */
	fun installations(tenant: UUID): Map<UUID, List<Pair<Instant, Instant?>>> =
		source.sql("SELECT member_id, created_at, revoked_at FROM enrollment.installations WHERE tenant_id = :tenant").param("tenant", tenant)
			.query { rs, _ -> rs.getObject(1, UUID::class.java) to (rs.getTimestamp(2).toInstant() to rs.getTimestamp(3)?.toInstant()) }.list()
			.groupBy({ it.first }, { it.second })

	/**
	 * 회수 검토 창 — 확정된(끝난 뒤 확정 대기 시간이 지난) 마지막 서울 날짜까지의 [days] 일과, 그중 완전한 날(ADR 0042).
	 * 아직 확정되지 않은 날은 완전할 수 없으므로 창에 넣지 않는다.
	 */
	fun reviewWindow(tenant: UUID, asOf: Instant, days: Int): Pair<List<LocalDate>, Set<LocalDate>> {
		val lastSettled = asOf.minus(completeness.settle).atZone(QueryReader.SEOUL).toLocalDate().minusDays(1)
		val dates = (0 until days).map { lastSettled.minusDays(it.toLong()) }
		return dates to completeness.completeDates(tenant, dates, QueryReader.SEOUL, asOf, boundaries.read(tenant).deletedBefore)
	}
}

/**
 * 좌석 원장의 판정 (ADR 0048 §7, 구성원 요청서 "숫자의 의미"). 입력은 한 기준 시각의 원장·관측이고 계산은 순수하다.
 *
 * **제품 단위 가용성** — 원장이 그 제품에 대해 비었거나 낡았으면 그 제품만 가용성을 낮춘다.
 *
 * | 등록 제품 | 가용성 | 사유 |
 * | --- | --- | --- |
 * | 활성 연결, 성공한 동기화 없음 | unavailable | `seat_sync_pending`(시도 전)·`seat_sync_failing`(실패만) |
 * | 활성 연결, 마지막 시도 실패 | partial | `seat_sync_failing` |
 * | 활성 연결, 마지막 성공이 [staleAfter] 보다 오래됨 | partial | `seat_sync_outdated` |
 * | 연결 없음, 기록된 좌석 없음 | unavailable | `seat_source_not_recorded` |
 * | 연결 없음, 커넥터가 있는 플랜(임시 기록) | partial | `seat_source_provisional` |
 * | 그 밖 | available | — |
 *
 * **회수 후보** — 배정(`assigned`) 좌석 중 다음을 **모두** 만족할 때만 후보다. 사용 이벤트가 없다는 것만으로 미사용을 확정하지 않는다.
 * 1. 구성원에 이어져 있고 그 구성원이 로스터(활성·정지)에 있다.
 * 2. 그 제품이 관측 가능하다 — 관측 제품 매핑이 있고(ADR 0044), 제품의 원장이 unavailable 이 아니다.
 * 3. 유휴 일수 ≥ 조직의 회수 기준. 유휴 일수 = 시작부터 기준 시각까지의 완전한 24시간 수, 시작 = 마지막 사용(텔레메트리·벤더 활동 중 늦은 것)과
 *    배정 시각 중 늦은 것(사용이 없으면 배정 시각).
 * 4. 관측이 충분하다 — 확정된 마지막 날까지의 [회수 기준]일이 모두 완전하고(ADR 0042), 기준 시각 앞 [회수 기준]일 동안 등록돼 폐기되지 않은
 *    그 구성원의 설치가 있다(설치가 없던 사람의 사용 없음은 관측이 아니다).
 */
class SeatAssessment(
	val ledger: SeatLedgerReader.Ledger,
	private val staleAfter: Duration,
	private val idleDays: Int,
	private val roster: Set<UUID>,
	private val lastUse: Map<Pair<UUID, String>, Instant>,
	private val installations: Map<UUID, List<Pair<Instant, Instant?>>>,
	private val windowComplete: Boolean,
) {
	data class ProductState(val product: SeatLedgerReader.Product, val availability: String, val reason: String?)

	/** 좌석 하나의 검토 결과. [reason] 은 후보가 아닌 까닭(후보면 null). */
	data class Review(val seat: SeatLedgerReader.SeatAt, val lastUsedAt: Instant?, val idleDays: Long?, val candidate: Boolean, val reason: String?)

	val products: List<ProductState> = ledger.products.map { product ->
		val connection = ledger.connections[product.vendorId]
		val (availability, reason) = when {
			connection != null -> when {
				connection.lastSyncSucceededAt == null ->
					Availability.UNAVAILABLE to if (connection.syncStatus == SyncStatus.FAILING) FAILING else PENDING
				connection.syncStatus == SyncStatus.FAILING -> Availability.PARTIAL to FAILING
				SyncStatus.stale(connection.lastSyncSucceededAt, connection.lastSyncFailedAt, ledger.asOf, staleAfter) -> Availability.PARTIAL to OUTDATED
				else -> Availability.AVAILABLE to null
			}
			ledger.seats.none { it.vendorId == product.vendorId } -> Availability.UNAVAILABLE to NOT_RECORDED
			product.connectorPlan -> Availability.PARTIAL to PROVISIONAL
			else -> Availability.AVAILABLE to null
		}
		ProductState(product, availability, reason)
	}

	private val usable: Set<String> = products.filter { it.availability != Availability.UNAVAILABLE }.map { it.product.vendorId }.toSet()

	/** 기준 시각에 좌석을 차지한 자리(해제 예정·배정 대기 포함) 중 원장을 쓸 수 있는 제품의 것. */
	val held: List<SeatLedgerReader.SeatAt> = ledger.seats.filter { it.state.holds && it.vendorId in usable }

	val reviews: List<Review> = ledger.seats.filter { it.state == SeatState.ASSIGNED }.map(::review)

	val candidates: List<Review> = reviews.filter { it.candidate }.sortedWith(compareByDescending<Review> { it.idleDays }.thenBy { it.seat.id.toString() })

	fun review(seat: SeatLedgerReader.SeatAt): Review {
		val kind = ledger.product(seat.vendorId)?.kind
		val member = seat.memberId
		val used = listOfNotNull(member?.let { m -> kind?.let { lastUse[m to it] } }, seat.vendorLastActivityAt).maxOrNull()
		val start = listOfNotNull(used, seat.assignedAt).max()
		val idle = Duration.between(start, ledger.asOf).toDays().coerceAtLeast(0)
		val reason = when {
			seat.state != SeatState.ASSIGNED -> "not_assigned"
			member == null || member !in roster -> "seat_unlinked"
			seat.vendorId !in usable -> "seat_source_unavailable"
			kind !in ledger.observableKinds -> "product_unobservable"
			idle < idleDays -> "in_use"
			!windowComplete || !observed(member) -> "observation_incomplete"
			else -> null
		}
		val observable = reason == null || reason == "in_use" || reason == "observation_incomplete"
		return Review(seat, used, idle.takeIf { observable }, reason == null, reason)
	}

	/** 기준 시각 앞 [idleDays] 일 동안 수집하던 그 구성원의 설치가 있는가. */
	private fun observed(member: UUID): Boolean = observedBetween(member, ledger.asOf.minus(Duration.ofDays(idleDays.toLong())), ledger.asOf)

	/** [from, until) 내내 등록돼 있고 폐기되지 않은 그 구성원의 설치가 있는가(회수 검토를 읽은 판정에서만 참일 수 있다). */
	fun observedBetween(member: UUID, from: Instant, until: Instant): Boolean =
		installations[member].orEmpty().any { (created, revoked) -> !created.isAfter(from) && (revoked == null || !revoked.isBefore(until)) }

	/** 구성원의 좌석 상태(구성원 요청서의 `seatState`). 좌석이 없다고 말하려면 모든 등록 제품의 원장을 쓸 수 있어야 한다. */
	fun seatState(member: UUID): String {
		val mine = ledger.seats.filter { it.memberId == member }
		return when {
			mine.any { it.state.holds && it.vendorId in usable } -> "assigned"
			mine.any { it.state == SeatState.RELEASED && (it.source == SeatSource.VENDOR_CONTROL || it.source == SeatSource.ADMIN_ACTION) } -> "reclaimed"
			products.isNotEmpty() && products.none { it.availability == Availability.UNAVAILABLE } -> "unassigned"
			else -> "unknown"
		}
	}

	/** 제품들의 가용성을 한 섹션으로 — 모두 unavailable 이면 unavailable, 하나라도 낮으면 partial(첫 사유), 등록 제품이 없으면 not_applicable. */
	fun section(): Pair<String, String?> = when {
		products.isEmpty() -> Availability.UNAVAILABLE to Availability.NOT_APPLICABLE
		products.all { it.availability == Availability.UNAVAILABLE } -> Availability.UNAVAILABLE to products.first().reason
		products.any { it.availability != Availability.AVAILABLE } -> Availability.PARTIAL to products.first { it.availability != Availability.AVAILABLE }.reason
		else -> Availability.AVAILABLE to null
	}

	/** 후보 목록의 가용성 — 배정 좌석 중 판정하지 못한 것(미연결·관측 불가·관측 부족·원장 없음)이 있으면 partial `observation_incomplete`. */
	fun candidateSection(): Pair<String, String?> {
		val (availability, reason) = section()
		if (availability == Availability.UNAVAILABLE) return availability to reason
		val undecided = reviews.any { it.reason in UNDECIDED }
		return if (undecided || availability == Availability.PARTIAL) Availability.PARTIAL to (if (undecided) OBSERVATION_INCOMPLETE else reason) else Availability.AVAILABLE to null
	}

	companion object {
		const val PENDING = "seat_sync_pending"
		const val FAILING = "seat_sync_failing"
		const val OUTDATED = "seat_sync_outdated"
		const val NOT_RECORDED = "seat_source_not_recorded"
		const val PROVISIONAL = "seat_source_provisional"
		const val OBSERVATION_INCOMPLETE = "observation_incomplete"
		/** 좌석 회수 실행은 아직 없다 — 후보는 검토용이다. */
		const val CONTROL_UNAVAILABLE = "vendor_control_unavailable"
		private val UNDECIDED = setOf("seat_unlinked", "seat_source_unavailable", "product_unobservable", "observation_incomplete")
	}
}
