package com.team376.pulsemetry.enrollment.inquiry

import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.auth.AuthTestClock
import com.team376.pulsemetry.persistence.enrollment.inquiry.InquiryLimits
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import com.team376.pulsemetry.persistence.enrollment.mail.InquiryNotifier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

private const val ORIGIN = "https://app.example.test"

/** 실제 HTTP와 PostgreSQL로 검증한다. 한도는 60초 5회, 재전송 판정은 10분이다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.inquiries.enabled=true", "pulsemetry.inquiries.duplicate-window=PT10M",
        "pulsemetry.inquiries.rate-limit.requests=5", "pulsemetry.inquiries.rate-limit.window=PT1M",
        "pulsemetry.inquiries.allowed-origins=$ORIGIN"])
@Import(PostgresContainerConfig::class, AuthClockConfig::class)
class InquiryApiTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var clock: AuthTestClock
    private val http = HttpClient.newHttpClient()
    private val start = Instant.parse("2026-09-09T12:00:00Z")

    @BeforeEach fun setup() {
        jdbc.sql("TRUNCATE enrollment.inquiries, enrollment.inquiry_attempts").update()
        clock.now = start
    }

    private fun send(body: String, contentType: String = "application/json", origin: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port/v1/inquiries")).header("Content-Type", contentType)
        origin?.let { builder.header("Origin", it) }
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun inquire(company: String?, email: String?) = send(mapper.writeValueAsString(mapOf("company" to company, "email" to email)))
    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun count(table: String) = jdbc.sql("SELECT count(*) FROM enrollment.$table").query(Int::class.java).single()
    private fun forgetAttempts() { jdbc.sql("TRUNCATE enrollment.inquiry_attempts").update() }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun assertError(response: HttpResponse<String>, status: Int, code: String) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status)
        val body = json(response)
        assertThat(body.propertyNames().toList()).containsExactlyInAnyOrder("error", "message")
        assertThat(body.path("error").asString()).isEqualTo(code)
        assertThat(body.path("message").asString()).isNotBlank()
    }
    /** 컨트롤러 앞의 필터가 쓴 오류는 문자셋을 명시한다(명세 §2.2). */
    private fun assertFilterCharset(response: HttpResponse<String>) {
        val type = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
        assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON)).describedAs(type.toString()).isTrue()
        assertThat(type.charset).describedAs(type.toString()).isEqualTo(StandardCharsets.UTF_8)
    }

    @Test fun `문의를 저장하고 접수 번호와 상태와 시각을 돌려준다`() {
        val response = inquire("  코드웍스 ", " Lead@Example.test ")
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201)
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
        val body = json(response)
        assertThat(body.propertyNames().toList()).containsExactlyInAnyOrder("inquiryId", "status", "receivedAt")
        assertThat(body.path("status").asString()).isEqualTo("received")
        assertThat(Instant.parse(body.path("receivedAt").asString())).isEqualTo(start)
        val row = jdbc.sql("SELECT id::text,company,email,status,source_ip_hash,received_at FROM enrollment.inquiries")
            .query { r, _ -> listOf(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getTimestamp(6).toInstant()) }.single()
        // 회사명은 앞뒤 공백만 떼고, 이메일은 소문자로 정규화해 저장한다. 출처 주소는 해시만 남긴다.
        assertThat(row).isEqualTo(listOf(body.path("inquiryId").asString(), "코드웍스", "lead@example.test", "received", sha256("inquiry-ip:127.0.0.1"), start))
    }

    @Test fun `문의는 조직도 구성원도 초대도 만들지 않는다`() {
        val before = listOf("tenants", "members", "invitations", "teams").map(::count)
        assertThat(inquire("코드웍스", "lead@example.test").statusCode()).isEqualTo(201)
        assertThat(listOf("tenants", "members", "invitations", "teams").map(::count)).isEqualTo(before)
        assertThat(count("inquiries")).isEqualTo(1)
    }

    @Test fun `같은 회사와 이메일의 재전송은 판정 시간 안에서 같은 접수를 돌려주고 다시 저장하지 않는다`() {
        val first = json(inquire("Codeworks Inc", "lead@example.test"))
        clock.now = start.plusSeconds(599)
        // 대소문자·전각·공백 차이는 같은 입력이다.
        val again = inquire("  ＣＯＤＥＷＯＲＫＳ   inc ", "LEAD@example.test")
        assertThat(again.statusCode()).isEqualTo(201)
        assertThat(json(again)).isEqualTo(first)
        assertThat(count("inquiries")).isEqualTo(1)
        // 이메일이나 회사가 다르면 다른 문의다.
        clock.now = start.plusSeconds(700)
        assertThat(json(inquire("Codeworks Inc", "other@example.test")).path("inquiryId")).isNotEqualTo(first.path("inquiryId"))
        assertThat(json(inquire("Codeworks Labs", "lead@example.test")).path("inquiryId")).isNotEqualTo(first.path("inquiryId"))
        assertThat(count("inquiries")).isEqualTo(3)
    }

    @Test fun `재전송 판정 시간이 지나면 새 문의로 저장한다`() {
        val first = json(inquire("코드웍스", "lead@example.test"))
        clock.now = start.plus(Duration.ofMinutes(10))
        val later = json(inquire("코드웍스", "lead@example.test"))
        assertThat(later.path("inquiryId")).isNotEqualTo(first.path("inquiryId"))
        assertThat(Instant.parse(later.path("receivedAt").asString())).isEqualTo(start.plus(Duration.ofMinutes(10)))
        assertThat(count("inquiries")).isEqualTo(2)
        // 가장 최근 접수를 기준으로 다시 판정한다.
        clock.now = start.plus(Duration.ofMinutes(15))
        assertThat(json(inquire("코드웍스", "lead@example.test"))).isEqualTo(later)
    }

    @Test fun `같은 문의가 동시에 와도 한 번만 저장한다`() {
        val pool = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        try {
            val results = (1..4).map { pool.submit(Callable { gate.await(); inquire("코드웍스", "lead@example.test") }) }
            gate.countDown()
            val responses = results.map { it.get() }
            assertThat(responses.map { it.statusCode() }).containsOnly(201)
            assertThat(responses.map { json(it).path("inquiryId").asString() }.toSet()).hasSize(1)
            assertThat(count("inquiries")).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `잘못된 입력은 400이고 저장하지 않는다`() {
        val invalid = listOf(
            "" to "lead@example.test", "   " to "lead@example.test", null to "lead@example.test", "가".repeat(101) to "lead@example.test",
            "코드\n웍스" to "lead@example.test", "코드웍스" to null, "코드웍스" to "", "코드웍스" to "lead", "코드웍스" to "lead@example",
            "코드웍스" to "le ad@example.test", "코드웍스" to "a".repeat(310) + "@example.test",
        )
        for ((company, email) in invalid) { forgetAttempts(); assertError(inquire(company, email), 400, "invalid_request") }
        // 계약에 없는 필드, 깨진 JSON, JSON 이 아닌 본문도 같은 오류다.
        for (body in listOf("""{"company":"코드웍스","email":"lead@example.test","tenantId":"x"}""", "{", "[]")) { forgetAttempts(); assertError(send(body), 400, "invalid_request") }
        forgetAttempts()
        assertError(send("company=코드웍스", "text/plain"), 400, "invalid_request")
        assertThat(count("inquiries")).isEqualTo(0)
        // 경계값은 받는다.
        forgetAttempts()
        assertThat(inquire("가".repeat(100), "a".repeat(307) + "@example.test").statusCode()).isEqualTo(201)
    }

    @Test fun `출처의 요청이 한도를 넘으면 429와 Retry-After를 주고 창이 지나면 다시 받는다`() {
        // 검증에 실패한 요청과 재전송도 요청으로 센다.
        assertThat(inquire("코드웍스", "lead@example.test").statusCode()).isEqualTo(201)
        assertThat(inquire("코드웍스", "lead@example.test").statusCode()).isEqualTo(201)
        assertThat(inquire("", "lead@example.test").statusCode()).isEqualTo(400)
        assertThat(send("{").statusCode()).isEqualTo(400)
        assertThat(inquire("다른 회사", "other@example.test").statusCode()).isEqualTo(201)
        val refused = inquire("세 번째 회사", "third@example.test")
        assertError(refused, 429, "rate_limited")
        assertFilterCharset(refused)
        assertThat(refused.headers().firstValue("Retry-After")).hasValue("60")
        clock.now = start.plusSeconds(30)
        assertThat(inquire("세 번째 회사", "third@example.test").headers().firstValue("Retry-After")).hasValue("30")
        assertThat(count("inquiries")).isEqualTo(2)
        // 제한 상태에는 주소 원문이 없다.
        assertThat(jdbc.sql("SELECT subject_hash FROM enrollment.inquiry_attempts").query(String::class.java).list()).isEqualTo(listOf(sha256("inquiry-ip:127.0.0.1")))
        clock.now = start.plusSeconds(60)
        assertThat(inquire("세 번째 회사", "third@example.test").statusCode()).isEqualTo(201)
        assertThat(count("inquiries")).isEqualTo(3)
    }

    @Test fun `허용한 출처만 CORS를 통과하고 preflight는 요청으로 세지 않는다`() {
        fun preflight(origin: String) = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/inquiries")).header("Origin", origin)
            .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        repeat(6) {
            val allowed = preflight(ORIGIN)
            assertThat(allowed.statusCode()).isEqualTo(200)
            assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN)
            assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("POST")
        }
        assertThat(count("inquiry_attempts")).isEqualTo(0)
        val denied = preflight("https://evil.example.test")
        assertThat(denied.statusCode()).isEqualTo(403)
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty()
        // 실제 요청의 응답은 브라우저가 Retry-After 를 읽을 수 있게 노출한다.
        val response = send(mapper.writeValueAsString(mapOf("company" to "코드웍스", "email" to "lead@example.test")), origin = ORIGIN)
        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN)
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElse("")).contains("Retry-After")
        assertThat(send("{}", origin = "https://evil.example.test").statusCode()).isEqualTo(403)
        assertThat(count("inquiries")).isEqualTo(1)
    }

    @Test fun `저장소 장애는 503과 Retry-After이고 예외 원문을 싣지 않는다`() {
        fun rename(from: String, to: String) { jdbc.sql("ALTER TABLE enrollment.$from RENAME TO $to").update() }
        rename("inquiries", "inquiries_unavailable")
        try {
            val response = inquire("코드웍스", "lead@example.test")
            assertError(response, 503, "inquiry_unavailable")
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
            assertThat(response.body()).doesNotContain("inquiries_unavailable", "relation", "SQL")
        } finally { rename("inquiries_unavailable", "inquiries") }
        rename("inquiry_attempts", "inquiry_attempts_unavailable")
        try {
            val response = inquire("코드웍스", "lead@example.test")
            assertError(response, 503, "inquiry_unavailable")
            assertFilterCharset(response)
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
        } finally { rename("inquiry_attempts_unavailable", "inquiry_attempts") }
        assertThat(count("inquiries")).isEqualTo(0)
    }

    @Test fun `켰을 때 운영 수치와 허용 출처가 비어 있으면 기동하지 않는다`() {
        // 메일이 꺼진 배포 — 통지 빈이 없다.
        @Suppress("UNCHECKED_CAST") val noNotifier = mock(ObjectProvider::class.java) as ObjectProvider<InquiryNotifier>
        fun start(change: InquiryProperties.() -> Unit) = InquiryConfig().inquiryStore(mock(JdbcClient::class.java), mock(PlatformTransactionManager::class.java), Clock.systemUTC(),
            InquiryProperties().apply {
                enabled = true; duplicateWindow = Duration.ofMinutes(10); allowedOrigins = listOf(ORIGIN)
                rateLimit.requests = 5; rateLimit.window = Duration.ofMinutes(1)
            }.apply(change), noNotifier)
        start {}
        assertThatThrownBy { start { duplicateWindow = null } }.hasMessageContaining("pulsemetry.inquiries.duplicate-window")
        assertThatThrownBy { start { rateLimit.requests = null } }.hasMessageContaining("pulsemetry.inquiries.rate-limit.requests")
        assertThatThrownBy { start { rateLimit.window = null } }.hasMessageContaining("pulsemetry.inquiries.rate-limit.window")
        assertThatThrownBy { start { allowedOrigins = emptyList() } }.hasMessageContaining("pulsemetry.inquiries.allowed-origins")
        assertThatThrownBy { start { allowedOrigins = listOf(" ") } }.hasMessageContaining("pulsemetry.inquiries.allowed-origins")
        // 0 이하의 값으로는 제한이 되지 않는다.
        assertThatThrownBy { InquiryLimits(Duration.ZERO, 5, Duration.ofMinutes(1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InquiryLimits(Duration.ofMinutes(10), 0, Duration.ofMinutes(1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InquiryLimits(Duration.ofMinutes(10), 5, Duration.ofSeconds(-1)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

/** 기본 설정에서는 문의 접수가 꺼져 있다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class)
class InquiryDisabledApiTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mapper: ObjectMapper

    @Test fun `꺼져 있으면 경로가 없고 아무것도 저장하지 않는다`() {
        fun rows() = listOf("inquiries", "inquiry_attempts").map { jdbc.sql("SELECT count(*) FROM enrollment.$it").query(Int::class.java).single() }
        val before = rows()
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/inquiries")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"company":"코드웍스","email":"lead@example.test"}""")).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(mapper.readTree(response.body()).path("error").asString()).isEqualTo("not_found")
        assertThat(rows()).isEqualTo(before)
    }
}
