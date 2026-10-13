package com.team376.pulsemetry.devseed

import com.team376.pulsemetry.telemetry.adapter.observation.*
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsageSeedTest {
    private val date = LocalDate.of(2026, 10, 11)
    private val data = scenario("A", date)
    private fun source(row: Row) = Instant.parse(row["source_time"].toString())

    @Test fun `180일 5만에서 8만 건 범위와 과거 전용 사용자 및 미관측 설치를 구분한다`() {
        val start = date.minusDays(179).atStartOfDay(seoul).toInstant()
        val previousStart = date.minusDays(55).atStartOfDay(seoul).toInstant()
        val split = date.minusDays(27).atStartOfDay(seoul).toInstant()
        val end = date.plusDays(1).atStartOfDay(seoul).toInstant()
        assertTrue(data.events.all { source(it) >= start && source(it) < end })
        assertTrue(data.ledger.all { Instant.parse(it["received_time"].toString()) <= aSnapshotTime(date) })
        assertTrue(data.events.size in 50_000..80_000)
        assertEquals(180, data.events.map { source(it).atZone(seoul).toLocalDate() }.distinct().size)
        val current = data.events.filter { source(it) >= split }
        val previous = data.events.filter { source(it) >= previousStart && source(it) < split }
        assertEquals(A_USAGE_MEMBER_INDICES.map { id("A/member/$it") }.toSet(), current.map { it["member_id"] }.toSet())
        assertEquals(A_USAGE_MEMBER_INDICES.map { id("A/member/$it") }.toSet() + id("A/member/11"), previous.map { it["member_id"] }.toSet())
        assertEquals(97, current.map { it["member_id"] }.distinct().size)
        assertEquals(98, previous.map { it["member_id"] }.distinct().size)
        assertTrue(current.size > previous.size)
        assertEquals(6, data.events.map { it["model"] }.distinct().size)
        assertEquals(setOf("claude_code", "codex"), data.events.map { it["product"] }.toSet())
        assertTrue(data.events.none { it["member_id"] == id("A/member/10") })
        assertEquals(0, data.ledger.single { it["installation_id"] == id("A/installation/10") }["record_count"])
        assertTrue(data.ledger.none { it["installation_id"] == id("A/installation/2/secondary") })
    }

    @Test fun `다른 요일 연말과 윤년 기준일에도 목표 분량을 유지한다`() {
        val dates = (0L..6L).map(date::plusDays) + listOf("2026-01-01", "2026-03-01", "2028-02-29", "2027-06-30").map(LocalDate::parse)
        for (asOf in dates) {
            // 전체 107컬럼 행을 반복 보관하지 않고 실제 생성에 쓰는 세션 일정에서 요청 수를 확인한다.
            val plan = usageSessionPlan(asOf)
            assertTrue(plan.sumOf { it.calls } in 50_000..80_000, asOf.toString())
            assertEquals(180, plan.map { it.date }.distinct().size, asOf.toString())
            assertEquals(asOf.minusDays(179), plan.minOf { it.date })
            assertEquals(asOf, plan.maxOf { it.date })
            assertEquals(A_USAGE_MEMBER_INDICES.toSet(), plan.filter { it.date >= asOf.minusDays(27) }.map { it.memberIndex }.toSet(), asOf.toString())
            assertEquals(A_USAGE_MEMBER_INDICES.toSet() + 11, plan.filter { it.date >= asOf.minusDays(55) && it.date < asOf.minusDays(27) }
                .map { it.memberIndex }.toSet(), asOf.toString())
            assertEquals((0..9).toSet(), plan.filter { it.date == asOf && it.marker == "latest_day" }
                .map { aTeamIndex(it.memberIndex) }.toSet(), asOf.toString())
        }
        assertEquals(data.events.size, usageSessionPlan(date).sumOf { it.calls })
    }

    @Test fun `주말인 기준일도 열 팀에 사용 이력이 있고 18시 뒤의 미래 관측은 없다`() {
        assertEquals(7, date.dayOfWeek.value)
        val todayStart = date.atStartOfDay(seoul).toInstant()
        val today = data.events.filter { source(it) >= todayStart }
        assertTrue(today.isNotEmpty())
        assertEquals((0..9).map { id("A/team/$it") }.toSet(), today.mapNotNull { it["team_id_as_of"] }.toSet())
        assertEquals((0..9).map { id("A/team/$it") }.toSet(), data.events.mapNotNull { it["team_id_as_of"] }.toSet())
        assertTrue(today.all { Instant.parse(it["end_time"].toString()) < aSnapshotTime(date) })
        assertTrue(data.ledger.filter { Instant.parse(it["received_time"].toString()) >= todayStart }
            .all { Instant.parse(it["received_time"].toString()) < aSnapshotTime(date) })
    }

    @Test fun `확대한 모든 구성원에게 사용 이력을 만들고 팀의 단일 도구 사례를 유지한다`() {
        val start = date.minusDays(27).atStartOfDay(seoul).toInstant()
        val current = data.events.filter { source(it) >= start }
        val addedMembers = (54..106).associate { id("A/member/$it") to requireNotNull(aTeamIndex(it)) }
        val addedUsage = current.filter { it["member_id"] in addedMembers }
        assertEquals(addedMembers.keys, addedUsage.map { it["member_id"] }.toSet())
        for (row in addedUsage) {
            val teamIndex = addedMembers.getValue(row["member_id"].toString())
            assertEquals(id("A/team/$teamIndex"), row["team_id_as_of"])
            assertEquals(if (teamIndex % 2 == 0) "claude_code" else "codex", row["product"])
        }
        val byTeam = current.filter { it["team_id_as_of"] != null }.groupBy { it["team_id_as_of"] }
            .mapValues { (_, rows) -> rows.map { it["product"] }.toSet() }
        for (team in 0..1) assertEquals(setOf("claude_code", "codex"), byTeam.getValue(id("A/team/$team")))
        for (team in 2..9) assertEquals(setOf(if (team % 2 == 0) "claude_code" else "codex"), byTeam.getValue(id("A/team/$team")))
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

    @Test fun `오전 오후 작업과 세션 길이 및 월별 증감이 드러난다`() {
        val sessions = data.events.groupBy { it["session_id"] }.values
        assertTrue(sessions.any { it.size in 2..4 })
        assertTrue(sessions.any { it.size >= 16 })
        val startHours = sessions.map { rows -> rows.minOf { source(it) }.atZone(seoul).hour }
        assertTrue(startHours.any { it in 9..11 })
        assertTrue(startHours.any { it in 14..19 })
        assertTrue(sessions.all { rows -> rows.map { source(it).atZone(seoul).toLocalDate() }.distinct().size == 1 })
        val start = date.minusDays(179).toEpochDay()
        val periods = data.events.groupingBy { ((source(it).atZone(seoul).toLocalDate().toEpochDay() - start) / 30).toInt() }.eachCount()
        assertTrue(periods.getValue(1) > periods.getValue(0))
        assertTrue(periods.getValue(2) < periods.getValue(1))
        assertTrue(periods.getValue(3) > periods.getValue(2))
        assertTrue(periods.getValue(4) < periods.getValue(3))
        assertTrue(periods.getValue(5) > periods.getValue(4))
    }

    @Test fun `팀별 모델과 사용자별 캐시 활용에 차이가 있고 과거 한 주에 비용이 집중된다`() {
        val premium = setOf("claude-fable-5-1", "gpt-6-astra")
        fun premiumRatio(team: Int): Double {
            val events = data.events.filter { it["team_id_as_of"] == id("A/team/$team") }
            return events.count { it["model"] in premium }.toDouble() / events.size
        }
        assertTrue(premiumRatio(0) > premiumRatio(3) * 2)
        fun cacheRatio(member: Int): Double {
            val events = data.events.filter { it["member_id"] == id("A/member/$member") }
            val read = events.sumOf { (it["tokens_cache_read"] as Number).toLong() }
            val input = events.sumOf { (it["tokens_input_uncached"] as Number).toLong() } + read
            return read.toDouble() / input
        }
        assertTrue(cacheRatio(6) > cacheRatio(5) * 4)
        fun weekCost(start: Long): BigDecimal {
            val from = date.plusDays(start).atStartOfDay(seoul).toInstant()
            val until = date.plusDays(start + 7).atStartOfDay(seoul).toInstant()
            return data.events.asSequence().filter { source(it) >= from && source(it) < until }
                .map { it["cost_estimated_usd"] as BigDecimal }.fold(BigDecimal.ZERO, BigDecimal::add)
        }
        assertTrue(weekCost(-49) > weekCost(-56) * BigDecimal(2))
        assertTrue(weekCost(-49) > weekCost(-42) * BigDecimal(2))
        val spikes = data.events.filter { json.readTree(it["metadata_json"].toString()).path("case").asString() == "cost_spike" }
        assertTrue(spikes.isNotEmpty())
        assertTrue(spikes.all { source(it) < date.minusDays(42).atStartOfDay(seoul).toInstant() })
    }

    @Test fun `최신 모델 여섯 종류가 올바른 도구에 속하고 최근 사용과 공개 fixture에 포함된다`() {
        val expected = mapOf(
            "claude_code" to setOf("claude-sonnet-5-5", "claude-fable-5-1", "claude-haiku-5-5"),
            "codex" to setOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna"),
        )
        val start = date.minusDays(27).atStartOfDay(seoul).toInstant()
        for (events in listOf(data.events, data.events.filter { source(it) >= start })) {
            assertEquals(expected, events.groupBy { it["product"].toString() }.mapValues { (_, rows) -> rows.map { it["model"].toString() }.toSet() })
        }
        val fixture = json.readTree(encode(frontendFixture(data)))
        assertEquals(expected.values.flatten().toSet(), fixture.path("usage").path("models").toList().map { it.asString() }.toSet())
        // 모델명만 교체하므로 가상 요율과 관측 규모의 고정 기준은 변하지 않는다.
        assertEquals(69_041, data.events.size)
        assertEquals(0, BigDecimal("913.14455803").compareTo(data.summary()["period_known_estimated_usd"] as BigDecimal))
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

    @Test fun `같은 일정의 재생성은 동일하고 다른 기준일의 같은 요청은 신원을 유지한다`() {
        val plan = usageSessionPlan(date)
        assertEquals(plan, usageSessionPlan(date))
        // 전체 데이터 재생성의 지문은 SeedScenarioTest에서 검증한다. 여기서는 각 기간의 같은 요청을 비교한다.
        val sampleDates = listOf(170L, 100L, 45L, 10L, 1L).map(date::minusDays).toSet()
        val selected = plan.filter { it.date in sampleDates }
        val regenerated = detailedUsage(date, data.rows, selected)
        val nativeKeys = selected.map { it.key }.toSet()
        val expected = data.events.filter { it["native_observation_id"].toString().substringBefore("/turn/") in nativeKeys }
        assertEquals(expected, regenerated.events)
        // 두 기간이 겹쳐도 같은 실제 날짜·사람·세션·요청은 같은 키여야 한다.
        val nextDate = date.plusDays(1)
        val next = detailedUsage(nextDate, data.rows, usageSessionPlan(nextDate).filter { it.date in sampleDates || it.date == nextDate })
        val prior = data.events.associateBy { it["observation_id"] }
        assertTrue(next.events.any { it["observation_id"] in prior })
        for (row in next.events) prior[row["observation_id"]]?.let {
            assertEquals(it["source_time"], row["source_time"])
            assertEquals(it["installation_id"], row["installation_id"])
        }
        assertTrue(next.events.any { it["observation_id"] !in prior })
    }
}
