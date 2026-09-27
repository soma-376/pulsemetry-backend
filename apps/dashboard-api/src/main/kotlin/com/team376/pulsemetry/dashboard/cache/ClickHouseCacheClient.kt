package com.team376.pulsemetry.dashboard.cache

import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * ClickHouse `dashboard_cache` 의 클라이언트 — 캐시 계정으로 DDL·snapshot 복사·payload 조회를 한다(ADR 0023 §1·§3).
 * 연결·실패 분류는 [ClickHouseConnection] 이 한다.
 *
 * 원천 읽기와 달리 `readonly` 를 걸지 않는다 — 이 계정이 쓰는 곳이 캐시다. 분석 테이블에 쓰지 못하는 것은 계정 권한이 강제한다.
 */
class ClickHouseCacheClient(
	private val connection: ClickHouseConnection,
	queryTimeout: Duration,
) {

	private val settings: Map<String, String> = mapOf(
		"max_execution_time" to queryTimeout.toSeconds().toString(),
	)

	/** 서버가 보고한 쓴 행 수를 돌려준다([ClickHouseConnection.execute]). */
	fun execute(sql: String, params: Map<String, ClickHouseParam> = emptyMap(), extraSettings: Map<String, String> = emptyMap()): Long =
		connection.execute(sql, settings + extraSettings, params)

	fun <T> query(sql: String, params: Map<String, ClickHouseParam> = emptyMap(), row: (JsonNode) -> T): List<T> =
		connection.query(sql, settings, params, row)
}
