package com.team376.pulsemetry.persistence.telemetry

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 마이그레이터의 **문장 분해**를 고정한다.
 *
 * ADR 0015 가 모든 변경을 `V2`·`V3` 새 파일로 강제하고 그 파일 양식이 긴 `--` 헤더다. 헤더 문장에
 * 세미콜론이 들어가거나 마지막 문장 뒤에 꼬리 주석이 오면, 주석만 담긴 조각이 ClickHouse 로 나가
 * `Empty query`(400) 가 되고 매 기동마다 스키마 적용이 죽는다. 그래서 주석 조각이 **전송되지 않는
 * 것**을 스텁 서버로 확인한다.
 *
 * ClickHouse 없이 돈다. 판정 대상이 문장 분해이지 DDL 의 효력이 아니다 — 효력은
 * [EnrichedEventsSinkTest] 가 실 컨테이너에서 본다.
 */
class ClickHouseSchemaMigratorTest {

	private lateinit var server: HttpServer
	private val received = mutableListOf<String>()

	@BeforeEach
	fun startStub() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val query = exchange.requestURI.rawQuery.orEmpty()
				.split('&')
				.firstOrNull { it.startsWith("query=") }
				?.removePrefix("query=")
				?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
				.orEmpty()
			received += query
			exchange.sendResponseHeaders(200, 0)
			exchange.responseBody.close()
		}
		server.start()
	}

	@AfterEach
	fun stopStub() {
		server.stop(0)
	}

	private fun migrator(): ClickHouseSchemaMigrator =
		ClickHouseSchemaMigrator(ClickHouseHttpClient("http://127.0.0.1:${server.address.port}"))

	@Test
	@DisplayName("꼬리 주석·주석 안 세미콜론은 문장이 되지 않는다 — 주석 조각을 보내지 않는다")
	fun commentFragmentsAreNotSentAsStatements() {
		val sql = """
			-- 헤더. 이 문장에는 세미콜론이 있다; 그래도 문장이 아니다.
			--   들여쓴 주석도 마찬가지다; 여기도.
			CREATE TABLE IF NOT EXISTS t (x String) ENGINE = MergeTree ORDER BY x;
			  -- 문장 사이의 주석
			ALTER TABLE t ADD COLUMN IF NOT EXISTS y String;
			-- 꼬리 주석. 마지막 세미콜론 뒤에 온다; 세미콜론도 들어 있다.
		""".trimIndent()

		val statements = migrator().statementsOf(sql)

		assertThat(statements).containsExactly(
			"CREATE TABLE IF NOT EXISTS t (x String) ENGINE = MergeTree ORDER BY x",
			"ALTER TABLE t ADD COLUMN IF NOT EXISTS y String",
		)
		assertThat(statements).noneMatch { it.contains("--") }
	}

	@Test
	@DisplayName("여러 줄 문장은 주석 줄만 빠지고 나머지 줄은 그대로 이어진다")
	fun multiLineStatementsSurviveCommentStripping() {
		val sql = """
			CREATE TABLE IF NOT EXISTS t
			(
			    x String,  -- 이런 줄 끝 주석은 벗기지 않는다. ClickHouse 가 스스로 무시한다
			    y String
			)
			ENGINE = MergeTree
			ORDER BY x;
		""".trimIndent()

		val statements = migrator().statementsOf(sql)

		assertThat(statements).hasSize(1)
		assertThat(statements.single()).startsWith("CREATE TABLE IF NOT EXISTS t").endsWith("ORDER BY x")
	}

	@Test
	@DisplayName("V1 은 문장 하나다 — 헤더 열여덟 줄이 전부 벗겨져 CREATE TABLE 만 나간다")
	fun v1SendsExactlyOneStatement() {
		migrator().apply()

		assertThat(received).hasSize(1)
		assertThat(received.single())
			.startsWith("CREATE TABLE IF NOT EXISTS enriched_events")
			.doesNotContain("--")
			.endsWith("ORDER BY event_id")
	}
}
