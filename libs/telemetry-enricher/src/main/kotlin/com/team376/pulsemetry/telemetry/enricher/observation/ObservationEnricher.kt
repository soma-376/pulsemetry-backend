package com.team376.pulsemetry.telemetry.enricher.observation

import com.team376.pulsemetry.persistence.enrollment.entity.TeamMembership
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationBatch
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationEnvelope
import com.team376.pulsemetry.telemetry.adapter.observation.OrgAttribution
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.withFlags
import com.team376.pulsemetry.telemetry.enricher.EnrichmentUnavailableException
import com.team376.pulsemetry.telemetry.enricher.provider.EnrichmentProvider
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.CannotCreateTransactionException
import java.util.UUID

/**
 * 관측 모델(ADR 0020)의 보강 단계 — 구성원과 이벤트 시점의 대표 팀을 붙인다(§5). **행을 드롭하지 않는다.**
 *
 * - `member_id` 는 인증된 `installation_id` 의 구성원이다. 소속과 상태에 무관한 installation 단독 조회라 무소속
 *   구성원과 revoked installation 도 채운다. 조회되지 않으면 null + `member_unresolved` 다.
 * - `team_ids_as_of` 는 관측의 `source_time` 에 유효한 소속(`joined_at <= t < left_at`)의 **서로 다른 팀 ID** 를
 *   정렬한 것이다. 팀의 현재 상태는 보지 않는다.
 * - `team_id_as_of` 는 그 목록이 하나면 그 팀, 둘 이상이면 null + `multi_team_membership`, 없으면 null 이다. 하나를
 *   고르는 규칙은 두지 않는다 — 대표 팀 정책은 제품 결정으로 남아 있다(ADR 0020 Follow-up).
 * - `enrichment_json` 은 이전 적재(`enriched_events`)가 쓰던 모양 그대로다 — `org` 항목의 `team_ids` 와 provider 마다의 항목(스텁 셋은 빈 객체,
 *   ADR 0017 규칙 8). 승격 컬럼 셋은 이 항목이 아니다(규칙 7).
 *
 * `observation_id` 와 `analysis_hash` 는 다시 계산하지 않는다. 보강 결과는 둘의 재료가 아니다(§3).
 *
 * ## 조회와 캐시
 *
 * [enrich] 한 번이 push 하나다. installation 마다 구성원과 그 소속 이력 전부를 한 번 읽고, 관측마다 시점으로 자른다.
 * 캐시는 호출을 넘지 않는다 — 소속 변경은 다음 push 부터 반영되고, 이미 적재된 행은 재처리로 갱신된다.
 * `enrollment` 스키마는 읽기만 한다(허브 `contracts/data-model.md` D-2).
 *
 * 오류 분류: 연결·트랜잭션 시작 실패·실행 중 끊김만 [EnrichmentUnavailableException](앱이 503)이고 나머지는 그대로
 * 전파한다 — 스키마 드리프트 같은 영구 오류는 앱이 400 으로 돌린다. **넓히지 마라**(그 예외의 KDoc).
 *
 * @param providers `enrichment_json` 에 항목을 쓰는 provider. 이름이 겹치거나 `org` 이면 거부한다 — `org` 항목은 이
 *   클래스가 쓴다.
 */
public class ObservationEnricher(
	private val installations: InstallationRepository,
	private val teamMemberships: TeamMembershipRepository,
	providers: List<EnrichmentProvider>,
) {

	private val providers: List<EnrichmentProvider> = providers.sortedWith(compareBy({ it.order }, { it.name }))

	init {
		val names = providers.map { it.name }
		require(names.size == names.toSet().size) { "provider 이름이 겹친다: $names" }
		require(ORG !in names) { "`$ORG` 항목은 ObservationEnricher 가 쓴다 — provider 목록에 넣지 않는다" }
	}

	/** push 하나를 보강한다. 이벤트와 metric point 는 같은 조회 캐시를 쓴다. */
	public fun enrich(batch: ObservationBatch): EnrichedBatch = enrich(listOf(batch)).single()

	/**
	 * push 하나의 여러 배치(아카이브 제품 문서마다 하나)를 보강한다. 배치들은 **한 조회 캐시**를 쓴다 — push 가 하나다.
	 * 결과는 입력 배치와 같은 순서다.
	 */
	public fun enrich(batches: List<ObservationBatch>): List<EnrichedBatch> {
		val push = Push()
		return batches.map { batch ->
			EnrichedBatch(
				events = batch.events.map { event ->
					val (org, flags) = push.attribute(event.envelope)
					EnrichedEvent(event.withFlags(*flags), org)
				},
				metricPoints = batch.metricPoints.map { point ->
					val (org, flags) = push.attribute(point.envelope)
					EnrichedMetricPoint(point.copy(envelope = point.envelope.withFlags(*flags)), org)
				},
				stats = batch.stats,
			)
		}
	}

	/** push 하나의 작업 공간 — installation 조회 캐시와 provider 문맥. */
	private inner class Push {
		private val resolved = HashMap<String, Membership>()
		private val ctx: MutableMap<String, Any?> = HashMap()

		fun attribute(envelope: ObservationEnvelope): Pair<OrgAttribution, Array<QualityFlag>> {
			val membership = resolved.getOrPut(envelope.installationId) { resolve(envelope.installationId) }
			val at = envelope.sourceTime.toInstant()
			val teamIds = membership.history.filter { it.coversAt(at) }.map { it.teamId.toString() }.distinct().sorted()

			val annotations = HashMap<String, Any?>()
			annotations[ORG] = mapOf(TEAM_IDS to teamIds)
			for (provider in providers) annotations[provider.name] = provider.annotate(envelope, ctx)

			val flags = buildList {
				if (membership.memberId == null) add(QualityFlag.MEMBER_UNRESOLVED)
				if (teamIds.size > 1) add(QualityFlag.MULTI_TEAM_MEMBERSHIP)
			}
			val org = OrgAttribution(
				memberId = membership.memberId?.toString(),
				teamIdAsOf = teamIds.singleOrNull(),
				teamIdsAsOf = teamIds,
				enrichmentJson = EnrichmentJson.sorted(annotations),
				enrichmentVersion = ENRICHMENT_VERSION,
			)
			return org to flags.toTypedArray()
		}
	}

	/** installation 의 구성원과 그 소속 이력 전부. 구성원이 없으면 이력도 없다. */
	private class Membership(val memberId: UUID?, val history: List<TeamMembership>)

	/**
	 * installation 을 구성원과 소속 이력으로 푼다. UUID 가 아닌 ID 는 어느 행과도 맞지 않으므로 조회하지 않고 미해결이다 —
	 * 인증 경로는 그런 ID 를 내지 않지만, 여기서 던지면 분류되지 않은 예외가 되어 재시도만 반복된다.
	 */
	private fun resolve(installationId: String): Membership {
		val id = try {
			UUID.fromString(installationId)
		} catch (_: IllegalArgumentException) {
			return Membership(null, emptyList())
		}
		return classified {
			val memberId = installations.findMemberIdById(id) ?: return@classified Membership(null, emptyList())
			Membership(memberId, teamMemberships.findAllByMemberId(memberId))
		}
	}

	/** 일시 장애만 [EnrichmentUnavailableException] 으로 감싼다. */
	private inline fun <T> classified(block: () -> T): T =
		try {
			block()
		} catch (exception: DataAccessResourceFailureException) {
			throw EnrichmentUnavailableException("rds unreachable: ${exception.message}", exception)
		} catch (exception: CannotCreateTransactionException) {
			throw EnrichmentUnavailableException("rds unreachable: ${exception.message}", exception)
		} catch (exception: TransientDataAccessException) {
			throw EnrichmentUnavailableException("rds transient failure: ${exception.message}", exception)
		} catch (exception: RecoverableDataAccessException) {
			throw EnrichmentUnavailableException("rds transient failure: ${exception.message}", exception)
		}

	public companion object {
		/**
		 * 보강 규칙의 버전(`enrichment_version`). 구성원 조회·as-of 판정·대표 팀 규칙·`enrichment_json` 모양 중 하나라도
		 * 바뀌면 올린다(ADR 0020 §5).
		 */
		public const val ENRICHMENT_VERSION: String = "enrichment-v1"

		/** `enrichment_json` 의 조직 항목 이름. 구 경로와 같다. */
		public const val ORG: String = "org"

		/** 조직 항목 안의 as-of 팀 목록 키. 구 경로와 같다. */
		public const val TEAM_IDS: String = "team_ids"
	}
}
