package com.team376.pulsemetry.telemetry

import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsUnavailableException
import com.team376.pulsemetry.persistence.telemetryops.TenantSummaryBackfill
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestTestData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
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
 * 저장 대상은 정규화 2판의 분석 테이블(`telemetry_events`·`telemetry_metric_points`)과 수집 운영 기록(ledger·요약)이다
 * (ADR 0020 · 0021). 구 `enriched_events` 에는 쓰지 않는다.
 *
 * 짧은 입력은 여기 직접 적고, 실캡처 입력은 어댑터의 `otlp-v2/…/real` fixture 에서 읽는다(파일 경로 — 테스트 리소스는
 * 모듈 사이에 공유되지 않는다). 정규화 자체는 fixture 테스트가 검증하므로 이 테스트의 몫은 **배선**이다 — 신원·보강·
 * 아카이브 참조·버전이 행에 실렸는지, 실패가 어떤 상태로 나가는지.
 */
class TelemetryIngestE2eTest : AbstractIngestIntegrationTest() {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var data: IngestTestData

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Value("\${pulsemetry.telemetry.archive.dir}")
	private lateinit var archiveDir: String

	private val http: HttpClient = HttpClient.newHttpClient()

	@BeforeEach
	fun reset() {
		data.clear()
		truncateAnalysisTables()
		// 파일 아카이브는 append 전용이라 실행마다 쌓인다. 이전 실행의 내용에 좌우되지 않게 비운다.
		for (product in listOf("claude_code", "codex", "unknown")) {
			for (signal in listOf("logs", "traces")) {
				Files.deleteIfExists(Path.of(archiveDir, product, "$signal.jsonl"))
			}
		}
	}

	@AfterEach
	fun cleanUp() {
		data.clear()
	}

	@Test
	@DisplayName("enroll 토큰으로 보낸 OTLP 가 분석 행·ledger·요약이 된다 — 신원은 토큰에서, 구성원·팀은 보강에서 온다")
	fun anAuthenticatedPushBecomesAnAnalysisRow() {
		val seeded = data.seed()

		val response = post("/v1/logs", seeded.rawToken, oneUserPrompt())

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.body()).isEqualTo("""{"partialSuccess":{}}""")

		val row = queryRow()
		// 클라이언트가 보낸 자기신고 값이 아니라 토큰에서 파생된 신원이어야 한다.
		assertThat(row[0]).isEqualTo(seeded.tenantId.toString())
		assertThat(row[1]).isEqualTo(seeded.installationId.toString())
		// 보강 2판 — installation 의 구성원과 이벤트 시점의 단일 소속 팀.
		assertThat(row[2]).isEqualTo(seeded.memberId.toString())
		assertThat(row[3]).isEqualTo(seeded.teamId.toString())
		// 아카이브 영수증의 객체와 문서 안 위치.
		assertThat(row[4]).startsWith("file:").endsWith("claude_code/logs.jsonl")
		assertThat(row[5]).matches("bytes=\\d+\\+\\d+;path=0/0/0")
		assertThat(row[6]).matches("[0-9a-f]{64}")
		// row_version = (normalizer_rev << 32) | receipt 수신 epoch 초.
		assertThat(row[7].toULong() shr 32).isEqualTo(1uL)
		assertThat(row[8]).isEqualTo("1")

		val ledger = clickHouse(
			"SELECT tenant_id, installation_id, signal, product, record_count, rejected_count, archive_ref FROM telemetry_ingest_ledger FINAL",
		).trim().split('\t')
		assertThat(ledger).containsExactly(
			seeded.tenantId.toString(), seeded.installationId.toString(), "log", "claude_code", "1", "0", row[4],
		)
		val summary = summary(seeded)
		assertThat(summary["first_received_at"]).isNotNull()
		assertThat(summary["last_received_at"]).isEqualTo(summary["first_received_at"])
		assertThat(summary["first_observed_at"]).isNotNull()
		// 이중 적재하지 않는다 — 구 테이블은 새 행을 받지 않는다.
		assertThat(clickHouse("SELECT count() FROM enriched_events").trim()).isEqualTo("0")
	}

	@Test
	@DisplayName("실캡처 Claude Code·Codex push — 등록된 프로파일의 사용량 행과 generic 행이 검증된 신원으로 적재된다")
	fun realCapturesAreLoaded() {
		val seeded = data.seed()

		assertThat(post("/v1/logs", seeded.rawToken, realFixture("claude_code", "logs-claude-code-2.1.282-api.otlp.jsonl")).statusCode())
			.isEqualTo(200)
		assertThat(post("/v1/logs", seeded.rawToken, realFixture("codex", "logs-codex-desktop-0.153.4-sse.otlp.jsonl")).statusCode())
			.isEqualTo(200)

		// 기대값은 그 fixture 의 기대 파일(ADR 0020 부록 A.2)이 정한 것이다 — api_request 가 사용량 행, 비용은 보고 추정값.
		assertThat(
			clickHouse(
				"SELECT product, original_name, event_type, usage_role, toString(tokens_output), toString(cost_reported_usd) " +
					"FROM telemetry_events FINAL WHERE usage_role = 'primary' FORMAT TSV",
			).trim(),
		).isEqualTo("claude_code\tapi_request\tmodel.response.usage\tprimary\t863\t0.116755")
		assertThat(clickHouse("SELECT DISTINCT product FROM telemetry_events FINAL ORDER BY product").trim().lines())
			.containsExactly("claude_code", "codex")
		assertThat(clickHouse("SELECT DISTINCT tenant_id, member_id FROM telemetry_events FINAL FORMAT TSV").trim())
			.isEqualTo("${seeded.tenantId}\t${seeded.memberId}")
		assertThat(clickHouse("SELECT count() FROM telemetry_ingest_ledger FINAL").trim()).isEqualTo("2")
	}

	@Test
	@DisplayName("/v1/traces 로 보낸 스팬도 행이 된다 — 티켓이 적은 경로 그대로")
	fun anAuthenticatedTracePushBecomesAnAnalysisRow() {
		val seeded = data.seed()

		val response = post("/v1/traces", seeded.rawToken, oneLlmRequestSpan())

		assertThat(response.statusCode()).isEqualTo(200)
		val row = queryRow()
		assertThat(row[0]).isEqualTo(seeded.tenantId.toString())
		assertThat(row[1]).isEqualTo(seeded.installationId.toString())
		assertThat(row[3]).isEqualTo(seeded.teamId.toString())
		assertThat(clickHouse("SELECT signal FROM telemetry_ingest_ledger FINAL").trim()).isEqualTo("span")
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
	@DisplayName("codex-app-server 는 codex 구간에, 별칭 표에 없는 서비스는 unknown 구간에 아카이브된다")
	fun codexAppServerAndUnregisteredServicesAreArchived() {
		val seeded = data.seed()

		val codex = post("/v1/logs", seeded.rawToken, oneLogFrom("codex-app-server"))
		val unknown = post("/v1/logs", seeded.rawToken, oneLogFrom("node_repl"))

		assertThat(codex.statusCode()).isEqualTo(200)
		assertThat(unknown.statusCode()).isEqualTo(200)
		assertThat(Files.readString(Path.of(archiveDir, "codex", "logs.jsonl")))
			.contains("codex-app-server").contains(seeded.tenantId.toString())
		assertThat(Files.readString(Path.of(archiveDir, "unknown", "logs.jsonl")))
			.contains("node_repl").contains(seeded.tenantId.toString())
		// 미등록 서비스도 generic 행으로 남는다 — 제품은 unknown, 사용량이 아니다.
		assertThat(
			clickHouse("SELECT product, usage_role, archive_ref FROM telemetry_events FINAL WHERE service_name = 'node_repl' FORMAT TSV").trim(),
		).startsWith("unknown\tnone\tfile:").endsWith("unknown/logs.jsonl")
	}

	@Test
	@DisplayName("같은 요청을 두 번 보내도 관측은 하나다 — observation_id 가 키다. ledger 는 receipt 둘을 따로 남긴다")
	fun theSamePushTwiceCollapsesToOneObservation() {
		val seeded = data.seed()
		val body = oneUserPrompt()

		post("/v1/logs", seeded.rawToken, body)
		post("/v1/logs", seeded.rawToken, body)

		assertThat(clickHouse("SELECT count() FROM telemetry_events FINAL").trim()).isEqualTo("1")
		assertThat(clickHouse("SELECT count(), uniqExact(receipt_id) FROM telemetry_ingest_ledger FINAL FORMAT TSV").trim())
			.isEqualTo("2\t2")
	}

	@Test
	@DisplayName("같은 신원 키를 두 번 보내도 뒤의 자기신고가 검증값을 이기지 못한다 — logs")
	fun duplicateIdentityKeysInLogsCannotSmuggleAnotherTenant() {
		val seeded = data.seed()

		val response = post("/v1/logs", seeded.rawToken, oneUserPrompt(duplicateIdentity = true))

		assertThat(response.statusCode()).isEqualTo(200)
		assertIdentityIsTheVerifiedOne(seeded, archivedSignal = "logs")
	}

	@Test
	@DisplayName("같은 신원 키를 두 번 보내도 뒤의 자기신고가 검증값을 이기지 못한다 — traces")
	fun duplicateIdentityKeysInTracesCannotSmuggleAnotherTenant() {
		val seeded = data.seed()

		val response = post("/v1/traces", seeded.rawToken, oneLlmRequestSpan(duplicateIdentity = true))

		assertThat(response.statusCode()).isEqualTo(200)
		assertIdentityIsTheVerifiedOne(seeded, archivedSignal = "traces")
	}

	@Test
	@DisplayName("인증 조회의 DB 장애는 503 + Retry-After 다 — 401·403 이면 데몬이 멀쩡한 토큰을 버린다")
	fun anAuthenticationStoreOutageIsRetryable() {
		val seeded = data.seed()
		doThrow(DataAccessResourceFailureException("simulated rds outage"))
			.`when`(telemetryTokens).findAuthRowByTokenHash(anyString())

		val response = post("/v1/logs", seeded.rawToken, oneUserPrompt())

		assertThat(response.statusCode()).isEqualTo(503)
		assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")
		// google.rpc.Status — UNAVAILABLE 은 14 다. DB 오류 메시지는 본문에 싣지 않는다.
		assertThat(response.body()).contains("\"code\":14").doesNotContain("simulated")
		assertThat(clickHouse("SELECT count() FROM telemetry_events").trim()).isEqualTo("0")
	}

	@Test
	@DisplayName("ClickHouse 적재 장애는 503 + Retry-After 다 — 수신 기록도 남기지 않는다(재전송이 남긴다)")
	fun aClickHouseOutageIsRetryable() {
		val seeded = data.seed()
		doThrow(TelemetrySinkUnavailableException("simulated clickhouse outage")).`when`(telemetryEvents).insert(anything(), anything(), anything())

		val response = post("/v1/logs", seeded.rawToken, oneUserPrompt())

		assertThat(response.statusCode()).isEqualTo(503)
		assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
		assertThat(clickHouse("SELECT count() FROM telemetry_ingest_ledger").trim()).isEqualTo("0")
	}

	@Test
	@DisplayName("보강의 RDS 장애는 503 이다")
	fun anEnrichmentStoreOutageIsRetryable() {
		val seeded = data.seed()
		doThrow(DataAccessResourceFailureException("simulated rds outage")).`when`(installations).findMemberIdById(anything())

		val response = post("/v1/logs", seeded.rawToken, oneUserPrompt())

		assertThat(response.statusCode()).isEqualTo(503)
		assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
		assertThat(clickHouse("SELECT count() FROM telemetry_events").trim()).isEqualTo("0")
	}

	@Test
	@DisplayName("요약 쓰기 장애는 503 이다 — 수신 기록의 내구성을 확인하지 못한 push 에 성공을 돌려주지 않는다")
	fun aSummaryOutageIsRetryable() {
		val seeded = data.seed()
		doThrow(TelemetryOpsUnavailableException("simulated telemetry_ops outage")).`when`(summaries).record(anything(), anything(), anything(), anything())

		val response = post("/v1/logs", seeded.rawToken, oneUserPrompt())

		assertThat(response.statusCode()).isEqualTo(503)
		assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
	}

	@Test
	@DisplayName("분석 테이블만 적재된 뒤 실패해 재전송해도 KPI 가 그대로다 — 같은 관측이 교체로 수렴한다")
	fun aRetryAfterAPartialWriteKeepsTheKpi() {
		val seeded = data.seed()
		val body = realFixture("claude_code", "logs-claude-code-2.1.282-api.otlp.jsonl")
		doThrow(TelemetryOpsUnavailableException("simulated telemetry_ops outage"))
			.doCallRealMethod()
			.`when`(summaries).record(anything(), anything(), anything(), anything())

		val first = post("/v1/logs", seeded.rawToken, body)
		val kpiAfterFailure = kpi()
		val second = post("/v1/logs", seeded.rawToken, body)

		assertThat(first.statusCode()).isEqualTo(503)
		assertThat(kpiAfterFailure).isEqualTo("2\t2\t863\t0.116755")
		assertThat(second.statusCode()).isEqualTo(200)
		assertThat(kpi()).isEqualTo(kpiAfterFailure)
		assertThat(summary(seeded)["first_received_at"]).isNotNull()
	}

	@Test
	@DisplayName("기동 시 요약 도입 전 이력의 백필이 한 번 돌아 완료를 기록했다")
	fun thePreLedgerBackfillRanAtStartup() {
		assertThat(
			jdbc.queryForObject(
				"SELECT count(*) FROM telemetry_ops.tenant_summary_backfill WHERE backfill = ?",
				Int::class.java,
				TenantSummaryBackfill.PRE_LEDGER_ENRICHED_EVENTS,
			),
		).isEqualTo(1)
	}

	@Test
	@DisplayName("필터가 잡지 못한 예외도 403 이 아니라 500 이다 — 기본 닫힘 체인이 오류 디스패치를 막지 않는다")
	fun anUncaughtFailureIsStillAServerError() {
		val seeded = data.seed()
		// RuntimeException 이 아니라 필터의 catch 를 지나쳐 컨테이너까지 간다. 그 경로의 대표다.
		doThrow(SimulatedContainerError()).`when`(telemetryTokens).findAuthRowByTokenHash(anyString())

		val response = post("/v1/logs", seeded.rawToken, oneUserPrompt())

		assertThat(response.statusCode()).isEqualTo(500)
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

	@ParameterizedTest
	@ValueSource(strings = ["/actuator/health", "/api/v1/unknown", "/error"])
	@DisplayName("계약 밖 경로는 토큰 유무와 무관하게 404 다 — 인증 복구를 유발하지 않는다")
	fun unmappedPathsAreDeniedByDefault(path: String) {
		val seeded = data.seed()
		for (token in listOf(null, "invalid-token", seeded.rawToken)) {
			val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
				.POST(HttpRequest.BodyPublishers.ofByteArray(oneUserPrompt()))
			if (token != null) request.header("Authorization", "Bearer $token")

			val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())

			assertThat(response.statusCode()).isEqualTo(404)
			assertThat(response.headers().firstValue("Content-Type")).hasValue("text/plain")
			assertThat(response.body()).isEqualTo("404 not found")
			assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty
			assertThat(response.headers().firstValue("Retry-After")).isEmpty
		}
		assertThat(clickHouse("SELECT count() FROM telemetry_events").trim()).isEqualTo("0")
		assertThat(Path.of(archiveDir, "claude_code", "logs.jsonl")).doesNotExist()
	}

	@Test
	@DisplayName("헬스 경로는 인증을 지나지 않는다")
	fun healthzIsOpen() {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/healthz")).GET().build()

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

	/** 행과 아카이브 모두 토큰에서 파생된 신원만 담고, 자기신고 값은 어디에도 없다. */
	private fun assertIdentityIsTheVerifiedOne(seeded: IngestTestData.Seeded, archivedSignal: String) {
		val row = queryRow()
		assertThat(row[0]).isEqualTo(seeded.tenantId.toString())
		assertThat(row[1]).isEqualTo(seeded.installationId.toString())
		assertThat(row[3]).isEqualTo(seeded.teamId.toString())

		val archived = Files.readString(Path.of(archiveDir, "claude_code", "$archivedSignal.jsonl"))
		assertThat(archived).doesNotContain(BOGUS_TENANT).doesNotContain(BOGUS_INSTALLATION)
	}

	/**
	 * claude_code 로그 하나. **리소스 속성에 가짜 신원을 실어 보낸다** — 스탬핑이 그것을
	 * 덮어쓰는지가 이 테스트의 판정 대상이다. [duplicateIdentity] 는 같은 키를 **뒤에 한 번 더**
	 * 싣는다 — 변환 단계가 마지막 값을 읽으므로, 첫 항목만 덮어쓰면 뒤의 것이 이긴다.
	 */
	private fun oneUserPrompt(duplicateIdentity: Boolean = false): ByteArray {
		val now = Instant.now()
		val nanos = now.epochSecond * 1_000_000_000L + now.nano
		return """
			{"resourceLogs":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"claude-code"}},
			  {"key":"tenant.id","value":{"stringValue":"$BOGUS_TENANT"}},
			  {"key":"developer.installation_id","value":{"stringValue":"$BOGUS_INSTALLATION"}}
			  ${if (duplicateIdentity) ",$TRAILING_BOGUS_IDENTITY" else ""}]},
			 "scopeLogs":[{"logRecords":[{
			   "timeUnixNano":"$nanos",
			   "body":{"stringValue":"claude_code.user_prompt"},
			   "attributes":[
			     {"key":"session.id","value":{"stringValue":"e2e-session"}},
			     {"key":"prompt_length","value":{"intValue":"42"}}]}]}]}]}
		""".trimIndent().toByteArray()
	}

	/** [serviceName] 이 보낸 로그 하나. 적재 대상인지와 무관하게 아카이브는 남아야 한다. */
	private fun oneLogFrom(serviceName: String): ByteArray {
		val now = Instant.now()
		val nanos = now.epochSecond * 1_000_000_000L + now.nano
		return """
			{"resourceLogs":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"$serviceName"}}]},
			 "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos",
			   "attributes":[{"key":"event.name","value":{"stringValue":"codex.user_prompt"}}]}]}]}]}
		""".trimIndent().toByteArray()
	}

	/**
	 * claude_code 스팬 하나. 리소스 속성의 가짜 신원은 [oneUserPrompt] 와 같은 이유로 실어 보낸다. 스팬은 프로파일의 허용
	 * 목록에 있어야 행이 되므로(ADR 0020 부록 A.4) 검증된 producer 버전을 싣는다.
	 */
	private fun oneLlmRequestSpan(duplicateIdentity: Boolean = false): ByteArray {
		val now = Instant.now()
		val end = now.epochSecond * 1_000_000_000L + now.nano
		val start = end - 3_000_000_000L
		return """
			{"resourceSpans":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"claude-code"}},
			  {"key":"service.version","value":{"stringValue":"2.1.282"}},
			  {"key":"tenant.id","value":{"stringValue":"$BOGUS_TENANT"}}
			  ${if (duplicateIdentity) ",$TRAILING_BOGUS_IDENTITY" else ""}]},
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

	/** 이벤트 행 하나 — 신원·보강·아카이브·키·버전. */
	private fun queryRow(): List<String> = clickHouse(
		"SELECT tenant_id, installation_id, member_id, team_id_as_of, archive_ref, archive_selector, observation_id, " +
			"row_version, normalizer_rev FROM telemetry_events FINAL FORMAT TSV",
	).trim().split('\t')

	/** 관측 수·서로 다른 관측 ID 수·출력 토큰 합·보고 비용 합 — FINAL 로 관측당 최신 행만 센다. */
	private fun kpi(): String = clickHouse(
		"SELECT count(), uniqExact(observation_id), sum(tokens_output), toString(sum(cost_reported_usd)) FROM telemetry_events FINAL FORMAT TSV",
	).trim()

	private fun summary(seeded: IngestTestData.Seeded): Map<String, Any?> = jdbc.queryForMap(
		"SELECT first_received_at, first_observed_at, last_received_at FROM telemetry_ops.tenant_ingest_summary WHERE tenant_id = ?",
		seeded.tenantId,
	)

	/** 어댑터 모듈의 실캡처 fixture 첫 줄 — OTLP/JSON export 요청 하나다. */
	private fun realFixture(product: String, file: String): ByteArray {
		val path = Path.of("../../libs/telemetry-adapter/src/test/resources/otlp-v2", product, "real", file)
		check(Files.exists(path)) { "실캡처 fixture 가 없다: $path" }
		return Files.readAllLines(path).first().toByteArray()
	}

	private fun truncateAnalysisTables() {
		// 테이블이 아직 없을 수 있다 — 첫 테스트는 기동 마이그레이션이 만든 뒤에 돈다.
		for (table in listOf("telemetry_events", "telemetry_metric_points", "telemetry_ingest_ledger", "enriched_events")) {
			clickHouse("TRUNCATE TABLE IF EXISTS $table")
		}
	}

	private fun clickHouse(query: String): String {
		val uri = URI.create(clickHouseUrl() + "/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
		val request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		check(response.statusCode() < 400) { "clickhouse ${response.statusCode()}: ${response.body()}" }
		return response.body()
	}

	/**
	 * 아무 값과 맞는 matcher. Mockito 의 `any()` 는 null 을 돌려줘 Kotlin 의 non-null 매개변수 검사에 걸린다 — matcher 만
	 * 등록하고 null 을 T 로 돌려준다(제네릭이라 호출 쪽에 검사가 생기지 않는다).
	 */
	@Suppress("UNCHECKED_CAST")
	private fun <T> anything(): T {
		ArgumentMatchers.any<T>()
		return null as T
	}

	/** 필터의 `catch (RuntimeException)` 을 지나쳐 컨테이너까지 가는 예외의 대역. */
	private class SimulatedContainerError : Error("simulated uncaught failure")

	private companion object {
		const val BOGUS_TENANT = "bogus-tenant-self-reported"
		const val BOGUS_INSTALLATION = "bogus-installation"

		/** 같은 두 키를 한 번 더, 다른 가짜 값으로. 배열의 맨 뒤에 붙는다. */
		const val TRAILING_BOGUS_IDENTITY =
			"""{"key":"tenant.id","value":{"stringValue":"$BOGUS_TENANT-2"}},""" +
				"""{"key":"developer.installation_id","value":{"stringValue":"$BOGUS_INSTALLATION-2"}}"""
	}
}
