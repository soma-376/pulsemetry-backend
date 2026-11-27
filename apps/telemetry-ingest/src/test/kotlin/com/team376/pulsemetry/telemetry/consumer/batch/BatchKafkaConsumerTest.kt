package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import com.team376.pulsemetry.telemetry.config.KafkaConsumerConfig
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestTestData
import com.team376.pulsemetry.telemetry.support.IngestTestKafka
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.RecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.isRegularFile

/**
 * 배치 모드(PROJ-341 · ADR 0083)를 실제 Kafka·PostgreSQL·ClickHouse 위에서 본다 — Spring 컨테이너의 `AckMode.BATCH`·배치 fallback·
 * 커밋까지. 기본 컨텍스트(dual·단건)와 섞이지 않게 topic·group 을 따로 쓰므로 이 클래스는 컨텍스트를 하나 더 띄운다.
 */
class BatchKafkaConsumerTest : AbstractIngestIntegrationTest() {

	@Autowired
	private lateinit var data: IngestTestData

	@Autowired
	@Qualifier(KafkaConsumerConfig.CONTAINER_BEAN)
	private lateinit var container: ConcurrentMessageListenerContainer<ByteArray, ByteArray>

	@Autowired
	private lateinit var registry: MeterRegistry

	private val http: HttpClient = HttpClient.newHttpClient()

	@BeforeEach
	fun reset() {
		data.clear()
		if (!container.isRunning) container.start()
	}

	@AfterEach
	fun cleanUp() {
		reset(telemetryEvents)
		data.clear()
	}

	@Test
	@DisplayName("배치 컨테이너는 AckMode.BATCH 로 서고, 30건을 레코드 수보다 적은 분석 INSERT 와 RDB 묶음 조회로 적재해 확정한다")
	fun loadsInBatches() {
		assertThat(container.containerProperties.ackMode).isEqualTo(ContainerProperties.AckMode.BATCH)
		val seeded = data.seedJwt()
		val inserts = inserts()
		clearInvocations(telemetryTokens, installations)

		val published = (0 until 30).map { IngestTestKafka.publishTo(TOPIC, IngestTestKafka.accepted(seeded, prompt("batch-$it"))) }
		awaitCommitted(published)

		assertThat(clickHouse("SELECT count() FROM telemetry_events FINAL WHERE tenant_id = '${seeded.tenantId}' FORMAT TSV").trim()).isEqualTo("30")
		assertThat(clickHouse("SELECT count() FROM telemetry_ingest_ledger FINAL WHERE tenant_id = '${seeded.tenantId}' FORMAT TSV").trim()).isEqualTo("30")
		assertThat(inserts() - inserts).isGreaterThan(0.0).isLessThan(30.0)
		// ADR 0084 — 배치 경로는 자격·구성원을 호출마다 IN 조회로 묶는다. 단건 조회는 쓰지 않고, 묶음 조회는 레코드 수보다 적다.
		assertThat(calls(telemetryTokens, "findCredentialRowById")).isZero()
		assertThat(calls(telemetryTokens, "findCredentialRowsByIds")).isGreaterThan(0).isLessThan(30)
		assertThat(calls(installations, "findMemberIdById")).isZero()
		assertThat(calls(installations, "findMemberIdsByIds")).isGreaterThan(0).isLessThan(30)
	}

	@Test
	@DisplayName("ClickHouse 장애가 단기 예산을 넘기면 S3 넘김 목록을 쓰고 확정한다. 복구 뒤 탐침이 차단기를 닫고 다시 적재한다")
	fun clickHouseOutageHandsOff() {
		val seeded = data.seedJwt()
		doThrow(TelemetrySinkUnavailableException("simulated clickhouse outage")).`when`(telemetryEvents).send(anything(), anything())

		val published = (0 until 5).map { IngestTestKafka.publishTo(TOPIC, IngestTestKafka.accepted(seeded, prompt("handoff-$it"))) }
		awaitCommitted(published)

		val handedOff = handoffFiles().flatMap { file ->
			val records = JsonMapper.builder().build().readTree(Files.readAllBytes(file))["records"]
			(0 until records.size()).map { records[it]["receipt"]["tenantId"].asString() to records[it]["messageId"].asString() }
		}.filter { it.first == seeded.tenantId.toString() }
		assertThat(handedOff).hasSize(5)
		assertThat(clickHouse("SELECT count() FROM telemetry_events FINAL WHERE tenant_id = '${seeded.tenantId}' FORMAT TSV").trim()).isEqualTo("0")

		// 복구 — 탐침 차례(테스트 1초 ±10%)가 지나면 SELECT 1 과 첫 쓰기로 닫고 적재한다. 그 전에 온 배치는 열린 차단기로 넘긴다.
		reset(telemetryEvents)
		Thread.sleep(1_500)
		val recovered = IngestTestKafka.publishTo(TOPIC, IngestTestKafka.accepted(seeded, prompt("after-recovery")))
		awaitCommitted(listOf(recovered))
		awaitRows(seeded.tenantId, 1)
	}

	private fun handoffFiles(): List<Path> {
		val root = Path.of(System.getProperty("pulsemetry.telemetry.archive.dir")).resolve("handoff").resolve(GROUP).resolve(TOPIC)
		if (!Files.exists(root)) return emptyList()
		return Files.walk(root).use { paths -> paths.filter { it.isRegularFile() && it.toString().endsWith(".json") }.toList() }
	}

	private fun calls(spy: Any, method: String): Int = mockingDetails(spy).invocations.count { it.method.name == method }

	private fun inserts(): Double = registry.find("pulsemetry.clickhouse.requests").tags("operation", "insert", "table", "telemetry_events").counters().sumOf { it.count() }

	private fun awaitCommitted(published: List<RecordMetadata>, timeout: Duration = Duration.ofSeconds(60)) {
		val last = published.groupBy { it.partition() }.mapValues { (_, metadata) -> metadata.maxOf { it.offset() } }
		val until = System.nanoTime() + timeout.toNanos()
		while (System.nanoTime() < until) {
			if (last.all { (partition, offset) -> IngestTestKafka.committed(GROUP, TOPIC, partition) >= offset + 1 }) return
			Thread.sleep(100)
		}
		throw AssertionError("$last 가 $timeout 안에 확정되지 않았다")
	}

	private fun awaitRows(tenant: UUID, expected: Int, timeout: Duration = Duration.ofSeconds(30)) {
		val until = System.nanoTime() + timeout.toNanos()
		while (System.nanoTime() < until) {
			if (clickHouse("SELECT count() FROM telemetry_events FINAL WHERE tenant_id = '$tenant' FORMAT TSV").trim().toInt() >= expected) return
			Thread.sleep(100)
		}
		throw AssertionError("$tenant 의 행이 $timeout 안에 $expected 개가 되지 않았다")
	}

	private fun prompt(session: String): ByteArray {
		val now = Instant.now()
		val nanos = now.epochSecond * 1_000_000_000L + now.nano
		return """
			{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"claude-code"}}]},
			 "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"claude_code.user_prompt"},
			   "attributes":[{"key":"session.id","value":{"stringValue":"$session"}},{"key":"prompt_length","value":{"intValue":"7"}}]}]}]}]}
		""".trimIndent().toByteArray()
	}

	private fun clickHouse(query: String): String {
		val uri = URI.create(clickHouseUrl() + "/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
		val response = http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
		check(response.statusCode() < 400) { "clickhouse ${response.statusCode()}: ${response.body()}" }
		return response.body()
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> anything(): T {
		ArgumentMatchers.any<T>()
		return null as T
	}

	companion object {
		const val TOPIC: String = "pulsemetry-test-telemetry-ingress-batch"
		const val GROUP: String = "pulsemetry-test-telemetry-ingest-batch"

		/** 배치 모드 컨텍스트. topic 을 컨테이너가 서기 전에 만든다 — 파티션 2개로 여러 파티션 배치를 본다. */
		@JvmStatic
		@DynamicPropertySource
		fun batchProperties(registry: DynamicPropertyRegistry) {
			IngestTestKafka.createTopic(TOPIC, partitions = 2)
			registry.add("pulsemetry.telemetry.kafka.topic") { TOPIC }
			registry.add("pulsemetry.telemetry.kafka.consumer-group") { GROUP }
			registry.add("pulsemetry.telemetry.kafka.consumer.batch.enabled") { "true" }
			registry.add("pulsemetry.telemetry.kafka.consumer.batch.fetch-max-wait") { "300ms" }
			registry.add("pulsemetry.telemetry.kafka.consumer.batch.long-wait") { "2s" }
			registry.add("pulsemetry.telemetry.kafka.consumer.batch.breaker-probe-interval") { "1s" }
			registry.add("pulsemetry.telemetry.kafka.consumer.batch.progress-interval") { "50ms" }
		}
	}
}
