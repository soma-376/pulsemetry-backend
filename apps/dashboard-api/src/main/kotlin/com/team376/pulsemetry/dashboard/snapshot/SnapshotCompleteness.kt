package com.team376.pulsemetry.dashboard.snapshot

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 완전한 날짜를 snapshot 에 고정한다 (ADR 0042). 근거(설치·수집 구간·정책 판)는 원천 계정으로 읽고, 판정 결과는 캐시 계정으로
 * `dashboard_cache.snapshot_complete_days` 에 쓴다. 한 번 고정한 결과는 그 snapshot 이 끝날 때까지 바뀌지 않는다.
 *
 * 근거를 읽지 못하면 build 가 실패한다 — 읽지 못한 것을 "완전한 날이 없다"로 바꾸지 않는다.
 */
class SnapshotCompleteness(
	private val source: JdbcClient,
	private val cache: JdbcClient,
	/** 하루가 끝난 뒤 그날을 확정하기까지 기다리는 시간(전송·적재가 끝나는 시간). 필수 설정이다. */
	private val settle: Duration,
) {
	init {
		require(!settle.isNegative && !settle.isZero) { "확정 대기 시간은 0보다 커야 한다: $settle" }
	}

	/** [dates] 중 완전한 날짜를 계산해 [snapshotId] 에 고정하고 그 수를 돌려준다. */
	fun fix(snapshotId: String, tenantId: UUID, dates: Collection<LocalDate>, zone: ZoneId, asOf: Instant, deletedBefore: Instant?): Int {
		if (dates.isEmpty()) return 0
		val from = dates.min().atStartOfDay(zone).toInstant()
		val until = dates.max().plusDays(1).atStartOfDay(zone).toInstant().plus(settle)
		val evidence = Completeness.Evidence(installations(tenantId, from, until), policies(tenantId), deletedBefore)
		val complete = Completeness.completeDates(dates, zone, evidence, asOf, settle)
		for (date in complete) {
			cache.sql("INSERT INTO dashboard_cache.snapshot_complete_days (snapshot_id, complete_date) VALUES (:snapshot, :date)")
				.param("snapshot", snapshotId)
				.param("date", date)
				.update()
		}
		return complete.size
	}

	/** tenant 의 모든 설치(폐기된 것 포함)와 [from, until] 과 겹치는 손실 없는 구간. */
	private fun installations(tenantId: UUID, from: Instant, until: Instant): List<Completeness.Installation> {
		val spans = source.sql(
			"""SELECT s.installation_id, s.from_at, s.to_at FROM enrollment.installation_collection_segments s
			JOIN enrollment.installations i ON i.id = s.installation_id
			WHERE i.tenant_id = :tenant AND s.lost = 0 AND s.to_at >= :from AND s.from_at <= :until""",
		)
			.param("tenant", tenantId)
			.param("from", Timestamp.from(from))
			.param("until", Timestamp.from(until))
			.query { rs, _ ->
				rs.getObject("installation_id", UUID::class.java) to
					Completeness.Span(rs.getTimestamp("from_at").toInstant(), rs.getTimestamp("to_at").toInstant())
			}
			.list()
			.groupBy({ it.first }, { it.second })
		return source.sql("SELECT id, created_at, revoked_at FROM enrollment.installations WHERE tenant_id = :tenant")
			.param("tenant", tenantId)
			.query { rs, _ ->
				val id = rs.getObject("id", UUID::class.java)
				Completeness.Installation(
					id = id,
					createdAt = rs.getTimestamp("created_at").toInstant(),
					// 폐기 시각을 모르는 폐기 설치는 폐기되지 않은 것처럼 끝까지 덮여야 한다 — 보고가 끊긴 뒤는 덮이지 않으므로 완전해지지 않는다.
					revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
					lossless = spans[id].orEmpty(),
				)
			}
			.list()
	}

	/** 활성화된 적 있는 판. `signals.logs` 가 참이 아닌 판(값이 없는 판 포함)은 사용량을 수집하지 않는 것으로 본다. */
	private fun policies(tenantId: UUID): List<Completeness.Policy> =
		source.sql(
			"""SELECT activated_at, COALESCE((manifest -> 'signals' ->> 'logs')::boolean, false) AS logs
			FROM enrollment.manifests WHERE tenant_id = :tenant AND activated_at IS NOT NULL ORDER BY activated_at""",
		)
			.param("tenant", tenantId)
			.query { rs, _ -> Completeness.Policy(rs.getTimestamp("activated_at").toInstant(), rs.getBoolean("logs")) }
			.list()
}
