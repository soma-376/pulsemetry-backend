package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID

@org.testcontainers.junit.jupiter.Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class)
class DashboardAuthTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mapper: ObjectMapper
    private lateinit var member: UUID
    @BeforeEach fun setup() {
        jdbc.sql("TRUNCATE enrollment.tenants CASCADE").update()
        jdbc.sql("TRUNCATE enrollment.auth_attempts").update()
        jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'테스트 조직')").param("id", tenant).update()
        member = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.members(id,tenant_id,email,role,password_hash)
            VALUES (:id,:tenant,'owner@example.com','owner',:hash)""")
            .param("id", member).param("tenant", tenant).param("hash", BCryptPasswordEncoder(4).encode(password)).update()
    }
    private fun login(password: String = Companion.password) = mvc.perform(post("/v1/auth/login")
        .contentType("application/json").content(mapper.writeValueAsString(mapOf("email" to "owner@example.com", "password" to password))))
    private fun token(): String {
        val result = login().andExpect(status().isOk).andReturn()
        val body = mapper.readTree(result.response.contentAsString)
        assertThat(body["expires_in"].asInt()).isEqualTo(28800)
        assertThat(body.has("refresh_token")).isFalse()
        return body["access_token"].asString()
    }
    @Test fun `지표 카탈로그는 인증을 요구하고 OpenAPI의 모든 지표와 차원을 제공한다`() {
        mvc.perform(get("/v1/meta/metrics")).andExpect(status().isUnauthorized)
        val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("docs/reference/pulsemetry_api_spec.yaml")) }
        val spec = Files.readString(root.resolve("docs/reference/pulsemetry_api_spec.yaml"))
        fun enumValues(name: String): Set<String> = Regex("(?m)^        - ([a-z_]+)")
            .findAll(spec.substringAfter("    $name:").substringBefore("\n    QueryResponse:")
                .let { if (name == "MetricId") it.substringBefore("\n    Dimension:") else it })
            .map { it.groupValues[1] }.toSet()
        val expected = enumValues("MetricId")
        val dimensions = enumValues("Dimension")
        val rdbColumns = jdbc.sql("""SELECT table_schema || '.' || table_name || '.' || column_name
            FROM information_schema.columns WHERE table_schema='enrollment'""").query(String::class.java).list()
        assertThat(expected).hasSize(53)
        for (role in listOf("owner", "admin")) {
            jdbc.sql("UPDATE enrollment.members SET role=CAST(:role AS enrollment.member_role)")
                .param("role", role).update()
            val response = mvc.perform(get("/v1/meta/metrics").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk).andReturn().response.contentAsString
            val definitions = mapper.readTree(response)["items"].toList()
            assertThat(definitions.map { it["metric_id"].asString() }).containsExactlyInAnyOrderElementsOf(expected)
            for (definition in definitions) {
                assertThat(definition.has("metricId")).isFalse()
                assertThat(definition["definition"].asString()).isNotBlank()
                assertThat(definition["title"].asString()).isNotBlank()
                val columns = definition["source_columns"].toList().map { it.asString() }
                assertThat(columns).isNotEmpty()
                assertThat(rdbColumns).containsAll(columns.filter { it.startsWith("enrollment.") })
                assertThat(definition["min_group_size"].asInt()).isEqualTo(5)
                val allowed = definition["allowed_group_by"].toList().map { it.asString() }
                val forbidden = definition["forbidden_group_by"].toList().map { it.asString() }
                assertThat(dimensions).containsAll(allowed + forbidden)
                assertThat(allowed.intersect(forbidden.toSet())).isEmpty()
            }
            val refusal = definitions.single { it["metric_id"].asString() == "refusals" }
            assertThat(refusal["forbidden_group_by"].toList().map { it.asString() }).containsExactly("team")
            assertThat(refusal["availability"].asString()).isEqualTo("partial")
            assertThat(refusal["caveat"].asString()).contains("server_fallback_hop")
            val cost = definitions.single { it["metric_id"].asString() == "cost" }
            assertThat(cost["availability"].asString()).isEqualTo("available")
            assertThat(cost["caveat"].asString()).contains("청구액이 아닙니다", "token_type=all")
        }
    }
    @Test fun `manifest 없는 owner 로그인 후 사용자 정보 조회하고 폐기하면 즉시 거부한다`() {
        val token = token()
        mvc.perform(get("/v1/not-implemented").header("Authorization", "Bearer $token")).andExpect(status().isNotFound)
        mvc.perform(get("/v1/me").header("Authorization", "Bearer $token")).andExpect(status().isOk)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.user_refresh_tokens").query(Long::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT session_kind FROM enrollment.user_sessions").query(String::class.java).single()).isEqualTo("web")
        jdbc.sql("UPDATE enrollment.user_sessions SET revoked_at=now()").update()
        mvc.perform(get("/v1/me").header("Authorization", "Bearer $token")).andExpect(status().isUnauthorized)
    }
    @Test fun `member 로그인과 권한 변경 뒤 기존 토큰은 거부한다`() {
        val token = token()
        jdbc.sql("UPDATE enrollment.members SET role='member'").update()
        mvc.perform(get("/v1/me").header("Authorization", "Bearer $token")).andExpect(status().isUnauthorized)
        login().andExpect(status().isForbidden)
    }
    @Test fun `실패 다섯 번은 계정을 잠그고 인증 없는 조회는 거부한다`() {
        repeat(4) { login("wrong").andExpect(status().isUnauthorized) }
        login("wrong").andExpect(status().isTooManyRequests)
        login().andExpect(status().isTooManyRequests)
        mvc.perform(get("/v1/me")).andExpect(status().isUnauthorized)
    }
    @Test fun `세션 만료와 tenant 상태 변경은 즉시 적용된다`() {
        val token = token()
        jdbc.sql("UPDATE enrollment.user_sessions SET created_at=now()-interval '9 hours', expires_at=now()-interval '1 second'").update()
        mvc.perform(get("/v1/me").header("Authorization", "Bearer $token")).andExpect(status().isUnauthorized)
        val fresh = token()
        jdbc.sql("UPDATE enrollment.tenants SET status='suspended'").update()
        mvc.perform(get("/v1/me").header("Authorization", "Bearer $fresh")).andExpect(status().isUnauthorized)
    }
    @Test fun `메타 조회는 현재 소속 팀만 허용하고 다른 팀 필터를 거부한다`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:mine,:tenant,'내 팀'),(:other,:tenant,'다른 팀')")
            .param("mine", mine).param("other", other).param("tenant", tenant).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)")
            .param("team", mine).param("member", member).update()
        jdbc.sql("UPDATE enrollment.members SET role='admin'").update()
        val token = token()
        val response = mvc.perform(get("/v1/meta/teams").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(mapper.readTree(response)["items"].size()).isEqualTo(1)
        for (path in listOf("contracts", "manifests")) {
            mvc.perform(get("/v1/meta/$path").header("Authorization", "Bearer $token")).andExpect(status().isOk)
        }
        mvc.perform(get("/v1/meta/members").param("team_id", other.toString()).header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden)
        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=now()").update()
        val empty = mvc.perform(get("/v1/meta/teams").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(mapper.readTree(empty)["items"].size()).isZero()
    }
    @Test fun `개인 이메일 조회는 owner 감사 사유를 한 번 해석해 저장한 뒤 허용한다`() {
        val token = token()
        mvc.perform(get("/v1/meta/members").header("Authorization", "Bearer $token")).andExpect(status().isForbidden)
        val reason = "정기 계정 점검을 위한 구성원 확인"
        mvc.perform(get("/v1/meta/members").header("Authorization", "Bearer $token")
            .header("X-Audit-Reason", java.net.URLEncoder.encode(reason, java.nio.charset.StandardCharsets.UTF_8)))
            .andExpect(status().isOk)
        assertThat(jdbc.sql("SELECT reason FROM dashboard.audit_log").query(String::class.java).single()).isEqualTo(reason)
        jdbc.sql("UPDATE enrollment.members SET role='admin'").update()
        val admin = token()
        mvc.perform(get("/v1/meta/members").header("Authorization", "Bearer $admin").header("X-Audit-Reason", reason))
            .andExpect(status().isForbidden)
    }
    @Test fun `관측 모델과 제품은 tenant 및 admin의 현재 팀 범위를 따른다`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:mine,:tenant,'내 팀'),(:other,:tenant,'다른 팀')")
            .param("mine", mine).param("other", other).param("tenant", tenant).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)")
            .param("team", mine).param("member", member).update()
        jdbc.sql("UPDATE enrollment.members SET role='admin'").update()
        val writer = com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient("http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}")
        com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator(writer).apply()
        writer.execute("TRUNCATE TABLE enriched_events")
        val rows = listOf(mine to "claude-test", other to "gpt-test").map { (team, model) ->
            mapper.writeValueAsString(mapOf("event_id" to UUID.randomUUID().toString(), "ts" to java.time.Instant.now().minusSeconds(3).epochSecond,
                "tenant_id" to tenant.toString(), "installation_id" to UUID.randomUUID().toString(), "signal" to "log", "product" to "claude_code",
                "team_ids_as_of" to listOf(team.toString()), "raw_json" to mapper.writeValueAsString(mapOf("payload" to mapOf("model" to model))), "enrichment_json" to "{}"))
        }.joinToString("\n")
        writer.execute("INSERT INTO enriched_events FORMAT JSONEachRow", rows.toByteArray())
        val token = token()
        val result = mvc.perform(get("/v1/meta/models").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(result).contains("claude-test").doesNotContain("gpt-test")
        mvc.perform(get("/v1/meta/filters").header("Authorization", "Bearer $token")).andExpect(status().isOk)
    }
    companion object {
        @org.testcontainers.junit.jupiter.Container @JvmStatic
        val clickhouse = org.testcontainers.containers.GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/ping").forPort(8123).forStatusCode(200))

        private val tenant = UUID.randomUUID()
        private const val password = "correct-password-123"
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private fun pem(label: String, bytes: ByteArray) = Files.createTempFile("dashboard-key", ".pem").also {
            Files.writeString(it, "-----BEGIN $label-----\n${Base64.getEncoder().encodeToString(bytes)}\n-----END $label-----")
            it.toFile().deleteOnExit()
        }.toString()
        private val privateFile = pem("PRIVATE KEY", pair.private.encoded)
        private val publicFile = pem("PUBLIC KEY", pair.public.encoded)
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("pulsemetry.dashboard.clickhouse-url") { "http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}" }
            r.add("pulsemetry.dashboard.tenant-id") { tenant.toString() }
            r.add("pulsemetry.dashboard.issuer") { "https://auth.test" }
            r.add("pulsemetry.dashboard.active-kid") { "test" }
            r.add("pulsemetry.dashboard.private-key-file") { privateFile }
            r.add("pulsemetry.dashboard.public-key-files.test") { publicFile }
        }
    }
}
