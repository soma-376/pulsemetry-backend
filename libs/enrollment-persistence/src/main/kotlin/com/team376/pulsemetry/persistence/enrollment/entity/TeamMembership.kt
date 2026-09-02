package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 구성원의 팀 소속 **관계와 기간**.
 *
 * as-of 조인의 근거다 — 소속은 시점 함수이고, 이벤트를 "지금 어느 팀인가" 가 아니라
 * "그 이벤트 시각에 어느 팀이었나" 로 귀속한다 (허브 `contracts/data-model.md` D-3).
 * 경계는 **좌폐우개**다: `joinedAt <= at < leftAt`. [coversAt] 이 그 판정을 담는다.
 *
 * 한 구성원이 같은 시각에 여러 팀에 속할 수 있다 — 파이프라인의 `team_ids_as_of` 가
 * 단수가 아니라 배열인 이유이며, 그래서 DB 에도 "동시 소속은 하나" 같은 제약이 없다.
 */
@Entity
@Table(name = "team_memberships", schema = "enrollment")
class TeamMembership(

	@Column(name = "team_id", nullable = false)
	var teamId: UUID,

	@Column(name = "member_id", nullable = false)
	var memberId: UUID,

	@Id
	@Column(name = "id", nullable = false)
	var id: UUID = UUID.randomUUID(),

	@Column(name = "joined_at", nullable = false)
	var joinedAt: Instant = Instant.now(),

	/** `null` 이면 아직 소속 중이다. */
	@Column(name = "left_at")
	var leftAt: Instant? = null,
) {
	/** 이 소속이 [at] 시점을 덮는가. 좌폐우개(`joinedAt <= at < leftAt`). */
	fun coversAt(at: Instant): Boolean =
		!joinedAt.isAfter(at) && (leftAt == null || at.isBefore(leftAt))
}
