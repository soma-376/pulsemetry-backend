package com.team376.pulsemetry.devseed

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator

import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneOffset

/** 로컬 compose DB 전용. 연결 문자열을 운영 주소로 바꾸는 옵션은 제공하지 않는다. */
class SeedStore private constructor(private val pg: Connection, private val clickHouseUrl: String) : AutoCloseable {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    companion object {
        const val COMPOSE_JDBC_URL = "jdbc:postgresql://postgres:5432/pulsemetry?connectTimeout=5&socketTimeout=120"

        /** 개발 Compose 전용. 외부 주소 입력과 Docker 소켓 접근은 허용하지 않는다. */
        fun openCompose(): SeedStore {
            check(System.getenv("PULSEMETRY_DEV_SEED_MODE") == "compose") { "개발 Compose에서만 실행할 수 있습니다." }
            return connect(COMPOSE_JDBC_URL, "http://clickhouse:8123")
        }

        /** 테스트의 격리 DB도 같은 잠금·트랜잭션 경로를 검증한다. CLI는 openCompose만 사용한다. */
        internal fun connect(jdbcUrl: String, clickHouseUrl: String): SeedStore {
            val connection = DriverManager.getConnection(jdbcUrl, "pulsemetry", "pulsemetry")
            val store = SeedStore(connection, clickHouseUrl)
            try {
                check(store.scalar("SELECT pg_try_advisory_lock(376, 20260928)") == "t") { "다른 시드 작업이 실행 중입니다." }
                store.execute("SET statement_timeout='120s'; SET lock_timeout='5s'; SET TIME ZONE 'UTC';")
                return store
            } catch (error: Exception) { store.close(); throw error }
        }
    }

    fun execute(query: String) { pg.createStatement().use { it.execute(query) } }
    fun query(query: String): List<List<String?>> = pg.createStatement().use { statement ->
        statement.executeQuery(query).use { result -> buildList {
            while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
        } }
    }
    fun scalar(query: String): String? = query(query).firstOrNull()?.firstOrNull()
    fun transaction(block: () -> Unit) {
        pg.autoCommit = false
        try { block(); pg.commit() } catch (error: Exception) { pg.rollback(); throw error } finally { pg.autoCommit = true }
    }
    fun clickHouse(query: String): String {
        val request = HttpRequest.newBuilder(URI("$clickHouseUrl/?wait_end_of_query=1&date_time_input_format=best_effort&output_format_json_quote_decimals=1"))
            .timeout(Duration.ofSeconds(120)).POST(HttpRequest.BodyPublishers.ofString(query)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "ClickHouse ${response.statusCode()}: ${response.body().take(1500)}" }
        return response.body().trim()
    }
    fun chRows(query: String): List<JsonNode> = clickHouse("$query FORMAT JSONEachRow").lineSequence().filter { it.isNotBlank() }.map(json::readTree).toList()

    fun prepare(initializeClickHouse: Boolean = false) {
        // DDL은 원래 소유 모듈의 멱등 마이그레이션을 재사용한다(ADR 0028).
        // 개발 Compose 연결 검증·PG 잠금 획득 뒤에만 호출할 수 있다.
        if (initializeClickHouse) ClickHouseSchemaMigrator(ClickHouseHttpClient(clickHouseUrl)).apply()
        query("SELECT id, oidc_subject FROM enrollment.members LIMIT 0")
        query("SELECT tenant_id FROM telemetry_ops.tenant_ingest_summary LIMIT 0")
        clickHouse("SELECT observation_id FROM default.telemetry_events LIMIT 0")
        clickHouse("SELECT receipt_id FROM default.telemetry_ingest_ledger LIMIT 0")
        execute("""CREATE SCHEMA IF NOT EXISTS dev_seed;
            CREATE TABLE IF NOT EXISTS dev_seed.dashboard_datasets (
              tenant_id uuid PRIMARY KEY, fingerprint text NOT NULL,
              state text NOT NULL CHECK (state IN ('loading','ready','resetting')));
        """)
    }

    fun apply(data: SeedData): String {
        val tenant = sql(data.tenantId)
        val existing = scalar("SELECT fingerprint || ':' || state FROM dev_seed.dashboard_datasets WHERE tenant_id=$tenant")
        if (existing != null) {
            check(existing == "${data.fingerprint}:ready") { "${data.scenario}: 기준일/버전 변경 또는 미완료 적재입니다. 해당 시나리오를 reset 후 실행하세요." }
            verify(data)
            return "기존 시드 검증 완료 (추가 INSERT 없음)"
        }
        check(scalar("SELECT count(*) FROM enrollment.tenants WHERE id=$tenant") == "0") { "같은 조직 ID가 이미 존재합니다. 덮어쓰지 않습니다." }
        for (table in listOf("telemetry_events", "telemetry_ingest_ledger")) {
            check(clickHouse("SELECT count() FROM default.$table WHERE tenant_id=$tenant") == "0") { "실행 기록 없는 분석 데이터가 존재합니다." }
        }
        transaction {
            execute("INSERT INTO dev_seed.dashboard_datasets VALUES ($tenant,${sql(data.fingerprint)},'loading')")
            for ((table, rows) in data.rows) for (row in rows) {
                if (table == "telemetry_ops.tenant_ingest_summary") {
                    // 같은 트랜잭션에서 조직 트리거가 만든 빈 행에 A/C의 합성 이력을 채운다.
                    val values = row.filterKeys { it != "tenant_id" }.entries.joinToString { (key, value) -> "$key=${sql(value)}" }
                    execute("UPDATE $table SET $values WHERE tenant_id=$tenant")
                } else {
                    execute("INSERT INTO $table (${row.keys.joinToString()}) VALUES (${row.values.joinToString(transform = ::sql)})")
                }
            }
        }
        for ((table, rows) in listOf("telemetry_events" to data.events, "telemetry_ingest_ledger" to data.ledger)) {
            if (rows.isNotEmpty()) clickHouse("INSERT INTO default.$table FORMAT JSONEachRow\n" + rows.joinToString("\n", transform = ::encode))
        }
        verify(data)
        execute("UPDATE dev_seed.dashboard_datasets SET state='ready' WHERE tenant_id=$tenant")
        return "적재 및 검증 완료"
    }

    fun ensureOnStartup(name: String, asOf: LocalDate): String {
        require(name in listOf("A", "B", "C"))
        val tenant = sql(id(name))
        val state = scalar("SELECT state FROM dev_seed.dashboard_datasets WHERE tenant_id=$tenant")
        val slug = scalar("SELECT slug FROM enrollment.tenants WHERE id=$tenant")
        if (!shouldSeedOnStartup(state, slug, name)) return "이미 준비된 시드 — 개발 중 변경 유지 (검증은 verify)"
        return "$asOf 기준 ${apply(scenario(name, asOf))}"
    }

    fun verify(data: SeedData) {
        val tenant = sql(data.tenantId)
        check(scalar("SELECT count(*) FROM telemetry_ops.tenant_ingest_summary WHERE tenant_id=$tenant") == "1") {
            "${data.scenario}: 조직 생성 시 초기화해야 하는 수집 요약이 없습니다."
        }
        if (data.scenario == "B") check(scalar("""SELECT count(*) FROM telemetry_ops.tenant_ingest_summary
            WHERE tenant_id=$tenant AND first_received_at IS NULL AND first_observed_at IS NULL
            AND last_received_at IS NULL AND NOT has_pre_ledger_history""") == "1") { "B: 신규 조직의 수집 요약이 비어 있지 않습니다." }
        for ((table, rows) in data.rows) for (row in rows) {
            // 외부 IdP 연결은 시드 생성 후 별도 관리한다. 연결 변경을 시드 오염으로 판정하지 않는다.
            val fields = if (table == "enrollment.members") row.filterKeys { it !in setOf("oidc_issuer", "oidc_subject") } else row
            val predicate = fields.entries.joinToString(" AND ") { (key, value) -> "$key IS NOT DISTINCT FROM ${sql(value)}" }
            check(scalar("SELECT count(*) FROM $table WHERE $predicate") == "1") { "${data.scenario}: $table 시드 행이 기대값과 다릅니다." }
        }
        for ((table, expected, identity) in listOf(Triple("telemetry_events", data.events, "observation_id"), Triple("telemetry_ingest_ledger", data.ledger, "receipt_id"))) {
            val actual = chRows("SELECT * FROM default.$table FINAL WHERE tenant_id=$tenant").associateBy { it.path(identity).asString() }
            check(actual.keys == expected.map { it.getValue(identity).toString() }.toSet()) { "$table 행 식별자가 다릅니다." }
            for (row in expected) for ((key, value) in row) {
                val observed = actual.getValue(row.getValue(identity).toString()).path(key)
                val equal = when {
                    value == null -> observed.isNull
                    key in listOf("source_time", "received_time", "event_time", "observed_time", "end_time", "source_time_min", "source_time_max") -> {
                        val actualTime = LocalDateTime.parse(observed.asString().replace(' ', 'T')).toInstant(ZoneOffset.UTC)
                        actualTime == Instant.parse(value.toString())
                    }
                    value is Number -> observed.asString().toBigDecimal().compareTo(value.toString().toBigDecimal()) == 0
                    else -> observed == json.valueToTree<JsonNode>(value)
                }
                check(equal) { "${data.scenario}: $table.$key 값이 다릅니다: $observed / $value" }
            }
            check(clickHouse("SELECT count() FROM default.$table WHERE tenant_id=$tenant").toInt() == expected.size) { "${table}에 물리적 중복 적재가 있습니다." }
        }
        verifyTotals(data)
    }

    private fun verifyTotals(data: SeedData) {
        for ((dimension, expression) in mapOf("organization" to "'all'", "day" to "toString(toDate(source_time,'Asia/Seoul'))",
            "model" to "model", "team" to "ifNull(team_id_as_of,'unassigned')",
            "member" to "member_id", "product" to "product")) {
            fun key(row: Row): String = when(dimension) {
                "day" -> Instant.parse(row["source_time"].toString()).atZone(seoul).toLocalDate().toString()
                "model" -> row["model"].toString()
                "team" -> row["team_id_as_of"]?.toString() ?: "unassigned"
                "member" -> row["member_id"].toString()
                "product" -> row["product"].toString()
                else -> "all"
            }
            val expected = data.events.groupBy(::key).mapValues { (_, rows) ->
                rows.mapNotNull { it["cost_estimated_usd"] as? java.math.BigDecimal }.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add).stripTrailingZeros() to rows.count { it["cost_estimated_usd"] == null }
            }
            val actual = chRows("SELECT $expression AS key, toString(sum(ifNull(cost_estimated_usd,0))) AS amount, countIf(cost_estimated_usd IS NULL) AS missing " +
                "FROM default.telemetry_events FINAL WHERE tenant_id=${sql(data.tenantId)} GROUP BY key").associate {
                it.path("key").asString() to (it.path("amount").asString().toBigDecimal().stripTrailingZeros() to it.path("missing").asString().toInt())
            }
            check(actual == expected) { "${data.scenario}: $dimension 비용 합계 불일치" }
        }
    }

    fun reset(scenario: String): String {
        require(scenario in listOf("A", "B", "C"))
        val tenant = sql(id(scenario))
        if (scalar("SELECT tenant_id FROM dev_seed.dashboard_datasets WHERE tenant_id=$tenant") == null) return "실행 기록 없음 (삭제 없음)"
        val slug = scalar("SELECT slug FROM enrollment.tenants WHERE id=$tenant")
        check(slug == null || slug == "pulsemetry-seed-${scenario.lowercase()}") { "시드 조직 slug가 변경되어 중단합니다." }
        execute("UPDATE dev_seed.dashboard_datasets SET state='resetting' WHERE tenant_id=$tenant")
        for (row in chRows("SELECT database,table FROM system.columns WHERE name='tenant_id' AND database IN ('default','dashboard_cache')")) {
            val database = identifier(row.path("database").asString())
            val table = identifier(row.path("table").asString())
            clickHouse("ALTER TABLE $database.$table DELETE WHERE tenant_id=$tenant SETTINGS mutations_sync=2")
        }
        val present = query("SELECT tablename FROM pg_tables WHERE schemaname='enrollment'").map { it[0]!! }.toSet()
        val scoped = query("SELECT table_schema,table_name FROM information_schema.columns WHERE column_name='tenant_id' AND table_schema IN ('telemetry_ops','dashboard_cache') ORDER BY table_name")
        transaction {
            for ((schema, table) in scoped) execute("DELETE FROM ${identifier(schema!!)}.${identifier(table!!)} WHERE tenant_id=$tenant")
            resetStatements(scenario, present).forEach(::execute)
            execute("DELETE FROM dev_seed.dashboard_datasets WHERE tenant_id=$tenant")
        }
        return "해당 시드 조직 초기화 완료"
    }

    override fun close() { pg.close(); http.close() }
}

private fun identifier(value: String): String { require(Regex("[a-z_][a-z0-9_]*").matches(value)); return value }

internal fun resetStatements(scenario: String, present: Set<String>): List<String> {
    require(scenario in listOf("A", "B", "C"))
    val tenant = sql(id(scenario))
    val members = "SELECT id FROM enrollment.members WHERE tenant_id=$tenant"
    val installations = "SELECT id FROM enrollment.installations WHERE tenant_id=$tenant"
    val contracts = "SELECT id FROM enrollment.contracts WHERE tenant_id=$tenant"
    val clauses = listOf("organization_onboarding" to "tenant_id=$tenant", "management_commands" to "tenant_id=$tenant", "vendor_contract_versions" to "tenant_id=$tenant", "managed_vendors" to "tenant_id=$tenant") + listOf(
        "user_refresh_tokens" to "session_id IN (SELECT id FROM enrollment.user_sessions WHERE member_id IN ($members))",
        "user_sessions" to "member_id IN ($members)", "user_authorization_codes" to "member_id IN ($members)",
    ) + listOf("telemetry_tokens", "installation_credentials", "installation_manifest_assignments").map { it to "installation_id IN ($installations)" } +
        listOf("contract_memberships", "contract_token_discounts", "contract_term_commitments").map { it to "contract_id IN ($contracts)" } +
        listOf("contracts", "installations", "invitations", "manifests").map { it to "tenant_id=$tenant" } +
        listOf("team_memberships" to "member_id IN ($members)") + listOf("teams", "members").map { it to "tenant_id=$tenant" } + listOf("tenants" to "id=$tenant")
    return clauses.filter { it.first in present }.map { (table, where) -> "DELETE FROM enrollment.$table WHERE $where" }
}
