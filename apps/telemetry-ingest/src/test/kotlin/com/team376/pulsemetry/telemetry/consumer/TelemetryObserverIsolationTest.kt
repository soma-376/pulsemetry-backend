package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import java.time.Instant
import java.util.UUID

/** listener의 반환/throw가 offset·재시도 판정이다. 계측 구현의 고장은 이를 바꾸지 않는다. */
class TelemetryObserverIsolationTest {
    @Test
    fun brokenObserverPreservesCommitAndRetryDecision() {
        var failing = false
        val process = mock(ProcessTelemetry::class.java) { if (failing) throw IllegalStateException("private cause") else null }
        val observer = TelemetryObserver { _, _, _ -> throw IllegalStateException("metrics unavailable") }
        val listener = TelemetryRecordListener(process, observer)
        val now = Instant.now()
        val message = AcceptedTelemetry(UUID.randomUUID(), now,
            GatewayAuthContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "pulsemetry-enrollment", "unit", now.minusSeconds(1), now.plusSeconds(600)),
            RawOtlpPayload(OtlpSignal.LOGS, OtlpMediaType.JSON, "", "{}".toByteArray()))
        val record = ConsumerRecord("test", 0, 0L, ByteArray(0), TelemetryEnvelopeCodec.encode(message))
        assertThatCode { listener.onMessage(record) }.doesNotThrowAnyException()
        assertThat(mockingDetails(process).invocations).hasSize(1)
        assertThatCode { listener.onMessage(ConsumerRecord("test", 0, 1L, ByteArray(0), ByteArray(0))) }.doesNotThrowAnyException()
        failing = true
        assertThatThrownBy { listener.onMessage(record) }.isInstanceOf(TelemetryTransientException::class.java)
            .hasMessageContaining("cause=IllegalStateException").hasMessageNotContaining("private cause")
    }
}
