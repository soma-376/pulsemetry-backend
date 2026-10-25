package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.observability.MicrometerTelemetryObserver
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID

/** Prometheus `increase()`가 첫 실패를 세려면 실패 전 scrape에 0이 있어야 한다. */
class TelemetryFailureReporterTest {
    @Test
    fun failureCountersAreExposedAtZeroBeforeTheFirstFailure() {
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).let { registry ->
            val reporter = TelemetryFailureReporter(4, observer = MicrometerTelemetryObserver(registry, gauges = emptySet()))
            val before = registry.scrape()
            assertThat(before).contains("pulsemetry_pipeline_commit_failures_total 0.0")
            for (stage in RETRY_EXHAUSTED_STAGES) assertThat(before).contains("""pulsemetry_pipeline_retry_exhausted_total{stage="$stage"} 0.0""")
            reporter.recoverer.accept(ConsumerRecord("test", 0, 0L, ByteArray(0), ByteArray(0)), TelemetryTransientException(null, "authorize", "IOException"))
            assertThat(registry.scrape()).contains("""pulsemetry_pipeline_retry_exhausted_total{stage="authorize"} 1.0""")
            registry.close()
        }
    }

    @Test
    fun everyStageTheRecovererEmitsIsPreRegistered() {
        // 미리 노출하지 않은 stage의 첫 소진은 다시 0이 된다. listener가 실제로 던지는 일시 실패와 오류 처리기 쪽 실패로 확인한다.
        val emitted = mutableListOf<String>()
        val reporter = TelemetryFailureReporter(4) { metric, value, tags ->
            if (metric == TelemetryMetric.PIPELINE_RETRY_EXHAUSTED && value > 0) emitted += tags.toMap().getValue("stage")
        }
        val now = Instant.now()
        val message = AcceptedTelemetry(UUID.randomUUID(), now,
            GatewayAuthContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "pulsemetry-enrollment", "unit", now.minusSeconds(1), now.plusSeconds(600)),
            RawOtlpPayload(OtlpSignal.LOGS, OtlpMediaType.JSON, "", "{}".toByteArray()))
        val record = ConsumerRecord("test", 0, 0L, ByteArray(0), TelemetryEnvelopeCodec.encode(message))
        val failures = listOf(TelemetryStageFailure("authorize", IllegalStateException("rdb")), IllegalStateException("clickhouse")).map { cause ->
            val listener = TelemetryRecordListener(mock(ProcessTelemetry::class.java) { throw cause })
            runCatching { listener.onMessage(record) }.exceptionOrNull()
        }
        // 오류 처리기는 listener 예외를 감싸서 넘긴다.
        for (exception in failures.map { RuntimeException(it) } + listOf(CommitFailedException(), IllegalStateException("container"))) {
            reporter.recoverer.accept(record, exception)
        }
        assertThat(emitted).containsExactly("authorize", "process", "commit", "container")
        assertThat(RETRY_EXHAUSTED_STAGES).containsAll(emitted)
    }
}
