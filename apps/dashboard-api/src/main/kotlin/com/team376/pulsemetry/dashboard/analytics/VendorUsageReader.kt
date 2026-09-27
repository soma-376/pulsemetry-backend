package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.snapshot.ModelResolution
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 공급자별 사용 — 설정의 벤더 지표 원천. **현재 상태(asOf) 조회**라 선택 기간의 snapshot 을 쓰지 않고 원천을 읽는다(원천 계정, 읽기 전용).
 *
 * 공급자는 snapshot build 와 같은 [ModelResolution] 으로만 정한다 — 검증된 적용 범위 밖의 행은 미확인이고 **어떤 벤더에도 넣지 않는다**.
 * `product` 로 공급자를 채우지 않는다. 미확인 행의 수를 함께 돌려 호출자가 "미확인을 빼고 센 값"을 완전한 값처럼 내지 않게 한다.
 *
 * 창은 asOf 가 속한 조직 날짜를 끝으로 하는 달력 날짜 범위다(7일·30일). 당일은 asOf 까지만이다.
 */
class VendorUsageReader(
	private val reader: ClickHouseSourceReader,
	private val resolution: ModelResolution,
	private val boundaries: RetentionBoundaryReader,
) {

	data class ProviderUsage(
		val firstSeenAt: Instant,
		val lastSeenAt: Instant,
		val users7d: Long,
		val users30d: Long,
		val rows7d: Long,
		val rows30d: Long,
	)

	data class Usage(
		val byProvider: Map<String, ProviderUsage>,
		val unresolvedRows: Long,
		val unresolvedRows7d: Long,
		val unresolvedRows30d: Long,
	)

	fun usage(tenantId: UUID, asOf: Instant, zone: ZoneId): Usage {
		val boundary = boundaries.read(tenantId)
		val today = asOf.atZone(zone).toLocalDate()
		val params = linkedMapOf(
			"tenant" to ClickHouseParam.string(tenantId.toString()),
			"as_of" to ClickHouseParam.instant(asOf),
			"d7" to ClickHouseParam.instant(today.minusDays(6).atStartOfDay(zone).toInstant()),
			"d30" to ClickHouseParam.instant(today.minusDays(29).atStartOfDay(zone).toInstant()),
		)
		boundary.deletedBefore?.let { params["deleted_before"] = ClickHouseParam.instant(it) }
		val provider = resolution.sql(params).provider
		val sql = """
			SELECT
			    provider AS p,
			    toString(min(source_time)) AS first_seen,
			    toString(max(source_time)) AS last_seen,
			    uniqExactIf(member_id, source_time >= {d7:DateTime64(9, 'UTC')}) AS u7,
			    uniqExactIf(member_id, source_time >= {d30:DateTime64(9, 'UTC')}) AS u30,
			    countIf(source_time >= {d7:DateTime64(9, 'UTC')}) AS r7,
			    countIf(source_time >= {d30:DateTime64(9, 'UTC')}) AS r30,
			    count() AS r
			FROM (
			    SELECT $provider AS provider, source_time, member_id
			    FROM telemetry_events FINAL
			    WHERE tenant_id = {tenant:String} AND record_status = 'active'
			      AND signal = 'log' AND event_type = 'model.response.usage' AND usage_role = 'primary' AND mapping_status = 'mapped'
			      AND source_time < {as_of:DateTime64(9, 'UTC')}${if (boundary.deletedBefore != null) " AND source_time >= {deleted_before:DateTime64(9, 'UTC')}" else ""}
			)
			GROUP BY provider
			SETTINGS do_not_merge_across_partitions_select_final = 1
		""".trimIndent()
		val rows = reader.query(sql, params) { row ->
			Row(
				provider = row.get("p")?.takeUnless { it.isNull }?.asString(),
				first = row.path("first_seen").asString(),
				last = row.path("last_seen").asString(),
				users7 = row.path("u7").asString().toLong(),
				users30 = row.path("u30").asString().toLong(),
				rows7 = row.path("r7").asString().toLong(),
				rows30 = row.path("r30").asString().toLong(),
				rows = row.path("r").asString().toLong(),
			)
		}
		val unresolved = rows.firstOrNull { it.provider == null }
		return Usage(
			byProvider = rows.filter { it.provider != null }.associate {
				it.provider!! to ProviderUsage(parse(it.first), parse(it.last), it.users7, it.users30, it.rows7, it.rows30)
			},
			unresolvedRows = unresolved?.rows ?: 0,
			unresolvedRows7d = unresolved?.rows7 ?: 0,
			unresolvedRows30d = unresolved?.rows30 ?: 0,
		)
	}

	/** `toString(DateTime64(9, 'UTC'))` 는 `YYYY-MM-DD hh:mm:ss.nnnnnnnnn`(UTC) 다. */
	private fun parse(text: String): Instant = Instant.parse(text.replace(' ', 'T') + "Z")

	private data class Row(
		val provider: String?,
		val first: String,
		val last: String,
		val users7: Long,
		val users30: Long,
		val rows7: Long,
		val rows30: Long,
		val rows: Long,
	)
}
