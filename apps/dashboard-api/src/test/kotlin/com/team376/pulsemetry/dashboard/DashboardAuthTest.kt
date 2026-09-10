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
    @Autowired lateinit var clock: java.time.Clock
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
        val expired = clock.instant().minusSeconds(1).atOffset(java.time.ZoneOffset.UTC)
        jdbc.sql("UPDATE enrollment.user_sessions SET created_at=:created, expires_at=:expired")
            .param("created",expired.minusHours(8)).param("expired",expired).update()
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
    private fun installations(people: Int, team: UUID? = null): List<UUID> = (1..people).map { n ->
        val person = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email) VALUES (:id,:tenant,:email)")
            .param("id",person).param("tenant",tenant).param("email","person-$person@example.com").update()
        if (team != null) jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)")
            .param("team",team).param("member",person).update()
        val invitation = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at)
            VALUES (:id,:tenant,:member,:creator,:hash,now()+interval '1 day')""")
            .param("id",invitation).param("tenant",tenant).param("member",person).param("creator",member)
            .param("hash",UUID.randomUUID().toString()).update()
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform) VALUES (:id,:tenant,:member,:invite,'linux')")
            .param("id",id).param("tenant",tenant).param("member",person).param("invite",invitation).update()
        id
    }
    private fun point(installation: UUID, value: Double, at: String = "2026-09-01T12:00:00Z", cumulative: Boolean = false,
        team: UUID? = null, tenantId: UUID = tenant): String = mapper.writeValueAsString(mapOf(
        "event_id" to UUID.randomUUID().toString(),"ts" to java.time.Instant.parse(at).epochSecond,
        "tenant_id" to tenantId.toString(),"installation_id" to installation.toString(),"signal" to "metric", "product" to "claude_code",
        "team_ids_as_of" to listOfNotNull(team?.toString()),"enrichment_json" to "{}",
        "raw_json" to mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.session.count", "value" to value,
            "aggregation_temporality" to if (cumulative) 2 else 1,"attrs" to mapOf("start_type" to "fresh"))))))
    private fun seedPoints(rows: List<String>) {
        val writer = com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient("http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}")
        com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator(writer).apply()
        writer.execute("TRUNCATE TABLE enriched_events")
        if (rows.isNotEmpty()) writer.execute("INSERT INTO enriched_events FORMAT JSONEachRow",rows.joinToString("\n").toByteArray())
    }
    private fun queryBody(query: Map<String, Any> = emptyMap(), extra: Map<String, Any> = emptyMap()) = mapper.writeValueAsString(
        mapOf("from" to "2026-09-01T00:00:00Z", "to" to "2026-09-03T00:00:00Z", "tz" to "UTC",
            "queries" to listOf(mapOf("ref_id" to "A", "metric_id" to "sessions", "interval" to "1d")+query))+extra)
    private fun queryResult(body: String, token: String = token(), accept: String = "application/json") =
        mvc.perform(post("/v1/query").header("Authorization", "Bearer $token").header("Accept",accept)
            .contentType("application/json").content(body))

    @Test fun `집계는 FINAL과 delta만 사용하고 비교의 빈 버킷을 보존한다`() {
        val ids = installations(5)
        val current = ids.map { point(it,2.0) }
        seedPoints(current+current+ids.map { point(it,100.0,cumulative=true) }+
            ids.map { point(it,4.0,"2026-08-31T12:00:00Z") }+ids.map { point(it,999.0,tenantId=UUID.randomUUID()) })
        val response = queryResult(queryBody(extra=mapOf("compare" to "previous_period"))).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val body = mapper.readTree(response)
        assertThat(body["results"]["A"]["status"].asInt()).isEqualTo(200)
        val frame = body["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"][1][0].asDouble()).isEqualTo(10.0)
        assertThat(frame["data"]["values"][1][1].isNull).isTrue()
        assertThat(frame["data"]["values"][2][0].isNull).isTrue()
        assertThat(frame["data"]["values"][2][1].asDouble()).isEqualTo(20.0)
        assertThat(frame["schema"]["meta"]["data_quality"].toString()).contains("5개")
        assertThat(frame["schema"]["meta"]["executed_sql"].asString()).contains("FINAL", "{tenant:String}")
        assertThat(body["coverage"]["active_installations"].asInt()).isEqualTo(5)
        val after = queryBody(extra=mapOf("from" to "2026-09-01T12:00:00.001Z"))
        val fractional = mapper.readTree(queryResult(after).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(fractional["results"]["A"]["frames"].size()).isZero()
    }
    @Test fun `한 사람의 여러 설치는 마스킹 인원을 늘리지 않고 CSV와 커버리지도 억제한다`() {
        val ids = installations(4)
        val duplicate = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform)
            SELECT :copy,tenant_id,member_id,invitation_id,platform FROM enrollment.installations WHERE id=:id""")
            .param("copy",duplicate).param("id",ids[0]).update()
        val previousIds = ids + installations(1)
        seedPoints((ids+duplicate).map { point(it,98765.0) } + previousIds.map { point(it,87654.0,"2026-08-31T12:00:00Z") })
        val input = queryBody(mapOf("frame_type" to "scalar"),mapOf("compare" to "previous_period"))
        val result = queryResult(input).andExpect(status().isOk).andReturn().response.contentAsString
        val body = mapper.readTree(result)
        val frame = body["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"][0][0].isNull).isTrue()
        assertThat(frame["data"]["values"][1][0].isNull).isTrue()
        assertThat(frame["schema"]["fields"][0]["config"]["suppressed"].asBoolean()).isTrue()
        assertThat(frame["schema"]["fields"][0]["config"]["group_size"].isNull).isTrue()
        assertThat(body["coverage"]["active_installations"].isNull).isTrue()
        assertThat(body["coverage"]["ratio"].isNull).isTrue()
        val csv = queryResult(input,accept="text/csv").andExpect(status().isOk).andReturn().response
        assertThat(csv.contentType).startsWith("text/csv")
        assertThat(csv.contentAsString).doesNotContain("98765", "493825", "438270")
    }
    @Test fun `쿼리 필터는 전역 팀을 유지하고 admin의 범위 밖과 개인 조회를 거부한다`() {
        val mine = UUID.randomUUID(); val other = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:a,:tenant,'내 팀'),(:b,:tenant,'다른 팀')")
            .param("a",mine).param("b",other).param("tenant",tenant).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)")
            .param("team",mine).param("member",member).update()
        val ids = installations(5,mine)
        seedPoints(ids.map { point(it,2.0,team=mine) } + ids.map { point(it,999.0,team=other) })
        val request = queryBody(mapOf("frame_type" to "scalar", "group_by" to listOf("team"), "filters" to mapOf("products" to listOf("claude_code"))),
            mapOf("filters" to mapOf("team_ids" to listOf(mine))))
        val owner = mapper.readTree(queryResult(request).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(owner["results"]["A"]["frames"].size()).isEqualTo(1)
        assertThat(owner["results"]["A"]["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(10.0)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        val admin = token()
        queryResult(queryBody(extra=mapOf("filters" to mapOf("team_ids" to listOf(other)))),admin).andExpect(status().isForbidden)
        queryResult(queryBody(extra=mapOf("filters" to mapOf("member_ids" to listOf(member)))),admin).andExpect(status().isForbidden)
        queryResult(request,admin).andExpect(status().isOk)
        val adoption = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "adoption_rate",
            "group_by" to listOf("team"))),admin).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(adoption["results"]["A"]["frames"].size()).isEqualTo(1)
        assertThat(adoption["results"]["A"]["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(5.0/6.0)
    }
    @Test fun `활성 시간 원천이 있으면 이벤트만 보낸 구성원으로 보호 인원을 늘리지 않는다`() {
        val ids = installations(5)
        val activity = ids.mapIndexed { index, id ->
            val row = mapper.readTree(point(id,0.0)) as tools.jackson.databind.node.ObjectNode
            row.put("raw_json", mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.active_time.total",
                "value" to if (index==0) 10 else 0,"aggregation_temporality" to 1,"attrs" to mapOf("type" to "user")))))
            mapper.writeValueAsString(row)
        }
        seedPoints(ids.map { point(it,2.0) } + activity)
        val result = mapper.readTree(queryResult(queryBody(mapOf("frame_type" to "scalar")))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val frame = result["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"][0][0].isNull).isTrue()
        assertThat(frame["schema"]["meta"]["active_user_definition"].asString()).isEqualTo("active_time_user")
        assertThat(result["coverage"]["ratio"].isNull).isTrue()
    }
    @Test fun `개인 집계는 감사 저장 이후 조회하고 값은 계속 마스킹한다`() {
        val ids = installations(1)
        val person = jdbc.sql("SELECT member_id FROM enrollment.installations WHERE id=:id").param("id",ids[0])
            .query(UUID::class.java).single()
        seedPoints(ids.map { point(it,10.0) })
        val input = queryBody(mapOf("frame_type" to "scalar"),mapOf("filters" to mapOf("member_ids" to listOf(person))))
        queryResult(input).andExpect(status().isForbidden)
        val result = mvc.perform(post("/v1/query").header("Authorization", "Bearer ${token()}")
            .header("X-Audit-Reason", "개인 설치 이슈를 확인하기 위한 감사 조회")
            .contentType("application/json").content(input)).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(mapper.readTree(result)["results"]["A"]["frames"][0]["data"]["values"][0][0].isNull).isTrue()
        assertThat(jdbc.sql("SELECT target FROM dashboard.audit_log WHERE action='query'").query(String::class.java).single())
            .isEqualTo(person.toString())
    }
    @Test fun `잘못된 요청은 오류로 거부하고 미구현 계산은 성공이나 원천 불가로 표시하지 않는다`() {
        seedPoints(emptyList())
        for (input in listOf(queryBody(mapOf("ref_id" to "AA")), queryBody(mapOf("metric_id" to "unknown")),
            queryBody(extra=mapOf("from" to "not-time")), queryBody(mapOf("metric_id" to "refusals","group_by" to listOf("team")))))
            queryResult(input).andExpect(status().isBadRequest)
        queryResult(queryBody(extra=mapOf("from" to "2020-01-01T00:00:00Z"))).andExpect(status().`is`(422))
        queryResult(queryBody(mapOf("frame_type" to "scalar"),mapOf("max_data_points" to 1,
            "from" to "2026-01-01T00:00:00Z"))).andExpect(status().isOk)
        val absent = mapper.readTree(queryResult(queryBody()).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(absent["results"]["A"]["frames"].size()).isZero()
        val unfinished = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "cost")))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(unfinished["results"]["A"]["status"].asInt()).isEqualTo(501)
    }
    @Test fun `도입 지표는 사람 중복을 제거하고 비율 분모와 기본 scalar를 보존한다`() {
        val ids = installations(5)
        val duplicate = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform)
            SELECT :copy,tenant_id,member_id,invitation_id,platform FROM enrollment.installations WHERE id=:id""")
            .param("copy",duplicate).param("id",ids[0]).update()
        seedPoints((ids+duplicate).map { point(it,2.0) })
        fun frame(metric: String) = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric)))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        val active = frame("active_users")
        assertThat(active["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        assertThat(active["data"]["values"][1][1].isNull).isTrue()
        val adoption = frame("adoption_rate")
        assertThat(adoption["schema"]["frame_type"].asString()).isEqualTo("scalar")
        assertThat(adoption["data"]["values"][0][0].asDouble()).isEqualTo(5.0/6.0)
        assertThat(adoption["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        assertThat(adoption["data"]["values"][2][0].asDouble()).isEqualTo(6.0)
        val coverage = frame("telemetry_coverage")
        assertThat(coverage["data"]["values"][0][0].asDouble()).isEqualTo(1.0)
        assertThat(coverage["data"]["values"][1][0].asDouble()).isEqualTo(6.0)
        jdbc.sql("UPDATE enrollment.installations SET status='revoked' WHERE tenant_id=:tenant")
            .param("tenant",tenant).update()
        val zero = frame("telemetry_coverage")
        assertThat(zero["data"]["values"][0][0].isNull).isTrue()
        assertThat(zero["data"]["values"][2][0].asDouble()).isEqualTo(0.0)
    }
    @Test fun `활성 시간은 개인 합계로 판정하며 소규모 비율의 분모도 숨긴다`() {
        val ids = installations(5)
        fun activity(id: UUID, value: Double): String {
            val row = mapper.readTree(point(id,value)) as tools.jackson.databind.node.ObjectNode
            row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.active_time.total",
                "value" to value,"aggregation_temporality" to 1,"attrs" to mapOf("type" to "user")))))
            return mapper.writeValueAsString(row)
        }
        seedPoints(ids.map { activity(it,10.0) } + activity(ids[0],-10.0))
        for (metric in listOf("active_users","adoption_rate","telemetry_coverage")) {
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar")))
                .andExpect(status().isOk).andReturn().response.contentAsString)
            assertThat(result["coverage"]["ratio"].isNull).isTrue()
            val frame = result["results"]["A"]["frames"][0]
            assertThat(frame["schema"]["meta"]["active_user_definition"].asString()).isEqualTo("active_time_user")
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        }
    }
    @Test fun `비율은 합계의 비로 계산하고 누적 포인트와 알 수 없는 세션을 제외한다`() {
        val ids = installations(5)
        fun metric(id: UUID, name: String, value: Double, type: String = "user", cumulative: Boolean = false): String {
            val row = mapper.readTree(point(id,value)) as tools.jackson.databind.node.ObjectNode
            row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.$name",
                "value" to value,"aggregation_temporality" to if (cumulative) 2 else 1,
                "attrs" to mapOf("type" to type,"start_type" to "fresh")))))
            return mapper.writeValueAsString(row)
        }
        fun prompt(id: UUID, command: String?, session: String): String {
            val row = mapper.readTree(point(id,0.0)) as tools.jackson.databind.node.ObjectNode
            row.put("signal","log")
            row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "user_prompt",
                "envelope" to mapOf("session_id" to session),"payload" to mapOf("command_name" to command))))
            return mapper.writeValueAsString(row)
        }
        val points = ids.flatMap { id -> listOf(metric(id,"active_time.total",10.0),
            metric(id,"active_time.total",30.0,"cli"),metric(id,"active_time.total",900.0,"cli",true),
            metric(id,"session.count",2.0),metric(id,"commit.count",1.0),metric(id,"pull_request.count",2.0),
            prompt(id,"/review",id.toString()),prompt(id,null,id.toString()),prompt(id,"/review","(unknown)")) }
        seedPoints(points + points.first())
        for ((metric, expected) in mapOf("automation_ratio" to listOf(0.75,150.0,200.0),
            "integration_depth" to listOf(1.5,15.0,10.0),"command_prompt_ratio" to listOf(0.5,5.0,10.0))) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar")))
                .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).isEqualTo(expected)
        }
        // 분모가 없는 관측은 0 대신 null이며, 비교 구간의 작은 집단은 양쪽의 모든 숫자를 숨긴다.
        seedPoints(ids.map { metric(it,"commit.count",1.0) })
        val zero = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "integration_depth","frame_type" to "scalar")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(zero[0][0].isNull).isTrue()
        assertThat(zero[1][0].asDouble()).isEqualTo(5.0)
        assertThat(zero[2][0].isNumber).isTrue()
        assertThat(zero[2][0].asDouble()).isEqualTo(0.0)
        seedPoints(ids.map { metric(it,"active_time.total",10.0) })
        val userOnly = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "automation_ratio","frame_type" to "scalar")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(userOnly[0][0].isNumber).isTrue()
        assertThat(userOnly[0][0].asDouble()).isEqualTo(0.0)
        assertThat(userOnly[1][0].isNumber).isTrue()
        assertThat(userOnly[1][0].asDouble()).isEqualTo(0.0)
        assertThat(userOnly[2][0].asDouble()).isEqualTo(50.0)
        val prior = points.filter { mapper.readTree(it)["installation_id"].asString()!=ids.last().toString() }.map {
            val row = mapper.readTree(it) as tools.jackson.databind.node.ObjectNode
            row.put("ts",java.time.Instant.parse("2026-08-31T12:00:00Z").epochSecond)
            row.put("event_id",UUID.randomUUID().toString())
            mapper.writeValueAsString(row)
        }
        seedPoints(points + prior)
        for (metric in listOf("automation_ratio","integration_depth","command_prompt_ratio")) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar"),
                mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].size()).isEqualTo(6)
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        }
    }
    private fun promptEvent(id: UUID, session: String = "shared-session", product: String = "claude_code",
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(point(id,0.0,at)) as tools.jackson.databind.node.ObjectNode
        row.put("signal","log").put("product",product)
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "user_prompt",
            "envelope" to mapOf("session_id" to session),"payload" to emptyMap<String,String>())))
        return mapper.writeValueAsString(row)
    }
    @Test fun `프롬프트 분포는 설치와 제품별 세션을 구분하고 버킷과 정확 분위수를 반환한다`() {
        val ids = installations(5)
        val rows = ids.zip(listOf(1,2,4,8,16)).flatMap { (id,count) -> (1..count).map { promptEvent(id) } }
        seedPoints(rows + rows.first() + (1..3).map { promptEvent(ids[0],product="codex") } +
            (1..20).map { promptEvent(ids[0],session="(unknown)") } + promptEvent(ids[0],session=""))
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "prompts_per_session")))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val frames = result["results"]["A"]["frames"].toList()
        assertThat(frames).hasSize(2)
        assertThat(frames.all { it["schema"]["frame_type"].asString()=="distribution" }).isTrue()
        val histogram = frames.single { it["schema"]["fields"][0]["name"].asString()=="bucket" }
        assertThat(histogram["data"]["values"][0].toList().map { it.asString() }).containsExactly("1","2–3","4–7","8–15","16+")
        assertThat(histogram["data"]["values"][1].toList().map { it.asInt() }).containsExactly(1,2,1,1,1)
        val summary = frames.single { it["schema"]["fields"][0]["name"].asString()=="p50" }
        assertThat(summary["data"]["values"].toList().map { it[0].asInt() }).containsExactly(4,16)
        val series = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "prompts_per_session","frame_type" to "timeseries")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(series["schema"]["fields"].toList().map { it["name"].asString() }).containsExactly("time","p50","p90")
        assertThat(series["data"]["values"][1][0].asInt()).isEqualTo(4)
        assertThat(series["data"]["values"][1][1].isNull).isTrue()
        val compared = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "prompts_per_session"),
            mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        compared.forEach { frame -> frame["schema"]["fields"].toList().forEachIndexed { index,field ->
            if (field["name"].asString().endsWith("_compare")) {
                assertThat(field["config"]["group_size"].asInt()).isEqualTo(5)
                assertThat(field["config"]["suppressed"].asBoolean()).isFalse()
                assertThat(frame["data"]["values"][index].toList().all { it.isNull }).isTrue()
            }
        } }
        val filtered = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "prompts_per_session",
            "filters" to mapOf("products" to listOf("codex")))))
            .andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        filtered.forEach { frame -> frame["schema"]["fields"].toList().forEachIndexed { index,field ->
            if (field["type"].asString()=="number") assertThat(frame["data"]["values"][index].toList().all { it.isNull }).isTrue()
        } }
    }
    @Test fun `분포의 비교 구간이 작으면 분위수와 모든 히스토그램 빈도 및 CSV를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { promptEvent(it) } + ids.take(4).map { promptEvent(it,at="2026-08-31T12:00:00Z") })
        val body = queryBody(mapOf("metric_id" to "prompts_per_session"),mapOf("compare" to "previous_period"))
        val frames = mapper.readTree(queryResult(body).andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(frames).hasSize(2)
        frames.forEach { frame -> frame["schema"]["fields"].toList().forEachIndexed { index,field ->
            if (field["type"].asString()=="number") {
                assertThat(field["config"]["suppressed"].asBoolean()).isTrue()
                assertThat(field["config"]["group_size"].isNull).isTrue()
                assertThat(frame["data"]["values"][index].toList().all { it.isNull }).isTrue()
            }
        } }
        val csv = queryResult(body,accept="text/csv").andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(csv.lines().filter { it.contains("\"count") || it.contains("\"p50") || it.contains("\"p90") }
            .all { it.endsWith(",\"\"") }).isTrue()
        seedPoints(emptyList())
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "prompts_per_session")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(empty.toList()).isEmpty()
    }
    private fun toolEvent(id: UUID, success: Boolean?, action: String = "read", at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(promptEvent(id,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "tool_call",
            "envelope" to mapOf("session_id" to "shared-session"),"payload" to mapOf("success" to success,
                "tool_name" to "Read", "tool_kind" to "function", "action" to action,"error_type" to "io_error","mcp_server" to "files"))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `도구 호출은 성공 미판정을 실패로 세지 않고 boolean 필터와 payload 차원을 적용한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(toolEvent(it,true),toolEvent(it,false),toolEvent(it,null)) }
        seedPoints(rows + rows.first())
        for ((metric,expected) in mapOf("tool_calls" to 15.0,"tool_failure_rate" to 0.5)) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar",
                "group_by" to listOf("tool_name")))).andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"][0][0].asDouble()).isEqualTo(expected)
            assertThat(frame["schema"]["fields"][0]["labels"]["tool_name"].asString()).isEqualTo("Read")
            if (metric=="tool_failure_rate") {
                assertThat(frame["data"]["values"][1][0].asInt()).isEqualTo(5)
                assertThat(frame["data"]["values"][2][0].asInt()).isEqualTo(10)
            }
        }
        for (success in listOf(true,false)) {
            for (dimension in listOf("tool_kind","action","error_type","mcp_server")) {
                val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tool_calls","frame_type" to "scalar",
                    "group_by" to listOf(dimension),"params" to mapOf("success" to success))))
                    .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
                assertThat(frame["data"]["values"][0][0].asInt()).isEqualTo(5)
            }
        }
        queryResult(queryBody(mapOf("metric_id" to "tool_calls","params" to mapOf("success" to "false")))).andExpect(status().isBadRequest)
        queryResult(queryBody(mapOf("metric_id" to "tool_calls","params" to mapOf("unknown" to true)))).andExpect(status().isBadRequest)
        seedPoints(ids.map { toolEvent(it,null) })
        val unjudged = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tool_failure_rate")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(unjudged[0][0].isNull).isTrue()
        assertThat(unjudged[2][0].isNumber).isTrue()
        assertThat(unjudged[2][0].asInt()).isZero()
        val noFailure = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tool_calls","frame_type" to "scalar",
            "params" to mapOf("success" to false)))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"][0][0]
        assertThat(noFailure.isNumber).isTrue()
        assertThat(noFailure.asInt()).isZero()
    }
    @Test fun `읽기 밀도는 읽기 호출이 없는 도구 세션을 포함하고 비교 집단이 작으면 숨긴다`() {
        val ids = installations(5)
        val rows = ids.zip(listOf(0,1,2,4,8)).flatMap { (id,count) ->
            listOf(toolEvent(id,true,"write")) + (1..count).map { toolEvent(id,true,listOf("read","search","fetch")[it%3]) }
        }
        seedPoints(rows)
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "read_tool_density")))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["schema"]["frame_type"].asString()).isEqualTo("distribution")
        assertThat(frame["schema"]["fields"].toList().map { it["name"].asString() }).containsExactly("p50","p90")
        assertThat(frame["data"]["values"].toList().map { it[0].asInt() }).containsExactly(2,8)
        seedPoints(rows + ids.take(4).map { toolEvent(it,false,at="2026-08-31T12:00:00Z") })
        for (metric in listOf("tool_calls","tool_failure_rate","read_tool_density")) {
            val hidden = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar"),
                mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(hidden["data"]["values"].toList().all { it[0].isNull }).isTrue()
        }
    }
    private fun llmEvent(id: UUID, attempt: Int?, status: Int?, model: String = "claude-test",
        product: String = "claude_code", type: String = "llm_call", at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(promptEvent(id,product=product,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to type,
            "envelope" to mapOf("session_id" to "session"),"payload" to mapOf("attempt" to attempt,"status_code" to status,"model" to model))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `재시도는 호출 비율과 누락 품질을 제공하고 모델 및 현지 시간 차원을 적용한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(llmEvent(it,1,200),llmEvent(it,2,429),llmEvent(it,null,null),
            llmEvent(it,9,429,type="llm_request"),llmEvent(it,3,429,"codex-test","codex")) }
        seedPoints(rows + rows.first())
        val frames = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "api_retry_attempts","group_by" to listOf("model"))))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(frames).hasSize(2)
        val claude = frames.single { it["schema"]["fields"][0]["labels"]["model"].asString()=="claude-test" }
        assertThat(claude["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(1.0/3,5.0,15.0)
        assertThat(claude["schema"]["meta"]["data_quality"][0].asString()).contains("5개", "미판정")
        val codex = frames.single { it["schema"]["fields"][0]["labels"]["model"].asString()=="codex-test" }
        assertThat(codex["data"]["values"][0][0].asDouble()).isEqualTo(1.0)
        val limits = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "rate_limit_events","group_by" to listOf("hour")),
            mapOf("tz" to "Asia/Seoul","filters" to mapOf("products" to listOf("claude_code")))))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(limits["schema"]["fields"][1]["labels"]["hour"].asString()).isEqualTo("21")
        assertThat(limits["data"]["values"][1][0].asInt()).isEqualTo(5)
        assertThat(limits["data"]["values"][1][1].isNull).isTrue()
        seedPoints(ids.map { llmEvent(it,null,null) })
        for (metric in listOf("api_retry_attempts","rate_limit_events")) {
            val value = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar")))
                .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"][0][0]
            assertThat(value.isNumber).isTrue()
            assertThat(value.asInt()).isZero()
        }
    }
    @Test fun `재시도와 요청 제한은 작은 비교 집단의 값과 품질 인원 수를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { llmEvent(it,2,429) } + ids.take(4).map { llmEvent(it,null,429,at="2026-08-31T12:00:00Z") })
        for (metric in listOf("api_retry_attempts","rate_limit_events")) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar"),
                mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
            if (metric=="api_retry_attempts") {
                val quality = frame["schema"]["meta"]["data_quality"][0].asString()
                assertThat(quality).contains("미판정").doesNotContain("4개")
            }
        }
    }
    private fun decisionEvent(id: UUID, source: String?, decision: String?, at: String = "2026-09-01T12:00:00Z",
        product: String = "claude_code", signal: String = "log"): String {
        val row = mapper.readTree(promptEvent(id,product=product,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("signal",signal)
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "tool_decision",
            "envelope" to mapOf("session_id" to "session"),"payload" to mapOf("decided_by" to source,
                "decision" to decision,"tool_name" to "Bash"))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `자동 결정 비율과 거절 수는 취소 및 누락을 구분하고 주체별 집계와 비교를 제공한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(decisionEvent(it,"config","reject"),
            decisionEvent(it,"hook","accept",product="codex",signal="span"),decisionEvent(it,"user","abort"),
            decisionEvent(it,null,null),toolEvent(it,false)) }
        seedPoints(rows + rows.first() + ids.map { decisionEvent(it,"user","reject",at="2026-08-31T12:00:00Z") })
        val ratio = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "auto_approval_ratio"),
            mapOf("compare" to "previous_period"))).andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(ratio["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.5,0.0,10.0,0.0,20.0,5.0)
        val rejected = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tool_rejections","frame_type" to "scalar")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"][0][0]
        assertThat(rejected.asInt()).isEqualTo(5)
        val groups = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "auto_approval_ratio",
            "group_by" to listOf("decided_by","tool_name")))).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(groups).hasSize(4)
        val config = groups.single { it["schema"]["fields"][0]["labels"]["decided_by"].asString()=="config" }
        assertThat(config["data"]["values"][0][0].asDouble()).isEqualTo(1.0)
        assertThat(config["schema"]["fields"][0]["labels"]["tool_name"].asString()).isEqualTo("Bash")
        val filtered = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "auto_approval_ratio",
            "filters" to mapOf("products" to listOf("codex"))))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(filtered["data"]["values"][0][0].asDouble()).isEqualTo(1.0)
        seedPoints(ids.map { decisionEvent(it,"user","abort") })
        for (metric in listOf("auto_approval_ratio","tool_rejections")) {
            val value = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar")))
                .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"][0][0]
            assertThat(value.isNumber).isTrue()
            assertThat(value.asInt()).isZero()
        }
    }
    @Test fun `결정 지표는 작은 비교 집단의 비율과 분모 및 거절 수를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { decisionEvent(it,"config","accept") } +
            ids.take(4).map { decisionEvent(it,"hook","reject",at="2026-08-31T12:00:00Z") })
        for (metric in listOf("auto_approval_ratio","tool_rejections")) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar"),
                mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        }
        seedPoints(emptyList())
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "auto_approval_ratio")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(empty.toList()).isEmpty()
    }
    @Test fun `API 오류율은 error_type으로 판정하고 상태 코드 누락과 비교 집단을 구분한다`() {
        val ids = installations(5)
        fun event(id: UUID, error: String?, status: Int?, at: String = "2026-09-01T12:00:00Z"): String {
            val row = mapper.readTree(llmEvent(id,1,status,at=at)) as tools.jackson.databind.node.ObjectNode
            row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "llm_call",
                "payload" to mapOf("error_type" to error,"status_code" to status,"model" to "claude-test"))))
            return mapper.writeValueAsString(row)
        }
        val rows = ids.flatMap { listOf(event(it,"timeout",null),event(it,null,500),event(it,"",200)) }
        seedPoints(rows+rows.first())
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "api_error_rate")))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(1.0/3,5.0,15.0)
        assertThat(frame["schema"]["meta"]["data_quality"][0].asString()).contains("시도 단위")
        val groups = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "api_error_rate",
            "group_by" to listOf("status_code")))).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(groups).hasSize(3)
        val missing = groups.single { it["schema"]["fields"][0]["labels"]["status_code"].asString()=="0" }
        assertThat(missing["data"]["values"][0][0].asDouble()).isEqualTo(1.0)
        val serverError = groups.single { it["schema"]["fields"][0]["labels"]["status_code"].asString()=="500" }
        assertThat(serverError["data"]["values"][0][0].asDouble()).isZero()
        seedPoints(rows+ids.take(4).map { event(it,"timeout",null,"2026-08-31T12:00:00Z") })
        val hidden = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "api_error_rate"),
            mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(hidden["data"]["values"].toList().all { it[0].isNull }).isTrue()
    }
    @Test fun `히트맵은 현지 요일과 시간의 전체 168칸을 지원하고 제한과 셀 마스킹을 적용한다`() {
        val ids = installations(5)
        val start = java.time.Instant.parse("2026-08-31T00:00:00Z")
        val rows = (0..167).flatMap { hour -> ids.map { promptEvent(it,at=start.plusSeconds(hour*3600L).toString()) } }
        seedPoints(rows+rows.first())
        val range = mapOf("from" to start.toString(),"to" to start.plusSeconds(168*3600L).toString(),"tz" to "Asia/Seoul")
        val query = mapOf("metric_id" to "usage_heatmap","group_by" to listOf("weekday","hour"))
        val frames = mapper.readTree(queryResult(queryBody(query,range)).andExpect(status().isOk)
            .andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(frames).hasSize(168)
        assertThat(frames.all { it["data"]["values"][0][0].asInt()==5 }).isTrue()
        val labels = frames.map { it["schema"]["fields"][0]["labels"] }
        assertThat(labels.map { it["weekday"].asString() }.toSet()).hasSize(7)
        assertThat(labels.map { it["hour"].asString() }.toSet()).hasSize(24)
        val limited = mapper.readTree(queryResult(queryBody(query+mapOf("limit" to 100),range))
            .andReturn().response.contentAsString)["results"]["A"]["status"]
        assertThat(limited.asInt()).isEqualTo(422)
        queryResult(queryBody(query+mapOf("limit" to 169),range)).andExpect(status().isBadRequest)
        seedPoints(rows.drop(1))
        val masked = mapper.readTree(queryResult(queryBody(query,range)).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        val hidden = masked.single { it["data"]["values"][0][0].isNull }
        assertThat(hidden["schema"]["fields"][0]["labels"]["weekday"].asString()).isEqualTo("1")
        assertThat(hidden["schema"]["fields"][0]["labels"]["hour"].asString()).isEqualTo("9")
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
