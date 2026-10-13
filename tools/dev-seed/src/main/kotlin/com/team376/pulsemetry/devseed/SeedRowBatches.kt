package com.team376.pulsemetry.devseed

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

internal const val SEED_BATCH_ROWS = 2_000
internal const val SEED_BATCH_BYTES = 4 * 1024 * 1024

/** 전체 시드를 한 문자열로 만들지 않고 한 HTTP 요청에 담을 행 수와 UTF-8 본문 크기를 제한한다. */
internal fun jsonEachRowBatches(
    rows: Sequence<Row>,
    maxRows: Int = SEED_BATCH_ROWS,
    maxBytes: Int = SEED_BATCH_BYTES,
): Sequence<String> = sequence {
    require(maxRows > 0 && maxBytes > 0)
    val body = StringBuilder()
    var count = 0
    var bytes = 0
    for (row in rows) {
        val encoded = encode(row)
        val rowBytes = encoded.toByteArray(Charsets.UTF_8).size
        require(rowBytes <= maxBytes) { "시드 한 행이 배치 본문 제한(${maxBytes}바이트)을 넘습니다." }
        val separatorBytes = if (count == 0) 0 else 1
        if (count == maxRows || bytes.toLong() + separatorBytes + rowBytes > maxBytes) {
            yield(body.toString())
            body.setLength(0)
            count = 0
            bytes = 0
        }
        if (count > 0) {
            body.append('\n')
            bytes++
        }
        body.append(encoded)
        bytes += rowBytes
        count++
    }
    if (count > 0) yield(body.toString())
}

/** 표본이 아닌 모든 행·필드를 비교하되 ClickHouse 원본은 한 배치씩만 메모리에 둔다. */
internal fun verifySeedAnalysisRows(
    scenario: String,
    table: String,
    expected: List<Row>,
    identity: String,
    loadBatch: (List<Row>) -> List<JsonNode>,
) {
    check(expected.mapTo(HashSet()) { it.getValue(identity).toString() }.size == expected.size) {
        "$scenario: $table 시드 정의의 행 식별자가 중복됩니다."
    }
    for (batch in expected.asSequence().chunked(SEED_BATCH_ROWS)) {
        val actualRows = loadBatch(batch)
        val actual = actualRows.associateBy { it.path(identity).asString() }
        check(actualRows.size == batch.size && actual.keys == batch.map { it.getValue(identity).toString() }.toSet()) {
            "$scenario: $table 행 식별자 또는 건수가 다릅니다."
        }
        for (row in batch) for ((key, value) in row) {
            val observed = actual.getValue(row.getValue(identity).toString()).path(key)
            val equal = when {
                value == null -> observed.isNull
                key in seedTimestampColumns -> {
                    val actualTime = LocalDateTime.parse(observed.asString().replace(' ', 'T')).toInstant(ZoneOffset.UTC)
                    actualTime == Instant.parse(value.toString())
                }
                value is Number -> observed.asString().toBigDecimal().compareTo(value.toString().toBigDecimal()) == 0
                else -> observed == json.valueToTree<JsonNode>(value)
            }
            check(equal) { "$scenario: $table.$key 값이 다릅니다: $observed / $value" }
        }
    }
}

private val seedTimestampColumns = setOf(
    "source_time", "received_time", "event_time", "observed_time", "end_time", "source_time_min", "source_time_max",
)
