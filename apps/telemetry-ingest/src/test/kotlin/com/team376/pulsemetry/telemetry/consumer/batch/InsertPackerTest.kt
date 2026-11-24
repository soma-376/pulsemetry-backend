package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.persistence.telemetry.AnalysisLinesSink
import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** INSERT 본문 분할(11차 개정안 2절 "큰 입력")과 운영 로그의 파티션 범위 필드(4절)를 고정한다. */
class InsertPackerTest {

	private val limit = 10_000_000L

	/** 줄바꿈을 더해 정확히 [bodyBytes] 바이트가 되는 한 행. */
	private fun lineOf(bodyBytes: Long): String = "x".repeat((bodyBytes - 1).toInt())

	@Test
	@DisplayName("한 행짜리 본문이 9,999,999바이트면 일반 배치, 10,000,000·10,000,001바이트면 각각 독립 INSERT 한 번이다")
	fun singleRowBoundary() {
		val lines = listOf(9_999_999L, 10_000_000L, 10_000_001L).map(::lineOf)
		lines.zip(listOf(9_999_999L, 10_000_000L, 10_000_001L)).forEach { (line, bytes) -> assertThat(AnalysisLinesSink.bodyBytes(line)).isEqualTo(bytes) }

		val packed = InsertPacker.pack(listOf("a" to listOf(lines[0]), "b" to listOf(lines[1]), "c" to listOf(lines[2])), limit)

		assertThat(packed.chunks).hasSize(3)
		assertThat(packed.chunks.map { chunk -> chunk.pieces.single().solo }).containsExactly(false, true, true)
		assertThat(packed.chunks.map { it.pieces.single().owner }).containsExactly("a", "b", "c")
	}

	@Test
	@DisplayName("일반 본문은 상한 미만으로만 묶고 원본 순서를 지킨다")
	fun packsBelowLimit() {
		val items = (1..5).map { "r$it" to listOf(lineOf(3_000_000)) }

		val packed = InsertPacker.pack(items, limit)

		assertThat(packed.chunks.map { chunk -> chunk.pieces.sumOf { it.bytes } }).allMatch { it < limit }
		assertThat(packed.chunks.flatMap { chunk -> chunk.pieces.map { it.owner } }).containsExactly("r1", "r2", "r3", "r4", "r5")
		assertThat(packed.chunks.map { chunk -> chunk.pieces.size }).containsExactly(3, 2)
	}

	@Test
	@DisplayName("한 원본의 줄이 상한을 넘으면 줄 경계에서 조각을 나눠 같은 원본에 연결한다")
	fun splitsLargeRecordIntoPieces() {
		val packed = InsertPacker.pack(listOf("big" to List(4) { lineOf(4_000_000) }, "small" to listOf(lineOf(100))), limit)

		assertThat(packed.pieceCounts).containsEntry("big", 2).containsEntry("small", 1)
		assertThat(packed.chunks.flatMap { it.pieces }.filter { it.owner == "big" }.map { it.lines.size }).containsExactly(2, 2)
		assertThat(packed.chunks.flatMap { it.pieces }).noneMatch { it.solo }
	}

	@Test
	@DisplayName("파티션 6개·19자리 offset 의 범위가 예외 설명 한 줄에 그대로 남는다")
	fun sixPartitionRangesSurviveFormatter() {
		val ranges = (0..5).map { PartitionRange(it, 1_000_000_000_000_000_000L + it, 9_000_000_000_000_000_000L + it * 11) }

		val line = TelemetryLogEvent.COMMIT_FAILED.format("cause" to "TimeoutException", "topic" to "pulsemetry-dev-telemetry-ingress-main", *BatchLogFields.ranges(ranges))

		for (range in ranges) assertThat(line).containsOnlyOnce(" p${range.partition}=${range.first}-${range.last}")
		assertThat(line).containsOnlyOnce("event=telemetry_commit_failed").containsOnlyOnce("topic=pulsemetry-dev-telemetry-ingress-main")
		assertThat(line).endsWith("p5=1000000000000000005-9000000000000000055")
	}

	@Test
	@DisplayName("넘김 목록 key 는 group·토픽·파티션 범위로 정해지고 같은 배치면 같다")
	fun handoffKeyIsDeterministic() {
		val ranges = listOf(PartitionRange(1, 101, 200), PartitionRange(0, 51, 90))

		val key = HandoffList.key("pulsemetry-dev-telemetry-ingest-main", "pulsemetry-dev-telemetry-ingress-main", ranges)

		assertThat(key).matches("handoff/pulsemetry-dev-telemetry-ingest-main/pulsemetry-dev-telemetry-ingress-main/[0-9a-f]{64}\\.json")
		assertThat(HandoffList.key("pulsemetry-dev-telemetry-ingest-main", "pulsemetry-dev-telemetry-ingress-main", ranges.reversed())).isEqualTo(key)
		assertThat(HandoffList.key("pulsemetry-dev-telemetry-ingest-main", "pulsemetry-dev-telemetry-ingress-main", listOf(PartitionRange(0, 51, 91)))).isNotEqualTo(key)
	}

	@Test
	@DisplayName("넘김 목록 key 는 파티션 수와 무관하게 S3 key 상한(1,024바이트) 안이다")
	fun handoffKeyLengthIsBounded() {
		val ranges = (0 until 100).map { PartitionRange(it, 1_000_000_000_000L + it, 1_000_000_000_000L + it) }

		val key = HandoffList.key("g".repeat(300), "t".repeat(249), ranges)

		assertThat(key.toByteArray(Charsets.UTF_8).size).isLessThan(1024)
	}

	@Test
	@DisplayName("정규화하면 같아지는 consumer group 도 넘김 목록 key 가 다르다")
	fun handoffKeySeparatesGroups() {
		val ranges = listOf(PartitionRange(0, 51, 90))

		assertThat(HandoffList.key("ingest/a", "topic", ranges)).isNotEqualTo(HandoffList.key("ingest_a", "topic", ranges))
	}
}
