package com.team376.pulsemetry.devseed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClickHouseResetTest {
    private val rawTables = listOf("enriched_events", "telemetry_events", "telemetry_metric_points", "telemetry_ingest_ledger", "telemetry_retention_fence")
        .map { SeedResetTable("default", it, "ReplacingMergeTree") }
    private val cacheTables = listOf("snapshot_usage", "snapshot_observed_days", "snapshot_member_activity")
        .map { SeedResetTable("dashboard_cache", it, "MergeTree") }
    private val intakeTables = listOf(
        SeedResetTable("dashboard_cache", "snapshot_intake", "Null"),
        SeedResetTable("dashboard_cache", "snapshot_intake_usage", "MaterializedView"),
        SeedResetTable("dashboard_cache", "snapshot_intake_days", "MaterializedView"),
    )

    @Test fun `Null 입구와 TO 뷰를 제외하고 원본과 캐시 저장 테이블의 선택 조직만 지운다`() {
        val statements = clickHouseResetStatements("C", rawTables + intakeTables + cacheTables)
        val expectedTables = (rawTables + cacheTables).map { "${it.database}.${it.table}" }
        assertEquals(expectedTables, statements.map { it.substringAfter("ALTER TABLE ").substringBefore(" DELETE") })
        assertTrue(statements.all { it.endsWith("DELETE WHERE tenant_id='${id("C")}' SETTINGS mutations_sync=2") })
        assertTrue(statements.none { id("A") in it || id("B") in it || "TRUNCATE" in it || "DROP" in it })
        assertTrue(statements.none { "snapshot_intake" in it })
    }

    @Test fun `뒤쪽의 미지원 엔진도 삭제 문장 실행 전에 거부한다`() {
        for (engine in listOf("Memory", "Log", "Distributed", "SummingMergeTree", "View", "MaterializedView", "Null")) {
            val executed = mutableListOf<String>()
            assertFailsWith<IllegalArgumentException> {
                clickHouseResetStatements("C", rawTables + SeedResetTable("default", "extra_tenant_data", engine)).forEach(executed::add)
            }
            assertTrue(executed.isEmpty(), engine)
        }
    }

    @Test fun `알려진 비저장 테이블의 엔진이 달라져도 조용히 건너뛰지 않는다`() {
        for (table in intakeTables) {
            assertFailsWith<IllegalArgumentException> {
                clickHouseResetStatements("C", rawTables + table.copy(engine = "MergeTree"))
            }
        }
    }

    @Test fun `초기화 범위 밖 DB 잘못된 식별자와 중복 메타데이터를 거부한다`() {
        for (table in listOf(
            SeedResetTable("other", "telemetry_events", "MergeTree"),
            SeedResetTable("default", "telemetry_events; DROP DATABASE default", "MergeTree"),
            SeedResetTable("default", "", "MergeTree"),
        )) assertFailsWith<IllegalArgumentException> { clickHouseResetStatements("C", rawTables + table) }
        assertFailsWith<IllegalArgumentException> { clickHouseResetStatements("C", rawTables + rawTables.first()) }
        assertFailsWith<IllegalArgumentException> { clickHouseResetStatements("real-tenant", rawTables) }
    }

    @Test fun `비어 있거나 비저장 객체만 있는 경우에는 ClickHouse 삭제를 만들지 않는다`() {
        assertTrue(clickHouseResetStatements("C", emptyList()).isEmpty())
        assertTrue(clickHouseResetStatements("C", intakeTables).isEmpty())
    }
}
