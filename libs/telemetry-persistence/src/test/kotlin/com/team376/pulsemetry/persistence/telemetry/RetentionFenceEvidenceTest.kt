package com.team376.pulsemetry.persistence.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ADR 0024 의 fence 확인이 기대는 ClickHouse 24.8 의 동작을 재현한다(Context 의 측정 1·3·4). 이 동작은 ClickHouse 의 구현이라
 * **이미지를 올리면 이 테스트가 먼저 통과해야 한다.** 분석 테이블 대신 같은 모양의 작은 테이블에 쓰고, 조건식은 분석 INSERT 가 쓰는
 * [AnalysisInsert.FENCE_FLOOR] 그대로다.
 *
 * 본문을 흘려 보내는 순서를 테스트가 쥐어야 하므로 [ClickHouseHttpClient] 가 아니라 JDK HTTP 클라이언트로 스트리밍한다.
 */
@Testcontainers
class RetentionFenceEvidenceTest {

	private lateinit var client: ClickHouseHttpClient
	private lateinit var fence: RetentionFence

	private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
	private val tenant = UUID.randomUUID().toString()

	private val june = Instant.parse("2024-06-01T00:00:00Z")
	private val october = Instant.parse("2024-10-01T00:00:00Z")

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient(url())
		ClickHouseSchemaMigrator(client).apply()
		client.execute(
			"CREATE TABLE IF NOT EXISTS $TABLE (tenant_id String, source_time DateTime64(9, 'UTC'), v UInt32) " +
				"ENGINE = MergeTree ORDER BY (tenant_id, source_time)",
		)
		client.execute("TRUNCATE TABLE $TABLE")
		// 측정 3 의 느린 서브쿼리가 fence 행 수만큼 잔다 — 다른 테스트의 행을 남기지 않는다.
		client.execute("TRUNCATE TABLE ${RetentionFence.TABLE}")
		fence = RetentionFence(client)
	}

	@Test
	@DisplayName("측정 1 — INSERT 안에서 fence 를 평가해 통과한 행만 쓰고, written_rows 는 통과한 수다")
	fun conditionalInsertCountsOnlyAdmittedRows() {
		fence.write(tenant, RetentionFence.Value(june, policyEpoch = 1))

		val response = http.send(
			insert(queryId = "evidence-1-$tenant", body = HttpRequest.BodyPublishers.ofString(row(1, "2024-05-31 23:59:59") + row(2, "2024-06-01 00:00:00"))),
			HttpResponse.BodyHandlers.ofString(),
		)

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.headers().firstValue("X-ClickHouse-Summary").orElseThrow()).contains("\"written_rows\":\"1\"")
		assertThat(values()).containsExactly(2)
	}

	@Test
	@DisplayName("측정 3 — 등록이 평가보다 먼저다: fence 서브쿼리를 평가하는 동안 INSERT 가 process list 에 보인다")
	fun registrationPrecedesFenceEvaluation() {
		// 서브쿼리가 fence 행마다 0.5초 자도록 한다 — 평가가 끝나기 전의 창을 만든다.
		fence.write(tenant, RetentionFence.Value(june, policyEpoch = 1))
		fence.write(tenant, RetentionFence.Value(october, policyEpoch = 2))
		val slowFloor = AnalysisInsert.FENCE_FLOOR.replace("WHERE tenant_id =", "WHERE sleepEachRow(0.5) = 0 AND tenant_id =")
		assertThat(slowFloor).isNotEqualTo(AnalysisInsert.FENCE_FLOOR)
		val queryId = "evidence-3-$tenant"

		val pending = http.sendAsync(
			insert(queryId, HttpRequest.BodyPublishers.ofString(row(1, "2025-01-01 00:00:00")), floor = slowFloor),
			HttpResponse.BodyHandlers.ofString(),
		)
		val seen = awaitRegistered(queryId) { pending.isDone }

		assertThat(seen).describedAs("평가 중 process list 에 등록돼 있다").isTrue()
		assertThat(pending.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200)
		assertThat(values()).containsExactly(1)
	}

	@Test
	@DisplayName("측정 4 — fence 는 시작 때 한 번 평가된다: 등록된 INSERT 는 fence 가 움직인 뒤 도착한 행도 옛 fence 로 쓴다")
	fun fenceIsEvaluatedOnceAtStart() {
		fence.write(tenant, RetentionFence.Value(june, policyEpoch = 1))
		val queryId = "evidence-4-$tenant"

		ChunkedInsert(clickhouse.host, clickhouse.getMappedPort(HTTP_PORT), target(queryId, settings = mapOf("max_query_size" to "1024"))).use { insert ->
			// max_query_size 를 넘기는 첫 묶음 — 서버가 쿼리를 파싱하고 등록하게 한다. 본문은 아직 끝나지 않았다.
			insert.chunk((1..FIRST_BATCH).joinToString("") { row(it, "2024-08-01 00:00:00") })
			assertThat(awaitRegistered(queryId) { false }).describedAs("본문을 다 받기 전에 등록된다").isTrue()

			// 등록된 뒤 fence 를 옮긴다. 늦게 도착한 2024-09 행은 새 fence(10월)로는 경계 이전이다.
			fence.write(tenant, RetentionFence.Value(october, policyEpoch = 2))
			insert.chunk(row(LATE, "2024-09-01 00:00:00"))

			assertThat(insert.finish()).isEqualTo(200)
		}
		assertThat(values()).describedAs("옛 fence(6월)로 판정돼 늦은 행까지 남는다 — drain 이 필요한 이유").hasSize(FIRST_BATCH + 1).contains(LATE)

		// fence 가 움직인 뒤 등록된 INSERT 는 새 fence 로 판정된다.
		http.send(insert("evidence-4b-$tenant", HttpRequest.BodyPublishers.ofString(row(LATE + 1, "2024-09-01 00:00:00"))), HttpResponse.BodyHandlers.ofString())
		assertThat(values()).doesNotContain(LATE + 1)
	}

	/** 분석 INSERT 와 같은 모양 — `input()`, fence 조건, 동기 INSERT. */
	private fun insert(queryId: String, body: HttpRequest.BodyPublisher, floor: String = AnalysisInsert.FENCE_FLOOR): HttpRequest =
		HttpRequest.newBuilder(URI.create(url() + target(queryId, floor))).timeout(Duration.ofSeconds(30)).POST(body).build()

	/** INSERT 요청의 경로와 쿼리 문자열. */
	private fun target(queryId: String, floor: String = AnalysisInsert.FENCE_FLOOR, settings: Map<String, String> = emptyMap()): String {
		val query = "INSERT INTO $TABLE (tenant_id, source_time, v) SELECT tenant_id, source_time, v " +
			"FROM input('tenant_id String, source_time DateTime64(9, \\'UTC\\'), v UInt32') " +
			"WHERE source_time >= $floor SETTINGS async_insert = 0 FORMAT JSONEachRow"
		val parameters = mapOf("query" to query, "param_${AnalysisInsert.TENANT_PARAM}" to tenant, "query_id" to queryId) + settings
		return "/?" + parameters.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
	}

	/** INSERT 가 끝나기 전에 process list 에서 보이면 true. */
	private fun awaitRegistered(queryId: String, finished: () -> Boolean): Boolean {
		val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
		while (System.nanoTime() < deadline && !finished()) {
			val found = client.execute(
				"SELECT count() FROM system.processes WHERE query_id = {id:String} AND query_kind = 'Insert' FORMAT TSV",
				params = mapOf("id" to queryId),
			).trim()
			if (found == "1") return true
			Thread.sleep(POLL_MILLIS)
		}
		return false
	}

	private fun row(v: Int, time: String): String = """{"tenant_id":"$tenant","source_time":"$time","v":$v}""" + "\n"

	private fun values(): List<Int> =
		client.execute("SELECT v FROM $TABLE WHERE tenant_id = {t:String} ORDER BY v FORMAT TSV", params = mapOf("t" to tenant))
			.lines().filter { it.isNotBlank() }.map { it.toInt() }

	private fun url(): String = "http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}"

	/**
	 * HTTP/1.1 chunked 본문을 테스트가 정한 때에 한 조각씩 보낸다. JDK 클라이언트의 스트리밍 본문은 조각을 보내는 시점을 보장하지
	 * 않아(측정 중 첫 조각이 서버에 닿지 않았다) 소켓에 직접 쓴다.
	 */
	private class ChunkedInsert(host: String, port: Int, target: String) : AutoCloseable {
		private val socket = Socket(host, port).apply { soTimeout = 30_000 }
		private val out = socket.getOutputStream()

		init {
			out.write("POST $target HTTP/1.1\r\nHost: $host:$port\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
			out.flush()
		}

		fun chunk(text: String) {
			val bytes = text.toByteArray(StandardCharsets.UTF_8)
			out.write("${Integer.toHexString(bytes.size)}\r\n".toByteArray(StandardCharsets.US_ASCII))
			out.write(bytes)
			out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
			out.flush()
		}

		/** 본문을 끝내고 응답 상태 코드를 돌려준다. */
		fun finish(): Int {
			out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
			out.flush()
			val status = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
			return status.split(' ')[1].toInt()
		}

		override fun close() = socket.close()
	}

	companion object {
		private const val HTTP_PORT: Int = 8123
		private const val TABLE = "fence_evidence_rows"
		private const val FIRST_BATCH = 200
		private const val LATE = 9_999
		private const val POLL_MILLIS = 20L

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
