package com.team376.pulsemetry.telemetry.adapter.observation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant

/**
 * `source_time` 규칙(ADR 0020 §2). 기대값은 규칙 표에서 쓴 것이다 — 부호 없는 64비트 나노초,
 * `DateTime64(9)` 의 상한은 `Long.MAX_VALUE` 나노초(2262-04-11T23:47:16.854775807Z).
 */
class SourceTimeTest {

	// ── 원본 한 값을 읽기 ──────────────────────────────────────────────────────

	@ParameterizedTest(name = "bits {0} -> {1}")
	@CsvSource(
		"0,                     UNSET",
		"1,                     VALID",
		"1758758400123456789,   VALID",
		"9223372036854775807,   VALID", // Long.MAX — 표현 가능한 마지막 나노초
		"-9223372036854775808,  OUT_OF_RANGE", // 2^63 을 protobuf long 비트로 본 값
		"-1,                    OUT_OF_RANGE", // UInt64 최댓값
	)
	@DisplayName("protobuf fixed64 비트 — 0 은 미설정, 2^63 이상은 범위 밖")
	fun readsProtobufBits(bits: Long, expected: String) {
		assertThat(classify(OtlpTime.ofBits(bits))).isEqualTo(expected)
	}

	@ParameterizedTest(name = "json \"{0}\" -> {1}")
	@CsvSource(
		"1758758400123456789,    VALID",
		"0,                      UNSET",
		"0000001,                VALID",
		"9223372036854775807,    VALID",
		"9223372036854775808,    OUT_OF_RANGE", // 2^63
		"18446744073709551615,   OUT_OF_RANGE", // UInt64 최댓값
		"18446744073709551616,   UNPARSEABLE", // 2^64 — fixed64 가 아니다
		"-1,                     UNPARSEABLE",
		"+1,                     UNPARSEABLE",
		"1.5,                    UNPARSEABLE",
		"1e9,                    UNPARSEABLE",
		"abc,                    UNPARSEABLE",
		"' 1',                   UNPARSEABLE",
		"'',                     UNPARSEABLE",
	)
	@DisplayName("OTLP/JSON 문자열 — 부호 없는 10진 정수만, Double 을 거치지 않는다")
	fun readsJsonStrings(text: String, expected: String) {
		assertThat(classify(OtlpTime.ofJson(text))).isEqualTo(expected)
	}

	@Test
	@DisplayName("JSON 에 필드가 없으면 미설정이다")
	fun missingJsonFieldIsUnset() {
		assertThat(OtlpTime.ofJson(null)).isEqualTo(OtlpTime.Invalid(SourceTimeRejection.UNSET))
	}

	@Test
	@DisplayName("나노초까지 정확하다 — 2^53 을 넘는 값에서도 한 자리도 깎이지 않는다")
	fun keepsEveryNanosecond() {
		val time = OtlpTime.ofJson("1758758400123456789") as OtlpTime.Valid

		assertThat(time.nanos.value).isEqualTo(1_758_758_400_123_456_789L)
		assertThat(time.nanos.toInstant()).isEqualTo(Instant.parse("2025-09-25T00:00:00.123456789Z"))
		assertThat((OtlpTime.ofBits(Long.MAX_VALUE) as OtlpTime.Valid).nanos.toInstant())
			.isEqualTo(Instant.parse("2262-04-11T23:47:16.854775807Z"))
	}

	// ── 신호별 선택 ────────────────────────────────────────────────────────────

	@ParameterizedTest(name = "log time={0} observed={1} -> {2} {3}")
	@CsvSource(
		// time,                observed,      결과,            origin 또는 사유
		"100,                   200,           SELECTED,        OTLP_EVENT",
		"0,                     200,           SELECTED,        OTLP_OBSERVED", // Codex 로그의 실제 모양
		"-1,                    200,           SELECTED,        OTLP_OBSERVED", // 범위 밖 time 은 유효하지 않다
		"0,                     0,             REJECTED,        UNSET",
		"-1,                    0,             REJECTED,        OUT_OF_RANGE",
		"0,                     -1,            REJECTED,        OUT_OF_RANGE",
	)
	@DisplayName("로그 — 유효한 timeUnixNano, 아니면 observedTimeUnixNano")
	fun logSelection(time: Long, observed: Long, result: String, detail: String) {
		val selection = SourceTimes.forLog(OtlpTime.ofBits(time), OtlpTime.ofBits(observed))

		when (result) {
			"SELECTED" -> {
				selection as SourceTimeSelection.Selected
				assertThat(selection.origin).isEqualTo(SourceTimeOrigin.valueOf(detail))
				assertThat(selection.sourceTime.value).isEqualTo(if (detail == "OTLP_EVENT") time else observed)
				// 관측 시각은 유효하면 어느 쪽이 골라졌든 남는다.
				assertThat(selection.observedTime?.value).isEqualTo(observed)
			}
			else -> assertThat(selection).isEqualTo(SourceTimeSelection.Rejected(SourceTimeRejection.valueOf(detail)))
		}
	}

	@Test
	@DisplayName("로그 — 두 후보가 모두 실패하면 더 구체적인 사유를 센다(파싱 실패 > 범위 밖 > 미설정)")
	fun logRejectionPrefersTheMoreInformativeReason() {
		assertThat(SourceTimes.forLog(OtlpTime.ofJson("x"), OtlpTime.ofJson(null)))
			.isEqualTo(SourceTimeSelection.Rejected(SourceTimeRejection.UNPARSEABLE))
		assertThat(SourceTimes.forLog(OtlpTime.ofJson(null), OtlpTime.ofJson("18446744073709551615")))
			.isEqualTo(SourceTimeSelection.Rejected(SourceTimeRejection.OUT_OF_RANGE))
	}

	@ParameterizedTest(name = "span start={0} -> {1}")
	@CsvSource("5, SPAN_START", "0, UNSET", "-5, OUT_OF_RANGE")
	@DisplayName("스팬 — startTimeUnixNano 만 본다")
	fun spanSelection(start: Long, expected: String) {
		val selection = SourceTimes.forSpan(OtlpTime.ofBits(start))

		if (expected == "SPAN_START") {
			assertThat(selection).isEqualTo(SourceTimeSelection.Selected(EpochNanos(start), SourceTimeOrigin.SPAN_START, null))
		} else {
			assertThat(selection).isEqualTo(SourceTimeSelection.Rejected(SourceTimeRejection.valueOf(expected)))
		}
	}

	@ParameterizedTest(name = "point time={0} -> {1}")
	@CsvSource("7, METRIC_POINT", "0, UNSET", "-7, OUT_OF_RANGE")
	@DisplayName("metric point — point 의 timeUnixNano 만 본다(시작 시각은 후보가 아니다)")
	fun metricPointSelection(time: Long, expected: String) {
		val selection = SourceTimes.forMetricPoint(OtlpTime.ofBits(time))

		if (expected == "METRIC_POINT") {
			assertThat(selection).isEqualTo(SourceTimeSelection.Selected(EpochNanos(time), SourceTimeOrigin.METRIC_POINT, null))
		} else {
			assertThat(selection).isEqualTo(SourceTimeSelection.Rejected(SourceTimeRejection.valueOf(expected)))
		}
	}

	@Test
	@DisplayName("event_time 은 source_time 을 바꾸지 않는다 — 다르면 event_time_mismatch 만 남긴다")
	fun eventTimeNeverReplacesSourceTime() {
		assertThat(SourceTimes.eventTimeFlags(EpochNanos(100), null)).isEmpty()
		assertThat(SourceTimes.eventTimeFlags(EpochNanos(100), EpochNanos(100))).isEmpty()
		assertThat(SourceTimes.eventTimeFlags(EpochNanos(100), EpochNanos(99))).containsExactly(QualityFlag.EVENT_TIME_MISMATCH)
	}

	@Test
	@DisplayName("선택 시각(스팬 종료·metric 시작)은 유효할 때만 값이다")
	fun optionalTimes() {
		assertThat(OtlpTime.optional(OtlpTime.ofBits(9))).isEqualTo(EpochNanos(9))
		assertThat(OtlpTime.optional(OtlpTime.ofBits(0))).isNull()
		assertThat(OtlpTime.optional(OtlpTime.ofJson("nope"))).isNull()
	}

	// ── 집계 ──────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("사유별 거부 수·허용 목록 밖 스팬 수·시각 범위를 센다 — rejected_count 는 둘의 합")
	fun statsCountWhatDoesNotReachTheTables() {
		val stats = NormalizationStats()
		val inputs = listOf(
			SourceTimes.forLog(OtlpTime.ofBits(300), OtlpTime.ofBits(0)),
			SourceTimes.forLog(OtlpTime.ofBits(0), OtlpTime.ofBits(100)),
			SourceTimes.forLog(OtlpTime.ofBits(0), OtlpTime.ofBits(0)),
			SourceTimes.forSpan(OtlpTime.ofBits(-1)),
			SourceTimes.forMetricPoint(OtlpTime.ofJson("x")),
			SourceTimes.forMetricPoint(OtlpTime.ofBits(0)),
		)
		inputs.forEach {
			stats.recordReceived()
			stats.record(it)
		}
		stats.recordReceived()
		stats.recordExcludedSpan()

		assertThat(stats.received).isEqualTo(7)
		assertThat(stats.sourceTimeRejections).containsExactlyInAnyOrderEntriesOf(
			mapOf(
				SourceTimeRejection.UNSET to 2,
				SourceTimeRejection.OUT_OF_RANGE to 1,
				SourceTimeRejection.UNPARSEABLE to 1,
			),
		)
		assertThat(stats.excludedSpans).isEqualTo(1)
		assertThat(stats.rejectedCount).isEqualTo(5)
		assertThat(stats.sourceTimeMin).isEqualTo(EpochNanos(100))
		assertThat(stats.sourceTimeMax).isEqualTo(EpochNanos(300))
	}

	@Test
	@DisplayName("분석 테이블로 간 것이 없으면 시각 범위는 null 이다")
	fun emptyRangeIsNull() {
		val stats = NormalizationStats()
		stats.recordReceived()
		stats.record(SourceTimes.forSpan(OtlpTime.ofBits(0)))

		assertThat(stats.sourceTimeMin).isNull()
		assertThat(stats.sourceTimeMax).isNull()
		assertThat(stats.rejectedCount).isEqualTo(1)
	}

	private fun classify(time: OtlpTime): String = when (time) {
		is OtlpTime.Valid -> "VALID"
		is OtlpTime.Invalid -> time.reason.name
	}
}
