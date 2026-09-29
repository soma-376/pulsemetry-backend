package com.team376.pulsemetry.devseed

import com.team376.pulsemetry.telemetry.adapter.observation.*
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class UsageSeedTest {
    private val date = LocalDate.of(2026, 9, 29)
    private val data = scenario("A", date)
    private fun source(row: Row) = Instant.parse(row["source_time"].toString())

    @Test fun `56일 범위와 과거 전용 사용자 및 미관측 설치를 구분한다`() {
        val start = date.minusDays(56).atStartOfDay(seoul).toInstant()
        val split = date.minusDays(28).atStartOfDay(seoul).toInstant()
        val end = date.atStartOfDay(seoul).toInstant()
        assertTrue(data.events.all { source(it) >= start && source(it) < end })
        assertTrue(data.ledger.all { Instant.parse(it["received_time"].toString()) < end })
        val current = data.events.filter { source(it) >= split }
        val previous = data.events.filter { source(it) < split }
        assertEquals((2..9).map { id("A/member/$it") }.toSet(), current.map { it["member_id"] }.toSet())
        assertEquals((2..9).map { id("A/member/$it") }.toSet() + id("A/member/11"), previous.map { it["member_id"] }.toSet())
        assertTrue(current.size > previous.size)
        assertEquals(6, data.events.map { it["model"] }.distinct().size)
        assertEquals(setOf("claude_code", "codex"), data.events.map { it["product"] }.toSet())
    }

    @Test fun `평일 사용량과 팀별 사용 패턴이 균일한 복제 데이터가 아니다`() {
        val weekend = data.events.count { source(it).atZone(seoul).dayOfWeek.value >= 6 }
        assertTrue(weekend > 0 && weekend < data.events.size / 8)
        val byPerson = data.events.groupingBy { it["member_id"] }.eachCount()
        assertTrue(byPerson.getValue(id("A/member/6")) > byPerson.getValue(id("A/member/5")) * 5)
        val before = date.minusDays(14).atStartOfDay(seoul).toInstant().minusSeconds(1)
        val at = before.plusSeconds(1)
        assertEquals(id("A/team/0"), data.events.single { source(it) == before }["team_id_as_of"])
        assertEquals(id("A/team/1"), data.events.single { source(it) == at }["team_id_as_of"])
        assertTrue(data.events.any { it["team_id_as_of"] == null })
    }

    @Test fun `수신은 세션의 관측들을 빠짐없이 포함하고 신원 시간 및 건수가 일치한다`() {
        assertEquals(data.events.size, data.events.map { it["observation_id"] }.distinct().size)
        assertEquals(data.ledger.size, data.ledger.map { it["receipt_id"] }.distinct().size)
        val pushed = data.events.groupBy { listOf(it["installation_id"], it["received_time"], it["product"]) }
        for (receipt in data.ledger) {
            val events = pushed[listOf(receipt["installation_id"], receipt["received_time"], receipt["product"])].orEmpty()
            assertEquals(receipt["record_count"], events.size)
            assertEquals(events.minOfOrNull { source(it).toString() }, receipt["source_time_min"])
            assertEquals(events.maxOfOrNull { source(it).toString() }, receipt["source_time_max"])
            assertTrue(events.all { source(it) <= Instant.parse(receipt["received_time"].toString()) })
        }
        assertEquals(data.events.size, data.ledger.sumOf { it["record_count"] as Int })
        assertTrue(data.events.groupBy { it["session_id"] }.values.any { it.size > 1 })
        val summary = data.rows.getValue("telemetry_ops.tenant_ingest_summary").single()
        assertEquals(data.ledger.minOf { it["received_time"].toString() }, summary["first_received_at"])
        assertEquals(data.events.minOf { source(it).toString() }, summary["first_observed_at"])
        assertEquals(data.ledger.maxOf { it["received_time"].toString() }, summary["last_received_at"])
    }

    @Test fun `Claude와 Codex 입력 토큰의 포함 관계가 총 토큰과 일치한다`() {
        for (row in data.events) {
            fun n(key: String) = (row.getValue(key) as Number).toLong()
            val input = n("tokens_input")
            val read = n("tokens_cache_read")
            // Codex 합성 프로파일의 cache write는 해당 없음이며 원본 값은 null이다(ADR 0020 §4).
            val created = (row["tokens_cache_create"] as? Number)?.toLong() ?: 0L
            val uncached = n("tokens_input_uncached")
            assertEquals(uncached + read + created + n("tokens_output"), n("tokens_total_derived"))
            if (row["product"] == "codex") {
                assertEquals(uncached + read, input)
                assertNull(row["tokens_cache_create"])
            } else assertEquals(uncached, input)
            assertTrue(n("ttft_ns") < n("duration_ns"))
            assertEquals(64, row["observation_id"].toString().length)
            assertEquals(64, row["analysis_hash"].toString().length)
        }
    }

    @Test fun `합성 관측도 정규화 계약의 어휘와 보강 형태를 따른다`() {
        val vocabulary = mapOf(
            "source_identity_kind" to SourceIdentityKind.entries, "source_time_origin" to SourceTimeOrigin.entries,
            "input_semantics" to InputSemantics.entries, "output_semantics" to OutputSemantics.entries,
            "operation" to Operation.entries, "ttft_scope" to TtftScope.entries,
            "span_kind" to SpanKind.entries, "span_status_code" to SpanStatusCode.entries,
        ).mapValues { (_, values) -> values.map { it.wire }.toSet() }
        for (row in data.events) {
            for ((column, values) in vocabulary) assertTrue(row[column] in values, "$column=${row[column]}")
            val enrichment = json.readTree(row["enrichment_json"].toString())
            assertEquals(json.valueToTree(row["team_ids_as_of"]), enrichment.path("org").path("team_ids"))
            for (provider in listOf("ai_analysis", "github", "jira")) assertTrue(enrichment.path(provider).isObject)
            assertEquals((row["normalizer_rev"] as Number).toLong(), (row["row_version"] as Number).toLong() ushr 32)
        }
    }

    @Test fun `같은 날짜의 재생성은 동일하고 다른 기준일의 관측 키는 충돌하지 않는다`() {
        assertEquals(data.fingerprint, scenario("A", date).fingerprint)
        // 두 기간이 겹쳐도 같은 실제 날짜·사람·세션·요청은 같은 키여야 한다.
        val next = scenario("A", date.plusDays(1))
        val prior = data.events.associateBy { it["observation_id"] }
        for (row in next.events) prior[row["observation_id"]]?.let {
            assertEquals(it["source_time"], row["source_time"])
            assertEquals(it["installation_id"], row["installation_id"])
        }
        assertTrue(next.events.any { it["observation_id"] !in prior })
    }
}
