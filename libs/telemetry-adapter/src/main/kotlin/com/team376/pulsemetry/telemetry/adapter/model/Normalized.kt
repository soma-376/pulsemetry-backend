package com.team376.pulsemetry.telemetry.adapter.model

/**
 * 정규화된 이벤트 하나. 세 신호가 각각 한 갈래다.
 *
 * 이식 원본은 판별 필드 없이 `isinstance` 로 갈랐고, 직렬화에도 태그가 없다 —
 * `point` 가 있으면 메트릭, `type`+`payload` 가 있으면 로그나 스팬이다. Kotlin 에서는
 * sealed interface 로 그 갈래를 타입으로 세운다.
 *
 * [callId] 는 `CallIdPairing` 이 **제자리에서** 고쳐 쓰므로 `var` 다. [Envelope.recordId] 는
 * 그전에 확정되므로 페어링에 흔들리지 않는다.
 */
public sealed interface Normalized {
	public val envelope: Envelope

	/** 툴 조인 키. 메트릭에는 없다. */
	public val callId: String?
}

/** 로그에서 온 이벤트. 점(event) — "무슨 일이 일어났다"는 순간 사실. */
public class NormalizedLog(
	override val envelope: Envelope,
	public var type: LogKind = LogKind.OTHER,
	public var payload: LogPayload? = null,
	public var turnId: String? = null,
	override var callId: String? = null,
	public var sequence: Int? = null,
) : Normalized

/**
 * 스팬에서 온 이벤트. 구간(interval) — [type] 이 곧 역할이다.
 *
 * [sequence] 가 없다. `record_id` 는 그 자리를 [spanId] 로 메운다 — 그러지 않으면
 * 같은 세션·같은 시각의 두 스팬이 한 키로 합쳐진다.
 */
public class NormalizedSpan(
	override val envelope: Envelope,
	public var type: SpanKind = SpanKind.OTHER,
	public var payload: SpanPayload? = null,
	public var traceId: String? = null,
	public var spanId: String? = null,
	public var parentId: String? = null,
	override var callId: String? = null,
) : Normalized

/**
 * 메트릭 데이터포인트 하나.
 *
 * 어댑터가 손대지 않고 통과시킨다 — claude_code 의 라인·커밋·PR·active_time 이 전용
 * 이벤트 타입으로 승격되지 않는 것은 알려진 공백이다.
 */
public class NormalizedMetric(
	override val envelope: Envelope,
	public val point: MetricPoint,
) : Normalized {
	/** 메트릭에는 툴 조인 키가 없다. 그래서 페어링 후보에 애초에 들어가지 않는다. */
	override val callId: String? get() = null
}

/**
 * OTLP 데이터포인트를 편 값.
 *
 * [attrs] 는 값이 전부 String 이다 — 원본이 dict·list 면 compact JSON 으로, 그 밖에는
 * 언어의 기본 문자열 표기로 눕힌다. **Boolean 은 `"True"`/`"False"` 다**(Python `str()`
 * 표기). golden fixture 가 그 표기로 굳어 있으므로 Kotlin 기본값(`true`)을 쓰면 어긋난다.
 */
public class MetricPoint(
	public val name: String,
	public val value: Number? = null,
	public val unit: String? = null,
	public val description: String? = null,
	public val metricType: String? = null,
	public val aggregationTemporality: Int? = null,
	public val isMonotonic: Boolean? = null,
	public val startTime: Double? = null,
	public val count: Int? = null,
	public val sum: Double? = null,
	public val min: Double? = null,
	public val max: Double? = null,
	public val bucketCounts: List<Int> = emptyList(),
	public val explicitBounds: List<Double> = emptyList(),
	public val attrs: Map<String, String> = emptyMap(),
)
