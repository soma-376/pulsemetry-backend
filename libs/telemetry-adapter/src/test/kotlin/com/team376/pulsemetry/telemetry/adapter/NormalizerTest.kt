package com.team376.pulsemetry.telemetry.adapter

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 진입점의 계약 — 신호 판별과 방출 규칙. */
internal class NormalizerTest {

	@Test
	@DisplayName("빈 요청은 이벤트를 내지 않는다")
	fun emptyRequestsProduceNothing() {
		assertThat(Normalizer.normalize(ExportLogsServiceRequest.getDefaultInstance())).isEmpty()
		assertThat(Normalizer.normalize(ExportTraceServiceRequest.getDefaultInstance())).isEmpty()
		assertThat(Normalizer.normalize(ExportMetricsServiceRequest.getDefaultInstance())).isEmpty()
	}

	@Test
	@DisplayName("요청 타입이 신호를 정한다 — 따로 넘기지 않는다")
	fun requestTypeDecidesTheSignal() {
		val logs = normalizeFixture("/otlp/claude_code/logs_synthetic.otlp.jsonl", 0, LOGS)
		val spans = normalizeFixture("/otlp/claude_code/traces_synthetic.otlp.jsonl", 0, TRACES)
		val metrics = normalizeFixture("/otlp/claude_code/metrics_synthetic.otlp.jsonl", 0, METRICS)

		assertThat(logs.map { it.envelope.ingest.signal.wire }).containsOnly("log")
		assertThat(spans.map { it.envelope.ingest.signal.wire }).containsOnly("span")
		assertThat(metrics.map { it.envelope.ingest.signal.wire }).containsOnly("metric")
	}

	@Test
	@DisplayName("제품 namespace 밖의 레코드는 조용히 버려진다")
	fun recordsOutsideTheProductNamespaceAreDropped() {
		// 경계 문서에는 미지원 claude_code 이벤트와 제품 밖 이벤트가 함께 들어 있다.
		val events = normalizeFixture("/otlp/claude_code/logs_synthetic.otlp.jsonl", 4, LOGS)
		assertThat(events).hasSize(3)
	}

	@Test
	@DisplayName("record_id 는 페어링 전에 확정돼 이후 call_id 변경에 흔들리지 않는다")
	fun recordIdIsFixedBeforePairing() {
		val events = normalizeFixture("/otlp/codex/pairing_synthetic.otlp.jsonl", 0, LOGS)

		assertThat(events).hasSize(2)
		val (decision, call) = events
		// 페어링으로 call_id 는 같아졌지만 record_id 는 각자 그대로다.
		assertThat(decision.callId).isEqualTo(call.callId)
		assertThat(decision.envelope.recordId).isNotEqualTo(call.envelope.recordId)
	}

	private fun normalizeFixture(
		resource: String,
		documentIndex: Int,
		signal: GoldenFixtures.Signal,
	) = Normalizer.normalize(GoldenFixtures.requests(resource, signal)[documentIndex])

	private companion object {
		val LOGS = GoldenFixtures.Signal.LOGS
		val TRACES = GoldenFixtures.Signal.TRACES
		val METRICS = GoldenFixtures.Signal.METRICS
	}
}
