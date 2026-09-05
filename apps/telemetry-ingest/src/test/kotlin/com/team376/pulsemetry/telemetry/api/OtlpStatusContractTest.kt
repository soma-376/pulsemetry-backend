package com.team376.pulsemetry.telemetry.api

import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import com.team376.pulsemetry.telemetry.collector.OtlpIngestHandler
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.SignalConsumer
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.Product
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * HTTP 층의 상태 계약을 고정한다 (허브 ADR 0006 Acceptance Criteria — "503 응답에 `Retry-After` 가 있다").
 *
 * 컨텍스트를 띄우지 않는다. 실물 [OtlpIngestHandler] 에 예외를 던지는 [SignalConsumer] 를 물려
 * standalone `MockMvc` 로 컨트롤러만 돌린다 — 판정 대상이 컨트롤러가 헤더를 어느 상태에 붙이는가이지
 * 파이프라인이 아니다. `@SpringBootTest` 로 하면 컨텍스트 캐시가 쪼개져 컨테이너가 하나 더 뜬다.
 */
class OtlpStatusContractTest {

	@Test
	@DisplayName("일시 장애는 503 + Retry-After 다 — 데몬이 이 헤더를 하한으로 쓴다")
	fun aTransientFailureCarriesRetryAfter() {
		val response = post(consumer { throw TelemetrySinkUnavailableException("clickhouse down") })

		assertThat(response.status).isEqualTo(503)
		assertThat(response.getHeader("Retry-After")).isEqualTo("1")
		assertThat(response.contentType).isEqualTo("application/json")
		// google.rpc.Status — UNAVAILABLE 은 14 다.
		assertThat(response.contentAsString).contains("\"code\":14")
	}

	@Test
	@DisplayName("영구 실패는 400 이고 Retry-After 가 없다 — 데몬이 즉시 폐기한다")
	fun aPermanentFailureHasNoRetryAfter() {
		val response = post(consumer { throw PermanentIngestException("schema drift") })

		assertThat(response.status).isEqualTo(400)
		assertThat(response.getHeader("Retry-After")).isNull()
		assertThat(response.contentAsString).contains("\"code\":3")
	}

	@Test
	@DisplayName("성공에는 Retry-After 가 없다")
	fun successHasNoRetryAfter() {
		val response = post(consumer { })

		assertThat(response.status).isEqualTo(200)
		assertThat(response.getHeader("Retry-After")).isNull()
		assertThat(response.contentAsString).isEqualTo("""{"partialSuccess":{}}""")
	}

	// ------------------------------------------------------------------ 도구

	private fun consumer(block: () -> Unit): SignalConsumer = SignalConsumer { _, _ -> block() }

	private fun post(next: SignalConsumer): MockHttpServletResponse =
		mockMvc(next)
			.perform(
				post("/v1/logs")
					.contentType("application/json")
					.content(ONE_LOG_RECORD),
			)
			.andReturn()
			.response

	private fun mockMvc(next: SignalConsumer): MockMvc {
		val archive = object : ArchiveWriter {
			override fun write(product: Product, signal: Signal, body: ByteArray) = Unit
		}
		val handler = OtlpIngestHandler(archive = archive, next = next)
		val properties = TelemetryIngestProperties(tokenHashSecret = "test-token-hash-secret")
		return MockMvcBuilders.standaloneSetup(OtlpController(handler, properties)).build()
	}

	private companion object {
		/** 레코드 0건이면 소비자를 부르지 않으므로 하나는 있어야 한다. */
		val ONE_LOG_RECORD: ByteArray = """
			{"resourceLogs":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"claude-code"}}]},
			 "scopeLogs":[{"logRecords":[{"body":{"stringValue":"claude_code.user_prompt"}}]}]}]}
		""".trimIndent().toByteArray()
	}
}
