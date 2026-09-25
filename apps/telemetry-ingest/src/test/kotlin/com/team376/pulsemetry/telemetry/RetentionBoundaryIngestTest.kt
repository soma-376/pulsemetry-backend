package com.team376.pulsemetry.telemetry

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.RetentionFence
import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsUnavailableException
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestTestData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * 조직별 삭제 경계를 ingest 가 집행하는지 본다(ADR 0024 §3·§6 · 허브 ADR 0007 AC8). 기대값은 그 규칙에서 온다 — `source_time` 이
 * 경계보다 이른 관측은 분석 테이블에 남지 않고, 수신 사실은 ledger 에 거부로 남는다. 경계를 읽지 못하면 허용하지 않고 503 이다.
 *
 * 보존 작업(경계 발효 뒤 DELETE)은 아직 없다 — 여기서는 경계를 [com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore.advance]
 * 로 발효하고, 삭제는 작업이 쓸 조건 그대로 테스트가 직접 실행한다.
 */
class RetentionBoundaryIngestTest : AbstractIngestIntegrationTest() {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var data: IngestTestData

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private val http: HttpClient = HttpClient.newHttpClient()
	private val clickHouse = ClickHouseHttpClient(clickHouseUrl())

	/** 한 시간 전 — 마이크로초로 내린 경계. 관측 둘은 그 앞(두 시간 전)과 뒤(지금)다. */
	private val boundary: Instant = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS)
	private val late: Instant = boundary.minus(1, ChronoUnit.HOURS)
	private val fresh: Instant = Instant.now()

	@BeforeEach
	fun reset() {
		data.clear()
		for (table in listOf("telemetry_events", "telemetry_metric_points", "telemetry_ingest_ledger", RetentionFence.TABLE)) {
			clickHouse.execute("TRUNCATE TABLE IF EXISTS $table")
		}
	}

	@AfterEach
	fun cleanUp() {
		data.clear()
	}

	@Test
	@DisplayName("경계 이전의 지연 도착은 분석 테이블에 남지 않고 ledger 의 거부로 세어진다 — push 는 200, 요약의 최초 관측도 넓히지 않는다")
	fun aLateArrivalBeforeTheBoundaryIsNotStored() {
		val seeded = data.seed()
		retentionBoundaries.advance(seeded.tenantId, boundary)

		val response = post(seeded.rawToken, prompts(late, fresh))

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		assertThat(sourceTimes()).containsExactly(fresh.truncatedTo(ChronoUnit.MICROS))
		val ledger = query("SELECT record_count, rejected_count, source_time_min >= {b:DateTime64(9, 'UTC')} FROM telemetry_ingest_ledger FINAL FORMAT TSV")
		assertThat(ledger).isEqualTo("2\t1\t1")
		val firstObserved = jdbc.queryForObject(
			"SELECT first_observed_at FROM telemetry_ops.tenant_ingest_summary WHERE tenant_id = ?",
			OffsetDateTime::class.java,
			seeded.tenantId,
		)!!.toInstant()
		assertThat(firstObserved).isAfterOrEqualTo(boundary)
	}

	@Test
	@DisplayName("모든 관측이 경계 이전이어도 수신 사실은 남는다 — 200, 분석 행 0, ledger 는 받은 것 전부가 거부")
	fun aPushEntirelyBeforeTheBoundaryIsStillReceived() {
		val seeded = data.seed()
		retentionBoundaries.advance(seeded.tenantId, boundary)

		val response = post(seeded.rawToken, prompts(late))

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		assertThat(sourceTimes()).isEmpty()
		assertThat(query("SELECT record_count, rejected_count, source_time_min IS NULL FROM telemetry_ingest_ledger FINAL FORMAT TSV"))
			.isEqualTo("1\t1\t1")
	}

	@Test
	@DisplayName("삭제 뒤의 재전송은 지운 관측을 되살리지 않는다 — 같은 push 를 다시 보내도 분석 행 0")
	fun aRetransmissionAfterDeletionDoesNotResurrect() {
		val seeded = data.seed()
		val body = prompts(late)
		post(seeded.rawToken, body).let { assertThat(it.statusCode()).describedAs(it.body()).isEqualTo(200) }
		assertThat(sourceTimes()).hasSize(1)

		// 보존 작업의 순서(ADR 0024 §4) 중 발효와 DELETE — 조건은 tenant 와 경계뿐이다.
		retentionBoundaries.advance(seeded.tenantId, boundary)
		clickHouse.execute(
			"DELETE FROM telemetry_events WHERE tenant_id = {t:String} AND source_time < {b:DateTime64(9, 'UTC')}",
			params = mapOf("t" to seeded.tenantId.toString(), "b" to boundaryParam()),
		)
		assertThat(sourceTimes()).isEmpty()

		val resent = post(seeded.rawToken, body)

		assertThat(resent.statusCode()).describedAs(resent.body()).isEqualTo(200)
		assertThat(sourceTimes()).isEmpty()
	}

	@Test
	@DisplayName("경계 조회 실패는 503 + Retry-After — 분석 행도 수신 기록도 남기지 않는다")
	fun anUnreadableBoundaryIsRetryable() {
		val seeded = data.seed()
		doThrow(TelemetryOpsUnavailableException("simulated telemetry_ops outage")).`when`(retentionBoundaries).read(anything())

		val response = post(seeded.rawToken, prompts(fresh))

		assertThat(response.statusCode()).isEqualTo(503)
		assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
		assertThat(sourceTimes()).isEmpty()
		assertThat(query("SELECT count() FROM telemetry_ingest_ledger FORMAT TSV")).isEqualTo("0")
	}

	@Test
	@DisplayName("RDS 경계를 읽은 뒤 fence 가 먼저 움직였어도 서버가 fence 이전 행을 쓰지 않는다")
	fun theServerFenceCatchesAStaleRead() {
		val seeded = data.seed()
		// RDS 에는 아직 경계가 없다 — ingest 가 읽는 값은 "경계 없음"이다. fence 만 앞서 있다.
		RetentionFence(clickHouse).write(seeded.tenantId.toString(), RetentionFence.Value(boundary, policyEpoch = 1))

		val response = post(seeded.rawToken, prompts(late, fresh))

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		assertThat(sourceTimes()).containsExactly(fresh.truncatedTo(ChronoUnit.MICROS))
	}

	// ------------------------------------------------------------------ 도구

	private fun post(token: String, body: ByteArray): HttpResponse<String> {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/logs"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer $token")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build()
		return http.send(request, HttpResponse.BodyHandlers.ofString())
	}

	/** claude_code user_prompt 로그 — 시각마다 하나. 세션이 달라 관측도 다르다. */
	private fun prompts(vararg times: Instant): ByteArray {
		val records = times.mapIndexed { index, time ->
			val nanos = time.epochSecond * 1_000_000_000L + time.nano
			"""
			{"timeUnixNano":"$nanos","body":{"stringValue":"claude_code.user_prompt"},
			 "attributes":[{"key":"session.id","value":{"stringValue":"retention-$index"}},
			               {"key":"prompt_length","value":{"intValue":"42"}}]}
			""".trimIndent()
		}
		return """
			{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"claude-code"}}]},
			 "scopeLogs":[{"logRecords":[${records.joinToString(",")}]}]}]}
		""".trimIndent().toByteArray()
	}

	/** 분석 행의 `source_time`(마이크로초로 내림 — 비교를 위해) 오름차순. */
	private fun sourceTimes(): List<Instant> =
		query("SELECT toString(toDateTime64(source_time, 6, 'UTC')) FROM telemetry_events FINAL ORDER BY source_time FORMAT TSV")
			.lines().filter { it.isNotBlank() }.map { Instant.parse(it.replace(' ', 'T') + "Z") }

	private fun query(sql: String): String = clickHouse.execute(sql, params = mapOf("b" to boundaryParam())).trim()

	private fun boundaryParam(): String = boundary.toString().removeSuffix("Z").replace('T', ' ')

	/** Mockito 의 `any()` 는 null 을 돌려줘 Kotlin 의 non-null 매개변수 검사에 걸린다 — matcher 만 등록한다. */
	@Suppress("UNCHECKED_CAST")
	private fun <T> anything(): T {
		ArgumentMatchers.any<T>()
		return null as T
	}
}
