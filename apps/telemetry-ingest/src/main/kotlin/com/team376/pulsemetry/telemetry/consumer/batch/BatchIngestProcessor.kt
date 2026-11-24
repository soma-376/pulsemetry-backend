package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.persistence.telemetry.ClickHouseErrorCodes
import com.team376.pulsemetry.persistence.telemetry.AnalysisLinesSink
import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryMetricPointsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkRejectedException
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import com.team376.pulsemetry.telemetry.collector.OtlpPayloadException
import com.team376.pulsemetry.telemetry.collector.OtlpPreparer
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.VerifiedIngestContext
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.ArchivedObject
import com.team376.pulsemetry.telemetry.collector.archive.Product
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties.Batch.PartialDefectPolicy
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties.Batch.SingleRecordFailurePolicy
import com.team376.pulsemetry.telemetry.credential.TelemetryCredentialAuthorizer
import com.team376.pulsemetry.telemetry.credential.TelemetryCredentialRejectedException
import com.team376.pulsemetry.telemetry.enricher.EnrichmentUnavailableException
import com.team376.pulsemetry.telemetry.ingress.InvalidEnvelopeException
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import com.team376.pulsemetry.telemetry.model.RowVersioning
import com.team376.pulsemetry.telemetry.observability.PipelineStage
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.measure
import com.team376.pulsemetry.telemetry.observability.observe
import com.team376.pulsemetry.telemetry.pipeline.BatchIngestOperations
import com.team376.pulsemetry.telemetry.pipeline.ClickHouseSchema
import com.team376.pulsemetry.telemetry.pipeline.IngestStages
import com.team376.pulsemetry.telemetry.pipeline.ReceiptPart
import com.team376.pulsemetry.telemetry.pipeline.RetentionBoundaries
import com.team376.pulsemetry.telemetry.pipeline.TransformRejectedException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore

/**
 * Kafka 배치 하나를 끝까지 처리한다(PROJ-341 · ADR 0083). 리스너 호출 하나가 [process] 한 번이고, 미완료면 예외를 던져 오류 처리기의
 * 배치 fallback 이 같은 메모리 배치로 다시 부르게 한다 — 이미 끝낸 작업은 [BatchWork] 에 남아 다시 하지 않는다.
 *
 * ## 한 호출의 흐름
 *
 * 1. **준비** — envelope 해석, RDB 자격 확인(호출마다), 디코딩·마스킹·신원 스탬프·원본 아카이브(동시 PUT 제한, 성공한 문서는 재사용).
 * 2. **변환** — 정규화 2판·보강([IngestStages]). 영구 실패는 그 레코드의 거부다.
 * 3. **분석 쓰기** — tenant 마다 삭제 경계를 읽고, 레코드별로 인코딩한 줄을 분석 테이블마다 묶어 보낸다. 본문은 [BatchSettings.maxInsertBytes]
 *    미만으로 나누고, 한 행만으로 그 이상이면 독립 INSERT 다. 재현으로 확인한 입력 결함 코드([ClickHouseErrorCodes.INPUT_DEFECT_CODES])면 원본
 *    레코드 수로 반씩 나눠 결함 레코드만 거부한다. 그 밖의 실패는 첫 실패에서 나머지 쓰기를 멈춘다.
 * 4. **ledger** — 적재·거부 레코드의 행을 INSERT 하나로. **요약** — tenant 마다 한 번(RDS).
 *
 * ## ClickHouse 장애와 S3 넘김
 *
 * 단기 예산(최초 포함 4회, 대기 1·5·30초)을 배치 전체가 공유한다. 예산을 다 쓰면 소비자의 차단기를 열고, S3 에 보관된 레코드 중 ClickHouse
 * 쓰기가 남은 것을 넘김 목록([HandoffList])에 올린 뒤 정상 반환한다 — 이 파이프라인의 책임은 ClickHouse 적재와 넘김 기록까지다. 넘김 목록
 * 쓰기·인증 DB·S3·요약이 실패하면 넘길 근거가 없으므로 기다린다. 열린 차단기는 탐침 차례까지 ClickHouse 를 부르지 않는다.
 *
 * 다른 조각은 저장되는데 한 레코드만 반복 실패하면 팀 결정 4번([SingleRecordFailurePolicy])을 따른다. 메모리 한도(코드 241)로 큰 쓰기가 예산을
 * 다 쓰면 `SELECT 1` 로 서버가 응답하는지 확인한 뒤, 같은 호출 안에서 작은 조각을 직렬로 보내 공통 장애인지 한 레코드 문제인지 가른다.
 *
 * 스테레오타입을 달지 않는다. 소비 스레드마다 하나씩 부르지만 상태는 [BatchWork]·스레드 지역 차단기에만 있다.
 */
class BatchIngestProcessor(
	private val authorizer: TelemetryCredentialAuthorizer,
	private val preparer: OtlpPreparer,
	private val archive: ArchiveWriter,
	private val stages: IngestStages,
	private val schema: ClickHouseSchema,
	private val boundaries: RetentionBoundaries,
	private val events: TelemetryEventsSink,
	private val metricPoints: TelemetryMetricPointsSink,
	private val operations: BatchIngestOperations?,
	private val handoffs: HandoffListWriter,
	/** 짧은 기한의 `SELECT 1`. 실패하면 예외다. */
	private val probe: () -> Unit,
	private val settings: BatchSettings,
	private val archiveExecutor: ExecutorService,
	private val observer: TelemetryObserver = TelemetryObserver.NONE,
	private val clock: Clock = Clock.systemUTC(),
) {

	private val breakers = ThreadLocal.withInitial { ClickHouseBreaker(settings.breakerProbeInterval, observer) }
	private val sinks: List<AnalysisLinesSink> = listOf(events, metricPoints)

	/** 이 소비 스레드의 차단기. */
	fun breaker(): ClickHouseBreaker = breakers.get()

	fun process(work: BatchWork, call: BatchCall) {
		work.items.forEach { it.held = false }
		val breaker = breaker()
		var handoffOnly = false
		if (breaker.isOpen) {
			if (breaker.probeDue() && reachable()) {
				call.probing = true
			} else {
				if (breaker.probeDue()) breaker.deferProbe()
				handoffOnly = true
			}
		}
		try {
			// ClickHouse 장애로 다시 불렸으면 새 아카이브 전에 응답부터 확인한다 — 아직 안 되면 PUT 을 늘리지 않고 끝낸다.
			if (!breaker.isOpen && work.lastFailureWasClickHouse) {
				call.ensureTime()
				clickHouse("probe") { probe() }
			}
			prepare(work, call)
			if (handoffOnly) {
				handoff(work, call, checkNotNull(breaker.reason).copy(trigger = "breaker_open"), work.items.filter { it.needsClickHouse })
			} else {
				transform(work, call)
				writeClickHouse(work, call)
			}
		} catch (failure: ClickHouseCallException) {
			onClickHouseFailure(work, call, failure)
		}
		writeSummaries(work, call)
		work.items.firstOrNull { it.held }?.let { throw HeldBatchFailure("hold", it.rejection?.second ?: "held") }
	}

	// --- 1. 준비 ---

	private fun prepare(work: BatchWork, call: BatchCall) {
		for (item in work.items.filter { it.status == BatchRecord.Status.PENDING && !it.decoded }) decode(work, item)
		// 재호출마다 미완료 레코드의 자격을 다시 확인한다 — 재시도 사이의 보안 폐기·설치 차단을 반영한다(ADR 0066 Decision 6).
		for (item in work.items.filter { it.status == BatchRecord.Status.PENDING && !it.handoffPending }) {
			call.ensureTime()
			authorize(work, item)
		}
		val todo = work.items.filter { it.status == BatchRecord.Status.PENDING && it.prepared == null }
		if (todo.isNotEmpty()) archiveAll(work, call, todo)
	}

	private fun decode(work: BatchWork, item: BatchRecord) {
		item.decoded = true
		try {
			item.message = TelemetryEnvelopeCodec.decode(item.record.value())
		} catch (e: InvalidEnvelopeException) {
			reject(work, item, "envelope", e.defect, ledger = false)
			return
		}
		val message = checkNotNull(item.message)
		observer.observe(TelemetryMetric.PIPELINE_ACCEPTED_TO_ATTEMPT_START, ageSeconds(message.acceptedAt), "signal" to item.signalLabel)
	}

	private fun authorize(work: BatchWork, item: BatchRecord) {
		val message = checkNotNull(item.message)
		val credential = try {
			observer.measure(PipelineStage.AUTHORIZE) { authorizer.authorize(message.auth, message.acceptedAt) }
		} catch (e: TelemetryCredentialRejectedException) {
			reject(work, item, "authorize", e.code, ledger = false)
			return
		} catch (e: RuntimeException) {
			throw DependencyCallException("authorize", e)
		}
		item.identity = credential.identity()
	}

	/** 아카이브를 동시에 [BatchSettings.archiveConcurrency] 개까지 진행한다. 실패한 레코드가 있어도 나머지는 끝까지 기다린다 — 성공한 PUT 을 남긴다. */
	private fun archiveAll(work: BatchWork, call: BatchCall, todo: List<BatchRecord>) {
		val permits = Semaphore(settings.archiveConcurrency)
		val futures = todo.map { item ->
			archiveExecutor.submit(
				Callable {
					permits.acquire()
					try {
						if (!call.hasTimeToStart()) return@Callable ArchiveOutcome.SKIPPED
						archiveOne(work, call, item)
					} finally {
						permits.release()
					}
				},
			)
		}
		var dependency: DependencyCallException? = null
		var unexpected: Throwable? = null
		var skipped = false
		for (future in futures) {
			try {
				if (future.get() == ArchiveOutcome.SKIPPED) skipped = true
			} catch (e: ExecutionException) {
				when (val cause = e.cause ?: e) {
					is DependencyCallException -> dependency = dependency ?: cause
					else -> unexpected = unexpected ?: cause
				}
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				throw DependencyCallException("archive", e)
			}
		}
		unexpected?.let { throw it }
		dependency?.let { throw it }
		if (skipped) throw BatchDeadlineReached(call.progressed)
	}

	private enum class ArchiveOutcome { DONE, SKIPPED }

	private fun archiveOne(work: BatchWork, call: BatchCall, item: BatchRecord): ArchiveOutcome {
		val message = checkNotNull(item.message)
		val context = VerifiedIngestContext(message.messageId, message.acceptedAt, checkNotNull(item.identity))
		try {
			val prepared = preparer.prepare(message.payload, context, RecordArchive(archive, item.archived))
			if (prepared == null) item.status = BatchRecord.Status.EMPTY else item.prepared = prepared
			call.progressed = true
		} catch (e: RecordArchive.WriteFailed) {
			throw DependencyCallException("archive", e.cause ?: e)
		} catch (e: OtlpPayloadException) {
			reject(work, item, "decode", e.reason, ledger = false)
		} catch (e: PermanentIngestException) {
			reject(work, item, "pipeline", "pipeline_rejected", ledger = false)
		}
		return ArchiveOutcome.DONE
	}

	// --- 2. 변환 ---

	private fun transform(work: BatchWork, call: BatchCall) {
		for (item in work.items.filter { it.status == BatchRecord.Status.PENDING && it.prepared != null && it.transformed == null && !it.handoffPending }) {
			call.ensureTime()
			val prepared = checkNotNull(item.prepared)
			try {
				item.transformed = stages.transform(prepared.signal, prepared.message, prepared.receipt)
				call.progressed = true
			} catch (e: TransformRejectedException) {
				reject(work, item, "pipeline", "pipeline_rejected", ledger = true, parts = e.parts)
				call.progressed = true
			} catch (e: EnrichmentUnavailableException) {
				throw DependencyCallException("enrich", e)
			} catch (e: DataAccessException) {
				throw DependencyCallException("enrich", e)
			}
		}
	}

	// --- 3·4. ClickHouse 쓰기 ---

	private fun writeClickHouse(work: BatchWork, call: BatchCall) {
		if (work.items.any { it.writable || (it.ledgerPending && !it.handoffPending) }) {
			// 테이블이 없는 채로 INSERT 하면 404 다. 그 앞에서 적용을 확인한다.
			clickHouse("schema") { schema.ensureApplied() }
		}
		val byTenant = work.items.filter { it.writable }.groupBy { checkNotNull(it.prepared).receipt.tenantId!! }
		for ((tenant, items) in byTenant) {
			call.ensureTime()
			val boundary = try {
				observer.measure(PipelineStage.RETENTION) { boundaries.read(tenant) }
			} catch (e: RuntimeException) {
				throw DependencyCallException("retention", e)
			}
			encode(work, call, items, boundary)
			for (sink in sinks) writeTable(work, call, sink, boundary, items)
			for (item in items) if (item.writable && sinks.all { it.table in item.tablesDone }) loaded(item)
		}
		writeLedger(work, call)
		handoffPending(work, call)
	}

	private fun encode(work: BatchWork, call: BatchCall, items: List<BatchRecord>, boundary: AnalysisWriteBoundary) {
		for (item in items) {
			if (!item.writable) continue
			val prepared = checkNotNull(item.prepared)
			val retained = stages.retain(checkNotNull(item.transformed), boundary)
			val versioning = RowVersioning.live(prepared.receipt.receivedAt)
			try {
				item.lines = mapOf(
					events.table to events.encode(retained.flatMap { it.events }, versioning, boundary),
					metricPoints.table to metricPoints.encode(retained.flatMap { it.metricPoints }, versioning, boundary),
				)
				item.loadedParts = retained.map { it.loaded }
			} catch (e: TelemetrySinkRejectedException) {
				// 인코더가 먼저 잡은 형식·범위 오류는 그 원본에 귀속한다 — 이 레코드의 행은 보내지 않았다.
				defect(work, call, item, "encode", null)
			}
		}
	}

	/** 한 (tenant, 테이블)의 남은 줄을 본문 상한 미만으로 묶어 차례로 보낸다. */
	private fun writeTable(work: BatchWork, call: BatchCall, sink: AnalysisLinesSink, boundary: AnalysisWriteBoundary, items: List<BatchRecord>) {
		val targets = items.filter { it.writable && sink.table !in it.tablesDone }
		targets.filter { it.lines[sink.table].isNullOrEmpty() }.forEach { it.tablesDone += sink.table }
		val write = TableWrite(work, call, sink, boundary)
		for (chunk in pack(targets.filter { !it.lines[sink.table].isNullOrEmpty() }, sink.table, write)) send(write, chunk)
	}

	/** 한 (tenant, 테이블) 쓰기의 문맥. [sent]·[total] 은 레코드별 보낸 조각 수와 전체 조각 수다. */
	private class TableWrite(val work: BatchWork, val call: BatchCall, val sink: AnalysisLinesSink, val boundary: AnalysisWriteBoundary) {
		val total: MutableMap<BatchRecord, Int> = mutableMapOf()
		val sent: MutableMap<BatchRecord, Int> = mutableMapOf()
	}

	private fun pack(items: List<BatchRecord>, table: String, write: TableWrite): List<Chunk<BatchRecord>> {
		val packed = InsertPacker.pack(items.map { it to checkNotNull(it.lines[table]) }, settings.maxInsertBytes)
		packed.pieceCounts.forEach { (item, count) -> write.total[item] = count }
		return packed.chunks
	}

	private fun send(write: TableWrite, chunk: Chunk<BatchRecord>) {
		val live = chunk.pieces.filter { it.owner.writable }
		if (live.isEmpty()) return
		write.call.ensureTime()
		val failure = try {
			write.sink.send(live.flatMap { it.lines }, write.boundary)
			null
		} catch (e: TelemetrySinkRejectedException) {
			e to e.code
		} catch (e: TelemetrySinkUnavailableException) {
			e to e.code
		}
		if (failure == null) {
			succeeded(write, live)
			return
		}
		val (exception, code) = failure
		val records = live.map { it.owner }.distinct()
		if (code != null && code in ClickHouseErrorCodes.INPUT_DEFECT_CODES) {
			// 재현으로 확인한 입력 결함 — 요청 전체가 미반영이다. 원본 레코드 수로 반씩 나눠 결함 레코드만 남긴다.
			if (records.size > 1) {
				val (left, right) = halves(live)
				send(write, left)
				send(write, right)
			} else {
				defect(write.work, write.call, records.single(), "insert", code)
			}
			return
		}
		val scope = ClickHouseErrorCodes.memoryScope(code, exception.message)
		if (code == ClickHouseErrorCodes.MEMORY_LIMIT_EXCEEDED && records.size > 1 && !write.call.probing &&
			write.work.failures + 1 >= settings.shortBudget && reachable()
		) {
			diagnose(write, live, exception)
			return
		}
		throw ClickHouseCallException("insert", code, exception, records.map { it.index }.toSet(), scope)
	}

	private fun succeeded(write: TableWrite, pieces: List<Piece<BatchRecord>>) {
		val call = write.call
		call.progressed = true
		write.work.clickHouseSuccesses++
		if (call.probing) {
			breaker().close(consumerName())
			call.probing = false
		}
		for (piece in pieces) {
			val record = piece.owner
			val sent = (write.sent[record] ?: 0) + 1
			write.sent[record] = sent
			if (sent >= (write.total[record] ?: 1)) record.tablesDone += write.sink.table else record.partiallyWritten = true
		}
	}

	/** 레코드 순서를 지키며 원본 레코드 수로 반을 나눈다. */
	private fun halves(pieces: List<Piece<BatchRecord>>): Pair<Chunk<BatchRecord>, Chunk<BatchRecord>> {
		val records = pieces.map { it.owner }.distinct()
		val left = records.take(records.size / 2).toSet()
		return Chunk(pieces.filter { it.owner in left }) to Chunk(pieces.filter { it.owner !in left })
	}

	/**
	 * 메모리 한도 진단(11차 개정안 3절). 단기 예산을 다 쓰게 될 실패에서, 같은 호출 안에서 작은 조각을 차례로 보낸다. 성공한 조각은 표시하고,
	 * 서로 다른 작은 조각이 같은 오류를 내면 공통 장애다. 한 레코드만 남아 실패하면 팀 결정 4번을 따른다.
	 */
	private fun diagnose(write: TableWrite, pieces: List<Piece<BatchRecord>>, cause: RuntimeException) {
		val queue = ArrayDeque<Chunk<BatchRecord>>()
		halves(pieces).let { (left, right) -> queue.addLast(left); queue.addLast(right) }
		val failedSingles = mutableSetOf<BatchRecord>()
		var writes = 0
		while (queue.isNotEmpty()) {
			if (writes >= settings.maxDiagnosticWrites) throw commonFailure(cause, pieces, "diagnosis_limit")
			val chunk = queue.removeFirst()
			val live = chunk.pieces.filter { it.owner.writable }
			if (live.isEmpty()) continue
			write.call.ensureTime()
			writes++
			try {
				write.sink.send(live.flatMap { it.lines }, write.boundary)
				succeeded(write, live)
			} catch (e: RuntimeException) {
				val code = (e as? TelemetrySinkUnavailableException)?.code ?: (e as? TelemetrySinkRejectedException)?.code
				if ((e !is TelemetrySinkUnavailableException && e !is TelemetrySinkRejectedException) || code != ClickHouseErrorCodes.MEMORY_LIMIT_EXCEEDED) {
					throw commonFailure(e, live, "diagnosis_other")
				}
				val records = live.map { it.owner }.distinct()
				if (records.size == 1) {
					failedSingles += records.single()
					if (failedSingles.size >= 2) throw commonFailure(e, live, "diagnosis_common")
				} else {
					halves(live).let { (left, right) -> queue.addFirst(right); queue.addFirst(left) }
				}
			}
		}
		val isolated = failedSingles.singleOrNull() ?: return
		isolatedFailure(write.work, isolated, HandoffReason("single_record", "insert", TelemetryLogEvent.causeOf(cause), ClickHouseErrorCodes.MEMORY_LIMIT_EXCEEDED, ClickHouseErrorCodes.memoryScope(ClickHouseErrorCodes.MEMORY_LIMIT_EXCEEDED, cause.message)))
	}

	private fun commonFailure(cause: RuntimeException, pieces: List<Piece<BatchRecord>>, why: String): ClickHouseCallException {
		val code = (cause as? TelemetrySinkUnavailableException)?.code ?: (cause as? TelemetrySinkRejectedException)?.code
		log.info("event=ingest_batch_diagnosis outcome={} code={}", why, code)
		return ClickHouseCallException("insert", code, cause, pieces.map { it.owner.index }.toSet(), ClickHouseErrorCodes.memoryScope(code, cause.message), common = true)
	}

	private fun loaded(item: BatchRecord) {
		item.status = BatchRecord.Status.LOADED
		val ops = operations ?: return
		val prepared = checkNotNull(item.prepared)
		val parts = checkNotNull(item.transformed).parts
		item.ledgerEntries = ops.loadedEntries(prepared.receipt, parts.zip(item.loadedParts))
		item.firstObservedAt = item.ledgerEntries.mapNotNull { it.sourceTimeMin }.minOrNull()?.toInstant()
		item.ledgerPending = true
		item.summaryPending = true
	}

	private fun writeLedger(work: BatchWork, call: BatchCall) {
		val ops = operations ?: return
		val items = work.items.filter { it.ledgerPending && !it.handoffPending }
		if (items.isEmpty()) return
		call.ensureTime()
		clickHouse("ledger", items) { observer.measure(PipelineStage.OPS) { ops.writeLedger(items.flatMap { it.ledgerEntries }) } }
		items.forEach { it.ledgerPending = false }
		call.progressed = true
		work.clickHouseSuccesses++
		if (call.probing) {
			breaker().close(consumerName())
			call.probing = false
		}
	}

	private fun writeSummaries(work: BatchWork, call: BatchCall) {
		val ops = operations ?: return
		val items = work.items.filter { it.summaryPending && !it.ledgerPending }
		for ((tenant, group) in items.groupBy { checkNotNull(it.prepared).receipt.tenantId!! }) {
			call.ensureTime()
			val received = group.map { checkNotNull(it.prepared).receipt.receivedAt }
			try {
				observer.measure(PipelineStage.OPS) {
					ops.writeSummary(tenant, received.min(), received.max(), group.mapNotNull { it.firstObservedAt }.minOrNull())
				}
			} catch (e: RuntimeException) {
				throw DependencyCallException("ops", e)
			}
			group.forEach { it.summaryPending = false }
			call.progressed = true
		}
	}

	// --- 거부·결함 ---

	private fun reject(
		work: BatchWork,
		item: BatchRecord,
		stage: String,
		reason: String,
		ledger: Boolean,
		parts: List<ReceiptPart>? = null,
		code: Int? = null,
	) {
		item.status = BatchRecord.Status.REJECTED
		item.rejection = stage to reason
		val ops = operations
		val prepared = item.prepared
		if (ledger && ops != null && prepared != null) {
			item.ledgerEntries = ops.unloadedEntries(prepared.receipt, parts ?: ReceiptPart.of(prepared.message, prepared.receipt))
			item.firstObservedAt = null
			item.ledgerPending = true
			item.summaryPending = true
		}
		observer.observe(TelemetryMetric.PIPELINE_FAILURES, 1.0, "stage" to stage, "reason" to reason)
		log.warn(
			TelemetryLogEvent.PERMANENT_FAILURE.format(
				"messageId" to item.messageId, "topic" to item.topic, "partition" to item.partition, "offset" to item.offset,
				"stage" to stage, "reason" to reason, "attempt" to work.invocations, "code" to code,
			),
		)
	}

	/** 입력 결함이 확인됐다. 이미 일부를 썼으면 팀 결정 3번([PartialDefectPolicy])을 따른다. */
	private fun defect(work: BatchWork, call: BatchCall, item: BatchRecord, stage: String, code: Int?) {
		call.progressed = true
		if (item.partiallyWritten && settings.partialDefect == PartialDefectPolicy.HOLD) {
			item.held = true
			item.rejection = stage to "partial_defect_held"
			log.warn(
				TelemetryLogEvent.BATCH_WAITING.format(
					"topic" to item.topic, "partition" to item.partition, "offset" to item.offset, "stage" to stage, "reason" to "partial_defect_held", "code" to code,
				),
			)
			return
		}
		reject(work, item, "pipeline", "pipeline_rejected", ledger = true, parts = item.transformed?.parts, code = code)
	}

	/** 다른 조각은 저장되는데 한 레코드만 반복 실패한다 — 팀 결정 4번([SingleRecordFailurePolicy]). */
	private fun isolatedFailure(work: BatchWork, item: BatchRecord, reason: HandoffReason) {
		when (settings.singleRecordFailure) {
			SingleRecordFailurePolicy.HANDOFF -> {
				item.handoffPending = true
				item.handoffReason = reason.trigger
				work.pendingHandoffReason = reason
			}
			SingleRecordFailurePolicy.HOLD -> {
				item.held = true
				item.rejection = reason.stage to "single_record_held"
			}
		}
	}

	// --- ClickHouse 장애 · S3 넘김 ---

	private fun onClickHouseFailure(work: BatchWork, call: BatchCall, failure: ClickHouseCallException) {
		val cause = TelemetryLogEvent.causeOf(failure.cause)
		val reason = HandoffReason("budget_exhausted", failure.stage, cause, failure.code, failure.memoryScope)
		if (call.probing) {
			// 탐침 배치의 첫 쓰기가 실패했다 — 단기 예산을 쓰지 않고 이 배치를 넘긴다.
			call.probing = false
			val breaker = breaker()
			breaker.deferProbe()
			handoffAfterPrepare(work, call, breaker.reason?.copy(trigger = "breaker_probe_failed") ?: reason.copy(trigger = "breaker_probe_failed"))
			return
		}
		work.clickHouseFailureRecords += failure.records
		val exhausted = failure.common || work.failures + 1 >= settings.shortBudget
		if (!exhausted) throw ClickHouseBatchFailure(failure.stage, cause)

		val isolated = if (failure.common) null else isolatedRecord(work)
		if (isolated != null) {
			isolatedFailure(work, isolated, reason.copy(trigger = "single_record"))
			try {
				// 그 한 건을 빼고 나머지 필수 쓰기를 끝낸다. 여기서 다시 실패하면 공통 장애다.
				writeClickHouse(work, call)
				return
			} catch (next: ClickHouseCallException) {
				openAndHandoff(work, call, HandoffReason("budget_exhausted", next.stage, TelemetryLogEvent.causeOf(next.cause), next.code, next.memoryScope))
				return
			}
		}
		openAndHandoff(work, call, reason)
	}

	/** 같은 원본 한 건만 반복 실패했고 그 배치의 다른 쓰기는 성공했다. */
	private fun isolatedRecord(work: BatchWork): BatchRecord? {
		val failed = work.clickHouseFailureRecords
		if (failed.isEmpty() || work.clickHouseSuccesses == 0) return null
		val single = failed.first().singleOrNull() ?: return null
		if (failed.any { it != setOf(single) }) return null
		return work.items[single].takeIf { it.status == BatchRecord.Status.PENDING }
	}

	private fun openAndHandoff(work: BatchWork, call: BatchCall, reason: HandoffReason) {
		breaker().open(reason, consumerName())
		handoffAfterPrepare(work, call, reason)
	}

	/** 아직 준비하지 못한 레코드가 있으면 먼저 준비한다 — 영수증 없는 레코드는 넘길 수 없다. 준비가 실패하면 기다린다. */
	private fun handoffAfterPrepare(work: BatchWork, call: BatchCall, reason: HandoffReason) {
		prepare(work, call)
		handoff(work, call, reason, work.items.filter { it.needsClickHouse || it.handoffPending })
	}

	/** 한 레코드 격리로 넘기기로 한 것을 목록에 쓴다. */
	private fun handoffPending(work: BatchWork, call: BatchCall) {
		val items = work.items.filter { it.handoffPending && it.status != BatchRecord.Status.HANDED_OFF }
		if (items.isEmpty()) return
		handoff(work, call, checkNotNull(work.pendingHandoffReason), items)
	}

	/**
	 * 넘김 목록을 쓰고 레코드를 [BatchRecord.Status.HANDED_OFF] 로 끝낸다. 순서는 목록 쓰기 → 완료 표시 → 정상 반환 → 커밋이다. 목록 쓰기가
	 * 실패하면 넘기지 않고 기다린다. key 는 배치 전체의 파티션 범위라, 같은 배치에서 여러 번 넘기면 이미 넘긴 레코드까지 담아 덮어쓴다.
	 */
	private fun handoff(work: BatchWork, call: BatchCall, reason: HandoffReason, items: List<BatchRecord>) {
		if (items.isEmpty()) return
		val unprepared = items.firstOrNull { it.prepared == null }
		check(unprepared == null) { "아카이브 영수증 없는 레코드는 넘길 수 없다: $unprepared" }
		call.ensureTime()
		items.forEach { it.handoffReason = it.handoffReason ?: reason.trigger }
		val listed = work.items.filter { it.status == BatchRecord.Status.HANDED_OFF } + items
		val ranges = PartitionRange.of(work.items)
		val key = HandoffList.key(settings.consumerGroup, settings.topic, ranges)
		try {
			observer.measure(PipelineStage.HANDOFF) {
				handoffs.write(key, HandoffList.encode(settings.consumerGroup, settings.topic, ranges, clock.instant(), reason, listed))
			}
		} catch (e: RuntimeException) {
			throw DependencyCallException("handoff", e)
		}
		items.forEach {
			it.status = BatchRecord.Status.HANDED_OFF
			it.handoffPending = false
			it.ledgerPending = false
			it.summaryPending = false
		}
		call.progressed = true
		observer.observe(TelemetryMetric.PIPELINE_HANDOFF_RECORDS, items.size.toDouble(), "reason" to reason.trigger)
		log.warn(
			TelemetryLogEvent.ARCHIVE_HANDOFF.format(
				"cause" to reason.cause, "topic" to settings.topic, *BatchLogFields.ranges(PartitionRange.of(items)),
				"records" to items.size, "trigger" to reason.trigger, "stage" to reason.stage, "code" to reason.code,
				"memoryScope" to reason.memoryScope, "key" to key,
			),
		)
	}

	// --- 공통 ---

	/** ClickHouse 가 응답하는가 — 짧은 기한의 `SELECT 1`. */
	private fun reachable(): Boolean = runCatching { probe() }.isSuccess

	private inline fun <T> clickHouse(stage: String, items: List<BatchRecord> = emptyList(), action: () -> T): T = try {
		action()
	} catch (e: TelemetrySinkUnavailableException) {
		throw ClickHouseCallException(stage, e.code, e, items.map { it.index }.toSet(), ClickHouseErrorCodes.memoryScope(e.code, e.message))
	} catch (e: TelemetrySinkRejectedException) {
		throw ClickHouseCallException(stage, e.code, e, items.map { it.index }.toSet(), null)
	}

	private fun ageSeconds(from: Instant): Double = Duration.between(from, clock.instant()).let { it.seconds + it.nano / 1e9 }

	private fun consumerName(): String = Thread.currentThread().name

	private companion object {
		val log = LoggerFactory.getLogger(BatchIngestProcessor::class.java)
	}
}

/**
 * 레코드 하나의 아카이브 writer(PROJ-341). 제품 문서마다 성공한 PUT 을 [cache] 에 남겨 같은 메모리 배치의 재호출에서 다시 쓰지 않는다.
 * 쓰기 실패는 [WriteFailed] 로 감싸 S3 장애(기다림)를 그 밖의 예외와 가른다.
 */
internal class RecordArchive(private val delegate: ArchiveWriter, private val cache: MutableMap<Product, ArchivedObject>) : ArchiveWriter {

	class WriteFailed(cause: Throwable) : RuntimeException("archive write failed", cause)

	override fun write(product: Product, signal: Signal, body: ByteArray): ArchivedObject =
		cache[product] ?: try {
			delegate.write(product, signal, body).also { cache[product] = it }
		} catch (e: RuntimeException) {
			throw WriteFailed(e)
		}
}

/** 운영 로그 한 줄의 파티션 범위 필드 — `p<번호>=<첫 offset>-<마지막 offset>`, 양 끝 포함(11차 개정안 4절). */
object BatchLogFields {
	fun ranges(ranges: List<PartitionRange>): Array<Pair<String, Any?>> =
		ranges.sortedBy { it.partition }.map { "p${it.partition}" to "${it.first}-${it.last}" }.toTypedArray()
}
