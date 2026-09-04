package com.team376.pulsemetry.telemetry.adapter.source

import com.team376.pulsemetry.telemetry.adapter.IngestContext
import com.team376.pulsemetry.telemetry.adapter.OtlpAttributes
import com.team376.pulsemetry.telemetry.adapter.OtlpRecord
import com.team376.pulsemetry.telemetry.adapter.OtlpTimestamp
import com.team376.pulsemetry.telemetry.adapter.RecordId
import com.team376.pulsemetry.telemetry.adapter.Stringify
import com.team376.pulsemetry.telemetry.adapter.model.Client
import com.team376.pulsemetry.telemetry.adapter.model.Envelope
import com.team376.pulsemetry.telemetry.adapter.model.Identity
import com.team376.pulsemetry.telemetry.adapter.model.Ingest
import com.team376.pulsemetry.telemetry.adapter.model.MetricPoint
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedMetric

/**
 * 메트릭 변환 — 두 소스가 공유한다. 벤더별 차이가 신원·클라이언트뿐이라 한 곳에 둔다.
 *
 * ⚠️ **메트릭은 통과만 시킨다.** claude_code 의 라인·커밋·PR·active_time 이 전용 이벤트
 * 타입으로 승격되지 않는 것은 알려진 공백이다. 어떤 payload 타입으로 올릴지가 미정이라
 * 이식 시점에 정하지 않는다.
 *
 * ⚠️ **속성이 걸러지지 않고 통째로 실린다.** 다른 이벤트들은 승격 대상을 화이트리스트로
 * 열거하는데 여기만 데이터포인트 속성 전체가 [MetricPoint.attrs] 로 들어간다. 수집 단계가
 * 메트릭에 마스킹을 걸지 않으므로(허브 계약 §5 의 M6) 시크릿이 메트릭 속성으로 오면
 * 그대로 남는다. 현행 동작이고 고치는 것은 별도 티켓이다.
 */
internal object MetricEvents {

	fun build(
		record: OtlpRecord,
		context: IngestContext,
		identity: Identity,
		client: Client,
		adapterVersion: Int,
	): NormalizedMetric {
		@Suppress("UNCHECKED_CAST")
		val meta = record.record["_metric"] as? Map<String, Any?> ?: emptyMap()

		// 메트릭은 session.id 를 데이터포인트뿐 아니라 리소스 레벨에도 실을 수 있다 →
		// 로그 어댑터와 같이 둘 다 뒤진다.
		val session = OtlpAttributes.optString(
			record.attributes,
			record.resourceAttributes,
			"session.id", "conversation.id", "thread.id",
		) ?: Envelope.UNKNOWN_SESSION

		val envelope = Envelope(
			identity = identity,
			client = client,
			timestamp = OtlpTimestamp.nanosToSeconds(record.record["timeUnixNano"]) ?: 0.0,
			sessionId = session,
			ingest = Ingest(
				adapterVersion = adapterVersion,
				signal = context.signal,
				sourceRecordId = context.rawRecordId,
			),
		)

		val point = MetricPoint(
			name = record.name,
			value = numberOf(record.record),
			unit = meta["unit"] as? String,
			description = meta["description"] as? String,
			metricType = meta["type"] as? String,
			aggregationTemporality = (meta["aggregationTemporality"] as? Number)?.toInt(),
			isMonotonic = meta["isMonotonic"] as? Boolean,
			startTime = OtlpTimestamp.nanosToSeconds(record.record["startTimeUnixNano"]),
			// fixed64 → Int. Int 범위 밖은 감싸지 않고 null 이다 — "없음" 과 "0건" 을 섞지 않는다.
			count = longOf(record.record["count"])?.let { OtlpAttributes.intOrNull(it) },
			sum = doubleOf(record.record["sum"]),
			min = doubleOf(record.record["min"]),
			max = doubleOf(record.record["max"]),
			bucketCounts = (record.record["bucketCounts"] as? List<*>)
				?.mapNotNull { longOf(it)?.let { count -> OtlpAttributes.intOrNull(count) } }
				?: emptyList(),
			explicitBounds = (record.record["explicitBounds"] as? List<*>)
				?.mapNotNull { doubleOf(it) } ?: emptyList(),
			attrs = Stringify.attrs(record.attributes),
		)

		return RecordId.finalize(NormalizedMetric(envelope, point))
	}

	/** `asInt` 가 있으면 정수, 없고 `asDouble` 이 있으면 실수, 둘 다 없으면 null. */
	private fun numberOf(record: Map<String, Any?>): Number? {
		longOf(record["asInt"])?.let { return it }
		doubleOf(record["asDouble"])?.let { return it }
		return null
	}

	private fun longOf(raw: Any?): Long? = when (raw) {
		is String -> raw.toLongOrNull()
		is Number -> raw.toLong()
		else -> null
	}

	private fun doubleOf(raw: Any?): Double? = when (raw) {
		is String -> raw.toDoubleOrNull()
		is Number -> raw.toDouble()
		else -> null
	}
}
