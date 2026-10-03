package com.team376.pulsemetry.dashboard.alert

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * [AlertEvaluator] 를 주기적으로 부른다 (ADR 0051 §5). 간격은 기본값 없는 설정이다. 실패는 로그만 남기고 다음 주기에 다시 한다 —
 * 시작점은 평가 기록에 있으므로 빠뜨린 관측 없이 이어진다.
 */
class AlertEvaluationJob(private val evaluator: AlertEvaluator) {

	private val log = LoggerFactory.getLogger(AlertEvaluationJob::class.java)

	@Scheduled(
		fixedDelayString = "\${pulsemetry.dashboard.alerts.evaluation-interval}",
		initialDelayString = "\${pulsemetry.dashboard.alerts.evaluation-interval}",
	)
	fun evaluate() {
		try {
			evaluator.runOnce()
		} catch (e: Exception) {
			log.error("알림 평가가 실패했다 — 다음 주기에 다시 한다", e)
		}
	}
}
