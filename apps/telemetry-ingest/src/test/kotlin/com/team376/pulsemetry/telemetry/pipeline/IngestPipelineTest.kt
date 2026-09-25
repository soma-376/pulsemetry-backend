package com.team376.pulsemetry.telemetry.pipeline

import com.google.protobuf.Message
import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryMetricPointsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import com.team376.pulsemetry.telemetry.adapter.observation.NormalizationStats
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationBatch
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationContext
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationNormalizer
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationPipeline
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt
import com.team376.pulsemetry.telemetry.collector.archive.ArchivedDocument
import com.team376.pulsemetry.telemetry.collector.archive.ArchivedObject
import com.team376.pulsemetry.telemetry.collector.archive.Product
import com.team376.pulsemetry.telemetry.enricher.EnrichmentUnavailableException
import com.team376.pulsemetry.telemetry.enricher.observation.ObservationEnricher
import com.team376.pulsemetry.telemetry.enricher.provider.AiAnalysisProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.provider.JiraProvider
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessResourceUsageException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 파이프라인의 배선과 실패 처분을 고정한다 (허브 ADR 0006 · ADR 0020 §3 · ADR 0021 §1).
 *
 * ClickHouse 대신 상태 코드를 마음대로 돌려주는 스텁 서버를 쓰고, 보강의 RDS 조회는 대역이다 — 판정 대상이 상태 코드의
 * 처분과 운영 기록을 부르는 순서이지 저장소의 동작이 아니다. 실제 저장은 E2E 가 본다.
 */
class IngestPipelineTest {

	private lateinit var server: HttpServer
	private var status: Int = 200
	private val requests = mutableListOf<String>()

	private val tenant = UUID.randomUUID().toString()
	private val installation = UUID.randomUUID()
	private val member = UUID.randomUUID()
	private val installations: InstallationRepository = mock(InstallationRepository::class.java)
	private val memberships: TeamMembershipRepository = mock(TeamMembershipRepository::class.java)
	private val operations = RecordingOperations()

	@BeforeEach
	fun startStub() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			// 쿼리 문자열의 query 값만 — 요청한 SQL 이다.
			val query = exchange.requestURI.rawQuery.orEmpty().substringAfter("query=").substringBefore("&")
			requests += URLDecoder.decode(query, StandardCharsets.UTF_8)
			val bytes = "".toByteArray(StandardCharsets.UTF_8)
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
		given(installations.findMemberIdById(installation)).willReturn(member)
		given(memberships.findAllByMemberId(member)).willReturn(emptyList())
	}

	@AfterEach
	fun stopStub() {
		server.stop(0)
	}

	@Test
	@DisplayName("성공 — 분석 테이블에 쓰고 운영 기록을 남긴다. 구 enriched_events 에는 쓰지 않는다")
	fun aSuccessfulPushLoadsAndRecords() {
		val pipeline = pipeline()
		requests.clear()

		pipeline.consume(Signal.LOGS, oneApiRequest(), receipt())

		assertThat(requests.filter { it.startsWith("INSERT") }).singleElement()
			.satisfies({ assertThat(it).startsWith("INSERT INTO telemetry_events (") })
		assertThat(requests).noneMatch { it.contains("enriched_events") }
		val parts = operations.loaded.single()
		assertThat(parts.single().first.product).isEqualTo("claude_code")
		assertThat(parts.single().second.received).isEqualTo(1)
		assertThat(operations.unloaded).isEmpty()
	}

	@Test
	@DisplayName("ClickHouse 4xx 는 PermanentIngestException 이다 — 받은 것 전부를 거부로 기록한 뒤 400")
	fun aRejectedInsertIsRecordedThenPermanent() {
		val pipeline = pipeline()
		status = 400

		assertThatThrownBy { pipeline.consume(Signal.LOGS, oneApiRequest(), receipt()) }
			.isInstanceOf(PermanentIngestException::class.java)
			.hasMessageContaining("clickhouse 400")
		assertThat(operations.unloaded.single().single().recordCount).isEqualTo(1)
		assertThat(operations.loaded).isEmpty()
	}

	@Test
	@DisplayName("ClickHouse 5xx 는 그대로 전파된다 — 503 이고 운영 기록은 재전송이 남긴다")
	fun anUnavailableInsertPropagatesWithoutRecording() {
		val pipeline = pipeline()
		status = 503

		assertThatThrownBy { pipeline.consume(Signal.LOGS, oneApiRequest(), receipt()) }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
		assertThat(operations.loaded).isEmpty()
		assertThat(operations.unloaded).isEmpty()
	}

	@Test
	@DisplayName("스키마가 아직이면 적재 전에 다시 적용한다 — 테이블 없이 INSERT 하면 404 로 폐기된다")
	fun theSchemaGateRunsBeforeTheInsert() {
		val pipeline = pipeline(startupAttempts = 0)
		requests.clear()

		pipeline.consume(Signal.LOGS, oneApiRequest(), receipt())

		assertThat(requests.first()).startsWith("CREATE TABLE")
		assertThat(requests.last()).startsWith("INSERT INTO telemetry_events")
	}

	@Test
	@DisplayName("보강의 스키마 드리프트는 영구 오류다 — 기록한 뒤 400, 적재 요청은 없다")
	fun aSchemaDriftInEnrichmentIsPermanent() {
		given(installations.findMemberIdById(installation)).willThrow(InvalidDataAccessResourceUsageException("column gone"))
		val pipeline = pipeline()
		requests.clear()

		assertThatThrownBy { pipeline.consume(Signal.LOGS, oneApiRequest(), receipt()) }
			.isInstanceOf(PermanentIngestException::class.java)
			.hasMessageContaining("column gone")
		assertThat(requests).isEmpty()
		assertThat(operations.unloaded).hasSize(1)
	}

	@Test
	@DisplayName("보강의 연결 실패는 일시 장애다 — 503, 기록 없음")
	fun aResourceFailureInEnrichmentStaysTransient() {
		given(installations.findMemberIdById(installation)).willThrow(DataAccessResourceFailureException("rds down"))
		val pipeline = pipeline()

		assertThatThrownBy { pipeline.consume(Signal.LOGS, oneApiRequest(), receipt()) }
			.isInstanceOf(EnrichmentUnavailableException::class.java)
		assertThat(operations.unloaded).isEmpty()
	}

	@Test
	@DisplayName("정규화 실패는 영구 오류다 — 받은 수로 기록한 뒤 400, 적재 요청은 없다")
	fun aNormalizationFailureIsRecordedThenPermanent() {
		val pipeline = pipeline(normalize = { _, _ -> throw IllegalStateException("unreadable span") })
		requests.clear()

		assertThatThrownBy { pipeline.consume(Signal.TRACES, oneApiRequest(), receipt(Signal.TRACES)) }
			.isInstanceOf(PermanentIngestException::class.java)
			.hasMessageContaining("/v1/traces")
			.hasMessageContaining("unreadable span")
		assertThat(requests).isEmpty()
		assertThat(operations.unloaded.single().single().recordCount).isEqualTo(1)
	}

	@Test
	@DisplayName("운영 기록이 실패하면 성공을 돌려주지 않는다 — 예외가 전파되어 503")
	fun aRecordingFailureIsNotASuccess() {
		operations.failure = IllegalStateException("summary down")
		val pipeline = pipeline()

		assertThatThrownBy { pipeline.consume(Signal.LOGS, oneApiRequest(), receipt()) }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("summary down")
	}

	@Test
	@DisplayName("검증된 신원이 없는 영수증은 적재하지 않는다 — 정규화 실패(400)가 아니라 503")
	fun aReceiptWithoutIdentityIsNotPermanent() {
		val pipeline = pipeline()

		assertThatThrownBy { pipeline.consume(Signal.LOGS, oneApiRequest(), receipt().copyWithoutIdentity()) }
			.isInstanceOf(IllegalStateException::class.java)
			.isNotInstanceOf(PermanentIngestException::class.java)
		assertThat(operations.unloaded).isEmpty()
	}

	@Test
	@DisplayName("정규화는 아카이브 문서마다 돈다 — 문서의 객체와 문서 기준 경로가 archive_ref·selector 가 된다")
	fun normalizationRunsPerArchivedDocument() {
		val contexts = mutableListOf<Pair<ObservationContext, Message>>()
		val real = observationPipeline()
		val pipeline = pipeline(normalize = { request, context -> contexts += context to request; real.run(request, context) })
		val request = twoResources()
		val receipt = receipt(
			documents = listOf(
				ArchivedDocument(Product.CODEX, ArchivedObject("s3://raw/codex/logs/b.json"), listOf(1)),
				ArchivedDocument(Product.CLAUDE_CODE, ArchivedObject("file:/archive/claude_code/logs.jsonl", 10L, 20L), listOf(0)),
			),
		)

		pipeline.consume(Signal.LOGS, request, receipt)

		assertThat(contexts).hasSize(2)
		assertThat(contexts.map { it.first.archive!!.locate(listOf(0, 0, 0))!!.ref })
			.containsExactly("s3://raw/codex/logs/b.json", "file:/archive/claude_code/logs.jsonl")
		assertThat(contexts[1].first.archive!!.locate(listOf(0, 0, 0))!!.selector).isEqualTo("bytes=10+20;path=0/0/0")
		assertThat(operations.loaded.single().map { it.first.product }).containsExactly("codex", "claude_code")
	}

	@Test
	@DisplayName("영수증이 덮지 않은 resource 도 적재한다 — archive_ref 없이 archive_receipt_missing")
	fun uncoveredResourcesLoadWithoutArchive() {
		var flags: List<QualityFlag> = emptyList()
		val real = observationPipeline()
		val pipeline = pipeline(normalize = { request, context ->
			real.run(request, context).also { batch -> flags = batch.events.flatMap { it.envelope.qualityFlags } }
		})

		pipeline.consume(Signal.LOGS, oneApiRequest(), receipt(documents = emptyList()))

		assertThat(flags).contains(QualityFlag.ARCHIVE_RECEIPT_MISSING)
		assertThat(operations.loaded.single().single().first.archived).isNull()
	}

	// ------------------------------------------------------------------ 도구

	private fun observationPipeline() = ObservationPipeline(ObservationNormalizer(ProfileRegistry(listOf(ClaudeCodeProfile))))

	private fun pipeline(
		startupAttempts: Int = 1,
		normalize: (Message, ObservationContext) -> ObservationBatch = observationPipeline()::run,
	): IngestPipeline {
		val client = ClickHouseHttpClient("http://127.0.0.1:${server.address.port}")
		val schema = ClickHouseSchema(ClickHouseSchemaMigrator(client), startupAttempts = startupAttempts, startupBackoff = Duration.ZERO)
		if (startupAttempts > 0) schema.afterPropertiesSet()
		val enricher = ObservationEnricher(installations, memberships, listOf(GithubProvider(), JiraProvider(), AiAnalysisProvider()))
		return IngestPipeline(normalize, enricher, TelemetryEventsSink(client), TelemetryMetricPointsSink(client), schema, operations)
	}

	private class RecordingOperations : IngestOperations {
		val loaded = mutableListOf<List<Pair<ReceiptPart, NormalizationStats>>>()
		val unloaded = mutableListOf<List<ReceiptPart>>()
		var failure: RuntimeException? = null

		override fun loaded(receipt: ArchiveReceipt, parts: List<Pair<ReceiptPart, NormalizationStats>>) {
			failure?.let { throw it }
			loaded += parts
		}

		override fun unloaded(receipt: ArchiveReceipt, parts: List<ReceiptPart>) {
			unloaded += parts
		}
	}

	/** Claude Code 2.1.282 의 api_request 하나 — 등록된 프로파일이 사용량 행으로 만든다. */
	private fun oneApiRequest(): ExportLogsServiceRequest = ExportLogsServiceRequest.newBuilder()
		.addResourceLogs(resource("claude-code", "claude_code.api_request"))
		.build()

	private fun twoResources(): ExportLogsServiceRequest = ExportLogsServiceRequest.newBuilder()
		.addResourceLogs(resource("claude-code", "claude_code.api_request"))
		.addResourceLogs(resource("codex-app-server", "codex.user_prompt"))
		.build()

	private fun resource(service: String, body: String) = io.opentelemetry.proto.logs.v1.ResourceLogs.newBuilder().apply {
		resourceBuilder.addAttributes(attribute("service.name", service)).addAttributes(attribute("service.version", "2.1.282"))
		addScopeLogsBuilder().addLogRecordsBuilder().apply {
			val now = Instant.now()
			timeUnixNano = now.epochSecond * 1_000_000_000L + now.nano
			bodyBuilder.stringValue = body
			addAttributes(attribute("event.name", body.substringAfter('.')))
			addAttributes(attribute("session.id", "unit-session"))
		}
	}.build()

	private fun attribute(key: String, value: String): KeyValue = KeyValue.newBuilder()
		.setKey(key)
		.setValue(AnyValue.newBuilder().setStringValue(value))
		.build()

	private fun receipt(
		signal: Signal = Signal.LOGS,
		documents: List<ArchivedDocument> = listOf(ArchivedDocument(Product.CLAUDE_CODE, ArchivedObject("s3://raw/claude_code/logs/a.json"), listOf(0))),
	): ArchiveReceipt = ArchiveReceipt(
		receiptId = "00000000-0000-4000-8000-000000000000",
		receivedAt = Instant.parse("2026-01-01T00:00:05Z"),
		signal = signal,
		tenantId = tenant,
		installationId = installation.toString(),
		maskingVersion = "masking-v2",
		identityVersion = "stamp-v1",
		documents = documents,
	)

	private fun ArchiveReceipt.copyWithoutIdentity() = ArchiveReceipt(
		receiptId, receivedAt, signal, tenantId = null, installationId = null, maskingVersion, identityVersion, documents,
	)
}
