package com.team376.pulsemetry.devseed

import java.math.BigDecimal
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.HexFormat

internal const val SOMA_DATASET = "soma-history-v1"
internal val somaTeamSizes = List(5) { 20 } + List(10) { 12 } + List(15) { 8 } + List(20) { 3 }
internal val somaMemberTeams = buildMap {
    var member = 0
    somaTeamSizes.forEachIndexed { team, size -> repeat(size) { put(member++, team) } }
}

private fun earlier(left: String, right: String) = if (Instant.parse(left) <= Instant.parse(right)) left else right
private fun later(left: String, right: String) = if (Instant.parse(left) >= Instant.parse(right)) left else right

/** 실행 시각과 대상을 파일에 고정한다. 파일 생성은 네트워크·DB·스키마 초기화를 수행하지 않는다. */
internal data class HistoricalSeedConfig(
    val environment: String,
    val endDate: LocalDate,
    val snapshotAt: Instant,
    val endpoint: String,
    val oidcIssuer: String,
    val oidcClientId: String,
    val oidcSecretRef: String,
    val activityProfile: String = "rich",
    val stageAlertRules: Boolean = false,
    val perfEnvironmentId: String? = null,
) {
    val startDate: LocalDate = endDate.minusDays(179)
    val isPerf = environment == "perf"
    val namespace = if (isPerf) "perf/$perfEnvironmentId/$SOMA_DATASET" else "$environment/$SOMA_DATASET"
    val tenantId = id(namespace)
    val tenantName = if (isPerf) "[SYNTHETIC] soma $perfEnvironmentId" else "soma"
    val tenantSlug = if (isPerf) "synthetic-$perfEnvironmentId-soma" else "soma"
    val context = SeedUsageContext(namespace, "soma", somaMemberTeams, somaTeamSizes.size)
    fun identity(key: String) = context.identity(key)
    fun email(index: Int) = if (isPerf) when (index) {
        0 -> "owner@$perfEnvironmentId.example.test"
        1 -> "admin@$perfEnvironmentId.example.test"
        else -> "member$index@$perfEnvironmentId.example.test"
    } else when (index) {
        0 -> "owner@seed-a.example.test"
        1 -> "admin@seed-a.example.test"
        else -> "member$index@seed-soma.example.test"
    }
    init {
        require(environment in setOf("local", "dev", "perf")) { "과거 시드는 local, dev 또는 격리 perf만 지원합니다." }
        if (isPerf) {
            require(perfEnvironmentId != null && Regex("perf-[a-z0-9][a-z0-9-]{2,42}").matches(perfEnvironmentId)) {
                "perf에는 perf-로 시작하는 독립 환경 ID가 필요합니다."
            }
            require(stageAlertRules) { "perf는 --stage-alert-rules true가 필요합니다." }
            require(activityProfile == "rich") { "perf 초기 기준은 기존 rich 프로필입니다." }
            require(listOf(oidcIssuer, oidcClientId, oidcSecretRef).all(String::isEmpty)) { "perf는 OIDC 연결을 받지 않습니다." }
        } else require(perfEnvironmentId == null) { "perf 환경 ID는 perf에서만 지정합니다." }
        require(activityProfile in setOf("rich", "team-daily")) { "활동 프로필은 rich 또는 team-daily여야 합니다." }
        require(endDate == snapshotAt.atZone(seoul).toLocalDate().minusDays(1)) { "마지막 날은 고정 실행시각의 전날이어야 합니다." }
        require(snapshotAt >= endDate.plusDays(1).atStartOfDay(seoul).toInstant().plusSeconds(600)) {
            "마지막 날의 수집 완료 판정을 위해 다음 날 00:10 KST 이후의 실행시각이 필요합니다."
        }
        validateHistoricalUrl(endpoint, environment)
        if (!isPerf) {
            validateHistoricalUrl(oidcIssuer, environment)
            require(oidcClientId.isNotBlank() && oidcClientId.length <= 255)
            require(Regex("config:[A-Za-z0-9_-]+").matches(oidcSecretRef)) { "OIDC 비밀 원문 대신 config 참조를 지정하세요." }
        }
    }
    fun descriptor(): Row = linkedMapOf(
        "datasetId" to SOMA_DATASET, "environment" to environment, "tenantId" to tenantId,
        "tenantName" to tenantName, "tenantSlug" to tenantSlug, "teamSizes" to somaTeamSizes,
        "range" to linkedMapOf("start" to startDate.toString(), "end" to endDate.toString(), "timezone" to "Asia/Seoul"),
        "snapshotAt" to snapshotAt.toString(), "otlpEndpoint" to endpoint,
        "oidc" to if (isPerf) null else linkedMapOf("issuer" to oidcIssuer, "clientId" to oidcClientId, "clientSecretRef" to oidcSecretRef),
    ).apply {
        if (isPerf) {
            put("perfEnvironmentId", perfEnvironmentId)
            put("provenance", "synthetic-regeneration")
            put("representativeness", "INCONCLUSIVE")
            put("activationPolicy", "disabled")
            put("activityProfileName", activityProfile)
        }
        // 기존 rich 파일의 설정 지문은 보존한다. 저밀도 후보는 명시적으로 선택하고 manifest에 구분한다.
        if (activityProfile != "rich") put("activityProfile", linkedMapOf(
            "name" to activityProfile, "dailyTeamSessions" to 1,
            "weekdayCallsPerSession" to 2, "weekendCallsPerSession" to 1,
            "memberSelection" to "team-roster-round-robin"))
        if (stageAlertRules) put("stageAlertRules", true)
    }
}

private fun validateHistoricalUrl(raw: String, environment: String) {
    val uri = runCatching { URI(raw) }.getOrNull()
    require(uri != null && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.scheme == "https" || environment == "local" && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) {
        "HTTPS 주소가 필요합니다. local에서만 loopback HTTP를 허용합니다."
    }
}

internal fun runHistoricalPlan(args: Array<String>) {
    val allowed = setOf("output", "end-date", "snapshot-at", "environment", "otlp-endpoint", "oidc-config",
        "oidc-issuer", "oidc-client-id", "oidc-secret-ref", "activity-profile", "stage-alert-rules", "perf-environment-id")
    require(args.size % 2 == 1) { "historical-plan 옵션은 --이름 값 형식입니다." }
    val options = linkedMapOf<String, String>()
    args.drop(1).chunked(2).forEach { (name, value) ->
        require(name.startsWith("--") && name.drop(2) in allowed && options.put(name.drop(2), value) == null) { "알 수 없거나 중복된 옵션: $name" }
    }
    fun required(key: String) = requireNotNull(options[key]) { "--$key 옵션이 필요합니다." }
    val environment = required("environment")
    if (environment == "perf") {
        require(options.keys.none { it.startsWith("oidc-") }) { "perf는 OIDC 옵션을 받지 않습니다." }
        required("perf-environment-id")
        required("activity-profile")
    }
    val overrideKeys = setOf("oidc-issuer", "oidc-client-id", "oidc-secret-ref")
    val override = overrideKeys.any(options::containsKey)
    require(!override || environment == "local" && overrideKeys.all(options::containsKey) && !options.containsKey("oidc-config")) {
        "OIDC override 세 옵션은 local에서만 함께 지정할 수 있습니다."
    }
    val publicOidc = if (override || environment == "perf") null else parseCompanyOidcSeed(Files.readString(Path.of(options["oidc-config"]
        ?: "tools/dev-seed/config/cognito/company-a/cognito-seed.json"))).also { require(it.company == "A") }
    val config = HistoricalSeedConfig(environment, LocalDate.parse(required("end-date")), Instant.parse(required("snapshot-at")),
        required("otlp-endpoint"), publicOidc?.issuer ?: if (environment == "perf") "" else required("oidc-issuer"),
        publicOidc?.clientId ?: if (environment == "perf") "" else required("oidc-client-id"),
        publicOidc?.secretRef ?: if (environment == "perf") "" else required("oidc-secret-ref"),
        options["activity-profile"] ?: "rich",
        options["stage-alert-rules"]?.let { requireNotNull(it.toBooleanStrictOrNull()) { "--stage-alert-rules 값은 true 또는 false여야 합니다." } } ?: false,
        options["perf-environment-id"])
    val manifest = exportHistoricalSeed(config, Path.of(required("output")))
    println(json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest))
}

/** 환경이 달라도 사용량·비용·시각은 같다. 환경은 신원 공간에만 영향을 준다. */
internal fun somaSessions(config: HistoricalSeedConfig, date: LocalDate): List<SeedSessionPlan> {
    require(date in config.startDate..config.endDate)
    val volume = listOf(75, 100, 85, 115, 95, 135)[((date.toEpochDay() - config.startDate.toEpochDay()) / 30).toInt()]
    val weekend = date.dayOfWeek.value >= 6
    if (config.activityProfile == "team-daily") {
        val dayIndex = (date.toEpochDay() - config.startDate.toEpochDay()).toInt()
        return somaTeamSizes.indices.map { team ->
            val roster = somaMemberTeams.filter { it.value == team && it.key >= 2 }.keys.toList()
            val member = roster[(dayIndex + team) % roster.size]
            SeedSessionPlan(date, member, 0, if (weekend) 1 else 2, keyNamespace = "soma-history-v1/usage")
        }
    }
    return buildList {
        somaTeamSizes.indices.forEach { team ->
            val roster = somaMemberTeams.filter { it.value == team && it.key >= 2 }.keys.toList()
            val active = if (weekend) listOf(roster[noise("soma/$date/$team/weekend", roster.size)]) else roster
            active.forEach { member ->
                val sessions = if (weekend) 1 else if (member % 19 == 0) 2 else 1
                repeat(sessions) { slot ->
                    val key = "soma-history-v1/usage/$date/$member/$slot/daily"
                    val base = when (team % 10) { 3, 9 -> 2; 7 -> 4; 0, 5, 8 -> 3; else -> 2 }
                    val calls = if (weekend) 2 else maxOf(2, (base + noise("$key/calls", 4)) * volume / 100)
                    add(SeedSessionPlan(date, member, slot, calls, keyNamespace = "soma-history-v1/usage"))
                }
            }
        }
    }
}

/** A의 작은 설정 행만 재사용한다. 회원·팀·설치·보고·요약은 독립 soma 명단으로 재구성한다. */
internal fun somaPostgres(config: HistoricalSeedConfig): LinkedHashMap<String, MutableList<Row>> {
    val template = historicalOrganizationTemplate(config.endDate, config.endpoint)
    val replacements = buildMap {
        put(id("A"), config.tenantId)
        for (index in 0..399) for (kind in listOf("member", "installation", "team"))
            put(id("A/$kind/$index"), config.identity("$kind/$index"))
    }
    val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun remap(value: Any?): Any? = when (value) {
        is String -> uuid.replace(value) { replacements[it.value] ?: config.identity("template/${it.value}") }
            .replace(Regex("member([0-9]+)@seed-a\\.example\\.test")) { config.email(it.groupValues[1].toInt()) }
        else -> value
    }
    val rows = linkedMapOf<String, MutableList<Row>>()
    template.rows.forEach { (table, values) -> rows[table] = values.map { it.mapValues { (_, value) -> remap(value) } }.toMutableList() }
    val origin = config.endDate.minusDays(184).atStartOfDay(seoul).toInstant().toString()
    val installed = config.endDate.minusDays(182).atStartOfDay(seoul).toInstant().toString()
    val changed = config.endDate.minusDays(7).atStartOfDay(seoul)
    val manifest = rows.getValue("enrollment.manifests").associate { (it["version"] as Number).toInt() to it.getValue("id") }
    val owner = config.identity("member/0")
    rows["enrollment.tenants"] = mutableListOf(rows.getValue("enrollment.tenants").single() + linkedMapOf(
        "name" to config.tenantName, "slug" to config.tenantSlug,
        "oidc_issuer" to config.oidcIssuer.takeUnless { config.isPerf }, "oidc_client_id" to config.oidcClientId.takeUnless { config.isPerf },
        "oidc_client_secret_ref" to config.oidcSecretRef.takeUnless { config.isPerf }, "sso_enabled" to false, "oidc_require_verified_email" to true))
    rows["enrollment.members"] = (0..399).map { index -> linkedMapOf<String, Any?>(
        "id" to config.identity("member/$index"), "tenant_id" to config.tenantId, "email" to config.email(index),
        "display_name" to "soma 구성원 %03d".format(index + 1), "role" to when (index) { 0 -> "owner"; 1 -> "admin"; else -> "member" },
        "status" to "active", "oidc_subject" to null, "created_at" to origin, "updated_at" to origin) }.toMutableList()
    rows["enrollment.teams"] = somaTeamSizes.indices.map { team -> linkedMapOf<String, Any?>(
        "id" to config.identity("team/$team"), "tenant_id" to config.tenantId,
        "name" to "%02d %s".format(team + 1, A_TEAM_NAMES[team % A_TEAM_NAMES.size]),
        "status" to "active", "created_at" to origin, "updated_at" to origin) }.toMutableList()
    rows["enrollment.team_memberships"] = somaMemberTeams.map { (member, team) -> linkedMapOf<String, Any?>(
        "id" to config.identity("membership/$member/$team"), "member_id" to config.identity("member/$member"),
        "team_id" to config.identity("team/$team"), "joined_at" to origin, "left_at" to null) }.toMutableList()
    for (table in listOf("invitations", "installations", "installation_manifest_assignments", "installation_heartbeats", "installation_collection_segments"))
        rows["enrollment.$table"] = mutableListOf()
    fun add(table: String, vararg fields: Pair<String, Any?>) { rows.getValue("enrollment.$table").add(linkedMapOf(*fields)) }
    for (index in 2..399) {
        val member = config.identity("member/$index")
        val installation = config.identity("installation/$index")
        val invitation = config.identity("invitation/$index/used")
        val platform = listOf("windows", "macos", "linux")[index % 3]
        val architecture = if (platform == "macos") "arm64" else "amd64"
        val revision = if (index % 5 != 0) 2 else 1
        val run = hash("${config.namespace}/daemon-run/$index").take(32)
        add("invitations", "id" to invitation, "tenant_id" to config.tenantId, "target_member_id" to member,
            "created_by_member_id" to owner, "code_hash" to hash("${config.namespace}/used-invitation/$index"),
            "used_at" to installed, "expires_at" to config.endDate.minusDays(181).atStartOfDay(seoul).toInstant().toString(), "created_at" to origin)
        add("installations", "id" to installation, "tenant_id" to config.tenantId, "member_id" to member, "invitation_id" to invitation,
            "hostname" to "seed-soma-$index", "platform" to platform, "architecture" to architecture, "client_version" to "seed-v1",
            "status" to "active", "last_seen_at" to config.snapshotAt.toString(), "created_at" to installed, "updated_at" to installed)
        add("installation_manifest_assignments", "installation_id" to installation, "manifest_id" to manifest.getValue(1),
            "assigned_at" to installed, "applied_at" to installed)
        if (revision == 2) {
            val at = changed.plusHours((index % 12 + 1).toLong()).toInstant().toString()
            add("installation_manifest_assignments", "installation_id" to installation, "manifest_id" to manifest.getValue(2), "assigned_at" to at, "applied_at" to at)
        }
        add("installation_heartbeats", "installation_id" to installation, "received_at" to config.snapshotAt.toString(), "run_id" to run,
            "daemon_version" to "seed-v1", "architecture" to architecture, "applied_config_revision" to revision,
            "applied_manifest_id" to manifest.getValue(revision), "mode" to "local", "forwarding" to true, "receiving_since" to installed,
            "delivered" to 0L, "lost" to 0, "pending" to 0, "last_delivered_at" to null, "pending_since" to null)
        add("installation_collection_segments", "id" to config.identity("collection-segment/$index"), "installation_id" to installation,
            "run_id" to run, "from_at" to installed, "to_at" to config.snapshotAt.toString(), "lost" to 0)
    }
    if (config.isPerf) rows.getValue("enrollment.organization_alert_rules").replaceAll { it + ("enabled" to false) }
    return rows
}

internal class HistoricalTotals {
    var events = 0L
    private val tokens = linkedMapOf("tokensInputUncached" to "tokens_input_uncached", "tokensOutput" to "tokens_output",
        "tokensCacheRead" to "tokens_cache_read", "tokensCacheCreate" to "tokens_cache_create", "tokensTotal" to "tokens_total_derived")
    private val sums = tokens.keys.associateWith { 0L }.toMutableMap()
    private var cost = BigDecimal.ZERO
    fun add(row: Row) {
        events++
        tokens.forEach { (name, column) -> sums[name] = Math.addExact(sums.getValue(name), (row.getValue(column) as Number).toLong()) }
        cost += row.getValue("cost_estimated_usd").toString().toBigDecimal()
    }
    fun value(): Row = linkedMapOf<String, Any?>("events" to events).apply {
        putAll(sums); put("costEstimatedUsd", cost.toPlainString())
    }
}

/** 최대 하루의 생성 행과 한 배치 문자열만 보관한다. 월이 바뀌면 두 테이블의 미완성 배치도 닫는다. */
internal fun exportHistoricalSeed(config: HistoricalSeedConfig, output: Path): Row {
    require(!Files.exists(output)) { "출력 디렉터리가 이미 있습니다. 기존 검증 파일을 덮어쓰지 않습니다." }
    Files.createDirectory(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val rows = somaPostgres(config)
    val descriptor = config.descriptor().toMutableMap()
    if (config.stageAlertRules) {
        val rules = rows.getValue("enrollment.organization_alert_rules")
        descriptor["activationRules"] = rules.map { row -> linkedMapOf(
            "ruleId" to row.getValue("rule_id"), "desiredEnabled" to row.getValue("enabled"),
            "expectedVersion" to row.getValue("version")) }.sortedBy { it.getValue("ruleId").toString() }
        rules.replaceAll { row -> row + ("enabled" to false) }
    }
    val totals = HistoricalTotals()
    val current = HistoricalTotals()
    val previous = HistoricalTotals()
    val batches = mutableListOf<Row>()
    val months = mutableListOf<Row>()
    val delivered = mutableMapOf<String, Long>()
    val lastDelivered = mutableMapOf<String, String>()
    var firstReceived: String? = null
    var firstObserved: String? = null
    var lastReceived: String? = null
    var receipts = 0L
    var pairs = 0
    var minSessions = Int.MAX_VALUE
    var maxDayEvents = 0
    val context = config.context
    var month = YearMonth.from(config.startDate)
    while (month <= YearMonth.from(config.endDate)) {
        val monthly = HistoricalTotals()
        val eventWriter = HistoricalBatchWriter(output, month.toString(), "telemetry_events", "source_time", "observation_id", batches)
        val receiptWriter = HistoricalBatchWriter(output, month.toString(), "telemetry_ingest_ledger", "received_time", "receipt_id", batches)
        var day = maxOf(config.startDate, month.atDay(1))
        val until = minOf(config.endDate, month.atEndOfMonth())
        var monthlyReceipts = 0L
        while (day <= until) {
            val sessions = somaSessions(config, day)
            val usage = detailedUsage(config.endDate, rows, sessions, context)
            validateHistoricalDay(config, day, usage)
            val teamSessions = sessions.groupingBy { somaMemberTeams.getValue(it.memberIndex) }.eachCount()
            check(teamSessions.keys == somaTeamSizes.indices.toSet()) { "$day: 빈 팀이 있습니다." }
            pairs += teamSessions.size
            minSessions = minOf(minSessions, teamSessions.values.min())
            maxDayEvents = maxOf(maxDayEvents, usage.events.size)
            for (row in usage.events) {
                totals.add(row); monthly.add(row)
                if (day >= config.endDate.minusDays(27)) current.add(row)
                else if (day >= config.endDate.minusDays(55)) previous.add(row)
                val source = row.getValue("source_time").toString()
                firstObserved = firstObserved?.let { earlier(it, source) } ?: source
                eventWriter.add(row)
            }
            for (row in usage.ledger) {
                val installation = row.getValue("installation_id").toString()
                val received = row.getValue("received_time").toString()
                delivered[installation] = delivered.getOrDefault(installation, 0) + 1
                lastDelivered[installation] = lastDelivered[installation]?.let { later(it, received) } ?: received
                firstReceived = firstReceived?.let { earlier(it, received) } ?: received
                lastReceived = lastReceived?.let { later(it, received) } ?: received
                receiptWriter.add(row)
            }
            receipts += usage.ledger.size; monthlyReceipts += usage.ledger.size
            day = day.plusDays(1)
        }
        eventWriter.finish(); receiptWriter.finish()
        months.add(linkedMapOf("month" to month.toString(), "receipts" to monthlyReceipts, "totals" to monthly.value()))
        month = month.plusMonths(1)
    }
    check(pairs == 9_000 && delivered.size == 398)
    rows.getValue("enrollment.installation_heartbeats").replaceAll { row ->
        val installation = row.getValue("installation_id").toString()
        row + mapOf("delivered" to delivered.getValue(installation), "last_delivered_at" to lastDelivered.getValue(installation))
    }
    rows["telemetry_ops.tenant_ingest_summary"] = mutableListOf(linkedMapOf(
        "tenant_id" to config.tenantId, "first_received_at" to firstReceived, "first_observed_at" to firstObserved,
        "last_received_at" to lastReceived, "has_pre_ledger_history" to false, "updated_at" to config.snapshotAt.toString()))
    val pgFile = output.resolve("postgres.json")
    privateWrite(pgFile, encode(mapOf("tables" to rows.map { (table, values) -> mapOf("table" to table, "rows" to values) })))
    val manifest = linkedMapOf<String, Any?>("schemaVersion" to 1).apply {
        putAll(descriptor)
        put("postgres", linkedMapOf("file" to "postgres.json", "sha256" to fileSha256(pgFile), "tables" to rows.mapValues { it.value.size }))
        put("batches", batches.sortedWith(compareBy({ it["timeMin"].toString() }, { it["table"].toString() }, { it["id"].toString() })))
        put("summary", linkedMapOf("members" to 400, "teams" to 50, "installations" to 398, "completeInstallations" to delivered.size,
            "events" to totals.events, "receipts" to receipts, "sessions" to receipts, "days" to 180,
            "dailyTeamPairs" to pairs, "minDailyTeamSessions" to minSessions, "maxBufferedDayEvents" to maxDayEvents,
            "totals" to totals.value(), "current28Days" to current.value(), "previous28Days" to previous.value(), "months" to months))
        put("configHash", fingerprintOf(descriptor))
    }
    // 완료 manifest는 모든 파일의 재독해·해시 검증 뒤 마지막에 쓴다.
    privateWrite(output.resolve("manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest))
    return manifest
}

/** 실제 직렬화할 관측과 수신을 맞대어 빠진 토큰·다른 신원·집계 불일치를 생성 단계에서 거부한다. */
internal fun validateHistoricalDay(config: HistoricalSeedConfig, day: LocalDate, usage: SeedUsage) {
    check(usage.events.map { it["observation_id"] }.toSet().size == usage.events.size)
    check(usage.ledger.map { it["receipt_id"] }.toSet().size == usage.ledger.size)
    val installations = (2..399).associate { config.identity("installation/$it") to it }
    val pushed = usage.events.groupBy { listOf(it["installation_id"], it["received_time"], it["product"]) }
    for (row in usage.events) {
        check(row["tenant_id"] == config.tenantId)
        val member = installations.getValue(row.getValue("installation_id").toString())
        check(row["member_id"] == config.identity("member/$member"))
        check(row["team_id_as_of"] == config.identity("team/${somaMemberTeams.getValue(member)}"))
        check(Instant.parse(row.getValue("source_time").toString()).atZone(seoul).toLocalDate() == day)
        check(Instant.parse(row.getValue("received_time").toString()) <= config.snapshotAt)
        fun token(key: String) = (row.getValue(key) as Number).toLong().also { check(it >= 0) }
        val uncached = token("tokens_input_uncached"); val read = token("tokens_cache_read")
        val created = token("tokens_cache_create"); val output = token("tokens_output")
        check(token("tokens_total_derived") == uncached + read + created + output)
        val claude = row["product"] == "claude_code"
        check(token("tokens_input") == if (claude) uncached else uncached + read + created)
        check(row["semantics_profile"] == if (claude) "claude-code-exclusive-v1" else "codex-responses-inclusive-v1")
        if (!claude) check(created == 0L)
        val model = seedModels.getValue(row.getValue("product").toString()).single { it.name == row["model"] }
        check(model.cost(uncached, output, read, created).compareTo(row.getValue("cost_estimated_usd").toString().toBigDecimal()) == 0)
    }
    for (receipt in usage.ledger) {
        check(receipt["tenant_id"] == config.tenantId)
        val events = pushed[listOf(receipt["installation_id"], receipt["received_time"], receipt["product"])].orEmpty()
        check(events.size == (receipt.getValue("record_count") as Number).toInt() && events.isNotEmpty())
        check(events.map { it["session_id"] }.toSet().size == 1)
        check(receipt["source_time_min"] == events.minOf { it.getValue("source_time").toString() })
        check(receipt["source_time_max"] == events.maxOf { it.getValue("source_time").toString() })
    }
    check(usage.ledger.sumOf { (it.getValue("record_count") as Number).toInt() } == usage.events.size)
}

internal class HistoricalBatchWriter(private val output: Path, private val month: String, private val table: String,
    private val timeColumn: String, private val identityColumn: String, private val batches: MutableList<Row>) {
    private val body = StringBuilder()
    private var count = 0
    private var bytes = 0
    private var sequence = 0
    private var timeMin: String? = null
    private var timeMax: String? = null
    fun add(row: Row) {
        val encoded = encode(row)
        val size = encoded.toByteArray(Charsets.UTF_8).size + 1
        require(size <= SEED_BATCH_BYTES)
        if (count == SEED_BATCH_ROWS || bytes + size > SEED_BATCH_BYTES) finish()
        val time = row.getValue(timeColumn).toString()
        check(YearMonth.from(Instant.parse(time).atZone(seoul)).toString() == month)
        body.append(encoded).append('\n'); count++; bytes += size
        timeMin = timeMin?.let { earlier(it, time) } ?: time
        timeMax = timeMax?.let { later(it, time) } ?: time
    }
    fun finish() {
        if (count == 0) return
        val batchId = "$month-$table-${sequence++.toString().padStart(4, '0')}"
        val filename = "$batchId.jsonl"
        val file = output.resolve(filename)
        val expectedHash = hash(body.toString())
        privateWrite(file, body.toString())
        check(Files.size(file) == bytes.toLong() && fileSha256(file) == expectedHash)
        var observed = 0
        val identities = HashSet<String>()
        Files.newBufferedReader(file).useLines { lines -> lines.forEach { line ->
            val row = json.readTree(line)
            check(identities.add(row.path(identityColumn).asString()))
            check(Instant.parse(row.path(timeColumn).asString()) in Instant.parse(timeMin)..Instant.parse(timeMax))
            observed++
        } }
        check(observed == count)
        batches.add(linkedMapOf("id" to batchId, "table" to table, "file" to filename, "sha256" to expectedHash,
            "rows" to count, "bytes" to bytes, "timeColumn" to timeColumn, "timeMin" to timeMin, "timeMax" to timeMax,
            "identityColumn" to identityColumn))
        body.setLength(0); count = 0; bytes = 0; timeMin = null; timeMax = null
    }
}

private fun privateWrite(path: Path, text: String) {
    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.writeString(path, text, StandardOpenOption.WRITE)
}

internal fun fileSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
