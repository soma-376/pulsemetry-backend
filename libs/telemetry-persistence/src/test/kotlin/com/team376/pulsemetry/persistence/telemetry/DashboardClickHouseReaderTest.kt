package com.team376.pulsemetry.persistence.telemetry

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.time.Duration
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

@Testcontainers
class DashboardClickHouseReaderTest {
    private fun endpoint() = URI("http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}")
    @Test fun `파라미터는 SQL 문법으로 해석되지 않고 SELECT는 쓰기 설정에서 실행된다`() {
        val reader = DashboardClickHouseReader(endpoint())
        val injection = "tenant' OR 1=1 --"
        val result = reader.query("SELECT {tenant:String} AS tenant", mapOf("tenant" to injection))
        assertThat(result).contains(injection)
        assertThatThrownBy { reader.query("DROP TABLE enriched_events", emptyMap()) }.isInstanceOf(IllegalArgumentException::class.java)
        val settings = reader.query("SELECT getSetting('readonly') AS mode", emptyMap())
        assertThat(settings).contains("2")
    }
    @Test fun `FINAL은 중복을 제거하고 다른 tenant는 조회되지 않는다`() {
        val writer = ClickHouseHttpClient(endpoint().toString())
        writer.execute("CREATE TABLE IF NOT EXISTS dashboard_reader_test (id String,tenant String) ENGINE=ReplacingMergeTree ORDER BY (tenant,id)")
        writer.execute("INSERT INTO dashboard_reader_test VALUES ('one','a'),('one','a'),('two','b')")
        val rows = DashboardClickHouseReader(endpoint()).query("SELECT id FROM dashboard_reader_test FINAL WHERE tenant={tenant:String}", mapOf("tenant" to "a"))
        assertThat(rows).contains("one").doesNotContain("two")
        assertThat(rows).contains("\"rows\": 1")
    }
    @Test fun `응답 헤더만 도착해도 본문 수신 제한 시간을 넘기지 않는다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 1000)
            try { Thread.sleep(500); exchange.responseBody.write(ByteArray(1000)) } catch (_: Exception) {} finally { exchange.close() }
        }
        server.start()
        try {
            val reader = DashboardClickHouseReader(URI("http://127.0.0.1:${server.address.port}"), timeout = Duration.ofMillis(100))
            assertThatThrownBy { reader.query("SELECT 1", emptyMap()) }
                .isInstanceOfSatisfying(DashboardReadException::class.java) { assertThat(it.status).isEqualTo(504) }
        } finally { server.stop(0) }
    }
    @Test fun `요청 예산이 소진되면 추가 조회를 전송하지 않는다`() {
        val reader = DashboardClickHouseReader(URI("http://127.0.0.1:1"))
        assertThatThrownBy { reader.query("SELECT 1", emptyMap(), Duration.ZERO) }
            .isInstanceOfSatisfying(DashboardReadException::class.java) { assertThat(it.status).isEqualTo(504) }
    }
    companion object {
        @Container @JvmStatic val clickhouse = GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200))
    }
}
