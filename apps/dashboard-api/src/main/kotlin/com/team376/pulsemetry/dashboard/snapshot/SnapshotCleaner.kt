package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * 자기 캐시의 정리 (ADR 0023 §1·§4). **자기 캐시 행만 지운다** — 분석 원본·ledger·`telemetry_ops` 는 건드리지 않는다.
 *
 * 1. 마감이 지난 `building` 을 `failed`(`abandoned`)로 바꾼다 — 결과가 불확실한 build 는 공개되지 않는다.
 * 2. 실패한 build 와 물리 정리 시각이 지난 build 의 ClickHouse 행을 지우고 manifest 를 지운다(참조 복제는 CASCADE).
 *
 * TTL 이 물리 정리의 본체이고 이 작업은 실패한 build 를 일찍 치우는 보조다. 유효기간을 행의 존재로 판정하지 않으므로 이 작업이 늦거나
 * 빠져도 응답은 달라지지 않는다. 여러 인스턴스가 동시에 돌아도 같은 결과다(삭제는 멱등).
 */
class SnapshotCleaner(
	private val manifests: SnapshotManifestStore,
	private val clickHouse: ClickHouseCacheClient,
	private val limits: SnapshotBuilder.Limits,
	private val clock: Clock,
	/** 설정의 벤더 관측 고정(ADR 0044)을 같은 기한으로 지운다. 지운 묶음 수를 돌려준다. */
	private val purgeObservations: (Instant) -> Int = { 0 },
) {

	private val log = LoggerFactory.getLogger(SnapshotCleaner::class.java)

	data class Result(val abandoned: Int, val deleted: Int)

	fun run(): Result {
		val now = clock.instant()
		val abandoned = manifests.abandonOverdue(now)
		val targets = manifests.purgeable(createdBefore = now - limits.buildTimeout - SnapshotBuilder.API_LIFETIME - limits.purgeGrace)
		if (targets.isNotEmpty()) {
			val builds = mapOf("builds" to ClickHouseParam.stringArray(targets.map { it.buildId.toString() }))
			for (table in TABLES) {
				clickHouse.execute("DELETE FROM $table WHERE build_id IN {builds:Array(String)}", builds)
			}
		}
		val deleted = manifests.delete(targets.map { it.snapshotId })
		val observations = purgeObservations(now - limits.buildTimeout - SnapshotBuilder.API_LIFETIME - limits.purgeGrace)
		if (abandoned > 0 || deleted > 0 || observations > 0) log.info("snapshot 정리 — 버려진 build {}건, 지운 snapshot {}건, 지운 벤더 관측 고정 {}건", abandoned, deleted, observations)
		return Result(abandoned, deleted)
	}

	private companion object {
		val TABLES = listOf("snapshot_usage", "snapshot_observed_days", "snapshot_member_activity")
	}
}
