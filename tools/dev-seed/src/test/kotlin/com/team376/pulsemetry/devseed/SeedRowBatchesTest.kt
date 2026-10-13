package com.team376.pulsemetry.devseed

import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeedRowBatchesTest {
    @Test fun `적재 배치를 나눠도 마지막 행까지 순서와 내용이 보존된다`() {
        val rows = (0..SEED_BATCH_ROWS * 2).map { mapOf("id" to it, "text" to "합성 요청 $it") }
        val bodies = jsonEachRowBatches(rows.asSequence()).toList()

        assertEquals(listOf(SEED_BATCH_ROWS, SEED_BATCH_ROWS, 1), bodies.map { it.lines().size })
        assertTrue(bodies.all { it.toByteArray(Charsets.UTF_8).size <= SEED_BATCH_BYTES })
        assertEquals(rows.map { json.valueToTree<JsonNode>(it) }, bodies.flatMap { it.lines().map(json::readTree) })
    }

    @Test fun `문자 수가 아닌 UTF-8 바이트와 줄바꿈을 포함해 본문 크기를 제한한다`() {
        val row = mapOf("text" to "가🙂")
        // {"text":"가🙂"}는 UTF-8 18바이트이고 두 행 사이에는 줄바꿈 1바이트가 있다.
        val exact = jsonEachRowBatches(sequenceOf(row, row, row), maxBytes = 37).toList()
        assertEquals(listOf(37, 18), exact.map { it.toByteArray(Charsets.UTF_8).size })
        assertEquals(listOf(1, 1), jsonEachRowBatches(sequenceOf(row, row), maxBytes = 36).map { it.lines().size }.toList())
        assertFailsWith<IllegalArgumentException> {
            jsonEachRowBatches(sequenceOf(row), maxBytes = 17).toList()
        }
    }

    @Test fun `빈 입력에는 요청을 만들지 않고 잘못된 제한은 거부한다`() {
        assertTrue(jsonEachRowBatches(emptySequence()).none())
        assertFailsWith<IllegalArgumentException> { jsonEachRowBatches(emptySequence(), maxRows = 0).toList() }
        assertFailsWith<IllegalArgumentException> { jsonEachRowBatches(emptySequence(), maxBytes = 0).toList() }
    }

    @Test fun `검증은 전체 원본을 한 번에 읽지 않고 끝 배치의 모든 필드까지 검사한다`() {
        val rows = (0..SEED_BATCH_ROWS).map { mapOf("observation_id" to "request-$it", "cost_estimated_usd" to BigDecimal("1.234567890123")) }
        val loaded = mutableListOf<Int>()
        verifySeedAnalysisRows("A", "telemetry_events", rows, "observation_id") { batch ->
            loaded.add(batch.size)
            batch.reversed().map { json.valueToTree<JsonNode>(it) }
        }
        assertEquals(listOf(SEED_BATCH_ROWS, 1), loaded)

        val error = assertFailsWith<IllegalStateException> {
            verifySeedAnalysisRows("A", "telemetry_events", rows, "observation_id") { batch ->
                batch.map { row ->
                    json.valueToTree<JsonNode>(if (row["observation_id"] == "request-$SEED_BATCH_ROWS") {
                        row + ("cost_estimated_usd" to BigDecimal("1.234567890124"))
                    } else row)
                }
            }
        }
        assertTrue(error.message!!.contains("cost_estimated_usd"))
    }

    @Test fun `동일 건수의 다른 ID 누락 중복과 정의 자체의 중복도 거부한다`() {
        val rows = listOf(mapOf("receipt_id" to "first"), mapOf("receipt_id" to "second"))
        val actual = rows.map { json.valueToTree<JsonNode>(it) }
        for (changed in listOf(actual.take(1), actual + actual.first(), listOf(actual.first(), actual.first()),
            listOf(actual.first(), json.readTree("{\"receipt_id\":\"unexpected\"}")))) {
            assertFailsWith<IllegalStateException> {
                verifySeedAnalysisRows("A", "telemetry_ingest_ledger", rows, "receipt_id") { changed }
            }
        }
        assertFailsWith<IllegalStateException> {
            verifySeedAnalysisRows("A", "telemetry_ingest_ledger", rows + rows.first(), "receipt_id") {
                error("중복된 정의는 조회 전에 거부해야 한다.")
            }
        }
    }

    @Test fun `시간 소수부 십진 정밀도 null을 유지하며 저장 형식 차이만 허용한다`() {
        val row = mapOf("observation_id" to "request", "source_time" to "2026-10-01T03:00:00.123456789Z",
            "cost_estimated_usd" to BigDecimal("1.230000000000"), "model" to null)
        val actual = json.readTree("""{"observation_id":"request","source_time":"2026-10-01 03:00:00.123456789","cost_estimated_usd":"1.23","model":null}""")
        verifySeedAnalysisRows("A", "telemetry_events", listOf(row), "observation_id") { listOf(actual) }
        assertFailsWith<IllegalStateException> {
            verifySeedAnalysisRows("A", "telemetry_events", listOf(row), "observation_id") {
                listOf(json.readTree("""{"observation_id":"request","source_time":"2026-10-01 03:00:00.123456789","cost_estimated_usd":"1.23"}"""))
            }
        }
    }
}
