package com.team376.pulsemetry.dashboard.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 이 앱의 설정 표면.
 *
 * 운영 수치와 저장소 계정에는 **기본값이 없다**(ADR 0022 §4·§5). 비어 있으면 기동이 실패한다 —
 * 조용히 뜬 기본값이 배포 환경의 값처럼 보이면 안 된다. 저장소 계정은 역할마다 한 묶음이다(§4 표 — 캐시 두 묶음의 권한은 ADR 0023 §3).
 */
@ConfigurationProperties(prefix = "pulsemetry.dashboard")
data class DashboardApiProperties(

	/** 503 응답의 `Retry-After`. 초 단위로 싣는다. */
	val retryAfter: Duration,

	val clickhouse: ClickHouse,

	val rds: Rds,

	val snapshot: Snapshot,

	val members: Members,
) {
	init {
		require(retryAfter.toSeconds() >= 1) {
			"pulsemetry.dashboard.retry-after 는 1초 이상이어야 한다."
		}
	}

	data class ClickHouse(
		/** 분석 테이블·ledger 읽기. 계정은 SELECT 만 갖는다. */
		val source: ClickHouseSource,

		/** `dashboard_cache` DDL·쓰기·읽기와 snapshot 복사의 원본 읽기. */
		val cache: ClickHouseCache,
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
			require(IDENTIFIER.matches(database)) { "pulsemetry.dashboard.clickhouse.source.database 가 식별자 형식이 아니다: '$database'" }
			require(username.isNotBlank()) { "pulsemetry.dashboard.clickhouse.source.username 이 비어 있다." }
			require(queryTimeout.toSeconds() >= 1) { "pulsemetry.dashboard.clickhouse.source.query-timeout 은 1초 이상이어야 한다." }
			require(maxResultRows >= 1) { "pulsemetry.dashboard.clickhouse.source.max-result-rows 는 1 이상이어야 한다." }
			require(maxResultBytes >= 1) { "pulsemetry.dashboard.clickhouse.source.max-result-bytes 는 1 이상이어야 한다." }
		}
	}

	/** 캐시 연결. 이 앱이 기동 때 `dashboard_cache` 의 DDL 을 적용한다(ADR 0023 §3). */
	data class ClickHouseCache(
		val url: String,
		/** infra 가 만든 캐시 DB 의 이름. SQL 식별자로 쓰이므로 형식을 검사한다. */
		val database: String,
		val username: String,
		/** 비어 있을 수 있다(로컬 기본 사용자). */
		val password: String,
		/** 서버의 `max_execution_time` 이자 HTTP 요청 제한 시간. */
		val queryTimeout: Duration,
	) {
		init {
			require(url.isNotBlank()) { "pulsemetry.dashboard.clickhouse.cache.url 이 비어 있다." }
			require(IDENTIFIER.matches(database)) { "pulsemetry.dashboard.clickhouse.cache.database 가 식별자 형식이 아니다: '$database'" }
			require(username.isNotBlank()) { "pulsemetry.dashboard.clickhouse.cache.username 이 비어 있다." }
			require(queryTimeout.toSeconds() >= 1) { "pulsemetry.dashboard.clickhouse.cache.query-timeout 은 1초 이상이어야 한다." }
		}
	}

	data class Rds(
		/** RDS `enrollment`·`telemetry_ops` 읽기. 계정은 SELECT 만 갖는다. */
		val source: RdsConnection,

		/** RDS `dashboard_cache` DDL·쓰기·읽기와 공개 CAS 의 삭제 경계 읽기. */
		val cache: RdsConnection,
	)

	data class RdsConnection(
		val url: String,
		val username: String,
		/** 비어 있을 수 있다. */
		val password: String,
		/** 커넥션 획득 대기. 기본 30초 동안 서블릿 스레드를 잠식하지 않게 짧게 둔다. */
		val connectionTimeout: Duration,
	) {
		init {
			require(url.isNotBlank()) { "pulsemetry.dashboard.rds.*.url 이 비어 있다." }
			require(username.isNotBlank()) { "pulsemetry.dashboard.rds.*.username 이 비어 있다." }
		}
	}

	/**
	 * snapshot 수명의 운영 수치 (ADR 0023 §1). 물리 정리 시각 = build 시작 + [buildTimeout] + API 수명 10분 + [purgeGrace].
	 */
	data class Snapshot(
		/** build 한 번의 제한 시간. manifest 의 `build_deadline` 이고 복사 문장의 `max_execution_time` 이다. */
		val buildTimeout: Duration,
		/** API 만료 뒤 진행 중인 조회를 위해 물리 행을 더 남기는 유예. */
		val purgeGrace: Duration,
		/** tenant 별 동시 build 수. 차 있으면 새 build 를 시작하지 않는다(503). */
		val maxConcurrentBuilds: Int,
		/** 복사 문장이 원본에서 읽을 수 있는 행 수(`max_rows_to_read`). 넘으면 잘라 내지 않고 실패한다. */
		val maxCopyRows: Long,
		/** 복사 문장이 원본에서 읽을 수 있는 바이트(`max_bytes_to_read`). */
		val maxCopyBytes: Long,
		/** 정리 작업의 주기. */
		val cleanupInterval: Duration,
	) {
		init {
			require(buildTimeout.toSeconds() >= 1) { "pulsemetry.dashboard.snapshot.build-timeout 은 1초 이상이어야 한다." }
			require(!purgeGrace.isNegative) { "pulsemetry.dashboard.snapshot.purge-grace 는 음수일 수 없다." }
			require(maxConcurrentBuilds >= 1) { "pulsemetry.dashboard.snapshot.max-concurrent-builds 는 1 이상이어야 한다." }
			require(maxCopyRows >= 1) { "pulsemetry.dashboard.snapshot.max-copy-rows 는 1 이상이어야 한다." }
			require(maxCopyBytes >= 1) { "pulsemetry.dashboard.snapshot.max-copy-bytes 는 1 이상이어야 한다." }
			require(cleanupInterval.toSeconds() >= 1) { "pulsemetry.dashboard.snapshot.cleanup-interval 은 1초 이상이어야 한다." }
		}
	}

	/** 구성원 화면의 정책 값. 저장된 조직 정책이 아직 없어 설정으로 받는다 — 기본값이 없다. */
	data class Members(
		/** 회수 후보의 유휴 기준 일수. 요청서가 정한 값(7·14·30·60) 중 하나. */
		val idleDays: Int,
	) {
		init {
			require(idleDays in IDLE_DAYS) { "pulsemetry.dashboard.members.idle-days 는 $IDLE_DAYS 중 하나여야 한다: $idleDays" }
		}
	}

	private companion object {
		val IDLE_DAYS = setOf(7, 14, 30, 60)

		/** DB 이름은 snapshot 복사 SQL 에 식별자로 들어간다. */
		val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
	}
}
