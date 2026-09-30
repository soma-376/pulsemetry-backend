package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDate
import java.util.UUID

/**
 * ready snapshot 의 고정 입력 중 사용량 payload 밖의 것 — 관측 일자와 팀 표시 (ADR 0023 §1·§2). 모두 build 때 고정된 복사본이다.
 */
class SnapshotReferences(
	private val clickHouse: ClickHouseCacheClient,
	private val cache: JdbcClient,
) {

	data class Team(val id: UUID, val name: String, val archived: Boolean)

	/** 관측 일자 — 사용량이 아닌 관측(generic·메트릭)까지 포함한 source_time 날짜. 수신 날짜가 아니다. */
	fun observedDates(snapshot: SnapshotManifestStore.Manifest): Set<LocalDate> =
		clickHouse.query(
			"SELECT DISTINCT toString(observed_date) AS d FROM snapshot_observed_days " +
				"WHERE tenant_id = {tenant:String} AND snapshot_id = {snapshot:String} AND build_id = {build:String}",
			mapOf(
				"tenant" to ClickHouseParam.string(snapshot.tenantId.toString()),
				"snapshot" to ClickHouseParam.string(snapshot.snapshotId),
				"build" to ClickHouseParam.string(snapshot.buildId.toString()),
			),
		) { LocalDate.parse(it.path("d").asString()) }.toSet()

	/** 완전 관측으로 판정해 build 때 고정한 날짜(ADR 0042). 없는 날짜는 완전하지 않다. */
	fun completeDates(snapshot: SnapshotManifestStore.Manifest): Set<LocalDate> =
		cache.sql("SELECT complete_date FROM dashboard_cache.snapshot_complete_days WHERE snapshot_id = :snapshot")
			.param("snapshot", snapshot.snapshotId)
			.query { rs, _ -> rs.getObject("complete_date", LocalDate::class.java) }
			.set()

	/** build 때의 로스터 한 사람. [role]·[status] 는 enrollment 의 값 그대로다. */
	data class RosterMember(
		val id: UUID,
		val account: String,
		val displayName: String?,
		val role: MemberRole,
		val status: String,
		val currentTeamIds: List<UUID>,
		val updatedAt: Instant,
	)

	fun roster(snapshot: SnapshotManifestStore.Manifest): List<RosterMember> =
		cache.sql(
			"SELECT member_id, account, display_name, role::text AS role, status::text AS status, current_team_ids, updated_at " +
				"FROM dashboard_cache.snapshot_members WHERE snapshot_id = :snapshot",
		)
			.param("snapshot", snapshot.snapshotId)
			.query { rs, _ ->
				RosterMember(
					id = rs.getObject("member_id", UUID::class.java),
					account = rs.getString("account"),
					displayName = rs.getString("display_name"),
					role = MemberRole.valueOf(rs.getString("role")),
					status = rs.getString("status"),
					currentTeamIds = (rs.getArray("current_team_ids").array as Array<*>).map { it as UUID },
					updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
				)
			}
			.list()

	/** 구성원별 마지막 사용 — build 의 기준 시각(asOf)까지, 팀과 무관한 사용량 행의 최댓값(snapshot 에 고정한 별도 결과). */
	fun lastUsed(snapshot: SnapshotManifestStore.Manifest): Map<String, Instant> =
		clickHouse.query(
			"SELECT member_id AS m, last_used_at AS t FROM snapshot_member_activity " +
				"WHERE tenant_id = {tenant:String} AND snapshot_id = {snapshot:String} AND build_id = {build:String}",
			mapOf(
				"tenant" to ClickHouseParam.string(snapshot.tenantId.toString()),
				"snapshot" to ClickHouseParam.string(snapshot.snapshotId),
				"build" to ClickHouseParam.string(snapshot.buildId.toString()),
			),
		) { it.path("m").asString() to Instant.parse(it.path("t").asString()) }.toMap()

	/** 로스터의 계정(이메일). 로스터에 없는 구성원은 빠진다. */
	fun accounts(snapshot: SnapshotManifestStore.Manifest, memberIds: Collection<UUID>): Map<UUID, String> =
		if (memberIds.isEmpty()) emptyMap() else
			cache.sql(
				"SELECT member_id, account FROM dashboard_cache.snapshot_members WHERE snapshot_id = :snapshot AND member_id = ANY(CAST(:ids AS uuid[]))",
			)
				.param("snapshot", snapshot.snapshotId)
				.param("ids", memberIds.joinToString(",", "{", "}"))
				.query { rs, _ -> rs.getObject("member_id", UUID::class.java) to rs.getString("account") }
				.list()
				.toMap()

	fun teams(snapshot: SnapshotManifestStore.Manifest): List<Team> =
		cache.sql("SELECT team_id, name, archived FROM dashboard_cache.snapshot_teams WHERE snapshot_id = :snapshot")
			.param("snapshot", snapshot.snapshotId)
			.query { rs, _ -> Team(rs.getObject("team_id", UUID::class.java), rs.getString("name"), rs.getBoolean("archived")) }
			.list()
}
