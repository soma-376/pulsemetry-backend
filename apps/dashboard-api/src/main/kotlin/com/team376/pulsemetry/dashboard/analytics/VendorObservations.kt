package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCompleteness
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 설정·벤더 조회의 관측 지표 (ADR 0044). 관측 제품(`product`)은 카탈로그의 명시 매핑(`enrollment.vendor_catalog_observed_products`)으로만
 * 카탈로그 제품에 잇는다 — 공급사·모델 이름으로 추정하지 않는다. 매핑 없는 관측은 어떤 등록 제품에도 넣지 않고 따로 센다.
 *
 * 조회 기준 시각(asOf) 하나에 대해 **한 번 계산해 캐시에 고정한다**([fix]). 같은 기준 시각의 다음 페이지·상세는 [read] 로 같은 값을 읽는다.
 * 고정이 없으면(만료·정리·이전 판의 기준 시각) null — 호출자는 409 로 답한다. 다시 계산해 섞지 않는다.
 *
 * - 관측 행: 활성 로그 관측(`signal = 'log'`, `record_status = 'active'`) 중 source_time 이 asOf 전이고 삭제 경계 뒤인 것.
 * - 처음·마지막 관측: 그 행의 source_time 최솟값·최댓값.
 * - 최근 7·30일: 기준일(asOf 의 조회 시간대 날짜) **전날까지**의 7·30일. 오늘은 아직 끝나지 않아 넣지 않는다.
 *   구성원 수는 그 창에 관측이 있는 서로 다른 구성원이다. 창의 모든 날이 완전 관측(ADR 0042)인지를 함께 고정한다.
 */
class VendorObservations(
	private val reader: ClickHouseSourceReader,
	private val source: JdbcClient,
	private val cache: JdbcClient,
	/** 캐시 연결의 트랜잭션. 묶음과 행을 함께 쓴다 — 같은 기준 시각을 동시에 고정하면 뒤의 요청은 앞의 커밋을 기다려 그것을 읽는다. */
	private val cacheTx: TransactionTemplate,
	private val completeness: SnapshotCompleteness,
	private val boundaries: RetentionBoundaryReader,
) {

	/** 관측된 대상 하나. [subject] 는 카탈로그 제품 ID, 매핑 없는 관측은 [UNMAPPED]. */
	data class Observed(
		val subject: String,
		val observedProducts: List<String>,
		val firstSeenAt: Instant,
		val lastSeenAt: Instant,
		val users7d: Long,
		val users30d: Long,
	)

	/** 한 기준 시각의 고정. [mappedProducts] 는 매핑이 잇는 카탈로그 제품, [observed] 는 관측이 있는 대상만. */
	data class Fixed(
		val asOf: Instant,
		val mappedProducts: Set<String>,
		val complete7d: Boolean,
		val complete30d: Boolean,
		val observed: Map<String, Observed>,
	) {
		/**
		 * 한 대상의 표시. 매핑이 없는 카탈로그 제품은 관측할 수 없어 모두 null·`unobserved` 다.
		 * 창의 모든 날이 완전하면 구성원 수는 정확하다(0 포함). 완전하지 않으면 센 수가 있을 때만 그 수(알려진 부분 값)이고 0 은 null 이다 —
		 * 수집이 빠졌을 수 있는 창의 0 은 관측이 아니다. 관측 상태는 30일 창이 완전하면 `complete`, 아니면 관측이 있을 때 `partial`, 없으면 `unobserved`.
		 */
		fun present(subject: String): Presented {
			if (subject != UNMAPPED && subject !in mappedProducts) return Presented(null, null, null, null, UNOBSERVED)
			val row = observed[subject]
			fun users(count: Long?, complete: Boolean): Long? = when {
				complete -> count ?: 0
				count != null && count > 0 -> count
				else -> null
			}
			val observation = when {
				complete30d -> COMPLETE
				row != null -> PARTIAL
				else -> UNOBSERVED
			}
			return Presented(row?.firstSeenAt, row?.lastSeenAt, users(row?.users7d, complete7d), users(row?.users30d, complete30d), observation)
		}
	}

	data class Presented(val firstSeenAt: Instant?, val lastSeenAt: Instant?, val users7d: Long?, val users30d: Long?, val observation: String)

	/** 계산해 고정하고 돌려준다. 같은 기준 시각이 이미 고정돼 있으면 그것을 돌려준다. */
	fun fix(tenantId: UUID, asOf: Instant, zone: ZoneId): Fixed {
		read(tenantId, asOf)?.let { return it }
		val mapping = source.sql("SELECT observed_product, product_id FROM enrollment.vendor_catalog_observed_products ORDER BY observed_product")
			.query { rs, _ -> rs.getString("observed_product") to rs.getString("product_id") }
			.list()
		val boundary = boundaries.read(tenantId)
		val today = asOf.atZone(zone).toLocalDate()
		val window30 = (30L downTo 1L).map { today.minusDays(it) }
		val complete = completeness.completeDates(tenantId, window30, zone, asOf, boundary.deletedBefore)
		val complete7 = window30.takeLast(7).all { it in complete }
		val complete30 = window30.all { it in complete }
		val observed = query(tenantId, asOf, zone, today, mapping, boundary.deletedBefore)
		val fixed = Fixed(asOf, mapping.map { it.second }.toSortedSet(), complete7, complete30, observed.associateBy { it.subject })
		write(tenantId, fixed)
		return read(tenantId, asOf) ?: fixed
	}

	/** 고정된 값. 없으면 null. */
	fun read(tenantId: UUID, asOf: Instant): Fixed? {
		val set = cache.sql(
			"""SELECT mapped_products, complete_7d, complete_30d FROM dashboard_cache.vendor_observation_sets
			WHERE tenant_id = :tenant AND as_of = :as_of""",
		)
			.param("tenant", tenantId)
			.param("as_of", Timestamp.from(asOf))
			.query { rs, _ -> Triple((rs.getArray("mapped_products").array as Array<*>).map { it as String }.toSortedSet(), rs.getBoolean("complete_7d"), rs.getBoolean("complete_30d")) }
			.optional()
			.orElse(null) ?: return null
		val observed = cache.sql(
			"""SELECT subject, observed_products, first_seen_at, last_seen_at, users_7d, users_30d FROM dashboard_cache.vendor_observations
			WHERE tenant_id = :tenant AND as_of = :as_of ORDER BY subject""",
		)
			.param("tenant", tenantId)
			.param("as_of", Timestamp.from(asOf))
			.query { rs, _ ->
				Observed(
					subject = rs.getString("subject"),
					observedProducts = (rs.getArray("observed_products").array as Array<*>).map { it as String },
					firstSeenAt = rs.getTimestamp("first_seen_at").toInstant(),
					lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
					users7d = rs.getLong("users_7d"),
					users30d = rs.getLong("users_30d"),
				)
			}
			.list()
		return Fixed(asOf, set.first, set.second, set.third, observed.associateBy { it.subject })
	}

	/** [before] 보다 먼저 만든 고정을 지운다(관측 행은 CASCADE). 지운 묶음 수. */
	fun purge(before: Instant): Int =
		cache.sql("DELETE FROM dashboard_cache.vendor_observation_sets WHERE created_at < :before")
			.param("before", Timestamp.from(before))
			.update()

	private fun query(
		tenantId: UUID,
		asOf: Instant,
		zone: ZoneId,
		today: LocalDate,
		mapping: List<Pair<String, String>>,
		deletedBefore: Instant?,
	): List<Observed> {
		val params = linkedMapOf(
			"tenant" to ClickHouseParam.string(tenantId.toString()),
			"as_of" to ClickHouseParam.instant(asOf),
			"d7" to ClickHouseParam.instant(today.minusDays(7).atStartOfDay(zone).toInstant()),
			"d30" to ClickHouseParam.instant(today.minusDays(30).atStartOfDay(zone).toInstant()),
			"today" to ClickHouseParam.instant(today.atStartOfDay(zone).toInstant()),
			"observed" to ClickHouseParam.stringArray(mapping.map { it.first }),
			"catalog" to ClickHouseParam.stringArray(mapping.map { it.second }),
		)
		deletedBefore?.let { params["deleted_before"] = ClickHouseParam.instant(it) }
		val sql = """
			SELECT
			    subject,
			    arraySort(groupUniqArray(product)) AS products,
			    toString(min(source_time)) AS first_seen,
			    toString(max(source_time)) AS last_seen,
			    uniqExactIf(member_id, source_time >= {d7:DateTime64(9, 'UTC')} AND source_time < {today:DateTime64(9, 'UTC')}) AS u7,
			    uniqExactIf(member_id, source_time >= {d30:DateTime64(9, 'UTC')} AND source_time < {today:DateTime64(9, 'UTC')}) AS u30
			FROM (
			    SELECT transform(product, {observed:Array(String)}, {catalog:Array(String)}, '') AS subject, product, source_time, member_id
			    FROM telemetry_events FINAL
			    WHERE tenant_id = {tenant:String} AND record_status = 'active' AND signal = 'log'
			      AND source_time < {as_of:DateTime64(9, 'UTC')}${if (deletedBefore != null) " AND source_time >= {deleted_before:DateTime64(9, 'UTC')}" else ""}
			)
			GROUP BY subject
			ORDER BY subject
			SETTINGS do_not_merge_across_partitions_select_final = 1
		""".trimIndent()
		return reader.query(sql, params) { row ->
			Observed(
				subject = row.path("subject").asString(),
				observedProducts = row.path("products").toList().map { it.asString() },
				firstSeenAt = parse(row.path("first_seen").asString()),
				lastSeenAt = parse(row.path("last_seen").asString()),
				users7d = row.path("u7").asString().toLong(),
				users30d = row.path("u30").asString().toLong(),
			)
		}
	}

	private fun write(tenantId: UUID, fixed: Fixed) = cacheTx.executeWithoutResult { writeRows(tenantId, fixed) }

	private fun writeRows(tenantId: UUID, fixed: Fixed) {
		val inserted = cache.sql(
			"""INSERT INTO dashboard_cache.vendor_observation_sets (tenant_id, as_of, mapped_products, complete_7d, complete_30d, created_at)
			VALUES (:tenant, :as_of, CAST(:mapped AS text[]), :c7, :c30, now()) ON CONFLICT DO NOTHING""",
		)
			.param("tenant", tenantId)
			.param("as_of", Timestamp.from(fixed.asOf))
			.param("mapped", fixed.mappedProducts.joinToString(",", "{", "}"))
			.param("c7", fixed.complete7d)
			.param("c30", fixed.complete30d)
			.update()
		// 같은 기준 시각을 다른 요청이 먼저 고정했다 — 그것을 쓴다.
		if (inserted == 0) return
		for (row in fixed.observed.values) {
			cache.sql(
				"""INSERT INTO dashboard_cache.vendor_observations (tenant_id, as_of, subject, observed_products, first_seen_at, last_seen_at, users_7d, users_30d)
				VALUES (:tenant, :as_of, :subject, CAST(:products AS text[]), :first, :last, :u7, :u30)""",
			)
				.param("tenant", tenantId)
				.param("as_of", Timestamp.from(fixed.asOf))
				.param("subject", row.subject)
				.param("products", row.observedProducts.joinToString(",", "{", "}"))
				.param("first", Timestamp.from(row.firstSeenAt))
				.param("last", Timestamp.from(row.lastSeenAt))
				.param("u7", row.users7d)
				.param("u30", row.users30d)
				.update()
		}
	}

	/** `toString(DateTime64(9, 'UTC'))` 는 `YYYY-MM-DD hh:mm:ss.nnnnnnnnn`(UTC) 다. */
	private fun parse(text: String): Instant = Instant.parse(text.replace(' ', 'T') + "Z")

	companion object {
		/** 매핑 없는 관측의 대상 이름. */
		const val UNMAPPED = ""
		const val COMPLETE = "complete"
		const val PARTIAL = "partial"
		const val UNOBSERVED = "unobserved"
	}
}
