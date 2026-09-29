package com.team376.pulsemetry.devseed

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 실제 공급자 가격이 아닌, 모델별 차이를 재현하기 위한 USD/백만 토큰 가상 요율이다. */
internal data class SeedModel(val name: String, val input: String, val output: String, val cacheRead: String, val cacheCreate: String) {
    fun cost(uncached: Long, outputTokens: Long, read: Long, created: Long): BigDecimal =
        (input.toBigDecimal() * uncached.toBigDecimal() + output.toBigDecimal() * outputTokens.toBigDecimal() +
            cacheRead.toBigDecimal() * read.toBigDecimal() + cacheCreate.toBigDecimal() * created.toBigDecimal()).movePointLeft(6)
}

internal val seedModels = mapOf(
    "claude_code" to listOf(
        SeedModel("claude-sonnet-4", "3", "15", "0.3", "3.75"),
        SeedModel("claude-opus-4", "12", "60", "1.2", "15"),
        SeedModel("claude-haiku-4", "0.6", "3", "0.06", "0.75")),
    "codex" to listOf(
        SeedModel("gpt-5", "2", "8", "0.2", "0"),
        SeedModel("o3", "6", "24", "0.6", "0"),
        SeedModel("gpt-5-mini", "0.4", "1.6", "0.04", "0")),
)

internal data class SeedUsage(val events: List<Row>, val ledger: List<Row>)

/** A의 56일 사용 기록. 실수 난수·현재 시각 없이 기준일과 의미 있는 키로 재현한다. */
internal fun detailedUsage(asOf: LocalDate, rows: Map<String, List<Row>>): SeedUsage {
    val events = mutableListOf<Row>()
    val ledger = mutableListOf<Row>()
    val memberships = rows.getValue("enrollment.team_memberships")
    val base = asOf.atStartOfDay(seoul)
    fun noise(key: String, bound: Int) = (hash(key).take(8).toLong(16) % bound).toInt()
    fun session(index: Int, day: Int, slot: Int, calls: Int, start: Instant? = null, marker: String = "daily") {
        val date = asOf.plusDays(day.toLong())
        val key = "A/usage-v2/$date/$index/$slot/$marker"
        val product = when (index) {
            2, 6 -> if (slot % 2 == 0) "claude_code" else "codex"
            else -> if (index % 2 == 0) "claude_code" else "codex"
        }
        val member = id("A/member/$index")
        val installation = id("A/installation/$index")
        val startAt = start ?: base.plusDays(day.toLong()).plusHours(9).plusMinutes((slot * 32 + noise(key, 12)).toLong()).toInstant()
        val times = (0 until calls).map { startAt.plusSeconds(it * 75L) }
        // 한 세션의 여러 관측을 한 push로 묶는다. 수신 건수와 사용자·세션 수는 서로 다르다.
        val received = times.last().plusSeconds(30)
        val modelIndex = noise("$key/model", 10).let { if (it < 6) 0 else if (it < 8) 1 else 2 }
        val model = seedModels.getValue(product)[modelIndex]
        for ((turn, time) in times.withIndex()) {
            val observationKey = "$key/turn/$turn"
            val activeTeams = memberships.filter {
                it["member_id"] == member && Instant.parse(it["joined_at"].toString()) <= time &&
                    (it["left_at"] == null || time < Instant.parse(it["left_at"].toString()))
            }.map { it["team_id"].toString() }.sorted()
            val uncached = 1800L + noise("$observationKey/input", 14000) + turn * 1300L
            val output = 200L + noise("$observationKey/output", 2200)
            val read = if (turn == 0) 0L else (2500 + turn * 2100 + noise("$observationKey/cache", 6000)).toLong()
            val created = if (product == "claude_code") (700 + noise("$observationKey/create", 2500)).toLong() else 0L
            val exclusive = product == "claude_code"
            val duration = (2L + noise("$observationKey/duration", 18)) * 1_000_000_000
            val service = if (exclusive) "claude-code" else "codex_cli_rs"
            val row = linkedMapOf<String, Any?>(
                "tenant_id" to id("A"), "installation_id" to installation,
                "observation_id" to hash(encode(listOf("seed-v2", id("A"), installation, "log", service, time.toString(), observationKey))),
                "row_version" to ((1L shl 32) or 1L), "normalizer_rev" to 1, "schema_version" to 1, "identity_version" to "seed-v2",
                "source_identity_kind" to "native_id", "source_identity_namespace" to "pulsemetry.dev-seed",
                "native_observation_id" to observationKey, "record_status" to "active", "exclusion_reason" to null,
                "mapping_status" to "mapped", "quality_flags" to emptyList<String>(),
                "source_time" to time.toString(), "source_time_origin" to "otlp_event", "event_time" to time.toString(),
                "observed_time" to null, "received_time" to received.toString(), "signal" to "log", "product" to product,
                "surface" to if (exclusive) "unknown" else "cli", "product_version" to "seed-v2", "service_name" to service,
                "mapping_version" to "seed-v2", "enrichment_version" to "seed-v2", "usage_role" to "primary",
                "usage_scope" to "response", "workload_kind" to "main", "session_id" to id(key),
                "session_id_namespace" to if (exclusive) "claude_code.session" else "codex.conversation",
                "model" to model.name, "member_id" to member, "team_id_as_of" to activeTeams.singleOrNull(), "team_ids_as_of" to activeTeams,
                "archive_ref" to null, "archive_selector" to null, "masking_version" to "seed-v2",
                "metadata_json" to encode(mapOf("synthetic" to true, "seedScenario" to "A", "seedRevision" to 2, "case" to marker)),
                "attrs" to emptyMap<String, String>(),
                "enrichment_json" to encode(linkedMapOf("ai_analysis" to emptyMap<String, Any>(), "github" to emptyMap<String, Any>(),
                    "jira" to emptyMap<String, Any>(), "org" to mapOf("team_ids" to activeTeams))),
                "event_type" to "model.response.usage", "operation" to "responses", "end_time" to time.plusNanos(duration).toString(),
                "duration_ns" to duration, "ttft_ns" to 200_000_000L + noise("$observationKey/ttft", 1800) * 1_000_000L,
                "ttft_scope" to "request", "turn_id" to id("$key/turn/$turn"),
                "turn_id_namespace" to if (exclusive) "claude_code.prompt" else "codex.turn",
                "request_id" to id("$observationKey/request"),
                "request_id_namespace" to if (exclusive) "claude_code.request" else "codex.upstream_request",
                "response_id" to null, "response_id_namespace" to null,
                "tokens_input" to if (exclusive) uncached else uncached + read, "tokens_output" to output,
                "tokens_cache_read" to read, "tokens_cache_create" to if (exclusive) created else null, "tokens_input_uncached" to uncached,
                "tokens_total_derived" to uncached + read + created + output,
                "input_semantics" to if (exclusive) "exclusive_cache" else "inclusive_cache",
                "output_semantics" to "inclusive_reasoning_tool",
                "semantics_profile" to if (exclusive) "seed-claude-exclusive-v2" else "seed-codex-inclusive-v2",
                "cost_reported_usd" to null, "cost_estimated_usd" to model.cost(uncached, output, read, created),
                "reported_cost_basis" to "unknown", "pricing_version" to "seed-synthetic-v2", "success" to true, "http_status" to 200,
                "error_type" to "none", "attempt" to 1,
            )
            // 선택값도 명시해 ClickHouse 기본값을 실제 관측으로 오인하지 않게 한다.
            listOf("service_version", "service_instance_id", "scope_name", "scope_version", "resource_schema_url", "scope_schema_url",
                "original_name", "client_request_id", "client_request_id_namespace", "call_id", "call_id_namespace", "tool_result_seq",
                "trace_id", "span_id", "parent_span_id", "severity_number", "tokens_reasoning", "tokens_tool", "tokens_total_reported",
                "tool_name", "tool_namespace", "mcp_server", "decision_raw", "prompt_length", "response_length", "command_name", "agent_id")
                .forEach { row[it] = null }
            listOf("span_kind", "span_status_code", "tool_origin", "tool_action", "decision", "decision_source", "decision_scope")
                .forEach { row[it] = "none" }
            listOf("stop_reason", "reasoning_effort")
                .forEach { row[it] = "unknown" }
            row["analysis_hash"] = hash(encode(row))
            events.add(row)
        }
        ledger.add(linkedMapOf("tenant_id" to id("A"), "installation_id" to installation, "received_time" to received.toString(),
            "receipt_id" to id("$key/receipt"), "signal" to "logs", "product" to product,
            "source_time_min" to times.first().toString(), "source_time_max" to times.last().toString(),
            "record_count" to calls, "rejected_count" to 0, "archive_ref" to null, "masking_version" to "seed-v2"))
    }
    for (day in -56..-1) {
        val weekday = asOf.plusDays(day.toLong()).dayOfWeek.value
        for (index in 2..9) {
            var sessions = when (index) {
                2 -> 5
                3, 7 -> 3
                4 -> if (weekday in listOf(2, 4)) 7 else 1
                5 -> if (weekday in listOf(1, 3, 5)) 1 else 0
                6 -> 10
                8 -> if (weekday == 4) 5 else 2
                else -> if (Math.floorMod(day, 3) == 0) 1 else 0
            }
            if (weekday >= 6) sessions = if (weekday == 6 && index in listOf(2, 6)) 1 else 0
            if (day >= -28 && sessions > 1) sessions += if (index == 6) 3 else 1
            if (day == -9 && index in listOf(4, 8)) sessions += 7
            if (day == -21 && index == 6) sessions += 5
            repeat(sessions) { slot -> session(index, day, slot, 3 + noise("${asOf.plusDays(day.toLong())}/$index/$slot/calls", 6)) }
        }
    }
    // 최근 28일에는 사용하지 않은 구성원, 그리고 팀 이동 경계 직전/정각을 명시한다.
    session(11, -50, 0, 3, marker = "previous_only")
    session(11, -40, 0, 3, marker = "previous_only")
    val moved = base.minusDays(14).toInstant()
    session(2, -15, 0, 1, moved.minusSeconds(1), "before_team_move")
    session(2, -14, 0, 1, moved, "at_team_move")
    ledger.add(linkedMapOf("tenant_id" to id("A"), "installation_id" to id("A/installation/10"),
        "received_time" to base.minusDays(1).plusHours(23).toInstant().toString(), "receipt_id" to id("A/usage-v2/$asOf/empty-receipt"),
        "signal" to "logs", "product" to "claude_code", "source_time_min" to null, "source_time_max" to null,
        "record_count" to 0, "rejected_count" to 0, "archive_ref" to null, "masking_version" to "seed-v2"))
    return SeedUsage(events.sortedWith(compareBy({ it["source_time"].toString() }, { it["observation_id"].toString() })),
        ledger.sortedWith(compareBy({ it["received_time"].toString() }, { it["receipt_id"].toString() })))
}
