package com.team376.pulsemetry.dashboard.snapshot

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * [SnapshotCleaner] 를 주기적으로 부른다. 간격은 기본값 없는 설정이다. 실패는 로그만 남기고 다음 주기에 다시 한다 —
 * 정리가 늦어도 응답은 달라지지 않는다(유효기간은 manifest 가 정한다).
 */
class SnapshotCleanupJob(
	private val cleaner: SnapshotCleaner,
) {

	private val log = LoggerFactory.getLogger(SnapshotCleanupJob::class.java)

	@Scheduled(
		fixedDelayString = "\${pulsemetry.dashboard.snapshot.cleanup-interval}",
		initialDelayString = "\${pulsemetry.dashboard.snapshot.cleanup-interval}",
	)
	fun cleanup() {
		try {
			cleaner.run()
		} catch (e: Exception) {
			log.error("snapshot 정리가 실패했다 — 다음 주기에 다시 한다", e)
		}
	}
}
