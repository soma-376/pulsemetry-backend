package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.installation.HeartbeatConfig
import com.team376.pulsemetry.enrollment.installation.HeartbeatProperties
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration

/** 설치 보고는 기본으로 꺼져 있고, 켰을 때 운영 수치가 비면 뜨지 않는다 (ADR 0040). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class, EnrollmentTestData::class)
class HeartbeatDisabledApiTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var data: EnrollmentTestData

    @Test fun `꺼져 있으면 경로가 없고 아무것도 기록하지 않는다`() {
        data.reset()
        val tenant = data.tenant().id
        val member = data.member(tenant).id
        val installation = data.installation(tenant, member, data.invitation(tenant, member, InvitationCode.generate()).id).id
        val token = data.credential(installation)
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/installations/$installation/heartbeat"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(mapper.readTree(response.body()).path("error").asString()).isEqualTo("not_found")
        assertThat(data.singleColumn("SELECT last_seen_at::text FROM enrollment.installations WHERE id='$installation'")).isNull()
        assertThat(data.countRows("installation_heartbeats")).isEqualTo(0)
    }

    @Test fun `켰는데 주기나 재시도 안내나 보존 기간이 비었거나 범위를 벗어나면 조립하지 못한다`() {
        fun properties(interval: String? = "PT5M", retry: String? = "PT5S", retention: String? = "P30D") = HeartbeatProperties().apply {
            enabled = true
            reportInterval = interval?.let(Duration::parse)
            retryAfter = retry?.let(Duration::parse)
            historyRetention = retention?.let(Duration::parse)
        }
        fun assemble(properties: HeartbeatProperties) = HeartbeatConfig().heartbeatService(properties, mock(), mock(), mock(), mock(), mock(), mock(), Clock.systemUTC())

        assertThat(assemble(properties()).retryAfterSeconds).isEqualTo(5)
        assertThat(assemble(properties(interval = "PT1M"))).isNotNull()
        assertThat(assemble(properties(interval = "PT1H"))).isNotNull()
        for (broken in listOf(properties(interval = null), properties(retry = null), properties(retention = null),
            // 계약의 주기 범위는 60초~3600초다.
            properties(interval = "PT59S"), properties(interval = "PT1H1S"), properties(interval = "PT90.5S"),
            properties(retry = "PT0S"), properties(retry = "PT0.5S"), properties(retention = "PT23H"))) {
            assertThatThrownBy { assemble(broken) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("pulsemetry.heartbeat.")
        }
    }
}
