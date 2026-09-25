package com.team376.pulsemetry.dashboard.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 이 앱의 설정 표면.
 *
 * 운영 수치와 저장소 계정에는 **기본값이 없다**(ADR 0022 §4·§5). 비어 있으면 기동이 실패한다 —
 * 조용히 뜬 기본값이 배포 환경의 값처럼 보이면 안 된다. 저장소 계정은 역할마다 한 묶음이고(§4 표),
 * 캐시 쓰기용 두 묶음(`clickhouse.cache`·`rds.cache`)은 그 연결을 처음 쓰는 구현이 더한다.
 */
@ConfigurationProperties(prefix = "pulsemetry.dashboard")
data class DashboardApiProperties(

	/** 503 응답의 `Retry-After`. 초 단위로 싣는다. */
	val retryAfter: Duration,

	val clickhouse: ClickHouse,

	val rds: Rds,
) {
	init {
		require(retryAfter.toSeconds() >= 1) {
			"pulsemetry.dashboard.retry-after 는 1초 이상이어야 한다."
		}
	}

	data class ClickHouse(
		/** 분석 테이블·ledger 읽기. 계정은 SELECT 만 갖는다. */
		val source: ClickHouseSource,
	)

	/**
	 * 원천 읽기 연결. 상한 셋(시간·행·바이트)은 넘으면 잘라 내지 않고 실패한다 — 잘린 결과를 정상 총액으로 내지 않는다.
	 */
	data class ClickHouseSource(
		val url: String,
		val database: String,
		val username: String,
		/** 비어 있을 수 있다(로컬 기본 사용자). */
		val password: String,
		/** 서버의 `max_execution_time` 이자 HTTP 요청 제한 시간. */
		val queryTimeout: Duration,
		val maxResultRows: Long,
		val maxResultBytes: Long,
	) {
		init {
			require(url.isNotBlank()) { "pulsemetry.dashboard.clickhouse.source.url 이 비어 있다." }
			require(database.isNotBlank()) { "pulsemetry.dashboard.clickhouse.source.database 가 비어 있다." }
			require(username.isNotBlank()) { "pulsemetry.dashboard.clickhouse.source.username 이 비어 있다." }
			require(queryTimeout.toSeconds() >= 1) { "pulsemetry.dashboard.clickhouse.source.query-timeout 은 1초 이상이어야 한다." }
			require(maxResultRows >= 1) { "pulsemetry.dashboard.clickhouse.source.max-result-rows 는 1 이상이어야 한다." }
			require(maxResultBytes >= 1) { "pulsemetry.dashboard.clickhouse.source.max-result-bytes 는 1 이상이어야 한다." }
		}
	}

	data class Rds(
		/** RDS `enrollment`·`telemetry_ops` 읽기. 계정은 SELECT 만 갖는다. */
		val source: RdsSource,
	)

	data class RdsSource(
		val url: String,
		val username: String,
		/** 비어 있을 수 있다. */
		val password: String,
		/** 커넥션 획득 대기. 기본 30초 동안 서블릿 스레드를 잠식하지 않게 짧게 둔다. */
		val connectionTimeout: Duration,
	) {
		init {
			require(url.isNotBlank()) { "pulsemetry.dashboard.rds.source.url 이 비어 있다." }
			require(username.isNotBlank()) { "pulsemetry.dashboard.rds.source.username 이 비어 있다." }
		}
	}
}
