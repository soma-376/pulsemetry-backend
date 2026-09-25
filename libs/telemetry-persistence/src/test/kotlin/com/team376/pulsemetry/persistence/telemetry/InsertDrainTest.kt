package com.team376.pulsemetry.persistence.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * drain 의 판정(ADR 0024 §4 의 3)을 실제 ClickHouse 위에서 본다 — 서버에 등록된 INSERT 가 끝나기 전에는 기다림이 끝나지 않고, 끝나면
 * 끝난다. 요청 쪽 timeout 은 판정에 쓰이지 않는다.
 */
@Testcontainers
class InsertDrainTest {

	private lateinit var client: ClickHouseHttpClient
	private lateinit var drain: InsertDrain

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient(url())
		client.execute("CREATE TABLE IF NOT EXISTS $TABLE (v UInt32) ENGINE = MergeTree ORDER BY v")
		drain = InsertDrain(client)
	}

	@Test
	@DisplayName("실행 중인 INSERT 가 목록에 잡히고, 끝나기 전에는 기다림이 상한에서 false, 끝나면 true 다")
	fun waitsUntilTheRegisteredInsertFinishes() {
		val queryId = "drain-${UUID.randomUUID()}"
		ChunkedInsert(clickhouse.host, clickhouse.getMappedPort(HTTP_PORT), target(queryId)).use { insert ->
			// max_query_size 를 넘겨 서버가 등록하게 한다 — 본문은 아직 열려 있다.
			insert.chunk((1..200).joinToString("") { """{"v":$it}""" + "\n" })
			assertThat(awaitRegistered(queryId)).isTrue()

			val inFlight = drain.running()
			assertThat(inFlight).contains(queryId)
			assertThat(drain.awaitFinished(inFlight, Duration.ofMillis(300), Duration.ofMillis(50))).isFalse()

			assertThat(insert.finish()).isEqualTo(200)
		}

		assertThat(drain.awaitFinished(setOf(queryId), Duration.ofSeconds(10), Duration.ofMillis(50))).isTrue()
	}

	@Test
	@DisplayName("INSERT 가 아닌 쿼리는 모으지 않는다 — 실행 중인 SELECT 는 목록에 없다")
	fun selectsAreNotCollected() {
		val queryId = "drain-select-${UUID.randomUUID()}"
		val select = CompletableFuture.supplyAsync {
			client.execute("SELECT sleep(2) SETTINGS max_execution_time = 10", queryId = queryId)
		}
		try {
			val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
			var seen = false
			while (!seen && System.nanoTime() < deadline) {
				seen = client.execute("SELECT count() FROM system.processes WHERE query_id = {id:String} FORMAT TSV", params = mapOf("id" to queryId)).trim() == "1"
				if (!seen) Thread.sleep(20)
			}
			assertThat(seen).describedAs("SELECT 가 실행 중이다").isTrue()

			assertThat(drain.running()).doesNotContain(queryId)
		} finally {
			select.get(15, TimeUnit.SECONDS)
		}
	}

	@Test
	@DisplayName("기다릴 것이 없으면 서버에 묻지 않고 끝난다")
	fun nothingToWaitFor() {
		val unreachable = InsertDrain(ClickHouseHttpClient("http://127.0.0.1:1"))

		assertThat(unreachable.awaitFinished(emptySet(), Duration.ZERO, Duration.ofMillis(10))).isTrue()
	}

	private fun target(queryId: String): String {
		val parameters = mapOf("query" to "INSERT INTO $TABLE FORMAT JSONEachRow", "query_id" to queryId, "max_query_size" to "1024")
		return "/?" + parameters.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
	}

	private fun awaitRegistered(queryId: String): Boolean {
		val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
		while (System.nanoTime() < deadline) {
			val found = client.execute("SELECT count() FROM system.processes WHERE query_id = {id:String} FORMAT TSV", params = mapOf("id" to queryId)).trim()
			if (found == "1") return true
			Thread.sleep(20)
		}
		return false
	}

	private fun url(): String = "http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}"

	companion object {
		private const val HTTP_PORT: Int = 8123
		private const val TABLE = "drain_rows"

		@Container
		@JvmStatic
		val clickhouse: GenericContainer<*> =
			GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
				.withExposedPorts(HTTP_PORT)
				// 이게 없으면 entrypoint 가 default 유저를 루프백 전용으로 잠근다 (infra ADR-0019).
				.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
				.waitingFor(Wait.forHttp("/ping").forPort(HTTP_PORT).forStatusCode(200))
	}
}
