package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.collector.VerifiedIngestContext
import com.team376.pulsemetry.telemetry.config.KafkaConsumerConfig
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestTestData
import com.team376.pulsemetry.telemetry.support.IngestTestKafka
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.producer.RecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.ConsumerPostProcessor
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ingest 의 Kafka Consumer(ADR 0066 Decision 6)를 실제 Kafka·PostgreSQL·ClickHouse 위에서 본다. HTTP 테스트와 같은 dual 모드 컨텍스트다.
 *
 * 테스트가 Gateway 대신 같은 codec 으로 envelope 를 발행한다. 실패는 수집 코어(spy)의 호출을 message ID 로 골라 흉내 낸다 —
 * 재시도 간격은 테스트 설정(100·200·300ms)이고 횟수(최초 + 3)와 순서는 운영과 같다.
 */
@ExtendWith(OutputCaptureExtension::class)
class TelemetryKafkaConsumerTest : AbstractIngestIntegrationTest() {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var data: IngestTestData

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	@Qualifier(KafkaConsumerConfig.CONTAINER_BEAN)
	private lateinit var container: ConcurrentMessageListenerContainer<ByteArray, ByteArray>

	@Autowired
	private lateinit var consumerFactory: DefaultKafkaConsumerFactory<ByteArray, ByteArray>

	@Autowired
	private lateinit var registry: MeterRegistry

	private val http: HttpClient = HttpClient.newHttpClient()

	/** 수집 코어가 성공적으로 끝낸 message ID 의 순서. */
	private val completed: MutableList<UUID> = Collections.synchronizedList(mutableListOf())

	@BeforeEach
	fun reset() {
		data.clear()
		completed.clear()
		if (!container.isRunning) container.start()
	}

	@AfterEach
	fun cleanUp() {
		if (!container.isRunning) container.start()
		data.clear()
	}

	// ------------------------------------------------------------------ 정상

	@Test
	@DisplayName("독립 smoke client의 Claude payload는 FINAL 논리 행 하나와 기대 토큰·archive 참조가 된다")
	fun smokePayloadPersistsExpectedLogicalRow() {
		val seeded = data.seedJwt()
		val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
		val template = javaClass.getResourceAsStream("/observability-smoke-log.json")!!.bufferedReader().use { it.readText() }
		val body = template.replace("2026-10-21T15:00:00+00:00", now.toString())
			.replace("1792594800000000000", (now.epochSecond * 1_000_000_000 + now.nano).toString()).toByteArray()
		awaitCommitted(IngestTestKafka.publish(IngestTestKafka.accepted(seeded, body)))
		val row = clickHouse("""
			SELECT count(), any(event_type), any(tokens_input), any(tokens_output), countIf(notEmpty(ifNull(archive_ref, ''))), any(observation_id)
			FROM telemetry_events FINAL
			WHERE tenant_id = '${seeded.tenantId}' AND installation_id = '${seeded.installationId}'
			AND session_id = '19100000-0000-4000-8000-000000000001'
			AND request_id = 'req_smoke_19100000000040008000000000000001' FORMAT TSV
		""".trimIndent()).trim().split('\t')
		assertThat(row).containsExactly("1", "model.response.usage", "1066", "28", "1", SmokePayloadContractTest.expectedId(body, seeded.tenantId.toString(), seeded.installationId.toString()))
	}

	@Test
	@DisplayName("정상 record 는 분석 행이 되고 receipt ID·수신 시각·row version 이 message ID·접수 시각에서 온다")
	fun processesWithAcceptedReceipt() {
		val before = count("pulsemetry.pipeline.attempts", "outcome", "completed")
		val acceptedAt = Instant.now().minusSeconds(120)
		// 접수 당시 유효했던 토큰이어야 한다 — 판정은 DB 의 발급 시각과 접수 시각으로 한다.
		val seeded = data.seedJwt(issuedAt = acceptedAt.minusSeconds(60))
		val message = IngestTestKafka.accepted(seeded, oneUserPrompt(), acceptedAt)

		val metadata = IngestTestKafka.publish(message)
		awaitCommitted(metadata)

		val ledger = clickHouse(
			"SELECT receipt_id, toString(received_time) FROM telemetry_ingest_ledger FINAL WHERE tenant_id = '${seeded.tenantId}' FORMAT TSV",
		).trim().split('\t')
		assertThat(ledger[0]).isEqualTo(message.messageId.toString())
		assertThat(Instant.parse(ledger[1].replace(' ', 'T') + "Z")).isEqualTo(acceptedAt)

		val row = clickHouse(
			"SELECT tenant_id, installation_id, member_id, row_version FROM telemetry_events FINAL WHERE tenant_id = '${seeded.tenantId}' FORMAT TSV",
		).trim().split('\t')
		assertThat(row[0]).isEqualTo(seeded.tenantId.toString())
		assertThat(row[1]).isEqualTo(seeded.installationId.toString())
		assertThat(row[2]).isEqualTo(seeded.memberId.toString())
		assertThat(row[3].toULong()).isEqualTo(RowVersioning.live(acceptedAt).rowVersion)
		assertThat(count("pulsemetry.pipeline.attempts", "outcome", "completed") - before).isEqualTo(1.0)
		assertThat(registry.get("pulsemetry.pipeline.accepted.to.completion").tags("signal", "logs").timer().count()).isPositive()
		assertThat(registry.meters.any { it.id.name.startsWith("kafka.consumer.") }).isTrue()
	}

	@Test
	@DisplayName("빈 OTLP 는 아무것도 쓰지 않고 offset 을 확정한다")
	fun emptyRequestCommits() {
		val before = count("pulsemetry.pipeline.empty")
		val rowsBefore = count("pulsemetry.clickhouse.rows.submitted")
		val seeded = data.seedJwt()
		val message = IngestTestKafka.accepted(seeded, """{"resourceLogs":[]}""".toByteArray())

		awaitCommitted(IngestTestKafka.publish(message))

		assertThat(processCalls(message.messageId)).isEqualTo(1)
		assertThat(clickHouse("SELECT count() FROM telemetry_ingest_ledger WHERE tenant_id = '${seeded.tenantId}'").trim()).isEqualTo("0")
		assertThat(count("pulsemetry.pipeline.empty") - before).isEqualTo(1.0)
		assertThat(count("pulsemetry.clickhouse.rows.submitted")).isEqualTo(rowsBefore)
	}

	// ------------------------------------------------------------------ 영구 실패

	@Test
	@DisplayName("잘못된 envelope·RDB 거부·디코딩 실패·파이프라인 영구 실패는 로그 후 확정하고 재시도하지 않는다")
	fun permanentFailuresCommit(output: CapturedOutput) {
		val garbage = IngestTestKafka.send("garbage", byteArrayOf(0x0a, 0xff.toByte(), 0x01))
		val tombstone = IngestTestKafka.send("tombstone", null)

		val unknown = IngestTestKafka.accepted(data.seedJwt(), oneUserPrompt()).let {
			it.copyWith(auth = it.auth.copy(credentialId = UUID.randomUUID()))
		}
		val rejected = IngestTestKafka.publish(unknown)

		val corrupt = IngestTestKafka.accepted(data.seedJwt(), "not gzip".toByteArray(), contentEncoding = "gzip")
		val corruptAt = IngestTestKafka.publish(corrupt)

		val pipeline = IngestTestKafka.accepted(data.seedJwt(), oneUserPrompt())
		failProcessing(pipeline.messageId, times = 99) { PermanentIngestException("schema mismatch") }
		val pipelineAt = IngestTestKafka.publish(pipeline)

		listOf(garbage, tombstone, rejected, corruptAt, pipelineAt).forEach { awaitCommitted(it) }

		assertThat(output.all).contains("event=telemetry_permanent_failure messageId=- topic=${IngestTestKafka.TOPIC} partition=${garbage.partition()} offset=${garbage.offset()} stage=envelope reason=envelope_unparseable attempt=1")
		assertThat(output.all).contains("stage=envelope reason=envelope_unparseable attempt=1").contains("offset=${tombstone.offset()}")
		assertThat(output.all).contains("event=telemetry_permanent_failure messageId=${unknown.messageId}").contains("stage=authorize reason=credential_unknown attempt=1")
		assertThat(output.all).contains("event=telemetry_permanent_failure messageId=${corrupt.messageId}").contains("stage=decode reason=decompress_failed attempt=1")
		assertThat(output.all).contains("event=telemetry_permanent_failure messageId=${pipeline.messageId}").contains("stage=pipeline reason=pipeline_rejected attempt=1")
		// RDB 가 거부한 record 는 수집 코어(디코딩·마스킹·아카이브)에 닿지 않는다.
		assertThat(processCalls(unknown.messageId)).isZero()
		assertThat(processCalls(corrupt.messageId)).isEqualTo(1)
		assertThat(processCalls(pipeline.messageId)).isEqualTo(1)
	}

	@Test
	@DisplayName("재시도마다 RDB 를 다시 본다 — 첫 시도 뒤 보안 폐기되면 다음 시도가 거부하고 확정한다")
	fun reauthorizesEveryAttempt(output: CapturedOutput) {
		val seeded = data.seedJwt()
		val message = IngestTestKafka.accepted(seeded, oneUserPrompt())
		failProcessing(message.messageId, times = 1) {
			jdbc.update(
				"UPDATE enrollment.telemetry_tokens SET revoked_at = COALESCE(revoked_at, now()), revoke_reason = 'security' WHERE id = ?",
				seeded.credentialId,
			)
			TelemetrySinkUnavailableException("simulated clickhouse outage")
		}

		awaitCommitted(IngestTestKafka.publish(message))

		assertThat(processCalls(message.messageId)).isEqualTo(1)
		assertThat(output.all).contains("event=telemetry_permanent_failure messageId=${message.messageId}")
			.contains("stage=authorize reason=credential_security_revoked attempt=2")
	}

	// ------------------------------------------------------------------ 일시 실패

	@Test
	@DisplayName("일시 실패는 최초 + 3회 시도 뒤 retry_exhausted 로 확정하고 같은 partition 의 다음 record 로 넘어간다")
	fun exhaustsThenMovesOn(output: CapturedOutput) {
		val failedBefore = count("pulsemetry.pipeline.attempts", "outcome", "transient_failed")
		val completedBefore = count("pulsemetry.pipeline.attempts", "outcome", "completed")
		val exhaustedBefore = count("pulsemetry.pipeline.retry.exhausted")
		val seeded = data.seedJwt()
		val first = IngestTestKafka.accepted(seeded, oneUserPrompt())
		val second = IngestTestKafka.accepted(seeded, oneUserPrompt())
		failProcessing(first.messageId, times = 99) { TelemetrySinkUnavailableException("simulated clickhouse outage") }

		val firstAt = IngestTestKafka.publish(first)
		val secondAt = IngestTestKafka.publish(second)
		assertThat(secondAt.partition()).isEqualTo(firstAt.partition())

		awaitCommitted(secondAt)

		assertThat(processCalls(first.messageId)).isEqualTo(4)
		assertThat(output.all).contains(
			"event=telemetry_retry_exhausted messageId=${first.messageId} topic=${IngestTestKafka.TOPIC} " +
				"partition=${firstAt.partition()} offset=${firstAt.offset()} stage=process reason=unavailable attempt=4 cause=TelemetrySinkUnavailableException",
		)
		assertThat(completed).containsExactly(second.messageId)
		assertThat(count("pulsemetry.pipeline.attempts", "outcome", "transient_failed") - failedBefore).isEqualTo(4.0)
		assertThat(count("pulsemetry.pipeline.attempts", "outcome", "completed") - completedBefore).isEqualTo(1.0)
		assertThat(count("pulsemetry.pipeline.retry.exhausted") - exhaustedBefore).isEqualTo(1.0)
		listOf(1, 2, 3, 4).forEach { attempt ->
			assertThat(output.all).contains("messageId=${first.messageId}").contains("stage=process reason=unavailable attempt=$attempt cause=TelemetrySinkUnavailableException")
		}
	}

	@Test
	@DisplayName("예외 종류가 바뀌어도 예산은 이어진다 — 합쳐 4회다")
	fun exceptionChangeDoesNotResetBudget() {
		val seeded = data.seedJwt()
		val message = IngestTestKafka.accepted(seeded, oneUserPrompt())
		val failures = listOf(
			TelemetrySinkUnavailableException("a"), IllegalStateException("b"), DataAccessResourceFailureException("c"), RuntimeException("d"),
			TelemetrySinkUnavailableException("e"),
		)
		failProcessing(message.messageId, times = 99) { failures[it] }

		awaitCommitted(IngestTestKafka.publish(message))

		assertThat(processCalls(message.messageId)).isEqualTo(4)
	}

	@Test
	@DisplayName("partition 안의 순서를 지킨다 — 앞 record 가 재시도로 성공하기 전에 뒤 record 를 처리하지 않는다")
	fun keepsPartitionOrder() {
		val seeded = data.seedJwt()
		val first = IngestTestKafka.accepted(seeded, oneUserPrompt())
		val second = IngestTestKafka.accepted(seeded, oneUserPrompt())
		failProcessing(first.messageId, times = 2) { TelemetrySinkUnavailableException("simulated") }

		IngestTestKafka.publish(first)
		awaitCommitted(IngestTestKafka.publish(second))

		assertThat(completed).containsExactly(first.messageId, second.messageId)
		assertThat(processCalls(first.messageId)).isEqualTo(3)
	}

	@Test
	@DisplayName("재시도 뒤 성공해도 receipt ID·접수 시각·row version 은 첫 시도와 같다 — 시도마다 새로 만들지 않는다")
	fun retriesKeepReceipt() {
		val acceptedAt = Instant.now().minus(10, ChronoUnit.MINUTES)
		val seeded = data.seedJwt(issuedAt = acceptedAt.minusSeconds(60))
		val message = IngestTestKafka.accepted(seeded, oneUserPrompt(), acceptedAt)
		failProcessing(message.messageId, times = 1) { TelemetrySinkUnavailableException("simulated") }

		awaitCommitted(IngestTestKafka.publish(message))

		val contexts = processContexts(message.messageId)
		assertThat(contexts).hasSize(2)
		assertThat(contexts.map { it.receiptId }.toSet()).containsExactly(message.messageId)
		assertThat(contexts.map { it.receivedAt }.toSet()).containsExactly(acceptedAt)
		val rowVersion = clickHouse("SELECT row_version FROM telemetry_events FINAL WHERE tenant_id = '${seeded.tenantId}' FORMAT TSV").trim()
		assertThat(rowVersion.toULong()).isEqualTo(RowVersioning.live(acceptedAt).rowVersion)
	}

	@Test
	@DisplayName("재시도 대기 중 컨테이너를 멈추면 확정하지 않고, 다시 시작하면 같은 record 를 처리한다 — health 는 그동안 503")
	fun stopDuringRetryRedelivers() {
		val seeded = data.seedJwt()
		val message = IngestTestKafka.accepted(seeded, oneUserPrompt())
		val secondAttempt = CountDownLatch(1)
		val release = CountDownLatch(1)
		val attempts = AtomicInteger()
		stubProcessing(message.messageId) {
			when (attempts.incrementAndGet()) {
				1 -> throw TelemetrySinkUnavailableException("simulated")
				2 -> {
					secondAttempt.countDown()
					release.await(20, TimeUnit.SECONDS)
					throw TelemetrySinkUnavailableException("simulated")
				}
				else -> null
			}
		}

		val metadata = IngestTestKafka.publish(message)
		assertThat(secondAttempt.await(20, TimeUnit.SECONDS)).isTrue()
		val stopping = Thread { container.stop() }.also { it.start() }
		Thread.sleep(300)
		release.countDown()
		stopping.join(20_000)

		assertThat(container.isRunning).isFalse()
		assertThat(health()).isEqualTo(503)
		assertThat(IngestTestKafka.committed(metadata.partition())).isLessThanOrEqualTo(metadata.offset())

		container.start()
		awaitCommitted(metadata)
		assertThat(health()).isEqualTo(200)
		assertThat(completed).containsExactly(message.messageId)
		assertThat(attempts.get()).isGreaterThanOrEqualTo(3)
	}

	@Test
	@DisplayName("offset commit 이 실패하면 성공이 아니다 — 같은 record 를 다시 처리하고 commit_failed 를 남긴다")
	fun commitFailureRedelivers(output: CapturedOutput) {
		val before = count("pulsemetry.pipeline.commit.failures")
		// 할당 직후의 초기 위치 commit 을 건드리지 않도록 할당이 끝난 뒤에 실패를 하나 장전한다.
		val failures = AtomicInteger(0)
		val postProcessor = ConsumerPostProcessor<ByteArray, ByteArray> { failingCommits(it, failures) }
		container.stop()
		consumerFactory.addPostProcessor(postProcessor)
		try {
			container.start()
			awaitAssigned()
			failures.set(1)
			val seeded = data.seedJwt()
			val message = IngestTestKafka.accepted(seeded, oneUserPrompt())

			awaitCommitted(IngestTestKafka.publish(message))

			assertThat(processCalls(message.messageId)).isEqualTo(2)
			assertThat(count("pulsemetry.pipeline.commit.failures") - before).isEqualTo(1.0)
			assertThat(output.all).contains("event=telemetry_commit_failed").contains("stage=commit reason=commit_failed")
				.contains("cause=CommitFailedException")
		} finally {
			container.stop()
			consumerFactory.removePostProcessor(postProcessor)
			container.start()
		}
	}

	@Test
	@DisplayName("payload·예외 메시지의 카나리아 문자열은 로그에 남지 않는다")
	fun canaryNeverLogged(output: CapturedOutput) {
		val canary = "CANARYKAFKA" + UUID.randomUUID().toString().replace("-", "")
		val seeded = data.seedJwt()
		val transient = IngestTestKafka.accepted(seeded, oneUserPrompt(extra = canary))
		failProcessing(transient.messageId, times = 99) { TelemetrySinkUnavailableException("insert failed near '$canary'") }
		val broken = IngestTestKafka.accepted(seeded, "{\"$canary\":".toByteArray())

		IngestTestKafka.publish(transient)
		awaitCommitted(IngestTestKafka.publish(broken))
		IngestTestKafka.send(canary, canary.toByteArray()).also { awaitCommitted(it) }

		assertThat(output.all).contains("event=telemetry_retry_exhausted messageId=${transient.messageId}")
		assertThat(output.all).doesNotContain(canary)
		assertThat(registry.meters.flatMap { it.id.tags }.joinToString()).doesNotContain(canary)
	}

	// ------------------------------------------------------------------ 도구

	/** [messageId] 의 처리만 [times] 번 실패시킨다. 나머지는 실제 수집 코어를 지나며 성공 순서를 [completed] 에 남긴다. */
	private fun failProcessing(messageId: UUID, times: Int, failure: (Int) -> RuntimeException) {
		val attempts = AtomicInteger()
		stubProcessing(messageId) {
			val attempt = attempts.getAndIncrement()
			if (attempt < times) throw failure(attempt)
			null
		}
	}

	/** [messageId] 의 호출마다 [behavior] 를 먼저 부른다. 예외를 던지면 그 실패, 그렇지 않으면 실제 처리다. */
	private fun stubProcessing(messageId: UUID, behavior: () -> Unit?) {
		doAnswer { invocation ->
			val context = invocation.getArgument<VerifiedIngestContext>(1)
			if (context.receiptId == messageId) behavior()
			invocation.callRealMethod()
			completed += context.receiptId
			null
		}.`when`(otlpProcessor).process(anything<RawOtlpPayload>(), anything<VerifiedIngestContext>())
	}

	private fun processContexts(messageId: UUID): List<VerifiedIngestContext> =
		mockingDetails(otlpProcessor).invocations
			.filter { it.method.name == "process" }
			.map { it.getArgument<VerifiedIngestContext>(1) }
			.filter { it.receiptId == messageId }

	private fun processCalls(messageId: UUID): Int = processContexts(messageId).size

	/** 공유 컨텍스트의 누적 counter 합. 테스트는 전후 차이만 본다. */
	private fun count(name: String, vararg tags: String): Double = registry.find(name).tags(*tags).counters().sumOf { it.count() }

	private fun awaitCommitted(metadata: RecordMetadata, timeout: Duration = Duration.ofSeconds(30)) {
		val until = System.nanoTime() + timeout.toNanos()
		while (System.nanoTime() < until) {
			if (IngestTestKafka.committed(metadata.partition()) >= metadata.offset() + 1) return
			Thread.sleep(100)
		}
		throw AssertionError("offset ${metadata.partition()}@${metadata.offset()} 가 $timeout 안에 확정되지 않았다")
	}

	private fun awaitAssigned() {
		val until = System.nanoTime() + Duration.ofSeconds(30).toNanos()
		while (System.nanoTime() < until) {
			if (container.assignedPartitions.orEmpty().size == IngestTestKafka.PARTITIONS) {
				Thread.sleep(500)
				return
			}
			Thread.sleep(100)
		}
		throw AssertionError("partition 할당을 기다리다 시간이 지났다")
	}

	private fun health(): Int = http.send(
		HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/healthz")).GET().build(),
		HttpResponse.BodyHandlers.discarding(),
	).statusCode()

	/** commitSync 를 [failures] 번 실패시키는 consumer 대리. 나머지 호출은 실제 consumer 로 넘긴다. */
	@Suppress("UNCHECKED_CAST")
	private fun failingCommits(delegate: Consumer<ByteArray, ByteArray>, failures: AtomicInteger): Consumer<ByteArray, ByteArray> =
		Proxy.newProxyInstance(Consumer::class.java.classLoader, arrayOf(Consumer::class.java)) { _, method, args ->
			if (method.name == "commitSync" && failures.getAndDecrement() > 0) throw CommitFailedException()
			try {
				method.invoke(delegate, *(args ?: emptyArray()))
			} catch (e: InvocationTargetException) {
				throw e.targetException
			}
		} as Consumer<ByteArray, ByteArray>

	private fun oneUserPrompt(extra: String = "kafka"): ByteArray {
		val now = Instant.now()
		val nanos = now.epochSecond * 1_000_000_000L + now.nano
		return """
			{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"claude-code"}}]},
			 "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"claude_code.user_prompt"},
			   "attributes":[{"key":"session.id","value":{"stringValue":"$extra-session"}},{"key":"prompt_length","value":{"intValue":"7"}}]}]}]}]}
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
}

/** 테스트에서 인증 문맥만 바꾼 사본을 만든다. */
private fun com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry.copyWith(
	auth: com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext,
) = com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry(messageId, acceptedAt, auth, payload)
