package com.team376.pulsemetry.devseed

import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

typealias Row = Map<String, Any?>

internal val json = JsonMapper.builder().build()
internal val seoul: ZoneId = ZoneId.of("Asia/Seoul")
internal fun encode(value: Any?): String = json.writeValueAsString(value)
internal fun id(name: String): String = UUID.nameUUIDFromBytes("pulsemetry/dev-seed/v1/$name".toByteArray()).toString()
internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun sql(value: Any?): String = when (value) {
    null -> "NULL"
    is Boolean -> if (value) "TRUE" else "FALSE"
    is Number -> value.toString()
    else -> "'" + value.toString().replace("'", "''") + "'"
}

// 공개된 로컬 개발용 계정이다. 운영 계정에는 사용하지 않는다.
const val SEED_PASSWORD = "Pulsemetry-local-2026!"
private const val PASSWORD_HASH = "\$2a\$12\$1UOJpgz.rEryDueX68mqxebO4v9zLldEp7P/pn.otP4IBwDgsQKKm"

data class SeedData(
    val scenario: String,
    val asOf: LocalDate,
    val rows: LinkedHashMap<String, MutableList<Row>>,
    val events: List<Row>,
    val ledger: List<Row>,
    val invitationCodes: Map<String, String>,
) {
    val tenantId = id(scenario)
    val fingerprint: String get() = hash(encode(listOf(1, scenario, asOf.toString(), rows, events, ledger)))

    fun summary(): Row {
        val start = asOf.minusDays(28).atStartOfDay(seoul).toInstant().toString()
        val current = events.filter { it.getValue("source_time").toString() >= start }
        val previous = events.filter { it.getValue("source_time").toString() < start }
        return linkedMapOf("scenario" to scenario, "tenant_id" to tenantId, "as_of" to asOf.toString(),
            "members" to rows.getValue("enrollment.members").size, "events" to events.size,
            "receipts" to ledger.size, "sessions" to events.mapNotNull { it["session_id"] }.distinct().size,
            "models" to events.mapNotNull { it["model"] }.distinct().size,
            "usage_start" to events.minOfOrNull { it["source_time"].toString() },
            "usage_end" to events.maxOfOrNull { it["source_time"].toString() },
            "previous_period_events" to previous.size,
            "previous_period_active_users" to previous.map { it["member_id"] }.distinct().size,
            "period_events" to current.size, "period_active_users" to current.map { it["member_id"] }.distinct().size,
            "period_known_estimated_usd" to current.mapNotNull { it["cost_estimated_usd"] as? BigDecimal }.fold(BigDecimal.ZERO, BigDecimal::add),
            "period_cost_complete" to current.all { it["cost_estimated_usd"] != null },
            "owner_email" to rows.getValue("enrollment.members").first()["email"],
            "development_password" to SEED_PASSWORD, "invitation_codes" to invitationCodes)
    }
}

/** 서버·인증·수집 파이프라인을 실행하지 않는 합성 데이터 생성기다. */
fun scenario(name: String, asOf: LocalDate): SeedData {
    require(name in listOf("A", "B", "C"))
    if (name == "B") return newOrganizationScenario(asOf)
    val base = asOf.atStartOfDay(seoul)
    fun at(days: Long, hours: Long = 0) = base.plusDays(days).plusHours(hours).toInstant().toString()
    val origin = at(-60)
    // A의 56일 사용 기록은 설치 이후에만 생성한다. C의 기존 시나리오는 유지한다.
    val installedAt = at(if (name == "A") -58 else -45)
    val tenant = id(name)
    val rows = linkedMapOf<String, MutableList<Row>>()
    val events = mutableListOf<Row>()
    val ledger = mutableListOf<Row>()
    fun add(table: String, vararg fields: Pair<String, Any?>) { rows.getOrPut(table) { mutableListOf() }.add(linkedMapOf(*fields)) }
    fun member(index: Int) = id("$name/member/$index")
    fun team(index: Int) = id("$name/team/$index")
    fun installation(index: Int) = id("$name/installation/$index")
    fun membership(index: Int, teamIndex: Int, joined: String = origin, left: String? = null) {
        add("enrollment.team_memberships", "id" to id("$name/membership/$index/$teamIndex"),
            "member_id" to member(index), "team_id" to team(teamIndex), "joined_at" to joined, "left_at" to left)
    }
    add("enrollment.tenants", "id" to tenant, "name" to "시드 $name · ${mapOf("A" to "정상 사용", "C" to "예외 데이터").getValue(name)}",
        "slug" to "pulsemetry-seed-${name.lowercase()}", "timezone" to "Asia/Seoul", "status" to "active", "created_at" to origin, "updated_at" to origin)
    if (name == "A") rows.getValue("enrollment.tenants").replaceAll { it + ("onboarding_completed_at" to origin) }
    val count = mapOf("A" to 12, "C" to 8).getValue(name)
    repeat(count + if (name == "A") 2 else 0) { index ->
        val local = when (index) { 0 -> "owner"; 1 -> "admin"; else -> "member$index" }
        add("enrollment.members", "id" to member(index), "tenant_id" to tenant, "email" to "$local@seed-${name.lowercase()}.example.test",
            "display_name" to "$name 구성원 %02d".format(index), "role" to when(index) { 0 -> "owner"; 1 -> "admin"; else -> "member" },
            "status" to if (index >= count) "invited" else "active", "password_hash" to if (index < 2) PASSWORD_HASH else null,
            "created_at" to origin, "updated_at" to origin)
    }
    repeat(if (name == "A") 4 else 1) { index ->
        add("enrollment.teams", "id" to team(index), "tenant_id" to tenant, "name" to listOf("플랫폼", "제품", "데이터", "디자인")[index],
            "status" to "active", "created_at" to origin, "updated_at" to origin)
    }
    membership(0, 0); membership(1, 0)
    if (name == "A") {
        membership(2, 0, left = at(-14)); membership(2, 1, joined = at(-14))
        (3..11).filter { it != 9 }.forEach { membership(it, (it - 2) % 4) }
    } else if (name == "C") (2 until count).forEach { membership(it, 0) }
    val manifestId = id("$name/manifest")
    val manifest = linkedMapOf("schema_version" to 1, "config_revision" to 1,
        "otlp" to mapOf("endpoint" to "http://localhost:4316", "protocol" to "http/protobuf"),
        "signals" to mapOf("logs" to true, "metrics" to true, "traces" to true),
        "privacy" to listOf("user_prompts", "assistant_responses", "tool_details", "tool_content", "user_email", "raw_api_bodies").associate { "collect_$it" to false })
    add("enrollment.manifests", "id" to manifestId, "tenant_id" to tenant, "version" to 1, "manifest" to encode(manifest),
        "is_active" to true, "created_by_member_id" to member(0), "created_at" to origin, "activated_at" to origin)
    if (name == "A") {
        // 완료 조건인 명시적 정책 확인과 활성 벤더 선택도 함께 준비한다(ADR 0032).
        add("enrollment.organization_onboarding", "tenant_id" to tenant, "policy_confirmed_at" to origin,
            "policy_confirmed_by" to member(0), "completed_by" to member(0))
        listOf("claude_team" to "Claude (Anthropic)", "openai_biz" to "ChatGPT / Codex (OpenAI)",
            "copilot" to "GitHub Copilot", "cursor" to "Cursor").forEach { (kind, displayName) ->
            val vendorId = id("$name/vendor/$kind")
            add("enrollment.managed_vendors", "tenant_id" to tenant, "vendor_id" to vendorId,
                "kind" to kind, "source" to "manual", "created_at" to origin)
            // A의 정상 좌석 계약과 계약 미입력을 함께 검증한다. 가격은 테스트용이며 관측 인원에서 계산하지 않는다.
            val seatContract = if (kind == "claude_team") encode(mapOf(
                "version" to 1, "planId" to "team", "effectiveFrom" to asOf.minusDays(60).toString(),
                "effectiveTo" to asOf.plusDays(305).toString(), "termNote" to "합성 테스트 계약",
                "tiers" to listOf(
                    mapOf("tierId" to id("$name/vendor/$kind/tier/standard"), "label" to "표준", "seats" to 8, "monthlyFeePerSeatUsd" to "20"),
                    mapOf("tierId" to id("$name/vendor/$kind/tier/premium"), "label" to "프리미엄", "seats" to 2, "monthlyFeePerSeatUsd" to "100"),
                ),
                "monthlySeatFeeUsd" to "360", "confirmedAt" to origin, "confirmedBy" to member(0),
            )) else if (kind == "openai_biz") encode(mapOf(
                "version" to 2, "planId" to "business", "effectiveFrom" to asOf.minusDays(14).toString(),
                "effectiveTo" to asOf.plusDays(351).toString(), "termNote" to "합성 테스트 계약 · 두 좌석 유형",
                "tiers" to listOf(
                    mapOf("tierId" to id("$name/vendor/$kind/tier/standard"), "label" to "Standard", "seats" to 6, "monthlyFeePerSeatUsd" to "25"),
                    mapOf("tierId" to id("$name/vendor/$kind/tier/premium"), "label" to "Premium", "seats" to 2, "monthlyFeePerSeatUsd" to "125"),
                ),
                "monthlySeatFeeUsd" to "400", "confirmedAt" to at(-14), "confirmedBy" to member(1),
            )) else if (kind == "copilot") encode(mapOf(
                "version" to 1, "planId" to "copilot_business", "effectiveFrom" to asOf.minusDays(60).toString(),
                "effectiveTo" to asOf.minusDays(1).toString(), "termNote" to "합성 테스트 만료 계약",
                "tiers" to listOf(mapOf("tierId" to id("$name/vendor/$kind/tier/standard"), "label" to "표준", "seats" to 5, "monthlyFeePerSeatUsd" to "19")),
                "monthlySeatFeeUsd" to "95", "confirmedAt" to origin, "confirmedBy" to member(0),
            )) else null
            if (kind == "openai_biz") {
                // 처음에는 제품만 등록했고, 이후 관리자가 계약을 입력한 이력을 재현한다.
                add("enrollment.vendor_contract_versions", "tenant_id" to tenant, "vendor_id" to vendorId,
                    "version" to 1, "display_name" to displayName, "contract" to null, "archived" to false,
                    "recorded_at" to origin, "recorded_by" to member(0))
            }
            add("enrollment.vendor_contract_versions", "tenant_id" to tenant, "vendor_id" to vendorId,
                "version" to if (kind == "openai_biz") 2 else 1, "display_name" to displayName, "contract" to seatContract, "archived" to false,
                "recorded_at" to if (kind == "openai_biz") at(-14) else origin,
                "recorded_by" to member(if (kind == "openai_biz") 1 else 0))
        }
    }
    fun invitation(index: Int, state: String): Pair<String, String> {
        val code = hash("seed-$name-$index-$state").take(12).uppercase().chunked(4).joinToString("-")
        val invitationId = id("$name/invitation/$index/$state")
        add("enrollment.invitations", "id" to invitationId, "tenant_id" to tenant, "target_member_id" to member(index),
            "created_by_member_id" to member(0), "code_hash" to hash(code), "used_at" to if (state == "used") installedAt else null,
            "expires_at" to at(if (state == "expired") -1 else 30), "created_at" to origin)
        return invitationId to code
    }
    val codes = linkedMapOf<String, String>()
    if (name == "A") listOf(12 to "pending", 13 to "expired").forEach { (index, state) -> codes[state] = invitation(index, state).second }
    (2 until count).forEach { index ->
        val platform = if (name == "A") listOf("windows", "macos", "linux")[index % 3] else "windows"
        add("enrollment.installations", "id" to installation(index), "tenant_id" to tenant, "member_id" to member(index),
            "invitation_id" to invitation(index, "used").first, "hostname" to "seed-${name.lowercase()}-$index",
            "platform" to platform, "architecture" to if (platform == "macos") "arm64" else "amd64", "client_version" to "seed-v1", "status" to "active",
            "last_seen_at" to null, "created_at" to installedAt, "updated_at" to installedAt)
        add("enrollment.installation_manifest_assignments", "installation_id" to installation(index), "manifest_id" to manifestId,
            "assigned_at" to installedAt, "applied_at" to installedAt)
    }
    if (name == "A") {
        // 같은 사람의 두 설치를 사람 두 명으로 집계하지 않는지 확인할 기준이다.
        // 아직 신호와 적용 보고가 없는 설치이며, 기존 ClickHouse 이벤트에는 추가하지 않는다.
        val secondary = id("$name/installation/2/secondary")
        val invite = id("$name/invitation/2/secondary")
        add("enrollment.invitations", "id" to invite, "tenant_id" to tenant, "target_member_id" to member(2),
            "created_by_member_id" to member(0), "code_hash" to hash("seed-A-secondary-device-invitation"),
            "used_at" to at(-3), "expires_at" to at(30), "created_at" to at(-3))
        add("enrollment.installations", "id" to secondary, "tenant_id" to tenant, "member_id" to member(2),
            "invitation_id" to invite, "hostname" to "seed-a-2-secondary", "platform" to "macos", "architecture" to "arm64",
            "client_version" to "seed-v1", "status" to "active", "last_seen_at" to null,
            "created_at" to at(-3), "updated_at" to at(-3))
        add("enrollment.installation_manifest_assignments", "installation_id" to secondary, "manifest_id" to manifestId,
            "assigned_at" to at(-3), "applied_at" to null)
    }
    fun receipt(index: Int, whenAt: String, product: String, recordCount: Int = 1) {
        ledger.add(linkedMapOf("tenant_id" to tenant, "installation_id" to installation(index), "received_time" to whenAt,
            "receipt_id" to id("$name/receipt/$index/$whenAt/$product"), "signal" to "logs", "product" to product,
            "source_time_min" to if (recordCount > 0) whenAt else null, "source_time_max" to if (recordCount > 0) whenAt else null,
            "record_count" to recordCount, "rejected_count" to 0, "archive_ref" to null, "masking_version" to "seed-v1"))
    }
    fun event(index: Int, day: Int, product: String = "claude_code", incomplete: Boolean = false, unknown: Boolean = false) {
        val whenAt = at(day.toLong(), (10 + index % 5).toLong())
        val teamIndex = when { name == "C" -> 0; index == 9 -> null; index == 2 -> if (day < -14) 0 else 1; else -> (index - 2) % 4 }
        val claude = product == "claude_code"
        val models = if (claude) listOf("claude-sonnet-4", "claude-opus-4", "claude-haiku-4") else listOf("gpt-5", "gpt-5-mini", "o3")
        val input = 1000 + index * 50 + (day + 60) * 10
        val output = 100 + index * 5
        val cacheRead = 200
        val cacheCreate = if (claude) 50 else 0
        // 가상 요율이다. 실제 벤더 요금·청구 금액이 아니다.
        val cost = (input + output * 3).toBigDecimal().movePointLeft(6)
        val observation = hash("$name/$index/$day/$product")
        events.add(linkedMapOf("tenant_id" to tenant, "installation_id" to installation(index), "observation_id" to observation,
            "analysis_hash" to observation, "row_version" to 1, "normalizer_rev" to 1, "identity_version" to "seed-v1",
            "source_identity_kind" to "native", "source_time" to whenAt, "source_time_origin" to "event_time", "event_time" to whenAt,
            "received_time" to whenAt, "record_status" to "active", "mapping_status" to "mapped",
            "quality_flags" to if (incomplete) listOf("seed_incomplete") else emptyList<String>(), "signal" to "log",
            "event_type" to "model.response.usage", "operation" to "model.response", "usage_role" to "primary", "usage_scope" to "response",
            "workload_kind" to "main", "product" to product, "surface" to "cli", "service_name" to if (claude) "claude-code" else "codex",
            "product_version" to "seed-v1", "model" to if (unknown) "unrecognized-seed-model" else models[Math.floorMod(index + day, 3)],
            "member_id" to member(index), "team_id_as_of" to teamIndex?.let(::team), "team_ids_as_of" to listOfNotNull(teamIndex?.let(::team)),
            "session_id" to if (incomplete) null else id("$name/session/$index/$day/$product"), "session_id_namespace" to if (incomplete) null else "$product.session",
            "tokens_input" to input, "tokens_output" to if (incomplete) null else output, "tokens_cache_read" to cacheRead, "tokens_cache_create" to cacheCreate,
            "tokens_input_uncached" to if (claude) input else input - cacheRead,
            "tokens_total_derived" to if (incomplete) null else input + output + if (claude) cacheRead + cacheCreate else 0,
            "input_semantics" to if (claude) "exclusive" else "inclusive", "output_semantics" to "inclusive",
            "semantics_profile" to if (claude) "claude-code-exclusive-v1" else "codex-inclusive-v1",
            "cost_estimated_usd" to if (incomplete || unknown) null else cost, "pricing_version" to "seed-synthetic-v1",
            "reported_cost_basis" to "unknown", "mapping_version" to "seed-v1", "enrichment_version" to "seed-v1", "masking_version" to "seed-v1",
            "metadata_json" to encode(mapOf("synthetic" to true)), "attrs" to emptyMap<String, String>(), "enrichment_json" to "{}"))
        receipt(index, whenAt, product)
    }
    if (name == "A") {
        val usage = detailedUsage(asOf, rows)
        events.addAll(usage.events)
        ledger.addAll(usage.ledger)
        // 기존 기간 약정 호환성 검증용. 위 managed_vendors의 좌석 계약과 연결하거나 합산하지 않는다.
        listOf("anthropic" to 1200, "openai" to 0).forEach { (vendor, amount) ->
            val contract = id("$name/contract/$vendor")
            add("enrollment.contracts", "id" to contract, "tenant_id" to tenant, "vendor" to vendor, "contract_type" to "term_commitment",
                "name" to "시드 $vendor 기간 약정", "contract_no" to "seed-$name-$vendor", "contracted_at" to asOf.minusDays(60).toString(),
                "starts_at" to asOf.minusDays(60).toString(), "ends_at" to asOf.plusDays(305).toString(), "status" to "active",
                "created_by_member_id" to member(0), "created_at" to origin, "updated_at" to origin)
            add("enrollment.contract_term_commitments", "contract_id" to contract, "commitment_months" to 12, "commitment_amount" to amount,
                "currency" to "USD", "auto_renew" to false, "created_at" to origin, "updated_at" to origin)
            (2..11).filter { it == 2 || (it % 2 == 0) == (vendor == "anthropic") }.forEach { index ->
                add("enrollment.contract_memberships", "id" to id("$name/contract-member/$vendor/$index"), "contract_id" to contract,
                    "member_id" to member(index), "assigned_at" to origin)
            }
        }
    } else if (name == "C") {
        listOf(-25, -15, -5, -1).forEach { day -> event(2, day); event(3, day, "codex", unknown = true); event(4, day, incomplete = true) }
        event(5, -40); event(6, -20); receipt(7, at(-1, 23), "codex", 0)
    }
    rows["enrollment.installations"]?.replaceAll { row -> row + ("last_seen_at" to ledger.filter { it["installation_id"] == row["id"] }.maxOfOrNull { it["received_time"].toString() }) }
    add("telemetry_ops.tenant_ingest_summary", "tenant_id" to tenant, "first_received_at" to ledger.minOfOrNull { it["received_time"].toString() },
        "first_observed_at" to events.minOfOrNull { it["source_time"].toString() }, "last_received_at" to ledger.maxOfOrNull { it["received_time"].toString() },
        "has_pre_ledger_history" to false, "updated_at" to at(0))
    return SeedData(name, asOf, rows, events, ledger, codes)
}

/** B는 조직과 오너만 만든다. A/C의 팀·설정·수집 데이터 생성 경로를 공유하지 않는다. */
private fun newOrganizationScenario(asOf: LocalDate): SeedData {
    val tenant = id("B")
    val origin = asOf.minusDays(60).atStartOfDay(seoul).toInstant().toString()
    val rows = linkedMapOf<String, MutableList<Row>>(
        "enrollment.tenants" to mutableListOf(linkedMapOf(
            "id" to tenant, "name" to "시드 B · 신규 조직", "slug" to "pulsemetry-seed-b",
            "timezone" to "Asia/Seoul", "status" to "active", "created_at" to origin, "updated_at" to origin,
        )),
        "enrollment.members" to mutableListOf(linkedMapOf(
            "id" to id("B/member/0"), "tenant_id" to tenant, "email" to "owner@seed-b.example.test",
            "display_name" to "B 구성원 00", "role" to "owner", "status" to "active", "password_hash" to PASSWORD_HASH,
            "created_at" to origin, "updated_at" to origin,
        )),
    )
    return SeedData("B", asOf, rows, emptyList(), emptyList(), emptyMap())
}
