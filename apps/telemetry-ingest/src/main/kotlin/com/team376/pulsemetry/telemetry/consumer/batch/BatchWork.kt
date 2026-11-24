package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.persistence.telemetry.IngestLedgerEntry
import com.team376.pulsemetry.telemetry.collector.AuthorizedTelemetryIdentity
import com.team376.pulsemetry.telemetry.collector.PreparedIngest
import com.team376.pulsemetry.telemetry.collector.archive.ArchivedObject
import com.team376.pulsemetry.telemetry.collector.archive.Product
import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.pipeline.LoadedPart
import com.team376.pulsemetry.telemetry.pipeline.TransformedPush
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 배치 안 Kafka 레코드 하나의 진행 상태(PROJ-341). **분할 단위는 원본 Kafka 레코드다.**
 *
 * 레코드는 적재([Status.LOADED])·영구 거부([Status.REJECTED])·빈 요청([Status.EMPTY])·S3 넘김([Status.HANDED_OFF]) 중 하나로 끝나야
 * 배치가 정상 반환한다. 성공한 아카이브 PUT([archived]), 끝낸 분석 테이블([tablesDone]), ledger·요약 완료는 같은 메모리 배치의
 * 재호출에서 다시 하지 않는다.
 */
class BatchRecord(val index: Int, val record: ConsumerRecord<ByteArray, ByteArray>) {

	enum class Status { PENDING, EMPTY, REJECTED, LOADED, HANDED_OFF }

	val topic: String get() = record.topic()
	val partition: Int get() = record.partition()
	val offset: Long get() = record.offset()

	@Volatile
	var status: Status = Status.PENDING

	/** 이번 호출에서 RDB 가 확인한 신원. 재호출마다 다시 확인한다(보안 폐기·차단 반영). */
	var identity: AuthorizedTelemetryIdentity? = null

	/** 팀 정책이 보류(HOLD)다 — 이번 호출에서는 더 쓰지 않고 배치를 확정하지 않는다. 호출마다 다시 판정한다. */
	var held: Boolean = false

	/** 넘김 목록에 올릴 레코드로 정해졌다 — 더 쓰지 않는다. 목록을 쓰면 [Status.HANDED_OFF] 다. */
	var handoffPending: Boolean = false

	/** 해석한 envelope. 잘못된 envelope 이면 null 이고 [rejection] 이 있다. */
	var message: AcceptedTelemetry? = null
	var decoded: Boolean = false

	val messageId: UUID? get() = message?.messageId

	/** 로그·지표의 signal 라벨. */
	val signalLabel: String get() = message?.payload?.signal?.name?.lowercase() ?: "unknown"

	/** 제품 문서마다 성공한 아카이브 위치 — 재호출에서 다시 PUT 하지 않는다. 준비는 여러 스레드에서 돈다. */
	val archived: MutableMap<Product, ArchivedObject> = ConcurrentHashMap()

	@Volatile
	var prepared: PreparedIngest? = null

	var transformed: TransformedPush? = null

	/** 이번 호출의 경계로 인코딩한 분석 행 줄 — 테이블 이름 → 줄. 호출마다 다시 만든다(경계가 움직일 수 있다). */
	var lines: Map<String, List<String>> = emptyMap()

	/** 모든 줄을 쓴 분석 테이블. */
	val tablesDone: MutableSet<String> = mutableSetOf()

	/** 이번 호출의 경계로 거른 문서별 적재 결과 — ledger 행이 옮긴다. */
	var loadedParts: List<LoadedPart> = emptyList()

	/** 일부 행이 쓰였을 가능성이 있다 — 결함이 확인되면 팀 결정 3번을 적용한다. */
	var partiallyWritten: Boolean = false

	/** 적재 또는 거부 뒤 써야 할 ledger 행. 운영 기록이 꺼져 있으면 비어 있다. */
	var ledgerEntries: List<IngestLedgerEntry> = emptyList()
	var ledgerPending: Boolean = false
	var summaryPending: Boolean = false

	/** 요약의 최초 유효 관측 시각 — 분석 테이블로 간 관측의 최소 `source_time`. */
	var firstObservedAt: Instant? = null

	/** 영구 거부의 단계·사유. */
	var rejection: Pair<String, String>? = null

	/** 넘긴 사유. */
	var handoffReason: String? = null

	val terminal: Boolean get() = status != Status.PENDING && !ledgerPending && !summaryPending

	/** ClickHouse 에 남은 일이 있다 — 분석 쓰기 또는 ledger. 넘김은 이 레코드들만 대상이다. */
	val needsClickHouse: Boolean
		get() = !held && ((status == Status.PENDING && prepared != null) || ledgerPending)

	/** 이번 호출에서 분석 쓰기를 할 레코드. */
	val writable: Boolean
		get() = status == Status.PENDING && prepared != null && transformed != null && !held && !handoffPending

	override fun toString(): String = "$topic-$partition@$offset"
}

/**
 * 원본 `ConsumerRecords` 하나(한 번의 전달)에 대한 작업 캐시(PROJ-341). 같은 메모리 배치의 재호출에서 이어간다.
 *
 * 새 배치로 재전달되거나, 재할당으로 소유권을 잃거나, 리스너가 정상 반환하면 버린다. 버린 뒤에도 Spring 이 보관한 재시도 콜백이
 * 원본 배치로 리스너를 부를 수 있는데, 그때는 새 작업 캐시로 원래 키·버전 그대로 다시 처리한다 — S3 객체만 늘 수 있다.
 */
class BatchWork(val records: ConsumerRecords<ByteArray, ByteArray>) {

	val items: List<BatchRecord> = records.mapIndexed { index, record -> BatchRecord(index, record) }

	/** 실제 장애 횟수 — 의존성 장애·ClickHouse 장애·예상 밖 오류로 끝난 호출 수. 마감으로 이어간 호출은 세지 않는다. */
	var failures: Int = 0

	/** 진척 없이 연속으로 마감한 호출 수. 진척이 있으면 0 으로 돌아간다. */
	var stalls: Int = 0

	/** 직전 호출이 ClickHouse 장애로 끝났다 — 다음 호출은 `SELECT 1` 로 먼저 확인한다. */
	var lastFailureWasClickHouse: Boolean = false

	/** 긴 대기에 들어갔다는 로그를 이미 남겼다. 복구 때 한 번 더 남긴다. */
	var waitingReported: Boolean = false

	/** 예상 밖 오류를 이미 경보로 남겼다. */
	var unexpectedReported: Boolean = false

	/** ClickHouse 장애가 난 쓰기 조각의 레코드들 — 한 레코드만 반복 실패하는지 판정한다. */
	val clickHouseFailureRecords: MutableList<Set<Int>> = mutableListOf()

	/** 이 배치에서 성공한 ClickHouse 쓰기 수(분석 조각·ledger). 한 레코드만 반복 실패하는지 판정할 때 쓴다. */
	var clickHouseSuccesses: Int = 0

	var invocations: Int = 0

	/** 한 레코드 격리로 넘기기로 한 사유. */
	var pendingHandoffReason: HandoffReason? = null

	fun pending(): List<BatchRecord> = items.filter { !it.terminal }

	fun completed(): Boolean = items.all { it.terminal }
}
