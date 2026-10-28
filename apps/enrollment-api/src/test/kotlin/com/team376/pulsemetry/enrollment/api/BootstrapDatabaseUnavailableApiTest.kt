package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource

/** 실제 Spring 트랜잭션 프록시에서도 형식 검사가 DB 연결 획득보다 먼저인지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class)
class BootstrapDatabaseUnavailableApiTest {
    @LocalServerPort private var port = 0
    @MockitoSpyBean private lateinit var dataSource: DataSource
    private val http = HttpClient.newHttpClient()

    @AfterEach fun restore() { reset(dataSource) }

    @Test fun `DB 연결 획득이 실패해도 누락과 형식 오류는 연결 없이 실패 스크립트다`() {
        doThrow(SQLTransientConnectionException("test database unavailable")).`when`(dataSource).connection
        clearInvocations(dataSource)
        for (path in listOf("/unix", "/windows?code=bad", "/unix?code=bad")) {
            val response = get(path)
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.body()).contains("exit 1").doesNotContain("/bin/pulsemetry_")
        }
        verify(dataSource, never()).connection
        for (path in listOf("unix", "windows")) {
            val response = get("/$path?code=ABCD-EFGH-JKMN")
            assertThat(response.statusCode()).isBetween(500, 599)
            assertThat(response.body()).doesNotContain("ABCD-EFGH-JKMN", "/bin/pulsemetry_", "초대 코드를 찾을 수 없습니다")
        }
        verify(dataSource, atLeastOnce()).connection
    }

    private fun get(path: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())
}
