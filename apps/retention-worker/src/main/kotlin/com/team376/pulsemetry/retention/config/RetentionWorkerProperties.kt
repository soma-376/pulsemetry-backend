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
	/** 요청 모드(`--requests`)의 설정. 명령 하나를 실행하는 모드는 쓰지 않는다. 값이 모두 비면 null 로 묶인다. */
	val requests: Requests?,
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

	data class Requests(
		/**
		 * 요청 하나의 선점 기한(ADR 0047). 한 요청의 삭제 실행 전체(drain 상한 × 반복 수 + DELETE)보다 길어야 한다 — 지나면 다른 실행이 다시 잡는다.
		 * 없으면 요청 모드가 아무것도 하지 않고 종료 코드 2 로 끝난다. 기본값이 없다.
		 */
		val lease: Duration?,
		/** 한 요청을 몇 번까지 실행하는가. 그 안에 끝내지 못하면 작업을 실패로 닫는다. 없으면 요청 모드가 종료 코드 2 로 끝난다. */
		val maxRuns: Int?,
	) {
		init {
			require(lease == null || (!lease.isNegative && !lease.isZero)) { "pulsemetry.retention.requests.lease 는 0 보다 커야 한다." }
			require(maxRuns == null || maxRuns >= 1) { "pulsemetry.retention.requests.max-runs 는 1 이상이어야 한다." }
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
