package com.team376.pulsemetry.dashboard.request

import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.ErrorResponse
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 조회 파라미터의 공통 해석. endpoint 는 자기가 받는 파라미터만 읽고, 나머지는 무시한다.
 *
 * **오류를 모아서 한 번에 400 으로 낸다.** [read] 블록 안의 각 읽기는 틀린 값을 만나면 필드 오류를 적고 자리표시 값을 돌려준다.
 * 블록이 끝났을 때 오류가 하나라도 있으면 [DashboardException](`invalid_request` + `fieldErrors`)을 던지므로
 * 자리표시 값은 블록 밖으로 나가지 않는다.
 *
 * ```kotlin
 * val query = QueryReader(request::getParameter).read { OverviewQuery(comparedPeriod()) }
 * ```
 */
class QueryReader(private val parameter: (String) -> String?) {

	private val errors = mutableListOf<ErrorResponse.FieldError>()

	fun <T> read(block: QueryReader.() -> T): T {
		val result = block()
		if (errors.isNotEmpty()) throw DashboardException(ErrorCode.INVALID_REQUEST, errors.toList())
		return result
	}

	/**
	 * `startDate`·`endDate`(필수, 실제로 있는 `YYYY-MM-DD`, 종료일 포함, 1–366일)와 `timeZone`(생략 시 Asia/Seoul, 그 밖은 400).
	 * 미래·수집 이전 날짜도 유효하다 — 데이터 부재는 400 이 아니라 응답의 상태로 낸다.
	 */
	fun period(): DatePeriod {
		val zone = timeZone()
		val start = date(START_DATE)
		val end = date(END_DATE)
		if (start == null || end == null) return placeholder(zone)
		if (end.isBefore(start)) return fail(END_DATE, FieldErrorCode.INVALID_ORDER, placeholder(zone))
		val period = DatePeriod(start, end, zone)
		if (period.days > MAX_PERIOD_DAYS) return fail(END_DATE, FieldErrorCode.OUT_OF_RANGE, placeholder(zone))
		return period
	}

	/** [period] + `compare`(`prev_week` 기본 · `prev_period` · `none`). 개요·팀 분석만 쓴다. */
	fun comparedPeriod(): ComparedPeriod = ComparedPeriod(period(), choice(COMPARE, CompareMode.BY_WIRE, CompareMode.PREV_WEEK))

	/** `limit`(생략 시 [default], 1–[max] 밖은 400 — 잘라 쓰지 않는다)과 `cursor`. */
	fun page(default: Int, max: Int, codec: PageCursorCodec): PageRequest {
		require(default in 1..max) { "limit 기본값이 범위 밖이다: $default (최대 $max)" }
		val limit = when (val raw = parameter(LIMIT)) {
			null -> default
			else -> raw.toIntOrNull()?.let { if (it in 1..max) it else fail(LIMIT, FieldErrorCode.OUT_OF_RANGE, default) }
				?: fail(LIMIT, FieldErrorCode.INVALID_FORMAT, default)
		}
		val cursor = parameter(CURSOR)?.let { codec.decode(it) ?: fail(CURSOR, FieldErrorCode.INVALID_CURSOR, null) }
		return PageRequest(limit, cursor)
	}

	/** `q` — 최대 200자의 검색어. 비어 있으면 검색하지 않는다. */
	fun search(): String? {
		val raw = parameter(SEARCH)?.takeIf { it.isNotEmpty() } ?: return null
		if (raw.codePointCount(0, raw.length) > MAX_SEARCH_LENGTH) return fail(SEARCH, FieldErrorCode.OUT_OF_RANGE, null)
		return raw
	}

	/** 허용 목록에서 고르는 파라미터. 생략하면 [default] 이고, [default] 가 없으면 필수다. */
	fun <E : Any> choice(name: String, allowed: Map<String, E>, default: E?): E {
		val raw = parameter(name)
		if (raw == null) return default ?: fail(name, FieldErrorCode.REQUIRED, allowed.values.first())
		return allowed[raw] ?: fail(name, FieldErrorCode.UNSUPPORTED_VALUE, default ?: allowed.values.first())
	}

	private fun timeZone(): ZoneId {
		val raw = parameter(TIME_ZONE) ?: return SEOUL
		if (raw != SEOUL_ID) return fail(TIME_ZONE, FieldErrorCode.UNSUPPORTED_VALUE, SEOUL)
		return SEOUL
	}

	private fun date(name: String): LocalDate? {
		val raw = parameter(name) ?: return fail(name, FieldErrorCode.REQUIRED, null)
		if (!DATE_SHAPE.matches(raw)) return fail(name, FieldErrorCode.INVALID_FORMAT, null)
		return try {
			LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
		} catch (_: DateTimeParseException) {
			fail(name, FieldErrorCode.INVALID_FORMAT, null)
		}
	}

	private fun <T> fail(field: String, code: FieldErrorCode, placeholder: T): T {
		errors += ErrorResponse.FieldError(field, code.wire)
		return placeholder
	}

	private fun placeholder(zone: ZoneId) = DatePeriod(LocalDate.EPOCH, LocalDate.EPOCH, zone)

	companion object {
		const val START_DATE = "startDate"
		const val END_DATE = "endDate"
		const val TIME_ZONE = "timeZone"
		const val COMPARE = "compare"
		const val LIMIT = "limit"
		const val CURSOR = "cursor"
		const val SEARCH = "q"

		/** v1 이 받는 유일한 시간대. */
		const val SEOUL_ID = "Asia/Seoul"
		val SEOUL: ZoneId = ZoneId.of(SEOUL_ID)

		const val MAX_PERIOD_DAYS = 366
		const val MAX_SEARCH_LENGTH = 200

		/** ISO 파서는 부호 붙은 다섯 자리 연도도 받는다. 모양을 먼저 고정한다. */
		private val DATE_SHAPE = Regex("\\d{4}-\\d{2}-\\d{2}")
	}
}

/** 목록 한 페이지의 요청. [cursor] 가 없으면 첫 페이지다. */
data class PageRequest(
	val limit: Int,
	val cursor: PageCursor?,
)
