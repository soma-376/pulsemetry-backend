package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant

/**
 * **공통 계산기** — ready snapshot 의 payload(`dashboard_cache.snapshot_usage`) 하나에서 축별 [UsageTotals] 를 센다.
 * 원천 `FINAL` 을 다시 읽지 않는다. 같은 snapshot 을 읽는 모든 endpoint 가 이것을 쓰므로 여러 번 불러도 값이 갈라지지 않는다.
 *
 * 축은 [Axis] 의 키 식이다. 조직 축은 키가 없어 항상 한 행이다(행이 없으면 카운터가 전부 0 인 한 행).
 * 기간은 현재·비교 중 하나다 — 겹친 기간의 관측은 한 행이 두 기간에 모두 세인다.
 */
class UsageAggregator(
	private val clickHouse: ClickHouseCacheClient,
) {

	enum class Side(val column: String) { CURRENT("in_current"), PREVIOUS("in_previous") }

	/** 그룹 키. SQL 식과 결과 열 이름. `null` 키는 그 축의 "없음"(예: 미배분 팀, 미식별 구성원)이다. */
	enum class Axis(val expressions: List<String>) {
		ORGANIZATION(emptyList()),
		TEAM(listOf("team_id_as_of")),
		MODEL(listOf("model_id")),
		MEMBER(listOf("member_id")),
		DAY(listOf("toString(toDate(source_time, {tz:String}))")),
		TEAM_MODEL(listOf("team_id_as_of", "model_id")),
		TEAM_DAY(listOf("team_id_as_of", "toString(toDate(source_time, {tz:String}))")),
		MEMBER_MODEL(listOf("member_id", "model_id")),
	}

	/** 한 팀으로 좁히는 조건. [teamId] 가 null 이면 미배분(`team_id_as_of IS NULL`)이다. */
	data class TeamScope(val teamId: String?)

	fun totals(
		snapshot: SnapshotManifestStore.Manifest,
		side: Side,
		axis: Axis,
		team: TeamScope? = null,
	): Map<List<String?>, UsageTotals> {
		val keys = axis.expressions.mapIndexed { index, expression -> "$expression AS k$index" }
		val select = (keys + COUNTERS).joinToString(",\n    ")
		val groupBy = if (axis.expressions.isEmpty()) "" else "GROUP BY " + axis.expressions.indices.joinToString(", ") { "k$it" }
		val sql = """
			SELECT
			    $select
			FROM snapshot_usage
			WHERE tenant_id = {tenant:String} AND snapshot_id = {snapshot:String} AND build_id = {build:String} AND ${side.column}${teamCondition(team)}
			$groupBy
		""".trimIndent()
		val params = buildMap {
			put("tenant", ClickHouseParam.string(snapshot.tenantId.toString()))
			put("snapshot", ClickHouseParam.string(snapshot.snapshotId))
			put("build", ClickHouseParam.string(snapshot.buildId.toString()))
			put("tz", ClickHouseParam.string(snapshot.current.zone.id))
			team?.teamId?.let { put("team", ClickHouseParam.string(it)) }
		}
		return clickHouse.query(sql, params) { row -> axis.expressions.indices.map { text(row, "k$it") } to totals(row) }.toMap()
	}

	private fun teamCondition(team: TeamScope?): String = when {
		team == null -> ""
		team.teamId == null -> " AND isNull(team_id_as_of)"
		else -> " AND team_id_as_of = {team:String}"
	}

	private fun totals(row: JsonNode) = UsageTotals(
		usageRows = long(row, "usage_rows"),
		identifiedUsers = long(row, "identified_users"),
		unidentifiedRows = long(row, "unidentified_rows"),
		identifiedSessions = long(row, "identified_sessions"),
		sessionlessRows = long(row, "sessionless_rows"),
		inputUncached = component(row, "input_uncached"),
		output = component(row, "output"),
		cacheRead = component(row, "cache_read"),
		cacheWrite = component(row, "cache_write"),
		totalDerived = component(row, "total_derived"),
		apiTotalMissingRows = long(row, "api_total_missing_rows"),
		unverifiedRows = long(row, "unverified_rows"),
		semanticsProfiles = long(row, "semantics_profiles"),
		cost = text(row, "cost")?.let(::BigDecimal),
		unpricedRows = long(row, "unpriced_rows"),
		multiTeamRows = long(row, "multi_team_rows"),
		lastSourceTime = text(row, "last_source_time")?.let(Instant::parse),
	)

	private fun component(row: JsonNode, name: String) = UsageTotals.Component(text(row, name)?.toLong(), long(row, "${name}_missing"))

	private fun long(row: JsonNode, name: String): Long = row.path(name).asString().toLong()

	private fun text(row: JsonNode, name: String): String? = row.get(name)?.takeUnless { it.isNull }?.asString()

	private companion object {
		/** 조회 골격의 카운터. 합은 `sumOrNull` — 값이 전부 없으면 0 이 아니라 NULL 이다. */
		val COUNTERS = listOf(
			"count() AS usage_rows",
			"uniqExact(member_id) AS identified_users",
			"countIf(isNull(member_id)) AS unidentified_rows",
			"uniqExactIf((product, session_id_namespace, session_id), isNotNull(session_id)) AS identified_sessions",
			"countIf(isNull(session_id)) AS sessionless_rows",
			"sumOrNull(tokens_input_uncached) AS input_uncached",
			"countIf(isNull(tokens_input_uncached)) AS input_uncached_missing",
			"sumOrNull(tokens_output) AS output",
			"countIf(isNull(tokens_output)) AS output_missing",
			"sumOrNull(tokens_cache_read) AS cache_read",
			"countIf(isNull(tokens_cache_read)) AS cache_read_missing",
			"sumOrNull(tokens_cache_create) AS cache_write",
			"countIf(isNull(tokens_cache_create)) AS cache_write_missing",
			"sumOrNull(tokens_total_derived) AS total_derived",
			"countIf(isNull(tokens_total_derived)) AS total_derived_missing",
			"countIf(isNull(tokens_total_derived) OR isNull(tokens_input_uncached) OR isNull(tokens_output) " +
				"OR isNull(tokens_cache_read) OR isNull(tokens_cache_create)) AS api_total_missing_rows",
			"countIf(isNull(semantics_profile)) AS unverified_rows",
			"uniqExact(semantics_profile) AS semantics_profiles",
			"sumOrNull(cost_estimated_usd) AS cost",
			"countIf(isNull(cost_estimated_usd)) AS unpriced_rows",
			"countIf(has(quality_flags, 'multi_team_membership')) AS multi_team_rows",
			// 행이 없는 조직 축에서도 NULL 이 나오게 — max 는 빈 입력에 1970 을 낸다.
			"if(count() = 0, NULL, max(source_time)) AS last_source_time",
		)
	}
}
