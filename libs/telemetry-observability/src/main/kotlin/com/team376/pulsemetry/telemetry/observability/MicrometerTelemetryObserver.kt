package com.team376.pulsemetry.telemetry.observability

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** registry의 수명은 앱이 소유한다. gauge backing object를 강하게 참조하고 유한 allowlist만 label에 싣는다. */
public class MicrometerTelemetryObserver(private val registry: MeterRegistry) : TelemetryObserver {
    private val inflight = listOf(TelemetryMetric.GATEWAY_INFLIGHT, TelemetryMetric.PIPELINE_INFLIGHT).associateWith { metric ->
        AtomicLong().also { Gauge.builder(metric.meterName, it, AtomicLong::toDouble).register(registry) }
    }

    override fun record(metric: TelemetryMetric, value: Double, vararg tags: Pair<String, String>) {
        if (!value.isFinite()) return
        if (metric.kind == TelemetryMetric.Kind.GAUGE) {
            inflight.getValue(metric).addAndGet(value.toLong())
            return
        }
        if (value < 0) {
            if (metric.kind == TelemetryMetric.Kind.AGE) registry.counter(TelemetryMetric.CLOCK_SKEW.meterName).increment()
            return
        }
        val supplied = tags.toMap()
        val bounded = metric.labels.map { key -> Tag.of(key, label(key, supplied[key])) }
        when (metric.kind) {
            TelemetryMetric.Kind.COUNTER -> registry.counter(metric.meterName, bounded).increment(value)
            TelemetryMetric.Kind.TIMER, TelemetryMetric.Kind.AGE -> Timer.builder(metric.meterName)
                .tags(bounded)
                .serviceLevelObjectives(*(if (metric.kind == TelemetryMetric.Kind.AGE) AGE_BUCKETS else DURATION_BUCKETS))
                .register(registry).record((value * 1e9).toLong(), TimeUnit.NANOSECONDS)
            TelemetryMetric.Kind.BYTES -> DistributionSummary.builder(metric.meterName).baseUnit("bytes").tags(bounded)
                .serviceLevelObjectives(1024.0, 16384.0, 65536.0, 262144.0, 1048576.0, 4194304.0, 10485760.0)
                .register(registry).record(value)
            TelemetryMetric.Kind.GAUGE -> Unit
        }
    }

    private fun label(key: String, value: String?): String = when (key) {
        "signal" -> value.takeIf { it in setOf("logs", "traces", "metrics") } ?: "unknown"
        "status" -> value.takeIf { it in setOf("200", "400", "401", "404", "405", "413", "415", "500", "503") } ?: "other"
        "stage" -> value.takeIf { it in STAGES } ?: "other"
        "outcome" -> value.takeIf { it in OUTCOMES } ?: "other"
        "operation" -> value.takeIf { it in setOf("insert", "schema", "select", "other") } ?: "other"
        "table" -> value.takeIf { it in setOf("telemetry_events", "telemetry_metric_points", "telemetry_ingest_ledger") } ?: "other"
        "reason" -> value.takeIf { it in REASONS } ?: "other"
        else -> "other"
    }

    private companion object {
        val DURATION_BUCKETS = listOf(5L, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000).map(Duration::ofMillis).toTypedArray()
        val AGE_BUCKETS = listOf(100L, 250, 500, 1000, 2500, 5000, 10000, 30000, 60000, 120000, 300000, 600000).map(Duration::ofMillis).toTypedArray()
        val STAGES = PipelineStage.entries.map { it.label }.toSet() + setOf("authenticate", "request", "publish", "envelope", "pipeline", "process", "commit", "container")
        val OUTCOMES = setOf("accepted", "rejected", "failed", "acknowledged", "success", "failure", "completed", "permanent_rejected", "transient_failed", "permanent_error", "transient_error")
        val REASONS = setOf(
            "none", "unavailable", "unexpected", "pipeline_rejected", "commit_failed", "method_not_allowed", "path_unknown", "unsupported_media_type",
            "unsupported_content_encoding", "payload_too_large", "body_too_large", "decompress_failed", "decode_failed", "masking_failed",
            "deadline_exceeded", "producer_closed", "interrupted", "record_too_large", "not_enough_replicas", "authorization_failed", "metadata_timeout", "delivery_timeout", "broker_error",
            "missing_bearer", "malformed_bearer", "jwt_malformed", "jwt_algorithm", "jwt_type", "jwt_header", "jwt_kid_unknown", "jwt_signature", "jwt_issuer", "jwt_audience",
            "jwt_scope", "jwt_claim_missing", "jwt_claim_format", "jwt_lifetime", "jwt_not_yet_valid", "jwt_expired",
            "credential_unknown", "credential_format", "credential_mismatch", "credential_expired", "credential_rotated", "credential_security_revoked", "credential_revoked_unknown",
            "envelope_unparseable", "envelope_version", "envelope_accepted_at", "envelope_auth", "envelope_payload", "envelope_issued_at", "envelope_expires_at", "envelope_issuer",
            "envelope_key_id", "envelope_signal", "envelope_content_type", "envelope_content_encoding", "envelope_message_id", "envelope_credential_id", "envelope_tenant_id", "envelope_installation_id",
            "source_time", "excluded_span", "retention",
        )
    }
}
