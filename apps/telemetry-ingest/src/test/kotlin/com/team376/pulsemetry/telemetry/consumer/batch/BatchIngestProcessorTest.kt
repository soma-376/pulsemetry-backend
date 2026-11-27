package com.team376.pulsemetry.telemetry.consumer.batch

import com.google.protobuf.Message
import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationMemberRow
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetry.IngestLedgerSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryMetricPointsSink
import com.team376.pulsemetry.persistence.telemetryops.SummaryOrigin
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationNormalizer
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationPipeline
import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import com.team376.pulsemetry.telemetry.collector.OtlpIngestProcessor
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.SignalConsumer
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.ArchivedObject
import com.team376.pulsemetry.telemetry.collector.archive.Product
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties.Batch.PartialDefectPolicy
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties.Batch.SingleRecordFailurePolicy
import com.team376.pulsemetry.telemetry.credential.AuthorizedTelemetryCredential
import com.team376.pulsemetry.telemetry.credential.CredentialDecision
import com.team376.pulsemetry.telemetry.credential.CredentialRequest
import com.team376.pulsemetry.telemetry.credential.TelemetryCredentialAuthorizer
import com.team376.pulsemetry.telemetry.enricher.observation.ObservationEnricher
import com.team376.pulsemetry.telemetry.enricher.provider.AiAnalysisProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.provider.JiraProvider
import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import com.team376.pulsemetry.telemetry.pipeline.ClickHouseSchema
import com.team376.pulsemetry.telemetry.pipeline.IngestOperationsRecorder
import com.team376.pulsemetry.telemetry.pipeline.IngestStages
import com.team376.pulsemetry.telemetry.pipeline.RetentionBoundaries
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.ResourceLogs
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willReturn
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessResourceUsageException
import org.springframework.dao.NonTransientDataAccessResourceException
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Kafka 배치 경로(PROJ-341)의 묶음·이분할·재시도·S3 넘김·차단기를 고정한다.
 *
 * ClickHouse 대신 본문을 보고 상태·오류 코드를 고르는 스텁 서버를 쓰고, 인가·요약 저장은 대역이다. 실제 Kafka·Spring 오류 처리기를 거친
 * 동작은 `BatchKafkaConsumerTest` 가 본다. 여기서는 리스너를 직접 불러 Spring 의 배치 fallback 이 같은 메모리 배치를 다시 부르는 상황을 흉내 낸다.
 */
class BatchIngestProcessorTest {

	private lateinit var server: HttpServer

	/** 성공한 INSERT 의 (테이블, 줄 수). */
	private val inserted: MutableList<Pair<String, List<String>>> = Collections.synchronizedList(mutableListOf())

	/** 분석 테이블 INSERT 시도 수(성공·실패 모두). */
	private val analysisAttempts = AtomicInteger()
	private val selects = AtomicInteger()

	/** 본문을 보고 실패를 고른다. null 이면 성공. */
	@Volatile
	private var failWith: (table: String, body: String) -> StubFailure? = { _, _ -> null }

	@Volatile
	private var selectFails = false

	private val archive = MemoryArchive()
	private val handoffs: MutableMap<String, ByteArray> = Collections.synchronizedMap(linkedMapOf())

	@Volatile
	private var handoffFails = false

	/** 남은 인가 실패 횟수 — 인증 DB 장애를 묶음 판정 호출 그 횟수만큼 흉내 낸다. */
	private val authFailuresLeft = AtomicInteger()

	/** 묶음 판정 호출마다 받은 요청. */
	private val authorizeCalls: MutableList<List<CredentialRequest>> = Collections.synchronizedList(mutableListOf())

	/** 이 자격은 판정에서 보안 폐기로 거부한다 — 재시도 사이의 폐기를 흉내 낸다. */
	private val revokedCredentials: MutableSet<UUID> = Collections.synchronizedSet(mutableSetOf())

	private val summaries: TenantIngestSummaryStore = mock(TenantIngestSummaryStore::class.java)
	private val authorizer: TelemetryCredentialAuthorizer = mock(TelemetryCredentialAuthorizer::class.java)

	/** 보강의 구성원·소속 저장소 대역. 스텁이 없으면 빈 결과라 모든 설치가 미해결로 보강된다. */
	private val installations: InstallationRepository = mock(InstallationRepository::class.java)
	private val memberships: TeamMembershipRepository = mock(TeamMembershipRepository::class.java)

	/** 정규화를 실패시킬 요청을 고른다. */
	@Volatile
	private var normalizeFails: (Message) -> Boolean = { false }
	private val state = BatchDeliveryState()
	private val executor = Executors.newVirtualThreadPerTaskExecutor()
	@Suppress("UNCHECKED_CAST")
	private val consumer: Consumer<ByteArray, ByteArray> = mock(Consumer::class.java) as Consumer<ByteArray, ByteArray>

	data class StubFailure(val status: Int, val code: Int?, val message: String = "simulated")

	@BeforeEach
	fun start() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val query = exchange.requestURI.rawQuery.split('&').first { it.startsWith("query=") }.removePrefix("query=")
				.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
			val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
			val table = Regex("""^INSERT INTO (\S+)""").find(query)?.groupValues?.get(1)
			val failure = when {
				query.startsWith("SELECT 1") -> {
					selects.incrementAndGet()
					if (selectFails) StubFailure(503, null) else null
				}
				table != null -> {
					if (table != LEDGER) analysisAttempts.incrementAndGet()
					failWith(table, body)
				}
				else -> null
			}
			if (failure == null) {
				if (table != null) inserted += table to body.lines().filter { it.isNotEmpty() }
				val response = if (query.startsWith("SELECT 1")) "1\n" else ""
				exchange.sendResponseHeaders(200, response.length.toLong().takeIf { it > 0 } ?: -1)
				if (response.isNotEmpty()) exchange.responseBody.use { it.write(response.toByteArray()) }
			} else {
				failure.code?.let { exchange.responseHeaders.add("X-ClickHouse-Exception-Code", it.toString()) }
				val bytes = "Code: ${failure.code}. DB::Exception: ${failure.message}".toByteArray()
				exchange.sendResponseHeaders(failure.status, bytes.size.toLong())
				exchange.responseBody.use { it.write(bytes) }
			}
			exchange.close()
		}
		server.start()
		given(authorizer.authorizeAll(anything())).willAnswer { invocation ->
			val requests = invocation.getArgument<List<CredentialRequest>>(0)
			authorizeCalls += requests
			if (authFailuresLeft.getAndDecrement() > 0) throw IllegalStateException("rds down")
			requests.map { (auth, _) ->
				if (auth.credentialId in revokedCredentials) {
					CredentialDecision.Rejected("credential_security_revoked")
				} else {
					CredentialDecision.Authorized(AuthorizedTelemetryCredential(auth.credentialId, auth.tenantId, auth.installationId, UUID.randomUUID()))
				}
			}
		}
	}

	@AfterEach
	fun stop() {
		server.stop(0)
		executor.close()
	}

	// --- 정상 ---

	@Test
	@DisplayName("고객 1명의 20건은 분석 INSERT 1회·ledger 1회·요약 1회로 끝나고 완료 표시가 남는다")
	fun oneTenantBatch() {
		val tenant = UUID.randomUUID()
		val records = batch((0 until 20).map { record(tenant) })

		listener().onMessage(records, null, consumer)

		assertThat(analysisAttempts.get()).isEqualTo(1)
		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(20)
		assertThat(inserted.count { it.first == LEDGER }).isEqualTo(1)
		verify(summaries, times(1)).record(anything<UUID>(), anything<SummaryOrigin>(), anything<Instant>(), anything<Instant>(), anything<Instant?>())
		assertThat(state.takeCompleted(records)).isTrue()
		assertThat(archive.puts.get()).isEqualTo(20)
	}

	// --- 자격 확인(ADR 0084) ---

	@Test
	@DisplayName("자격 확인은 호출당 묶음 판정 한 번이다 — 레코드 순서대로 자기 접수 시각을 넘기고 단건 판정은 부르지 않는다")
	fun authorizesOncePerCall() {
		val messages = (0 until 20).map { record(UUID.randomUUID()) }
		val records = batch(messages)

		listener().onMessage(records, null, consumer)

		assertThat(authorizeCalls).hasSize(1)
		// envelope 의 iat·exp 는 초 단위라 claim 전체가 아니라 자격 ID·신원·접수 시각을 맞춘다.
		assertThat(authorizeCalls.single().map { listOf(it.auth.credentialId, it.auth.tenantId, it.auth.installationId, it.acceptedAt) })
			.containsExactlyElementsOf(messages.map { listOf(it.auth.credentialId, it.auth.tenantId, it.auth.installationId, it.acceptedAt) })
		verify(authorizer, times(1)).authorizeAll(anything())
		verify(authorizer, never()).authorize(anything(), anything())
		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(20)
	}

	@Test
	@DisplayName("재호출마다 미완료 레코드의 자격을 다시 판정한다 — 그 사이 폐기된 레코드만 거부하고 나머지를 끝낸다")
	fun reauthorizesOnEveryCall() {
		val firstWrite = AtomicBoolean(true)
		failWith = { table, _ -> if (table == TelemetryEventsSink.TABLE && firstWrite.getAndSet(false)) StubFailure(503, null) else null }
		val tenant = UUID.randomUUID()
		val messages = (0 until 3).map { record(tenant) }
		val records = batch(messages)
		val listener = listener()

		assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(ClickHouseBatchFailure::class.java)
		state.takeDelay(-1)
		revokedCredentials += messages[1].auth.credentialId
		listener.onMessage(records, null, consumer)

		assertThat(authorizeCalls).hasSize(2)
		assertThat(authorizeCalls).allSatisfy { assertThat(it.map { request -> request.auth.credentialId }).containsExactlyElementsOf(messages.map { m -> m.auth.credentialId }) }
		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(2)
		assertThat(rows(LEDGER)).describedAs("자격 거부는 ledger 에 남기지 않는다").hasSize(2)
		assertThat(state.takeCompleted(records)).isTrue()
	}

	@Test
	@DisplayName("고객 3명이 섞이면 분석 INSERT 는 고객 수만큼, ledger 는 한 번이다")
	fun insertsPerTenant() {
		val tenants = List(3) { UUID.randomUUID() }
		val records = batch((0 until 12).map { record(tenants[it % 3]) })

		listener().onMessage(records, null, consumer)

		assertThat(analysisAttempts.get()).isEqualTo(3)
		assertThat(inserted.count { it.first == LEDGER }).isEqualTo(1)
		verify(summaries, times(3)).record(anything<UUID>(), anything<SummaryOrigin>(), anything<Instant>(), anything<Instant>(), anything<Instant?>())
	}

	// --- 입력 결함 ---

	@Test
	@DisplayName("100건 중 137번 한 건만 입력 결함(코드 27)이면 최대 15회로 격리하고 정상 99건을 모두 저장한다")
	fun bisectsOneDefect() {
		val tenant = UUID.randomUUID()
		failWith = { table, body -> if (table == TelemetryEventsSink.TABLE && POISON in body) StubFailure(400, 27) else null }
		val records = batch((0 until 100).map { record(tenant, poison = it == 36) }, firstOffset = 101)

		listener().onMessage(records, null, consumer)

		assertThat(analysisAttempts.get()).isLessThanOrEqualTo(15)
		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(99).noneMatch { POISON in it }
		val ledger = rows(LEDGER)
		assertThat(ledger).hasSize(100)
		assertThat(ledger.count { """"rejected_count":1""" in it && """"record_count":1""" in it }).isGreaterThanOrEqualTo(1)
		assertThat(state.takeCompleted(records)).isTrue()
		assertThat(handoffs).isEmpty()
	}

	@Test
	@DisplayName("HTTP 500 으로 오는 입력 결함(코드 131)도 코드로 판정해 이분할한다")
	fun bisectsServerStatusDefect() {
		val tenant = UUID.randomUUID()
		failWith = { table, body -> if (table == TelemetryEventsSink.TABLE && POISON in body) StubFailure(500, 131) else null }
		val records = batch((0 until 8).map { record(tenant, poison = it == 0 || it == 7) })

		listener().onMessage(records, null, consumer)

		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(6)
		assertThat(state.takeCompleted(records)).isTrue()
	}

	@Test
	@DisplayName("허용 목록 밖 4xx 는 레코드 결함으로 단정하지 않고 공통 장애로 재시도한다")
	fun unlistedClientErrorIsCommon() {
		failWith = { table, _ -> if (table == TelemetryEventsSink.TABLE) StubFailure(404, 60) else null }
		val records = batch((0 until 4).map { record(UUID.randomUUID()) })

		val thrown = catchThrowable { listener().onMessage(records, null, consumer) }

		assertThat(thrown).isInstanceOf(ClickHouseBatchFailure::class.java)
		assertThat(rows(LEDGER)).isEmpty()
		assertThat(state.takeDelay(-1)).isEqualTo(1_000)
	}

	// --- ClickHouse 장애 · 넘김 · 차단기 ---

	@Test
	@DisplayName("ClickHouse 장애는 1·5·30초를 기다린 뒤 넷째 실패에서 S3 넘김 목록을 쓰고 확정한다 — 개별 거부 0건")
	fun clickHouseOutageHandsOff() {
		failWith = { table, _ -> if (table != null) StubFailure(503, null) else null }
		selectFails = true
		val tenant = UUID.randomUUID()
		val records = batch((0 until 10).map { record(tenant) }, firstOffset = 51)
		val listener = listener()

		val delays = (1..3).map {
			assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(ClickHouseBatchFailure::class.java)
			state.takeDelay(-1)
		}
		listener.onMessage(records, null, consumer)

		assertThat(delays).containsExactly(1_000L, 5_000L, 30_000L)
		assertThat(state.takeCompleted(records)).isTrue()
		assertThat(archive.puts.get()).describedAs("성공한 PUT 은 재호출에서 다시 하지 않는다").isEqualTo(10)
		val (key, body) = handoffs.entries.single()
		assertThat(key).isEqualTo(HandoffList.key(GROUP, TOPIC, listOf(PartitionRange(0, 51, 60)))).matches("handoff/$GROUP/$TOPIC/[0-9a-f]{64}\\.json")
		val document = JsonMapper.builder().build().readTree(body)
		assertThat(document["records"].size()).isEqualTo(10)
		val first = document["records"][0]
		assertThat(first["kafka"]["offset"].asLong()).isEqualTo(51)
		assertThat(first["receipt"]["receivedAt"].asString()).isEqualTo(ACCEPTED_AT.toString())
		assertThat(first["documents"][0]["uri"].asString()).startsWith("mem://")
		assertThat(document["reason"]["trigger"].asString()).isEqualTo("budget_exhausted")
		assertThat(rows(LEDGER)).isEmpty()
	}

	@Test
	@DisplayName("열린 차단기는 ClickHouse 쓰기 없이 넘기고, 탐침 차례에 SELECT 1 과 첫 쓰기가 성공하면 닫는다")
	fun breakerSkipsClickHouseUntilProbe() {
		failWith = { table, _ -> if (table != null) StubFailure(503, null) else null }
		selectFails = true
		val listener = listener(settings(probeInterval = Duration.ofHours(1)))
		val first = batch((0 until 3).map { record(UUID.randomUUID()) })
		repeat(3) { catchThrowable { listener.onMessage(first, null, consumer) } }
		listener.onMessage(first, null, consumer)
		val attemptsWhenOpened = analysisAttempts.get()

		val second = batch((0 until 3).map { record(UUID.randomUUID()) }, firstOffset = 10)
		listener.onMessage(second, null, consumer)

		assertThat(analysisAttempts.get()).describedAs("열린 동안 ClickHouse 쓰기 0회").isEqualTo(attemptsWhenOpened)
		assertThat(handoffs).hasSize(2)
		assertThat(state.takeCompleted(second)).isTrue()

		val probing = listener(settings(probeInterval = Duration.ofNanos(1)))
		failWith = { _, _ -> null }
		selectFails = false
		val third = batch((0 until 3).map { record(UUID.randomUUID()) }, firstOffset = 20)
		// 새 리스너의 소비 스레드 차단기는 닫혀 있으므로, 같은 처리기의 차단기를 직접 연다.
		probing.processorForTest().breaker().open(HandoffReason("budget_exhausted", "insert", "x", null, null), "test")
		Thread.sleep(5)
		probing.onMessage(third, null, consumer)

		assertThat(probing.processorForTest().breaker().isOpen).isFalse()
		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(3)
	}

	@Test
	@DisplayName("넘김 목록 쓰기가 실패하면 넘기지 않고 기다린다 — 커밋 0건")
	fun handoffWriteFailureWaits() {
		failWith = { table, _ -> if (table != null) StubFailure(503, null) else null }
		selectFails = true
		handoffFails = true
		val records = batch((0 until 2).map { record(UUID.randomUUID()) })
		val listener = listener()
		repeat(3) { catchThrowable { listener.onMessage(records, null, consumer) } }

		val thrown = catchThrowable { listener.onMessage(records, null, consumer) }

		assertThat(thrown).isInstanceOf(DependencyBatchFailure::class.java)
		assertThat((thrown as BatchIncompleteException).stage).isEqualTo("handoff")
		assertThat(state.takeCompleted(records)).isFalse()
		assertThat(handoffs).isEmpty()
	}

	// --- 넘길 수 없는 장애 ---

	@Test
	@DisplayName("S3 장애는 넘기지 않고 1·5·30초 뒤 긴 대기다. 복구되면 실패했던 PUT 만 다시 하고 끝낸다")
	fun archiveOutageWaits() {
		archive.failing = { it % 2 == 1 }
		val tenant = UUID.randomUUID()
		val records = batch((0 until 6).map { record(tenant) })
		val listener = listener()

		val delays = (1..4).map {
			assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(DependencyBatchFailure::class.java)
			// 첫 호출은 홀수 번째 PUT 만 실패하고, 그 뒤로는 모두 실패한다.
			archive.failing = { true }
			state.takeDelay(-1)
		}
		archive.failing = { false }
		listener.onMessage(records, null, consumer)

		assertThat(delays).containsExactly(1_000L, 5_000L, 30_000L, Duration.ofMinutes(5).toMillis())
		assertThat(handoffs).isEmpty()
		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(6)
		assertThat(archive.succeeded.get()).describedAs("성공한 문서는 한 번만 PUT 한다").isEqualTo(6)
	}

	@Test
	@DisplayName("인증 DB 장애는 넘기지 않고 기다린다")
	fun authorizationOutageWaits() {
		willThrow(IllegalStateException("rds down")).given(authorizer).authorizeAll(anything())
		val records = batch((0 until 3).map { record(UUID.randomUUID()) })

		val thrown = catchThrowable { listener().onMessage(records, null, consumer) }

		assertThat(thrown).isInstanceOf(DependencyBatchFailure::class.java)
		assertThat((thrown as BatchIncompleteException).stage).isEqualTo("authorize")
		assertThat(archive.puts.get()).isZero()
		assertThat(rows(LEDGER)).describedAs("장애는 어느 레코드의 거부도 아니다").isEmpty()
		assertThat(state.takeCompleted(records)).isFalse()
		verify(authorizer, times(1)).authorizeAll(anything())
	}

	// --- 한 건 반복 실패 · 메모리 진단 ---

	@Test
	@DisplayName("다른 조각은 저장되는데 한 레코드만 반복 실패하면 그 한 건만 넘기고 나머지를 끝낸다(팀 결정 4번 기본값)")
	fun singleRecordFailureHandsOffOnlyThatRecord() {
		val tenant = UUID.randomUUID()
		failWith = { table, body -> if (table == TelemetryEventsSink.TABLE && POISON in body) StubFailure(503, null) else null }
		// 상한을 작게 둬 레코드마다 INSERT 하나가 되게 한다.
		val listener = listener(settings(maxInsertBytes = 1024))
		val records = batch((0 until 5).map { record(tenant, poison = it == 2) })

		repeat(3) { assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(ClickHouseBatchFailure::class.java) }
		listener.onMessage(records, null, consumer)

		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(4)
		val document = JsonMapper.builder().build().readTree(handoffs.values.single())
		assertThat(document["records"].size()).isEqualTo(1)
		assertThat(document["records"][0]["kafka"]["offset"].asLong()).isEqualTo(2)
		assertThat(listener.processorForTest().breaker().isOpen).describedAs("한 건 문제로 차단기를 열지 않는다").isFalse()
		assertThat(rows(LEDGER)).hasSize(4)
	}

	@Test
	@DisplayName("한 건 반복 실패 정책이 보류면 넘기지 않고 긴 대기다")
	fun singleRecordFailureHeld() {
		val tenant = UUID.randomUUID()
		failWith = { table, body -> if (table == TelemetryEventsSink.TABLE && POISON in body) StubFailure(503, null) else null }
		val listener = listener(settings(maxInsertBytes = 1024, singleRecord = SingleRecordFailurePolicy.HOLD))
		val records = batch((0 until 3).map { record(tenant, poison = it == 1) })
		repeat(3) { catchThrowable { listener.onMessage(records, null, consumer) } }

		val thrown = catchThrowable { listener.onMessage(records, null, consumer) }

		assertThat(thrown).isInstanceOf(HeldBatchFailure::class.java)
		assertThat(state.takeDelay(-1)).isEqualTo(Duration.ofMinutes(5).toMillis())
		assertThat(handoffs).isEmpty()
		assertThat(state.takeCompleted(records)).isFalse()
	}

	@Test
	@DisplayName("다른 장애로 단기 예산을 다 쓴 뒤 한 건이 한 번 실패하면 그 건을 단정하지 않고 공통 장애로 넘긴다")
	fun singleFailureAfterOtherOutagesIsNotIsolated() {
		val tenant = UUID.randomUUID()
		authFailuresLeft.set(3)
		failWith = { table, body -> if (table == TelemetryEventsSink.TABLE && POISON in body) StubFailure(503, null) else null }
		val listener = listener(settings(maxInsertBytes = 1024))
		val records = batch((0 until 3).map { record(tenant, poison = it == 1) })

		repeat(3) { assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(DependencyBatchFailure::class.java) }
		listener.onMessage(records, null, consumer)

		val document = JsonMapper.builder().build().readTree(handoffs.values.single())
		assertThat(document["reason"]["trigger"].asString()).isEqualTo("budget_exhausted")
		assertThat(document["records"].size()).isEqualTo(3)
		assertThat(listener.processorForTest().breaker().isOpen).isTrue()
	}

	@Test
	@DisplayName("보류한 레코드는 다른 배치 때문에 차단기가 열려도 넘기지 않고 계속 기다린다")
	fun heldRecordIsNotHandedOffWhileBreakerOpen() {
		val tenant = UUID.randomUUID()
		failWith = { table, body -> if (table == TelemetryEventsSink.TABLE && POISON in body) StubFailure(503, null) else null }
		val listener = listener(settings(maxInsertBytes = 1024, singleRecord = SingleRecordFailurePolicy.HOLD))
		val records = batch((0 until 3).map { record(tenant, poison = it == 1) })
		repeat(3) { catchThrowable { listener.onMessage(records, null, consumer) } }
		assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(HeldBatchFailure::class.java)
		listener.processorForTest().breaker().open(HandoffReason("budget_exhausted", "insert", "x", null, null), "test")

		val thrown = catchThrowable { listener.onMessage(records, null, consumer) }

		assertThat(thrown).isInstanceOf(HeldBatchFailure::class.java)
		assertThat(handoffs).isEmpty()
		assertThat(state.takeCompleted(records)).isFalse()
	}

	@Test
	@DisplayName("넘김 목록의 ledger 완료는 실제로 쓴 경우만이다 — 같은 배치의 다음 목록에 다시 실려도 그대로다")
	fun handoffListKeepsActualLedgerState() {
		val tenant = UUID.randomUUID()
		val poisonFails = AtomicBoolean(true)
		failWith = { table, body ->
			when {
				table == LEDGER -> StubFailure(503, null)
				table == TelemetryEventsSink.TABLE && POISON in body && poisonFails.get() -> StubFailure(503, null)
				else -> null
			}
		}
		val listener = listener(settings(maxInsertBytes = 1024, singleRecord = SingleRecordFailurePolicy.HOLD, probeInterval = Duration.ofNanos(1)))
		val records = batch((0 until 2).map { record(tenant, poison = it == 1) })
		repeat(3) { catchThrowable { listener.onMessage(records, null, consumer) } }

		// 넷째 호출: 1번은 보류, 0번은 ledger 가 실패해 첫 목록에 오른다.
		assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(HeldBatchFailure::class.java)
		val first = JsonMapper.builder().build().readTree(handoffs.values.single())
		assertThat(first["records"].size()).isEqualTo(1)
		assertThat(first["records"][0]["completed"]["ledger"].asBoolean()).isFalse()

		// 다섯째 호출: 탐침 뒤 1번은 적재되지만 ledger 가 또 실패해 같은 key 의 목록에 0번과 함께 다시 오른다.
		poisonFails.set(false)
		listener.onMessage(records, null, consumer)

		val second = JsonMapper.builder().build().readTree(handoffs.values.single())
		assertThat(second["records"].size()).isEqualTo(2)
		assertThat((0 until 2).map { second["records"][it]["completed"]["ledger"].asBoolean() }).containsOnly(false)
	}

	@Test
	@DisplayName("아카이브를 기다리다 인터럽트되면 시작한 PUT 이 끝난 뒤에 던지고, 재호출은 같은 문서를 다시 PUT 하지 않는다")
	fun archiveInterruptWaitsForStartedPuts() {
		archive.delay = Duration.ofMillis(300)
		val records = batch((0 until 8).map { record(UUID.randomUUID()) })
		val listener = listener()
		val first = AtomicReference<Throwable?>()
		val inFlightAtThrow = AtomicInteger(-1)
		val completed = AtomicBoolean(false)
		// 작업 캐시는 소비 스레드마다 하나라 두 호출을 같은 스레드에서 부른다.
		val thread = Thread {
			first.set(catchThrowable { listener.onMessage(records, null, consumer) })
			inFlightAtThrow.set(archive.inFlight.get())
			Thread.interrupted() // Spring 이 재호출 전에 인터럽트를 처리했다고 본다.
			listener.onMessage(records, null, consumer)
			completed.set(state.takeCompleted(records))
		}
		thread.start()
		Thread.sleep(100)
		thread.interrupt()
		thread.join(10_000)

		assertThat(first.get()).isInstanceOf(DependencyBatchFailure::class.java)
		assertThat(inFlightAtThrow.get()).describedAs("던질 때 진행 중인 PUT 이 없다").isZero()
		assertThat(archive.maxInFlight.get()).isLessThanOrEqualTo(4)
		assertThat(archive.puts.get()).describedAs("같은 문서를 두 번 PUT 하지 않는다").isEqualTo(8)
		assertThat(completed.get()).isTrue()
	}

	@Test
	@DisplayName("큰 쓰기만 메모리 한도(241)로 실패하면 넷째 호출에서 작은 직렬 쓰기로 복구한다 — 넘김 0건")
	fun memoryLimitDiagnosisRecovers() {
		val tenant = UUID.randomUUID()
		failWith = { table, body ->
			if (table == TelemetryEventsSink.TABLE && body.lines().count { it.isNotEmpty() } > 4) StubFailure(500, 241, "Memory limit (for query) exceeded") else null
		}
		val listener = listener()
		val records = batch((0 until 16).map { record(tenant) })

		repeat(3) { assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(ClickHouseBatchFailure::class.java) }
		listener.onMessage(records, null, consumer)

		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(16)
		assertThat(handoffs).isEmpty()
		assertThat(listener.processorForTest().breaker().isOpen).isFalse()
	}

	@Test
	@DisplayName("작은 조각들도 같은 메모리 오류를 내면 공통 장애로 차단기를 열고 넘긴다")
	fun memoryLimitEverywhereOpensBreaker() {
		failWith = { table, _ -> if (table == TelemetryEventsSink.TABLE) StubFailure(500, 241, "Memory limit (total) exceeded") else null }
		val listener = listener()
		val tenant = UUID.randomUUID()
		val records = batch((0 until 8).map { record(tenant) })

		repeat(3) { catchThrowable { listener.onMessage(records, null, consumer) } }
		listener.onMessage(records, null, consumer)

		assertThat(listener.processorForTest().breaker().isOpen).isTrue()
		val document = JsonMapper.builder().build().readTree(handoffs.values.single())
		assertThat(document["records"].size()).isEqualTo(8)
		assertThat(document["reason"]["memoryScope"].asString()).isEqualTo("total")
	}

	@Test
	@DisplayName("메모리 한도 진단 중 작은 조각이 입력 결함(117)을 내면 그 레코드만 거부하고 차단기를 열지 않는다")
	fun memoryDiagnosisIsolatesInputDefect() {
		val tenant = UUID.randomUUID()
		failWith = { table, body ->
			when {
				table != TelemetryEventsSink.TABLE -> null
				body.lines().count { it.isNotEmpty() } > 4 -> StubFailure(500, 241, "Memory limit (for query) exceeded")
				POISON in body -> StubFailure(400, 117)
				else -> null
			}
		}
		val listener = listener()
		val records = batch((0 until 16).map { record(tenant, poison = it == 5) })

		repeat(3) { assertThat(catchThrowable { listener.onMessage(records, null, consumer) }).isInstanceOf(ClickHouseBatchFailure::class.java) }
		listener.onMessage(records, null, consumer)

		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(15).noneMatch { POISON in it }
		assertThat(rows(LEDGER)).hasSize(16)
		assertThat(handoffs).isEmpty()
		assertThat(listener.processorForTest().breaker().isOpen).isFalse()
	}

	// --- 마감 ---

	@Test
	@DisplayName("마감에 닿아도 진척이 있으면 장애 횟수를 늘리지 않고 짧게 기다린다")
	fun deadlineWithProgress() {
		// 시작 가능 창 100ms — 첫 PUT 들은 그 안에 시작하고, 창보다 긴 PUT 뒤의 변환은 마감에 닿는다.
		val listener = listener(settings(callDeadline = Duration.ofSeconds(2), minRemaining = Duration.ofMillis(1900)))
		archive.delay = Duration.ofMillis(300)
		val records = batch((0 until 3).map { record(UUID.randomUUID()) })

		val thrown = catchThrowable { listener.onMessage(records, null, consumer) }

		assertThat(thrown).isInstanceOf(BatchDeadlineReached::class.java)
		assertThat((thrown as BatchDeadlineReached).progressed).isTrue()
		assertThat(state.takeDelay(-1)).isEqualTo(200)
	}

	// --- 변환: 정규화는 레코드마다, 보강 조회는 호출마다(PROJ-345 · ADR 0084) ---

	@Test
	@DisplayName("보강의 구성원·소속 조회는 호출마다 묶음 한 번이다 — 단건 조회는 쓰지 않고, 재호출은 다시 읽는다")
	fun enrichmentLooksUpOncePerCall() {
		val tenant = UUID.randomUUID()
		val records = batch((0 until 5).map { record(tenant) })

		listener().onMessage(records, null, consumer)

		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(5)
		assertThat(state.takeCompleted(records)).isTrue()
		verify(installations, times(1)).findMemberIdsByIds(anyCollection())
		verify(installations, never()).findMemberIdById(anything())
		// 묶음 조회의 결과가 비었다 — 구성원이 없으니 소속은 읽지 않는다.
		verify(memberships, never()).findAllByMemberIdIn(anyCollection())

		listener().onMessage(batch((0 until 3).map { record(tenant) }, firstOffset = 5), null, consumer)
		verify(installations, times(2)).findMemberIdsByIds(anyCollection())
	}

	@Test
	@DisplayName("정규화 실패는 그 레코드만 거부하고 나머지는 한 번에 보강해 적재한다")
	fun normalizationFailureRejectsOnlyThatRecord() {
		val tenant = UUID.randomUUID()
		normalizeFails = { POISON in it.toString() }
		val records = batch((0 until 5).map { record(tenant, poison = it == 2) })

		listener().onMessage(records, null, consumer)

		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(4).noneMatch { POISON in it }
		assertThat(rows(LEDGER)).hasSize(5)
		assertThat(state.takeCompleted(records)).isTrue()
		verify(installations, times(1)).findMemberIdsByIds(anyCollection())
	}

	@Test
	@DisplayName("보강 영구 오류는 레코드별 보강으로 되돌아가 그 레코드만 거부한다 — 나머지는 적재하고 기다리지 않는다")
	fun permanentEnrichmentFailureFallsBackPerRecord() {
		val tenant = UUID.randomUUID()
		val messages = (0 until 4).map { record(tenant) }
		val poisoned = messages[1].auth.installationId
		willThrow(InvalidDataAccessResourceUsageException("column gone")).given(installations).findMemberIdsByIds(anyCollection())
		willThrow(InvalidDataAccessResourceUsageException("column gone")).given(installations).findMemberIdById(poisoned)
		val records = batch(messages)

		listener().onMessage(records, null, consumer)

		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(3).noneMatch { poisoned.toString() in it }
		assertThat(rows(LEDGER)).hasSize(4)
		assertThat(state.takeCompleted(records)).isTrue()
		assertThat(handoffs).isEmpty()
		verify(installations, times(1)).findMemberIdsByIds(anyCollection())
		messages.forEach { verify(installations, times(1)).findMemberIdById(it.auth.installationId) }
	}

	@Test
	@DisplayName("보강 일시 장애는 거부하지 않고 기다린다 — 재호출은 정규화부터 다시 하고, 아카이브는 다시 하지 않는다")
	fun transientEnrichmentFailureWaits() {
		val tenant = UUID.randomUUID()
		val records = batch((0 until 3).map { record(tenant) })
		val listener = listener()

		// 연결 실패(보강이 EnrichmentUnavailableException 으로 감싼다)와, 감싸지 않은 자원 계열 오류 둘 다 일시 장애다.
		for (failure in listOf(DataAccessResourceFailureException("rds down"), NonTransientDataAccessResourceException("rds gone"))) {
			willThrow(failure).given(installations).findMemberIdsByIds(anyCollection())

			val thrown = catchThrowable { listener.onMessage(records, null, consumer) }

			assertThat(thrown).isInstanceOf(DependencyBatchFailure::class.java)
			assertThat((thrown as BatchIncompleteException).stage).isEqualTo("enrich")
			assertThat(rows(TelemetryEventsSink.TABLE)).isEmpty()
			assertThat(rows(LEDGER)).isEmpty()
			state.takeDelay(-1)
		}
		willReturn(emptyList<InstallationMemberRow>()).given(installations).findMemberIdsByIds(anyCollection())
		listener.onMessage(records, null, consumer)

		assertThat(rows(TelemetryEventsSink.TABLE)).hasSize(3)
		assertThat(state.takeCompleted(records)).isTrue()
		assertThat(archive.puts.get()).isEqualTo(3)
		verify(installations, times(3)).findMemberIdsByIds(anyCollection())
		verify(installations, never()).findMemberIdById(anything())
	}

	// --- 시험 도구 ---

	private fun settings(
		maxInsertBytes: Long = 10_000_000,
		probeInterval: Duration = Duration.ofMinutes(5),
		singleRecord: SingleRecordFailurePolicy = SingleRecordFailurePolicy.HANDOFF,
		callDeadline: Duration = Duration.ofSeconds(60),
		minRemaining: Duration = Duration.ofSeconds(1),
	) = BatchSettings(
		topic = TOPIC,
		consumerGroup = GROUP,
		retryIntervals = listOf(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)),
		maxInsertBytes = maxInsertBytes,
		archiveConcurrency = 4,
		callDeadline = callDeadline,
		minRemainingToStart = minRemaining,
		progressInterval = Duration.ofMillis(200),
		longWait = Duration.ofMinutes(5),
		breakerProbeInterval = probeInterval,
		probeTimeout = Duration.ofSeconds(2),
		maxDiagnosticWrites = 32,
		partialDefect = PartialDefectPolicy.REJECT,
		singleRecordFailure = singleRecord,
	)

	private val processors = mutableMapOf<TelemetryBatchListener, BatchIngestProcessor>()

	private fun TelemetryBatchListener.processorForTest(): BatchIngestProcessor = processors.getValue(this)

	private fun listener(settings: BatchSettings = settings()): TelemetryBatchListener {
		val client = ClickHouseHttpClient("http://127.0.0.1:${server.address.port}")
		val schema = ClickHouseSchema(ClickHouseSchemaMigrator(client), startupAttempts = 1, startupBackoff = Duration.ZERO).also { it.afterPropertiesSet() }
		val enricher = ObservationEnricher(installations, memberships, listOf(GithubProvider(), JiraProvider(), AiAnalysisProvider()))
		val normalizer = ObservationPipeline(ObservationNormalizer(ProfileRegistry(listOf(ClaudeCodeProfile))))
		val stages = IngestStages({ request, context -> if (normalizeFails(request)) error("unreadable") else normalizer.run(request, context) }, enricher)
		val processor = BatchIngestProcessor(
			authorizer = authorizer,
			preparer = OtlpIngestProcessor(archive, SignalConsumer { _, _, _ -> error("배치 경로는 다음 단계를 직접 부르지 않는다") }),
			archive = archive,
			stages = stages,
			schema = schema,
			boundaries = RetentionBoundaries { AnalysisWriteBoundary(it, deletedBefore = null, policyEpoch = 0) },
			events = TelemetryEventsSink(client),
			metricPoints = TelemetryMetricPointsSink(client),
			operations = IngestOperationsRecorder(IngestLedgerSink(client), summaries),
			handoffs = HandoffListWriter { key, body ->
				if (handoffFails) error("s3 down")
				handoffs[key] = body
				"mem://$key"
			},
			probe = { client.execute("SELECT 1", operation = "select", requestTimeout = Duration.ofSeconds(2)) },
			settings = settings,
			archiveExecutor = executor,
		)
		return TelemetryBatchListener(processor, state, settings).also { processors[it] = processor }
	}

	/** Mockito 의 `any()` 는 null 을 돌려줘 Kotlin 의 non-null 매개변수 검사에 걸린다 — matcher 만 등록한다. */
	@Suppress("UNCHECKED_CAST")
	private fun <T> anything(): T {
		ArgumentMatchers.any<T>()
		return null as T
	}

	private fun rows(table: String): List<String> = synchronized(inserted) { inserted.filter { it.first == table }.flatMap { it.second } }

	private fun record(tenant: UUID, poison: Boolean = false): AcceptedTelemetry = AcceptedTelemetry(
		messageId = UUID.randomUUID(),
		acceptedAt = ACCEPTED_AT,
		auth = GatewayAuthContext(
			credentialId = UUID.randomUUID(),
			tenantId = tenant,
			installationId = UUID.randomUUID(),
			issuer = "pulsemetry-enrollment",
			keyId = "k1",
			issuedAt = ACCEPTED_AT.minusSeconds(60),
			expiresAt = ACCEPTED_AT.plusSeconds(3600),
		),
		payload = RawOtlpPayload(OtlpSignal.LOGS, OtlpMediaType.PROTOBUF, "", logs(if (poison) POISON else "s-${UUID.randomUUID()}").toByteArray()),
	)

	private fun batch(messages: List<AcceptedTelemetry>, firstOffset: Long = 0): ConsumerRecords<ByteArray, ByteArray> {
		val partition = TopicPartition(TOPIC, 0)
		val records = messages.mapIndexed { index, message ->
			ConsumerRecord(TOPIC, 0, firstOffset + index, "k".toByteArray(), TelemetryEnvelopeCodec.encode(message))
		}
		return ConsumerRecords(mapOf(partition to records), emptyMap())
	}

	private fun logs(session: String): ExportLogsServiceRequest = ExportLogsServiceRequest.newBuilder()
		.addResourceLogs(
			ResourceLogs.newBuilder().apply {
				resourceBuilder.addAttributes(attribute("service.name", "claude-code")).addAttributes(attribute("service.version", "2.1.282"))
				addScopeLogsBuilder().addLogRecordsBuilder().apply {
					val now = Instant.now()
					timeUnixNano = now.epochSecond * 1_000_000_000L + now.nano
					bodyBuilder.stringValue = "claude_code.api_request"
					addAttributes(attribute("event.name", "api_request"))
					addAttributes(attribute("session.id", session))
				}
			},
		)
		.build()

	private fun attribute(key: String, value: String): KeyValue = KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

	/** 문서마다 PUT 을 세는 메모리 아카이브. [failing] 은 PUT 순번(0부터)을 받아 실패를 고른다. */
	private class MemoryArchive : ArchiveWriter {
		val puts = AtomicInteger()
		val succeeded = AtomicInteger()
		val inFlight = AtomicInteger()
		val maxInFlight = AtomicInteger()

		@Volatile
		var failing: (Int) -> Boolean = { false }

		@Volatile
		var delay: Duration = Duration.ZERO

		override fun write(product: Product, signal: Signal, body: ByteArray): ArchivedObject {
			val n = puts.getAndIncrement()
			maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
			try {
				if (!delay.isZero) Thread.sleep(delay.toMillis())
				if (failing(n)) throw IllegalStateException("s3 put failed")
				succeeded.incrementAndGet()
				return ArchivedObject("mem://raw/${product.archiveSegment}/${signal.name.lowercase()}/$n.json")
			} finally {
				inFlight.decrementAndGet()
			}
		}
	}

	private companion object {
		const val TOPIC = "pulsemetry-test-telemetry-ingress"
		const val GROUP = "pulsemetry-test-telemetry-ingest"
		const val POISON = "s-poison"
		const val LEDGER = "telemetry_ingest_ledger"
		val ACCEPTED_AT: Instant = Instant.parse("2026-11-20T06:30:00.123456789Z")
	}
}
