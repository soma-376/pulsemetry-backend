package com.team376.pulsemetry.telemetry.support

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Duration

/**
 * 끝난 span을 메모리에 모은다. exporter를 빈으로 두지 않는다 — 빈이면 Boot가 OTLP batch processor에도 붙여 같은 span을 두 번 받는다.
 * 서버 span은 응답을 보낸 뒤에 끝날 수 있어 [await]로 기다린다.
 */
object TestSpans {
	val exporter: InMemorySpanExporter = InMemorySpanExporter.create()

	fun await(timeout: Duration = Duration.ofSeconds(5), condition: (List<SpanData>) -> Boolean): List<SpanData> {
		val deadline = System.nanoTime() + timeout.toNanos()
		while (true) {
			val spans = exporter.finishedSpanItems
			if (condition(spans) || System.nanoTime() > deadline) return spans
			Thread.sleep(20)
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		fun testSpanProcessor(): SpanProcessor = SimpleSpanProcessor.create(exporter)
	}
}
