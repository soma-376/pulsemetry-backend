package com.team376.pulsemetry.telemetry.gateway

import com.team376.pulsemetry.telemetry.gateway.support.AbstractGatewayIntegrationTest
import io.micrometer.tracing.Tracer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.TimeUnit

/**
 * 실제 Boot 배선에서 내보내는 span 이 [com.team376.pulsemetry.telemetry.gateway.config.ScrubbedSpanExporter] 를 지나는지 본다(ADR 0069).
 * 다른 시험의 [support.TestSpans] 는 span processor 라 export 경로를 거치지 않는다 — 여기서는 exporter 를 bean 으로 둬 Boot 가 batch processor 에 붙이게 한다.
 * 미처리 예외의 서버 span 과 같은 모양(`span.error(원본 예외)`)을 직접 만든다.
 */
@AutoConfigureTracing
@Import(SpanScrubbingTest.Sink::class)
@TestPropertySource(
	properties = [
		"management.tracing.export.enabled=true",
		"management.tracing.sampling.probability=1.0",
		"management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces",
	],
)
class SpanScrubbingTest : AbstractGatewayIntegrationTest() {

	@Autowired
	private lateinit var tracer: Tracer

	@Autowired
	private lateinit var provider: SdkTracerProvider

	@Test
	@DisplayName("span.error(원본 예외)의 메시지·stack 과 status 설명은 export 전에 지워지고 ERROR 와 예외 종류는 남는다")
	fun exportedSpansCarryNoExceptionText() {
		val span = tracer.nextSpan().name("scrub probe").start()
		span.error(IllegalStateException("CANARY-scrub message"))
		span.end()
		assertThat(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess).isTrue()

		val exported = Sink.exporter.finishedSpanItems.single { it.name == "scrub probe" }
		assertThat(exported.status.statusCode).isEqualTo(StatusCode.ERROR)
		assertThat(exported.status.description).isEmpty()
		val exception = exported.events.single { it.name == "exception" }
		assertThat(exception.attributes.get(AttributeKey.stringKey("exception.type"))).isEqualTo("java.lang.IllegalStateException")
		assertThat(exception.attributes.asMap().keys.map { it.key }).doesNotContain("exception.message", "exception.stacktrace")
		assertThat(exported.toString()).doesNotContain("CANARY")
	}

	@TestConfiguration(proxyBeanMethods = false)
	class Sink {
		@Bean
		fun scrubSink(): SpanExporter = exporter

		companion object {
			val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
		}
	}
}
