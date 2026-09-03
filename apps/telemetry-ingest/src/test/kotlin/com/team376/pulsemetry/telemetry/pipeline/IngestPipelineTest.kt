package com.team376.pulsemetry.telemetry.pipeline

import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetry.EnrichedEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.enricher.Enricher
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
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * 적재 실패가 어떤 처분으로 나가는지 고정한다 (허브 ADR 0006).
 *
 * ClickHouse 대신 상태 코드를 마음대로 돌려주는 스텁 서버를 쓴다 — 판정 대상이 상태 코드의
 * 처분이지 ClickHouse 의 동작이 아니다. `OrgProvider` 는 넣지 않는다(RDS 가 필요하다).
 */
class IngestPipelineTest {

	private lateinit var server: HttpServer
	private var status: Int = 200
	private val requests = mutableListOf<String>()

	@BeforeEach
	fun startStub() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			requests += exchange.requestURI.query.orEmpty()
			val bytes = "".toByteArray(StandardCharsets.UTF_8)
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stopStub() {
		server.stop(0)
	}

	@Test
	@DisplayName("ClickHouse 4xx 는 PermanentIngestException 이 된다 — 수집 진입점이 400 으로 돌린다")
	fun aRejectedInsertBecomesPermanent() {
		val pipeline = pipeline()
		status = 400

		assertThatThrownBy { pipeline.consume(Signal.LOGS, oneUserPrompt()) }
			.isInstanceOf(PermanentIngestException::class.java)
			.hasMessageContaining("clickhouse 400")
	}

	@Test
	@DisplayName("ClickHouse 5xx 는 그대로 전파된다 — 일시 장애라 503 이 된다")
	fun anUnavailableInsertPropagates() {
		val pipeline = pipeline()
		status = 503

		assertThatThrownBy { pipeline.consume(Signal.LOGS, oneUserPrompt()) }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
	}

	@Test
	@DisplayName("스키마가 아직이면 적재 전에 다시 적용한다 — 테이블 없이 INSERT 하면 404 로 폐기된다")
	fun theSchemaGateRunsBeforeTheInsert() {
		// 기동 시도를 0회로 두어 "적용 못 한 채 뜬" 상태를 만든다.
		val pipeline = pipeline(startupAttempts = 0)
		requests.clear()

		pipeline.consume(Signal.LOGS, oneUserPrompt())

		// 첫 요청이 DDL 이고 그다음이 INSERT 다. 순서가 뒤집히면 테이블 없는 INSERT 가 나간다.
		assertThat(requests.first()).contains("CREATE+TABLE").doesNotContain("INSERT")
		assertThat(requests.last()).contains("INSERT")
	}

	@Test
	@DisplayName("정규화되는 이벤트가 없으면 적재하지 않는다 — 아카이브는 이미 남았다")
	fun anEmptyNormalizationSkipsTheSink() {
		val pipeline = pipeline()
		requests.clear()

		pipeline.consume(Signal.LOGS, ExportLogsServiceRequest.getDefaultInstance())

		assertThat(requests).isEmpty()
	}

	// ------------------------------------------------------------------ 도구

	private fun pipeline(startupAttempts: Int = 1): IngestPipeline {
		val client = ClickHouseHttpClient("http://127.0.0.1:${server.address.port}")
		val schema = ClickHouseSchema(
			ClickHouseSchemaMigrator(client),
			startupAttempts = startupAttempts,
			startupBackoff = Duration.ZERO,
		)
		if (startupAttempts > 0) schema.afterPropertiesSet()
		return IngestPipeline(
			enricher = Enricher(listOf(GithubProvider(), JiraProvider(), AiAnalysisProvider())),
			sink = EnrichedEventsSink(client),
			schema = schema,
		)
	}

	private fun oneUserPrompt(): ExportLogsServiceRequest {
		val now = Instant.now()
		return ExportLogsServiceRequest.newBuilder().apply {
			addResourceLogsBuilder().apply {
				resourceBuilder.addAttributes(attribute("service.name", "claude-code"))
				addScopeLogsBuilder().addLogRecordsBuilder().apply {
					timeUnixNano = now.epochSecond * 1_000_000_000L + now.nano
					bodyBuilder.stringValue = "claude_code.user_prompt"
					addAttributes(attribute("session.id", "unit-session"))
				}
			}
		}.build()
	}

	private fun attribute(key: String, value: String): KeyValue = KeyValue.newBuilder()
		.setKey(key)
		.setValue(AnyValue.newBuilder().setStringValue(value))
		.build()
}
