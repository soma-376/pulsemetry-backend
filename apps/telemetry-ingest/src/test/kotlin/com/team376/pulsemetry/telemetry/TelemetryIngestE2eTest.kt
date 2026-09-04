package com.team376.pulsemetry.telemetry

import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestTestData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 다섯 모듈이 한 요청에서 도는지 확인한다 — 이 티켓의 수용 기준이다.
 *
 * `POST /v1/traces` 대신 `/v1/logs` 를 쓰는 것은 claude_code 로그가 가장 짧은 유효 입력이기
 * 때문이다. 경로 셋은 [OtlpIngestApiTest] 가 따로 덮는다.
 *
 * **입력 OTLP 를 여기 직접 적는다.** 공유 fixture 는 정규화된 기대출력(`*.normalized.jsonl`)
 * 뿐이고 짝인 OTLP 입력은 어댑터의 `src/test` 에만 있어 공유되지 않는다. 정규화 자체는 골든
 * 테스트가 검증하므로 이 테스트의 몫은 **배선**이다 — 행이 하나도 안 나오면 그 자리에서 깨진다.
 */
class TelemetryIngestE2eTest : AbstractIngestIntegrationTest() {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var data: IngestTestData

	@Value("\${pulsemetry.telemetry.archive.dir}")
	private lateinit var archiveDir: String

	private val http: HttpClient = HttpClient.newHttpClient()

	@BeforeEach
	fun reset() {
		data.clear()
		truncateEnrichedEvents()
		// 파일 아카이브는 append 전용이라 실행마다 쌓인다. 이전 실행의 내용에 좌우되지 않게 비운다.
		for (signal in listOf("logs", "traces")) {
			Files.deleteIfExists(Path.of(archiveDir, "claude_code", "$signal.jsonl"))
		}
	}

	@AfterEach
	fun cleanUp() {
		data.clear()
	}

	@Test
	@DisplayName("enroll 토큰으로 보낸 OTLP 가 ClickHouse 행이 된다 — 신원은 토큰에서 온다")
	fun anAuthenticatedPushBecomesAnEnrichedEventRow() {
		val seeded = data.seed()

		val response = post("/v1/logs", seeded.rawToken, oneUserPrompt())

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.body()).isEqualTo("""{"partialSuccess":{}}""")

		val row = queryRow()
		// 클라이언트가 보낸 자기신고 값이 아니라 토큰에서 파생된 신원이어야 한다.
		assertThat(row[0]).isEqualTo(seeded.tenantId.toString())
		assertThat(row[1]).isEqualTo(seeded.installationId.toString())
		// 보강이 as-of 조인으로 팀을 찾았다는 증거다.
		assertThat(row[2]).contains(seeded.teamId.toString())
	}

	@Test
	@DisplayName("/v1/traces 로 보낸 스팬도 행이 된다 — 티켓이 적은 경로 그대로")
	fun anAuthenticatedTracePushBecomesAnEnrichedEventRow() {
		val seeded = data.seed()

		val response = post("/v1/traces", seeded.rawToken, oneLlmRequestSpan())

		assertThat(response.statusCode()).isEqualTo(200)
		val row = queryRow()
		assertThat(row[0]).isEqualTo(seeded.tenantId.toString())
		assertThat(row[1]).isEqualTo(seeded.installationId.toString())
		assertThat(row[2]).contains(seeded.teamId.toString())
	}

	@Test
	@DisplayName("아카이브에도 검증된 신원이 들어간다 — 재처리가 같은 멱등 키를 만든다")
	fun theArchivedRawCarriesTheVerifiedIdentity() {
		val seeded = data.seed()

		post("/v1/logs", seeded.rawToken, oneUserPrompt())

		val archived = Files.readString(Path.of(archiveDir, "claude_code", "logs.jsonl"))
		assertThat(archived).contains(seeded.tenantId.toString())
		assertThat(archived).contains(seeded.installationId.toString())
		assertThat(archived).doesNotContain(BOGUS_TENANT)
	}

	@Test
	@DisplayName("같은 요청을 두 번 보내도 행이 하나다 — record_id 가 멱등 키다")
	fun theSamePushTwiceCollapsesToOneRow() {
		val seeded = data.seed()
		val body = oneUserPrompt()

		post("/v1/logs", seeded.rawToken, body)
		post("/v1/logs", seeded.rawToken, body)

		assertThat(clickHouse("SELECT count() FROM enriched_events FINAL").trim()).isEqualTo("1")
	}

	@Test
	@DisplayName("토큰이 없으면 401 단일 메시지다 — 사유를 알려 주지 않는다")
	fun aMissingTokenIsTheSingleUnauthorizedBody() {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/logs"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofByteArray(oneUserPrompt()))
			.build()

		val response = http.send(request, HttpResponse.BodyHandlers.ofString())

		assertThat(response.statusCode()).isEqualTo(401)
		assertThat(response.body())
			.isEqualTo("""{"error":"unauthorized","message":"Invalid or expired credential"}""")
		assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty
	}

	@Test
	@DisplayName("OTLP 경로 밖은 기본 닫힘이다 — 새 경로가 인증 없이 열리지 않는다")
	fun unmappedPathsAreDeniedByDefault() {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/health")).GET().build()

		assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403)
	}

	@Test
	@DisplayName("헬스 경로는 인증을 지나지 않는다")
	fun healthzIsOpen() {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/healthz")).GET().build()

		assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200)
	}

	// ------------------------------------------------------------------ 도구

	private fun post(path: String, token: String, body: ByteArray): HttpResponse<String> {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer $token")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build()
		return http.send(request, HttpResponse.BodyHandlers.ofString())
	}

	/**
	 * claude_code 로그 하나. **리소스 속성에 가짜 신원을 실어 보낸다** — 스탬핑이 그것을
	 * 덮어쓰는지가 이 테스트의 판정 대상이다.
	 */
	private fun oneUserPrompt(): ByteArray {
		val now = Instant.now()
		val nanos = now.epochSecond * 1_000_000_000L + now.nano
		return """
			{"resourceLogs":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"claude-code"}},
			  {"key":"tenant.id","value":{"stringValue":"$BOGUS_TENANT"}},
			  {"key":"developer.installation_id","value":{"stringValue":"bogus-installation"}}]},
			 "scopeLogs":[{"logRecords":[{
			   "timeUnixNano":"$nanos",
			   "body":{"stringValue":"claude_code.user_prompt"},
			   "attributes":[
			     {"key":"session.id","value":{"stringValue":"e2e-session"}},
			     {"key":"prompt_length","value":{"intValue":"42"}}]}]}]}]}
		""".trimIndent().toByteArray()
	}

	/** claude_code 스팬 하나. 리소스 속성의 가짜 신원은 [oneUserPrompt] 와 같은 이유로 실어 보낸다. */
	private fun oneLlmRequestSpan(): ByteArray {
		val now = Instant.now()
		val end = now.epochSecond * 1_000_000_000L + now.nano
		val start = end - 3_000_000_000L
		return """
			{"resourceSpans":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"claude-code"}},
			  {"key":"tenant.id","value":{"stringValue":"$BOGUS_TENANT"}}]},
			 "scopeSpans":[{"spans":[{
			   "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"2222222222222222",
			   "name":"claude_code.llm_request","kind":1,
			   "startTimeUnixNano":"$start","endTimeUnixNano":"$end",
			   "attributes":[
			     {"key":"session.id","value":{"stringValue":"e2e-session"}},
			     {"key":"model","value":{"stringValue":"claude-sonnet-4-5"}},
			     {"key":"request_id","value":{"stringValue":"req-e2e-0001"}}]}]}]}]}
		""".trimIndent().toByteArray()
	}

	private fun queryRow(): List<String> = clickHouse(
		"SELECT tenant_id, installation_id, toString(team_ids_as_of) FROM enriched_events FINAL",
	).trim().split('\t')

	private fun truncateEnrichedEvents() {
		// 테이블이 아직 없을 수 있다 — 첫 테스트는 기동 마이그레이션이 만든 뒤에 돈다.
		clickHouse("TRUNCATE TABLE IF EXISTS enriched_events")
	}

	private fun clickHouse(query: String): String {
		val uri = URI.create(clickHouseUrl() + "/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
		val request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		check(response.statusCode() < 400) { "clickhouse ${response.statusCode()}: ${response.body()}" }
		return response.body()
	}

	private companion object {
		const val BOGUS_TENANT = "bogus-tenant-self-reported"
	}
}
