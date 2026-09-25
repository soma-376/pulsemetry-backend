package com.team376.pulsemetry.dashboard.store

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 서버 쪽 바인딩 파라미터. 쿼리 본문에 `{name:Type}` 으로 쓰고 값은 URL 의 `param_<name>` 으로 보낸다 —
 * 값을 SQL 문자열에 이어 붙이지 않는다.
 */
data class ClickHouseParam(val type: String, val value: String) {

	companion object {
		private val DATETIME64: DateTimeFormatter =
			DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC)

		fun string(value: String) = ClickHouseParam("String", value)

		fun uuid(value: UUID) = ClickHouseParam("String", value.toString())

		/** 나노초 정밀도, UTC. 분석 테이블의 `source_time` 과 같은 타입이다(ADR 0020 §1). */
		fun instant(value: Instant) = ClickHouseParam("DateTime64(9, 'UTC')", DATETIME64.format(value))

		fun date(value: LocalDate) = ClickHouseParam("Date", value.toString())

		fun uint64(value: Long): ClickHouseParam {
			require(value >= 0) { "UInt64 파라미터는 음수일 수 없다: $value" }
			return ClickHouseParam("UInt64", value.toString())
		}
	}
}
