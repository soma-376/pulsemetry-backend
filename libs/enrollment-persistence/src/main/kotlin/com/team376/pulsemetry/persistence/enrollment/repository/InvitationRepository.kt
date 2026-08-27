package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.Invitation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface InvitationRepository : JpaRepository<Invitation, UUID> {

	/**
	 * 소비·폐기가 **실패한 이유**를 알아내기 위한 조회다.
	 * 이 결과를 보고 UPDATE 를 결정하지 마라 — 그러면 SELECT-then-UPDATE 경합이 생긴다 (PLAN.md A6).
	 */
	fun findByCodeHash(codeHash: String): Invitation?

	/**
	 * 초대 코드를 원자적으로 소비한다.
	 *
	 * 사용 여부·폐기 여부·만료 여부를 전부 WHERE 절에 넣어 한 문장으로 처리한다.
	 * 동시에 N 개의 요청이 같은 코드로 들어와도 DB 가 행을 잠그므로 **정확히 하나만 1을 받는다.**
	 *
	 * @return 영향 행 수. 1이면 소비 성공. 0이면 코드 없음/이미 사용/폐기됨/만료됨 중 하나이며,
	 *   어느 쪽인지는 [findByCodeHash] 로 따로 판별한다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"""
		UPDATE Invitation i
		SET i.usedAt = :now
		WHERE i.codeHash = :codeHash
		  AND i.usedAt IS NULL
		  AND i.revokedAt IS NULL
		  AND i.expiresAt > :now
		""",
	)
	fun consume(
		@Param("codeHash") codeHash: String,
		@Param("now") now: Instant,
	): Int

	/**
	 * 아직 쓰지도 폐기되지도 않은 초대를 폐기한다.
	 *
	 * @return 영향 행 수. 1이면 폐기 성공(204). 0이면 없음(404)/이미 사용(409)/이미 폐기(409) 중 하나다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"""
		UPDATE Invitation i
		SET i.revokedAt = :now
		WHERE i.id = :id
		  AND i.usedAt IS NULL
		  AND i.revokedAt IS NULL
		""",
	)
	fun revoke(
		@Param("id") id: UUID,
		@Param("now") now: Instant,
	): Int
}
