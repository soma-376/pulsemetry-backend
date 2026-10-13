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

// 2026-10-11 공식 모델 목록 기준. 과거 출시 이력을 재현하지 않는 화면 검증용 모델 구성이다.
// 순서는 일반·고성능·경량이며, 요율은 공급자 공시 가격과 별개인 합성 값으로 유지한다.
internal val seedModels = mapOf(
    "claude_code" to listOf(
        SeedModel("claude-sonnet-5-5", "3", "15", "0.3", "3.75"),
        SeedModel("claude-fable-5-1", "12", "60", "1.2", "15"),
        SeedModel("claude-haiku-5-5", "0.6", "3", "0.06", "0.75")),
    "codex" to listOf(
        SeedModel("gpt-6.1-sol", "2", "8", "0.2", "0"),
        SeedModel("gpt-6-astra", "6", "24", "0.6", "0"),
        SeedModel("gpt-6-luna", "0.4", "1.6", "0.04", "0")),
)

internal data class SeedUsage(val events: List<Row>, val ledger: List<Row>)

internal const val A_USAGE_DAYS = 180

private fun noise(key: String, bound: Int) = (hash(key).take(8).toLong(16) % bound).toInt()

internal data class SeedSessionPlan(
    val date: LocalDate, val memberIndex: Int, val slot: Int, val calls: Int,
    val marker: String = "daily", val start: Instant? = null,
) {
    val key = "A/usage-v5/$date/$memberIndex/$slot/$marker"
}

/** 행을 직렬화하기 전에 사용 일정을 정한다. 기준일을 바꿔도 같은 실제 날짜의 슬롯·요청 신원은 유지한다. */
internal fun usageSessionPlan(asOf: LocalDate): List<SeedSessionPlan> = buildList {
    // 30일마다 도입 확대·휴지기·재확대가 보인다. 최근 28일과 이전 28일은 별도로 비교한다.
    val volumePercent = listOf(75, 100, 85, 115, 95, 135)
    for (day in -(A_USAGE_DAYS - 1)..0) {
        val date = asOf.plusDays(day.toLong())
        val weekday = date.dayOfWeek.value
        for (index in A_USAGE_MEMBER_INDICES) {
            val teamIndex = if (index >= 14) aTeamIndex(index) else null
            var sessions = when (index) {
                2 -> 4
                3, 7 -> 3
                4 -> if (weekday == 2 || weekday == 4) 5 else 1
                5 -> if (weekday == 1 || weekday == 3 || weekday == 5) 1 else 0
                6 -> 6
                8 -> if (weekday == 4) 4 else 2
                9 -> if (Math.floorMod(date.toEpochDay(), 3L) == 0L) 1 else 0
                // 인원이 많은 조직에서도 5만~8만 요청 규모를 유지하도록 새 구성원은 하루 한 세션을 쓴다.
                else -> 1
            }
            if (weekday >= 6) sessions = when {
                weekday == 6 && index in setOf(2, 6, 21, 22) -> 1
                weekday == 7 && index == 6 -> 1
                else -> 0
            }
            repeat(sessions) { slot ->
                val key = "A/usage-v5/$date/$index/$slot/daily"
                val baselineCalls = when {
                    index == 6 -> 8 + noise("$key/calls", 12)
                    index in setOf(5, 9) -> 2 + noise("$key/calls", 4)
                    teamIndex in setOf(3, 9) -> 2 + noise("$key/calls", 3)
                    teamIndex == 7 -> 4 + noise("$key/calls", 4)
                    teamIndex in setOf(0, 5, 8) -> 3 + noise("$key/calls", 4)
                    index >= 14 -> 2 + noise("$key/calls", 4)
                    else -> 4 + noise("$key/calls", 10)
                }
                // 인원은 모든 기간에 유지하고 요청 수를 바꿔 도입 확대·휴지기를 나타낸다.
                val calls = maxOf(2, baselineCalls * volumePercent[(day + A_USAGE_DAYS - 1) / 30] / 100)
                add(SeedSessionPlan(date, index, slot, calls))
            }
        }
    }
    // 기준일이 주말이어도 열 팀을 오늘 화면에서 확인할 수 있는 소량의 합성 점검 세션이다.
    // 실제 시각에 기대지 않고 고정 슬롯을 사용하며 모두 18시 수집 스냅샷 전에 끝난다.
    A_TEAM_NAMES.indices.forEach { team -> add(SeedSessionPlan(asOf, 14 + team, 7, 2, "latest_day")) }
    // 최근 28일에는 사용하지 않은 구성원, 그리고 팀 이동 경계 직전/정각을 명시한다.
    add(SeedSessionPlan(asOf.minusDays(50), 11, 0, 3, "previous_only"))
    add(SeedSessionPlan(asOf.minusDays(40), 11, 0, 3, "previous_only"))
    val moved = asOf.minusDays(14).atStartOfDay(seoul).toInstant()
    add(SeedSessionPlan(asOf.minusDays(15), 2, 0, 1, "before_team_move", moved.minusSeconds(1)))
    add(SeedSessionPlan(asOf.minusDays(14), 2, 0, 1, "at_team_move", moved))
}

private data class SeedMembership(val team: String, val joined: Instant, val left: Instant?)

/** A의 180일·약 7만 건 사용 기록. 실수 난수·현재 시각 없이 기준일과 의미 있는 키로 재현한다. */
internal fun detailedUsage(asOf: LocalDate, rows: Map<String, List<Row>>, sessions: List<SeedSessionPlan> = usageSessionPlan(asOf)): SeedUsage {
    val events = mutableListOf<Row>()
    val ledger = mutableListOf<Row>()
    val memberships = rows.getValue("enrollment.team_memberships").groupBy { it["member_id"] }.mapValues { (_, entries) ->
        entries.map { SeedMembership(it["team_id"].toString(), Instant.parse(it["joined_at"].toString()),
            it["left_at"]?.toString()?.let(Instant::parse)) }
    }
    val base = asOf.atStartOfDay(seoul)
    val tenant = id("A")
    val members = A_ACTIVE_MEMBER_INDICES.filter { it >= 2 }.associateWith { id("A/member/$it") }
    val installations = A_ACTIVE_MEMBER_INDICES.filter { it >= 2 }.associateWith { id("A/installation/$it") }
    val teamIndices = A_TEAM_NAMES.indices.associateBy { id("A/team/$it") }
    fun session(plan: SeedSessionPlan) {
        val (date, index, slot, calls, marker, start) = plan
        val key = plan.key
        val product = when {
            index == 2 || index == 6 -> if (slot % 2 == 0) "claude_code" else "codex"
            index >= 54 -> if (requireNotNull(aTeamIndex(index)) % 2 == 0) "claude_code" else "codex"
            else -> if (index % 2 == 0) "claude_code" else "codex"
        }
        val member = members.getValue(index)
        val installation = installations.getValue(index)
        // 오전·오후 슬롯을 번갈아 둔다. 18시 스냅샷 이후의 미래 관측은 만들지 않는다.
        val minuteOfDay = (if ((slot + index) % 2 == 0) 9 else 14) * 60 + (slot / 2) * 25
        val startAt = start ?: date.atStartOfDay(seoul).plusMinutes((minuteOfDay + noise("$key/start", 10)).toLong()).toInstant()
        val cadence = 45L + noise("$key/cadence", 90)
        val times = (0 until calls).map { startAt.plusSeconds(it * cadence) }
        // 한 세션의 여러 관측을 한 push로 묶는다. 수신 건수와 사용자·세션 수는 서로 다르다.
        // 같은 설치의 겹치는 세션도 수신 시각으로 모을 때 서로 섞이지 않도록 슬롯을 나노초에 넣는다.
        val received = times.last().plusSeconds(30).plusNanos(slot.toLong())
        val activeTeams = memberships[member].orEmpty().filter { it.joined <= startAt && (it.left == null || startAt < it.left) }
            .map { it.team }.sorted()
        val teamIndex = activeTeams.singleOrNull()?.let(teamIndices::get)
        val premiumPercent = teamIndex?.let { listOf(40, 20, 10, 5, 20, 30, 15, 55, 25, 5)[it] } ?: 15
        val economicalPercent = teamIndex?.let { listOf(5, 15, 40, 60, 30, 10, 25, 5, 20, 65)[it] } ?: 30
        // 비용 급증은 정책 변경·최근 수집 상태와 분리된 과거 한 주에만 둔다.
        val costSpike = date >= asOf.minusDays(49) && date <= asOf.minusDays(43) &&
            (index in setOf(4, 6, 8) || index >= 14 && teamIndex in setOf(0, 2, 5, 7, 8))
        val modelIndex = if (costSpike) 1 else noise("$key/model", 100).let {
            if (it < premiumPercent) 1 else if (it < premiumPercent + economicalPercent) 2 else 0
        }
        val model = seedModels.getValue(product)[modelIndex]
        val sessionId = id(key)
        val cachePercent = when {
            index in setOf(2, 6) -> 80
            index in setOf(3, 7) -> 55
            index in setOf(4, 8) -> 30
            index >= 14 && teamIndex != null -> listOf(75, 45, 60, 20, 55, 70, 35, 80, 65, 30)[teamIndex] + noise("member/$index/cache", 11) - 5
            else -> 10
        }
        val enrichment = encode(linkedMapOf("ai_analysis" to emptyMap<String, Any>(), "github" to emptyMap<String, Any>(),
            "jira" to emptyMap<String, Any>(), "org" to mapOf("team_ids" to activeTeams)))
        val metadata = encode(mapOf("synthetic" to true, "seedScenario" to "A", "seedRevision" to 5,
            "case" to if (costSpike) "cost_spike" else marker))
        for ((turn, time) in times.withIndex()) {
            val observationKey = "$key/turn/$turn"
            val scale = if (costSpike) 3 else 1
            val context = (5000L + noise("$observationKey/input", 16000) + turn * 1400L) * scale
            val read = if (turn == 0) 0L else context * cachePercent / 100
            val uncached = context - read
            val output = (200L + noise("$observationKey/output", 2200)) * scale
            val created = if (product == "claude_code") (700 + noise("$observationKey/create", 2500)).toLong() else 0L
            val exclusive = product == "claude_code"
            val duration = (2L + noise("$observationKey/duration", 18)) * 1_000_000_000
            val service = if (exclusive) "claude-code" else "codex_cli_rs"
            val row = linkedMapOf<String, Any?>(
                "tenant_id" to tenant, "installation_id" to installation,
                "observation_id" to hash(encode(listOf("seed-v5", tenant, installation, "log", service, time.toString(), observationKey))),
                "row_version" to ((1L shl 32) or 1L), "normalizer_rev" to 1, "schema_version" to 1, "identity_version" to "seed-v5",
                "source_identity_kind" to "native_id", "source_identity_namespace" to "pulsemetry.dev-seed",
                "native_observation_id" to observationKey, "record_status" to "active", "exclusion_reason" to null,
                "mapping_status" to "mapped", "quality_flags" to emptyList<String>(),
                "source_time" to time.toString(), "source_time_origin" to "otlp_event", "event_time" to time.toString(),
                "observed_time" to null, "received_time" to received.toString(), "signal" to "log", "product" to product,
                "surface" to if (exclusive) "unknown" else "cli", "product_version" to "seed-v5", "service_name" to service,
                "mapping_version" to "seed-v5", "enrichment_version" to "seed-v5", "usage_role" to "primary",
                "usage_scope" to "response", "workload_kind" to "main", "session_id" to sessionId,
                "session_id_namespace" to if (exclusive) "claude_code.session" else "codex.conversation",
                "model" to model.name, "member_id" to member, "team_id_as_of" to activeTeams.singleOrNull(), "team_ids_as_of" to activeTeams,
                "archive_ref" to null, "archive_selector" to null, "masking_version" to "seed-v5",
                "metadata_json" to metadata,
                "attrs" to emptyMap<String, String>(),
                "enrichment_json" to enrichment,
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
                "semantics_profile" to if (exclusive) "seed-claude-exclusive-v5" else "seed-codex-inclusive-v5",
                "cost_reported_usd" to null, "cost_estimated_usd" to model.cost(uncached, output, read, created),
                "reported_cost_basis" to "unknown", "pricing_version" to "seed-synthetic-v5", "success" to true, "http_status" to 200,
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
        ledger.add(linkedMapOf("tenant_id" to tenant, "installation_id" to installation, "received_time" to received.toString(),
            "receipt_id" to id("$key/receipt"), "signal" to "logs", "product" to product,
            "source_time_min" to times.first().toString(), "source_time_max" to times.last().toString(),
            "record_count" to calls, "rejected_count" to 0, "archive_ref" to null, "masking_version" to "seed-v5"))
    }
    sessions.forEach(::session)
    ledger.add(linkedMapOf("tenant_id" to id("A"), "installation_id" to id("A/installation/10"),
        "received_time" to base.plusHours(17).plusMinutes(30).toInstant().toString(), "receipt_id" to id("A/usage-v5/$asOf/empty-receipt"),
        "signal" to "logs", "product" to "claude_code", "source_time_min" to null, "source_time_max" to null,
        "record_count" to 0, "rejected_count" to 0, "archive_ref" to null, "masking_version" to "seed-v5"))
    return SeedUsage(events.sortedWith(compareBy({ it["source_time"].toString() }, { it["observation_id"].toString() })),
        ledger.sortedWith(compareBy({ it["received_time"].toString() }, { it["receipt_id"].toString() })))
}
