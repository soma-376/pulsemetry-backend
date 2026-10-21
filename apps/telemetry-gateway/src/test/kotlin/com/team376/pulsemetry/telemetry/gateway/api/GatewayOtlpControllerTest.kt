package com.team376.pulsemetry.telemetry.gateway.api

import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import com.team376.pulsemetry.telemetry.gateway.config.TelemetryGatewayProperties
import com.team376.pulsemetry.telemetry.gateway.publish.PublishedRecord
import com.team376.pulsemetry.telemetry.gateway.publish.TelemetryPublishException
import com.team376.pulsemetry.telemetry.gateway.publish.TelemetryPublisher
import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 접수 순서의 핵심 둘 — **ack 전에는 200 을 쓰지 않는다**, 발행 실패·기한 초과는 503 + `Retry-After` 다(ADR 0066 Decision 2·3).
 * Kafka 없이 발행기를 손으로 막아 본다.
 */
class GatewayOtlpControllerTest {

	private val key = TelemetryJwtFixtures.signingKey("tk-unit")
	private val properties = TelemetryGatewayProperties(
		telemetryJwt = TelemetryGatewayProperties.TelemetryJwt(mapOf("tk-unit" to "/unused")),
	)
	private val writer = GatewayResponseWriter(properties)

	private fun controller(publisher: TelemetryPublisher, clock: Clock = Clock.systemUTC()) =
		GatewayOtlpController(key.verifier(), publisher, writer, clock, properties)

	private fun request(): MockHttpServletRequest = MockHttpServletRequest("POST", "/v1/logs").apply {
		addHeader("Authorization", "Bearer " + key.issuer().issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()).token)
		contentType = "application/json"
		setContent("""{"resourceLogs":[]}""".toByteArray())
	}

	@Test
	fun instrumentationFailurePreservesAcceptanceAndRejection() {
		val observer = TelemetryObserver { _, _, _ -> throw IllegalStateException("metrics unavailable") }
		for ((publisher, expected) in listOf(
			TelemetryPublisher { _, _ -> PublishedRecord("t", 0, 0) } to 200,
			TelemetryPublisher { _, _ -> throw TelemetryPublishException("broker_error", "Exception") } to 503,
		)) {
			val response = MockHttpServletResponse()
			GatewayOtlpController(key.verifier(), publisher, writer, Clock.systemUTC(), properties, observer).ingest(request(), response)
			assertThat(response.status).isEqualTo(expected)
		}
	}

	@Test
	@DisplayName("발행이 끝나기 전에는 응답을 쓰지 않는다 — ack 뒤에만 200 이다")
	fun noSuccessBeforeAck() {
		val release = CountDownLatch(1)
		val published = AtomicReference<AcceptedTelemetry>()
		val publisher = TelemetryPublisher { message, _ ->
			published.set(message)
			release.await(10, TimeUnit.SECONDS)
			PublishedRecord("t", 0, 0)
		}
		val response = MockHttpServletResponse()

		val running = CompletableFuture.runAsync { controller(publisher).ingest(request(), response) }
		Thread.sleep(300)

		assertThat(published.get()).isNotNull()
		assertThat(running.isDone).isFalse()
		assertThat(response.isCommitted).isFalse()
		assertThat(response.contentAsByteArray).isEmpty()

		release.countDown()
		running.get(5, TimeUnit.SECONDS)
		assertThat(response.status).isEqualTo(200)
		assertThat(response.contentAsString).isEqualTo("""{"partialSuccess":{}}""")
	}

	@Test
	@DisplayName("발행 실패는 503 + Retry-After 이고 본문에 Kafka 문구가 없다")
	fun publishFailureIs503() {
		val response = MockHttpServletResponse()

		controller({ _, _ -> throw TelemetryPublishException("not_enough_replicas", "NotEnoughReplicasException") }).ingest(request(), response)

		assertThat(response.status).isEqualTo(503)
		assertThat(response.getHeader("Retry-After")).isEqualTo("1")
		assertThat(response.contentAsString).isEqualTo("""{"code":14,"message":"telemetry ingress unavailable"}""")
	}

	@Test
	@DisplayName("발행기에 넘기는 기한은 요청 시작 + 7.5초다 — 본문을 읽은 시간도 포함한다")
	fun deadlineFromRequestStart() {
		val start = Instant.parse("2026-10-21T00:00:00Z")
		val clock = Clock.fixed(start, ZoneOffset.UTC)
		val deadline = AtomicReference<Instant>()
		val signedAt = Instant.now()
		val issued = key.issuer().issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), start)
		val req = MockHttpServletRequest("POST", "/v1/logs").apply {
			addHeader("Authorization", "Bearer ${issued.token}")
			contentType = "application/json"
			setContent("{}".toByteArray())
		}

		controller({ _, d -> deadline.set(d); PublishedRecord("t", 0, 0) }, clock).ingest(req, MockHttpServletResponse())

		assertThat(signedAt).isNotNull()
		assertThat(deadline.get()).isEqualTo(start.plus(Duration.ofMillis(7500)))
	}
}
