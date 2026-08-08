package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TelemetryTokenRepository : JpaRepository<TelemetryToken, UUID> {

	fun findByTokenHash(tokenHash: String): TelemetryToken?

	fun findAllByInstallationIdAndRevokedAtIsNull(installationId: UUID): List<TelemetryToken>

	/**
	 * 해당 installation 의 살아있는 telemetry token 을 전부 폐기한다.
	 * 재발급(PLAN.md §6.3)은 새 토큰을 만들기 전에 이걸 먼저 부른다.
	 *
	 * @return 폐기된 행 수. 이미 폐기된 토큰은 세지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"""
		UPDATE TelemetryToken t
		SET t.revokedAt = :now
		WHERE t.installationId = :installationId
		  AND t.revokedAt IS NULL
		""",
	)
	fun revokeActiveByInstallationId(
		@Param("installationId") installationId: UUID,
		@Param("now") now: Instant,
	): Int
}
