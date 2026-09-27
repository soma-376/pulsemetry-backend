package com.team376.pulsemetry.telemetry.adapter.observation

/**
 * 분석 행의 `source_time` 을 고른다 — ADR 0020 §2 의 규칙을 그대로 옮긴 순수 함수다.
 *
 * | 신호 | 후보 | `source_time_origin` |
 * |---|---|---|
 * | 로그 | 유효한 `timeUnixNano`, 아니면 `observedTimeUnixNano` | `otlp_event` / `otlp_observed` |
 * | 스팬 | `startTimeUnixNano` | `span_start` |
 * | metric point | `timeUnixNano` | `metric_point` |
 *
 * 이 값이 파티션·정렬 키·일자 귀속·수신 ledger 의 시각 범위의 재료다. **얻지 못하면 `received_time` 을
 * 대입하지 않는다** — 그 관측은 분석 테이블 밖이고, 거부 사유가 [NormalizationStats] 에 세어진다.
 *
 * 속성의 `event.timestamp` 같은 의미 시각은 후보가 아니다. 검증된 프로파일이 파생 `event_time` 을
 * 주면 [eventTimeFlags] 가 차이를 표시할 뿐 `source_time` 을 바꾸지 않는다.
 */
public object SourceTimes {

	/**
	 * 로그. `timeUnixNano` 가 유효하지 않으면(미설정·범위 밖·파싱 실패 모두) `observedTimeUnixNano` 로 물러난다.
	 * `observed_time` 컬럼은 어느 쪽이 골라졌든 관측 시각이 유효하면 채운다.
	 */
	public fun forLog(timeUnixNano: OtlpTime, observedTimeUnixNano: OtlpTime): SourceTimeSelection {
		val observed = (observedTimeUnixNano as? OtlpTime.Valid)?.nanos
		return when {
			timeUnixNano is OtlpTime.Valid ->
				SourceTimeSelection.Selected(timeUnixNano.nanos, SourceTimeOrigin.OTLP_EVENT, observed)

			observedTimeUnixNano is OtlpTime.Valid ->
				SourceTimeSelection.Selected(observedTimeUnixNano.nanos, SourceTimeOrigin.OTLP_OBSERVED, observed)

			else -> SourceTimeSelection.Rejected(
				moreInformative((timeUnixNano as OtlpTime.Invalid).reason, (observedTimeUnixNano as OtlpTime.Invalid).reason),
			)
		}
	}

	public fun forSpan(startTimeUnixNano: OtlpTime): SourceTimeSelection =
		select(startTimeUnixNano, SourceTimeOrigin.SPAN_START)

	public fun forMetricPoint(timeUnixNano: OtlpTime): SourceTimeSelection =
		select(timeUnixNano, SourceTimeOrigin.METRIC_POINT)

	/**
	 * 검증된 프로파일이 준 파생 `event_time` 이 `source_time` 과 다르면 `event_time_mismatch`.
	 * 둘 다 남긴다 — 원본 시각을 지우지 않는다.
	 */
	public fun eventTimeFlags(sourceTime: EpochNanos, eventTime: EpochNanos?): Set<QualityFlag> =
		if (eventTime != null && eventTime != sourceTime) setOf(QualityFlag.EVENT_TIME_MISMATCH) else emptySet()

	private fun select(time: OtlpTime, origin: SourceTimeOrigin): SourceTimeSelection = when (time) {
		is OtlpTime.Valid -> SourceTimeSelection.Selected(time.nanos, origin, observedTime = null)
		is OtlpTime.Invalid -> SourceTimeSelection.Rejected(time.reason)
	}

	/** 두 후보가 모두 실패하면 더 구체적인 사유를 센다 — 값이 있었는데 틀린 쪽이 미설정보다 정보가 많다. */
	private fun moreInformative(a: SourceTimeRejection, b: SourceTimeRejection): SourceTimeRejection =
		if (a.ordinal >= b.ordinal) a else b
}

/** [SourceTimes] 의 결과. */
public sealed interface SourceTimeSelection {
	public data class Selected(
		val sourceTime: EpochNanos,
		val origin: SourceTimeOrigin,
		/** 로그의 유효한 `observedTimeUnixNano`. 스팬·metric point 는 null. */
		val observedTime: EpochNanos?,
	) : SourceTimeSelection

	public data class Rejected(val reason: SourceTimeRejection) : SourceTimeSelection
}

/**
 * `source_time` 을 얻지 못한 사유. 선언 순서가 정보량 순서다 — 두 후보가 모두 실패하면 뒤의 것을 센다.
 */
public enum class SourceTimeRejection(override val wire: String) : WireValue {
	/** 값이 없거나 OTLP 의 미설정 값 0 이다. */
	UNSET("unset"),

	/** 부호 없는 64비트로는 읽혔지만 `DateTime64(9)` 로 표현할 수 없다(2^63 나노초 이상). */
	OUT_OF_RANGE("out_of_range"),

	/** OTLP/JSON 의 문자열이 부호 없는 10진 정수가 아니다. */
	UNPARSEABLE("unparseable"),
}

/**
 * OTLP 의 `fixed64` 나노초 시각 하나를 읽은 결과.
 *
 * 원본은 **부호 없는** 64비트다. protobuf-java 는 `fixed64` 를 부호 있는 `long` 비트로 돌려주므로 음수로
 * 보이는 값은 2^63 이상이다. `DateTime64(9)` 는 부호 있는 64비트 틱이라 표현 가능한 최댓값이
 * `Long.MAX_VALUE` 나노초(2262-04-11)다 — 그보다 크면 [SourceTimeRejection.OUT_OF_RANGE] 다.
 * Double 초를 거치지 않는다.
 */
public sealed interface OtlpTime {
	public data class Valid(val nanos: EpochNanos) : OtlpTime
	public data class Invalid(val reason: SourceTimeRejection) : OtlpTime

	public companion object {
		private val UNSIGNED_DECIMAL = Regex("^[0-9]+$")

		/** protobuf `fixed64` 의 비트 그대로. 0 은 미설정이다. */
		public fun ofBits(bits: Long): OtlpTime = when {
			bits == 0L -> Invalid(SourceTimeRejection.UNSET)
			bits < 0L -> Invalid(SourceTimeRejection.OUT_OF_RANGE)
			else -> Valid(EpochNanos(bits))
		}

		/**
		 * OTLP/JSON 의 표기(부호 없는 10진 문자열). 없으면 미설정이다. 2^64 이상이나 부호·소수·공백이 섞이면
		 * `fixed64` 가 아니므로 파싱 실패다.
		 */
		public fun ofJson(text: String?): OtlpTime {
			if (text == null) return Invalid(SourceTimeRejection.UNSET)
			if (!UNSIGNED_DECIMAL.matches(text)) return Invalid(SourceTimeRejection.UNPARSEABLE)
			val unsigned = text.toULongOrNull() ?: return Invalid(SourceTimeRejection.UNPARSEABLE)
			return ofBits(unsigned.toLong())
		}

		/** 선택 시각(스팬 종료·metric 시작 등)을 읽는다. 유효하지 않으면 null — 거부 사유로 세지 않는다. */
		public fun optional(time: OtlpTime): EpochNanos? = (time as? Valid)?.nanos
	}
}
