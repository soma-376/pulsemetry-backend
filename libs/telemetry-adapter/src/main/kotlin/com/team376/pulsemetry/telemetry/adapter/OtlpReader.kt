package com.team376.pulsemetry.telemetry.adapter

import com.team376.pulsemetry.telemetry.adapter.model.SignalType

/**
 * 리더가 낸 레코드 하나. 세 신호가 여기서 같은 모양이 된다.
 */
internal class OtlpRecord(
	val resourceAttributes: Map<String, Any?>,
	/** 원본 레코드 트리. `source_record_id` 해시의 재료이자 시각·ID 의 출처다. */
	val record: Map<String, Any?>,
	val attributes: Map<String, Any?>,
	/** 어댑터가 무엇으로 매치할지 정하는 이름. 신호마다 나오는 자리가 다르다. */
	val name: String,
	val signal: SignalType,
)

/**
 * OTLP 와이어 리더 — 툴 무관. 세 신호를 공통 레코드 스트림으로 편다.
 *
 * logs/metrics/traces 는 와이어 모양이 다르지만(`logRecords` vs `dataPoints` vs `spans`)
 * 여기서 [OtlpRecord] 로 통일한다. "어느 벤더 필드가 payload 로 가는가"는 `source/` 의 몫이고,
 * "OTLP 를 어떻게 읽는가"인 이 파일은 모든 툴이 공유한다.
 *
 * ## [OtlpRecord.name] 이 나오는 자리가 신호마다 다르다
 *
 * | 신호 | 이름의 출처 |
 * |---|---|
 * | log | 로그 본문 문자열 (`body.stringValue`) |
 * | metric | 메트릭 이름 |
 * | span | 스팬 이름 |
 *
 * codex 는 로그에서 이 이름을 쓰지 않고 `event.name` 속성을 본다 — `CodexSource` 참고.
 */
internal object OtlpReader {

	/** 메트릭 본문이 실릴 수 있는 자리. 순서가 이식 원본과 같다. */
	private val METRIC_BODIES = listOf("sum", "gauge", "histogram", "exponentialHistogram")

	/**
	 * 한 OTLP 문서에서 존재하는 모든 신호를 편다.
	 *
	 * 실제 요청은 신호 하나짜리지만(엔드포인트가 셋이다) 순서는 원본과 같게 둔다 —
	 * logs → metrics → traces.
	 */
	fun readAll(document: Map<String, Any?>): List<OtlpRecord> =
		readLogs(document) + readMetrics(document) + readTraces(document)

	private fun readLogs(document: Map<String, Any?>): List<OtlpRecord> {
		val out = mutableListOf<OtlpRecord>()
		for (resourceLogs in document.list("resourceLogs")) {
			val resourceAttributes = OtlpAttributes.of(resourceLogs.map("resource"))
			for (scopeLogs in resourceLogs.list("scopeLogs")) {
				for (record in scopeLogs.list("logRecords")) {
					val name = record.map("body")?.get("stringValue") as? String ?: ""
					out += OtlpRecord(
						resourceAttributes = resourceAttributes,
						record = record,
						attributes = OtlpAttributes.of(record),
						name = name,
						signal = SignalType.LOG,
					)
				}
			}
		}
		return out
	}

	private fun readMetrics(document: Map<String, Any?>): List<OtlpRecord> {
		val out = mutableListOf<OtlpRecord>()
		for (resourceMetrics in document.list("resourceMetrics")) {
			val resourceAttributes = OtlpAttributes.of(resourceMetrics.map("resource"))
			for (scopeMetrics in resourceMetrics.list("scopeMetrics")) {
				for (metric in scopeMetrics.list("metrics")) {
					val name = metric["name"] as? String ?: ""
					for (kind in METRIC_BODIES) {
						val body = metric.map(kind) ?: continue
						for (dataPoint in body.list("dataPoints")) {
							out += OtlpRecord(
								resourceAttributes = resourceAttributes,
								record = withMetricMeta(dataPoint, metric, body, kind),
								attributes = OtlpAttributes.of(dataPoint),
								name = name,
								signal = SignalType.METRIC,
							)
						}
					}
				}
			}
		}
		return out
	}

	/**
	 * 데이터포인트에 부모 메트릭의 메타데이터를 `_metric` 으로 얹는다.
	 *
	 * **다섯 키가 언제나 다 있다** — 없는 값은 빠지는 것이 아니라 `null` 로 실린다.
	 * 이 맵이 `source_record_id` 해시에 그대로 들어가므로 키를 빼면 해시가 어긋난다.
	 */
	private fun withMetricMeta(
		dataPoint: Map<String, Any?>,
		metric: Map<String, Any?>,
		body: Map<String, Any?>,
		kind: String,
	): Map<String, Any?> = LinkedHashMap(dataPoint).apply {
		put(
			"_metric",
			linkedMapOf(
				"unit" to metric["unit"],
				"description" to metric["description"],
				"type" to kind,
				"aggregationTemporality" to body["aggregationTemporality"],
				"isMonotonic" to body["isMonotonic"],
			),
		)
	}

	/**
	 * 스팬을 편다.
	 *
	 * 이식 원본은 `resourceSpans` 와 `resourceTraces` 를 둘 다 받았다. 여기서는 규격 키만
	 * 읽는다 — 입력이 `ExportTraceServiceRequest` 라 후자가 애초에 도달할 수 없다(ADR 0013).
	 */
	private fun readTraces(document: Map<String, Any?>): List<OtlpRecord> {
		val out = mutableListOf<OtlpRecord>()
		for (resourceSpans in document.list("resourceSpans")) {
			val resourceAttributes = OtlpAttributes.of(resourceSpans.map("resource"))
			for (scopeSpans in resourceSpans.list("scopeSpans")) {
				for (span in scopeSpans.list("spans")) {
					out += OtlpRecord(
						resourceAttributes = resourceAttributes,
						record = span,
						attributes = OtlpAttributes.of(span),
						name = span["name"] as? String ?: "",
						signal = SignalType.SPAN,
					)
				}
			}
		}
		return out
	}
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> =
	(this[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.map(key: String): Map<String, Any?>? =
	this[key] as? Map<String, Any?>
