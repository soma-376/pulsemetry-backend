package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import com.team376.pulsemetry.dashboard.store.StoreLimitExceededException
import com.team376.pulsemetry.dashboard.store.StoreQueryRejectedException
import com.team376.pulsemetry.dashboard.store.StoreUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * snapshot 하나를 만든다(ADR 0023). 결과 manifest 는 **`building` 에 머문다** — 공개(`ready`)는 [SnapshotService] 의 CAS 다.
 *
 * 순서가 불변식이다.
 * 1. 삭제 경계를 읽는다. 실패하면 manifest 도 만들지 않고 실패한다 — 경계를 모른 채 과거 행을 복사하지 않는다.
 * 2. `building` manifest 를 쓴다(시작 epoch·적용 경계·기준 시각·해석 규칙 판). tenant 의 동시 build 가 한도에 차 있으면 쓰지 않고 거부한다.
 * 3. 참조를 복제한다(팀·로스터).
 * 4. 원본을 **한 번** 읽어 사용량 행과 관측 일자를 만든다(`INSERT … SELECT` 한 문장 — [SnapshotCopySql.intake]).
 * 5. 기간 밖 고정 입력(구성원별 마지막 사용)을 만든다.
 * 6. **검증** — 조회에 쓸 연결로 행을 세어 서버가 보고한 쓴 행 수와 맞춘다. 입구의 모든 행은 관측 일자의 개수로 한 번씩 세이므로
 *    `written_rows = Σ observations + 사용량 행 + 관측 일자 행` 이어야 한다. 어긋나면 결과를 믿지 않는다.
 * 7. 가격 판 집합을 manifest 에 고정한다.
 *
 * 복사 문장에는 읽기 상한(행·바이트)과 실행 시간 상한을 싣는다 — 넘으면 잘라 내지 않고 실패한다.
 * 2 이후에 실패하면 manifest 를 `failed` 로 바꾸고 원래 예외를 다시 던진다. 남은 캐시 행은 어떤 조회도 읽지 않는다 —
 * 조회는 ready manifest 의 `build_id` 로만 거른다. 재시도는 새 snapshot·새 build 다.
 */
class SnapshotBuilder(
	private val boundaries: RetentionBoundaryReader,
	private val manifests: SnapshotManifestStore,
	private val references: SnapshotReferenceCopier,
	private val clickHouse: ClickHouseCacheClient,
	private val sql: SnapshotCopySql,
	private val resolution: ModelResolution,
	private val limits: Limits,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(SnapshotBuilder::class.java)

	/** 운영 수치 — 모두 기본값 없는 설정이다(ADR 0023 §1). */
	data class Limits(
		val buildTimeout: Duration,
		val purgeGrace: Duration,
		val maxConcurrentBuilds: Int,
		val maxCopyRows: Long,
		val maxCopyBytes: Long,
	)

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

	private val copySettings: Map<String, String> = mapOf(
		"max_rows_to_read" to limits.maxCopyRows.toString(),
		"max_bytes_to_read" to limits.maxCopyBytes.toString(),
		"read_overflow_mode" to "throw",
		"max_execution_time" to limits.buildTimeout.toSeconds().toString(),
	)

	fun build(request: Request): Built {
		val boundary = boundaries.read(request.tenantId)
		val snapshotId = SnapshotIds.next()
		val buildId = UUID.randomUUID()
		val createdAt = clock.instant()
		val purgeAfter = createdAt + limits.buildTimeout + API_LIFETIME + limits.purgeGrace
		val admitted = manifests.insertBuilding(
			SnapshotManifestStore.NewManifest(
				snapshotId = snapshotId,
				buildId = buildId,
				tenantId = request.tenantId,
				requestedBy = request.requestedBy,
				current = request.period.current,
				compareMode = request.period.mode,
				previous = request.period.previous,
				asOf = createdAt,
				deletedBefore = boundary.deletedBefore,
				policyEpoch = boundary.policyEpoch,
				modelResolutionVersion = resolution.version,
				createdAt = createdAt,
				buildDeadline = createdAt + limits.buildTimeout,
			),
			limits.maxConcurrentBuilds,
		)
		if (!admitted) {
			log.warn("tenant {} 의 진행 중 snapshot build 가 한도({})에 찼다", request.tenantId, limits.maxConcurrentBuilds)
			throw SnapshotUnavailableException(CONCURRENCY_LIMIT)
		}

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
			val intakeWritten = sql.intake(scope).let { clickHouse.execute(it.sql, it.params, copySettings) }
			val activityWritten = sql.memberActivity(scope).let { clickHouse.execute(it.sql, it.params, copySettings) }

			val keys = mapOf(
				"tenant" to ClickHouseParam.string(scope.tenantId),
				"snapshot" to ClickHouseParam.string(snapshotId),
				"build" to ClickHouseParam.string(scope.buildId),
			)
			val usageRows = count("SELECT count() AS n FROM snapshot_usage WHERE $BUILD_FILTER", keys)
			val (observations, dayRows, distinctDays) = clickHouse.query(
				"SELECT sum(observations) AS o, count() AS r, uniqExact(observed_date) AS d FROM snapshot_observed_days WHERE $BUILD_FILTER",
				keys,
			) { Triple(it.path("o").asString().toLong(), it.path("r").asString().toLong(), it.path("d").asString().toLong()) }.single()
			val activityRows = count("SELECT count() AS n FROM snapshot_member_activity WHERE $BUILD_FILTER", keys)
			if (intakeWritten != observations + usageRows + dayRows || activityWritten != activityRows) {
				log.error(
					"snapshot build {} 검증 실패 — 보고 {}/{}, 읽힘 관측 {} + 사용량 {} + 일자 행 {} / 활동 {}",
					buildId, intakeWritten, activityWritten, observations, usageRows, dayRows, activityRows,
				)
				throw SnapshotUnavailableException(VERIFICATION_FAILED)
			}

			val pricingVersions = clickHouse.query(
				"SELECT DISTINCT assumeNotNull(pricing_version) AS v FROM snapshot_usage WHERE $BUILD_FILTER AND isNotNull(pricing_version) ORDER BY v",
				keys,
			) { it.path("v").asString() }
			manifests.setPricingVersions(buildId, pricingVersions)

			return Built(snapshotId, buildId, usageRows, distinctDays, pricingVersions, boundary.policyEpoch)
		} catch (e: Exception) {
			fail(buildId, e)
			throw e
		}
	}

	private fun count(query: String, params: Map<String, ClickHouseParam>): Long =
		clickHouse.query(query, params) { it.path("n").asString().toLong() }.single()

	/** 실패를 기록한다. 기록마저 실패하면 원래 예외를 가리지 않도록 로그만 남긴다 — manifest 는 `building` 으로 남고 마감이 지나면 버려진다. */
	private fun fail(buildId: UUID, cause: Exception) {
		val reason = when (cause) {
			is SnapshotUnavailableException -> cause.reason
			is StoreLimitExceededException -> LIMIT_EXCEEDED
			is StoreUnavailableException, is DataAccessException -> "store_unavailable"
			is StoreQueryRejectedException -> "rejected"
			else -> "internal"
		}
		try {
			manifests.markFailed(buildId, reason, clock.instant())
		} catch (e: Exception) {
			log.error("snapshot build {} 의 실패를 기록하지 못했다", buildId, e)
		}
		log.warn("snapshot build {} 실패 — {}", buildId, reason, cause)
	}

	companion object {
		/** 화면 요청서가 정한 snapshot 의 API 수명(ready 뒤 10분). 물리 정리 시각은 이보다 늦어야 한다. */
		val API_LIFETIME: Duration = Duration.ofMinutes(10)

		const val CONCURRENCY_LIMIT = "concurrency_limit"
		const val VERIFICATION_FAILED = "verification_failed"
		const val LIMIT_EXCEEDED = "limit_exceeded"

		private const val BUILD_FILTER = "tenant_id = {tenant:String} AND snapshot_id = {snapshot:String} AND build_id = {build:String}"
	}
}
