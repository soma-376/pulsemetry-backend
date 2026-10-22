package com.team376.pulsemetry.telemetry.observability

/** 유한한 운영 지표 집합. 전송 본문·신원·예외 메시지를 받지 않는다(ADR 0068). */
public enum class TelemetryMetric(public val meterName: String, public val kind: Kind, public vararg val labels: String) {
    GATEWAY_REQUESTS("pulsemetry.gateway.requests", Kind.COUNTER, "signal", "status", "outcome"),
    GATEWAY_REQUEST_DURATION("pulsemetry.gateway.request.duration", Kind.TIMER, "signal", "status"),
    GATEWAY_REQUEST_BYTES("pulsemetry.gateway.request.bytes", Kind.BYTES, "signal"),
    GATEWAY_REJECTIONS("pulsemetry.gateway.rejections", Kind.COUNTER, "stage", "reason"),
    GATEWAY_INFLIGHT("pulsemetry.gateway.inflight", Kind.GAUGE),
    GATEWAY_PUBLISH("pulsemetry.gateway.publish", Kind.COUNTER, "signal", "outcome", "reason"),
    GATEWAY_PUBLISH_DURATION("pulsemetry.gateway.publish.duration", Kind.TIMER, "signal", "outcome"),
    PIPELINE_ATTEMPTS("pulsemetry.pipeline.attempts", Kind.COUNTER, "signal", "outcome"),
    PIPELINE_FAILURES("pulsemetry.pipeline.failures", Kind.COUNTER, "stage", "reason"),
    PIPELINE_RETRY_EXHAUSTED("pulsemetry.pipeline.retry.exhausted", Kind.COUNTER, "stage"),
    PIPELINE_COMMIT_FAILURES("pulsemetry.pipeline.commit.failures", Kind.COUNTER),
    PIPELINE_STAGE_DURATION("pulsemetry.pipeline.stage.duration", Kind.TIMER, "stage", "outcome"),
    PIPELINE_ACCEPTED_TO_ATTEMPT_START("pulsemetry.pipeline.accepted.to.attempt.start", Kind.AGE, "signal"),
    PIPELINE_ACCEPTED_TO_COMPLETION("pulsemetry.pipeline.accepted.to.completion", Kind.AGE, "signal"),
    PIPELINE_INFLIGHT("pulsemetry.pipeline.inflight", Kind.GAUGE),
    PIPELINE_EMPTY("pulsemetry.pipeline.empty", Kind.COUNTER, "signal"),
    PIPELINE_EXCLUDED_OBSERVATIONS("pulsemetry.pipeline.excluded.observations", Kind.COUNTER, "reason"),
    CLOCK_SKEW("pulsemetry.pipeline.clock.skew", Kind.COUNTER),
    CLICKHOUSE_REQUESTS("pulsemetry.clickhouse.requests", Kind.COUNTER, "operation", "table", "outcome"),
    CLICKHOUSE_REQUEST_DURATION("pulsemetry.clickhouse.request.duration", Kind.TIMER, "operation", "table", "outcome"),
    CLICKHOUSE_ROWS_SUBMITTED("pulsemetry.clickhouse.rows.submitted", Kind.COUNTER, "table"),
    ;

    public enum class Kind { COUNTER, TIMER, AGE, BYTES, GAUGE }
}

public enum class PipelineStage(public val label: String) {
    AUTHORIZE("authorize"), DECODE("decode"), MASK("mask"), ARCHIVE("archive"), NORMALIZE("normalize"),
    ENRICH("enrich"), RETENTION("retention"), INSERT("insert"), OPS("ops"),
}

/** value는 timer의 경우 초, bytes는 byte, counter/gauge는 개수다. gauge만 음수 증감을 허용한다. */
public fun interface TelemetryObserver {
    public fun record(metric: TelemetryMetric, value: Double, vararg tags: Pair<String, String>)

    public companion object {
        public val NONE: TelemetryObserver = TelemetryObserver { _, _, _ -> }
    }
}

/** 계측 장애가 업무 응답·재시도·offset 확정을 바꾸지 않는다. 입력값은 계측 구현에서 다시 제한한다. */
public fun TelemetryObserver.observe(metric: TelemetryMetric, value: Double = 1.0, vararg tags: Pair<String, String>) {
    try {
        record(metric, value, *tags)
    } catch (_: RuntimeException) {
        // 계측 실패를 다시 계측하거나 원문 예외를 출력하지 않는다.
    }
}

public inline fun <T> TelemetryObserver.measure(stage: PipelineStage, action: () -> T): T {
    val started = System.nanoTime()
    var outcome = "failure"
    try {
        return action().also { outcome = "success" }
    } finally {
        observe(TelemetryMetric.PIPELINE_STAGE_DURATION, (System.nanoTime() - started) / 1e9, "stage" to stage.label, "outcome" to outcome)
    }
}
