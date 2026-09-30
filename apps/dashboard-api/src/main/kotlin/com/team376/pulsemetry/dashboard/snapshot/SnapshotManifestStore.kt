package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.DatePeriod
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * RDS `dashboard_cache.snapshots` 의 읽기·쓰기 (ADR 0023 §2·§4). 캐시 계정으로 한다. manifest 가 공개·만료·무효화 판정의 진실원이다.
 */
class SnapshotManifestStore(
	private val cache: JdbcClient,
	private val transactions: TransactionTemplate,
) {

	data class NewManifest(
		val snapshotId: String,
		val buildId: UUID,
		val tenantId: UUID,
		val requestedBy: UUID,
		val current: DatePeriod,
		val compareMode: CompareMode,
		val previous: DatePeriod?,
		val asOf: Instant,
		val deletedBefore: Instant?,
		val policyEpoch: Long,
		val modelResolutionVersion: String,
		val createdAt: Instant,
		val buildDeadline: Instant,
	)

	data class Manifest(
		val snapshotId: String,
		val buildId: UUID,
		val tenantId: UUID,
		val accessScope: String,
		val queryContract: String,
		val current: DatePeriod,
		val compareMode: CompareMode,
		val previous: DatePeriod?,
		val asOf: Instant,
		val deletedBefore: Instant?,
		val policyEpoch: Long,
		val modelResolutionVersion: String,
		val pricingVersions: List<String>,
		val status: String,
		val createdAt: Instant,
		val readyAt: Instant?,
		val expiresAt: Instant?,
		val invalidatedAt: Instant?,
		val usageRows: Long?,
		val observedDayRows: Long?,
	)

	/**
	 * `building` manifest 를 쓴다 — **tenant 의 진행 중 build 가 [maxConcurrentBuilds] 개 미만일 때만**. 셈과 쓰기를 tenant 별
	 * advisory lock 아래 한 트랜잭션에서 해 동시 요청이 한도를 함께 넘지 못하게 한다. 마감이 지난 building 은 버려진 것이라 세지 않는다.
	 */
	fun insertBuilding(manifest: NewManifest, maxConcurrentBuilds: Int): Boolean = transactions.execute {
		cache.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:tenant AS text), 0))")
			.param("tenant", manifest.tenantId)
			.query { _, _ -> 1 }
			.single()
		val building = cache.sql(
			"SELECT count(*) FROM dashboard_cache.snapshots WHERE tenant_id = :tenant AND status = 'building' AND build_deadline > :now",
		)
			.param("tenant", manifest.tenantId)
			.param("now", Timestamp.from(manifest.createdAt))
			.query(Long::class.java)
			.single()
		if (building >= maxConcurrentBuilds) return@execute false
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
			.param("snapshot", manifest.snapshotId)
			.param("build", manifest.buildId)
			.param("tenant", manifest.tenantId)
			.param("requested_by", manifest.requestedBy)
			.param("access_scope", ACCESS_SCOPE)
			.param("query_contract", QUERY_CONTRACT)
			.param("time_zone", manifest.current.zone.id)
			.param("compare", manifest.compareMode.wire)
			.param("current_start", manifest.current.startDate)
			.param("current_end", manifest.current.endDate)
			.param("previous_start", manifest.previous?.startDate)
			.param("previous_end", manifest.previous?.endDate)
			.param("as_of", Timestamp.from(manifest.asOf))
			.param("deleted_before", manifest.deletedBefore?.let(Timestamp::from))
			.param("epoch", manifest.policyEpoch)
			.param("resolution", manifest.modelResolutionVersion)
			.param("created_at", Timestamp.from(manifest.createdAt))
			.param("deadline", Timestamp.from(manifest.buildDeadline))
			.update()
		true
	} == true

	fun setPricingVersions(buildId: UUID, versions: List<String>) {
		cache.sql("UPDATE dashboard_cache.snapshots SET pricing_versions = CAST(:versions AS text[]) WHERE build_id = :build AND status = 'building'")
			.param("versions", versions.joinToString(",", "{", "}") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
			.param("build", buildId)
			.update()
	}

	/** `building` 인 build 만 `failed` 로 바꾼다. 이미 공개됐거나 실패한 것은 건드리지 않는다. */
	fun markFailed(buildId: UUID, reason: String, now: Instant): Boolean =
		cache.sql(
			"UPDATE dashboard_cache.snapshots SET status = 'failed', failed_at = :now, failure_reason = :reason " +
				"WHERE build_id = :build AND status = 'building'",
		)
			.param("now", Timestamp.from(now))
			.param("reason", reason)
			.param("build", buildId)
			.update() == 1

	/**
	 * **공개 CAS** (ADR 0023 §4). 한 문장이 함께 검사한다 — 같은 build 가 아직 `building`, 무효화되지 않음, 마감 전,
	 * 그리고 **지금의 삭제 정책 epoch 가 시작 epoch 와 같음**(`telemetry_ops.tenant_retention_boundary`, 행이 없으면 0).
	 * 하나라도 어긋나면 아무것도 바꾸지 않고 `false` 다.
	 */
	fun publish(buildId: UUID, usageRows: Long, observedDayRows: Long, now: Instant, expiresAt: Instant): Boolean =
		cache.sql(
			"""
			UPDATE dashboard_cache.snapshots s
			SET status = 'ready', ready_at = :now, expires_at = :expires, usage_rows = :usage_rows, observed_day_rows = :day_rows
			WHERE s.build_id = :build
			  AND s.status = 'building'
			  AND s.invalidated_at IS NULL
			  AND s.build_deadline > :now
			  AND s.policy_epoch = COALESCE(
			      (SELECT b.policy_epoch FROM telemetry_ops.tenant_retention_boundary b WHERE b.tenant_id = s.tenant_id), 0)
			""".trimIndent(),
		)
			.param("now", Timestamp.from(now))
			.param("expires", Timestamp.from(expiresAt))
			.param("usage_rows", usageRows)
			.param("day_rows", observedDayRows)
			.param("build", buildId)
			.update() == 1

	fun find(snapshotId: String): Manifest? =
		cache.sql("SELECT * FROM dashboard_cache.snapshots WHERE snapshot_id = :snapshot")
			.param("snapshot", snapshotId)
			.query { rs, _ -> manifest(rs) }
			.optional()
			.orElse(null)

	/**
	 * tenant 의 공개됐거나 진행 중인 snapshot 을 모두 무효화한다 — 권한·정책이 바뀌어 기존 snapshot 을 보여 줄 수 없을 때.
	 * 무효화된 snapshot 은 읽히지 않고(409), 진행 중인 build 는 공개 CAS 에 실패한다.
	 */
	fun invalidateTenant(tenantId: UUID, reason: String, now: Instant): Int =
		cache.sql(
			"UPDATE dashboard_cache.snapshots SET invalidated_at = :now, invalidation_reason = :reason " +
				"WHERE tenant_id = :tenant AND invalidated_at IS NULL AND status IN ('building', 'ready')",
		)
			.param("now", Timestamp.from(now))
			.param("reason", reason)
			.param("tenant", tenantId)
			.update()

	/** 마감이 지난 `building` 을 `failed`(`abandoned`)로 바꾼다. 결과가 불확실한 build 를 공개하지 않는다. */
	fun abandonOverdue(now: Instant): Int =
		cache.sql(
			"UPDATE dashboard_cache.snapshots SET status = 'failed', failed_at = :now, failure_reason = 'abandoned' " +
				"WHERE status = 'building' AND build_deadline <= :now",
		)
			.param("now", Timestamp.from(now))
			.update()

	data class Purgeable(val snapshotId: String, val buildId: UUID)

	/** 실패한 것 전부와, 만든 지 [createdBefore] 보다 오래된 것 전부 — 물리 정리 시각이 지난 manifest 다. */
	fun purgeable(createdBefore: Instant): List<Purgeable> =
		cache.sql("SELECT snapshot_id, build_id FROM dashboard_cache.snapshots WHERE status = 'failed' OR created_at < :before")
			.param("before", Timestamp.from(createdBefore))
			.query { rs, _ -> Purgeable(rs.getString("snapshot_id"), rs.getObject("build_id", UUID::class.java)) }
			.list()

	/** manifest 를 지운다. 참조 복제는 CASCADE 로 함께 지워진다. */
	fun delete(snapshotIds: Collection<String>): Int =
		if (snapshotIds.isEmpty()) 0 else
			cache.sql("DELETE FROM dashboard_cache.snapshots WHERE snapshot_id = ANY(CAST(:ids AS text[]))")
				.param("ids", snapshotIds.joinToString(",", "{", "}"))
				.update()

	private fun manifest(rs: ResultSet): Manifest {
		val zone = ZoneId.of(rs.getString("time_zone"))
		val previousStart = rs.getObject("previous_start_date", LocalDate::class.java)
		return Manifest(
			snapshotId = rs.getString("snapshot_id"),
			buildId = rs.getObject("build_id", UUID::class.java),
			tenantId = rs.getObject("tenant_id", UUID::class.java),
			accessScope = rs.getString("access_scope"),
			queryContract = rs.getString("query_contract"),
			current = DatePeriod(rs.getObject("current_start_date", LocalDate::class.java), rs.getObject("current_end_date", LocalDate::class.java), zone),
			compareMode = CompareMode.BY_WIRE.getValue(rs.getString("compare_mode")),
			previous = previousStart?.let { DatePeriod(it, rs.getObject("previous_end_date", LocalDate::class.java), zone) },
			asOf = instant(rs, "as_of")!!,
			deletedBefore = instant(rs, "deleted_before"),
			policyEpoch = rs.getLong("policy_epoch"),
			modelResolutionVersion = rs.getString("model_resolution_version"),
			pricingVersions = (rs.getArray("pricing_versions").array as Array<*>).map { it as String },
			status = rs.getString("status"),
			createdAt = instant(rs, "created_at")!!,
			readyAt = instant(rs, "ready_at"),
			expiresAt = instant(rs, "expires_at"),
			invalidatedAt = instant(rs, "invalidated_at"),
			usageRows = rs.getLong("usage_rows").takeUnless { rs.wasNull() },
			observedDayRows = rs.getLong("observed_day_rows").takeUnless { rs.wasNull() },
		)
	}

	private fun instant(rs: ResultSet, column: String): Instant? = rs.getObject(column, OffsetDateTime::class.java)?.toInstant()

	companion object {
		/** 조회 계약의 판. 응답 의미를 바꾸면 올린다 — 다른 판의 snapshot 은 재사용하지 않는다. */
		const val QUERY_CONTRACT = "dashboard-v3"

		/** 합의 전 재사용 범위 — 조직 전체(ADR 0022 §3 의 기본 인가 정책). */
		const val ACCESS_SCOPE = "organization"
	}
}
