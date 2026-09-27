package com.team376.pulsemetry.dashboard.request

import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.ErrorResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

/**
 * 공통 요청 해석. 기대값은 화면 요청서의 개요 명세 2절(요청)·공통 조회 계약에서 쓴다.
 */
class QueryReaderTest {

	private val codec = PageCursorCodec(JsonMapper.builder().build())

	private fun reader(vararg params: Pair<String, String>) = QueryReader(mapOf(*params)::get)

	private fun errorsOf(block: () -> Unit): List<ErrorResponse.FieldError> {
		var caught: DashboardException? = null
		try {
			block()
		} catch (e: DashboardException) {
			caught = e
		}
		assertThat(caught).describedAs("400 이어야 한다").isNotNull
		assertThat(caught!!.code).isEqualTo(ErrorCode.INVALID_REQUEST)
		return caught.fieldErrors
	}

	@Nested
	inner class Period {

		@Test
		@DisplayName("명세의 예시 — 2026-09-07..13 은 [2026-09-06T15:00Z, 2026-09-13T15:00Z) 7일이다")
		fun specExample() {
			val period = reader("startDate" to "2026-09-07", "endDate" to "2026-09-13", "timeZone" to "Asia/Seoul")
				.read { period() }

			assertThat(period.days).isEqualTo(7)
			assertThat(period.from).isEqualTo(Instant.parse("2026-09-06T15:00:00Z"))
			assertThat(period.until).isEqualTo(Instant.parse("2026-09-13T15:00:00Z"))
			assertThat(period.dates()).hasSize(7).startsWith(LocalDate.of(2026, 9, 7)).endsWith(LocalDate.of(2026, 9, 13))
		}

		@Test
		@DisplayName("timeZone 을 생략하면 Asia/Seoul 이다")
		fun defaultTimeZone() {
			val period = reader("startDate" to "2026-09-07", "endDate" to "2026-09-07").read { period() }

			assertThat(period.zone.id).isEqualTo("Asia/Seoul")
			assertThat(period.from).isEqualTo(Instant.parse("2026-09-06T15:00:00Z"))
			assertThat(period.until).isEqualTo(Instant.parse("2026-09-07T15:00:00Z"))
		}

		@Test
		@DisplayName("하루(시작일 = 종료일)와 366일은 유효하고 367일은 400 이다")
		fun lengthBounds() {
			assertThat(reader("startDate" to "2026-09-07", "endDate" to "2026-09-07").read { period() }.days).isEqualTo(1)
			// 2028 은 윤년 — 2028-01-01..2028-12-31 이 366일이다.
			assertThat(reader("startDate" to "2028-01-01", "endDate" to "2028-12-31").read { period() }.days).isEqualTo(366)
			assertThat(errorsOf { reader("startDate" to "2027-01-01", "endDate" to "2028-01-02").read { period() } })
				.containsExactly(ErrorResponse.FieldError("endDate", "out_of_range"))
		}

		@Test
		@DisplayName("종료일이 시작일보다 앞이면 400 invalid_order")
		fun reversed() {
			assertThat(errorsOf { reader("startDate" to "2026-09-13", "endDate" to "2026-09-07").read { period() } })
				.containsExactly(ErrorResponse.FieldError("endDate", "invalid_order"))
		}

		@ParameterizedTest
		@ValueSource(strings = ["2026-02-29", "2026-13-01", "2026-9-7", "20260907", "+2026-09-07", "2026-09-07T00:00", " 2026-09-07", ""])
		@DisplayName("형식이 틀리거나 없는 날짜는 400 invalid_format")
		fun malformedDate(value: String) {
			assertThat(errorsOf { reader("startDate" to value, "endDate" to "2026-09-07").read { period() } })
				.containsExactly(ErrorResponse.FieldError("startDate", "invalid_format"))
		}

		@Test
		@DisplayName("둘 다 없으면 두 필드 모두 required — 오류를 모아서 낸다")
		fun missingBoth() {
			assertThat(errorsOf { reader().read { period() } }).containsExactly(
				ErrorResponse.FieldError("startDate", "required"),
				ErrorResponse.FieldError("endDate", "required"),
			)
		}

		@ParameterizedTest
		@ValueSource(strings = ["UTC", "asia/seoul", "Asia/Tokyo", "+09:00", ""])
		@DisplayName("Asia/Seoul 이 아닌 timeZone 은 400 unsupported_value")
		fun unsupportedTimeZone(value: String) {
			assertThat(
				errorsOf { reader("startDate" to "2026-09-07", "endDate" to "2026-09-07", "timeZone" to value).read { period() } },
			).containsExactly(ErrorResponse.FieldError("timeZone", "unsupported_value"))
		}

		@Test
		@DisplayName("미래 날짜도 유효한 요청이다")
		fun futureDateIsValid() {
			assertThat(reader("startDate" to "2099-01-01", "endDate" to "2099-01-31").read { period() }.days).isEqualTo(31)
		}
	}

	@Nested
	inner class Compare {

		private fun compared(vararg extra: Pair<String, String>, start: String = "2026-09-07", end: String = "2026-09-13") =
			reader("startDate" to start, "endDate" to end, *extra).read { comparedPeriod() }

		@Test
		@DisplayName("compare 를 생략하면 prev_week — 양 끝을 7일 앞으로 옮긴다")
		fun defaultIsPrevWeek() {
			val compared = compared()

			assertThat(compared.mode).isEqualTo(CompareMode.PREV_WEEK)
			assertThat(compared.previous!!.startDate).isEqualTo(LocalDate.of(2026, 8, 31))
			assertThat(compared.previous!!.endDate).isEqualTo(LocalDate.of(2026, 9, 6))
		}

		@Test
		@DisplayName("28일 선택의 prev_week 는 28일이고 현재 기간과 겹친다")
		fun prevWeekOverlapsLongPeriods() {
			val compared = compared("compare" to "prev_week", start = "2026-09-01", end = "2026-09-28")

			assertThat(compared.previous!!.days).isEqualTo(28)
			assertThat(compared.previous!!.startDate).isEqualTo(LocalDate.of(2026, 8, 25))
			assertThat(compared.previous!!.endDate).isEqualTo(LocalDate.of(2026, 9, 21))
		}

		@Test
		@DisplayName("prev_period 는 선택한 N일 바로 앞의 N일이다")
		fun prevPeriod() {
			val compared = compared("compare" to "prev_period", start = "2026-09-01", end = "2026-09-28")

			assertThat(compared.previous!!.startDate).isEqualTo(LocalDate.of(2026, 8, 4))
			assertThat(compared.previous!!.endDate).isEqualTo(LocalDate.of(2026, 8, 31))
		}

		@Test
		@DisplayName("none 이면 비교 기간이 없다")
		fun none() {
			assertThat(compared("compare" to "none").previous).isNull()
		}

		@ParameterizedTest
		@ValueSource(strings = ["PREV_WEEK", "prev_month", "", "week"])
		@DisplayName("그 밖의 compare 는 400 unsupported_value")
		fun unsupported(value: String) {
			assertThat(errorsOf { compared("compare" to value) })
				.containsExactly(ErrorResponse.FieldError("compare", "unsupported_value"))
		}
	}

	@Nested
	inner class Page {

		private fun page(vararg params: Pair<String, String>) = reader(*params).read { page(default = 20, max = 100, codec = codec) }

		@Test
		@DisplayName("limit 을 생략하면 기본값, 1 과 최대값은 유효하다")
		fun limitBounds() {
			assertThat(page().limit).isEqualTo(20)
			assertThat(page("limit" to "1").limit).isEqualTo(1)
			assertThat(page("limit" to "100").limit).isEqualTo(100)
		}

		@ParameterizedTest
		@ValueSource(strings = ["0", "101", "-1", "2147483648"])
		@DisplayName("범위 밖 limit 은 잘라 쓰지 않고 400")
		fun limitOutOfRange(value: String) {
			val expected = if (value == "2147483648") "invalid_format" else "out_of_range"
			assertThat(errorsOf { page("limit" to value) }).containsExactly(ErrorResponse.FieldError("limit", expected))
		}

		@ParameterizedTest
		@ValueSource(strings = ["abc", "1.5", "", " 20"])
		@DisplayName("정수가 아닌 limit 은 400 invalid_format")
		fun limitNotInteger(value: String) {
			assertThat(errorsOf { page("limit" to value) }).containsExactly(ErrorResponse.FieldError("limit", "invalid_format"))
		}

		@Test
		@DisplayName("만든 cursor 는 되읽힌다")
		fun cursorRoundTrip() {
			val token = codec.encode(PageCursor("snap-1", "teams:cost", listOf("12.5", null, "team-1")))

			assertThat(page("cursor" to token).cursor).isEqualTo(PageCursor("snap-1", "teams:cost", listOf("12.5", null, "team-1")))
		}

		@ParameterizedTest
		@ValueSource(strings = ["", "not-a-cursor!", "eyJ2IjoyfQ", "e30", "W10", "eyJ2IjoxLCJzIjoiYSIsImMiOiJiIiwiYSI6WzFdfQ"])
		@DisplayName("깨진 cursor 는 400 invalid_cursor")
		fun brokenCursor(value: String) {
			assertThat(errorsOf { page("cursor" to value) }).containsExactly(ErrorResponse.FieldError("cursor", "invalid_cursor"))
		}
	}

	@Nested
	inner class Search {

		@Test
		@DisplayName("q 는 200자까지 받고 201자는 400 — 코드 포인트로 센다")
		fun lengthLimit() {
			val emoji = "😀".repeat(200)
			assertThat(reader("q" to emoji).read { search() }).isEqualTo(emoji)
			assertThat(errorsOf { reader("q" to "a".repeat(201)).read { search() } })
				.containsExactly(ErrorResponse.FieldError("q", "out_of_range"))
		}

		@Test
		@DisplayName("빈 q 는 검색하지 않는다")
		fun emptyIsAbsent() {
			assertThat(reader("q" to "").read { search() }).isNull()
			assertThat(reader().read { search() }).isNull()
		}
	}

	@Test
	@DisplayName("여러 필드의 오류를 한 번에 모은다")
	fun errorsAccumulate() {
		val errors = errorsOf {
			reader("startDate" to "x", "endDate" to "2026-09-07", "compare" to "bad", "limit" to "0", "timeZone" to "UTC")
				.read { Pair(comparedPeriod(), page(default = 20, max = 100, codec = codec)) }
		}

		assertThat(errors).containsExactlyInAnyOrder(
			ErrorResponse.FieldError("timeZone", "unsupported_value"),
			ErrorResponse.FieldError("startDate", "invalid_format"),
			ErrorResponse.FieldError("compare", "unsupported_value"),
			ErrorResponse.FieldError("limit", "out_of_range"),
		)
	}

	@Test
	@DisplayName("choice — 생략하면 기본값, 기본값이 없으면 필수")
	fun choice() {
		val allowed = mapOf("cost" to "C", "token" to "T")
		assertThat(reader().read { choice("sort", allowed, "C") }).isEqualTo("C")
		assertThat(reader("sort" to "token").read { choice("sort", allowed, "C") }).isEqualTo("T")
		assertThat(errorsOf { reader().read { choice("sort", allowed, null) } })
			.containsExactly(ErrorResponse.FieldError("sort", "required"))
		assertThatThrownBy { reader("sort" to "Cost").read { choice("sort", allowed, "C") } }
			.isInstanceOf(DashboardException::class.java)
	}
}
