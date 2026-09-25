package com.team376.pulsemetry.dashboard.source

import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import com.team376.pulsemetry.dashboard.store.StoreLimitExceededException
import com.team376.pulsemetry.dashboard.store.StoreQueryRejectedException
import com.team376.pulsemetry.dashboard.store.StoreUnavailableException
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * 원천 읽기 클라이언트를 실제 ClickHouse 에 붙여 본다 (ADR 0022 §4). 쓰기 경로가 없어야 하고, 상한을 넘으면 잘라 내지 않고
 * 실패해야 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseSourceReaderTest {

	private val mapper = JsonMapper.builder().build()
	private val table = "reader_probe_${System.nanoTime()}"

	private fun reader(
		url: String = DashboardTestStores.clickHouseUrl(),
		password: String = "",
		queryTimeout: Duration = Duration.ofSeconds(10),
		maxResultRows: Long = 1_000,
		maxResultBytes: Long = 1_000_000,
	) = ClickHouseSourceReader(
		ClickHouseConnection(url, "default", "default", password, queryTimeout, mapper),
		queryTimeout,
		maxResultRows,
		maxResultBytes,
	)

	/** 시드는 앱 경로가 아니라 직접 보낸다. */
	private fun admin(sql: String) {
		val response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("${DashboardTestStores.clickHouseUrl()}/?query=${URLEncoder.encode(sql, StandardCharsets.UTF_8)}"))
				.POST(HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		check(response.statusCode() == 200) { response.body() }
	}

	private fun count(): Long = reader().query("SELECT count() AS c FROM $table") { it.path("c").asString().toLong() }.single()

	@BeforeAll
	fun createTable() {
		admin(
			"CREATE TABLE $table (id String, n UInt64, d Decimal(38, 12), ts DateTime64(9, 'UTC')) ENGINE = MergeTree ORDER BY id",
		)
		admin(
			"INSERT INTO $table VALUES ('a', 18446744073709551615, 0.3, '2026-09-06 15:00:00.000000001'), " +
				"('b', 9007199254740993, 12345678901234567890.123456789012, '2026-09-13 15:00:00')",
		)
	}

	@Test
	@DisplayName("파라미터를 서버에서 바인딩한다 — 64비트 정수·Decimal 은 문자열로, 시각은 ISO 로 받는다")
	fun bindsParametersAndKeepsPrecision() {
		val rows = reader().query(
			"SELECT id, n, d, ts FROM $table WHERE ts >= {from:DateTime64(9, 'UTC')} ORDER BY id",
			mapOf("from" to ClickHouseParam.instant(Instant.parse("2026-09-06T15:00:00.000000001Z"))),
		) { row -> listOf(row.path("id").asString(), row.path("n").asString(), row.path("d").asString(), row.path("ts").asString()) }

		assertThat(rows).hasSize(2)
		assertThat(rows[0][1]).isEqualTo("18446744073709551615")
		assertThat(BigDecimal(rows[0][2])).isEqualByComparingTo("0.3")
		assertThat(Instant.parse(rows[0][3])).isEqualTo(Instant.parse("2026-09-06T15:00:00.000000001Z"))
		assertThat(rows[1][1]).isEqualTo("9007199254740993")
		assertThat(BigDecimal(rows[1][2])).isEqualByComparingTo("12345678901234567890.123456789012")
	}

	@Test
	@DisplayName("값은 SQL 에 이어 붙지 않는다 — 따옴표가 섞인 값은 그냥 일치하지 않는 문자열이다")
	fun parameterValuesAreNotSql() {
		val rows = reader().query(
			"SELECT id FROM $table WHERE id = {id:String}",
			mapOf("id" to ClickHouseParam.string("a' OR 1 = 1 --")),
		) { it.path("id").asString() }

		assertThat(rows).isEmpty()
	}

	@Test
	@DisplayName("쓰기 문장은 조회 거부(500 쪽)이고 테이블은 그대로다 — 기본 사용자는 쓰기 권한이 있지만 요청의 readonly=2 가 막는다")
	fun writesAreRefused() {
		val before = count()

		assertThatThrownBy { reader().query("INSERT INTO $table VALUES ('c', 1, 1, now64(9))") { it } }
			.isInstanceOf(StoreQueryRejectedException::class.java)
		assertThatThrownBy { reader().query("CREATE TABLE ${table}_x (id String) ENGINE = Memory") { it } }
			.isInstanceOf(StoreQueryRejectedException::class.java)
		assertThat(count()).isEqualTo(before)
	}

	@Test
	@DisplayName("행 상한을 넘으면 잘라 내지 않고 상한 초과로 실패한다")
	fun rowLimitThrows() {
		assertThatThrownBy { reader(maxResultRows = 5).query("SELECT number FROM numbers(10)") { it } }
			.isInstanceOf(StoreLimitExceededException::class.java)
		assertThat(reader(maxResultRows = 10).query("SELECT number FROM numbers(10)") { it }).hasSize(10)
	}

	@Test
	@DisplayName("바이트 상한을 넘으면 상한 초과로 실패한다")
	fun byteLimitThrows() {
		// 24.8 은 테이블 없이 상수로 접히는 한 행 결과에는 바이트 상한을 걸지 않는다(실측) — 행을 만드는 조회로 본다.
		assertThatThrownBy { reader(maxResultBytes = 100).query("SELECT toString(number) || repeat('x', 1000) AS s FROM numbers(3)") { it } }
			.isInstanceOf(StoreLimitExceededException::class.java)
	}

	@Test
	@DisplayName("시간 상한을 넘으면 일시 장애로 실패한다")
	fun timeLimitThrows() {
		assertThatThrownBy {
			reader(queryTimeout = Duration.ofSeconds(1))
				.query("SELECT sleepEachRow(0.3) FROM numbers(20) SETTINGS max_block_size = 1") { it }
		}.isInstanceOf(StoreUnavailableException::class.java)
	}

	@Test
	@DisplayName("없는 테이블·문법 오류는 조회 거부(이 앱의 결함)다")
	fun badQueriesAreRejected() {
		assertThatThrownBy { reader().query("SELECT * FROM no_such_table_${System.nanoTime()}") { it } }
			.isInstanceOf(StoreQueryRejectedException::class.java)
		assertThatThrownBy { reader().query("SELEC 1") { it } }
			.isInstanceOf(StoreQueryRejectedException::class.java)
	}

	@Test
	@DisplayName("인증 실패와 연결 실패는 일시 장애다")
	fun authenticationAndConnectionFailuresAreUnavailable() {
		assertThatThrownBy { reader(password = "wrong").query("SELECT 1 AS x") { it } }
			.isInstanceOf(StoreUnavailableException::class.java)
		assertThatThrownBy { reader(url = "http://127.0.0.1:1").query("SELECT 1 AS x") { it } }
			.isInstanceOf(StoreUnavailableException::class.java)
	}

	@Test
	@DisplayName("빈 결과는 빈 목록이다")
	fun emptyResult() {
		assertThat(reader().query("SELECT id FROM $table WHERE id = 'none'") { it }).isEmpty()
	}
}
