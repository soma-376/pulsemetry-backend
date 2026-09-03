package com.team376.pulsemetry.telemetry.adapter

import java.time.Instant
import java.time.OffsetDateTime

/**
 * 타임스탬프 파싱 — ISO8601 문자열 / `timeUnixNano` 를 epoch 초(Double)로 통일한다.
 *
 * ⚠️ **파싱 실패는 진단 없이 `0.0`(1970-01-01)이 된다.** `record_id` 해시와 페어링 정렬이
 * 전부 이 값에 걸려 있어 한 건이 세션 정렬을 망가뜨릴 수 있다. 구 파이프라인의 알려진
 * 결함이고(그쪽 `data-gaps-and-schema-risks.md` 2.1) 동작 동일성 때문에 그대로 옮긴다.
 */
internal object OtlpTimestamp {

	/** `event.timestamp`(ISO8601) 가 있으면 그것이 이긴다. 없으면 나노초 필드를 본다. */
	fun parse(record: Map<String, Any?>, attrs: Map<String, Any?>): Double {
		(attrs["event.timestamp"] as? String)?.takeIf { it.isNotEmpty() }?.let { text ->
			parseIso(text)?.let { return it }
		}
		val nanos = record["timeUnixNano"] ?: record["observedTimeUnixNano"]
		return nanosToSeconds(nanos) ?: 0.0
	}

	/** 스팬의 시작 시각. 없으면 `0.0`. */
	fun startOf(record: Map<String, Any?>): Double =
		nanosToSeconds(record["startTimeUnixNano"]) ?: 0.0

	/**
	 * 시작과 끝이 둘 다 있을 때만 구간 길이를 낸다.
	 *
	 * 정수 나눗셈이 아니라 **실수 나눗셈 뒤 절단**이다. 이식 원본이
	 * `int((int(e) - int(s)) / 1e6)` 이라, 나노초 차가 클 때 두 방식의 결과가 갈린다.
	 */
	fun durationMs(record: Map<String, Any?>): Int? {
		val start = (record["startTimeUnixNano"] as? String)?.toLongOrNull() ?: return null
		val end = (record["endTimeUnixNano"] as? String)?.toLongOrNull() ?: return null
		return ((end - start) / 1e6).toInt()
	}

	/**
	 * 나노초를 초로 나눈다. 없거나 읽히지 않으면 null.
	 *
	 * 나눗셈이 `Long → Double` 이라 정밀도가 여기서 한 번 깎인다. `record_id` 는 이 값을
	 * 다시 `* 1e9` 해서 쓰므로 **양쪽이 같은 방식으로 깎여야** 키가 맞는다 — [RecordId] 참고.
	 */
	fun nanosToSeconds(raw: Any?): Double? {
		val nanos = when (raw) {
			is String -> raw.toLongOrNull() ?: return null
			is Number -> raw.toLong()
			else -> return null
		}
		if (nanos == 0L) return null
		return nanos / 1e9
	}

	private fun parseIso(text: String): Double? = runCatching {
		val normalized = text.replace("Z", "+00:00")
		val instant = runCatching { OffsetDateTime.parse(normalized).toInstant() }
			.getOrElse { Instant.parse(text) }
		instant.epochSecond + instant.nano / 1e9
	}.getOrNull()
}
