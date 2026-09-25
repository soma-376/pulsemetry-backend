package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import java.time.Instant

/**
 * snapshot 복사의 ClickHouse 문장 (ADR 0023 §1). 캐시 계정이 캐시 DB 에서 실행하고, 원본은 [sourceDatabase] 로 한정해 읽는다.
 *
 * ## 한 번 읽는다
 *
 * [intake] 는 `INSERT INTO snapshot_intake SELECT …` **한 문장**이다. 두 분석 테이블을 각각 `FINAL` 로 한 번씩 읽어(`UNION ALL`)
 * 입구로 보내고, 구체화 뷰 둘이 같은 블록을 사용량 행과 관측 일자로 가른다.
 *
 * - 범위: tenant, 현재 ∪ 비교 기간(`source_time` 반개방 구간 둘의 합 — 겹치면 관측은 한 번), 삭제 경계 이후, 최신 행이 active.
 *   조건은 전부 `FINAL` 뒤에 적용된다(ADR 0020 §7) — excluded 최신 행 아래의 옛 active 행을 되살리지 않는다.
 * - 사용량 대표 행: `signal = 'log'`·`event_type = 'model.response.usage'`·`usage_role = 'primary'`·`mapping_status = 'mapped'`
 *   (ADR 0020 §7). 그 밖의 관측(generic·진단·메트릭 point)은 날짜의 근거일 뿐 사용량 열은 기본값으로 보낸다.
 * - 관측 일자는 `toDate(source_time, 조회 시간대)` 다. 수신 날짜가 아니다.
 *
 * [memberActivity] 는 기간 밖의 고정 입력(asOf 까지 팀과 무관한 마지막 사용)이다. 관측 일자 집합과는 별개의 조회다.
 */
class SnapshotCopySql(
	private val sourceDatabase: String,
	private val resolution: ModelResolution,
) {

	init {
		require(ClickHouseConnection.IDENTIFIER.matches(sourceDatabase)) { "원천 DB 이름이 식별자 형식이 아니다: $sourceDatabase" }
	}

	data class Statement(val sql: String, val params: Map<String, ClickHouseParam>)

	data class Scope(
		val tenantId: String,
		val snapshotId: String,
		val buildId: String,
		val current: DatePeriod,
		val previous: DatePeriod?,
		val deletedBefore: Instant?,
		val purgeAfter: Instant,
		val asOf: Instant,
	)

	fun intake(scope: Scope): Statement {
		val params = common(scope)
		params["tz"] = ClickHouseParam.string(scope.current.zone.id)
		params["resolution"] = ClickHouseParam.string(resolution.version)
		val model = resolution.sql(params)
		val inCurrent = "(source_time >= {current_from:DateTime64(9, 'UTC')} AND source_time < {current_until:DateTime64(9, 'UTC')})"
		val inPrevious = if (scope.previous == null) "false" else
			"(source_time >= {previous_from:DateTime64(9, 'UTC')} AND source_time < {previous_until:DateTime64(9, 'UTC')})"
		params["current_from"] = ClickHouseParam.instant(scope.current.from)
		params["current_until"] = ClickHouseParam.instant(scope.current.until)
		scope.previous?.let {
			params["previous_from"] = ClickHouseParam.instant(it.from)
			params["previous_until"] = ClickHouseParam.instant(it.until)
		}
		val where = "tenant_id = {tenant:String} AND record_status = 'active' AND ($inCurrent OR $inPrevious)${boundary(scope)}"

		val sql = """
			INSERT INTO snapshot_intake ($INTAKE_COLUMNS)
			SELECT
			    tenant_id, {snapshot:String}, {build:String},
			    ($USAGE_CONDITION) AS is_usage,
			    'telemetry_events', toDate(source_time, {tz:String}),
			    observation_id, installation_id, source_time, $inCurrent, $inPrevious,
			    product, surface, service_name, product_version, workload_kind, usage_scope,
			    session_id, session_id_namespace, member_id, team_id_as_of,
			    model, ${model.provider} AS provider, ${model.modelId}, {resolution:String},
			    tokens_input, tokens_output, tokens_cache_read, tokens_cache_create, tokens_input_uncached, tokens_total_derived,
			    input_semantics, output_semantics, semantics_profile,
			    cost_reported_usd, reported_cost_basis, cost_estimated_usd, pricing_version,
			    quality_flags, {purge:DateTime('UTC')}
			FROM `$sourceDatabase`.telemetry_events FINAL
			WHERE $where
			UNION ALL
			SELECT
			    tenant_id, {snapshot:String}, {build:String},
			    false,
			    'telemetry_metric_points', toDate(source_time, {tz:String}),
			    observation_id, installation_id, source_time, $inCurrent, $inPrevious,
			    '', '', CAST(NULL AS Nullable(String)), CAST(NULL AS Nullable(String)), '', '',
			    CAST(NULL AS Nullable(String)), CAST(NULL AS Nullable(String)), CAST(NULL AS Nullable(String)), CAST(NULL AS Nullable(String)),
			    CAST(NULL AS Nullable(String)), CAST(NULL AS Nullable(String)), '', '',
			    $NULL_INT, $NULL_INT, $NULL_INT, $NULL_INT, $NULL_INT, $NULL_INT,
			    '', '', CAST(NULL AS Nullable(String)),
			    $NULL_DECIMAL, '', $NULL_DECIMAL, CAST(NULL AS Nullable(String)),
			    CAST([] AS Array(LowCardinality(String))), {purge:DateTime('UTC')}
			FROM `$sourceDatabase`.telemetry_metric_points FINAL
			WHERE $where
			SETTINGS do_not_merge_across_partitions_select_final = 1
		""".trimIndent()
		return Statement(sql, params)
	}

	fun memberActivity(scope: Scope): Statement {
		val params = common(scope)
		params["as_of"] = ClickHouseParam.instant(scope.asOf)
		val sql = """
			INSERT INTO snapshot_member_activity (tenant_id, snapshot_id, build_id, member_id, last_used_at, purge_after)
			SELECT tenant_id, {snapshot:String}, {build:String}, assumeNotNull(member_id), max(source_time), {purge:DateTime('UTC')}
			FROM `$sourceDatabase`.telemetry_events FINAL
			WHERE tenant_id = {tenant:String} AND record_status = 'active' AND $USAGE_CONDITION
			  AND isNotNull(member_id) AND source_time < {as_of:DateTime64(9, 'UTC')}${boundary(scope)}
			GROUP BY tenant_id, member_id
			SETTINGS do_not_merge_across_partitions_select_final = 1
		""".trimIndent()
		return Statement(sql, params)
	}

	private fun common(scope: Scope): MutableMap<String, ClickHouseParam> {
		val params = linkedMapOf(
			"tenant" to ClickHouseParam.string(scope.tenantId),
			"snapshot" to ClickHouseParam.string(scope.snapshotId),
			"build" to ClickHouseParam.string(scope.buildId),
			"purge" to ClickHouseParam.dateTime(scope.purgeAfter),
		)
		scope.deletedBefore?.let { params["deleted_before"] = ClickHouseParam.instant(it) }
		return params
	}

	/** 삭제 경계가 없으면 제한하지 않는다. 경계를 읽지 못한 build 는 여기까지 오지 않는다(빌더가 실패시킨다). */
	private fun boundary(scope: Scope): String =
		if (scope.deletedBefore == null) "" else " AND source_time >= {deleted_before:DateTime64(9, 'UTC')}"

	private companion object {
		const val USAGE_CONDITION =
			"signal = 'log' AND event_type = 'model.response.usage' AND usage_role = 'primary' AND mapping_status = 'mapped'"
		const val NULL_INT = "CAST(NULL AS Nullable(Int64))"
		const val NULL_DECIMAL = "CAST(NULL AS Nullable(Decimal(38, 12)))"

		const val INTAKE_COLUMNS =
			"tenant_id, snapshot_id, build_id, is_usage, origin, observed_date, " +
				"observation_id, installation_id, source_time, in_current, in_previous, " +
				"product, surface, service_name, product_version, workload_kind, usage_scope, " +
				"session_id, session_id_namespace, member_id, team_id_as_of, " +
				"model, provider, model_id, model_resolution_version, " +
				"tokens_input, tokens_output, tokens_cache_read, tokens_cache_create, tokens_input_uncached, tokens_total_derived, " +
				"input_semantics, output_semantics, semantics_profile, " +
				"cost_reported_usd, reported_cost_basis, cost_estimated_usd, pricing_version, " +
				"quality_flags, purge_after"
	}
}
