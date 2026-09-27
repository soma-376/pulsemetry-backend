package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.event
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.ReportedCostBasis
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.withFlags
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * `telemetry_events` 행 인코더(ADR 0020 §1 직렬화). 기대 표기는 그 절에서 온다 — 정수는 Double 을 거치지 않고, Decimal 은
 * 지수 없는 정확한 10진, 시각은 UTC `YYYY-MM-DD HH:MM:SS.nnnnnnnnn`, 선택 값은 null·빈 배열, 분류는 none/unknown.
 */
class TelemetryEventRowTest {

	private val versioning = RowVersioning.of(1u, 1_767_225_605u)

	private fun tokens(observation: EventObservation) = RowTokens.parse(TelemetryEventRow.toJson(event(observation), versioning))

	private fun base() = event().observation

	@Test
	@DisplayName("107컬럼을 DDL 순서대로 전부 쓴다 — 선택 값도 생략하지 않는다")
	fun writesEveryColumnInOrder() {
		val row = tokens(base())

		assertThat(row.keys).containsExactlyElementsOf(AnalysisColumns.EVENTS.map { it.name })
		assertThat(row).hasSize(107)
	}

	@Test
	@DisplayName("미보고는 null·빈 배열·none/unknown 이다 — 0 으로 채우지 않는다")
	fun unreportedValuesStayNull() {
		val row = tokens(base())

		for (column in listOf("tokens_input", "cost_reported_usd", "success", "http_status", "severity_number", "end_time", "session_id")) {
			assertThat(row[column]).describedAs(column).isEqualTo("null")
		}
		assertThat(row["quality_flags"]).isEqualTo("[]")
		assertThat(row["team_ids_as_of"]).isEqualTo("[]")
		assertThat(row["attrs"]).isEqualTo("{}")
		assertThat(row["operation"]).isEqualTo("\"none\"")
		assertThat(row["input_semantics"]).isEqualTo("\"unknown\"")
		assertThat(row["schema_version"]).isEqualTo("1")
	}

	@Test
	@DisplayName("Int64 는 Double 을 거치지 않는다 — 2^53+1 과 Long 최댓값이 그대로다")
	fun int64IsExact() {
		val row = tokens(base().copy(tokensInput = 9_007_199_254_740_993L, durationNs = Long.MAX_VALUE))

		assertThat(row["tokens_input"]).isEqualTo("9007199254740993")
		assertThat(row["duration_ns"]).isEqualTo("9223372036854775807")
	}

	@Test
	@DisplayName("row_version·normalizer_rev 는 부호 없는 정수 그대로다")
	fun versioningIsUnsigned() {
		val row = RowTokens.parse(TelemetryEventRow.toJson(event(), RowVersioning.of(UInt.MAX_VALUE, UInt.MAX_VALUE)))

		assertThat(row["row_version"]).isEqualTo("18446744073709551615")
		assertThat(row["normalizer_rev"]).isEqualTo("4294967295")
	}

	@Test
	@DisplayName("Decimal(38, 12) 는 지수 없는 정확한 10진이다 — 뒤따르는 0 은 값이 아니다")
	fun decimalsArePlain() {
		fun cost(value: String) = tokens(base().copy(costReportedUsd = BigDecimal(value), reportedCostBasis = ReportedCostBasis.ESTIMATE))["cost_reported_usd"]

		assertThat(cost("0.000000000001")).isEqualTo("0.000000000001")
		assertThat(cost("99999999999999999999999999.999999999999")).isEqualTo("99999999999999999999999999.999999999999")
		assertThat(cost("0.0200")).isEqualTo("0.02")
		assertThat(cost("1E+2")).isEqualTo("100")
		assertThat(cost("1E-7")).isEqualTo("0.0000001")
	}

	@Test
	@DisplayName("Decimal(38, 12) 에 반올림 없이 들어가지 않는 금액은 절삭하지 않고 거부한다 — 영구 오류")
	fun decimalsOutOfRangeAreRejected() {
		for (value in listOf("0.0000000000001", "100000000000000000000000000")) {
			assertThatThrownBy { tokens(base().copy(costReportedUsd = BigDecimal(value), reportedCostBasis = ReportedCostBasis.ESTIMATE)) }
				.describedAs(value)
				.isInstanceOf(TelemetrySinkRejectedException::class.java)
				.hasMessageContaining("cost_reported_usd")
		}
	}

	@Test
	@DisplayName("DateTime64(9) 는 UTC 나노초 아홉 자리 문자열이다")
	fun timesHaveNineFractionDigits() {
		fun at(nanos: EpochNanos) = tokens(base().copy(endTime = nanos))["end_time"]

		assertThat(tokens(base())["source_time"]).isEqualTo("\"2026-01-01 00:00:00.123456789\"")
		assertThat(at(EpochNanos(1))).isEqualTo("\"1970-01-01 00:00:00.000000001\"")
		assertThat(at(EpochNanos(-1))).isEqualTo("\"1969-12-31 23:59:59.999999999\"")
		assertThat(at(EpochNanos(Long.MAX_VALUE))).isEqualTo("\"2262-04-11 23:47:16.854775807\"")
		assertThat(at(EpochNanos.of(Instant.parse("1900-01-01T00:00:00Z")))).isEqualTo("\"1900-01-01 00:00:00.000000000\"")
	}

	@Test
	@DisplayName("DateTime64(9) 의 하한(1900-01-01) 앞 시각은 거부한다 — ClickHouse 는 조용히 다른 시각으로 바꾼다")
	fun timesBefore1900AreRejected() {
		val before = EpochNanos.of(Instant.parse("1899-12-31T23:59:59.999999999Z"))

		assertThatThrownBy { tokens(base().copy(endTime = before)) }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
			.hasMessageContaining("end_time")
	}

	@Test
	@DisplayName("분류·플래그는 wire 값, 보강 컬럼은 봉투의 자기 자리에 온다")
	fun vocabularyAndEnrichment() {
		val observation = base().copy(
			envelope = base().envelope.copy(usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE, attrs = mapOf("k" to "v\"1")),
			eventType = EventType.MODEL_RESPONSE_USAGE,
			success = false,
			httpStatus = 429,
			severityNumber = 17,
		).withFlags(QualityFlag.PROVIDER_UNRESOLVED, QualityFlag.MEMBER_UNRESOLVED)
		val row = RowTokens.parse(
			TelemetryEventRow.toJson(event(observation, AnalysisSamples.org(memberId = null, teamIds = listOf("t1", "t2"))), versioning),
		)

		assertThat(row["event_type"]).isEqualTo("\"model.response.usage\"")
		assertThat(row["usage_role"]).isEqualTo("\"primary\"")
		assertThat(row["quality_flags"]).isEqualTo("[\"provider_unresolved\",\"member_unresolved\"]")
		assertThat(row["attrs"]).isEqualTo("{\"k\":\"v\\\"1\"}")
		assertThat(row["success"]).isEqualTo("false")
		assertThat(row["http_status"]).isEqualTo("429")
		assertThat(row["severity_number"]).isEqualTo("17")
		assertThat(row["member_id"]).isEqualTo("null")
		assertThat(row["team_ids_as_of"]).isEqualTo("[\"t1\",\"t2\"]")
		assertThat(row["enrichment_version"]).isEqualTo("\"enrichment-v1\"")
		assertThat(row["enrichment_json"]).startsWith("\"{\\\"ai_analysis\\\":{}")
	}
}
