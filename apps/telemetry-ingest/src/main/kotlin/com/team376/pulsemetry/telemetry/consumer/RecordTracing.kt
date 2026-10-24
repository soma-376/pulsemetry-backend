package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.telemetry.observability.PlatformTracing
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.apache.kafka.clients.consumer.ConsumerRecord

/**
 * Kafka record 처리의 플랫폼 span(ADR 0069). trace 를 끄면(기본) [Tracer.NOOP] 이라 아무것도 만들지 않는다.
 *
 * record header 의 `traceparent` 가 정확히 하나이고 길이가 맞을 때만 부모로 쓴다. 없음·중복·과대 길이는 새 root 다 — 형식이 틀린 값은
 * W3C propagator 가 버린다. header 가 있든 없든 처리 결과는 같다. Gateway 의 header 확장 전후 record 가 섞여도 소비는 그대로 돈다.
 *
 * 속성은 topic·partition·offset·시도 횟수와 처리 결과뿐이다. 신원·payload·예외 메시지를 싣지 않는다.
 * 실패한 시도는 status ERROR 이고 분류는 `error.type`(reason·예외 클래스 이름·outcome 코드)이다.
 */
class RecordTracing(private val tracer: Tracer = Tracer.NOOP, private val propagator: Propagator = Propagator.NOOP) {

	/**
	 * 처리 시도마다 새 span 이다 — 재시도는 같은 부모 아래 다른 span ID 로 남는다.
	 * trace 구현이 실패해도 처리·offset 판정을 바꾸지 않도록 [Span.NOOP] 으로 대신한다(ADR 0068 의 observer 격리와 같은 원칙).
	 */
	fun start(record: ConsumerRecord<*, *>, name: String, attempt: Int? = null): Span =
		runCatching { begin(record, name, attempt) }.getOrDefault(Span.NOOP)

	private fun begin(record: ConsumerRecord<*, *>, name: String, attempt: Int?): Span {
		val parent = PlatformTracing.singleTraceparent(record.headers().headers(PlatformTracing.TRACEPARENT).map { it.value() ?: ByteArray(0) })
		val builder = if (parent == null) {
			tracer.spanBuilder().setNoParent()
		} else {
			propagator.extract(record) { _, key -> if (key == PlatformTracing.TRACEPARENT) parent else null }
		}
		return builder
			.name(name)
			.kind(Span.Kind.CONSUMER)
			.remoteServiceName("kafka")
			.tag("messaging.system", "kafka")
			.tag("messaging.destination.name", record.topic())
			.tag("messaging.destination.partition.id", record.partition().toString())
			.tag("messaging.kafka.offset", record.offset().toString())
			.apply { if (attempt != null) tag("pulsemetry.attempt", attempt.toString()) }
			.start()
	}

	/** 실패 span 을 ERROR 로 표시한다. 원본 예외 대신 메시지·stack 없는 표식을 넘긴다 — status 설명과 이벤트에 원문이 실리지 않는다. */
	fun fail(span: Span, errorType: String) {
		runCatching { span.tag(PlatformTracing.ERROR_TYPE, errorType).error(PlatformTracing.SPAN_FAILURE) }
	}

	/** sampled 된 span 인가. 끄거나 sampling 에서 빠진 요청은 false 다. */
	fun sampled(span: Span): Boolean = runCatching { span.context().sampled() == true }.getOrDefault(false)

	/** span 을 현재 문맥으로 연다. 닫으면 이전 문맥과 MDC 로 돌아간다 — 다음 record 로 문맥이 새지 않는다. */
	fun scope(span: Span): Tracer.SpanInScope = runCatching { tracer.withSpan(span) }.getOrElse { Tracer.NOOP.withSpan(null) }

	/**
	 * listener scope 밖에서 나는 로그(재시도 소진·commit 실패)를 그 record 의 trace 에 잇는다. 짧은 span 을 열어 [log] 를 부르고 닫는다.
	 * 부르는 곳은 실패 경로뿐이라 span 은 ERROR 이고 `error.type` 은 [outcome] 이다.
	 * span 이나 로그가 실패해도 호출자의 offset 판정을 바꾸지 않는다 — 호출자가 예외를 삼킨다.
	 */
	fun around(record: ConsumerRecord<*, *>, name: String, outcome: String, stage: String, log: () -> Unit) {
		val span = start(record, name)
		try {
			scope(span).use { log() }
		} finally {
			fail(span, outcome)
			span.tag("pulsemetry.outcome", outcome).tag("pulsemetry.stage", stage).end()
		}
	}
}
