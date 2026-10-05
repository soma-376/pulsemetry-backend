package com.team376.pulsemetry.devseed

import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
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

/** 시드 조직. A·B·C 가 기본 초기화 대상이고 D·E 는 파괴적 E2E 용 빈 조직이다(명시해야 만든다). */
internal val SCENARIOS = listOf("A", "B", "C", "D", "E")
internal const val SCENARIO_CHOICES = "A,B,C,D,E"

/** 시드 manifest 의 OTLP 수신 주소 기본값 — 개발 Compose 의 ingest(4316). */
const val DEFAULT_OTLP_ENDPOINT = "http://localhost:4316"
internal const val OTLP_ENDPOINT_ENV = "PULSEMETRY_LOCAL_SEED_OTLP_ENDPOINT"

/**
 * 시드 manifest 의 수신 주소. 비어 있으면 [DEFAULT_OTLP_ENDPOINT] 다. http·https 이고 호스트가 있어야 하며 사용자 정보·쿼리·조각을 받지 않는다.
 * 원격 telemetryctl 은 http 를 호스트가 `localhost` 일 때만 받는다(`internal/contract/manifest.go`) — 격리 스택은 `http://localhost:<포트>` 를 쓴다.
 */
fun otlpEndpoint(environment: Map<String, String>): String {
    val raw = environment[OTLP_ENDPOINT_ENV]?.trim().takeUnless { it.isNullOrEmpty() } ?: return DEFAULT_OTLP_ENDPOINT
    val uri = runCatching { URI(raw) }.getOrNull()
    require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "$OTLP_ENDPOINT_ENV 는 호스트가 있는 http(s) 주소여야 합니다: $raw"
    }
    return raw.trimEnd('/')
}

data class SeedData(
    val scenario: String,
    val asOf: LocalDate,
    val rows: LinkedHashMap<String, MutableList<Row>>,
    val events: List<Row>,
    val ledger: List<Row>,
    val invitationCodes: Map<String, String>,
    /** manifest 에 쓴 수신 주소. */
    val otlpEndpoint: String = DEFAULT_OTLP_ENDPOINT,
    /** 기본 주소로 만든 같은 시드의 지문. 주소가 기본이 아닐 때만 있다. */
    internal val defaultEndpointFingerprint: String? = null,
) {
    val tenantId = id(scenario)
    /** 업무 시드와 SSO 컬럼을 합친 판 2. 과거 판의 지문을 같다고 간주하거나 자동 초기화하지 않는다. */
    val fingerprint: String get() = defaultEndpointFingerprint ?: hash(encode(listOf(2, scenario, asOf.toString(), rows, events, ledger)))

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
            "invitation_codes" to invitationCodes, "otlp_endpoint" to otlpEndpoint)
    }
}

/** 서버·인증·수집 파이프라인을 실행하지 않는 합성 데이터 생성기다. [otlpEndpoint] 는 manifest 의 수신 주소이고 지문에 들지 않는다. */
fun scenario(name: String, asOf: LocalDate, otlpEndpoint: String = DEFAULT_OTLP_ENDPOINT): SeedData {
    require(name in SCENARIOS) { "시나리오는 $SCENARIO_CHOICES 중 선택하세요." }
    val data = generate(name, asOf, otlpEndpoint)
    return if (otlpEndpoint == DEFAULT_OTLP_ENDPOINT) data
    else data.copy(otlpEndpoint = otlpEndpoint, defaultEndpointFingerprint = generate(name, asOf, DEFAULT_OTLP_ENDPOINT).fingerprint)
}

private fun generate(name: String, asOf: LocalDate, otlpEndpoint: String): SeedData {
    if (name == "B") return emptyOrganization("B", "신규 조직", asOf)
    if (name == "D") return emptyOrganization("D", "빈 조직(온보딩 시험)", asOf)
    if (name == "E") return enrollmentOrganization(asOf, otlpEndpoint)
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
            "status" to if (index >= count) "invited" else "active",
            "oidc_subject" to null,
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
    val privacy = listOf("user_prompts", "assistant_responses", "tool_details", "tool_content", "user_email", "raw_api_bodies").associate { "collect_$it" to false }
    fun manifest(revision: Int, privacy: Map<String, Boolean>) = linkedMapOf("schema_version" to 1, "config_revision" to revision,
        "otlp" to mapOf("endpoint" to otlpEndpoint, "protocol" to "http/protobuf"),
        "signals" to mapOf("logs" to true, "metrics" to true, "traces" to true), "privacy" to privacy)
    add("enrollment.manifests", "id" to manifestId, "tenant_id" to tenant, "version" to 1, "manifest" to encode(manifest(1, privacy)),
        "is_active" to (name != "A"), "created_by_member_id" to member(0), "created_at" to origin, "activated_at" to origin)
    // A는 일주일 전에 정책을 한 번 바꿨다(판 2 — 도구 세부 수집만 켬, 사용량 시그널과 원문 선택은 그대로). 설치 일부만 새 판을 적용했다고
    // 보고해 적용·미적용·미확인이 모두 있다(ADR 0043). 적용 확인 행은 설치 보고가 만든 것처럼 적용한 판에만 있다.
    val policyChangedAt = at(-7)
    val activeManifestId = if (name == "A") id("$name/manifest/2") else manifestId
    val adopters = 2..8
    if (name == "A") {
        add("enrollment.manifests", "id" to activeManifestId, "tenant_id" to tenant, "version" to 2,
            "manifest" to encode(manifest(2, privacy + ("collect_tool_details" to true))),
            "is_active" to true, "created_by_member_id" to member(1), "created_at" to policyChangedAt, "activated_at" to policyChangedAt)
    }
    if (name == "A") {
        // 완료 조건인 명시적 정책 확인과 활성 벤더 선택도 함께 준비한다(ADR 0032).
        add("enrollment.organization_onboarding", "tenant_id" to tenant, "policy_confirmed_at" to origin,
            "policy_confirmed_by" to member(0), "completed_by" to member(0))
        // 관리자가 정책을 바꾼 날 회수 기준과 집계 보존도 저장했다(ADR 0046 — manifest 판과 따로, 설정의 판 1).
        add("enrollment.organization_policy_settings", "tenant_id" to tenant, "reclaim_idle_days" to 30, "aggregate_retention_months" to 24,
            "version" to 1, "updated_at" to policyChangedAt, "updated_by" to member(1))
        // 급증만 켠다. 등록 제품 규칙은 자동으로 켜지지 않는다(허브 ADR 0008).
        add("enrollment.organization_alert_rules", "tenant_id" to tenant, "rule_id" to "spend_spike", "enabled" to true, "version" to 1,
            "updated_at" to policyChangedAt, "updated_by" to member(1))
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
    if (name == "A") seats(name, tenant, origin, ::add, ::member)
    if (name == "C") meteredBilling(name, tenant, origin, at(0), at(-12), asOf, ::add, ::member)
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
        if (activeManifestId != manifestId && index in adopters) {
            add("enrollment.installation_manifest_assignments", "installation_id" to installation(index), "manifest_id" to activeManifestId,
                "assigned_at" to at(-7, index.toLong()), "applied_at" to at(-7, index.toLong()))
        }
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
        add("enrollment.installation_manifest_assignments", "installation_id" to secondary, "manifest_id" to activeManifestId,
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
        // 어댑터가 알아보지 못한 도구의 관측(product = unknown)은 사용량 행이 아니다 — 구조만 받은 관측이다.
        if (product == "unknown") {
            events.add(linkedMapOf("tenant_id" to tenant, "installation_id" to installation(index), "observation_id" to observation,
                "analysis_hash" to observation, "row_version" to 1, "normalizer_rev" to 1, "identity_version" to "seed-v1",
                "source_identity_kind" to "native", "source_time" to whenAt, "source_time_origin" to "event_time", "event_time" to whenAt,
                "received_time" to whenAt, "record_status" to "active", "mapping_status" to "generic", "quality_flags" to emptyList<String>(),
                "signal" to "log", "event_type" to "vendor.unknown", "operation" to "unknown", "usage_role" to "none", "usage_scope" to "unknown",
                "workload_kind" to "main", "product" to product, "surface" to "unknown", "service_name" to "unrecognized-seed-tool",
                "product_version" to "seed-v1", "model" to null, "member_id" to member(index), "team_id_as_of" to teamIndex?.let(::team),
                "team_ids_as_of" to listOfNotNull(teamIndex?.let(::team)), "session_id" to null, "session_id_namespace" to null,
                "tokens_input" to null, "tokens_output" to null, "tokens_cache_read" to null, "tokens_cache_create" to null,
                "tokens_input_uncached" to null, "tokens_total_derived" to null, "input_semantics" to "unknown", "output_semantics" to "unknown",
                "semantics_profile" to null, "cost_estimated_usd" to null, "pricing_version" to null,
                "reported_cost_basis" to "unknown", "mapping_version" to "seed-v1", "enrichment_version" to "seed-v1", "masking_version" to "seed-v1",
                "metadata_json" to encode(mapOf("synthetic" to true)), "attrs" to emptyMap<String, String>(), "enrichment_json" to "{}"))
            receipt(index, whenAt, product)
            return
        }
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
        // 어느 카탈로그 제품에도 매핑되지 않는 관측(ADR 0044) — 어떤 등록 제품에도 귀속하지 않고 따로 보여야 한다.
        event(6, -10, "unknown")
    }
    rows["enrollment.installations"]?.replaceAll { row -> row + ("last_seen_at" to ledger.filter { it["installation_id"] == row["id"] }.maxOfOrNull { it["received_time"].toString() }) }
    if (name == "A") {
        // 설치 보고(ADR 0040·0041). A의 주 설치는 등록한 뒤로 끊김·손실 없이 수집하다가 기준 시각에 마지막으로 보고했다.
        // 시드에는 살아 있는 데몬이 없으므로 조회 시점의 상태는 "보고가 끊긴 지 얼마나 됐는가"로 정해진다 — 정상이라고 꾸미지 않는다.
        // 두 번째 설치와 C에는 보고를 넣지 않는다(보고한 적 없는 설치 · 근거가 없는 조직).
        val reportedAt = at(0)
        (2 until count).forEach { index ->
            val receipts = ledger.filter { it["installation_id"] == installation(index) }.map { it["received_time"].toString() }
            val run = hash("$name/daemon-run/$index").take(32)
            add("enrollment.installation_heartbeats", "installation_id" to installation(index), "received_at" to reportedAt, "run_id" to run,
                "daemon_version" to "seed-v1", "architecture" to rows.getValue("enrollment.installations").single { it["id"] == installation(index) }["architecture"],
                "applied_config_revision" to if (index in adopters) 2 else 1, "applied_manifest_id" to if (index in adopters) activeManifestId else manifestId,
                "mode" to "local", "forwarding" to true,
                "receiving_since" to installedAt, "delivered" to receipts.size, "lost" to 0, "pending" to 0,
                "last_delivered_at" to receipts.maxOrNull(), "pending_since" to null)
            add("enrollment.installation_collection_segments", "id" to id("$name/collection-segment/$index"), "installation_id" to installation(index),
                "run_id" to run, "from_at" to installedAt, "to_at" to reportedAt, "lost" to 0)
        }
        // 운영에서 설치의 생존 시각은 마지막 보고를 받은 시각이다.
        rows.getValue("enrollment.installations").replaceAll { row ->
            if (rows.getValue("enrollment.installation_heartbeats").any { it["installation_id"] == row["id"] }) row + ("last_seen_at" to reportedAt) else row
        }
    }
    add("telemetry_ops.tenant_ingest_summary", "tenant_id" to tenant, "first_received_at" to ledger.minOfOrNull { it["received_time"].toString() },
        "first_observed_at" to events.minOfOrNull { it["source_time"].toString() }, "last_received_at" to ledger.maxOfOrNull { it["received_time"].toString() },
        "has_pre_ledger_history" to false, "updated_at" to at(0))
    return SeedData(name, asOf, rows, events, ledger, codes, otlpEndpoint)
}

/**
 * A 의 좌석 원장(ADR 0048). 구매 수량으로 채우지 않고 사람별 좌석을 넣는다 — 관리자가 기록한 수동 원천(Claude Team·OpenAI Business)과
 * 구성원에 잇지 않은 좌석을 함께 둔다. Copilot·Cursor 는 좌석을 기록하지 않았다(그 제품만 `seat_source_not_recorded`). Copilot 은 커넥터가 없는 플랜이다(ADR 0054).
 *
 * - member4·member8 은 Claude 를 쓰는 사람, member10 은 설치는 있지만 Claude 사용이 없는 사람(수집 근거가 이어지면 유휴 후보),
 *   admin(member1)은 설치가 없는 사람(관측 부족이라 후보가 아니다), 외부 계정 하나는 구성원이 없다.
 * - 시드의 설치 보고는 기준일 0시에 끝난다 — 그 뒤의 날은 완전하지 않으므로 기준일이 지나면 후보는 모두 관측 부족으로 빠진다(정상이라고 꾸미지 않는다).
 */
private fun seats(name: String, tenant: String, origin: String, add: (String, Array<out Pair<String, Any?>>) -> Unit, member: (Int) -> String) {
    fun row(table: String, vararg fields: Pair<String, Any?>) = add(table, fields)
    fun email(index: Int) = when (index) { 0 -> "owner"; 1 -> "admin"; else -> "member$index" } + "@seed-${name.lowercase()}.example.test"
    fun seat(key: String, vendor: String, account: String, memberIndex: Int?, link: String?, tier: String?) {
        val seatId = id("$name/seat/$key")
        row("enrollment.seat_assignments", "id" to seatId, "tenant_id" to tenant, "vendor_id" to id("$name/vendor/$vendor"), "account" to account,
            "account_kind" to "email", "vendor_account_ref" to null, "account_email" to account, "state" to "assigned", "source" to "manual",
            "member_id" to memberIndex?.let(member), "member_link" to link, "tier_id" to tier?.let { id("$name/vendor/$vendor/tier/$it") }, "vendor_tier" to null,
            "assigned_at" to origin, "release_effective_on" to null, "released_at" to null, "vendor_last_activity_at" to null, "note" to null,
            "version" to 1, "updated_at" to origin)
        row("enrollment.seat_assignment_events", "seat_assignment_id" to seatId, "version" to 1, "tenant_id" to tenant, "state" to "assigned", "source" to "manual",
            "member_id" to memberIndex?.let(member), "member_link" to link, "tier_id" to tier?.let { id("$name/vendor/$vendor/tier/$it") }, "vendor_tier" to null,
            "release_effective_on" to null, "note" to null, "actor_id" to member(0), "sync_run_id" to null, "operation_id" to null,
            "recorded_at" to origin, "assigned_at" to origin)
    }
    // 관리자가 기록했다. 이메일이 구성원과 같으면 이메일 일치로 잇는다.
    listOf(4 to "standard", 8 to "standard", 10 to "standard", 1 to "premium").forEach { (index, tier) ->
        seat("claude_team/member$index", "claude_team", email(index), index, "email_match", tier)
    }
    seat("claude_team/contractor", "claude_team", "contractor@partner.example.test", null, null, null)
    listOf(3, 5).forEach { index -> seat("openai_biz/member$index", "openai_biz", email(index), index, "email_match", "standard") }
}

/**
 * C 의 벤더 청구 누계(ADR 0050) — 예외 데이터다. Cursor Enterprise 를 연결했는데 기준일 0시 실행에서 좌석 목록은 일시 장애로 실패하고(좌석 원장 없음 —
 * `seat_sync_failing`), 같은 실행의 청구 누계(이번 청구 주기의 on-demand 지출)는 읽었다. 청구 행의 원천은 `seed` 다 — **실제 청구의 증거가 아니다.**
 * 주기 시작은 벤더가 정한다(기준일 12일 전). 금액은 계약액(3석 × $40 = $120)과 다르다.
 */
private fun meteredBilling(name: String, tenant: String, origin: String, readAt: String, cycleStart: String, asOf: LocalDate,
    add: (String, Array<out Pair<String, Any?>>) -> Unit, member: (Int) -> String) {
    fun row(table: String, vararg fields: Pair<String, Any?>) = add(table, fields)
    val vendorId = id("$name/vendor/cursor")
    row("enrollment.managed_vendors", "tenant_id" to tenant, "vendor_id" to vendorId, "kind" to "cursor", "source" to "manual", "created_at" to origin)
    row("enrollment.vendor_contract_versions", "tenant_id" to tenant, "vendor_id" to vendorId, "version" to 1, "display_name" to "Cursor",
        "contract" to encode(mapOf("version" to 1, "planId" to "cursor_enterprise", "effectiveFrom" to asOf.minusDays(60).toString(),
            "effectiveTo" to asOf.plusDays(305).toString(), "termNote" to "합성 테스트 계약 · 청구 누계",
            "tiers" to listOf(mapOf("tierId" to id("$name/vendor/cursor/tier/enterprise"), "label" to "Enterprise", "seats" to 3, "monthlyFeePerSeatUsd" to "40")),
            "monthlySeatFeeUsd" to "120", "confirmedAt" to origin, "confirmedBy" to member(0))),
        "archived" to false, "recorded_at" to origin, "recorded_by" to member(0))
    row("enrollment.vendor_connections", "id" to id("$name/vendor-connection/cursor"), "tenant_id" to tenant, "vendor_id" to vendorId, "connector" to "cursor_enterprise",
        "settings" to encode(emptyMap<String, String>()), "credential_ciphertext" to "c2VlZC1wbGFjZWhvbGRlci1ub3QtYS1zZWNyZXQ=",
        "credential_key_id" to "seed-placeholder", "credential_updated_at" to origin, "check_status" to "unverified", "checked_at" to null,
        "sync_claimed_by" to null, "sync_claimed_until" to null, "last_sync_succeeded_at" to null, "last_sync_failed_at" to readAt, "last_sync_error" to "vendor_unavailable",
        "last_billing_succeeded_at" to readAt, "last_billing_failed_at" to null, "last_billing_error" to null,
        "version" to 1, "created_at" to origin, "created_by" to member(0), "updated_at" to origin, "updated_by" to member(0), "deleted_at" to null, "deleted_by" to null,
        "sync_requested_operation_id" to null)
    row("enrollment.vendor_billing_periods", "tenant_id" to tenant, "vendor_id" to vendorId, "period_start" to cycleStart, "period_end" to readAt,
        "amount_usd" to "137.42", "kind" to "usage_spend", "finalized" to false, "source" to "seed", "connection_id" to null, "fetched_at" to readAt)
}

/** B는 조직과 오너만 만든다. A/C의 팀·설정·수집 데이터 생성 경로를 공유하지 않는다. */
/** 정책·벤더·팀이 없는 조직과 오너 하나. B 는 첫 온보딩 화면용, D 는 온보딩을 끝까지 돌리는 파괴적 E2E 용이다(A·B·C 를 바꾸지 않는다). */
private fun emptyOrganization(name: String, label: String, asOf: LocalDate): SeedData {
    val tenant = id(name)
    val origin = asOf.minusDays(60).atStartOfDay(seoul).toInstant().toString()
    val rows = linkedMapOf<String, MutableList<Row>>(
        "enrollment.tenants" to mutableListOf(linkedMapOf(
            "id" to tenant, "name" to "시드 $name · $label", "slug" to "pulsemetry-seed-${name.lowercase()}",
            "timezone" to "Asia/Seoul", "status" to "active", "created_at" to origin, "updated_at" to origin,
        )),
        "enrollment.members" to mutableListOf(linkedMapOf(
            "id" to id("$name/member/0"), "tenant_id" to tenant, "email" to "owner@seed-${name.lowercase()}.example.test",
            "display_name" to "$name 구성원 00", "role" to "owner", "status" to "active", "oidc_subject" to null,
            "created_at" to origin, "updated_at" to origin,
        )),
    )
    return SeedData(name, asOf, rows, emptyList(), emptyList(), emptyMap())
}

/**
 * E — 원격 데몬 흐름용 조직. 오너가 수집 정책 1판을 저장했고(온보딩의 정책 확인까지, 벤더·팀은 없다) 초대한 구성원 하나의 설치 코드가 있다.
 * 설치·수신·설치 보고는 없다 — 데몬이 그 코드로 등록하는 데서 시작한다. 코드는 plan 의 `invitation_codes.pending` 이다(개발 시드 전용 값).
 */
private fun enrollmentOrganization(asOf: LocalDate, otlpEndpoint: String): SeedData {
    val name = "E"
    val base = emptyOrganization(name, "데몬 등록", asOf)
    val tenant = base.tenantId
    val origin = asOf.minusDays(60).atStartOfDay(seoul).toInstant().toString()
    val rows = base.rows
    fun add(table: String, vararg fields: Pair<String, Any?>) { rows.getOrPut(table) { mutableListOf() }.add(linkedMapOf(*fields)) }
    val owner = id("$name/member/0")
    add("enrollment.members", "id" to id("$name/member/1"), "tenant_id" to tenant, "email" to "member1@seed-e.example.test",
        "display_name" to "E 구성원 01", "role" to "member", "status" to "invited", "oidc_subject" to null, "created_at" to origin, "updated_at" to origin)
    val privacy = listOf("user_prompts", "assistant_responses", "tool_details", "tool_content", "user_email", "raw_api_bodies").associate { "collect_$it" to false }
    val manifest = linkedMapOf("schema_version" to 1, "config_revision" to 1, "otlp" to mapOf("endpoint" to otlpEndpoint, "protocol" to "http/protobuf"),
        "signals" to mapOf("logs" to true, "metrics" to true, "traces" to true), "privacy" to privacy)
    add("enrollment.manifests", "id" to id("$name/manifest"), "tenant_id" to tenant, "version" to 1, "manifest" to encode(manifest),
        "is_active" to true, "created_by_member_id" to owner, "created_at" to origin, "activated_at" to origin)
    add("enrollment.organization_onboarding", "tenant_id" to tenant, "policy_confirmed_at" to origin, "policy_confirmed_by" to owner, "completed_by" to null)
    val code = hash("seed-$name-1-pending").take(12).uppercase().chunked(4).joinToString("-")
    add("enrollment.invitations", "id" to id("$name/invitation/1/pending"), "tenant_id" to tenant, "target_member_id" to id("$name/member/1"),
        "created_by_member_id" to owner, "code_hash" to hash(code), "used_at" to null,
        "expires_at" to asOf.plusDays(30).atStartOfDay(seoul).toInstant().toString(), "created_at" to origin)
    return SeedData(name, asOf, rows, emptyList(), emptyList(), mapOf("pending" to code), otlpEndpoint)
}
