package com.team376.pulsemetry.telemetry.enricher.provider

import com.team376.pulsemetry.persistence.enrollment.entity.TeamMembership
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.telemetry.enricher.Enriched
import com.team376.pulsemetry.telemetry.enricher.EnrichmentUnavailableException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.CannotCreateTransactionException
import java.time.Instant
import java.util.UUID
import kotlin.math.floor

/**
 * 조직 provider — `installation_id` 를 **이벤트 발생 시각의** 팀 소속으로 해석한다.
 *
 * 유일하게 RDS 를 읽는 provider 이고 `order = 0` 이라 가장 먼저 돈다. whitelist 컬럼
 * [Enriched.teamIdsAsOf] 를 채우는 것도 이 provider 뿐이다(파이프라인 ADR 0006).
 *
 * ⚠️ 구 레포 `enrich.py` 의 docstring("enrichment 는 RDS 에 접속하지 않는다")은 **스테일이다.**
 * 코드가 기준이고, 코드는 접속한다.
 *
 * ## 조회는 이미 있는 것을 쓴다
 *
 * [TeamMembershipRepository.findActiveTeamMembershipsByInstallationId] 가 구
 * `_MEMBERSHIP_SQL` 과 조인·필터가 1:1 이고, 시점 판정은 [TeamMembership.coversAt] 이
 * **좌폐우개**(`joinedAt <= at < leftAt`)로 한다. 시점을 SQL 에 넣지 않는 이유는 그 KDoc 에 있다 —
 * push 하나가 서로 다른 `ts` 의 이벤트를 담으므로 installation 당 한 번만 읽는 편이 싸다.
 *
 * ## 이 모듈은 쓰지 않는다
 *
 * `team_memberships` 의 쓰기 소유는 관리자 API 그대로다(ADR 0008 규칙 1). 여기는 읽기 전용
 * 소비자다 — 허브 `contracts/data-model.md` D-2 가 파이프라인에 건 제약이다.
 */
public class OrgProvider(
	private val teamMemberships: TeamMembershipRepository,
) : EnrichmentProvider {

	override val name: String = NAME

	override val order: Int = 0

	override fun enrich(item: Enriched, ctx: MutableMap<String, Any?>): Map<String, Any?> {
		val installationId = item.event.envelope.identity.installationId
		// 신뢰 키가 없으면 **조회 자체를 하지 않는다.** 캐시 항목도 만들지 않는다 —
		// 빈 주석(`{}`)과 "조회했는데 소속이 없다"(`{"team_ids": []}`)는 다른 사실이다.
		if (installationId.isNullOrEmpty()) return emptyMap()

		val memberships = cache(ctx).getOrPut(installationId) { load(installationId) }
		val at = instantOf(item.timestamp)
		val teamIds = memberships.filter { it.coversAt(at) }.map { it.teamId.toString() }

		item.teamIdsAsOf = teamIds
		return mapOf(TEAM_IDS to teamIds)
	}

	/**
	 * push 단위 캐시. 같은 installation 은 push 하나에 한 번만 조회한다.
	 *
	 * TTL 도 push 를 넘는 캐시도 없다 — 소속이 바뀌면 다음 push 부터 즉시 반영된다.
	 * 테스트는 이 키를 미리 채워 조회를 가로챈다.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun cache(ctx: MutableMap<String, Any?>): MutableMap<String, List<TeamMembership>> =
		ctx.getOrPut(CACHE_KEY) { HashMap<String, List<TeamMembership>>() }
			as MutableMap<String, List<TeamMembership>>

	private fun load(installationId: String): List<TeamMembership> =
		try {
			// UUID 가 아니면 여기서 던지고 그대로 전파한다 — 일시 장애가 아니라 잘못된 입력이다.
			teamMemberships.findActiveTeamMembershipsByInstallationId(UUID.fromString(installationId))
		} catch (exception: DataAccessResourceFailureException) {
			// 연결 계열만 잡는다. 스키마 드리프트(InvalidDataAccessResourceUsageException)는
			// 영구 오류라 전파해야 한다 — 이식 원본이 OperationalError 만 잡은 것과 같다.
			throw EnrichmentUnavailableException("rds unreachable: ${exception.message}", exception)
		} catch (exception: CannotCreateTransactionException) {
			// 리포지토리 호출이 트랜잭션을 열다 실패하는 경로. 커넥션을 못 얻은 것이므로 위와 같은 사실이다.
			throw EnrichmentUnavailableException("rds unreachable: ${exception.message}", exception)
		}

	/**
	 * epoch 초(소수부 포함) → [Instant].
	 *
	 * 초와 나노를 나눠 만든다. `timestamp * 1e9` 를 한 번에 반올림하면 현재 시각대에서
	 * `Double` 의 정수 정밀도(2^53)를 넘어 수백 나노초가 어긋난다.
	 */
	private fun instantOf(timestamp: Double): Instant {
		val seconds = floor(timestamp).toLong()
		val nanos = Math.round((timestamp - seconds) * NANOS_PER_SECOND)
		return Instant.ofEpochSecond(seconds, nanos)
	}

	public companion object {
		public const val NAME: String = "org"

		/** 주석 맵의 키. `enrichment_json` 안에서 `{"org": {"team_ids": [...]}}` 로 나타난다. */
		public const val TEAM_IDS: String = "team_ids"

		/** [ctx] 안의 캐시 키. */
		public const val CACHE_KEY: String = "_org_memberships"

		private const val NANOS_PER_SECOND: Double = 1_000_000_000.0
	}
}
