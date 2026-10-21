package com.team376.pulsemetry.telemetry.observability

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TelemetryObserverTest {
    @Test
    fun prometheusContractAndBoundedLabels() {
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).let { registry ->
            val observer = MicrometerTelemetryObserver(registry)
            observer.observe(TelemetryMetric.GATEWAY_REQUESTS, 1.0, "signal" to "logs", "status" to "200", "outcome" to "accepted")
            observer.observe(TelemetryMetric.GATEWAY_REQUEST_DURATION, 0.025, "signal" to "logs", "status" to "200")
            observer.observe(TelemetryMetric.GATEWAY_REQUEST_BYTES, 1024.0, "signal" to "logs")
            observer.observe(TelemetryMetric.GATEWAY_PUBLISH, 1.0, "signal" to "logs", "outcome" to "acknowledged", "reason" to "none")
            observer.observe(TelemetryMetric.GATEWAY_PUBLISH_DURATION, 0.01, "signal" to "logs", "outcome" to "acknowledged")
            observer.observe(TelemetryMetric.PIPELINE_ATTEMPTS, 1.0, "signal" to "logs", "outcome" to "completed")
            observer.measure(PipelineStage.DECODE) { Unit }
            observer.observe(TelemetryMetric.PIPELINE_ACCEPTED_TO_ATTEMPT_START, 0.2, "signal" to "logs")
            observer.observe(TelemetryMetric.PIPELINE_ACCEPTED_TO_COMPLETION, 0.3, "signal" to "logs")
            observer.observe(TelemetryMetric.CLICKHOUSE_REQUESTS, 1.0, "operation" to "insert", "table" to "telemetry_events", "outcome" to "success")
            observer.observe(TelemetryMetric.CLICKHOUSE_REQUEST_DURATION, 0.02, "operation" to "insert", "table" to "telemetry_events", "outcome" to "success")
            observer.observe(TelemetryMetric.CLICKHOUSE_ROWS_SUBMITTED, 2.0, "table" to "telemetry_events")
            observer.observe(TelemetryMetric.PIPELINE_RETRY_EXHAUSTED, 1.0, "stage" to "insert")
            observer.observe(TelemetryMetric.PIPELINE_COMMIT_FAILURES)
            observer.observe(TelemetryMetric.PIPELINE_EMPTY, 1.0, "signal" to "logs")
            observer.observe(TelemetryMetric.PIPELINE_EXCLUDED_OBSERVATIONS, 1.0, "reason" to "retention")
            observer.observe(TelemetryMetric.GATEWAY_REJECTIONS, 1.0, "stage" to "authenticate", "reason" to "missing_bearer")
            repeat(100) { observer.observe(TelemetryMetric.PIPELINE_FAILURES, 1.0, "stage" to "CANARY-$it", "reason" to "CANARY-$it", "tenant" to "CANARY-$it") }
            val scrape = registry.scrape()
            assertThat(scrape).contains("pulsemetry_gateway_requests_total", "pulsemetry_gateway_request_duration_seconds_bucket", "pulsemetry_gateway_request_bytes_bucket")
            assertThat(scrape).contains("pulsemetry_gateway_publish_duration_seconds_bucket", "pulsemetry_pipeline_stage_duration_seconds_bucket")
            assertThat(scrape).contains("pulsemetry_pipeline_accepted_to_completion_seconds_bucket", "pulsemetry_clickhouse_rows_submitted_total")
            assertThat(scrape).doesNotContain("CANARY", "tenant=", "quantile=")
            assertThat(registry.get("pulsemetry.pipeline.failures").counters()).hasSize(1)
            Files.createDirectories(Path.of("build"))
            Files.writeString(Path.of("build/metrics-contract.prom"), scrape)
            registry.close()
        }
    }

    @Test
    fun observerFailurePreservesWorkAndOriginalFailure() {
        val broken = TelemetryObserver { _, _, _ -> throw IllegalStateException("observer unavailable") }
        assertThat(broken.measure(PipelineStage.ARCHIVE) { "written" }).isEqualTo("written")
        val original = IllegalArgumentException("original failure")
        assertThatThrownBy { broken.measure(PipelineStage.INSERT) { throw original } }.isSameAs(original)
    }

    @Test
    fun negativeAgeIsNotSuccessfulLatencyAndInflightReturnsToZero() {
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).let { registry ->
            val observer = MicrometerTelemetryObserver(registry)
            observer.observe(TelemetryMetric.PIPELINE_ACCEPTED_TO_COMPLETION, -1.0, "signal" to "logs")
            assertThat(registry.get("pulsemetry.pipeline.clock.skew").counter().count()).isEqualTo(1.0)
            assertThat(registry.find("pulsemetry.pipeline.accepted.to.completion").timer()).isNull()
            observer.observe(TelemetryMetric.PIPELINE_INFLIGHT)
            observer.observe(TelemetryMetric.PIPELINE_INFLIGHT, -1.0)
            System.gc()
            assertThat(registry.get("pulsemetry.pipeline.inflight").gauge().value()).isZero()
            registry.close()
        }
    }
}
