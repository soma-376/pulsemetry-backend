package com.team376.pulsemetry.telemetry.pipeline

import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 적재 직전 관문이 요청 스레드를 줄 세우지 않는지 고정한다 (ADR 0016).
 *
 * ClickHouse 대신 응답을 붙들거나 상태를 마음대로 돌려주는 스텁 서버를 쓴다.
 */
class ClickHouseSchemaTest {

	private lateinit var server: HttpServer
	private var status: Int = 200
	private val requests = AtomicInteger()

	/** 서버가 응답하기 전에 기다릴 래치. 테스트가 열어 준다. */
	private var hold: CountDownLatch = CountDownLatch(0)

	@BeforeEach
	fun startStub() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.executor = Executors.newCachedThreadPool()
		server.createContext("/") { exchange ->
			requests.incrementAndGet()
			hold.await(10, TimeUnit.SECONDS)
			exchange.sendResponseHeaders(status, 0)
			exchange.responseBody.close()
		}
		server.start()
	}

	@AfterEach
	fun stopStub() {
		hold.countDown()
		server.stop(0)
	}

	@Test
	@DisplayName("한 스레드가 적용 중이면 다른 스레드는 기다리지 않고 바로 503 이다")
	fun concurrentCallersDoNotQueueBehindTheLock() {
		hold = CountDownLatch(1)
		val schema = schema()
		val first = Executors.newSingleThreadExecutor().submit { schema.ensureApplied() }
		// 첫 스레드가 서버까지 닿아 래치에 걸릴 때까지 기다린다.
		val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
		while (requests.get() == 0) {
			check(System.nanoTime() < deadline) { "첫 스레드가 스텁 서버에 닿지 않았다" }
			Thread.sleep(5)
		}

		val started = System.nanoTime()
		assertThatThrownBy { schema.ensureApplied() }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1))

		hold.countDown()
		first.get(5, TimeUnit.SECONDS)
		assertThat(requests.get()).isEqualTo(1)
	}

	@Test
	@DisplayName("일시 장애 뒤에는 백오프 동안 다시 시도하지 않는다")
	fun aTransientFailureStartsACooldown() {
		status = 503
		val schema = schema(backoff = Duration.ofMinutes(1))

		assertThatThrownBy { schema.ensureApplied() }.isInstanceOf(TelemetrySinkUnavailableException::class.java)
		assertThatThrownBy { schema.ensureApplied() }.isInstanceOf(TelemetrySinkUnavailableException::class.java)

		assertThat(requests.get()).isEqualTo(1)
	}

	@Test
	@DisplayName("DDL 거부는 503 이고 매 요청마다 다시 던지지 않는다 — 배치는 클라이언트 잘못이 아니다")
	fun aRejectedDdlIsTransientForTheClientAndCoolsDown() {
		status = 400
		val schema = schema(backoff = Duration.ofMinutes(1))

		assertThatThrownBy { schema.ensureApplied() }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
			.hasMessageContaining("거부")
		assertThatThrownBy { schema.ensureApplied() }.isInstanceOf(TelemetrySinkUnavailableException::class.java)

		assertThat(requests.get()).isEqualTo(1)
	}

	@Test
	@DisplayName("백오프가 지나면 다시 시도하고, 성공하면 그 뒤로는 서버를 부르지 않는다")
	fun retriesAfterTheCooldownAndThenStaysApplied() {
		status = 503
		val schema = schema(backoff = Duration.ofMillis(50))
		assertThatThrownBy { schema.ensureApplied() }.isInstanceOf(TelemetrySinkUnavailableException::class.java)
		Thread.sleep(100)

		status = 200
		schema.ensureApplied()
		schema.ensureApplied()

		// 실패 1회 + 성공 1회. 두 번째 성공 호출은 volatile 읽기로 끝난다.
		assertThat(requests.get()).isEqualTo(2)
	}

	// ------------------------------------------------------------------ 도구

	private fun schema(backoff: Duration = Duration.ZERO): ClickHouseSchema = ClickHouseSchema(
		ClickHouseSchemaMigrator(ClickHouseHttpClient("http://127.0.0.1:${server.address.port}")),
		startupAttempts = 0,
		startupBackoff = backoff,
	)
}
