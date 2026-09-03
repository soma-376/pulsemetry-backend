package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.Product
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** 수집 진입점의 특성화 테스트. 상위 `receiver/otlpreceiver/otlphttp.go` 가 사양이다. */
class OtlpIngestHandlerTest {

	private val archive = RecordingArchiveWriter()
	private val consumed = mutableListOf<Pair<Signal, Message>>()
	private var downstream: (Signal, Message) -> Unit = { s, m -> consumed += s to m }
	private var identity: StampedIdentity? = null

	private val handler = OtlpIngestHandler(
		archive = archive,
		next = { signal, request -> downstream(signal, request) },
		identity = { identity },
	)

	// ------------------------------------------------------------------ 상태 매핑

	@Test
	@DisplayName("POST 가 아니면 405 — 본문은 text/plain 이다")
	fun rejectsNonPost() {
		val response = handler.handle(request(method = "GET"))

		assertThat(response.status).isEqualTo(405)
		assertThat(response.contentType).isEqualTo("text/plain")
		assertThat(response.body.toString(Charsets.UTF_8))
			.isEqualTo("405 method not allowed, supported: [POST]")
	}

	@Test
	@DisplayName("모르는 경로면 404 — 상위는 그 경로에 라우트를 등록하지 않는다")
	fun rejectsUnknownPath() {
		assertThat(handler.handle(request(path = "/v1/profiles")).status).isEqualTo(404)
	}

	@Test
	@DisplayName("아는 Content-Type 이 아니면 415 — 지원 목록을 본문에 적는다")
	fun rejectsUnknownContentType() {
		val response = handler.handle(request(contentType = "text/csv"))

		assertThat(response.status).isEqualTo(415)
		assertThat(response.body.toString(Charsets.UTF_8)).isEqualTo(
			"415 unsupported media type, supported: [application/json, application/x-protobuf]",
		)
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = ["application/json", "application/json; charset=utf-8", "APPLICATION/JSON"])
	@DisplayName("Content-Type 의 파라미터와 대소문자를 무시한다 — 상위 mime.ParseMediaType 과 같다")
	fun acceptsContentTypeParameters(contentType: String) {
		val response = handler.handle(request(contentType = contentType, body = "{}".toByteArray()))

		assertThat(response.status).isEqualTo(200)
	}

	@Test
	@DisplayName("모르는 Content-Encoding 이면 400 이고 본문이 google.rpc.Status 다")
	fun rejectsUnknownContentEncoding() {
		val response = handler.handle(request(contentEncoding = "br", body = "{}".toByteArray()))

		assertThat(response.status).isEqualTo(400)
		assertThat(response.contentType).isEqualTo("application/json")
		assertThat(response.body.toString(Charsets.UTF_8))
			.isEqualTo("""{"code":3,"message":"unsupported Content-Encoding: br"}""")
	}

	@Test
	@DisplayName("본문이 깨졌으면 400 이고 코드는 INVALID_ARGUMENT(3) 다 — HTTP 코드를 싣지 않는다")
	fun rejectsUnparseableBody() {
		val response = handler.handle(request(body = "not json".toByteArray()))

		assertThat(response.status).isEqualTo(400)
		assertThat(response.body.toString(Charsets.UTF_8)).startsWith("""{"code":3,""")
	}

	@Test
	@DisplayName("하류의 일반 예외는 503 이다 — 상태가 없는 오류의 기본은 재시도 가능이다")
	fun downstreamFailureIsRetryable() {
		downstream = { _, _ -> throw IllegalStateException("clickhouse down") }

		val response = handler.handle(request(body = oneLogRecord()))

		assertThat(response.status).isEqualTo(503)
		assertThat(response.body.toString(Charsets.UTF_8))
			.isEqualTo("""{"code":14,"message":"clickhouse down"}""")
	}

	@Test
	@DisplayName("하류가 영구 오류를 던지면 400 이다 — 데몬이 즉시 폐기하는 코드는 4xx 뿐이다")
	fun permanentDownstreamFailureIsNotRetryable() {
		downstream = { _, _ -> throw PermanentIngestException("schema mismatch") }

		val response = handler.handle(request(body = oneLogRecord()))

		// 한때 500(INTERNAL) 이었다. 그 의미론의 수신자는 otlphttp exporter 였고, 지금 상태 코드를
		// 읽는 telemetryctl 데몬은 5xx 를 전부 재시도한다 — 허브 ADR 0006 이 뒤집은 자리다.
		assertThat(response.status).isEqualTo(400)
		assertThat(response.body.toString(Charsets.UTF_8))
			.isEqualTo("""{"code":3,"message":"schema mismatch"}""")
	}

	// ------------------------------------------------------------------ 수신 · 왕복

	@ParameterizedTest(name = "{0}")
	@EnumSource(Signal::class)
	@DisplayName("세 경로 모두 받는다")
	fun acceptsEverySignalPath(signal: Signal) {
		assertThat(handler.handle(request(path = signal.path, body = "{}".toByteArray())).status)
			.isEqualTo(200)
	}

	@Test
	@DisplayName("gzip 을 푼다")
	fun decompressesGzip() {
		val response = handler.handle(
			request(contentEncoding = "gzip", body = gzip(oneLogRecord())),
		)

		assertThat(response.status).isEqualTo(200)
		assertThat(consumed).hasSize(1)
	}

	@Test
	@DisplayName("protobuf 로 받으면 protobuf 로 답한다")
	fun answersInTheRequestEncoding() {
		val proto = ExportLogsServiceRequest.parseFrom(
			// JSON 으로 만든 뒤 proto 바이트로 바꿔 넣는다.
			ExportLogsServiceRequest.newBuilder().also {
				OtlpJson.fromJson(oneLogRecord(), it)
			}.build().toByteArray(),
		)

		val response = handler.handle(
			request(contentType = "application/x-protobuf", body = proto.toByteArray()),
		)

		assertThat(response.status).isEqualTo(200)
		assertThat(response.contentType).isEqualTo("application/x-protobuf")
		assertThat(response.body).containsExactly(0x0a, 0x00)
	}

	@Test
	@DisplayName("레코드가 0건이면 하류를 부르지 않고 200 이다")
	fun shortCircuitsEmptyRequests() {
		val response = handler.handle(request(body = """{"resourceLogs":[]}""".toByteArray()))

		assertThat(response.status).isEqualTo(200)
		assertThat(consumed).isEmpty()
		assertThat(archive.written).isEmpty()
	}

	@Test
	@DisplayName("snake_case 와 모르는 필드를 받아들인다 — 상위 파서가 관대하다")
	fun parsesLeniently() {
		val body = """
			{"resource_logs":[{"resource":{"attributes":[]},"unknown_field":123,
			 "scope_logs":[{"log_records":[{"body":{"stringValue":"hi"}}]}]}]}
		""".trimIndent().toByteArray()

		val response = handler.handle(request(body = body))

		assertThat(response.status).isEqualTo(200)
		assertThat(consumed).hasSize(1)
	}

	// ------------------------------------------------------------------ 마스킹 · 아카이브

	@Test
	@DisplayName("logs 는 마스킹해서 아카이브와 하류에 같은 값을 보낸다")
	fun masksLogsBeforeArchiveAndDownstream() {
		handler.handle(request(body = oneLogRecord(attributeValue = "sk-abcdefghij1234567890")))

		val archived = archive.written.single()
		assertThat(archived.body.toString(Charsets.UTF_8)).doesNotContain("sk-abcdefghij")
		assertThat(archived.body.toString(Charsets.UTF_8)).contains("****")

		val downstreamRequest = consumed.single().second as ExportLogsServiceRequest
		assertThat(
			downstreamRequest.getResourceLogs(0).getScopeLogs(0).getLogRecords(0)
				.getAttributes(0).value.stringValue,
		).isEqualTo("****")
	}

	@Test
	@DisplayName("metrics 는 마스킹하지 않는다 — 현행 설정에 redaction 이 없다(M6)")
	fun leavesMetricsUnmasked() {
		val secret = "sk-abcdefghij1234567890"
		val body = """
			{"resourceMetrics":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"claude-code"}},
			  {"key":"leak","value":{"stringValue":"$secret"}}]},
			 "scopeMetrics":[{"metrics":[{"name":"m"}]}]}]}
		""".trimIndent().toByteArray()

		handler.handle(request(path = "/v1/metrics", body = body))

		// 이 단언이 깨졌다면 M6 를 고친 것이다. 그것은 별도 티켓이고, 고친다면
		// Signal.METRICS.masked 와 AttributeWalker · ADR 0012 를 함께 바꿔야 한다.
		assertThat(archive.written.single().body.toString(Charsets.UTF_8)).contains(secret)
	}

	@Test
	@DisplayName("제품별로 갈라 아카이브한다 — service.name 이 판정 기준이다")
	fun splitsArchiveByProduct() {
		val body = """
			{"resourceLogs":[
			 {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"claude-code"}}]},
			  "scopeLogs":[{"logRecords":[{"body":{"stringValue":"a"}}]}]},
			 {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"codex_cli_rs"}}]},
			  "scopeLogs":[{"logRecords":[{"body":{"stringValue":"b"}}]}]}]}
		""".trimIndent().toByteArray()

		handler.handle(request(body = body))

		assertThat(archive.written.map { it.product })
			.containsExactlyInAnyOrder(Product.CLAUDE_CODE, Product.CODEX)
		assertThat(archive.written.single { it.product == Product.CLAUDE_CODE }.body.toString(Charsets.UTF_8))
			.contains("\"a\"").doesNotContain("\"b\"")
	}

	@Test
	@DisplayName("service.name 이 없으면 어느 아카이브에도 남지 않는다 — 하류는 그대로 받는다")
	fun dropsUnknownProductFromEveryArchive() {
		// OTTL 의 nil != "codex_cli_rs" 가 참이라 양쪽 필터가 모두 버린다.
		// 양쪽에 남는 것으로 오해하기 쉬운 자리다.
		handler.handle(request(body = oneLogRecord(serviceName = null)))

		assertThat(archive.written).isEmpty()
		assertThat(consumed).hasSize(1)
	}

	@Test
	@DisplayName("아카이브가 실패하면 하류를 부르지 않고 503 이다 — 복구 원천 없이 진행하지 않는다")
	fun archiveFailureStopsThePipeline() {
		archive.failWith = IllegalStateException("s3 unreachable")

		val response = handler.handle(request(body = oneLogRecord()))

		assertThat(response.status).isEqualTo(503)
		assertThat(consumed).isEmpty()
	}

	// ------------------------------------------------------------------ 실측 fixture

	@Test
	@DisplayName("실측 캡처 48문서를 받아 아카이브가 한 줄 한 문서로 왕복한다")
	fun roundTripsTheRealCapture() {
		val documents = fixtureLines("/otlp/logs_real.otlp.jsonl")
		assertThat(documents).hasSize(48)

		documents.forEach { document ->
			assertThat(handler.handle(request(body = document.toByteArray())).status).isEqualTo(200)
		}

		assertThat(consumed).hasSize(48)
		// 실측 캡처는 전부 claude-code 다.
		assertThat(archive.written.map { it.product }.toSet()).containsExactly(Product.CLAUDE_CODE)
		// 아카이브 산출물 한 건이 완결된 OTLP 문서 하나다.
		archive.written.forEach { entry ->
			val reparsed = ExportLogsServiceRequest.newBuilder()
				.also { OtlpJson.fromJson(entry.body, it) }
				.build()
			assertThat(reparsed.resourceLogsCount).isEqualTo(1)
		}
	}

	@Test
	@DisplayName("합성 fixture 5문서도 같은 경로를 지난다")
	fun roundTripsTheSyntheticCapture() {
		fixtureLines("/otlp/logs_synthetic.otlp.jsonl").forEach { document ->
			assertThat(handler.handle(request(body = document.toByteArray())).status).isEqualTo(200)
		}

		assertThat(consumed).hasSize(5)
	}

	@Test
	@DisplayName("왕복이 데이터를 잃지 않는다 — 파싱한 메시지와 아카이브를 다시 읽은 메시지가 같다")
	fun archiveDocumentEqualsParsedRequest() {
		val document = fixtureLines("/otlp/logs_real.otlp.jsonl").first()

		handler.handle(request(body = document.toByteArray()))

		val fromDownstream = consumed.single().second as ExportLogsServiceRequest
		val fromArchive = ExportLogsServiceRequest.newBuilder()
			.also { OtlpJson.fromJson(archive.written.single().body, it) }
			.build()

		assertThat(fromArchive).isEqualTo(fromDownstream)
	}

	// ------------------------------------------------------------------ 신원 스탬프

	@Test
	@DisplayName("검증된 신원을 리소스 속성으로 심는다 — 아카이브와 하류가 같은 값을 본다")
	fun stampsIdentityIntoResourceAttributes() {
		identity = StampedIdentity(tenantId = "ten-1", installationId = "inst-1")

		handler.handle(request(body = oneLogRecord()))

		// 아카이브가 먼저 쓰이므로, 여기 보이면 외부 저장소의 원본도 신원을 갖는다는 뜻이다.
		val archived = archive.written.single().body.toString(Charsets.UTF_8)
		assertThat(archived).contains("tenant.id").contains("ten-1")
		assertThat(archived).contains("developer.installation_id").contains("inst-1")

		val resource = (consumed.single().second as ExportLogsServiceRequest).getResourceLogs(0).resource
		assertThat(resource.attributesList.associate { it.key to it.value.stringValue })
			.containsEntry("tenant.id", "ten-1")
			.containsEntry("developer.installation_id", "inst-1")
	}

	@Test
	@DisplayName("클라이언트 자기신고를 덮어쓴다 — 검증된 값이 신뢰 경계다")
	fun theVerifiedValueWinsOverSelfReport() {
		identity = StampedIdentity(tenantId = "ten-real", installationId = null)
		val body = """
			{"resourceLogs":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"claude-code"}},
			  {"key":"tenant.id","value":{"stringValue":"ten-fake"}}]},
			 "scopeLogs":[{"logRecords":[{"body":{"stringValue":"hi"}}]}]}]}
		""".trimIndent().toByteArray()

		handler.handle(request(body = body))

		val resource = (consumed.single().second as ExportLogsServiceRequest).getResourceLogs(0).resource
		val attributes = resource.attributesList.filter { it.key == "tenant.id" }
		assertThat(attributes).singleElement()
		assertThat(attributes.single().value.stringValue).isEqualTo("ten-real")
	}

	@Test
	@DisplayName("빈 값은 건너뛴다 — 없는 신원으로 있는 값을 지우지 않는다")
	fun anEmptyValueIsNotStamped() {
		identity = StampedIdentity(tenantId = "", installationId = null)

		handler.handle(request(body = oneLogRecord()))

		val resource = (consumed.single().second as ExportLogsServiceRequest).getResourceLogs(0).resource
		assertThat(resource.attributesList.map { it.key }).doesNotContain("tenant.id")
	}

	@Test
	@DisplayName("신원이 없으면 아무것도 심지 않는다 — 인증을 세우지 않은 호출자의 기본값이다")
	fun noIdentityStampsNothing() {
		identity = null

		handler.handle(request(body = oneLogRecord()))

		val resource = (consumed.single().second as ExportLogsServiceRequest).getResourceLogs(0).resource
		assertThat(resource.attributesList.map { it.key }).containsExactly("service.name")
	}

	@Test
	@DisplayName("metrics 에도 심는다 — 마스킹하지 않는 신호라고 신원까지 빠지지 않는다")
	fun stampsMetricsToo() {
		identity = StampedIdentity(tenantId = "ten-1", installationId = "inst-1")
		val body = """
			{"resourceMetrics":[{"resource":{"attributes":[
			  {"key":"service.name","value":{"stringValue":"claude-code"}}]},
			 "scopeMetrics":[{"metrics":[{"name":"m"}]}]}]}
		""".trimIndent().toByteArray()

		handler.handle(request(path = "/v1/metrics", body = body))

		assertThat(archive.written.single().body.toString(Charsets.UTF_8))
			.contains("developer.installation_id").contains("inst-1")
	}

	// ------------------------------------------------------------------ 도구

	private fun request(
		method: String = "POST",
		path: String = "/v1/logs",
		contentType: String? = "application/json",
		contentEncoding: String? = null,
		body: ByteArray = ByteArray(0),
	) = OtlpHttpRequest(method, path, contentType, contentEncoding, body)

	private fun oneLogRecord(
		serviceName: String? = "claude-code",
		attributeValue: String = "plain",
	): ByteArray {
		val resourceAttrs = if (serviceName == null) {
			""
		} else {
			"""{"key":"service.name","value":{"stringValue":"$serviceName"}}"""
		}
		return """
			{"resourceLogs":[{"resource":{"attributes":[$resourceAttrs]},
			 "scopeLogs":[{"logRecords":[{"body":{"stringValue":"hi"},
			  "attributes":[{"key":"a","value":{"stringValue":"$attributeValue"}}]}]}]}]}
		""".trimIndent().toByteArray()
	}

	private fun gzip(body: ByteArray): ByteArray {
		val out = ByteArrayOutputStream()
		GZIPOutputStream(out).use { it.write(body) }
		return out.toByteArray()
	}

	private fun fixtureLines(resource: String): List<String> =
		OtlpIngestHandlerTest::class.java.getResourceAsStream(resource)
			?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }
			?: error("$resource 를 찾지 못했다")

	private class ArchiveEntry(val product: Product, val signal: Signal, val body: ByteArray)

	private class RecordingArchiveWriter : ArchiveWriter {
		val written = mutableListOf<ArchiveEntry>()
		var failWith: RuntimeException? = null

		override fun write(product: Product, signal: Signal, body: ByteArray) {
			failWith?.let { throw it }
			written += ArchiveEntry(product, signal, body)
		}
	}
}
