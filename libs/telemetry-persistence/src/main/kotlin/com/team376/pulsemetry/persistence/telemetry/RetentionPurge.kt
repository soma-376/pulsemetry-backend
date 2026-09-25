package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import java.time.Instant

/**
 * 두 분석 테이블에서 tenant 의 경계 이전 관측을 지운다(ADR 0020 §8 · ADR 0024 §4). 두 테이블의 DDL 이 이 모듈 아래 있어 삭제도
 * 여기다(ADR 0008 규칙 1). **보존 작업만 조립한다** — ingest·대시보드 계정에는 DELETE 가 없다.
 *
 * 조건은 `tenant_id` 와 `source_time < 경계` 뿐이다. `row_version`·`record_status`·`normalizer_rev` 를 넣지 않는다 — 한 관측의 모든
 * revision 이 지워진다. 월 파티션은 tenant 를 공유하므로 파티션을 지우지 않는다.
 *
 * 수신 ledger 는 대상이 아니다(ADR 0021 · 0024 §4). 구 `enriched_events` 도 아니다 — 두 분석 테이블만이 ADR 0020 의 분석 행이다.
 *
 * 이 클래스는 **drain 을 하지 않는다.** fence 를 쓰고 구 경계의 INSERT 가 빠진 것을 확인한 뒤에 부르는 것은 호출자(보존 작업)의 몫이다
 * ([RetentionFence] · [InsertDrain]). 빈이 아니다(ADR 0011).
 */
public class RetentionPurge(private val client: ClickHouseHttpClient) {

	/** 한 테이블의 삭제 대상 — 서로 다른 관측 수와 물리 revision 행 수(FINAL 없이). */
	public data class Target(public val observations: Long, public val rows: Long)

	/** 두 테이블의 삭제 대상. */
	public data class Targets(public val events: Target, public val metricPoints: Target)

	public fun count(tenantId: String, deletedBefore: Instant): Targets =
		Targets(count(TelemetryEventsSink.TABLE, tenantId, deletedBefore), count(TelemetryMetricPointsSink.TABLE, tenantId, deletedBefore))

	/**
	 * 두 테이블에 lightweight DELETE 를 보낸다. `lightweight_deletes_sync = 2` 라 mutation 이 끝난 뒤 반환한다 — 반환 뒤의 조회는 지운 행을
	 * 보지 않는다. 디스크의 물리 제거는 뒤의 merge 다.
	 */
	public fun delete(tenantId: String, deletedBefore: Instant) {
		for (table in TABLES) {
			client.execute(
				"DELETE FROM $table WHERE tenant_id = {tenant:String} AND source_time < {before:DateTime64(9, 'UTC')} " +
					"SETTINGS lightweight_deletes_sync = 2",
				params = params(tenantId, deletedBefore),
			)
		}
	}

	/** DELETE 뒤 두 테이블에 남은 경계 이전 행 수의 합. 0 이어야 논리 삭제 완료다. */
	public fun remaining(tenantId: String, deletedBefore: Instant): Long =
		TABLES.sumOf { count(it, tenantId, deletedBefore).rows }

	private fun count(table: String, tenantId: String, deletedBefore: Instant): Target {
		val line = client.execute(
			"SELECT uniqExact(observation_id), count() FROM $table " +
				"WHERE tenant_id = {tenant:String} AND source_time < {before:DateTime64(9, 'UTC')} FORMAT TSV",
			params = params(tenantId, deletedBefore),
		).trim()
		val (observations, rows) = line.split('\t').map { it.toLong() }
		return Target(observations, rows)
	}

	private fun params(tenantId: String, deletedBefore: Instant): Map<String, String> {
		require(tenantId.isNotBlank()) { "tenant 없이 지우지 않는다" }
		return mapOf("tenant" to tenantId, "before" to AnalysisRowWriter.formatTime(EpochNanos.of(deletedBefore)))
	}

	private companion object {
		val TABLES = listOf(TelemetryEventsSink.TABLE, TelemetryMetricPointsSink.TABLE)
	}
}
