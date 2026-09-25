package com.team376.pulsemetry.retention.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 보존 작업의 설정 표면. **기본값이 없다** — 비어 있으면 기동이 실패한다. 지우는 작업이 조용히 뜬 기본값으로 다른 저장소를 가리키면 안 된다.
 * 보존 기간 수치는 여기 없다 — 명령의 인자다(ADR 0024 §5).
 */
@ConfigurationProperties(prefix = "pulsemetry.retention")
data class RetentionWorkerProperties(
	val clickhouse: ClickHouse,
	val drain: Drain,
	/** drain → DELETE → 검증의 최대 반복 수. */
	val maxPasses: Int,
) {
	init {
		require(maxPasses >= 1) { "pulsemetry.retention.max-passes 는 1 이상이어야 한다." }
	}

	data class ClickHouse(
		/** 분석 테이블·fence 가 있는 서버. 분석 INSERT 를 받는 바로 그 서버여야 drain 이 성립한다(ADR 0024 Context). */
		val url: String,
		val database: String,
		/** 응답 헤더까지의 대기. */
		val timeout: Duration,
	) {
		init {
			require(url.isNotBlank()) { "pulsemetry.retention.clickhouse.url 이 비어 있다." }
			require(database.isNotBlank()) { "pulsemetry.retention.clickhouse.database 가 비어 있다." }
			require(timeout.toSeconds() >= 1) { "pulsemetry.retention.clickhouse.timeout 은 1초 이상이어야 한다." }
		}
	}

	data class Drain(
		/** fence 를 쓴 순간 실행 중이던 INSERT 를 기다리는 상한. */
		val timeout: Duration,
		val pollInterval: Duration,
	) {
		init {
			require(!timeout.isNegative) { "pulsemetry.retention.drain.timeout 은 음수일 수 없다." }
			require(!pollInterval.isNegative && !pollInterval.isZero) { "pulsemetry.retention.drain.poll-interval 은 0 보다 커야 한다." }
		}
	}
}
