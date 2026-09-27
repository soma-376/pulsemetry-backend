package com.team376.pulsemetry.dashboard.source

import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * 분석 원천(ClickHouse)의 **읽기 전용** 클라이언트 (ADR 0022 §4). 연결·실패 분류는 [ClickHouseConnection] 이 한다.
 *
 * 요청마다 싣는 설정:
 *
 * - `readonly=2` — 읽기만 허용하되 아래 설정은 바꿀 수 있게 한다. 계정 권한이 1차 강제이고 이것은 2차다.
 *   계정 프로필이 `readonly=1` 이면 설정 변경이 거부되므로 프로필은 2 이하여야 한다.
 * - `max_execution_time`·`max_result_rows`·`max_result_bytes` + `result_overflow_mode=throw` — 넘으면 **실패**한다.
 *   잘린 결과를 정상 총액으로 내지 않는다. 테이블 없이 상수로 접히는 한 행 결과에는 24.8 이 바이트 상한을 걸지 않는다(실측) —
 *   테이블을 읽는 조회에는 걸린다.
 */
class ClickHouseSourceReader(
	private val connection: ClickHouseConnection,
	queryTimeout: Duration,
	maxResultRows: Long,
	maxResultBytes: Long,
) {

	private val settings: Map<String, String> = mapOf(
		"readonly" to "2",
		"max_execution_time" to queryTimeout.toSeconds().toString(),
		"max_result_rows" to maxResultRows.toString(),
		"max_result_bytes" to maxResultBytes.toString(),
		"result_overflow_mode" to "throw",
	)

	fun <T> query(sql: String, params: Map<String, ClickHouseParam> = emptyMap(), row: (JsonNode) -> T): List<T> =
		connection.query(sql, settings, params, row)
}
