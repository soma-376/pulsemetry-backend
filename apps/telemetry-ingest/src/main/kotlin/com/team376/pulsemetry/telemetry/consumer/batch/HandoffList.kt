package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.telemetry.pipeline.ReceiptPart
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** 넘김 목록 객체 하나를 쓴다(PROJ-341). 성공하면 객체의 URI 를 돌려주고, 실패하면 예외다 — 위치를 추정하지 않는다. */
fun interface HandoffListWriter {
	fun write(key: String, body: ByteArray): String
}

/** 배포 경로 — 원본 아카이브와 같은 버킷의 `handoff/` 아래. 보존(dev 7일·운영 30일)도 원본과 같다. */
class S3HandoffListWriter(
	private val s3: S3Client,
	private val bucket: String,
	basePrefix: String = "",
) : HandoffListWriter {

	private val head = basePrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

	override fun write(key: String, body: ByteArray): String {
		val objectKey = head + key
		s3.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType("application/json").build(), RequestBody.fromBytes(body))
		return "s3://$bucket/$objectKey"
	}
}

/** 로컬 dev·테스트 — 아카이브 디렉터리 아래 파일. 같은 key 는 덮어쓴다(임시 파일 → 원자적 이동). */
class FileHandoffListWriter(private val root: Path) : HandoffListWriter {

	override fun write(key: String, body: ByteArray): String {
		val target = root.resolve(key).normalize()
		require(target.startsWith(root.normalize())) { "넘김 목록 key 가 루트를 벗어난다: $key" }
		Files.createDirectories(target.parent)
		val temp = Files.createTempFile(target.parent, ".handoff", ".tmp")
		try {
			Files.write(temp, body)
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} finally {
			Files.deleteIfExists(temp)
		}
		return target.toUri().toString()
	}
}

/**
 * 넘김 목록의 형식(PROJ-341 · ADR 0083). 배치마다 객체 하나이고, 재적재 모듈이 **목록만으로** 각 레코드의 원래 `receivedAt`·receipt·
 * S3 위치·Kafka 위치를 복원할 수 있어야 한다 — S3 원본 객체에는 OTLP 문서만 있고 receipt 값이 없다.
 *
 * key 는 consumer group·토픽과 파티션별 첫·마지막 offset 으로 정한다. fallback 이 같은 메모리 배치를 다시 넘기면 같은 객체를 덮어쓴다.
 * 재할당·재시작으로 마지막 커밋 위치부터 다시 받아 범위가 달라지면 같은 레코드가 두 목록에 오를 수 있다 — 재적재는 `receiptId` 와
 * 분석 행의 `record_id` 로 중복을 정리한다.
 */
object HandoffList {

	const val VERSION: Int = 1

	/**
	 * `handoff/<group>/<topic>/<해시>.json`. 해시는 원래 group·토픽·파티션 순 범위를 JSON 으로 직렬화한 SHA-256(hex 64자)이다 — 파티션
	 * 수와 무관하게 S3 key 상한(1,024바이트) 안에 들고, 정규화하면 같아지는 group 끼리도 겹치지 않는다. group·토픽 경로는 목록 조회용이라
	 * 안전한 문자만 남기고 [SEGMENT_MAX] 자로 자른다. 범위는 본문과 넘김 로그(`p<n>=<첫>-<끝>`·`key`)에 있다.
	 */
	fun key(consumerGroup: String, topic: String, ranges: List<PartitionRange>): String {
		require(ranges.isNotEmpty()) { "넘길 레코드가 없다" }
		val identity = listOf(consumerGroup, topic, ranges.sortedBy { it.partition }.map { listOf(it.partition, it.first, it.last) })
		val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(identity)))
		return "handoff/${safe(consumerGroup).take(SEGMENT_MAX)}/${safe(topic).take(SEGMENT_MAX)}/$digest.json"
	}

	/** key 의 group·토픽 경로 한 칸의 최대 길이. */
	const val SEGMENT_MAX: Int = 100

	/** 넘김 목록 본문(JSON). */
	fun encode(
		consumerGroup: String,
		topic: String,
		ranges: List<PartitionRange>,
		writtenAt: Instant,
		reason: HandoffReason,
		records: List<BatchRecord>,
	): ByteArray {
		val document = linkedMapOf(
			"version" to VERSION,
			"consumerGroup" to consumerGroup,
			"topic" to topic,
			"ranges" to ranges.sortedBy { it.partition }.map { linkedMapOf("partition" to it.partition, "first" to it.first, "last" to it.last) },
			"writtenAt" to writtenAt.toString(),
			"reason" to reason.toMap(),
			"records" to records.map(::record),
		)
		return MAPPER.writeValueAsBytes(document)
	}

	private fun record(item: BatchRecord): Map<String, Any?> {
		val prepared = checkNotNull(item.prepared) { "아카이브 영수증 없는 레코드는 넘길 수 없다: $item" }
		val receipt = prepared.receipt
		val parts = ReceiptPart.of(prepared.message, receipt)
		return linkedMapOf(
			"kafka" to linkedMapOf("topic" to item.topic, "partition" to item.partition, "offset" to item.offset),
			"messageId" to item.messageId?.toString(),
			"receipt" to linkedMapOf(
				"receiptId" to receipt.receiptId,
				"receivedAt" to receipt.receivedAt.toString(),
				"signal" to receipt.signal.name.lowercase(),
				"tenantId" to receipt.tenantId,
				"installationId" to receipt.installationId,
				"maskingVersion" to receipt.maskingVersion,
				"identityVersion" to receipt.identityVersion,
			),
			"documents" to parts.map { part ->
				linkedMapOf(
					"product" to part.product,
					"uri" to part.archived?.location?.uri,
					"byteOffset" to part.archived?.location?.byteOffset,
					"byteLength" to part.archived?.location?.byteLength,
					"recordCount" to part.recordCount,
				)
			},
			"reason" to item.handoffReason,
			"rejection" to item.rejection?.let { (stage, reason) -> linkedMapOf("stage" to stage, "reason" to reason) },
			"completed" to linkedMapOf(
				"analysisTables" to item.tablesDone.sorted(),
				"ledger" to item.ledgerWritten,
				"summary" to false,
			),
		)
	}

	private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

	private val MAPPER: JsonMapper = JsonMapper.builder().build()
}

/** 파티션 하나에서 이번 배치가 전달받은 범위. 양 끝을 포함한다. */
data class PartitionRange(val partition: Int, val first: Long, val last: Long) {
	companion object {
		fun of(records: Iterable<BatchRecord>): List<PartitionRange> = records.groupBy { it.partition }.map { (partition, items) ->
			PartitionRange(partition, items.minOf { it.offset }, items.maxOf { it.offset })
		}.sortedBy { it.partition }
	}
}

/** 넘긴 이유 — 실패 단계·원인 클래스·ClickHouse 오류 코드·메모리 한도 범위. 오류 원문은 담지 않는다. */
data class HandoffReason(val trigger: String, val stage: String, val cause: String, val code: Int?, val memoryScope: String?) {
	fun toMap(): Map<String, Any?> =
		linkedMapOf("trigger" to trigger, "stage" to stage, "cause" to cause, "code" to code, "memoryScope" to memoryScope)
}
