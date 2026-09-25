package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import org.springframework.jdbc.core.simple.JdbcClient
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
