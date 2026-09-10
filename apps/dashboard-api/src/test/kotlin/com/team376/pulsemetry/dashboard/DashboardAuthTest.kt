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
