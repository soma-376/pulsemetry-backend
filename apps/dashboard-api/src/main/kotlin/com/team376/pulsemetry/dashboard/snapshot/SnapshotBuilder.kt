package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import com.team376.pulsemetry.dashboard.store.StoreLimitExceededException
import com.team376.pulsemetry.dashboard.store.StoreQueryRejectedException
import com.team376.pulsemetry.dashboard.store.StoreUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * snapshot 하나를 만든다(ADR 0023). 결과 manifest 는 **`building` 에 머문다** — 공개(`ready`)는 공개 CAS 의 몫이다.
 *
 * 순서가 불변식이다.
 * 1. 삭제 경계를 읽는다. 실패하면 manifest 도 만들지 않고 실패한다 — 경계를 모른 채 과거 행을 복사하지 않는다.
 * 2. `building` manifest 를 쓴다(시작 epoch·적용 경계·기준 시각·해석 규칙 판).
 * 3. 참조를 복제한다(팀·로스터).
 * 4. 원본을 **한 번** 읽어 사용량 행과 관측 일자를 만든다(`INSERT … SELECT` 한 문장 — [SnapshotCopySql.intake]).
 * 5. 기간 밖 고정 입력(구성원별 마지막 사용)을 만든다.
 * 6. 복사한 행 수와 가격 판 집합을 센다. 가격 판 집합은 manifest 에 고정한다.
 *
 * 2 이후에 실패하면 manifest 를 `failed` 로 바꾸고 원래 예외를 다시 던진다. 남은 캐시 행은 어떤 조회도 읽지 않는다 —
 * 조회는 ready manifest 의 `build_id` 로만 거른다. 재시도는 새 snapshot·새 build 다.
 */
class SnapshotBuilder(
	private val boundaries: RetentionBoundaryReader,
	private val cache: JdbcClient,
	private val references: SnapshotReferenceCopier,
	private val clickHouse: ClickHouseCacheClient,
	private val sql: SnapshotCopySql,
	private val resolution: ModelResolution,
	private val buildTimeout: Duration,
	private val purgeGrace: Duration,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(SnapshotBuilder::class.java)

	data class Request(
		val tenantId: UUID,
		val requestedBy: UUID,
		val period: ComparedPeriod,
	)

	data class Built(
		val snapshotId: String,
		val buildId: UUID,
		val usageRows: Long,
		val observedDayRows: Long,
		val pricingVersions: List<String>,
		val policyEpoch: Long,
	)

	fun build(request: Request): Built {
		val boundary = boundaries.read(request.tenantId)
		val snapshotId = SnapshotIds.next()
		val buildId = UUID.randomUUID()
		val createdAt = clock.instant()
		val purgeAfter = createdAt + buildTimeout + API_LIFETIME + purgeGrace
		insertManifest(snapshotId, buildId, request, boundary, createdAt)

		try {
			references.copy(snapshotId, request.tenantId, asOf = createdAt)
			val scope = SnapshotCopySql.Scope(
				tenantId = request.tenantId.toString(),
				snapshotId = snapshotId,
				buildId = buildId.toString(),
				current = request.period.current,
				previous = request.period.previous,
				deletedBefore = boundary.deletedBefore,
				purgeAfter = purgeAfter,
				asOf = createdAt,
			)
			sql.intake(scope).let { clickHouse.execute(it.sql, it.params) }
			sql.memberActivity(scope).let { clickHouse.execute(it.sql, it.params) }

			val keys = mapOf(
				"tenant" to ClickHouseParam.string(scope.tenantId),
				"snapshot" to ClickHouseParam.string(snapshotId),
				"build" to ClickHouseParam.string(scope.buildId),
			)
			val usageRows = count("SELECT count() AS n FROM snapshot_usage WHERE $BUILD_FILTER", keys)
			val observedDayRows = count("SELECT uniqExact(observed_date) AS n FROM snapshot_observed_days WHERE $BUILD_FILTER", keys)
			val pricingVersions = clickHouse.query(
				"SELECT DISTINCT assumeNotNull(pricing_version) AS v FROM snapshot_usage WHERE $BUILD_FILTER AND isNotNull(pricing_version) ORDER BY v",
				keys,
			) { it.path("v").asString() }
			cache.sql("UPDATE dashboard_cache.snapshots SET pricing_versions = CAST(:versions AS text[]) WHERE build_id = :build AND status = 'building'")
				.param("versions", pricingVersions.joinToString(",", "{", "}") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
				.param("build", buildId)
				.update()

			return Built(snapshotId, buildId, usageRows, observedDayRows, pricingVersions, boundary.policyEpoch)
		} catch (e: Exception) {
			markFailed(buildId, e)
			throw e
		}
	}

	private fun insertManifest(
		snapshotId: String,
		buildId: UUID,
		request: Request,
		boundary: RetentionBoundaryReader.Boundary,
		createdAt: Instant,
	) {
		val previous = request.period.previous
		cache.sql(
			"""
			INSERT INTO dashboard_cache.snapshots (
			    snapshot_id, build_id, tenant_id, requested_by, access_scope, query_contract, time_zone, compare_mode,
			    current_start_date, current_end_date, previous_start_date, previous_end_date,
			    as_of, deleted_before, policy_epoch, model_resolution_version, status, created_at, build_deadline)
			VALUES (:snapshot, :build, :tenant, :requested_by, :access_scope, :query_contract, :time_zone,
			    CAST(:compare AS dashboard_cache.compare_mode),
			    :current_start, :current_end, :previous_start, :previous_end,
			    :as_of, :deleted_before, :epoch, :resolution, 'building', :created_at, :deadline)
			""".trimIndent(),
		)
			.param("snapshot", snapshotId)
			.param("build", buildId)
			.param("tenant", request.tenantId)
			.param("requested_by", request.requestedBy)
			.param("access_scope", ACCESS_SCOPE)
			.param("query_contract", QUERY_CONTRACT)
			.param("time_zone", request.period.current.zone.id)
			.param("compare", request.period.mode.wire)
			.param("current_start", request.period.current.startDate)
			.param("current_end", request.period.current.endDate)
			.param("previous_start", previous?.startDate)
			.param("previous_end", previous?.endDate)
			.param("as_of", Timestamp.from(createdAt))
			.param("deleted_before", boundary.deletedBefore?.let(Timestamp::from))
			.param("epoch", boundary.policyEpoch)
			.param("resolution", resolution.version)
			.param("created_at", Timestamp.from(createdAt))
			.param("deadline", Timestamp.from(createdAt + buildTimeout))
			.update()
	}

	private fun count(query: String, params: Map<String, ClickHouseParam>): Long =
		clickHouse.query(query, params) { it.path("n").asString().toLong() }.single()

	/** 실패를 기록한다. 기록마저 실패하면 원래 예외를 가리지 않도록 로그만 남긴다 — manifest 는 `building` 으로 남고 제한 시간이 지나면 버려진다. */
	private fun markFailed(buildId: UUID, cause: Exception) {
		val reason = when (cause) {
			is StoreLimitExceededException -> "limit_exceeded"
			is StoreUnavailableException, is DataAccessException -> "store_unavailable"
			is StoreQueryRejectedException -> "rejected"
			else -> "internal"
		}
		try {
			cache.sql(
				"UPDATE dashboard_cache.snapshots SET status = 'failed', failed_at = :now, failure_reason = :reason " +
					"WHERE build_id = :build AND status = 'building'",
			)
				.param("now", Timestamp.from(clock.instant()))
				.param("reason", reason)
				.param("build", buildId)
				.update()
		} catch (e: Exception) {
			log.error("snapshot build {} 의 실패를 기록하지 못했다", buildId, e)
		}
		log.warn("snapshot build {} 실패 — {}", buildId, reason, cause)
	}

	companion object {
		/** 화면 요청서가 정한 snapshot 의 API 수명(ready 뒤 10분). 물리 정리 시각은 이보다 늦어야 한다. */
		val API_LIFETIME: Duration = Duration.ofMinutes(10)

		/** 조회 계약의 판. 응답 의미를 바꾸면 올린다 — 다른 판의 snapshot 은 재사용하지 않는다. */
		const val QUERY_CONTRACT = "dashboard-v1"

		/** 합의 전 재사용 범위 — 조직 전체(ADR 0022 §3 의 기본 인가 정책). */
		const val ACCESS_SCOPE = "organization"

		private const val BUILD_FILTER = "tenant_id = {tenant:String} AND snapshot_id = {snapshot:String} AND build_id = {build:String}"
	}
}
