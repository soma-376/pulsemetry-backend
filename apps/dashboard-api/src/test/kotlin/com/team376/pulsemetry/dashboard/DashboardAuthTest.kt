package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    @Autowired lateinit var scenarioRuns: DashboardScenarioRuns
    @Autowired lateinit var runStore: com.team376.pulsemetry.persistence.dashboard.DashboardRuns
    @Autowired lateinit var scenarioInputs: DashboardScenarioInputs
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
    @Test fun `세션 상위 그룹 재집계는 누적값을 제외하고 비교 기간과 빈 버킷을 유지한다`() {
        val ids = installations(5)
        fun session(id: UUID, kind: String, value: Double, cumulative: Boolean = false, at: String = "2026-09-01T12:00:00Z"): String {
            val row = mapper.readTree(point(id,value,at,cumulative)) as tools.jackson.databind.node.ObjectNode
            val raw = mapper.readTree(row["raw_json"].asString())
            (raw["point"]["attrs"] as tools.jackson.databind.node.ObjectNode).put("start_type",kind)
            row.put("raw_json",raw.toString())
            return row.toString()
        }
        seedPoints(ids.flatMap { id -> listOf(session(id,"fresh",10.0),session(id,"resume",3.0),session(id,"fork",2.0),
            session(id,"resume",9999.0,true),session(id,"fresh",1.0,at="2026-08-30T12:00:00Z"),
            session(id,"resume",20.0,at="2026-08-30T12:00:00Z")) })
        val result = mapper.readTree(queryResult(queryBody(mapOf("group_by" to listOf("start_type"),"limit" to 1),
            mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frames = result["frames"].toList().associateBy { it["schema"]["fields"][1]["labels"]["start_type"].asString() }
        assertThat(frames.keys).containsExactlyInAnyOrder("fresh","__other__")
        val other = frames.getValue("__other__")
        assertThat(other["data"]["values"][1][0].asDouble()).isEqualTo(25.0)
        assertThat(other["data"]["values"][2][0].asDouble()).isEqualTo(100.0)
        assertThat(other["data"]["values"][1][1].isNull).isTrue()
        assertThat(other["schema"]["fields"][1]["config"]["group_size"].asLong()).isEqualTo(5)
        assertThat(frames.getValue("fresh")["data"]["values"][1][0].asDouble()).isEqualTo(50.0)
    }
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
    @Test fun `도입률 기타는 비교 기간 팀까지 현재 구성원 합집합을 분모로 사용한다`() {
        val teams = costTeams()
        val top = installations(5,teams[0]); val shared = installations(10,teams[1])
        shared.forEach { id -> jdbc.sql("""INSERT INTO enrollment.team_memberships(team_id,member_id)
            SELECT :team,member_id FROM enrollment.installations WHERE id=:id""")
            .param("team",teams[2]).param("id",id).update() }
        installations(2,teams[2]) // 관측이 없어도 현재 팀 구성원은 분모에 포함한다.
        seedPoints(top.map { point(it,1.0,team=teams[0]) }+
            shared.take(5).flatMap { id -> listOf("2026-09-01T12:00:00Z","2026-09-02T12:00:00Z").map { point(id,1.0,at=it,team=teams[1]) } }+
            shared.take(5).map { point(it,1.0,at="2026-08-30T12:00:00Z",team=teams[2]) })
        for(type in listOf("table","scalar","timeseries")) {
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "adoption_rate","group_by" to listOf("team"),
                "frame_type" to type,"limit" to 1),mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]
            assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
            val offset = if(type=="timeseries") 1 else 0
            val frames = result["frames"].toList().associateBy { it["schema"]["fields"][offset]["labels"]["team"].asString() }
            assertThat(frames.keys).containsExactlyInAnyOrder(teams[0].toString(),"__other__")
            val other = frames.getValue("__other__")
            val fields = other["schema"]["fields"].toList()
            val values = other["data"]["values"]
            val byName = fields.mapIndexed { i,f -> f["name"].asString() to values[i][0] }.toMap()
            assertThat(values[offset][0].asDouble()).isEqualTo(5.0/12.0)
            assertThat(byName["denominator"]?.asDouble()).isEqualTo(12.0)
            assertThat(byName["value_compare"]?.asDouble()).isEqualTo(5.0/12.0)
            assertThat(byName["denominator_compare"]?.asDouble()).isEqualTo(12.0)
        }
    }
    @Test fun `활성 사용자 상위는 기간 고유 인원으로 선택하고 기타 팀의 중복 인원을 제거한다`() {
        val teams = costTeams(); val top = installations(6); val shared = installations(5)
        seedPoints(top.map { point(it,1.0,team=teams[0]) }+shared.flatMap { id ->
            listOf("2026-09-01T12:00:00Z","2026-09-02T12:00:00Z").map { at ->
                val row = mapper.readTree(point(id,1.0,at=at)) as tools.jackson.databind.node.ObjectNode
                row.set("team_ids_as_of",mapper.valueToTree(teams.drop(1).map { it.toString() }))
                row.toString()
            } }+shared.map { point(it,1.0,at="2026-08-30T12:00:00Z",team=teams[1]) })
        for(type in listOf("table","scalar","timeseries")) {
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "active_users","group_by" to listOf("team"),
                "frame_type" to type,"limit" to 1),mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]
            assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
            val offset = if(type=="timeseries") 1 else 0
            val frames = result["frames"].toList().associateBy { it["schema"]["fields"][offset]["labels"]["team"].asString() }
            assertThat(frames.keys).containsExactlyInAnyOrder(teams[0].toString(),"__other__")
            assertThat(frames.getValue(teams[0].toString())["data"]["values"][offset][0].asDouble()).isEqualTo(6.0)
            assertThat(frames.getValue("__other__")["data"]["values"][offset][0].asDouble()).isEqualTo(5.0)
            assertThat(frames.getValue("__other__")["data"]["values"][offset+1][0].asDouble()).isEqualTo(5.0)
        }
    }
    @Test fun `커버리지 상위는 날짜를 가로질러 설치를 중복 제거하고 전체 설치 분모를 유지한다`() {
        val ids = installations(6)
        seedPoints(ids.map { promptEvent(it,product="claude_code") }+ids.take(5).flatMap { id ->
            listOf("2026-09-01T12:00:00Z","2026-09-02T12:00:00Z").map { promptEvent(id,product="codex",at=it) } })
        for(type in listOf("table","timeseries")) {
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "telemetry_coverage","group_by" to listOf("product"),
                "frame_type" to type,"limit" to 1))).andReturn().response.contentAsString)["results"]["A"]
            assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
            val offset = if(type=="timeseries") 1 else 0
            val frames = result["frames"].toList().associateBy { it["schema"]["fields"][offset]["labels"]["product"].asString() }
            assertThat(frames.keys).containsExactlyInAnyOrder("claude_code","__other__")
            val other = frames.getValue("__other__")
            assertThat(other["data"]["values"][offset][0].asDouble()).isEqualTo(5.0/6.0)
            assertThat(other["data"]["values"][offset+1][0].asDouble()).isEqualTo(5.0)
            assertThat(other["data"]["values"][offset+2][0].asDouble()).isEqualTo(6.0)
        }
    }
    @Test fun `활성 사용자 기타의 다중 팀 시간 중복으로 비활성자가 활성화되지 않는다`() {
        val teams = costTeams(); val top = installations(6); val hidden = installations(4)
        fun activity(id: UUID, value: Double, membership: List<UUID>): String {
            val row = mapper.readTree(point(id,value)) as tools.jackson.databind.node.ObjectNode
            row.set("team_ids_as_of",mapper.valueToTree(membership.map { it.toString() }))
            row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.active_time.total",
                "value" to value,"aggregation_temporality" to 1,"attrs" to mapOf("type" to "user")))))
            return row.toString()
        }
        seedPoints(top.map { activity(it,1.0,listOf(teams[0])) }+hidden.map { activity(it,1.0,teams.drop(1)) }+
            listOf(activity(top[0],1.0,teams.drop(1)),activity(top[0],-1.0,listOf(teams[1]))))
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "active_users","group_by" to listOf("team"),
            "frame_type" to "table","limit" to 1))).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val other = result["frames"].single { it["schema"]["fields"][0]["labels"]["team"].asString()=="__other__" }
        assertThat(other["data"]["values"][0][0].isNull).isTrue()
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
        val unfinished = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "cost","frame_type" to "distribution")))
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
    private fun groupedSessionEvent(id: UUID, metric: String, teams: List<UUID>, session: String,
        at: String = "2026-09-01T12:00:00Z", read: Boolean = true): String {
        val row = mapper.readTree(if(metric=="prompts_per_session") promptEvent(id,session=session,at=at)
            else toolEvent(id,true,action=if(read) "read" else "write",at=at)) as tools.jackson.databind.node.ObjectNode
        row.set("team_ids_as_of",mapper.valueToTree(teams.map { it.toString() }))
        val raw = mapper.readTree(row["raw_json"].asString()) as tools.jackson.databind.node.ObjectNode
        (raw["envelope"] as tools.jackson.databind.node.ObjectNode).put("session_id",session)
        row.put("raw_json",raw.toString())
        return row.toString()
    }
    @Test fun `세션 분포 상위와 기타는 분위수와 히스토그램을 원본 세션으로 재계산한다`() {
        val ids = installations(5); val teams = costTeams()
        for(metric in listOf("prompts_per_session","read_tool_density")) {
            seedPoints(ids.flatMap { id -> teams.zip(listOf(5,3,1)).flatMap { (team,count) ->
                (1..count).map { groupedSessionEvent(id,metric,listOf(team),team.toString()) } }+
                (1..3).map { groupedSessionEvent(id,metric,listOf(teams[1]),"second-day",at="2026-09-02T12:00:00Z") } })
            for(type in listOf("table","scalar","timeseries","distribution")) {
                val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"group_by" to listOf("team"),
                    "frame_type" to type,"limit" to 1))).andReturn().response.contentAsString)["results"]["A"]
                assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
                val offset = if(type=="timeseries") 1 else 0
                val summaries = result["frames"].filter { it["schema"]["fields"][offset]["name"].asString()=="p50" }
                val other = summaries.single { it["schema"]["fields"][offset]["labels"]["team"].asString()=="__other__" }
                assertThat(other["data"]["values"][offset][0].asDouble()).isEqualTo(3.0)
                assertThat(other["data"]["values"][offset+1][0].asDouble()).isEqualTo(3.0)
                assertThat(summaries.single { it!=other }["data"]["values"][offset][0].asDouble()).isEqualTo(5.0)
                if(type=="distribution" && metric=="prompts_per_session") {
                    val histogram = result["frames"].single { it["schema"]["fields"][0]["name"].asString()=="bucket" &&
                        it["schema"]["fields"][1]["labels"]["team"].asString()=="__other__" }
                    assertThat(histogram["data"]["values"][1].toList().map { it.asInt() }).containsExactly(5,10,0,0,0)
                }
            }
        }
    }
    @Test fun `기타의 다중 팀 이벤트를 중복하지 않고 비교 기간 그룹을 유지한다`() {
        val ids = installations(5); val teams = costTeams()
        seedPoints(ids.flatMap { id -> (1..10).map { groupedSessionEvent(id,"prompts_per_session",listOf(teams[0]),"top") }+
            (1..3).map { groupedSessionEvent(id,"prompts_per_session",teams.drop(1),"shared") }+
            (1..2).map { groupedSessionEvent(id,"prompts_per_session",teams.drop(1),"old",at="2026-08-30T12:00:00Z") } })
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "prompts_per_session","group_by" to listOf("team"),
            "frame_type" to "table","limit" to 1),mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val other = result["frames"].single { it["schema"]["fields"][0]["labels"]["team"].asString()=="__other__" }
        assertThat(other["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(3.0,2.0,3.0,2.0)
        assertThat(other["schema"]["fields"][0]["config"]["group_size"].asInt()).isEqualTo(5)
    }
    @Test fun `세션 분포 기타의 다른 이벤트로 소집단 마스킹을 해제하지 않는다`() {
        val ids = installations(5); val teams = costTeams()
        for(metric in listOf("prompts_per_session","read_tool_density")) {
            seedPoints(ids.flatMap { id -> (1..10).map { groupedSessionEvent(id,metric,listOf(teams[0]),"top") } }+
                ids.take(4).flatMap { id -> teams.drop(1).map { groupedSessionEvent(id,metric,listOf(it),it.toString()) } }+
                ids.map { groupedSessionEvent(it,if(metric=="prompts_per_session") "read_tool_density" else "prompts_per_session",teams.drop(1),"unrelated") })
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"group_by" to listOf("team"),
                "frame_type" to "table","limit" to 1))).andReturn().response.contentAsString)["results"]["A"]
            assertThat(result["status"].asInt()).isEqualTo(200)
            val other = result["frames"].single { it["schema"]["fields"][0]["labels"]["team"].asString()=="__other__" }
            assertThat(other["data"]["values"].all { it[0].isNull }).isTrue()
        }
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
    @Test fun `도구 상위 조합은 필터와 특수문자 키를 보존하고 나머지 인원을 중복 제거한다`() {
        val ids = installations(5)
        val selected = "read'한글\""
        seedPoints(ids.flatMap { id -> listOf(toolEvent(id,true,selected),toolEvent(id,true,selected),
            toolEvent(id,true,"write"),toolEvent(id,true,"search"))+(1..5).map { toolEvent(id,false,"search") } })
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tool_calls","frame_type" to "table",
            "group_by" to listOf("tool_name","action"),"limit" to 1,"params" to mapOf("success" to true))))
            .andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frames = result["frames"].toList().associateBy { it["schema"]["fields"][0]["labels"]["action"].asString() }
        assertThat(frames.keys).containsExactlyInAnyOrder(selected,"__other__")
        assertThat(frames.getValue(selected)["data"]["values"][0][0].asDouble()).isEqualTo(10.0)
        val other = frames.getValue("__other__")
        assertThat(other["data"]["values"][0][0].asDouble()).isEqualTo(10.0)
        assertThat(other["schema"]["fields"][0]["labels"]["tool_name"].asString()).isEqualTo("__other__")
        assertThat(other["schema"]["fields"][0]["config"]["group_size"].asLong()).isEqualTo(5)
    }
    private fun namedToolEvent(id: UUID, success: Boolean?, name: String, at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(toolEvent(id,success,at=at)) as tools.jackson.databind.node.ObjectNode
        val raw = mapper.readTree(row["raw_json"].asString())
        (raw["payload"] as tools.jackson.databind.node.ObjectNode).put("tool_name",name)
        row.put("raw_json",raw.toString())
        return row.toString()
    }
    @Test fun `상위 비율은 일별 비율 합이 아닌 전체 분자 분모로 선택하고 나머지도 재계산한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { id ->
            listOf(namedToolEvent(id,false,"a")) + List(9) { namedToolEvent(id,true,"a","2026-09-02T12:00:00Z") } +
                listOf("2026-09-01T12:00:00Z","2026-09-02T12:00:00Z").flatMap { at ->
                    List(2) { namedToolEvent(id,false,"b",at) }+List(3) { namedToolEvent(id,true,"b",at) }+namedToolEvent(id,null,"c",at)
                }
        })
        fun frames(type: String) = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tool_failure_rate",
            "group_by" to listOf("tool_name"),"frame_type" to type,"limit" to 1)))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        val totals = frames("table").associateBy { it["schema"]["fields"][0]["labels"]["tool_name"].asString() }
        assertThat(totals.keys).containsExactlyInAnyOrder("b","__other__")
        assertThat(totals.getValue("b")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.4,20.0,50.0)
        assertThat(totals.getValue("__other__")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.1,5.0,50.0)
        assertThat(totals.getValue("__other__")["schema"]["fields"][0]["config"]["group_size"].asLong()).isEqualTo(5)
        val daily = frames("timeseries").associateBy { it["schema"]["fields"][1]["labels"]["tool_name"].asString() }
        assertThat(daily.keys).containsExactlyInAnyOrder("b","__other__")
        assertThat(daily.getValue("b")["data"]["values"][1].toList().map { it.asDouble() }).containsExactly(0.4,0.4)
        assertThat(daily.getValue("__other__")["data"]["values"][1].toList().map { it.asDouble() }).containsExactly(1.0,0.0)
    }
    @Test fun `나머지 비율은 영 분모의 null과 소집단 마스킹을 유지한다`() {
        val ids = installations(5)
        fun frames() = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tool_failure_rate",
            "group_by" to listOf("tool_name"),"frame_type" to "table","limit" to 1)))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        seedPoints(ids.flatMap { listOf(namedToolEvent(it,null,"a"),namedToolEvent(it,null,"b")) })
        val empty = frames()
        assertThat(empty).hasSize(2)
        assertThat(empty.all { it["data"]["values"][0][0].isNull && it["data"]["values"][2][0].asDouble()==0.0 }).isTrue()
        seedPoints(ids.flatMap { listOf(namedToolEvent(it,false,"public"),namedToolEvent(it,true,"public")) }+
            ids.take(4).flatMap { listOf(namedToolEvent(it,false,"hidden-a"),namedToolEvent(it,false,"hidden-b")) })
        val protected = frames().associateBy { it["schema"]["fields"][0]["labels"]["tool_name"].asString() }
        assertThat(protected.keys).containsExactlyInAnyOrder("public","__other__")
        assertThat(protected.getValue("public")["data"]["values"][0][0].asDouble()).isEqualTo(0.5)
        assertThat(protected.getValue("__other__")["data"]["values"].toList().all { it[0].isNull }).isTrue()
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
            .andReturn().response.contentAsString)["results"]["A"]
        assertThat(limited["status"].asInt()).isEqualTo(200)
        assertThat(limited["frames"].size()).isEqualTo(101)
        val other = limited["frames"].single { it["schema"]["fields"][0]["labels"]["hour"].asString()=="__other__" }
        assertThat(other["data"]["values"][0][0].asDouble()).isEqualTo(340.0)
        assertThat(other["schema"]["fields"][0]["config"]["group_size"].asLong()).isEqualTo(5)
        queryResult(queryBody(query+mapOf("limit" to 169),range)).andExpect(status().isBadRequest)
        seedPoints(rows.drop(1))
        val masked = mapper.readTree(queryResult(queryBody(query,range)).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        val hidden = masked.single { it["data"]["values"][0][0].isNull }
        assertThat(hidden["schema"]["fields"][0]["labels"]["weekday"].asString()).isEqualTo("1")
        assertThat(hidden["schema"]["fields"][0]["labels"]["hour"].asString()).isEqualTo("9")
    }
    private fun compactionEvent(id: UUID, before: Int?, after: Int?, kind: String = "compaction",
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(promptEvent(id,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "lifecycle","payload" to mapOf("kind" to kind,
            "tokens_before" to before,"tokens_after" to after,"attrs" to mapOf("trigger" to "auto","success" to false)))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `압축 감소율은 전후 쌍의 합계 비율이며 누락 토큰을 절감으로 간주하지 않는다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(compactionEvent(it,100,90),compactionEvent(it,900,100),
            compactionEvent(it,1000,null),compactionEvent(it,null,100),compactionEvent(it,100,0,"mcp_connection")) }
        seedPoints(rows+rows.first())
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "compaction_reduction")))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.81,4050.0,5000.0)
        assertThat(frame["schema"]["fields"][1]["config"]["unit"].asString()).isEqualTo("token")
        assertThat(frame["schema"]["meta"]["data_quality"][0].asString()).contains("10개", "제외")
        val count = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "compactions","frame_type" to "scalar",
            "group_by" to listOf("trigger")))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(count["schema"]["fields"][0]["labels"]["trigger"].asString()).isEqualTo("auto")
        assertThat(count["data"]["values"][0][0].asInt()).isEqualTo(20)
        seedPoints(ids.map { compactionEvent(it,100,null) })
        val missing = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "compaction_reduction")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(missing["data"]["values"].toList().all { it[0].isNull }).isTrue()
        assertThat(missing["schema"]["fields"][0]["config"]["suppressed"].asBoolean()).isFalse()
        seedPoints(ids.map { compactionEvent(it,0,0) })
        val zero = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "compaction_reduction")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(zero[0][0].isNull).isTrue()
        assertThat(zero[2][0].isNumber).isTrue()
        assertThat(zero[2][0].asInt()).isZero()
        seedPoints(ids.map { compactionEvent(it,100,120) })
        val growth = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "compaction_reduction")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"][0][0]
        assertThat(growth.asDouble()).isEqualTo(-0.2)
    }
    @Test fun `압축 지표는 작은 비교 집단의 비율과 토큰 수 및 품질 개수를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { compactionEvent(it,100,20) } +
            ids.take(4).map { compactionEvent(it,100,null,at="2026-08-31T12:00:00Z") })
        for (metric in listOf("compactions","compaction_reduction")) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar"),
                mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
            if (metric=="compaction_reduction") assertThat(frame["schema"]["meta"]["data_quality"][0].asString())
                .contains("제외").doesNotContain("4개")
        }
    }
    private fun mcpEvent(id: UUID, status: String?, scope: String = "user",
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(promptEvent(id,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "lifecycle","payload" to mapOf(
            "kind" to "mcp_connection","attrs" to mapOf("server_name" to "github","status" to status,
                "server_scope" to scope,"transport_type" to "stdio","is_plugin" to "True")))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `MCP 상태와 연결 속성을 집계하며 범위 필터를 바인딩한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { id -> listOf(mcpEvent(id,"connected"),mcpEvent(id,"failed"),
            mcpEvent(id,"disconnected"),mcpEvent(id,null,"project"),compactionEvent(id,100,90)) }
        seedPoints(rows+rows.first())
        val ratio = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "mcp_failure_ratio",
            "group_by" to listOf("server_name")))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(ratio["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.75,15.0,20.0)
        assertThat(ratio["schema"]["fields"][0]["labels"]["server_name"].asString()).isEqualTo("github")
        for (dims in listOf(listOf("server_scope","transport_type"),listOf("server_name","is_plugin"))) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "mcp_connections",
                "frame_type" to "scalar","group_by" to dims,"params" to mapOf("server_scope" to "user"))))
                .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"][0][0].asInt()).isEqualTo(15)
            assertThat(frame["schema"]["fields"][0]["labels"][dims[1]].asString())
                .isEqualTo(if (dims[1]=="is_plugin") "True" else "stdio")
        }
        for (params in listOf(mapOf("server_scope" to ""),mapOf("server_scope" to true),mapOf("bad" to "user"))) {
            queryResult(queryBody(mapOf("metric_id" to "mcp_connections","params" to params))).andExpect(status().isBadRequest)
        }
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "mcp_connections",
            "params" to mapOf("server_scope" to "x' OR 1=1")))).andReturn().response.contentAsString)
        assertThat(empty["results"]["A"]["frames"].size()).isZero()
        seedPoints(ids.map { mcpEvent(it,"connected") })
        val zero = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "mcp_failure_ratio")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(zero.toList().map { it[0].asDouble() }).containsExactly(0.0,0.0,5.0)
    }
    @Test fun `MCP 비교 기간의 작은 집단은 연결 수와 실패율 모두 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { mcpEvent(it,"connected") } +
            ids.take(4).map { mcpEvent(it,"failed",at="2026-08-31T12:00:00Z") })
        for (metric in listOf("mcp_connections","mcp_failure_ratio")) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "scalar"),
                mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
            assertThat(frame["schema"]["fields"][0]["config"]["suppressed"].asBoolean()).isTrue()
        }
    }
    private fun stopEvent(id: UUID, reason: String?, type: String = "llm_call",
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(llmEvent(id,1,200,type=type,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to type,"payload" to mapOf(
            "model" to "claude-test","stop_reason" to reason))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `종료 사유는 호출과 응답 이벤트를 집계하고 누락 및 중복을 구별한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(stopEvent(it,"end_turn"),stopEvent(it,"end_turn","llm_response"),
            stopEvent(it,"max_tokens"),stopEvent(it,null),stopEvent(it,"ignored","llm_request")) }
        seedPoints(rows+rows.first())
        val frames = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "llm_stop_reasons",
            "group_by" to listOf("model","stop_reason")))).andExpect(status().isOk)
            .andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(frames).hasSize(3)
        val values = frames.associate { it["schema"]["fields"][0]["labels"]["stop_reason"].asString() to
            it["data"]["values"][0][0].asInt() }
        assertThat(values).containsExactlyInAnyOrderEntriesOf(mapOf("end_turn" to 10,"max_tokens" to 5,"" to 5))
        assertThat(frames.all { it["schema"]["fields"][0]["labels"]["model"].asString()=="claude-test" }).isTrue()
        queryResult(queryBody(mapOf("metric_id" to "llm_stop_reasons","params" to mapOf("status" to "ok"))))
            .andExpect(status().isBadRequest)
        seedPoints(ids.map { stopEvent(it,"ignored","llm_request") })
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "llm_stop_reasons")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(empty.size()).isZero()
    }
    @Test fun `종료 사유는 작은 비교 집단을 마스킹하고 시계열 빈 구간을 보존한다`() {
        val ids = installations(5)
        seedPoints(ids.map { stopEvent(it,"end_turn") })
        val query = mapOf("metric_id" to "llm_stop_reasons","frame_type" to "timeseries",
            "interval" to "1d","group_by" to listOf("stop_reason"))
        val frame = mapper.readTree(queryResult(queryBody(query)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"][1][0].asInt()).isEqualTo(5)
        assertThat(frame["data"]["values"][1][1].isNull).isTrue()
        seedPoints(ids.map { stopEvent(it,"end_turn") } +
            ids.take(4).map { stopEvent(it,"end_turn",at="2026-08-31T12:00:00Z") })
        val hidden = mapper.readTree(queryResult(queryBody(query,mapOf("compare" to "previous_period")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(hidden["data"]["values"].toList().drop(1).all { values -> values.toList().all { it.isNull } }).isTrue()
    }
    private fun durationEvent(id: UUID, duration: Int?, turn: Boolean = false, error: String? = null,
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(llmEvent(id,1,200,at=at)) as tools.jackson.databind.node.ObjectNode
        if (turn) row.put("signal","span")
        val payload = if (turn) mapOf("kind" to "turn","attrs" to mapOf("duration_ms" to duration?.toString()))
            else mapOf("model" to "claude-test","duration_ms" to duration,"error_type" to error)
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to if (turn) "turn" else "llm_call","payload" to payload)))
        return mapper.writeValueAsString(row)
    }
    @Test fun `시간 상위 그룹은 기간 중앙값으로 선택하고 나머지 백분위수를 원본에서 계산한다`() {
        val ids = installations(5)
        fun sample(id: UUID, model: String, value: Int, at: String): String {
            val row = mapper.readTree(durationEvent(id,value,at=at)) as tools.jackson.databind.node.ObjectNode
            val raw = mapper.readTree(row["raw_json"].asString())
            (raw["payload"] as tools.jackson.databind.node.ObjectNode).put("model",model)
            row.put("raw_json",raw.toString())
            return row.toString()
        }
        val first = "2026-09-01T12:00:00Z"
        val second = "2026-09-02T12:00:00Z"
        seedPoints(ids.flatMap { id -> listOf(sample(id,"a",100,first)) + List(9) { sample(id,"a",1,second) }+
            listOf(first,second).flatMap { listOf(sample(id,"b",20,it),sample(id,"c",10,it)) }
        })
        val totals = topFrames("llm_duration_ms","model")
        assertThat(totals.keys).containsExactlyInAnyOrder("b","__other__")
        assertThat(totals.getValue("b")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(20.0,20.0,20.0)
        assertThat(totals.getValue("__other__")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(1.0,100.0,100.0)
        assertThat(totals.getValue("__other__")["schema"]["fields"][0]["config"]["group_size"].asLong()).isEqualTo(5)
        val daily = topFrames("llm_duration_ms","model","timeseries")
        assertThat(daily.keys).containsExactlyInAnyOrder("b","__other__")
        assertThat(daily.getValue("__other__")["data"]["values"][1].toList().map { it.asDouble() }).containsExactly(100.0,1.0)
    }
    @Test fun `모델 사용자 상위 선택과 나머지는 모델 및 날짜를 가로질러 사람을 중복 제거한다`() {
        val ids = installations(10)
        seedPoints(listOf("2026-09-01T12:00:00Z","2026-09-02T12:00:00Z").flatMapIndexed { day, at ->
            ids.take(5).flatMap { listOf(modelCost(it,"a",1.0,at),modelCost(it,"c",1.0,at)) }+
                ids.drop(day*5).take(5).map { modelCost(it,"b",1.0,at) }
        })
        val totals = topFrames("model_users","model")
        assertThat(totals.keys).containsExactlyInAnyOrder("b","__other__")
        assertThat(totals.getValue("b")["data"]["values"][0][0].asDouble()).isEqualTo(10.0)
        assertThat(totals.getValue("__other__")["data"]["values"][0][0].asDouble()).isEqualTo(5.0)
        val daily = topFrames("model_users","model","timeseries")
        assertThat(daily.keys).containsExactlyInAnyOrder("b","__other__")
        assertThat(daily.getValue("__other__")["data"]["values"][1].toList().map { it.asDouble() }).containsExactly(5.0,5.0)
    }
    @Test fun `소요 시간은 정확 백분위수와 밀리초 단위를 반환하며 누락 음수 오류를 제외한다`() {
        val ids = installations(5)
        for (turn in listOf(false,true)) {
            val rows = ids.flatMap { id -> (0..19).map { durationEvent(id,it*100,turn) } +
                listOf(durationEvent(id,null,turn),durationEvent(id,-100,turn)) }
            seedPoints(rows+rows.first()+if (turn) emptyList() else ids.map { durationEvent(it,99999,error="failed") })
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to
                if (turn) "turn_duration_ms" else "llm_duration_ms","group_by" to listOf("product"))))
                .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().map { it[0].asDouble() })
                .containsExactlyElementsOf(if (turn) listOf(1000.0,1800.0) else listOf(1000.0,1900.0,1900.0))
            assertThat(frame["schema"]["fields"].toList().all { it["config"]["unit"].asString()=="ms" }).isTrue()
            seedPoints(ids.map { durationEvent(it,null,turn) })
            val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to
                if (turn) "turn_duration_ms" else "llm_duration_ms"))).andReturn().response.contentAsString)
            assertThat(empty["results"]["A"]["frames"].size()).isZero()
        }
    }
    @Test fun `소요 시간 비교의 작은 집단은 모든 백분위수를 숨긴다`() {
        val ids = installations(5)
        for (turn in listOf(false,true)) {
            seedPoints(ids.map { durationEvent(it,0,turn) })
            val metric = if (turn) "turn_duration_ms" else "llm_duration_ms"
            val query = mapOf("metric_id" to metric,"frame_type" to "timeseries","interval" to "1d")
            val frame = mapper.readTree(queryResult(queryBody(query)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"][1][0].isNumber).isTrue()
            assertThat(frame["data"]["values"][1][0].asInt()).isZero()
            assertThat(frame["data"]["values"][1][1].isNull).isTrue()
            seedPoints(ids.map { durationEvent(it,0,turn) }+
                ids.take(4).map { durationEvent(it,200,turn,at="2026-08-31T12:00:00Z") })
            val hidden = mapper.readTree(queryResult(queryBody(query,mapOf("compare" to "previous_period")))
                .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(hidden["data"]["values"].toList().drop(1).all { it.toList().all { v -> v.isNull } }).isTrue()
        }
    }
    private fun ttftEvent(id: UUID, value: Int?, request: String?, span: Boolean = false,
        product: String = "claude_code", error: String? = null, at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(llmEvent(id,1,200,product=product,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("signal",if (span) "span" else "log")
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to if (span) "llm_request" else "llm_call",
            "envelope" to mapOf("session_id" to "session"),
            "payload" to mapOf("model" to "test","request_id" to request,"ttft_ms" to value,"error_type" to error))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `첫 토큰 지연은 요청별 유효 로그를 우선하고 스팬으로 대체한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(ttftEvent(it,100,"a"),ttftEvent(it,9999,"a",true),
            ttftEvent(it,null,"b"),ttftEvent(it,200,"b",true),
            ttftEvent(it,-1,"c"),ttftEvent(it,300,"c",true),
            ttftEvent(it,9000,"d",error="failed"),ttftEvent(it,400,"d",true),
            ttftEvent(it,0,"e"),ttftEvent(it,9000,"e",true),
            ttftEvent(it,500,null),ttftEvent(it,9999,null,true),
            ttftEvent(it,700,"a",true,product="codex")) }
        seedPoints(rows+rows.first())
        val frames = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "llm_ttft_ms",
            "group_by" to listOf("product")))).andExpect(status().isOk).andReturn().response.contentAsString)
            .get("results").get("A").get("frames").toList()
        val claude = frames.single { it["schema"]["fields"][0]["labels"]["product"].asString()=="claude_code" }
        assertThat(claude["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(300.0,500.0)
        val codex = frames.single { it["schema"]["fields"][0]["labels"]["product"].asString()=="codex" }
        assertThat(codex["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(700.0,700.0)
        assertThat(claude["schema"]["fields"][0]["config"]["unit"].asString()).isEqualTo("ms")
        // 동일 요청 ID여도 다른 설치의 로그가 스팬을 제거하지 않는다.
        seedPoints(ids.mapIndexed { index, id -> ttftEvent(id,if (index==0) 100 else 900,"shared",span=index!=0) })
        val separated = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "llm_ttft_ms")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(separated["data"]["values"][0][0].asDouble()).isEqualTo(900.0)
        seedPoints(ids.map { ttftEvent(it,null,null,true) })
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "llm_ttft_ms")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(empty.size()).isZero()
    }
    @Test fun `첫 토큰 소스 선택은 버킷 경계 중복을 막고 비교 집단을 숨긴다`() {
        val ids = installations(5)
        val current = ids.flatMap { listOf(ttftEvent(it,100,"same",at="2026-09-02T01:00:00Z"),
            ttftEvent(it,900,"same",true)) }
        seedPoints(current)
        val query = mapOf("metric_id" to "llm_ttft_ms","frame_type" to "timeseries","interval" to "1d")
        val frame = mapper.readTree(queryResult(queryBody(query)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"][1][0].isNull).isTrue()
        assertThat(frame["data"]["values"][1][1].asInt()).isEqualTo(100)
        seedPoints(current+ids.take(4).map { ttftEvent(it,200,"same",true,at="2026-08-31T12:00:00Z") })
        val hidden = mapper.readTree(queryResult(queryBody(query,mapOf("compare" to "previous_period")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(hidden["data"]["values"].toList().drop(1).all { it.toList().all { v -> v.isNull } }).isTrue()
    }
    private fun gateEvent(id: UUID, wait: Int?, decision: String = "accept", by: String = "user",
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(llmEvent(id,1,200,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("signal","span")
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "tool_gate","payload" to mapOf(
            "blocked_on_user_ms" to wait,"decision" to decision,"decided_by" to by))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `승인 지표는 정확 대기 분위수와 사용자 승인만의 임계 비율을 계산한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { id -> listOf(gateEvent(id,0),gateEvent(id,1999),gateEvent(id,2000),
            gateEvent(id,3000),gateEvent(id,null),gateEvent(id,-1),gateEvent(id,100,"reject"),
            gateEvent(id,0,by="config")) }
        seedPoints(rows+rows.first())
        val gate = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "gate_wait_ms",
            "group_by" to listOf("decision","decided_by")))).andReturn().response.contentAsString)["results"]["A"]["frames"]
            .toList().single { it["schema"]["fields"][0]["labels"]["decision"].asString()=="accept" &&
                it["schema"]["fields"][0]["labels"]["decided_by"].asString()=="user" }
        assertThat(gate["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(2000.0,3000.0)
        assertThat(gate["schema"]["fields"][0]["config"]["unit"].asString()).isEqualTo("ms")
        for ((threshold, expected) in listOf(null to 0.5,0 to 0.0,2001 to 0.75,3600000 to 1.0)) {
            val q = mutableMapOf<String,Any>("metric_id" to "rubber_stamp_ratio")
            if (threshold!=null) q["params"] = mapOf("threshold_ms" to threshold)
            val values = mapper.readTree(queryResult(queryBody(q)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
            assertThat(values[0][0].asDouble()).isEqualTo(expected)
            assertThat(values[2][0].asInt()).isEqualTo(20)
        }
        for (threshold in listOf<Any>(-1,3600001,1.5,"2000",true,999999999999L)) {
            queryResult(queryBody(mapOf("metric_id" to "rubber_stamp_ratio","params" to mapOf("threshold_ms" to threshold))))
                .andExpect(status().isBadRequest)
        }
        seedPoints(ids.map { gateEvent(it,100,"reject") })
        val noAccept = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "rubber_stamp_ratio")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(noAccept[0][0].isNull).isTrue()
        assertThat(noAccept[2][0].asInt()).isZero()
    }
    @Test fun `승인 지표는 작은 비교 집단의 분위수와 비율을 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { gateEvent(it,100) } + ids.take(4).map { gateEvent(it,900,at="2026-08-31T12:00:00Z") })
        for (metric in listOf("gate_wait_ms","rubber_stamp_ratio")) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric),
                mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        }
    }
    private fun editPoint(id: UUID, value: Double?, decision: String, source: String,
        cumulative: Boolean = false, product: String = "claude_code", at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(point(id,0.0,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("product",product)
        row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.code_edit_tool.decision",
            "value" to value,"aggregation_temporality" to if (cumulative) 2 else 1,
            "attrs" to mapOf("decision" to decision,"source" to source,"language" to "kotlin","tool_name" to "Edit")))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `편집 수락률은 사용자 결정의 값 합계를 사용하고 누적 자동 승인 및 다른 제품을 제외한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { id -> listOf(editPoint(id,3.0,"accept","user_temporary"),
            editPoint(id,2.0,"accept","user_permanent"),editPoint(id,5.0,"reject","user_reject"),
            editPoint(id,99.0,"abort","user_abort"),editPoint(id,999.0,"accept","config"),
            editPoint(id,999.0,"accept","hook"),editPoint(id,999.0,"accept","user_unknown"),
            editPoint(id,999.0,"accept","user_temporary",cumulative=true),
            editPoint(id,999.0,"accept","user_temporary",product="codex"),
            editPoint(id,null,"accept","user_temporary")) }
        seedPoints(rows+rows.first())
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "edit_acceptance_rate",
            "group_by" to listOf("language","tool_name")))).andExpect(status().isOk)
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.5,25.0,50.0)
        assertThat(frame["schema"]["fields"][0]["labels"]["language"].asString()).isEqualTo("kotlin")
        assertThat(frame["schema"]["fields"][0]["labels"]["tool_name"].asString()).isEqualTo("Edit")
        assertThat(frame["schema"]["meta"]["data_quality"][0].asString()).contains("5개","제외")
        seedPoints(ids.map { editPoint(it,1.0,"abort","user_abort") })
        val emptyDenominator = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "edit_acceptance_rate")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(emptyDenominator[0][0].isNull).isTrue()
        assertThat(emptyDenominator[2][0].asDouble()).isZero()
        seedPoints(ids.map { editPoint(it,1.0,"accept","config") })
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "edit_acceptance_rate")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(empty.size()).isZero()
    }
    @Test fun `편집 수락률은 작은 비교 집단의 수락 거절 합계와 누적 개수를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { editPoint(it,3.0,"accept","user_temporary") }+
            ids.take(4).map { editPoint(it,5.0,"reject","user_reject",cumulative=true,at="2026-08-31T12:00:00Z") })
        val query = queryBody(mapOf("metric_id" to "edit_acceptance_rate"),mapOf("compare" to "previous_period"))
        val frame = mapper.readTree(queryResult(query).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        assertThat(frame["schema"]["meta"]["data_quality"][0].asString()).contains("제외").doesNotContain("4개")
        val csv = queryResult(query,accept="text/csv").andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(csv).doesNotContain("15.0","20.0")
    }
    private fun agentEvent(id: UUID, agent: String?, type: String = "tool_call",
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(promptEvent(id,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to type,"payload" to mapOf(
            "agent_id" to agent,"parent_agent_id" to "parent","tool_name" to "Read"))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `서브에이전트 활동은 식별자 고유 수와 도구 호출 비율을 각각 계산한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(agentEvent(it,"a"),agentEvent(it,"a"),agentEvent(it,"b"),
            agentEvent(it,null),agentEvent(it,"ignored","llm_call")) }
        seedPoints(rows+rows.first())
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "subagent_activity","frame_type" to "scalar")))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(2.0,0.75,15.0,20.0)
        assertThat(frame["schema"]["fields"][0]["config"]["unit"].asString()).isEqualTo("count")
        assertThat(frame["schema"]["fields"][1]["config"]["unit"].asString()).isEqualTo("ratio")
        assertThat(frame["schema"]["meta"]["data_quality"][0].asString()).contains("판정하지 않음")
        seedPoints(ids.flatMap { listOf(agentEvent(it,null),agentEvent(it,"")) })
        val zero = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "subagent_activity","frame_type" to "scalar")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(zero.toList().map { it[0].asDouble() }).containsExactly(0.0,0.0,0.0,10.0)
        seedPoints(ids.map { agentEvent(it,"ignored","llm_call") })
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "subagent_activity")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(empty.size()).isZero()
    }
    @Test fun `서브에이전트 상위는 기간 고유 식별자로 정하고 기타의 다중 팀 호출을 중복 제거한다`() {
        val ids = installations(5); val teams = costTeams()
        fun event(id: UUID, agent: String?, groups: List<UUID>, at: String): String {
            val row = mapper.readTree(agentEvent(id,agent,at=at)) as tools.jackson.databind.node.ObjectNode
            row.set("team_ids_as_of",mapper.valueToTree(groups.map { it.toString() }))
            return row.toString()
        }
        seedPoints(ids.flatMap { id -> listOf("a","b","c").map { event(id,it,listOf(teams[0]),"2026-09-01T12:00:00Z") }+
            listOf("2026-09-01T12:00:00Z","2026-09-02T12:00:00Z").flatMap { at -> listOf("x","y",null).map { event(id,it,teams.drop(1),at) } }+
            listOf("z",null).map { event(id,it,teams.drop(1),"2026-08-30T12:00:00Z") } })
        for(type in listOf("table","scalar","timeseries")) {
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "subagent_activity","group_by" to listOf("team"),
                "frame_type" to type,"limit" to 1),mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]
            assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
            val offset = if(type=="timeseries") 1 else 0
            val frames = result["frames"].toList().associateBy { it["schema"]["fields"][offset]["labels"]["team"].asString() }
            assertThat(frames.keys).containsExactlyInAnyOrder(teams[0].toString(),"__other__")
            val other = frames.getValue("__other__")
            val values = other["schema"]["fields"].toList().mapIndexed { n,f -> f["name"].asString() to other["data"]["values"][n][0] }.toMap()
            assertThat(values["value"]?.asDouble()).isEqualTo(2.0)
            assertThat(values["ratio"]?.asDouble()).isEqualTo(2.0/3.0)
            assertThat(values["numerator"]?.asDouble()).isEqualTo(if(type=="timeseries") 10.0 else 20.0)
            assertThat(values["denominator"]?.asDouble()).isEqualTo(if(type=="timeseries") 15.0 else 30.0)
            assertThat(values["value_compare"]?.asDouble()).isEqualTo(1.0)
            assertThat(values["numerator_compare"]?.asDouble()).isEqualTo(5.0)
            assertThat(values["denominator_compare"]?.asDouble()).isEqualTo(10.0)
        }
    }
    @Test fun `서브에이전트 기타 소집단은 일반 프롬프트로 마스킹이 해제되지 않는다`() {
        val ids = installations(5); val teams = costTeams()
        seedPoints(ids.flatMap { id -> listOf("a","b","c").map { inTeam(agentEvent(id,it),teams[0]) } }+
            ids.take(4).flatMap { id -> teams.drop(1).map { inTeam(agentEvent(id,"x"),it) } }+
            ids.map { inTeam(promptEvent(it),teams[1]) })
        val other = topFrames("subagent_activity","team").getValue("__other__")
        assertThat(other["data"]["values"].toList().all { it[0].isNull }).isTrue()
    }
    @Test fun `서브에이전트 활동 비교의 작은 집단은 수 비율 분자 분모 모두 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { agentEvent(it,"a") }+ids.take(4).map { agentEvent(it,"b",at="2026-08-31T12:00:00Z") })
        val query = queryBody(mapOf("metric_id" to "subagent_activity","frame_type" to "scalar"),mapOf("compare" to "previous_period"))
        val frame = mapper.readTree(queryResult(query).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["schema"]["fields"].size()).isEqualTo(8)
        assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        assertThat(frame["schema"]["fields"].toList().all { it["config"]["suppressed"].asBoolean() }).isTrue()
    }
    private fun hookEvent(id: UUID, blocking: String?, signal: String = "span",
        event: String = "PreToolUse", at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(promptEvent(id,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("signal",signal)
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "hook","payload" to mapOf("kind" to "hook",
            "attrs" to mapOf("num_blocking" to blocking,"hook_event" to event,"num_hooks" to "99")))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `훅 차단은 문자열 수를 합산하고 누락 비정상 값과 로그를 제외한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(hookEvent(it,"2"),hookEvent(it,"3"),hookEvent(it,null),
            hookEvent(it,"bad"),hookEvent(it,"-1"),hookEvent(it,"99",signal="log")) }
        seedPoints(rows+rows.first())
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_blocking","frame_type" to "scalar",
            "group_by" to listOf("hook_event")))).andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"][0][0].asLong()).isEqualTo(25)
        assertThat(frame["schema"]["fields"][0]["labels"]["hook_event"].asString()).isEqualTo("PreToolUse")
        seedPoints(ids.map { hookEvent(it,"0") })
        val zero = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_blocking","frame_type" to "scalar")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"][0][0]
        assertThat(zero.isNumber).isTrue()
        assertThat(zero.asInt()).isZero()
        seedPoints(ids.map { hookEvent(it,null) })
        val missing = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_blocking")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(missing.size()).isZero()
    }
    @Test fun `훅 차단 비교의 작은 집단은 현재와 이전 값을 모두 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { hookEvent(it,"2") }+ids.take(4).map { hookEvent(it,"3",at="2026-08-31T12:00:00Z") })
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_blocking","frame_type" to "scalar"),
            mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        assertThat(frame["schema"]["fields"].toList().all { it["config"]["suppressed"].asBoolean() }).isTrue()
    }
    private fun hookSession(id: UUID, session: String?, event: String = "PreToolUse",
        product: String = "claude_code", at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(hookEvent(id,"0",event=event,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("product",product)
        val raw = mapper.readTree(row["raw_json"].asString()) as tools.jackson.databind.node.ObjectNode
        raw.set("envelope",mapper.valueToTree(mapOf("session_id" to session)))
        row.put("raw_json",mapper.writeValueAsString(raw))
        return mapper.writeValueAsString(row)
    }
    @Test fun `훅 실행은 실행 수와 전체 세션 대비 비율을 구분한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(hookSession(it,"same"),hookSession(it,"same"),
            hookSession(it,"same","PostToolUse"),hookSession(it,null),
            hookSession(it,"same",product="codex"),promptEvent(it,session="no-hook")) }
        seedPoints(rows+rows.first())
        val frames = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_executions",
            "frame_type" to "scalar","group_by" to listOf("hook_event")))).andExpect(status().isOk)
            .andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        val pre = frames.single { it["schema"]["fields"][0]["labels"]["hook_event"].asString()=="PreToolUse" }
        assertThat(pre["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(20.0,2.0/3,10.0,15.0)
        val post = frames.single { it["schema"]["fields"][0]["labels"]["hook_event"].asString()=="PostToolUse" }
        assertThat(post["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(5.0,1.0/3,5.0,15.0)
        assertThat(pre["schema"]["fields"][1]["config"]["unit"].asString()).isEqualTo("ratio")
        seedPoints(ids.flatMap { listOf(hookSession(it,null),hookSession(it,"(unknown)")) })
        val noSession = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_executions","frame_type" to "scalar")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
        assertThat(noSession[0][0].asInt()).isEqualTo(10)
        assertThat(noSession[1][0].isNull).isTrue()
        assertThat(noSession[3][0].asInt()).isZero()
        seedPoints(ids.map { promptEvent(it) })
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_executions")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(empty.size()).isZero()
    }
    @Test fun `훅 실행 세션 비율은 작은 비교 집단에서 모든 수치를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { hookSession(it,"same") }+
            ids.take(4).map { hookSession(it,"same",at="2026-08-31T12:00:00Z") })
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_executions","frame_type" to "scalar"),
            mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["schema"]["fields"].size()).isEqualTo(8)
        assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
    }
    @Test fun `훅 상위 기타는 전체 세션 분모를 유지하고 중복 세션을 합치며 비교 그룹을 고정한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(hookSession(it,"same","A"),hookSession(it,"same","A"),
            hookSession(it,"same","B"),hookSession(it,"same","C"),promptEvent(it,session="no-hook"),
            hookSession(it,"old","B",at="2026-08-30T12:00:00Z"),hookSession(it,"old","C",at="2026-08-30T12:00:00Z")) })
        for(type in listOf("table","scalar","timeseries")) {
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "hook_executions","group_by" to listOf("hook_event"),
                "frame_type" to type,"limit" to 1),mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]
            assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
            val offset = if(type=="timeseries") 1 else 0
            val frames = result["frames"].toList().associateBy { it["schema"]["fields"][offset]["labels"]["hook_event"].asString() }
            assertThat(frames.keys).containsExactlyInAnyOrder("A","__other__")
            val other = frames.getValue("__other__")
            fun value(name: String) = other["data"]["values"][other["schema"]["fields"].toList().indexOfFirst { it["name"].asString()==name }][0].asDouble()
            assertThat(value("value")).isEqualTo(10.0)
            assertThat(value("numerator")).isEqualTo(5.0)
            assertThat(value("denominator")).isEqualTo(10.0)
            assertThat(value("ratio")).isEqualTo(0.5)
            assertThat(value("value_compare")).isEqualTo(10.0)
            assertThat(value("ratio_compare")).isEqualTo(1.0)
            assertThat(other["schema"]["fields"][offset]["config"]["group_size"].asInt()).isEqualTo(5)
        }
    }

    @Test fun `거부 상위 범주는 현재와 비교 기간의 기타를 집계하고 owner만 허용한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(refusalEvent(it,"A"),refusalEvent(it,"A"),refusalEvent(it,"B"),refusalEvent(it,"C"),
            refusalEvent(it,"B",at="2026-08-30T12:00:00Z")) })
        val request = queryBody(mapOf("metric_id" to "refusals","group_by" to listOf("category","model"),
            "frame_type" to "table","limit" to 1),mapOf("compare" to "previous_period"))
        val result = mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
        val frames = result["frames"].toList().associateBy { it["schema"]["fields"][0]["labels"]["category"].asString() }
        assertThat(frames.keys).containsExactlyInAnyOrder("A","__other__")
        assertThat(frames.getValue("__other__")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(10.0,5.0)
        assertThat(frames.getValue("__other__")["schema"]["fields"][0]["labels"]["model"].asString()).isEqualTo("__other__")
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        queryResult(request).andExpect(status().isForbidden)
    }

    @Test fun `거부와 훅 상위 선택은 숨겨진 수치를 사용하지 않고 기타 소집단을 마스킹한다`() {
        val ids = installations(5)
        for (metric in listOf("refusals","hook_executions")) {
            fun event(id: UUID, category: String) = if(metric=="refusals") refusalEvent(id,category) else hookSession(id,"same",category)
            seedPoints(ids.map { event(it,"public") }+ids.take(4).flatMap { id -> (1..3).flatMap { listOf(event(id,"a"),event(id,"b")) } }+
                ids.map { promptEvent(it) })
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"frame_type" to "table","limit" to 1,
                "group_by" to listOf(if(metric=="refusals") "category" else "hook_event")))).andReturn().response.contentAsString)["results"]["A"]
            assertThat(result["status"].asInt()).isEqualTo(200)
            val frames = result["frames"].toList().associateBy { it["schema"]["fields"][0]["labels"].properties().first().value.asString() }
            assertThat(frames.keys).containsExactlyInAnyOrder("public","__other__")
            assertThat(frames.getValue("public")["data"]["values"][0][0].asDouble()).isEqualTo(5.0)
            assertThat(frames.getValue("__other__")["data"]["values"].toList().all { it[0].isNull }).isTrue()
        }
    }

    private fun refusalEvent(id: UUID, category: String?, reason: String = "refusal", type: String = "llm_response",
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(promptEvent(id,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to type,"payload" to mapOf(
            "model" to "claude-test","stop_reason" to reason,"refusal_category" to category))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `모델 거부는 응답만 집계하고 분류 누락과 다른 종료 사유를 구분한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(refusalEvent(it,"policy"),refusalEvent(it,null),refusalEvent(it,""),
            refusalEvent(it,"ignored",reason="end_turn"),refusalEvent(it,"ignored",type="llm_call")) }
        seedPoints(rows+rows.first())
        val frames = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "refusals",
            "frame_type" to "scalar","group_by" to listOf("category","model")))).andExpect(status().isOk)
            .andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(frames.associate { it["schema"]["fields"][0]["labels"]["category"].asString() to
            it["data"]["values"][0][0].asInt() }).containsExactlyInAnyOrderEntriesOf(mapOf("policy" to 5,"unspecified" to 10))
        assertThat(frames.all { it["schema"]["fields"][0]["labels"]["model"].asString()=="claude-test" }).isTrue()
        queryResult(queryBody(mapOf("metric_id" to "refusals","group_by" to listOf("team")))).andExpect(status().isBadRequest)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        queryResult(queryBody(mapOf("metric_id" to "refusals"))).andExpect(status().isForbidden)
    }
    @Test fun `모델 거부는 작은 비교 집단을 숨기고 관측 없는 기간은 빈 프레임이다`() {
        val ids = installations(5)
        seedPoints(ids.map { refusalEvent(it,"policy") }+
            ids.take(4).map { refusalEvent(it,"policy",at="2026-08-31T12:00:00Z") })
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "refusals","frame_type" to "scalar"),
            mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        seedPoints(ids.map { refusalEvent(it,"policy",reason="end_turn") })
        val empty = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "refusals")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(empty.size()).isZero()
    }
    private fun modelPoint(id: UUID, model: String, cumulative: Boolean = false): String {
        val row = mapper.readTree(point(id,1.0,cumulative=cumulative)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.token.usage",
            "value" to 1,"aggregation_temporality" to if (cumulative) 2 else 1,"attrs" to mapOf("model" to model)))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `모델 사용자는 설치가 아닌 사람을 중복 제거하고 로그 및 메트릭 모델을 사용한다`() {
        val ids = installations(5)
        val extra = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform)
            SELECT :extra,tenant_id,member_id,invitation_id,platform FROM enrollment.installations WHERE id=:id""")
            .param("extra",extra).param("id",ids.first()).update()
        val rows = ids.flatMap { listOf(llmEvent(it,1,200,model="a"),modelPoint(it,"b"),
            modelPoint(it,"b",true),llmEvent(it,1,200,model="")) }+
            listOf(llmEvent(extra,1,200,model="a"),llmEvent(UUID.randomUUID(),1,200,model="a"))
        seedPoints(rows+rows.first())
        val frames = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "model_users","group_by" to listOf("model"))))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(frames.associate { it["schema"]["fields"][0]["labels"]["model"].asString() to
            it["data"]["values"][0][0].asInt() }).containsExactlyInAnyOrderEntriesOf(mapOf("a" to 5,"b" to 5))
        val combined = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "model_users")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"][0][0]
        assertThat(combined.asInt()).isEqualTo(5)
        val b = frames.single { it["schema"]["fields"][0]["labels"]["model"].asString()=="b" }
        assertThat(b["schema"]["meta"]["data_quality"][0].asString()).contains("5개","제외")
        seedPoints(ids.map { modelPoint(it,"b",true) })
        val cumulative = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "model_users","group_by" to listOf("model"))))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(cumulative["data"]["values"][0][0].isNull).isTrue()
    }
    @Test fun `모델 사용자 비교는 작은 집단을 숨기고 모델 필터를 적용한다`() {
        val ids = installations(5)
        seedPoints(ids.map { llmEvent(it,1,200,model="a") }+
            ids.take(4).map { llmEvent(it,1,200,model="a",at="2026-08-31T12:00:00Z") })
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "model_users","group_by" to listOf("model")),
            mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        val filtered = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "model_users"),
            mapOf("filters" to mapOf("models" to listOf("missing"))))).andReturn().response.contentAsString)["results"]["A"]["frames"]
        assertThat(filtered.size()).isZero()
    }
    private fun tokenEvent(id: UUID, input: Int?, output: Int?, read: Int?, create: Int?,
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(llmEvent(id,1,200,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "llm_call","payload" to mapOf("model" to "test",
            "tokens" to mapOf("input" to input,"output" to output,"cache_read" to read,"cache_create" to create)))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `토큰 비율은 완전한 호출의 합계를 사용하며 누락을 영으로 추정하지 않는다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(tokenEvent(it,100,50,200,100),tokenEvent(it,900,450,0,600),
            tokenEvent(it,null,900,9999,100),tokenEvent(it,-1,100,100,100)) }
        seedPoints(rows+rows.first())
        for ((metric,expected) in mapOf("cache_read_ratio" to listOf(200.0/1900,1000.0,9500.0),
            "input_output_ratio" to listOf(2.0,5000.0,2500.0))) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"group_by" to listOf("model"))))
                .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactlyElementsOf(expected)
            assertThat(frame["schema"]["fields"][1]["config"]["unit"].asString()).isEqualTo("token")
        }
        seedPoints(ids.map { tokenEvent(it,null,100,null,null) })
        for (metric in listOf("cache_read_ratio","input_output_ratio")) {
            val missing = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric)))
                .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
            assertThat(missing.toList().all { it[0].isNull }).isTrue()
        }
        seedPoints(ids.map { tokenEvent(it,0,0,0,0) })
        for (metric in listOf("cache_read_ratio","input_output_ratio")) {
            val zero = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric)))
                .andReturn().response.contentAsString)["results"]["A"]["frames"][0]["data"]["values"]
            assertThat(zero[0][0].isNull).isTrue()
            assertThat(zero[2][0].isNumber).isTrue()
            assertThat(zero[2][0].asDouble()).isZero()
        }
    }
    @Test fun `토큰 비율 비교의 작은 집단은 비율과 토큰 합계를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { tokenEvent(it,100,50,200,100) }+
            ids.take(4).map { tokenEvent(it,100,50,200,100,at="2026-08-31T12:00:00Z") })
        for (metric in listOf("cache_read_ratio","input_output_ratio")) {
            val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric),
                mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        }
    }
    private fun tokenPoint(id: UUID, type: String, value: Int, cumulative: Boolean = false): String {
        val row = mapper.readTree(point(id,value.toDouble(),cumulative=cumulative)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.token.usage",
            "value" to value,"aggregation_temporality" to if (cumulative) 2 else 1,
            "attrs" to mapOf("type" to type,"agent.name" to "worker","query_source" to "subagent","model" to "test")))))
        return mapper.writeValueAsString(row)
    }
    private fun namedTokens(id: UUID, model: String, input: Int, at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(tokenEvent(id,input,0,0,0,at)) as tools.jackson.databind.node.ObjectNode
        val raw = mapper.readTree(row["raw_json"].asString()) as tools.jackson.databind.node.ObjectNode
        (raw["payload"] as tools.jackson.databind.node.ObjectNode).put("model",model)
        row.put("raw_json",raw.toString())
        return row.toString()
    }
    @Test fun `토큰 상위 모델과 기타는 비교 집단을 고정하고 원본에서 합산한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(namedTokens(it,"top",10),namedTokens(it,"b",3),namedTokens(it,"c",2),
            namedTokens(it,"top",1,"2026-08-30T12:00:00Z"),namedTokens(it,"b",20,"2026-08-30T12:00:00Z")) })
        for(type in listOf("table","scalar","timeseries")) {
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tokens","group_by" to listOf("model"),
                "frame_type" to type,"interval" to "1d","limit" to 1),mapOf("compare" to "previous_period")))
                .andReturn().response.contentAsString)["results"]["A"]
            assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
            val offset = if(type=="timeseries") 1 else 0
            val frames = result["frames"].toList().associateBy { it["schema"]["fields"][offset]["labels"]["model"].asString() }
            assertThat(frames.keys).containsExactlyInAnyOrder("top","__other__")
            assertThat(frames.getValue("top")["data"]["values"][offset][0].asDouble()).isEqualTo(50.0)
            assertThat(frames.getValue("__other__")["data"]["values"][offset][0].asDouble()).isEqualTo(25.0)
            assertThat(frames.getValue("__other__")["data"]["values"][offset+1][0].asDouble()).isEqualTo(100.0)
            assertThat(frames.getValue("__other__")["schema"]["fields"][offset]["config"]["group_size"].asInt()).isEqualTo(5)
        }
    }
    @Test fun `토큰 종류 상위는 누적 메트릭과 선택하지 않은 종류를 합산하지 않는다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(tokenPoint(it,"input",10),tokenPoint(it,"output",3),tokenPoint(it,"cacheRead",2),
            tokenPoint(it,"cacheRead",999,true),tokenPoint(it,"cacheCreation",999)) })
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tokens","source" to "metrics",
            "group_by" to listOf("model","type"),"frame_type" to "table","limit" to 1,
            "params" to mapOf("types" to listOf("input","output","cache_read")))))
            .andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).withFailMessage(result.toString()).isEqualTo(200)
        val frames = result["frames"].toList().associateBy { it["schema"]["fields"][0]["labels"]["type"].asString() }
        assertThat(frames.keys).containsExactlyInAnyOrder("input","__other__")
        assertThat(frames.getValue("input")["data"]["values"][0][0].asDouble()).isEqualTo(50.0)
        assertThat(frames.getValue("__other__")["data"]["values"][0][0].asDouble()).isEqualTo(25.0)
        assertThat(frames.getValue("__other__")["schema"]["fields"][0]["labels"]["model"].asString()).isEqualTo("__other__")
    }
    @Test fun `토큰 상위 선택은 소집단 값을 사용하지 않고 기타도 마스킹한다`() {
        val ids = installations(5)
        seedPoints(ids.map { namedTokens(it,"public",1) }+ids.take(4).flatMap { listOf(namedTokens(it,"a",999),namedTokens(it,"b",999)) })
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tokens","group_by" to listOf("model"),
            "frame_type" to "table","limit" to 1))).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frames = result["frames"].toList().associateBy { it["schema"]["fields"][0]["labels"]["model"].asString() }
        assertThat(frames.keys).containsExactlyInAnyOrder("public","__other__")
        assertThat(frames.getValue("public")["data"]["values"][0][0].asDouble()).isEqualTo(5.0)
        assertThat(frames.getValue("__other__")["data"]["values"][0][0].isNull).isTrue()
    }
    @Test fun `토큰은 원천을 섞지 않고 종류를 정규화하여 합산한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(tokenEvent(it,100,50,200,100),tokenPoint(it,"input",10),
            tokenPoint(it,"output",20),tokenPoint(it,"cacheRead",30),tokenPoint(it,"cacheCreation",40),
            tokenPoint(it,"cacheRead",999,true),tokenPoint(it,"other",999)) }
        seedPoints(rows+rows.first())
        for ((source, expected) in mapOf("events" to mapOf("input" to 500,"output" to 250,"cache_read" to 1000,"cache_create" to 500),
            "metrics" to mapOf("input" to 50,"output" to 100,"cache_read" to 150,"cache_create" to 200))) {
            val frames = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tokens","source" to source,
                "frame_type" to "scalar","group_by" to listOf("type")))).andExpect(status().isOk)
                .andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
            assertThat(frames.associate { it["schema"]["fields"][0]["labels"]["type"].asString() to
                it["data"]["values"][0][0].asInt() }).containsExactlyInAnyOrderEntriesOf(expected)
        }
        val selected = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tokens","source" to "metrics",
            "frame_type" to "scalar","params" to mapOf("types" to listOf("cache_read","output")),
            "group_by" to listOf("agent_name","query_source")))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(selected["data"]["values"][0][0].asInt()).isEqualTo(250)
        assertThat(selected["schema"]["fields"][0]["labels"]["agent_name"].asString()).isEqualTo("worker")
        assertThat(selected["schema"]["fields"][0]["labels"]["query_source"].asString()).isEqualTo("subagent")
        queryResult(queryBody(mapOf("metric_id" to "tokens","group_by" to listOf("agent_name")))).andExpect(status().isBadRequest)
        for (types in listOf<Any>(listOf("input","input"),listOf("unknown"),"input",listOf(1))) {
            queryResult(queryBody(mapOf("metric_id" to "tokens","params" to mapOf("types" to types)))).andExpect(status().isBadRequest)
        }
    }
    @Test fun `토큰은 누락과 영을 구분하고 작은 비교 집단을 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { tokenEvent(it,null,0,null,null) })
        val frames = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tokens","frame_type" to "scalar",
            "group_by" to listOf("type")))).andReturn().response.contentAsString)["results"]["A"]["frames"].toList()
        assertThat(frames.single { it["schema"]["fields"][0]["labels"]["type"].asString()=="input" }["data"]["values"][0][0].isNull).isTrue()
        val output = frames.single { it["schema"]["fields"][0]["labels"]["type"].asString()=="output" }["data"]["values"][0][0]
        assertThat(output.isNumber).isTrue()
        assertThat(output.asInt()).isZero()
        seedPoints(ids.map { tokenEvent(it,10,20,30,40) }+
            ids.take(4).map { tokenEvent(it,10,20,30,40,at="2026-08-31T12:00:00Z") })
        val hidden = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tokens","frame_type" to "scalar"),
            mapOf("compare" to "previous_period"))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(hidden["data"]["values"].toList().all { it[0].isNull }).isTrue()
    }
    private fun sessionOutput(id: UUID, session: String, name: String, value: Double = 1.0,
        cumulative: Boolean = false, at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(point(id,value,at=at,cumulative=cumulative)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("envelope" to mapOf("session_id" to session),
            "point" to mapOf("name" to name,"value" to value,"aggregation_temporality" to if (cumulative) 2 else 1,
                "attrs" to mapOf("decision" to "accept")))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `무산출 세션은 로그 세션과 산출을 연결하고 누적과 다른 제품을 구분한다`() {
        val ids = installations(5)
        val outputs = listOf("claude_code.code_edit_tool.decision","claude_code.lines_of_code.count",
            "claude_code.commit.count","claude_code.pull_request.count")
        val rows = ids.flatMap { id -> outputs.flatMapIndexed { index, name ->
            listOf(promptEvent(id,session="s$index"),sessionOutput(id,"s$index",name)) }+
            listOf(promptEvent(id,session="empty"),promptEvent(id,session="cumulative"),
                sessionOutput(id,"cumulative",outputs[0],cumulative=true),promptEvent(id,session="zero"),
                sessionOutput(id,"zero",outputs[1],value=0.0),promptEvent(id,session="s0",product="codex"),
                sessionOutput(id,"metric-only",outputs[0]),promptEvent(id,session="(unknown)")) }
        seedPoints(rows+rows.first())
        val frame = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "abandoned_session_ratio")))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.5,20.0,40.0)
        assertThat(frame["schema"]["meta"]["data_quality"].toList().map { it.asString() }.joinToString())
            .contains("종료를 보장하지","5개")
    }
    @Test fun `무산출 세션은 마지막 로그 버킷에 배치하고 비교 집단을 숨긴다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(promptEvent(it,session="s"),promptEvent(it,session="s",at="2026-09-02T12:00:00Z"),
            sessionOutput(it,"s","claude_code.commit.count")) }
        seedPoints(rows)
        val query = mapOf("metric_id" to "abandoned_session_ratio","frame_type" to "timeseries","interval" to "1d")
        val frame = mapper.readTree(queryResult(queryBody(query)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"][1][0].isNull).isTrue()
        assertThat(frame["data"]["values"][1][1].asDouble()).isZero()
        assertThat(frame["data"]["values"][3][1].asInt()).isEqualTo(5)
        seedPoints(rows+ids.take(4).map { promptEvent(it,session="s",at="2026-08-31T12:00:00Z") })
        val hidden = mapper.readTree(queryResult(queryBody(query,mapOf("compare" to "previous_period")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(hidden["data"]["values"].toList().drop(1).all { it.toList().all { v -> v.isNull } }).isTrue()
    }
    private fun lastEvent(id: UUID, session: String, type: String, sequence: Int, error: String = "",
        at: String = "2026-09-01T12:00:00Z", signal: String = "log", product: String = "claude_code"): String {
        val row = mapper.readTree(promptEvent(id,session=session,at=at,product=product)) as tools.jackson.databind.node.ObjectNode
        row.put("signal",signal)
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to type,"sequence" to sequence,
            "envelope" to mapOf("session_id" to session),"payload" to mapOf("error_type" to error))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `마지막 로그는 시각과 순번으로 유형과 오류를 함께 고르고 제품을 분리한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { id -> listOf(lastEvent(id,"s","llm_call",1,error="old"),
            lastEvent(id,"s","user_prompt",2),lastEvent(id,"s","ignored",99,signal="span"),
            lastEvent(id,"e","llm_call",1,error="failed"),lastEvent(id,"s","llm_call",1,product="codex"),
            lastEvent(id,"(unknown)","ignored",1),lastEvent(id,"","ignored",1)) }
        seedPoints(rows+rows.first())
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "session_last_event")))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        assertThat(result["frames"].toList().associate { it["schema"]["fields"][0]["labels"]["last_event"].asString() to
            it["data"]["values"][0][0].asInt() }).containsExactlyInAnyOrderEntriesOf(mapOf("user_prompt" to 5,"api_error" to 5,"llm_call" to 5))
    }
    @Test fun `마지막 로그는 마지막 날짜에만 배치하고 비교 소집단을 숨긴다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(lastEvent(it,"s","user_prompt",99),
            lastEvent(it,"s","llm_call",1,at="2026-09-02T12:00:00Z")) }
        seedPoints(rows)
        val q = mapOf("metric_id" to "session_last_event","frame_type" to "timeseries","interval" to "1d")
        val result = mapper.readTree(queryResult(queryBody(q)).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frame = result["frames"][0]
        assertThat(frame["data"]["values"][1][0].isNull).isTrue()
        assertThat(frame["data"]["values"][1][1].asInt()).isEqualTo(5)
        seedPoints(rows+ids.take(4).map { lastEvent(it,"s","llm_call",1,at="2026-08-31T12:00:00Z") })
        val hidden = mapper.readTree(queryResult(queryBody(q,mapOf("compare" to "previous_period")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(hidden["data"]["values"].toList().drop(1).all { it.toList().all { v -> v.isNull } }).isTrue()
    }
    @Test fun `사용 집중도는 사람 설치를 합치고 익명 곡선과 올림한 상위 십퍼센트를 반환한다`() {
        val ids = installations(10)
        val extra = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform)
            SELECT :extra,tenant_id,member_id,invitation_id,platform FROM enrollment.installations WHERE id=:id""")
            .param("extra",extra).param("id",ids.first()).update()
        val unknown = UUID.randomUUID()
        val rows = ids.mapIndexed { index,id -> tokenEvent(id,index+1,0,0,0) }+
            listOf(tokenEvent(extra,100,0,0,0),tokenEvent(unknown,0,0,0,0))
        seedPoints(rows+rows.first())
        val response = queryResult(queryBody(mapOf("metric_id" to "usage_concentration")))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(response).doesNotContain(extra.toString(),unknown.toString(),ids.first().toString())
        val result = mapper.readTree(response)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frames = result["frames"].toList()
        assertThat(frames).hasSize(2)
        val summary = frames.single { it["schema"]["fields"][0]["name"].asString()=="value" }
        assertThat(summary["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(111.0/155,111.0,155.0)
        val curve = frames.single { it["schema"]["fields"][0]["name"].asString()=="population_share" }
        assertThat(curve["data"]["values"][0].size()).isEqualTo(12)
        assertThat(curve["data"]["values"][0][1].asDouble()).isEqualTo(1.0/11)
        assertThat(curve["data"]["values"][1].toList().map { it.asDouble() })
            .containsExactly(0.0,0.0,2.0,5.0,9.0,14.0,20.0,27.0,35.0,44.0,54.0,155.0)
        assertThat(curve["data"]["values"][2][11].asDouble()).isEqualTo(1.0)
    }
    @Test fun `사용 집중도는 누락과 음수 호출을 제외하고 영 분모를 보존한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(tokenEvent(it,10,20,30,40),tokenEvent(it,null,999,999,999),
            tokenEvent(it,-1,999,999,999),modelPoint(it,"test",true)) })
        val q = mapOf("metric_id" to "usage_concentration","frame_type" to "scalar")
        val frame = mapper.readTree(queryResult(queryBody(q)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.2,100.0,500.0)
        seedPoints(ids.map { tokenEvent(it,0,0,0,0) })
        val zero = mapper.readTree(queryResult(queryBody(q)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(zero["data"]["values"][0][0].isNull).isTrue()
        assertThat(zero["data"]["values"][1][0].asDouble()).isZero()
        assertThat(zero["data"]["values"][2][0].asDouble()).isZero()
        seedPoints(emptyList())
        val empty = mapper.readTree(queryResult(queryBody(q)).andReturn().response.contentAsString)["results"]["A"]
        assertThat(empty["status"].asInt()).isEqualTo(200)
        assertThat(empty["frames"].size()).isZero()
    }
    @Test fun `집중도 비교 소집단은 요약과 곡선 좌표 및 길이와 CSV를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { tokenEvent(it,98765,0,0,0) }+
            ids.take(4).map { tokenEvent(it,98765,0,0,0,at="2026-08-31T12:00:00Z") })
        val request = queryBody(mapOf("metric_id" to "usage_concentration","frame_type" to "distribution"),
            mapOf("compare" to "previous_period"))
        val result = mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        assertThat(result["frames"].size()).isEqualTo(2)
        result["frames"].forEach { frame ->
            frame["data"]["values"].forEach { column ->
                assertThat(column.size()).isEqualTo(1)
                assertThat(column[0].isNull).isTrue()
            }
            assertThat(frame["schema"]["fields"].toList().all { it["config"]["suppressed"].asBoolean() }).isTrue()
        }
        assertThat(queryResult(request,accept="text/csv").andReturn().response.contentAsString)
            .doesNotContain("98765","493825","395060")
    }
    private fun installationCreated(id: UUID, at: String) {
        jdbc.sql("UPDATE enrollment.installations SET created_at=CAST(:at AS timestamptz) WHERE id=:id")
            .param("id",id).param("at",at).update()
    }
    @Test fun `첫 사용 시간은 생성 시각과 최초 이벤트의 초 간격 및 분포를 반환한다`() {
        val ids = installations(5)
        val seconds = listOf(0L,3600L,86400L,604800L,2592000L)
        val first = java.time.Instant.parse("2026-09-01T12:00:00Z")
        ids.zip(seconds).forEach { (id,delay) -> installationCreated(id,first.minusSeconds(delay).plusMillis(500).toString()) }
        val rows = ids.flatMap { listOf(promptEvent(it),promptEvent(it,at="2026-09-02T12:00:00Z")) }
        seedPoints(rows+rows.first())
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "onboarding_ttfu","group_by" to listOf("platform"))))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frames = result["frames"].toList()
        assertThat(frames).hasSize(2)
        val histogram = frames.single { it["schema"]["fields"][0]["name"].asString()=="bucket" }
        assertThat(histogram["data"]["values"][1].toList().map { it.asInt() }).containsExactly(1,1,1,1,1)
        assertThat(histogram["schema"]["fields"][1]["config"]["unit"].asString()).isEqualTo("count")
        val summary = frames.single { it["schema"]["fields"][0]["name"].asString()=="p50" }
        assertThat(summary["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(86400.0,2592000.0)
        assertThat(summary["schema"]["fields"][0]["config"]["unit"].asString()).isEqualTo("s")
        assertThat(summary["schema"]["fields"][0]["labels"]["platform"].asString()).isEqualTo("linux")
    }
    @Test fun `첫 사용은 기간 이전 이력을 확인하고 생성 이전 및 미매핑 이벤트를 제외한다`() {
        val fresh = installations(5); val old = installations(5); val invalid = installations(5)
        fresh.forEach { installationCreated(it,"2026-09-01T12:00:00Z") }
        old.forEach { installationCreated(it,"2026-08-01T00:00:00Z") }
        invalid.forEach { installationCreated(it,"2026-09-03T00:00:00Z") }
        seedPoints(fresh.map { promptEvent(it,at="2026-09-02T12:00:00Z") }+
            old.flatMap { listOf(promptEvent(it,at="2026-08-31T12:00:00Z"),promptEvent(it,at="2026-09-02T12:00:00Z")) }+
            invalid.map { promptEvent(it) }+promptEvent(UUID.randomUUID()))
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "onboarding_ttfu","frame_type" to "timeseries")))
            .andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frame = result["frames"][0]
        assertThat(frame["data"]["values"][1][0].isNull).isTrue()
        assertThat(frame["data"]["values"][1][1].asDouble()).isEqualTo(86400.0)
        assertThat(frame["schema"]["fields"][1]["config"]["group_size"].asInt()).isEqualTo(5)
    }
    @Test fun `첫 사용 비교 소집단은 분위수와 분포 및 CSV를 숨긴다`() {
        val current = installations(5); val previous = installations(4)
        (current+previous).forEach { installationCreated(it,"2026-08-01T00:00:00Z") }
        seedPoints(current.map { promptEvent(it) }+previous.map { promptEvent(it,at="2026-08-31T12:00:00Z") })
        val request = queryBody(mapOf("metric_id" to "onboarding_ttfu"),mapOf("compare" to "previous_period"))
        val result = mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        assertThat(result["frames"].size()).isEqualTo(2)
        result["frames"].forEach { frame -> frame["schema"]["fields"].forEachIndexed { index,field ->
            if (field["type"].asString()=="number") {
                assertThat(field["config"]["suppressed"].asBoolean()).isTrue()
                assertThat(frame["data"]["values"][index].toList().all { it.isNull }).isTrue()
            }
        } }
        assertThat(queryResult(request,accept="text/csv").andReturn().response.contentAsString)
            .doesNotContain("2721600","2635200")
    }
    @Test fun `잔존율은 설치 코호트와 재사용 주차를 중복 제거하고 지난 빈 주는 영으로 채운다`() {
        val ids = installations(5)
        val extra = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform)
            SELECT :extra,tenant_id,member_id,invitation_id,platform FROM enrollment.installations WHERE id=:id""")
            .param("extra",extra).param("id",ids.first()).update()
        val old = installations(5)
        val rows = (ids+extra).map { promptEvent(it,at="2026-08-04T12:00:00Z") }+
            ids.take(3).map { promptEvent(it,at="2026-08-11T12:00:00Z") }+
            old.flatMap { listOf(promptEvent(it,at="2026-07-28T12:00:00Z"),promptEvent(it,at="2026-08-04T12:00:00Z")) }+
            promptEvent(UUID.randomUUID(),at="2026-08-04T12:00:00Z")
        seedPoints(rows+rows.first())
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "onboarding_retention"),
            mapOf("from" to "2026-08-03T00:00:00Z","to" to "2026-08-24T00:00:00Z")))
            .andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frames = result["frames"].toList().associateBy { it["schema"]["fields"][0]["labels"]["week_index"].asString() }
        assertThat(frames.keys).containsExactlyInAnyOrder("0","1","2")
        for ((week,value) in listOf(1.0,0.5,0.0).withIndex()) {
            val frame = frames.getValue(week.toString())
            assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(value,value*6,6.0)
            assertThat(frame["schema"]["fields"][0]["labels"]["cohort_week"].asString()).isEqualTo("2026-08-03")
            assertThat(frame["schema"]["fields"][0]["config"]["group_size"].asInt()).isEqualTo(5)
        }
    }
    @Test fun `잔존율 진행 중과 미래 주는 미판정이며 미래 이벤트를 사용하지 않는다`() {
        val ids = installations(5)
        val at = clock.instant().minusSeconds(5)
        val monday = at.atZone(java.time.ZoneOffset.UTC).toLocalDate().with(java.time.DayOfWeek.MONDAY)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val future = monday.plus(java.time.Duration.ofDays(8))
        seedPoints(ids.map { promptEvent(it,at=at.toString()) }+installations(5).map { promptEvent(it,at=future.toString()) })
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "onboarding_retention"),mapOf(
            "from" to monday.toString(),"to" to monday.plus(java.time.Duration.ofDays(14)).toString())))
            .andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        assertThat(result["frames"].size()).isEqualTo(2)
        result["frames"].forEach { frame ->
            assertThat(frame["data"]["values"][0][0].isNull).isTrue()
            assertThat(frame["data"]["values"][1][0].isNull).isTrue()
            assertThat(frame["data"]["values"][2][0].asInt()).isEqualTo(5)
        }
    }
    @Test fun `잔존율 비교는 상대 코호트 주를 맞추고 작은 비교 코호트를 숨긴다`() {
        val current = installations(5); val previous = installations(4)
        seedPoints(current.map { promptEvent(it,at="2026-08-18T12:00:00Z") }+
            previous.map { promptEvent(it,at="2026-08-04T12:00:00Z") })
        val request = queryBody(mapOf("metric_id" to "onboarding_retention"),mapOf("from" to "2026-08-17T00:00:00Z",
            "to" to "2026-08-31T00:00:00Z","compare" to "previous_period"))
        val result = mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        assertThat(result["frames"].size()).isEqualTo(2)
        result["frames"].forEach { frame ->
            assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
            assertThat(frame["schema"]["fields"][0]["labels"]["cohort_week"].asString()).isEqualTo("2026-08-17")
            assertThat(frame["schema"]["fields"][0]["labels"]["cohort_week_compare"].asString()).isEqualTo("2026-08-03")
        }
    }
    private fun vendorEvent(id: UUID, email: String, sequence: Int = 1, signal: String = "log",
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(promptEvent(id,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("signal",signal)
        row.put("raw_json",mapper.writeValueAsString(mapOf("sequence" to sequence,
            "envelope" to mapOf("identity" to mapOf("vendor_email" to email)))))
        return mapper.writeValueAsString(row)
    }
    private fun mismatchQuery(body: String, accept: String = "application/json") = mvc.perform(post("/v1/query")
        .header("Authorization","Bearer ${token()}").header("X-Audit-Reason","벤더 계정 불일치 정기 점검 사유")
        .header("Accept",accept).contentType("application/json").content(body))
    @Test fun `벤더 불일치는 마지막 비어 있지 않은 이벤트 주소를 비교하고 식별자는 반환하지 않는다`() {
        val ids = installations(5)
        val rows = ids.flatMapIndexed { index,id ->
            val email = "o'neal-$id@example.test"
            jdbc.sql("UPDATE enrollment.members SET email=:email WHERE id=(SELECT member_id FROM enrollment.installations WHERE id=:id)")
                .param("email",email).param("id",id).update()
            listOf(vendorEvent(id,"old@vendor.test"),vendorEvent(id,email.uppercase(),2),vendorEvent(id,"",99),
                vendorEvent(id,"ignored@vendor.test",100,signal="metric"))+
                if (index<2) listOf(vendorEvent(id,"new@vendor.test",3,signal="span")) else emptyList()
        }+vendorEvent(UUID.randomUUID(),"unknown@vendor.test")
        seedPoints(rows+rows.first())
        val response = mismatchQuery(queryBody(mapOf("metric_id" to "vendor_account_mismatch")))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val result = mapper.readTree(response)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        assertThat(result["frames"][0]["data"]["values"][0][0].asInt()).isEqualTo(2)
        assertThat(response).doesNotContain("@example.test","@vendor.test",ids.first().toString())
        assertThat(jdbc.sql("SELECT target FROM dashboard.audit_log WHERE action='query'").query(String::class.java).single())
            .isEqualTo("vendor_account_mismatch")
    }
    @Test fun `벤더 불일치 비교 소집단은 시계열과 CSV를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { vendorEvent(it,"different@vendor.test") }+
            ids.take(4).map { vendorEvent(it,"different@vendor.test",at="2026-08-31T12:00:00Z") })
        val request = queryBody(mapOf("metric_id" to "vendor_account_mismatch","frame_type" to "timeseries"),
            mapOf("compare" to "previous_period"))
        val result = mapper.readTree(mismatchQuery(request).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frame = result["frames"][0]
        assertThat(frame["data"]["values"].toList().drop(1).all { it.toList().all { v -> v.isNull } }).isTrue()
        assertThat(frame["schema"]["fields"][1]["config"]["suppressed"].asBoolean()).isTrue()
        assertThat(mismatchQuery(request,"text/csv").andReturn().response.contentAsString).doesNotContain("different@vendor.test")
    }
    @Test fun `벤더 불일치는 소유자 감사 사유를 요구하고 빈 관측은 빈 결과다`() {
        seedPoints(emptyList())
        val request = queryBody(mapOf("metric_id" to "vendor_account_mismatch"))
        queryResult(request).andExpect(status().isForbidden)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        mismatchQuery(request).andExpect(status().isForbidden)
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log").query(Long::class.java).single()).isZero()
        jdbc.sql("UPDATE enrollment.members SET role='owner' WHERE id=:id").param("id",member).update()
        val result = mapper.readTree(mismatchQuery(request).andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        assertThat(result["frames"].size()).isZero()
    }
    private fun costEvent(id: UUID, cost: Double?, at: String = "2026-09-01T12:00:00Z", team: UUID? = null): String {
        val row = mapper.readTree(llmEvent(id,1,200,at=at)) as tools.jackson.databind.node.ObjectNode
        if (team != null) row.putArray("team_ids_as_of").add(team.toString())
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "llm_call","payload" to mapOf(
            "model" to "claude-test","cost_usd" to cost,"cost_source" to "reported"))))
        return mapper.writeValueAsString(row)
    }
    private fun modelCost(id: UUID, model: String, cost: Double, at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(costEvent(id,cost,at)) as tools.jackson.databind.node.ObjectNode
        val raw = mapper.readTree(row["raw_json"].asString())
        (raw["payload"] as tools.jackson.databind.node.ObjectNode).put("model",model)
        row.put("raw_json",raw.toString())
        return row.toString()
    }
    @Test fun `비용 상위 그룹과 나머지를 원본에서 집계하고 비교 그룹을 고정한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { id -> listOf(modelCost(id,"top",10.0),modelCost(id,"second",3.0),modelCost(id,"third",2.0),
            modelCost(id,"top",1.0,"2026-08-31T12:00:00Z"),modelCost(id,"second",20.0,"2026-08-31T12:00:00Z")) })
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "cost","group_by" to listOf("model"),
            "frame_type" to "table","limit" to 1),mapOf("compare" to "previous_period")))
            .andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frames = result["frames"].toList().associateBy { it["schema"]["fields"][0]["labels"]["model"].asString() }
        assertThat(frames.keys).containsExactlyInAnyOrder("top","__other__")
        assertThat(frames.getValue("top")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(50.0,5.0)
        assertThat(frames.getValue("__other__")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(25.0,100.0)
        assertThat(frames.getValue("__other__")["schema"]["fields"][0]["config"]["group_size"].asLong()).isEqualTo(5)
    }
    @Test fun `소집단의 숨겨진 비용은 상위 선택에 쓰지 않고 나머지 집단도 마스킹한다`() {
        val ids = installations(5)
        seedPoints(ids.map { modelCost(it,"public",1.0) }+ids.take(4).flatMap {
            listOf(modelCost(it,"hidden-a",100.0),modelCost(it,"hidden-b",200.0)) })
        val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "cost","group_by" to listOf("model"),
            "frame_type" to "timeseries","interval" to "1d","limit" to 1)))
            .andReturn().response.contentAsString)["results"]["A"]
        assertThat(result["status"].asInt()).isEqualTo(200)
        val frames = result["frames"].toList().associateBy { it["schema"]["fields"][1]["labels"]["model"].asString() }
        assertThat(frames.keys).containsExactlyInAnyOrder("public","__other__")
        assertThat(frames.getValue("public")["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        val other = frames.getValue("__other__")
        assertThat(other["data"]["values"][1][0].isNull).isTrue()
        assertThat(other["schema"]["fields"][1]["config"]["suppressed"].asBoolean()).isTrue()
        assertThat(other["schema"]["fields"][1]["config"]["group_size"].isNull).isTrue()
    }
    private fun costPoint(id: UUID, value: Double, cumulative: Boolean = false): String {
        val row = mapper.readTree(point(id,value,cumulative=cumulative)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.cost.usage",
            "value" to value,"aggregation_temporality" to if (cumulative) 2 else 1,
            "attrs" to mapOf("model" to "claude-test","agent.name" to "worker")))))
        return mapper.writeValueAsString(row)
    }
    private fun discountContract(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.contracts(id,tenant_id,vendor,contract_type,name,contracted_at,starts_at,ends_at)
            VALUES (:id,:tenant,'anthropic','token_discount','테스트 할인','2026-08-01','2026-09-01','2026-09-02')""")
            .param("id",id).param("tenant",tenant).update()
        jdbc.sql("""INSERT INTO enrollment.contract_token_discounts(contract_id,model_pattern,discount_rate,effective_from,effective_to)
            VALUES (:id,'claude',0.5,'2026-09-01','2026-09-02')""").param("id",id).update()
        jdbc.sql("""INSERT INTO enrollment.contract_memberships(contract_id,member_id,assigned_at,released_at)
            SELECT :id,id,'2026-09-01T12:00:00Z','2026-09-02T12:00:00Z' FROM enrollment.members WHERE tenant_id=:tenant""")
            .param("id",id).param("tenant",tenant).update()
        return id
    }
    @Test fun `비용은 이벤트와 메트릭을 분리하고 누락 음수 누적을 제외한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(costEvent(it,10.0),costEvent(it,null),costEvent(it,-1.0),
            costPoint(it,2.0),costPoint(it,100.0,true)) }
        seedPoints(rows+rows.first())
        fun result(q: Map<String,Any>) = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "cost","frame_type" to "scalar")+q))
            .andReturn().response.contentAsString)["results"]["A"]
        val events = result(emptyMap())
        assertThat(events["status"].asInt()).isEqualTo(200)
        assertThat(events["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(50.0)
        val metrics = result(mapOf("source" to "metrics","group_by" to listOf("agent_name")))
        assertThat(metrics["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(10.0)
        assertThat(metrics["frames"][0]["schema"]["fields"][0]["labels"]["agent_name"].asString()).isEqualTo("worker")
        queryResult(queryBody(mapOf("metric_id" to "cost","group_by" to listOf("agent_name")))).andExpect(status().isBadRequest)
        seedPoints(ids.map { costEvent(it,null) })
        assertThat(result(emptyMap())["frames"][0]["data"]["values"][0][0].isNull).isTrue()
        seedPoints(ids.map { costEvent(it,0.0) })
        assertThat(result(emptyMap())["frames"][0]["data"]["values"][0][0].asDouble()).isZero()
    }
    @Test fun `계약 비용은 배정 기간과 가격 우선순위를 지키고 중복 적용을 거부한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(costEvent(it,10.0),costEvent(it,20.0,at="2026-09-02T12:00:00Z")) })
        val contract = discountContract()
        jdbc.sql("""INSERT INTO enrollment.contract_token_discounts(contract_id,model_pattern,token_type,discount_rate,effective_from)
            VALUES (:id,'claude','input',0.1,'2026-09-01')""").param("id",contract).update()
        val q = mapOf("metric_id" to "cost","frame_type" to "scalar")
        fun result(query: Map<String,Any>, basis: String) = mapper.readTree(queryResult(queryBody(query,mapOf("price_basis" to basis)))
            .andReturn().response.contentAsString)["results"]["A"]
        assertThat(result(q,"contract")["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(125.0)
        assertThat(result(q+mapOf("price_basis" to "list"),"contract")["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(150.0)
        assertThat(result(q+mapOf("price_basis" to "contract"),"list")["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(125.0)
        jdbc.sql("UPDATE enrollment.contracts SET ends_at='2026-08-31' WHERE id=:id").param("id",contract).update()
        assertThat(result(q,"contract")["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(150.0)
        jdbc.sql("UPDATE enrollment.contracts SET ends_at='2026-09-02' WHERE id=:id").param("id",contract).update()
        discountContract()
        val overlap = result(q,"contract")
        assertThat(overlap["status"].asInt()).isEqualTo(422)
        assertThat(overlap["error"]["error"].asString()).isEqualTo("contract_overlap")
    }
    @Test fun `비용 비교 소집단은 금액과 원천 개수를 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { costEvent(it,98765.0) }+ids.take(4).map { costEvent(it,98765.0,at="2026-08-31T12:00:00Z") })
        val request = queryBody(mapOf("metric_id" to "cost","frame_type" to "scalar"),mapOf("compare" to "previous_period"))
        val response = queryResult(request).andReturn().response.contentAsString
        val frame = mapper.readTree(response)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().all { it[0].isNull }).isTrue()
        assertThat(frame["schema"]["meta"]["data_quality"].toString()).contains("reported·estimated").doesNotContain("9개")
        assertThat(queryResult(request,accept="text/csv").andReturn().response.contentAsString).doesNotContain("493825","395060")
    }
    private fun subagentCost(id: UUID, value: Double, source: String, model: String = "claude-test",
        cumulative: Boolean = false, at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(point(id,value,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.cost.usage",
            "value" to value,"aggregation_temporality" to if (cumulative) 2 else 1,
            "attrs" to mapOf("query_source" to source,"model" to model)))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `서브에이전트 비용 비율은 메트릭만 사용하고 계약 배율을 각 비용에 적용한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(subagentCost(it,2.0,"subagent"),subagentCost(it,6.0,"","other"),
            subagentCost(it,100.0,"subagent",cumulative=true),subagentCost(it,-1.0,"subagent"),costEvent(it,9999.0)) }
        seedPoints(rows+rows.first())
        fun result(basis: String) = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "subagent_cost_ratio"),
            mapOf("price_basis" to basis))).andReturn().response.contentAsString)["results"]["A"]
        val list = result("list")
        assertThat(list["status"].asInt()).isEqualTo(200)
        assertThat(list["frames"][0]["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.25,10.0,40.0)
        assertThat(list["frames"][0]["schema"]["fields"][1]["config"]["unit"].asString()).isEqualTo("USD")
        discountContract()
        val contract = result("contract")
        assertThat(contract["frames"][0]["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(5.0/35,5.0,35.0)
    }
    @Test fun `서브에이전트 비용 비율은 영 분모와 작은 비교 집단을 구분한다`() {
        val ids = installations(5)
        val q = mapOf("metric_id" to "subagent_cost_ratio")
        seedPoints(ids.map { subagentCost(it,0.0,"subagent") })
        val zero = mapper.readTree(queryResult(queryBody(q)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(zero["data"]["values"][0][0].isNull).isTrue()
        assertThat(zero["data"]["values"][1][0].asDouble()).isZero()
        assertThat(zero["data"]["values"][2][0].asDouble()).isZero()
        seedPoints(ids.map { subagentCost(it,10.0,"main") })
        val noSubagent = mapper.readTree(queryResult(queryBody(q)).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(noSubagent["data"]["values"][0][0].asDouble()).isZero()
        seedPoints(ids.map { subagentCost(it,10.0,"subagent") }+
            ids.take(4).map { subagentCost(it,10.0,"subagent",at="2026-08-31T12:00:00Z") })
        val hidden = mapper.readTree(queryResult(queryBody(q,mapOf("compare" to "previous_period")))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(hidden["data"]["values"].toList().all { it[0].isNull }).isTrue()
    }
    private fun userTime(id: UUID, seconds: Double, type: String = "user", cumulative: Boolean = false,
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(point(id,seconds,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("point" to mapOf("name" to "claude_code.active_time.total",
            "value" to seconds,"aggregation_temporality" to if (cumulative) 2 else 1,"attrs" to mapOf("type" to type)))))
        return mapper.writeValueAsString(row)
    }
    private fun costTeams(): List<UUID> = (1..3).map { index -> UUID.randomUUID().also { id ->
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,:name)")
            .param("id",id).param("tenant",tenant).param("name","비용 팀 $index").update()
    } }
    private fun inTeam(json: String, team: UUID): String {
        val row = mapper.readTree(json) as tools.jackson.databind.node.ObjectNode
        row.putArray("team_ids_as_of").add(team.toString())
        return row.toString()
    }
    private fun topFrames(metric: String, group: String, type: String = "table") =
        mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric,"group_by" to listOf(group),
            "frame_type" to type,"limit" to 1))).andExpect(status().isOk).andReturn().response.contentAsString)["results"]["A"]["frames"].toList().associateBy {
                it["schema"]["fields"][if(type=="timeseries") 1 else 0]["labels"][group].asString()
            }
    @Test fun `사용자당 비용의 상위 선택과 나머지는 기간 전체 사용자를 중복 제거한다`() {
        val teams = costTeams()
        val ids = installations(10)
        seedPoints(listOf("2026-09-01T12:00:00Z","2026-09-02T12:00:00Z").flatMapIndexed { day, at ->
            ids.take(5).flatMap { listOf(costEvent(it,5.0,at,teams[0]),costEvent(it,2.0,at,teams[2])) }+
                ids.drop(day*5).take(5).map { costEvent(it,6.0,at,teams[1]) }
        })
        val totals = topFrames("cost_per_active_user","team")
        assertThat(totals.keys).containsExactlyInAnyOrder(teams[0].toString(),"__other__")
        assertThat(totals.getValue(teams[0].toString())["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(10.0,50.0,5.0)
        assertThat(totals.getValue("__other__")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(8.0,80.0,10.0)
        val daily = topFrames("cost_per_active_user","team","timeseries")
        assertThat(daily.keys).containsExactlyInAnyOrder(teams[0].toString(),"__other__")
        assertThat(daily.getValue("__other__")["data"]["values"][1].toList().map { it.asDouble() }).containsExactly(8.0,4.0)
    }
    @Test fun `시간당 비용과 서브에이전트 나머지는 각 원천의 분모를 재집계한다`() {
        val teams = costTeams()
        val ids = installations(5)
        fun source(id: UUID, amount: Double, subagent: Boolean, team: UUID): String {
            val row = mapper.readTree(costPoint(id,amount)) as tools.jackson.databind.node.ObjectNode
            val raw = mapper.readTree(row["raw_json"].asString())
            (raw["point"]["attrs"] as tools.jackson.databind.node.ObjectNode).put("query_source",if(subagent) "subagent" else "main")
            row.put("raw_json",raw.toString())
            return inTeam(row.toString(),team)
        }
        seedPoints(ids.flatMap { id -> teams.flatMapIndexed { i, team ->
            val sub = listOf(8.0,1.0,1.0)[i]
            val all = listOf(10.0,2.0,8.0)[i]
            listOf(costEvent(id,sub,team=team),inTeam(userTime(id,if(i==2) 32400.0 else 3600.0),team),
                source(id,sub,true,team),source(id,all-sub,false,team))
        } })
        for (metric in listOf("cost_per_user_hour","subagent_cost_ratio")) {
            val frames = topFrames(metric,"team")
            assertThat(frames.keys).containsExactlyInAnyOrder(teams[0].toString(),"__other__")
            assertThat(frames.getValue("__other__")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.2,10.0,50.0)
        }
    }
    @Test fun `모델 단가 나머지는 모델 단가 평균이 아닌 비용과 토큰 합계로 계산한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { id -> listOf(Triple("a",10.0,5),Triple("b",1.0,1),Triple("c",9.0,99)).map { (model,cost,tokens) ->
            val row = mapper.readTree(pricedTokens(id,cost,tokens)) as tools.jackson.databind.node.ObjectNode
            val raw = mapper.readTree(row["raw_json"].asString())
            (raw["payload"] as tools.jackson.databind.node.ObjectNode).put("model",model)
            row.put("raw_json",raw.toString())
            row.toString()
        } })
        val frames = topFrames("model_unit_price","model")
        assertThat(frames.keys).containsExactlyInAnyOrder("a","__other__")
        assertThat(frames.getValue("a")["data"]["values"][0][0].asDouble()).isEqualTo(2.0)
        assertThat(frames.getValue("__other__")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.1,50.0,500.0)
    }
    @Test fun `사용자 비용은 사람을 중복 제거하고 시간 비용은 사용자 시간만 합산한다`() {
        val ids = installations(5)
        val extra = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform)
            SELECT :extra,tenant_id,member_id,invitation_id,platform FROM enrollment.installations WHERE id=:id""")
            .param("extra",extra).param("id",ids.first()).update()
        val rows = (ids+extra).flatMap { listOf(costEvent(it,10.0),userTime(it,3600.0),
            userTime(it,9999.0,"cli"),userTime(it,9999.0,cumulative=true)) }
        seedPoints(rows+rows.first())
        fun frame(metric: String, basis: String = "list") = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric),
            mapOf("price_basis" to basis))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame("cost_per_active_user")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(12.0,60.0,5.0)
        val hours = frame("cost_per_user_hour")
        assertThat(hours["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(10.0,60.0,6.0)
        assertThat(hours["schema"]["fields"][2]["config"]["unit"].asString()).isEqualTo("h")
        discountContract()
        assertThat(frame("cost_per_active_user","contract")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(6.0,30.0,5.0)
        assertThat(frame("cost_per_user_hour","contract")["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(5.0,30.0,6.0)
    }
    @Test fun `사용자 비용 폴백과 시간 미관측을 구분하고 비교 소집단을 숨긴다`() {
        val ids = installations(5)
        seedPoints(ids.map { costEvent(it,10.0) })
        fun result(metric: String, compare: Boolean = false) = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to metric),
            if (compare) mapOf("compare" to "previous_period") else emptyMap()))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        val users = result("cost_per_active_user")
        assertThat(users["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(10.0,50.0,5.0)
        assertThat(users["schema"]["meta"]["active_user_definition"].asString()).isEqualTo("any_event")
        val hours = result("cost_per_user_hour")
        assertThat(hours["data"]["values"][0][0].isNull).isTrue()
        assertThat(hours["data"]["values"][1][0].asDouble()).isEqualTo(50.0)
        assertThat(hours["data"]["values"][2][0].isNull).isTrue()
        seedPoints(ids.flatMap { listOf(costEvent(it,10.0),userTime(it,3600.0)) }+
            ids.take(4).flatMap { listOf(costEvent(it,10.0,at="2026-08-31T12:00:00Z"),userTime(it,3600.0,at="2026-08-31T12:00:00Z")) })
        for (metric in listOf("cost_per_active_user","cost_per_user_hour")) {
            assertThat(result(metric,true)["data"]["values"].toList().all { it[0].isNull }).isTrue()
        }
    }
    private fun pricedTokens(id: UUID, cost: Double?, input: Int?, output: Int = 0, read: Int = 0, create: Int = 0,
        at: String = "2026-09-01T12:00:00Z"): String {
        val row = mapper.readTree(costEvent(id,cost,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "llm_call","payload" to mapOf(
            "model" to "claude-test","cost_usd" to cost,"tokens" to mapOf("input" to input,"output" to output,
                "cache_read" to read,"cache_create" to create)))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `모델 단가는 완전한 호출의 비용과 네 토큰 합계로 계산하고 계약을 적용한다`() {
        val ids = installations(5)
        val rows = ids.flatMap { listOf(pricedTokens(it,9.0,100,50,200,100),pricedTokens(it,1.0,1000),
            pricedTokens(it,999.0,null),pricedTokens(it,null,99999),pricedTokens(it,999.0,-1),costPoint(it,999.0)) }
        seedPoints(rows+rows.first())
        fun result(basis: String) = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "model_unit_price","group_by" to listOf("model")),
            mapOf("price_basis" to basis))).andReturn().response.contentAsString)["results"]["A"]
        val list = result("list")
        assertThat(list["status"].asInt()).isEqualTo(200)
        val frame = list["frames"][0]
        assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(50.0/7250,50.0,7250.0)
        assertThat(frame["schema"]["fields"][0]["labels"]["model"].asString()).isEqualTo("claude-test")
        assertThat(frame["schema"]["fields"][2]["config"]["unit"].asString()).isEqualTo("token")
        discountContract()
        assertThat(result("contract")["frames"][0]["data"]["values"].toList().map { it[0].asDouble() })
            .containsExactly(25.0/7250,25.0,7250.0)
    }
    @Test fun `모델 단가는 토큰 누락과 영 분모를 구분하고 비교 집단을 숨긴다`() {
        val ids = installations(5)
        fun result(compare: Boolean = false) = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "model_unit_price"),
            if (compare) mapOf("compare" to "previous_period") else emptyMap()))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        seedPoints(ids.map { pricedTokens(it,1.0,null) })
        assertThat(result()["data"]["values"].toList().all { it[0].isNull }).isTrue()
        seedPoints(ids.map { pricedTokens(it,1.0,0) })
        val zero = result()
        assertThat(zero["data"]["values"][0][0].isNull).isTrue()
        assertThat(zero["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        assertThat(zero["data"]["values"][2][0].asDouble()).isZero()
        seedPoints(ids.map { pricedTokens(it,1.0,100) }+ids.take(4).map { pricedTokens(it,1.0,100,at="2026-08-31T12:00:00Z") })
        assertThat(result(true)["data"]["values"].toList().all { it[0].isNull }).isTrue()
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
            r.add("pulsemetry.dashboard.worker-enabled") { "false" }
            r.add("pulsemetry.dashboard.clickhouse-url") { "http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}" }
            r.add("pulsemetry.dashboard.tenant-id") { tenant.toString() }
            r.add("pulsemetry.dashboard.issuer") { "https://auth.test" }
            r.add("pulsemetry.dashboard.active-kid") { "test" }
            r.add("pulsemetry.dashboard.private-key-file") { privateFile }
            r.add("pulsemetry.dashboard.public-key-files.test") { publicFile }
        }
    }
    @Test fun `비용 이상은 직전 달력일 평균과 할인 비용을 사용하고 별칭을 검증한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(costEvent(it,30.0),
            costEvent(it,10.0,at="2026-08-31T12:00:00Z"),costEvent(it,20.0,at="2026-08-30T12:00:00Z")) })
        fun frame(params: Map<String,Int>, basis: String = "list") = mapper.readTree(queryResult(queryBody(
            mapOf("metric_id" to "cost_anomaly","params" to params),mapOf("price_basis" to basis)))
            .andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        val result = frame(mapOf("moving_avg_days" to 2))
        assertThat(result["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(1.0,150.0,75.0)
        assertThat(frame(mapOf("window_days" to 2))["data"]).isEqualTo(result["data"])
        discountContract()
        // 이 계약은 9월 1일부터 적용되므로 기준 기간은 정가다.
        assertThat(frame(mapOf("window_days" to 2),"contract")["data"]["values"].toList().map { it[0].asDouble() })
            .containsExactly(0.0,75.0,75.0)
        for (params in listOf(mapOf("moving_avg_days" to 0),mapOf("window_days" to 91),
            mapOf("moving_avg_days" to 2,"window_days" to 3))) {
            queryResult(queryBody(mapOf("metric_id" to "cost_anomaly","params" to params))).andExpect(status().isBadRequest)
        }
    }
    @Test fun `비용 이상은 누락일과 영 평균을 null로 두고 기준 소집단도 숨긴다`() {
        val ids = installations(5)
        fun frame(days: Int) = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "cost_anomaly",
            "params" to mapOf("moving_avg_days" to days)))).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        seedPoints(ids.flatMap { listOf(costEvent(it,30.0),costEvent(it,0.0,at="2026-08-31T12:00:00Z")) })
        assertThat(frame(1)["data"]["values"][0][0].isNull).isTrue()
        assertThat(frame(1)["data"]["values"][2][0].asDouble()).isZero()
        assertThat(frame(2)["data"]["values"][2][0].isNull).isTrue()
        seedPoints(ids.map { costEvent(it,30.0) }+ids.take(4).map { costEvent(it,10.0,at="2026-08-31T12:00:00Z") })
        assertThat(frame(1)["data"]["values"].toList().all { it[0].isNull }).isTrue()
    }

    @Test fun `비용 이상 시계열은 날짜와 비교 기준 집단 마스킹을 함께 적용한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(costEvent(it,30.0),costEvent(it,10.0,at="2026-08-31T12:00:00Z")) }+
            ids.take(4).map { costEvent(it,10.0,at="2026-08-30T12:00:00Z") })
        val request = queryBody(mapOf("metric_id" to "cost_anomaly","frame_type" to "timeseries",
            "group_by" to listOf("query_source"),"params" to mapOf("moving_avg_days" to 1)),mapOf("compare" to "previous_period"))
        val frame = mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["schema"]["fields"][1]["config"]["suppressed"].asBoolean()).isTrue()
        assertThat(frame["data"]["values"].toList().drop(1).all { it.toList().all { value -> value.isNull } }).isTrue()
        queryResult(queryBody(mapOf("metric_id" to "cost_anomaly","interval" to "1h"))).andExpect(status().isBadRequest)
    }

    @Test fun `에이전트 비용 이상은 시간대의 달력일과 비누적 메트릭만 사용한다`() {
        val ids = installations(5)
        fun metric(id: UUID, value: Double, at: String, cumulative: Boolean = false): String {
            val row = mapper.readTree(costPoint(id,value,cumulative)) as tools.jackson.databind.node.ObjectNode
            row.put("ts",java.time.Instant.parse(at).epochSecond)
            return mapper.writeValueAsString(row)
        }
        seedPoints(ids.flatMap { listOf(metric(it,2.0,"2026-09-01T16:00:00Z"),
            metric(it,1.0,"2026-08-31T16:00:00Z"),metric(it,999.0,"2026-09-01T16:00:00Z",true),
            metric(it,-100.0,"2026-09-01T16:00:00Z")) })
        val request = queryBody(mapOf("metric_id" to "cost_anomaly","group_by" to listOf("agent_name"),
            "frame_type" to "timeseries","params" to mapOf("moving_avg_days" to 1)),
            mapOf("from" to "2026-09-01T15:00:00Z","to" to "2026-09-02T15:00:00Z","tz" to "Asia/Seoul"))
        val frame = mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"][0][0].asLong()).isEqualTo(java.time.Instant.parse("2026-09-01T15:00:00Z").toEpochMilli())
        assertThat(frame["data"]["values"].toList().drop(1).map { it[0].asDouble() }).containsExactly(1.0,10.0,5.0)
        assertThat(frame["schema"]["fields"][1]["labels"]["agent_name"].asString()).isEqualTo("worker")
    }

    private fun termContract(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("""INSERT INTO enrollment.contracts(id,tenant_id,vendor,contract_type,name,contracted_at,starts_at,ends_at)
            VALUES (:id,:tenant,'anthropic','term_commitment','테스트 약정','2026-08-01','2026-08-01','2026-09-30')""")
            .param("id",id).param("tenant",tenant).update()
        jdbc.sql("INSERT INTO enrollment.contract_term_commitments(contract_id,commitment_months,commitment_amount) VALUES (:id,2,1000)")
            .param("id",id).update()
        jdbc.sql("""INSERT INTO enrollment.contract_memberships(contract_id,member_id,assigned_at)
            SELECT :id,id,'2026-08-01T00:00:00Z' FROM enrollment.members WHERE tenant_id=:tenant""")
            .param("id",id).param("tenant",tenant).update()
        return id
    }
    @Test fun `약정 소진율은 계약 배정 벤더 기간과 할인 비용으로 계산한다`() {
        val ids = installations(5)
        val contract = termContract()
        discountContract()
        jdbc.sql("""UPDATE enrollment.contract_memberships SET assigned_at='2026-09-01T12:00:00Z',
            released_at='2026-09-01T13:00:00Z' WHERE contract_id=:id""").param("id",contract).update()
        val valid = ids.map { costEvent(it,10.0) }
        val wrongVendor = ids.map { id ->
            val row = mapper.readTree(costEvent(id,999.0)) as tools.jackson.databind.node.ObjectNode
            row.put("product","codex")
            mapper.writeValueAsString(row)
        }
        seedPoints(valid+valid.first()+wrongVendor+ids.flatMap { listOf(costEvent(it,999.0,at="2026-09-01T11:59:59Z"),
            costEvent(it,999.0,at="2026-09-01T13:00:00Z"),costPoint(it,999.0)) })
        val request = queryBody(mapOf("metric_id" to "contract_commitment_burn","params" to mapOf("contract_id" to contract.toString())))
        val frame = mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().map { it[0].asDouble() }).containsExactly(0.025,25.0,1000.0)
        assertThat(frame["schema"]["fields"][0]["labels"]["contract_id"].asString()).isEqualTo(contract.toString())
        assertThat(frame["schema"]["fields"][2]["config"]["unit"].asString()).isEqualTo("USD")
        jdbc.sql("UPDATE enrollment.contracts SET terminated_at='2026-09-01T12:00:00Z' WHERE id=:id").param("id",contract).update()
        assertThat(mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]["frames"].size()).isZero()
    }
    @Test fun `약정액 누락과 영 금액을 구분하고 통화와 음수는 오류로 반환한다`() {
        val ids = installations(5)
        val contract = termContract()
        seedPoints(ids.map { costEvent(it,10.0) })
        fun result() = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "contract_commitment_burn")))
            .andReturn().response.contentAsString)["results"]["A"]
        for (amount in listOf("NULL","0")) {
            jdbc.sql("UPDATE enrollment.contract_term_commitments SET commitment_amount=$amount WHERE contract_id=:id").param("id",contract).update()
            val frame = result()["frames"][0]
            assertThat(frame["data"]["values"][0][0].isNull).isTrue()
            assertThat(frame["data"]["values"][1][0].asDouble()).isEqualTo(50.0)
            assertThat(frame["data"]["values"][2][0].isNull).isEqualTo(amount=="NULL")
        }
        jdbc.sql("UPDATE enrollment.contract_term_commitments SET currency='KRW' WHERE contract_id=:id").param("id",contract).update()
        assertThat(result()["status"].asInt()).isEqualTo(422)
        jdbc.sql("UPDATE enrollment.contract_term_commitments SET currency='USD',commitment_amount=-1 WHERE contract_id=:id").param("id",contract).update()
        assertThat(result()["status"].asInt()).isEqualTo(422)
    }
    @Test fun `약정 소진율 비교 집단은 비율 비용 약정액과 CSV를 숨긴다`() {
        val ids = installations(5)
        termContract()
        seedPoints(ids.map { costEvent(it,10.0) }+ids.take(4).map { costEvent(it,10.0,at="2026-08-31T12:00:00Z") })
        val request = queryBody(mapOf("metric_id" to "contract_commitment_burn","frame_type" to "timeseries"),
            mapOf("compare" to "previous_period"))
        val frame = mapper.readTree(queryResult(request).andReturn().response.contentAsString)["results"]["A"]["frames"][0]
        assertThat(frame["data"]["values"].toList().drop(1).all { it.toList().all { value -> value.isNull } }).isTrue()
        assertThat(frame["schema"]["fields"][1]["config"]["suppressed"].asBoolean()).isTrue()
        assertThat(queryResult(request,"text/csv").andReturn().response.contentAsString).doesNotContain("1000","50.0","40.0")
    }
    @Test fun `약정 조회는 owner 전사 범위와 UUID를 검증하고 다른 tenant 계약을 반환하지 않는다`() {
        seedPoints(emptyList())
        val request = queryBody(mapOf("metric_id" to "contract_commitment_burn"))
        queryResult(queryBody(mapOf("metric_id" to "contract_commitment_burn","params" to mapOf("contract_id" to "bad"))))
            .andExpect(status().isBadRequest)
        queryResult(queryBody(mapOf("metric_id" to "contract_commitment_burn"),mapOf("filters" to mapOf("products" to listOf("codex")))))
            .andExpect(status().isForbidden)
        val other = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'다른 조직')").param("id",other).update()
        val contract = termContract()
        jdbc.sql("DELETE FROM enrollment.contract_memberships WHERE contract_id=:id").param("id",contract).update()
        jdbc.sql("UPDATE enrollment.contracts SET tenant_id=:tenant WHERE id=:id").param("tenant",other).param("id",contract).update()
        val foreign = queryBody(mapOf("metric_id" to "contract_commitment_burn","params" to mapOf("contract_id" to contract.toString())))
        assertThat(mapper.readTree(queryResult(foreign).andReturn().response.contentAsString)["results"]["A"]["frames"].size()).isZero()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        queryResult(request).andExpect(status().isForbidden)
    }

    private fun installationList(params: String = "", reason: String? = "설치 운영 상태 정기 점검") = mvc.perform(
        get("/v1/installations$params").header("Authorization","Bearer ${token()}").apply {
            if (reason!=null) header("X-Audit-Reason",java.net.URLEncoder.encode(reason,java.nio.charset.StandardCharsets.UTF_8))
        })
    private fun versionEvent(id: UUID, at: java.time.Instant, product: String, version: String): String {
        val row = mapper.readTree(point(id,1.0,at=at.toString())) as tools.jackson.databind.node.ObjectNode
        row.put("product",product)
        row.put("raw_json",mapper.writeValueAsString(mapOf("envelope" to mapOf("client" to mapOf("version" to version)))))
        return mapper.writeValueAsString(row)
    }
    @Test fun `설치 목록은 실제 마지막 이벤트와 제품 버전을 결합하고 키셋으로 페이지를 나눈다`() {
        val ids = installations(3).sortedBy { it.toString() }
        val now = clock.instant().minusSeconds(60)
        val rows = listOf(versionEvent(ids[0],now.minusSeconds(10),"claude_code","1"),
            versionEvent(ids[0],now,"claude_code","2"),versionEvent(ids[0],now.minusSeconds(5),"codex","3"),
            versionEvent(ids[0],now.plusSeconds(3600),"codex","future"))
        seedPoints(rows+rows[1])
        jdbc.sql("UPDATE enrollment.installations SET hostname='fixture-host',client_version='ctl-1' WHERE id=:id").param("id",ids[0]).update()
        val first = mapper.readTree(installationList("?limit=2&cursor=").andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(first["items"].toList().map { it["installation_id"].asString() }).containsExactly(ids[0].toString(),ids[1].toString())
        val item = first["items"][0]
        assertThat(item["last_event_at"].asString()).isEqualTo(java.time.Instant.ofEpochSecond(now.epochSecond).toString())
        assertThat(item["product_versions"]["claude_code"].asString()).isEqualTo("2")
        assertThat(item["product_versions"]["codex"].asString()).isEqualTo("3")
        assertThat(item["hostname"].asString()).isEqualTo("fixture-host")
        assertThat(item["member_email_masked"].asString()).startsWith("***@")
        assertThat(first["items"][1]["last_event_at"].isNull).isTrue()
        val second = mapper.readTree(installationList("?limit=2&cursor="+first["next_cursor"].asString()).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(second["items"].toList().map { it["installation_id"].asString() }).containsExactly(ids[2].toString())
        assertThat(second["next_cursor"].isNull).isTrue()
        assertThat(second["total"].isNull).isTrue()
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='installations'").query(Long::class.java).single()).isEqualTo(2)
    }
    @Test fun `무활동은 마지막 이벤트와 미관측 설치의 생성일을 기준으로 필터링한다`() {
        val ids = installations(3)
        val now = clock.instant()
        jdbc.sql("UPDATE enrollment.installations SET created_at=now()-interval '100 days',last_seen_at=now(),status='revoked' WHERE id=:id")
            .param("id",ids[0]).update()
        seedPoints(listOf(versionEvent(ids[2],now.minusSeconds(86400),"claude_code","1")))
        val result = mapper.readTree(installationList("?inactive_days=30&platform=linux&status=revoked&limit=1")
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(result["items"].size()).isEqualTo(1)
        assertThat(result["items"][0]["installation_id"].asString()).isEqualTo(ids[0].toString())
        assertThat(result["items"][0]["last_seen_at"].isNull).isFalse()
        assertThat(result["items"][0]["last_event_at"].isNull).isTrue()
        assertThat(result["next_cursor"].isNull).isTrue()
    }
    @Test fun `설치 조회는 owner 감사와 요청 범위를 확인한다`() {
        installations(1)
        seedPoints(emptyList())
        installationList(reason=null).andExpect(status().isForbidden)
        for (params in listOf("?limit=0","?limit=501","?inactive_days=0","?platform=darwin","?status=deleted","?cursor=bad"))
            installationList(params).andExpect(status().isBadRequest)
        installationList("?team_id="+UUID.randomUUID()).andExpect(status().isForbidden)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        installationList().andExpect(status().isForbidden)
    }

    @Test fun `설치 목록의 팀 필터는 현재 소속을 사용하고 다른 조직 설치는 제외한다`() {
        val ids = installations(3)
        val team = UUID.randomUUID()
        val other = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'설치 팀')").param("id",team).param("tenant",tenant).update()
        jdbc.sql("""INSERT INTO enrollment.team_memberships(team_id,member_id)
            SELECT :team,member_id FROM enrollment.installations WHERE id=:id""").param("team",team).param("id",ids[0]).update()
        jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'외부 조직')").param("id",other).update()
        jdbc.sql("UPDATE enrollment.installations SET tenant_id=:other WHERE id=:id").param("other",other).param("id",ids[2]).update()
        seedPoints(emptyList())
        val all = mapper.readTree(installationList().andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(all["items"].toList().map { it["installation_id"].asString() }).containsExactlyInAnyOrder(ids[0].toString(),ids[1].toString())
        val scoped = mapper.readTree(installationList("?team_id=$team").andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(scoped["items"].size()).isEqualTo(1)
        assertThat(scoped["items"][0]["team_ids"][0].asString()).isEqualTo(team.toString())
        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=now() WHERE team_id=:team").param("team",team).update()
        assertThat(mapper.readTree(installationList("?team_id=$team").andReturn().response.contentAsString)["items"].size()).isZero()
    }
    @Test fun `설치 감사 저장에 실패하면 식별자를 반환하지 않는다`() {
        installations(1)
        seedPoints(emptyList())
        jdbc.sql("""CREATE FUNCTION dashboard.test_reject_install_audit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'audit unavailable'; END $$""").update()
        jdbc.sql("""CREATE TRIGGER reject_install_audit BEFORE INSERT ON dashboard.audit_log
            FOR EACH ROW WHEN (NEW.action='installations') EXECUTE FUNCTION dashboard.test_reject_install_audit()""").update()
        try {
            val result = installationList().andExpect(status().isServiceUnavailable).andReturn().response
            assertThat(result.getHeader("Retry-After")).isEqualTo("2")
            assertThat(result.contentAsString).doesNotContain("installation_id","member_email_masked")
        } finally {
            jdbc.sql("DROP TRIGGER reject_install_audit ON dashboard.audit_log").update()
            jdbc.sql("DROP FUNCTION dashboard.test_reject_install_audit()").update()
        }
    }

    private fun sessionEvent(id: UUID, sequence: Int, signal: String = "log", session: String = "session-test",
        at: String = "2026-09-01T12:00:00Z", product: String = "claude_code"): String {
        val row = mapper.readTree(point(id,1.0,at=at)) as tools.jackson.databind.node.ObjectNode
        row.put("signal",signal).put("product",product)
        row.put("raw_json",mapper.writeValueAsString(mapOf("type" to "llm_call","sequence" to sequence,"call_id" to "call-test",
            "span_id" to "span-test","parent_id" to "parent-test","turn_id" to "turn-test",
            "envelope" to mapOf("session_id" to session,"client" to mapOf("version" to "test-1"),
                "_ingest" to mapOf("call_id_inferred" to true),"identity" to mapOf("vendor_email" to "hidden@vendor.test")),
            "payload" to if (signal=="metric") null else mapOf("request_id" to "request-test","cost_usd" to 2,"tokens" to mapOf("input" to 10)),
            "point" to mapOf("name" to "claude_code.cost.usage","value" to 1))))
        return mapper.writeValueAsString(row)
    }
    private fun sessionQuery(id: String = "session-test", params: String = "", reason: String? = "세션 오류 원인 정기 감사 점검") = mvc.perform(
        get("/v1/sessions/{id}/events",id).queryParam("from","2026-09-01T00:00:00Z").queryParam("to","2026-09-02T00:00:00Z")
            .apply { if (params.isNotEmpty()) for (pair in params.split('&')) queryParam(pair.substringBefore('='),pair.substringAfter('=')) }
            .header("Authorization","Bearer ${token()}").apply {
                if (reason!=null) header("X-Audit-Reason",java.net.URLEncoder.encode(reason,java.nio.charset.StandardCharsets.UTF_8))
            })
    @Test fun `세션 조회는 신호를 결합하고 동시각 순서와 페이지 메타를 보존한다`() {
        val id = installations(1).single()
        val rows = listOf(sessionEvent(id,3,"metric"),sessionEvent(id,1),sessionEvent(id,2,"span"))
        seedPoints(rows+rows[0])
        val first = mapper.readTree(sessionQuery(params="limit=2").andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(first["items"].toList().map { it["sequence"].asInt() }).containsExactly(1,2)
        assertThat(first["session"]["event_count"].asInt()).isEqualTo(3)
        assertThat(first["session"]["installation_id"].asString()).isEqualTo(id.toString())
        assertThat(first["items"][0]["call_id_inferred"].asBoolean()).isTrue()
        assertThat(first.toString()).doesNotContain("hidden@vendor.test","raw_json")
        val second = mapper.readTree(sessionQuery(params="limit=2&cursor="+first["next_cursor"].asString()).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(second["session"]).isEqualTo(first["session"])
        assertThat(second["items"][0]["type"].asString()).isEqualTo("claude_code.cost.usage")
        assertThat(second["items"][0]["payload"]["value"].asInt()).isEqualTo(1)
        assertThat(second["next_cursor"].isNull).isTrue()
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='session_events'").query(Long::class.java).single()).isEqualTo(2)
        sessionQuery(id="other",params="cursor="+first["next_cursor"].asString()).andExpect(status().isBadRequest)
    }
    @Test fun `요청 도구 설치 검색은 세션 전체로 확장하되 중복 세션을 합치지 않는다`() {
        val ids = installations(2)
        seedPoints(listOf(sessionEvent(ids[0],1),sessionEvent(ids[0],2,"metric")))
        for ((kind,key) in listOf("request_id" to "request-test","call_id" to "call-test","installation_id" to ids[0].toString())) {
            val result = mapper.readTree(sessionQuery(key,"lookup=$kind").andExpect(status().isOk).andReturn().response.contentAsString)
            assertThat(result["items"].size()).isEqualTo(2)
            assertThat(result["session"]["session_id"].asString()).isEqualTo("session-test")
        }
        seedPoints(listOf(sessionEvent(ids[0],1),sessionEvent(ids[1],1)))
        sessionQuery().andExpect(status().`is`(422))
        seedPoints(listOf(sessionEvent(ids[0],1),sessionEvent(ids[0],2,session="other")))
        sessionQuery(ids[0].toString(),"lookup=installation_id").andExpect(status().`is`(422))
    }
    @Test fun `세션 조회는 기간 조직 owner 감사 경계를 지킨다`() {
        val id = installations(1).single()
        val foreign = mapper.readTree(sessionEvent(id,1)) as tools.jackson.databind.node.ObjectNode
        foreign.put("tenant_id",UUID.randomUUID().toString())
        seedPoints(listOf(mapper.writeValueAsString(foreign),sessionEvent(id,2,at="2026-08-31T23:59:59Z")))
        sessionQuery().andExpect(status().isNotFound)
        sessionQuery(reason=null).andExpect(status().isForbidden)
        sessionQuery(params="lookup=unknown").andExpect(status().isBadRequest)
        sessionQuery(params="limit=501").andExpect(status().isBadRequest)
        sessionQuery(params="cursor=bad").andExpect(status().isBadRequest)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        sessionQuery().andExpect(status().isForbidden)
    }

    @Test fun `세션 커서는 상대 기간을 고정하고 같은 순번을 event id로 나눈다`() {
        val id = installations(1).single()
        val at = clock.instant().minusSeconds(60).toString()
        val rows = List(3) { sessionEvent(id,1,at=at) }
        seedPoints(rows)
        val expected = rows.map { mapper.readTree(it)["event_id"].asString() }.sorted()
        fun request(cursor: String = "") = mvc.perform(get("/v1/sessions/session-test/events")
            .queryParam("limit","1").queryParam("cursor",cursor)
            .header("Authorization","Bearer ${token()}").header("X-Audit-Reason","session pagination audit"))
            .andExpect(status().isOk).andReturn().response.contentAsString.let(mapper::readTree)
        val first = request()
        val second = request(first["next_cursor"].asString())
        val third = request(second["next_cursor"].asString())
        assertThat(listOf(first,second,third).map { it["items"][0]["event_id"].asString() }).isEqualTo(expected)
        fun bounds(page: tools.jackson.databind.JsonNode) = mapper.readTree(Base64.getUrlDecoder().decode(page["next_cursor"].asString()))
            .toList().slice(1..2)
        assertThat(bounds(second)).isEqualTo(bounds(first))
        assertThat(third["next_cursor"].isNull).isTrue()
    }
    @Test fun `세션 감사 저장 실패는 이벤트 반환 전에 차단한다`() {
        val id = installations(1).single()
        seedPoints(listOf(sessionEvent(id,1)))
        jdbc.sql("""CREATE FUNCTION dashboard.test_reject_session_audit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'audit unavailable'; END $$""").update()
        jdbc.sql("""CREATE TRIGGER reject_session_audit BEFORE INSERT ON dashboard.audit_log
            FOR EACH ROW WHEN (NEW.action='session_events') EXECUTE FUNCTION dashboard.test_reject_session_audit()""").update()
        try {
            val result = sessionQuery().andExpect(status().isServiceUnavailable).andReturn().response
            assertThat(result.getHeader("Retry-After")).isEqualTo("2")
            assertThat(result.contentAsString).doesNotContain("event_id","payload")
        } finally {
            jdbc.sql("DROP TRIGGER reject_session_audit ON dashboard.audit_log").update()
            jdbc.sql("DROP FUNCTION dashboard.test_reject_session_audit()").update()
        }
    }

    @Test fun `시나리오 카탈로그는 명세의 46개 식별자와 지표 정의를 보존한다`() {
        val bearer = "Bearer ${token()}"
        val catalog = mapper.readTree(mvc.perform(get("/v1/scenarios").header("Authorization", bearer))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(catalog["categories"].size()).isEqualTo(8)
        val ids = catalog["items"].toList().map { it["scenario_id"].asString() }
        val expected = listOf(7, 3, 5, 8, 7, 5, 4, 7).flatMapIndexed { index, count ->
            (1..count).map { "S${index + 1}-$it" }
        }
        assertThat(ids).containsExactlyElementsOf(expected)
        val metricDefinitions = mapper.readTree(mvc.perform(get("/v1/meta/metrics").header("Authorization", bearer))
            .andReturn().response.contentAsString)["items"].associateBy { it["metric_id"].asString() }
        for (summary in catalog["items"]) {
            assertThat(summary.has("params_schema")).isFalse()
            val detail = mapper.readTree(mvc.perform(get("/v1/scenarios/${summary["scenario_id"].asString()}")
                .header("Authorization", bearer)).andExpect(status().isOk).andReturn().response.contentAsString)
            assertThat(detail["metric_ids"].toList().map { it.asString() }).containsExactlyElementsOf(detail["metrics"].toList().map { it["metric_id"].asString() })
            for (metric in detail["metrics"]) {
                val definition = metricDefinitions.getValue(metric["metric_id"].asString())
                for (key in listOf("indicator_id", "availability", "definition", "caveat"))
                    assertThat(metric[key]).isEqualTo(definition[key])
            }
            assertThat(detail["params_schema"]["required"]).isEqualTo(detail["required_params"])
            for (key in detail["required_params"]) assertThat(detail["params_schema"]["properties"].has(key.asString())).isTrue()
        }
        // 상세 응답 가공이 공유 카탈로그 객체를 변형하지 않는다.
        assertThat(mapper.readTree(mvc.perform(get("/v1/scenarios").header("Authorization", bearer))
            .andReturn().response.contentAsString)).isEqualTo(catalog)
    }

    @Test fun `시나리오 필터는 교집합 검색과 잘못된 입력을 구분한다`() {
        val bearer = "Bearer ${token()}"
        fun search(vararg params: Pair<String, String>) = mapper.readTree(mvc.perform(get("/v1/scenarios")
            .header("Authorization", bearer).also { request -> params.forEach { request.param(it.first, it.second) } })
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(search("category" to "cost")["items"].size()).isEqualTo(7)
        assertThat(search("availability" to "unavailable")["items"].size()).isEqualTo(4)
        assertThat(search("category" to "cost", "target_page" to "P1", "q" to "스파이크")["items"].single()["scenario_id"].asString()).isEqualTo("S1-3")
        assertThat(search("q" to "무한 루프")["items"].size()).isEqualTo(1)
        assertThat(search("q" to "없는검색어")["items"].size()).isZero()
        assertThat(search("q" to "없는검색어")["categories"].size()).isEqualTo(8)
        for ((key, value) in listOf("category" to "unknown", "availability" to "unknown", "target_page" to "P9", "q" to "a".repeat(501)))
            mvc.perform(get("/v1/scenarios").param(key, value).header("Authorization", bearer)).andExpect(status().isBadRequest)
        mvc.perform(get("/v1/scenarios/S9-1").header("Authorization", bearer)).andExpect(status().isNotFound)
    }

    @Test fun `시나리오 정의만 admin에게 공개하며 비인증 조회는 거부한다`() {
        mvc.perform(get("/v1/scenarios")).andExpect(status().isUnauthorized)
        mvc.perform(get("/v1/scenarios/S1-3")).andExpect(status().isUnauthorized)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id", member).update()
        val bearer = "Bearer ${token()}"
        val detail = mapper.readTree(mvc.perform(get("/v1/scenarios/S1-3").header("Authorization", bearer))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(detail["findings_rules"].size()).isEqualTo(3)
        assertThat(detail["params_schema"]["properties"]["moving_avg_days"]["minimum"].asInt()).isEqualTo(3)
        val unavailable = mapper.readTree(mvc.perform(get("/v1/scenarios/S5-1").header("Authorization", bearer))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(unavailable["availability"].asString()).isEqualTo("unavailable")
        assertThat(unavailable["metrics"].size()).isZero()
        assertThat(unavailable["unavailable_reason"].asString()).contains("원문")
        mvc.perform(get("/v1/scenarios").header("Authorization", bearer)).andExpect(status().isOk)
    }

    private fun prepareScenario(id: String = "S1-3", json: String = """{"params":{}}""", role: String = "owner", reason: String? = null) =
        scenarioInputs.prepare(id, mapper.readTree(json), com.team376.pulsemetry.security.user.UserIdentity(
            member, tenant, role, UUID.randomUUID(), null, "web"), "Asia/Seoul", clock.instant(), reason)

    @Test fun `실행 입력은 현재 팀 범위를 고정하고 빈 admin 범위를 전사로 확대하지 않는다`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:mine,:tenant,'내 팀'),(:other,:tenant,'다른 팀')")
            .param("mine", mine).param("other", other).param("tenant", tenant).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)")
            .param("team", mine).param("member", member).update()
        val prepared = prepareScenario(role = "admin")
        assertThat(prepared.teamIds).containsExactly(mine)
        assertThat(prepared.organizationScope).isFalse()
        assertThat(prepareScenario().organizationScope).isTrue()
        assertThatThrownBy { prepareScenario(json = """{"params":{"team_ids":["$other"]}}""", role = "admin") }
            .hasMessage("forbidden")
        assertThatThrownBy { prepareScenario("S1-1", """{"params":{"budget_by_team":{"$other":{"usd":10}}}}""", "admin") }
            .hasMessage("forbidden")
        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=now() WHERE member_id=:id").param("id",member).update()
        assertThat(prepareScenario(role = "admin").teamIds).isEmpty()
        assertThat(prepareScenario(role = "admin").organizationScope).isFalse()
        // 팀 필터가 없는 시나리오도 같은 범위를 전달한다.
        assertThat(prepareScenario("S3-4", role = "admin").teamIds).isEmpty()
    }

    @Test fun `P3와 거부 지표 실행 준비는 owner 감사 기록을 요구한다`() {
        assertThatThrownBy { prepareScenario("S1-7", role = "admin", reason = "audit reason for scenario") }.hasMessage("forbidden")
        assertThatThrownBy { prepareScenario("S1-7") }.hasMessage("audit_reason_required")
        assertThatThrownBy { prepareScenario("S6-1", role = "admin") }.hasMessage("forbidden")
        prepareScenario("S1-7", reason = "audit reason for scenario")
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S1-7'")
            .query(Long::class.java).single()).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.scenario_runs").query(Long::class.java).single()).isZero()
    }

    @Test fun `불가 시나리오와 다른 tenant 팀은 실행 준비에서 거부한다`() {
        assertThatThrownBy { prepareScenario("S5-1") }.hasMessage("scenario_unavailable")
        assertThatThrownBy { prepareScenario("S9-1") }.hasMessage("not_found")
        val otherTenant = UUID.randomUUID()
        val team = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'다른 조직')").param("id",otherTenant).update()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'다른 조직 팀')")
            .param("id",team).param("tenant",otherTenant).update()
        assertThatThrownBy { prepareScenario(json = """{"params":{"team_ids":["$team"]}}""") }.hasMessage("forbidden")
    }

    private fun startRun(bearer: String = token(), id: String = "S1-3", params: String = """{"from":"2026-09-01","to":"2026-09-02","moving_avg_days":3}""") =
        mvc.perform(post("/v1/scenarios/$id/runs").header("Authorization", "Bearer $bearer")
            .contentType("application/json").content("""{"params":$params}"""))
    private fun readRun(id: String, bearer: String) = mapper.readTree(mvc.perform(get("/v1/scenario-runs/$id")
        .header("Authorization", "Bearer $bearer")).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun completedReportRun(bearer: String): String {
        val id = mapper.readTree(startRun(bearer,params="""{"from":"now-1d","to":"now"}""").andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        val row = requireNotNull(runStore.claim())
        assertThat(row.id.toString()).isEqualTo(id)
        assertThat(runStore.finish(row,"""{"frames":{},"findings":[]}""",null)).isTrue()
        return id
    }
    private fun saveReport(id: String, bearer: String, body: String = """{"name":"비용 보고서"}""") = mvc.perform(
        post("/v1/scenario-runs/$id/save").header("Authorization","Bearer $bearer").contentType("application/json").content(body))
    private fun savedList(bearer: String, cursor: String? = null) = mvc.perform(get("/v1/saved-reports")
        .header("Authorization","Bearer $bearer").param("limit","1").apply { cursor?.let { param("cursor",it) } })

    @Test fun `저장 리포트는 고정 상대 모드와 페이지 및 원본 참조 삭제 조건을 보존한다`() {
        val bearer = token()
        val run = completedReportRun(bearer)
        val fixed = mapper.readTree(saveReport(run,bearer).andExpect(status().isCreated).andReturn().response.contentAsString)
        val relative = mapper.readTree(saveReport(run,bearer,"""{"name":"상대 보고서","note":"첫 줄\n둘째 줄","time_mode":"relative"}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        assertThat(fixed["time_mode"].asString()).isEqualTo("fixed")
        assertThat(relative["time_mode"].asString()).isEqualTo("relative")
        assertThat(fixed["share_path"].asString()).isEqualTo("/runs/$run")
        val first = mapper.readTree(savedList(bearer).andExpect(status().isOk).andReturn().response.contentAsString)
        val second = mapper.readTree(savedList(bearer,first["next_cursor"].asString()).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(listOf(first["items"][0]["saved_id"].asString(),second["items"][0]["saved_id"].asString()))
            .containsExactly(relative["saved_id"].asString(),fixed["saved_id"].asString())
        assertThat(second["next_cursor"].isNull).isTrue()
        val listed = mapper.readTree(runList(bearer).andReturn().response.contentAsString)["items"][0]
        assertThat(listed["saved_id"].asString()).isEqualTo(relative["saved_id"].asString())
        mvc.perform(delete("/v1/scenario-runs/$run").header("Authorization","Bearer $bearer")).andExpect(status().isConflict)
        for (report in listOf(fixed,relative)) mvc.perform(delete("/v1/saved-reports/${report["saved_id"].asString()}")
            .header("Authorization","Bearer $bearer")).andExpect(status().isNoContent)
        assertThat(readRun(run,bearer)["status"].asString()).isEqualTo("succeeded")
        mvc.perform(delete("/v1/scenario-runs/$run").header("Authorization","Bearer $bearer")).andExpect(status().isNoContent)
        mvc.perform(get("/v1/scenario-runs/$run").header("Authorization","Bearer $bearer")).andExpect(status().isNotFound)
    }

    @Test fun `저장 입력과 실행 상태 오류를 400 409로 구분한다`() {
        val bearer = token()
        val run = mapper.readTree(startRun(bearer).andReturn().response.contentAsString)["run_id"].asString()
        saveReport(run,bearer).andExpect(status().isConflict)
        mvc.perform(delete("/v1/scenario-runs/$run").header("Authorization","Bearer $bearer")).andExpect(status().isConflict)
        for (body in listOf("{}","""{"name":" "}""","""{"name":2}""","""{"name":"ok","time_mode":"bad"}""",
            """{"name":"ok","extra":true}""",mapper.writeValueAsString(mapOf("name" to "x".repeat(101))),
            mapper.writeValueAsString(mapOf("name" to "ok","note" to "x".repeat(2001)))))
            saveReport(run,bearer,body).andExpect(status().isBadRequest)
        savedList(bearer,"bad-cursor").andExpect(status().isBadRequest)
        mvc.perform(get("/v1/saved-reports").header("Authorization","Bearer $bearer").param("limit","bad")).andExpect(status().isBadRequest)
        mvc.perform(get("/v1/saved-reports")).andExpect(status().isUnauthorized)
        saveReport(UUID.randomUUID().toString(),bearer).andExpect(status().isNotFound)
    }

    @Test fun `저장 리포트 목록과 삭제는 현재 실행 권한을 재검증한다`() {
        val team = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'실행 팀')").param("id",team).param("tenant",tenant).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)").param("team",team).param("member",member).update()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        val bearer = token()
        val run = completedReportRun(bearer)
        val saved = mapper.readTree(saveReport(run,bearer).andExpect(status().isCreated).andReturn().response.contentAsString)["saved_id"].asString()
        assertThat(mapper.readTree(savedList(bearer).andReturn().response.contentAsString)["items"].size()).isEqualTo(1)
        val other = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES (:id,:tenant,'report-owner@example.com','owner')")
            .param("id",other).param("tenant",tenant).update()
        val ownerSaved = UUID.randomUUID()
        jdbc.sql("""INSERT INTO dashboard.saved_reports(id,tenant_id,run_id,name,time_mode,created_by_member_id)
            VALUES (:id,:tenant,:run,'owner 저장','fixed',:creator)""")
            .param("id",ownerSaved).param("tenant",tenant).param("run",UUID.fromString(run)).param("creator",other).update()
        mvc.perform(delete("/v1/saved-reports/$ownerSaved").header("Authorization","Bearer $bearer")).andExpect(status().isForbidden)

        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=now() WHERE member_id=:id").param("id",member).update()
        assertThat(mapper.readTree(savedList(bearer).andReturn().response.contentAsString)["items"].size()).isZero()
        saveReport(run,bearer).andExpect(status().isNotFound)
        mvc.perform(delete("/v1/saved-reports/$saved").header("Authorization","Bearer $bearer")).andExpect(status().isNotFound)
        mvc.perform(delete("/v1/scenario-runs/$run").header("Authorization","Bearer $bearer")).andExpect(status().isNotFound)
    }

    @Test fun `동시 저장과 실행 삭제는 참조를 원자적으로 보존한다`() {
        val bearer = token()
        val run = completedReportRun(bearer)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        try {
            val save = pool.submit<Int> { barrier.await(); saveReport(run,bearer).andReturn().response.status }
            val remove = pool.submit<Int> { barrier.await(); mvc.perform(delete("/v1/scenario-runs/$run").header("Authorization","Bearer $bearer")).andReturn().response.status }
            val pair = save.get(10,java.util.concurrent.TimeUnit.SECONDS) to remove.get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertThat(pair).isIn(201 to 409,404 to 204)
        } finally { pool.shutdownNow() }
    }

    private fun runList(bearer: String, cursor: String? = null, limit: Int = 2, state: String? = null) =
        mvc.perform(get("/v1/scenario-runs").header("Authorization","Bearer $bearer").param("limit",limit.toString())
            .apply { cursor?.let { param("cursor",it) }; state?.let { param("status",it) } })

    @Test fun `실행 목록은 같은 시각의 ID 순서로 누락 중복 없이 요약을 페이지 처리한다`() {
        val bearer = token()
        val ids = (1..5).map {
            val id = mapper.readTree(startRun(bearer).andReturn().response.contentAsString)["run_id"].asString()
            mvc.perform(post("/v1/scenario-runs/$id/cancel").header("Authorization","Bearer $bearer")).andExpect(status().isOk)
            id
        }
        jdbc.sql("UPDATE dashboard.scenario_runs SET created_at='2026-09-01T00:00:00Z'").update()
        val found = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = mapper.readTree(runList(bearer,cursor).andExpect(status().isOk).andReturn().response.contentAsString)
            page["items"].forEach {
                found += it["run_id"].asString()
                assertThat(it.has("result")).isFalse()
                assertThat(it.has("params")).isFalse()
                assertThat(it["findings_count"]["info"].asInt()).isZero()
                assertThat(it["created_by"]["member_id"].asString()).isEqualTo(member.toString())
            }
            cursor = page["next_cursor"].takeUnless { it.isNull }?.asString()
        } while (cursor!=null)
        assertThat(found).containsExactlyElementsOf(ids.sortedDescending())
        val empty = mapper.readTree(runList(bearer,state="succeeded").andReturn().response.contentAsString)
        assertThat(empty["items"].size()).isZero()
        val filtered = mapper.readTree(mvc.perform(get("/v1/scenario-runs").header("Authorization","Bearer $bearer")
            .param("scenario_id","S1-3").param("created_by",member.toString()).param("status","cancelled"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(filtered["items"].size()).isEqualTo(5)
        val foreign = com.team376.pulsemetry.security.user.UserIdentity(member,UUID.randomUUID(),"owner",UUID.randomUUID(),null,"web")
        assertThat(scenarioRuns.list(foreign,null,null,null,50,null)["items"] as List<*>).isEmpty()
    }

    @Test fun `실행 목록 커서는 필터와 사용자 범위에 묶이고 잘못된 요청은 거부한다`() {
        val bearer = token()
        repeat(2) { startRun(bearer).andExpect(status().isAccepted) }
        val cursor = mapper.readTree(runList(bearer,limit=1).andReturn().response.contentAsString)["next_cursor"].asString()
        runList(bearer,cursor,state="queued").andExpect(status().isBadRequest)
        runList(bearer,"not-a-cursor").andExpect(status().isBadRequest)
        runList(bearer,limit=0).andExpect(status().isBadRequest)
        runList(bearer,limit=501).andExpect(status().isBadRequest)
        runList(bearer,state="unknown").andExpect(status().isBadRequest)
        mvc.perform(get("/v1/scenario-runs").header("Authorization","Bearer $bearer").param("limit","abc")).andExpect(status().isBadRequest)
        mvc.perform(get("/v1/scenario-runs")).andExpect(status().isUnauthorized)
        mvc.perform(get("/v1/scenario-runs").header("Authorization","Bearer $bearer").param("created_by","invalid")).andExpect(status().isBadRequest)
    }

    @Test fun `admin 실행 목록은 본인 실행과 현재 팀 권한을 페이지 절단 전에 적용한다`() {
        val ownerToken = token()
        startRun(ownerToken).andExpect(status().isAccepted)
        val team = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'실행 팀')").param("id",team).param("tenant",tenant).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)").param("team",team).param("member",member).update()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        val bearer = token()
        val own = mapper.readTree(startRun(bearer).andReturn().response.contentAsString)["run_id"].asString()
        val page = mapper.readTree(runList(bearer,limit=1).andReturn().response.contentAsString)
        assertThat(page["items"].size()).isEqualTo(1)
        assertThat(page["items"][0]["run_id"].asString()).isEqualTo(own)
        assertThat(page["next_cursor"].isNull).isTrue()
        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=now() WHERE member_id=:member").param("member",member).update()
        val denied = mapper.readTree(runList(bearer).andReturn().response.contentAsString)
        assertThat(denied["items"].size()).isZero()
    }

    private fun comparisonRun(bearer: String, scenario: String, weeks: Int = 1,
        pivot: String = "2026-09-01", audit: String? = "period comparison review") =
        mvc.perform(post("/v1/scenarios/$scenario/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("tz" to "Asia/Seoul",
                "params" to mapOf("pivot_date" to pivot,"window_weeks" to weeks)))))

    @Test fun `교육 전후 비교는 기준일을 중복하지 않고 기간 전체 프롬프트 분포를 비교한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(promptEvent(it,at="2026-08-24T15:00:00Z"),
            promptEvent(it,at="2026-08-31T15:00:00Z"),promptEvent(it,at="2026-09-01T12:00:00Z"),
            promptEvent(it,at="2026-09-07T15:00:00Z")) })
        val bearer = token()
        val id = mapper.readTree(comparisonRun(bearer,"S4-4",audit=null).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        val filters = run["result"]["applied_filters"]
        assertThat(filters["compare_from"].asString()).isEqualTo("2026-08-24T15:00:00Z")
        assertThat(filters["compare_to"]).isEqualTo(filters["from"])
        assertThat(filters["observation_complete"].asBoolean()).isTrue()
        val finding = run["result"]["findings"].single()["evidence"]
        assertThat(finding["metric_id"].asString()).isEqualTo("prompts_per_session")
        assertThat(finding["before"].asDouble()).isEqualTo(1.0)
        assertThat(finding["after"].asDouble()).isEqualTo(2.0)
        assertThat(finding["delta"].asDouble()).isEqualTo(1.0)
    }

    @Test fun `정책 전후 비교는 owner 감사와 거절 수 및 대기 중앙값을 연결한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(decisionEvent(it,"config","reject",at="2026-08-25T12:00:00Z"),
            decisionEvent(it,"config","reject"),decisionEvent(it,"hook","reject"),
            gateEvent(it,100,at="2026-08-25T12:00:00Z"),gateEvent(it,300)) })
        val bearer = token()
        comparisonRun(bearer,"S8-6",audit=null).andExpect(status().isForbidden)
        comparisonRun(bearer,"S8-6",pivot="2026-02-30").andExpect(status().isBadRequest)
        val id = mapper.readTree(comparisonRun(bearer,"S8-6").andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        val findings = run["result"]["findings"].toList().associateBy { it["evidence"]["metric_id"].asString() }
        assertThat(findings.keys).containsExactlyInAnyOrder("tool_rejections","gate_wait_ms")
        assertThat(findings.getValue("tool_rejections")["evidence"]["before"].asDouble()).isEqualTo(5.0)
        assertThat(findings.getValue("tool_rejections")["evidence"]["after"].asDouble()).isEqualTo(10.0)
        assertThat(findings.getValue("gate_wait_ms")["evidence"]["delta"].asDouble()).isEqualTo(200.0)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        comparisonRun(token(),"S8-6").andExpect(status().isForbidden)
    }

    @Test fun `정책 비교는 영 기준값과 감소를 백분율 추정 없이 처리한다`() {
        val ids = installations(5)
        val bearer = token()
        for ((prior,current,expected) in listOf(Triple("accept","reject",5.0),Triple("reject","accept",-5.0),Triple("accept","accept",0.0))) {
            seedPoints(ids.flatMap { listOf(decisionEvent(it,"config",prior,at="2026-08-25T12:00:00Z"),decisionEvent(it,"config",current)) })
            val id = mapper.readTree(comparisonRun(bearer,"S8-6").andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            if (expected==0.0) assertThat(run["result"]["findings"].size()).isZero()
            else assertThat(run["result"]["findings"].single()["evidence"]["delta"].asDouble()).isEqualTo(expected)
        }
    }

    @Test fun `전후 비교는 한쪽 소집단 미관측과 미완료 기간을 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for ((rows,pivot) in listOf(
            (ids.take(4).map { promptEvent(it,at="2026-08-25T12:00:00Z") } + ids.map { promptEvent(it) }) to "2026-09-01",
            ids.map { promptEvent(it) } to "2026-09-01",
            emptyList<String>() to "2026-09-01",
            (ids.map { promptEvent(it) } + ids.map { promptEvent(it,at="2026-09-09T12:00:00Z") }) to "2026-09-08")) {
            seedPoints(rows)
            val id = mapper.readTree(comparisonRun(bearer,"S4-4",pivot=pivot).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
            if (rows.size==9) {
                val frame = run["result"]["frames"]["prompts_per_session"]["frames"].single()
                assertThat(frame["data"]["values"].toList().flatMap { it.toList() }.all { it.isNull }).isTrue()
            }
        }
        val id = mapper.readTree(comparisonRun(bearer,"S8-6",weeks=52).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        assertThat(readRun(id,bearer)["status"].asString()).isEqualTo("succeeded")
    }

    private fun policyPurposeRun(bearer: String, audit: String? = "policy purpose review") =
        mvc.perform(post("/v1/scenarios/S5-6/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content("""{"params":{"from":"2026-08-25","to":"2026-09-03","pivot_date":"2026-09-01"}}"""))

    @Test fun `정책 용도 비교는 config hook만 집계하고 owner 감사와 기간을 보존한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(decisionEvent(it,"config","reject",at="2026-08-25T12:00:00Z"),
            decisionEvent(it,"config","reject",at="2026-08-31T15:00:00Z"),decisionEvent(it,"hook","reject"),
            decisionEvent(it,"user","reject"),decisionEvent(it,null,"reject")) })
        val bearer = token()
        policyPurposeRun(bearer,null).andExpect(status().isForbidden)
        val id = mapper.readTree(policyPurposeRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(1)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(1)
        assertThat(run["params"]["from"].asString()).isEqualTo("2026-08-25")
        assertThat(run["resolved_from"].asString()).isEqualTo("2026-08-31T15:00:00Z")
        val finding = run["result"]["findings"].single()
        assertThat(finding["widget_id"].asString()).isEqualTo("W3.3")
        assertThat(finding["evidence"]["before"].asDouble()).isEqualTo(5.0)
        assertThat(finding["evidence"]["after"].asDouble()).isEqualTo(10.0)
        assertThat(finding["evidence"]["limitation"].asString()).contains("기간 길이")
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        policyPurposeRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `거절 결정 주체 파라미터는 빈 선택 호환성과 타입을 검증한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(decisionEvent(it,"config","reject"),decisionEvent(it,"hook","reject"),decisionEvent(it,"user","reject")) })
        for ((sources,expected) in listOf(emptyList<String>() to 15.0,listOf("config","hook") to 10.0,listOf("user") to 5.0)) {
            val result = mapper.readTree(queryResult(queryBody(mapOf("metric_id" to "tool_rejections","frame_type" to "table",
                "params" to mapOf("decided_by" to sources)))).andExpect(status().isOk).andReturn().response.contentAsString)
            assertThat(result["results"]["A"]["frames"].single()["data"]["values"][0][0].asDouble()).isEqualTo(expected)
        }
        for (invalid in listOf("config",listOf("config","config"),listOf("invalid"),listOf(1)))
            queryResult(queryBody(mapOf("metric_id" to "tool_rejections","params" to mapOf("decided_by" to invalid))))
                .andExpect(status().isBadRequest)
    }

    @Test fun `정책 용도 비교는 소집단과 사용자 결정만 있는 기간을 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for (rows in listOf(ids.take(4).map { decisionEvent(it,"config","reject",at="2026-08-25T12:00:00Z") } +
            ids.map { decisionEvent(it,"hook","reject") },ids.map { decisionEvent(it,"user","reject") },emptyList<String>())) {
            seedPoints(rows)
            val id = mapper.readTree(policyPurposeRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun modelComparisonRun(bearer: String, a: String = "model-a", b: String = "model-b", audit: String? = "model comparison review") =
        mvc.perform(post("/v1/scenarios/S8-3/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("params" to mapOf(
                "from" to "2026-09-01","to" to "2026-09-02","model_a" to a,"model_b" to b)))))

    private fun comparisonModelEvent(id: UUID, model: String, cost: Int, duration: Int, status: Int): String {
        val row = mapper.readTree(llmEvent(id,1,status,model)) as tools.jackson.databind.node.ObjectNode
        val raw = mapper.readTree(row["raw_json"].asString())
        (raw["payload"] as tools.jackson.databind.node.ObjectNode).put("cost_usd",cost).put("duration_ms",duration)
            .put("error_type",if(status>=400) "server_error" else "")
        row.put("raw_json",raw.toString())
        return row.toString()
    }

    @Test fun `모델 비교는 동일 기간에 두 모델을 독립 집계하고 모델 라벨을 보존한다`() {
        val ids = installations(5)
        val a = "model-a'quoted"
        seedPoints(ids.flatMap { listOf(comparisonModelEvent(it,a,1,100,200),
            comparisonModelEvent(it,"model-b",2,200,200),comparisonModelEvent(it,"model-b",0,0,500),comparisonModelEvent(it,"excluded",99,9999,500),promptEvent(it)) })
        val bearer = token()
        modelComparisonRun(bearer,audit=null).andExpect(status().isForbidden)
        modelComparisonRun(bearer,"same","same").andExpect(status().isBadRequest)
        modelComparisonRun(bearer,"x".repeat(201)).andExpect(status().isBadRequest)
        val id = mapper.readTree(modelComparisonRun(bearer,a).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        assertThat(run["result"]["applied_filters"]["filters"]["models"].toList().map { it.asString() }).containsExactly(a,"model-b")
        val frames = run["result"]["frames"]["cost"]["frames"].toList()
        assertThat(frames.map { it["schema"]["fields"][0]["labels"]["model"].asString() }).containsExactly(a,"model-b")
        assertThat(frames.map { it["data"]["values"][0][0].asDouble() }).containsExactly(5.0,10.0)
        val findings = run["result"]["findings"].toList().associateBy { it["evidence"]["metric_id"].asString() }
        assertThat(findings.keys).containsExactlyInAnyOrder("cost","llm_duration_ms","api_error_rate")
        assertThat(findings.getValue("cost")["evidence"]["delta_b_minus_a"].asDouble()).isEqualTo(5.0)
        assertThat(findings.getValue("llm_duration_ms")["evidence"]["delta_b_minus_a"].asDouble()).isEqualTo(100.0)
        assertThat(findings.getValue("api_error_rate")["evidence"]["delta_b_minus_a"].asDouble()).isEqualTo(0.5)
        assertThat(run["result"]["frames"]["prompts_per_session"]["frames"].size()).isZero()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        modelComparisonRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `모델 비교는 한쪽 소집단 미관측 동일값을 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for (rows in listOf(ids.map { comparisonModelEvent(it,"model-a",1,100,200) } +
            ids.take(4).map { comparisonModelEvent(it,"model-b",2,200,500) },
            ids.map { comparisonModelEvent(it,"model-a",1,100,200) },
            ids.flatMap { listOf(comparisonModelEvent(it,"model-a",1,100,200),comparisonModelEvent(it,"model-b",1,100,200)) },
            emptyList<String>())) {
            seedPoints(rows)
            val id = mapper.readTree(modelComparisonRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
            if(rows.size==9) {
                val masked = run["result"]["frames"]["cost"]["frames"].toList().single { it["schema"]["fields"][0]["labels"]["model"].asString()=="model-b" }
                assertThat(masked["data"]["values"][0][0].isNull).isTrue()
            }
        }
    }

    private fun shadowRun(bearer: String, reason: String? = "vendor account review") =
        mvc.perform(post("/v1/scenarios/S5-4/runs").header("Authorization","Bearer $bearer")
            .also { if(reason!=null) it.header("X-Audit-Reason",java.net.URLEncoder.encode(reason,java.nio.charset.StandardCharsets.UTF_8)) }
            .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02"}}"""))

    @Test fun `섀도우 관측은 감사 사유를 보존하고 주소 없는 일별 불일치를 반환한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMapIndexed { index,id ->
            val email = "registered-$id@example.test"
            jdbc.sql("UPDATE enrollment.members SET email=:email WHERE id=(SELECT member_id FROM enrollment.installations WHERE id=:id)")
                .param("email",email).param("id",id).update()
            listOf(vendorEvent(id,"old@vendor.test"),vendorEvent(id,if(index<2) "other@vendor.test" else email.uppercase(),2))
        })
        val bearer = token()
        shadowRun(bearer,null).andExpect(status().isForbidden)
        val reason = "계정 확인 100% + %2F 사유"
        val id = mapper.readTree(shadowRun(bearer,reason).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        assertThat(run["result"]["frames"].propertyNames()).containsExactlyInAnyOrder("rate_limit_events","vendor_account_mismatch","mcp_connections","active_users")
        assertThat(run["result"]["findings"].single()["evidence"]["count"].asDouble()).isEqualTo(2.0)
        assertThat(run.toString()).doesNotContain("@vendor.test","@example.test",reason,"audit_reason")
        assertThat(jdbc.sql("SELECT reason FROM dashboard.audit_log WHERE action='query' AND target='vendor_account_mismatch'")
            .query(String::class.java).single()).isEqualTo(reason)
        assertThat(jdbc.sql("SELECT reason FROM dashboard.audit_log WHERE action='scenario_run' AND target='S5-4'")
            .query(String::class.java).single()).isEqualTo(reason)
    }

    @Test fun `섀도우 관측은 작은 집단 일치와 미관측을 불일치로 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for (rows in listOf(ids.take(4).map { vendorEvent(it,"different@vendor.test") },
            ids.map { id ->
                val email = jdbc.sql("SELECT m.email FROM enrollment.members m JOIN enrollment.installations i ON i.member_id=m.id WHERE i.id=:id")
                    .param("id",id).query(String::class.java).single()
                vendorEvent(id,email.uppercase())
            },emptyList<String>())) {
            seedPoints(rows)
            val id = mapper.readTree(shadowRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `섀도우 워커는 권한 변경과 저장 사유 누락 시 조회를 거부한다`() {
        val bearer = token()
        seedPoints(installations(5).map { vendorEvent(it,"different@vendor.test") })
        val first = mapper.readTree(shadowRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        jdbc.sql("UPDATE dashboard.scenario_runs SET execution=execution-'audit_reason' WHERE id=:id").param("id",UUID.fromString(first)).update()
        scenarioRuns.runOne()
        assertThat(readRun(first,bearer)["status"].asString()).isEqualTo("failed")
        assertThat(readRun(first,bearer)["error"]["error"].asString()).isEqualTo("audit_reason_required")
        val second = mapper.readTree(shadowRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        scenarioRuns.runOne()
        shadowRun(token()).andExpect(status().isForbidden)
        assertThat(jdbc.sql("SELECT status::text FROM dashboard.scenario_runs WHERE id=:id").param("id",UUID.fromString(second)).query(String::class.java).single()).isEqualTo("failed")
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='query'").query(Long::class.java).single()).isZero()
    }

    private fun acceptanceRun(bearer: String, language: String? = "kotlin") =
        mvc.perform(post("/v1/scenarios/S4-3/runs").header("Authorization","Bearer $bearer")
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("params" to
                (mapOf("from" to "2026-09-01","to" to "2026-09-02") + if(language!=null) mapOf("language" to language) else emptyMap())))))

    private fun languageEdit(id: UUID, language: String, value: Double, decision: String): String {
        val row = mapper.readTree(editPoint(id,value,decision,if(decision=="accept") "user_temporary" else "user_reject")) as tools.jackson.databind.node.ObjectNode
        val raw = mapper.readTree(row["raw_json"].asString())
        (raw["point"]["attrs"] as tools.jackson.databind.node.ObjectNode).put("language",language)
        row.put("raw_json",raw.toString())
        return row.toString()
    }

    @Test fun `코드 수용 시나리오는 선택 언어의 비율과 전체 기간 보조 지표를 구분한다`() {
        val ids = installations(5)
        val language = "kotlin'quoted"
        seedPoints(ids.flatMap { listOf(languageEdit(it,language,1.0,"accept"),languageEdit(it,language,3.0,"reject"),
            languageEdit(it,"javascript",9.0,"accept"),point(it,10.0).replace("claude_code.session.count","claude_code.lines_of_code.count")) })
        val bearer = token()
        acceptanceRun(bearer,"").andExpect(status().isBadRequest)
        acceptanceRun(bearer,"x".repeat(257)).andExpect(status().isBadRequest)
        for ((selected,expected) in listOf(language to 0.25,null to (10.0/13.0))) {
            val id = mapper.readTree(acceptanceRun(bearer,selected).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
            val finding = run["result"]["findings"].single()
            assertThat(finding["evidence"]["ratio"].asDouble()).isCloseTo(expected,org.assertj.core.api.Assertions.within(0.000001))
            if(selected!=null) assertThat(finding["evidence"]["language"].asString()).isEqualTo(language)
            assertThat(run["result"]["frames"]["lines_of_code"]["frames"].single()["data"]["values"][1][0].asDouble()).isEqualTo(50.0)
        }
        for (invalid in listOf(42,"", " ","x".repeat(257))) queryResult(queryBody(mapOf("metric_id" to "edit_acceptance_rate",
            "params" to mapOf("language" to invalid)))).andExpect(status().isBadRequest)
    }

    @Test fun `코드 수용은 언어 대소문자 소집단 거절만 있는 경우와 미관측을 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for (rows in listOf(ids.map { languageEdit(it,"Kotlin",1.0,"accept") },
            ids.take(4).map { languageEdit(it,"kotlin",1.0,"accept") },
            ids.take(4).map { languageEdit(it,"kotlin",1.0,"accept") } + languageEdit(ids.last(),"javascript",1.0,"accept"),
            ids.map { languageEdit(it,"kotlin",1.0,"reject") },emptyList<String>())) {
            seedPoints(rows)
            val id = mapper.readTree(acceptanceRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun fixedComparisonRun(bearer: String, scenario: String, models: List<String> = listOf("claude-test"),
        pivot: String = "2026-08-10", audit: String? = "fixed period comparison") =
        mvc.perform(post("/v1/scenarios/$scenario/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("params" to
                (mapOf("pivot_date" to pivot) + if(scenario=="S6-3") mapOf("models" to models) else emptyMap())))))

    @Test fun `드리프트 비교는 선택 모델과 종료 사유별 전후 건수를 보존한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(stopEvent(it,"end_turn",at="2026-08-01T12:00:00Z"),
            stopEvent(it,"end_turn"),stopEvent(it,"end_turn",at="2026-09-02T12:00:00Z"),
            comparisonModelEvent(it,"excluded",99,9999,500)) })
        val bearer = token()
        fixedComparisonRun(bearer,"S6-3",audit=null).andExpect(status().isForbidden)
        fixedComparisonRun(bearer,"S6-3",models=listOf("x".repeat(201))).andExpect(status().isBadRequest)
        val id = mapper.readTree(fixedComparisonRun(bearer,"S6-3").andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        assertThat(run["result"]["applied_filters"]["window_weeks"].asInt()).isEqualTo(4)
        assertThat(run["result"]["applied_filters"]["filters"]["models"].toList().map { it.asString() }).containsExactly("claude-test")
        val finding = run["result"]["findings"].single()
        assertThat(finding["widget_id"].asString()).isEqualTo("W3.1")
        assertThat(finding["evidence"]["dimensions"]["stop_reason"].asString()).isEqualTo("end_turn")
        assertThat(finding["evidence"]["before"].asDouble()).isEqualTo(5.0)
        assertThat(finding["evidence"]["after"].asDouble()).isEqualTo(10.0)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        fixedComparisonRun(token(),"S6-3").andExpect(status().isForbidden)
    }

    @Test fun `챔피언 비교는 전후 프롬프트와 사용 집중도를 비교하고 개인을 반환하지 않는다`() {
        val ids = installations(5)
        seedPoints(ids.flatMapIndexed { i,id -> listOf(promptEvent(id,at="2026-08-01T12:00:00Z"),promptEvent(id),
            promptEvent(id,at="2026-09-02T12:00:00Z"),tokenEvent(id,10,0,0,0,at="2026-08-01T12:00:00Z"),
            tokenEvent(id,if(i==4) 100 else 10,0,0,0)) })
        val bearer = token()
        val id = mapper.readTree(fixedComparisonRun(bearer,"S8-7",audit=null).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        val findings = run["result"]["findings"].toList().associateBy { it["evidence"]["metric_id"].asString() }
        assertThat(findings.keys).containsExactlyInAnyOrder("prompts_per_session","usage_concentration")
        assertThat(findings.getValue("prompts_per_session")["evidence"]["before"].asDouble()).isEqualTo(1.0)
        assertThat(findings.getValue("prompts_per_session")["evidence"]["after"].asDouble()).isEqualTo(2.0)
        assertThat(findings.getValue("usage_concentration")["evidence"]["after"].asDouble()).isGreaterThan(0.7)
        ids.forEach { assertThat(run.toString()).doesNotContain(it.toString()) }
    }

    @Test fun `고정 4주 비교는 소집단 미관측과 미완료 기간을 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for ((rows,pivot) in listOf((ids.take(4).flatMap { listOf(promptEvent(it,at="2026-08-01T12:00:00Z"),
            stopEvent(it,"end_turn",at="2026-08-01T12:00:00Z")) } + ids.flatMap { listOf(promptEvent(it),stopEvent(it,"end_turn")) }) to "2026-08-10",
            emptyList<String>() to "2026-08-10",ids.flatMap { listOf(promptEvent(it),stopEvent(it,"end_turn")) } to "2026-09-01")) {
            seedPoints(rows)
            for (scenario in listOf("S6-3","S8-7")) {
                val id = mapper.readTree(fixedComparisonRun(bearer,scenario,pivot=pivot).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
                scenarioRuns.runOne()
                val run = readRun(id,bearer)
                assertThat(run["status"].asString()).isEqualTo("succeeded")
                assertThat(run["result"]["findings"].size()).isZero()
            }
        }
    }

    private fun probeRun(bearer: String, minutes: Int = 5, count: Int = 10, audit: String? = "repeated refusal review") =
        mvc.perform(post("/v1/scenarios/S5-5/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content("""{"params":{"from":"2026-09-01T12:01:00Z","to":"2026-09-01T12:06:00Z","probe_window_min":$minutes,"probe_count":$count}}"""))

    @Test fun `반복 거부 창은 조회 경계와 고정 창 및 최소 횟수를 적용한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { id -> listOf("12:00:59","12:01:00","12:04:59","12:05:00","12:06:00")
            .map { refusalEvent(id,null,at="2026-09-01T${it}Z") } +
            listOf(refusalEvent(id,null,reason="end_turn",at="2026-09-01T12:02:00Z"),
                refusalEvent(id,null,type="llm_call",at="2026-09-01T12:02:00Z")) })
        val bearer = token()
        probeRun(bearer,audit=null).andExpect(status().isForbidden)
        for ((minutes,count,expected) in listOf(Triple(5,10,1),Triple(5,11,0),Triple(1,5,3))) {
            val id = mapper.readTree(probeRun(bearer,minutes,count).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).withFailMessage(run.toString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
            val findings = run["result"]["findings"]
            assertThat(findings.size()).isEqualTo(expected)
            if(minutes==5 && count==10) {
                val evidence = findings.single()["evidence"]
                assertThat(evidence["refusals"].asLong()).isEqualTo(10)
                assertThat(evidence["window_start"].asString()).isEqualTo("2026-09-01T12:00:00Z")
                assertThat(evidence["observed_from"].asString()).isEqualTo("2026-09-01T12:01:00Z")
                assertThat(evidence["observed_to"].asString()).isEqualTo("2026-09-01T12:05:00Z")
                assertThat(findings.single()["severity"].asString()).isEqualTo("info")
            }
            ids.forEach { assertThat(run.toString()).doesNotContain(it.toString()) }
        }
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        probeRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `반복 거부 창은 구성원 소집단과 단일 구성원의 여러 설치를 숨긴다`() {
        val ids = installations(5)
        val bearer = token()
        for (mode in listOf("small","same_member")) {
            seedPoints((if(mode=="small") ids.take(4) else ids).flatMap { id ->
                listOf("12:01:00","12:02:00","12:03:00").map { refusalEvent(id,null,at="2026-09-01T${it}Z") } })
            if(mode=="same_member") jdbc.sql("UPDATE enrollment.installations SET member_id=(SELECT member_id FROM enrollment.installations WHERE id=:id) WHERE tenant_id=:tenant")
                .param("id",ids.first()).param("tenant",tenant).update()
            val id = mapper.readTree(probeRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).withFailMessage(run.toString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun inactivityRun(bearer: String, audit: String? = "inactive installation review") =
        mvc.perform(post("/v1/scenarios/S1-7/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content("""{"params":{"as_of":"2026-09-10T00:00:00Z","inactive_days":30}}"""))

    @Test fun `유휴 관측은 과거 기준 이후 이벤트를 제외하고 경계 이상 경과 설치를 집계한다`() {
        val ids = installations(5)
        jdbc.sql("UPDATE enrollment.installations SET created_at='2026-07-01T00:00:00Z' WHERE tenant_id=:tenant").param("tenant",tenant).update()
        seedPoints(ids.flatMap { listOf(promptEvent(it,at="2026-08-11T00:00:00Z"),promptEvent(it,at="2026-09-11T00:00:00Z")) })
        val bearer = token()
        inactivityRun(bearer,null).andExpect(status().isForbidden)
        val id = mapper.readTree(inactivityRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        assertThat(run["resolved_from"].asString()).isEqualTo("2026-08-11T00:00:00Z")
        assertThat(run["result"]["findings"].single()["evidence"]["installations"].asInt()).isEqualTo(5)
        ids.forEach { assertThat(run.toString()).doesNotContain(it.toString()) }
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        inactivityRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `유휴 관측은 신규 최근 활동 폐기 설치와 소집단을 제외한다`() {
        val ids = installations(5)
        val bearer = token()
        for (mode in listOf("recent","new","revoked","small")) {
            jdbc.sql("UPDATE enrollment.installations SET created_at='2026-07-01T00:00:00Z',status='active' WHERE tenant_id=:tenant").param("tenant",tenant).update()
            seedPoints(if(mode=="recent") ids.map { promptEvent(it,at="2026-09-09T23:59:59Z") } else emptyList())
            if(mode=="new") jdbc.sql("UPDATE enrollment.installations SET created_at='2026-09-09T00:00:00Z' WHERE tenant_id=:tenant").param("tenant",tenant).update()
            if(mode=="revoked") jdbc.sql("UPDATE enrollment.installations SET status='revoked' WHERE tenant_id=:tenant").param("tenant",tenant).update()
            if(mode=="small") jdbc.sql("UPDATE enrollment.installations SET status='revoked' WHERE id=:id").param("id",ids.last()).update()
            val id = mapper.readTree(inactivityRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `미관측 설치는 생성일을 사용하고 한 사람의 여러 설치로 소집단을 해제하지 않는다`() {
        val ids = installations(5)
        jdbc.sql("UPDATE enrollment.installations SET created_at='2026-07-01T00:00:00Z' WHERE tenant_id=:tenant").param("tenant",tenant).update()
        seedPoints(emptyList())
        val bearer = token()
        val first = mapper.readTree(inactivityRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        assertThat(readRun(first,bearer)["result"]["findings"].single()["evidence"]["installations"].asInt()).isEqualTo(5)
        jdbc.sql("UPDATE enrollment.installations SET member_id=(SELECT member_id FROM enrollment.installations WHERE id=:id) WHERE tenant_id=:tenant")
            .param("id",ids.first()).param("tenant",tenant).update()
        val second = mapper.readTree(inactivityRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        assertThat(readRun(second,bearer)["result"]["findings"].size()).isZero()
    }

    private fun sprintRun(bearer: String, dates: List<String>, zone: String = "Asia/Seoul") =
        mvc.perform(post("/v1/scenarios/S2-3/runs").header("Authorization","Bearer $bearer")
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("tz" to zone,"params" to
                mapOf("from" to "2026-09-01","to" to "2026-09-03","sprint_dates" to dates)))))

    @Test fun `스프린트 시나리오는 현지 날짜와 세션을 연결하고 시간대별 프롬프트를 제공한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(point(it,1.0,at="2026-09-01T16:00:00Z"),promptEvent(it,at="2026-09-01T16:00:00Z")) })
        val bearer = token()
        sprintRun(bearer,listOf("2026-02-30")).andExpect(status().isBadRequest)
        for ((zone,date) in listOf("Asia/Seoul" to "2026-09-02","UTC" to "2026-09-01")) {
            val id = mapper.readTree(sprintRun(bearer,listOf(date),zone).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
            val frames = run["result"]["frames"]
            assertThat(frames.propertyNames()).containsExactlyInAnyOrder("usage_heatmap","sessions","llm_duration_ms","rate_limit_events")
            val finding = run["result"]["findings"].single()
            assertThat(finding["evidence"]["sprint_date"].asString()).isEqualTo(date)
            assertThat(finding["evidence"]["tz"].asString()).isEqualTo(zone)
            assertThat(finding["evidence"]["sessions"].asDouble()).isEqualTo(5.0)
            assertThat(frames["usage_heatmap"]["frames"].size()).isPositive()
        }
    }

    @Test fun `스프린트 시나리오는 다른 날짜 소집단과 빈 선택을 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for ((rows,dates) in listOf(ids.map { point(it,1.0) } to listOf("2026-09-02"),
            ids.take(4).map { point(it,1.0) } to listOf("2026-09-01"),
            ids.map { point(it,1.0) } to emptyList(),emptyList<String>() to listOf("2026-09-01"))) {
            seedPoints(rows)
            val id = mapper.readTree(sprintRun(bearer,dates).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun qualityRun(bearer: String, models: List<String> = listOf("claude-test"), audit: String? = "quality feedback review") =
        mvc.perform(post("/v1/scenarios/S6-1/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("params" to
                mapOf("from" to "2026-09-01","to" to "2026-09-02","models" to models)))))

    @Test fun `품질 피드백 시나리오는 P2에서도 owner 감사와 모델 범위를 적용한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(refusalEvent(it,"policy"),promptEvent(it)) })
        val bearer = token()
        qualityRun(bearer,audit=null).andExpect(status().isForbidden)
        qualityRun(bearer,listOf("x".repeat(201))).andExpect(status().isBadRequest)
        val id = mapper.readTree(qualityRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        assertThat(run["result"]["target_page"].asString()).isEqualTo("P2")
        assertThat(run["result"]["applied_filters"]["filters"]["models"].toList().map { it.asString() }).containsExactly("claude-test")
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("refusals","edit_acceptance_rate","prompts_per_session")
        val finding = run["result"]["findings"].single()
        assertThat(finding["rule_id"].asString()).isEqualTo("observed_quality_refusals")
        assertThat(finding["evidence"]["count"].asDouble()).isEqualTo(5.0)
        assertThat(frames["prompts_per_session"]["frames"].toList().all { f -> f["data"]["values"].toList().drop(1).all { values -> values.toList().all { it.isNull } } }).isTrue()
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S6-1'")
            .query(Long::class.java).single()).isEqualTo(1)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        qualityRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `품질 피드백 시나리오는 소집단 다른 모델과 정상 종료를 거부로 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for ((rows,models) in listOf(ids.take(4).map { refusalEvent(it,"policy") } to listOf("claude-test"),
            ids.map { refusalEvent(it,"policy") } to listOf("missing"),
            ids.map { refusalEvent(it,null,reason="end_turn") } to emptyList(),emptyList<String>() to emptyList())) {
            seedPoints(rows)
            val id = mapper.readTree(qualityRun(bearer,models).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun consolidationRun(bearer: String) =
        mvc.perform(post("/v1/scenarios/S8-5/runs").header("Authorization","Bearer $bearer")
            .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02"}}"""))

    @Test fun `도구 통합 시나리오는 제품별 사용자를 별도로 제공하고 전체 비용을 합산하지 않는다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(promptEvent(it,product="claude_code"),promptEvent(it,product="codex"),costEvent(it,3.0),toolEvent(it,true)) })
        val bearer = token()
        val id = mapper.readTree(consolidationRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("active_users","cost_per_active_user","tool_calls")
        val findings = run["result"]["findings"].toList()
        assertThat(findings.map { it["evidence"]["product"].asString() }).containsExactly("claude_code","codex")
        assertThat(findings.map { it["evidence"]["active_users"].asDouble() }).containsExactly(5.0,5.0)
        assertThat(frames["cost_per_active_user"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(3.0)
        assertThat(frames["tool_calls"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
    }

    @Test fun `도구 통합 시나리오는 제품별 소집단과 미관측을 제외한다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.map { promptEvent(it) }+ids.take(4).map { promptEvent(it,product="codex") },emptyList())) {
            seedPoints(rows)
            val id = mapper.readTree(consolidationRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            val products = run["result"]["findings"].toList().map { it["evidence"]["product"].asString() }
            assertThat(products).containsExactlyElementsOf(if(rows.isEmpty()) emptyList() else listOf("claude_code"))
        }
    }

    private fun commandPrompt(id: UUID, name: String?): String {
        val row = mapper.readTree(promptEvent(id)) as tools.jackson.databind.node.ObjectNode
        val raw = mapper.readTree(row["raw_json"].asString()) as tools.jackson.databind.node.ObjectNode
        (raw["payload"] as tools.jackson.databind.node.ObjectNode).put("command_name",name)
        row.put("raw_json",raw.toString())
        return row.toString()
    }
    private fun templateRun(bearer: String, names: List<String>? = null) =
        mvc.perform(post("/v1/scenarios/S4-5/runs").header("Authorization","Bearer $bearer")
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("params" to
                (mapOf("from" to "2026-09-01","to" to "2026-09-02") +
                    if(names==null) emptyMap() else mapOf("command_names" to names))))))

    @Test fun `템플릿 시나리오는 선택 명령만 분자에 포함하고 전체 프롬프트 분모를 유지한다`() {
        val ids = installations(5)
        val quoted = "/review'\\name"
        seedPoints(ids.flatMap { listOf(commandPrompt(it,quoted),commandPrompt(it,"/plan"),commandPrompt(it,null)) })
        val bearer = token()
        templateRun(bearer,listOf("x".repeat(201))).andExpect(status().isBadRequest)
        templateRun(bearer,(1..101).map { "c$it" }).andExpect(status().isBadRequest)
        for ((names,expected) in listOf(listOf(quoted) to 1.0/3,null to 2.0/3,emptyList<String>() to 2.0/3)) {
            val id = mapper.readTree(templateRun(bearer,names).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(2)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(2)
            assertThat(run["result"]["frames"].propertyNames()).containsExactlyInAnyOrder("command_prompt_ratio","prompts_per_session")
            assertThat(run["result"]["findings"].single()["evidence"]["ratio"].asDouble()).isEqualTo(expected)
            val frame = run["result"]["frames"]["command_prompt_ratio"]["frames"][0]
            val denominator = frame["schema"]["fields"].toList().indexOfFirst { it["name"].asString()=="denominator" }
            assertThat(frame["data"]["values"][denominator][0].asDouble()).isEqualTo(15.0)
        }
    }

    @Test fun `명령 조회는 잘못된 필터를 거부하고 시나리오는 소집단과 불일치를 제외한다`() {
        val ids = installations(5)
        val bearer = token()
        for (names in listOf("invalid",listOf(1),listOf(""),listOf("x".repeat(201)))) {
            queryResult(queryBody(mapOf("metric_id" to "command_prompt_ratio","params" to mapOf("command_names" to names))))
                .andExpect(status().isBadRequest)
        }
        for(rows in listOf(ids.take(4).map { commandPrompt(it,"/review") },emptyList(),ids.map { commandPrompt(it,"/Review") })) {
            seedPoints(rows)
            val id = mapper.readTree(templateRun(bearer,listOf("/review")).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun governanceRun(bearer: String, audit: String? = "governance coverage review") =
        mvc.perform(post("/v1/scenarios/S7-4/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02"}}"""))

    @Test fun `감사 거버넌스 시나리오는 활성 설치 대비 관측 커버리지를 제공한다`() {
        val ids = installations(6)
        seedPoints(ids.take(5).flatMap { listOf(hookEvent(it,"1"),mcpEvent(it,"connected")) })
        val bearer = token()
        governanceRun(bearer,null).andExpect(status().isForbidden)
        val id = mapper.readTree(governanceRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("telemetry_coverage","hook_executions","mcp_connections")
        val finding = run["result"]["findings"].single()
        assertThat(finding["rule_id"].asString()).isEqualTo("observed_telemetry_coverage")
        assertThat(finding["severity"].asString()).isEqualTo("info")
        assertThat(finding["evidence"]["ratio"].asDouble()).isEqualTo(5.0/6.0)
        assertThat(frames["hook_executions"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        assertThat(frames["mcp_connections"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S7-4'")
            .query(Long::class.java).single()).isEqualTo(1)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        governanceRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `감사 거버넌스 시나리오는 소집단 빈 데이터와 비활성 분모를 정상으로 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for ((rows,revoke) in listOf(ids.take(4).map { point(it,1.0) } to false,
            emptyList<String>() to false,ids.map { point(it,1.0) } to true)) {
            seedPoints(rows)
            if(revoke) jdbc.sql("UPDATE enrollment.installations SET status='revoked' WHERE tenant_id=:tenant").param("tenant",tenant).update()
            val id = mapper.readTree(governanceRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun readDensityRun(bearer: String, threshold: Double = 10.0, audit: String? = "read density review") =
        mvc.perform(post("/v1/scenarios/S7-2/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("params" to
                mapOf("from" to "2026-09-01","to" to "2026-09-02","density_threshold" to threshold)))))

    @Test fun `읽기 밀도 시나리오는 세션 p90과 지정 임계값을 엄격 비교한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(toolEvent(it,true,"read"),toolEvent(it,true,"search"),toolEvent(it,true,"fetch"),toolEvent(it,true,"write")) })
        val bearer = token()
        readDensityRun(bearer,audit=null).andExpect(status().isForbidden)
        readDensityRun(bearer,-1.0).andExpect(status().isBadRequest)
        for ((threshold,count) in listOf(2.5 to 1,3.0 to 0,10.0 to 0)) {
            val id = mapper.readTree(readDensityRun(bearer,threshold).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
            assertThat(run["result"]["frames"].propertyNames()).containsExactlyInAnyOrder("read_tool_density","mcp_connections","auto_approval_ratio")
            assertThat(run["result"]["findings"].size()).isEqualTo(count)
            if(count>0) {
                val finding = run["result"]["findings"].single()
                assertThat(finding["rule_id"].asString()).isEqualTo("high_read_density")
                assertThat(finding["evidence"]["p90_calls_per_session"].asDouble()).isEqualTo(3.0)
                assertThat(finding["evidence"]["threshold"].asDouble()).isEqualTo(threshold)
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S7-2'")
            .query(Long::class.java).single()).isEqualTo(3)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        readDensityRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `읽기 밀도 시나리오는 소집단 빈 데이터와 쓰기만 있는 세션을 제외한다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { toolEvent(it,true,"read") },emptyList(),ids.map { toolEvent(it,true,"write") })) {
            seedPoints(rows)
            val id = mapper.readTree(readDensityRun(bearer,0.0).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun latencyRun(bearer: String, models: List<String> = listOf("test"), audit: String? = "latency model review") =
        mvc.perform(post("/v1/scenarios/S6-4/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("params" to
                mapOf("from" to "2026-09-01","to" to "2026-09-02","models" to models)))))

    @Test fun `레이턴시 시나리오는 모델 범위를 모든 지표와 결과 필터에 적용한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(ttftEvent(it,100,"request"),durationEvent(it,9999),llmEvent(it,1,500)) })
        val bearer = token()
        latencyRun(bearer,audit=null).andExpect(status().isForbidden)
        latencyRun(bearer,listOf("x".repeat(201))).andExpect(status().isBadRequest)
        latencyRun(bearer,(1..101).map { "m$it" }).andExpect(status().isBadRequest)
        val id = mapper.readTree(latencyRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        assertThat(run["result"]["applied_filters"]["filters"]["models"].toList().map { it.asString() }).containsExactly("test")
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("llm_duration_ms","llm_ttft_ms","api_error_rate","active_users")
        assertThat(frames["llm_duration_ms"]["frames"].toList().all { f -> f["data"]["values"].toList().drop(1).all { values -> values.toList().all { it.isNull } } }).isTrue()
        assertThat(frames["active_users"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        val finding = run["result"]["findings"].single()
        assertThat(finding["rule_id"].asString()).isEqualTo("observed_first_token_latency")
        assertThat(finding["evidence"]["p90_ms"].asDouble()).isEqualTo(100.0)
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S6-4'")
            .query(Long::class.java).single()).isEqualTo(1)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        latencyRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `레이턴시 시나리오는 소집단 미관측 모델과 영 지연을 장애로 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for((rows,models) in listOf(ids.take(4).map { ttftEvent(it,100,"a") } to listOf("test"),
            ids.map { ttftEvent(it,100,"a") } to listOf("missing"),
            ids.map { ttftEvent(it,0,"a") } to emptyList(), emptyList<String>() to emptyList())) {
            seedPoints(rows)
            val id = mapper.readTree(latencyRun(bearer,models).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun fastApprovalRun(bearer: String, threshold: Int? = null, audit: String? = "fast approval review") =
        mvc.perform(post("/v1/scenarios/S5-7/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content(mapper.writeValueAsString(mapOf("params" to
                (mapOf("from" to "2026-09-01","to" to "2026-09-02") +
                    if(threshold==null) emptyMap() else mapOf("threshold_ms" to threshold))))))

    @Test fun `즉시 승인 시나리오는 지정 임계값과 엄격 경계를 적용하고 감사한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(gateEvent(it,1999),gateEvent(it,2000),gateEvent(it,3000),
            gateEvent(it,null),gateEvent(it,0,"reject"),gateEvent(it,0,by="config")) })
        val bearer = token()
        fastApprovalRun(bearer,audit=null).andExpect(status().isForbidden)
        for (invalid in listOf(0,60001)) fastApprovalRun(bearer,invalid).andExpect(status().isBadRequest)
        for ((threshold,expected) in listOf(null to 1.0/3,2001 to 2.0/3)) {
            val id = mapper.readTree(fastApprovalRun(bearer,threshold).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
            assertThat(run["result"]["frames"].propertyNames()).containsExactlyInAnyOrder("rubber_stamp_ratio","auto_approval_ratio","pull_requests")
            val finding = run["result"]["findings"].single()
            assertThat(finding["rule_id"].asString()).isEqualTo("observed_fast_approvals")
            assertThat(finding["evidence"]["ratio"].asDouble()).isCloseTo(expected,org.assertj.core.data.Offset.offset(0.000001))
            assertThat(finding["evidence"]["threshold_ms"].asInt()).isEqualTo(threshold ?: 2000)
        }
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S5-7'")
            .query(Long::class.java).single()).isEqualTo(2)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        fastApprovalRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `즉시 승인 시나리오는 소집단 결측과 임계값 이상 승인에서 판정을 만들지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { gateEvent(it,0) },emptyList(),ids.map { gateEvent(it,2000) },
            ids.map { gateEvent(it,0,"reject") })) {
            seedPoints(rows)
            val id = mapper.readTree(fastApprovalRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun externalAccessRun(bearer: String, audit: String? = "external access review") =
        mvc.perform(post("/v1/scenarios/S5-2/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02"}}"""))

    @Test fun `외부 접근 시나리오는 감사 후 MCP와 읽기 밀도를 제공한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(mcpEvent(it,"connected"),toolEvent(it,true,"read"),toolEvent(it,true,"search")) })
        val bearer = token()
        externalAccessRun(bearer,null).andExpect(status().isForbidden)
        val id = mapper.readTree(externalAccessRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(2)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(2)
        assertThat(run["result"]["target_page"].asString()).isEqualTo("P3")
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("mcp_connections","read_tool_density")
        assertThat(frames["mcp_connections"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        assertThat(frames["read_tool_density"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(2.0)
        val findings = run["result"]["findings"]
        assertThat(findings.size()).isEqualTo(1)
        assertThat(findings[0]["rule_id"].asString()).isEqualTo("observed_mcp_connections")
        assertThat(findings[0]["evidence"]["count"].asDouble()).isEqualTo(5.0)
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S5-2'")
            .query(Long::class.java).single()).isEqualTo(1)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        externalAccessRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `외부 접근 시나리오는 소집단과 읽기만으로 유출을 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { mcpEvent(it,"connected") },emptyList(),ids.map { toolEvent(it,true,"read") })) {
            seedPoints(rows)
            val id = mapper.readTree(externalAccessRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun policyRun(bearer: String, audit: String? = "policy scenario review") =
        mvc.perform(post("/v1/scenarios/S7-3/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02"}}"""))

    @Test fun `정책 시나리오는 감사 후 거절 차단 대기를 제공한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(decisionEvent(it,"hook","reject"),gateEvent(it,120000),hookEvent(it,"2")) })
        val bearer = token()
        policyRun(bearer,null).andExpect(status().isForbidden)
        val id = mapper.readTree(policyRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        assertThat(run["result"]["target_page"].asString()).isEqualTo("P3")
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("tool_rejections","hook_blocking","gate_wait_ms")
        assertThat(frames["tool_rejections"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        assertThat(frames["hook_blocking"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(10.0)
        assertThat(frames["gate_wait_ms"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(120000.0)
        val findings = run["result"]["findings"]
        assertThat(findings.size()).isEqualTo(1)
        assertThat(findings[0]["rule_id"].asString()).isEqualTo("observed_tool_rejections")
        assertThat(findings[0]["severity"].asString()).isEqualTo("info")
        assertThat(findings[0]["evidence"]["count"].asDouble()).isEqualTo(5.0)
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S7-3'")
            .query(Long::class.java).single()).isEqualTo(1)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        policyRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `정책 시나리오는 소집단과 승인에서 위반을 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { decisionEvent(it,"user","reject") },emptyList(),ids.map { decisionEvent(it,"user","accept") })) {
            seedPoints(rows)
            val id = mapper.readTree(policyRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun retryStormRun(bearer: String, audit: String? = "retry scenario review") =
        mvc.perform(post("/v1/scenarios/S6-5/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02"}}"""))

    @Test fun `재시도 시나리오는 감사 후 재시도 비율과 전체 비용을 제공한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(llmEvent(it,1,200),llmEvent(it,2,200),costEvent(it,3.0)) })
        val bearer = token()
        retryStormRun(bearer,null).andExpect(status().isForbidden)
        val id = mapper.readTree(retryStormRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(2)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(2)
        assertThat(run["result"]["target_page"].asString()).isEqualTo("P3")
        assertThat(run["result"]["frames"].propertyNames()).containsExactlyInAnyOrder("api_retry_attempts","cost")
        assertThat(run["result"]["frames"]["cost"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(15.0)
        val findings = run["result"]["findings"]
        assertThat(findings.size()).isEqualTo(1)
        assertThat(findings[0]["rule_id"].asString()).isEqualTo("observed_api_retries")
        // 비용만 있는 llm_call도 전체 관측 호출 분모에 포함된다(Q12).
        assertThat(findings[0]["evidence"]["ratio"].asDouble()).isEqualTo(1.0/3)
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S6-5'")
            .query(Long::class.java).single()).isEqualTo(1)
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        retryStormRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `재시도 시나리오는 소집단과 미관측 및 첫 시도를 스톰으로 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { llmEvent(it,2,500) },emptyList(),ids.map { llmEvent(it,1,200) })) {
            seedPoints(rows)
            val id = mapper.readTree(retryStormRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun rateLimitRun(bearer: String, audit: String? = "rate limit scenario review") =
        mvc.perform(post("/v1/scenarios/S2-2/runs").header("Authorization","Bearer $bearer")
            .also { if(audit!=null) it.header("X-Audit-Reason",audit) }
            .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02"}}"""))

    @Test fun `제한 시나리오는 owner 감사 후 실행하고 권한 변경을 재검증한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(llmEvent(it,2,429),tokenEvent(it,100,50,0,0)) })
        val bearer = token()
        rateLimitRun(bearer,null).andExpect(status().isForbidden)
        val id = mapper.readTree(rateLimitRun(bearer).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        assertThat(jdbc.sql("SELECT count(*) FROM dashboard.audit_log WHERE action='scenario_run' AND target='S2-2'")
            .query(Long::class.java).single()).isEqualTo(1)
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        assertThat(run["result"]["target_page"].asString()).isEqualTo("P3")
        assertThat(run["result"]["findings"][0]["evidence"]["count"].asDouble()).isEqualTo(5.0)
        assertThat(run["result"]["frames"]["tokens"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(750.0)
        val queued = mapper.readTree(rateLimitRun(bearer).andReturn().response.contentAsString)["run_id"].asString()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        rateLimitRun(token()).andExpect(status().isForbidden)
        scenarioRuns.runOne()
        val failed = jdbc.sql("SELECT status FROM dashboard.scenario_runs WHERE id=CAST(:id AS uuid)").param("id",queued)
            .query(String::class.java).single()
        assertThat(failed).isEqualTo("failed")
    }

    @Test fun `제한 시나리오는 소집단과 미관측에서 고갈을 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { llmEvent(it,2,429) },emptyList(),ids.map { llmEvent(it,1,200) })) {
            seedPoints(rows)
            val id = mapper.readTree(rateLimitRun(bearer).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    private fun effortCost(id: UUID, value: Double, effort: String, speed: String, cumulative: Boolean = false): String {
        val row = mapper.readTree(subagentCost(id,value,"main",cumulative=cumulative)) as tools.jackson.databind.node.ObjectNode
        val raw = mapper.readTree(row["raw_json"].asString())
        (raw["point"]["attrs"] as tools.jackson.databind.node.ObjectNode).put("effort",effort).put("speed",speed)
        row.put("raw_json",raw.toString())
        return row.toString()
    }

    @Test fun `비용 예측은 90일 실측의 일정 평균과 선형 추세를 구분한다`() {
        val ids = installations(5)
        val start = java.time.LocalDate.parse("2026-06-01")
        val rows = ids.flatMap { id -> (0 until 90).map { day -> modelCost(id,"forecast-model",day+1.0,
            "${start.plusDays(day.toLong())}T12:00:00Z") } }
        seedPoints(rows)
        val bearer = token()
        for ((model,expected) in listOf("constant" to 6825.0,"linear" to 15825.0)) {
            val id = mapper.readTree(mvc.perform(post("/v1/scenarios/S8-2/runs").header("Authorization","Bearer $bearer")
                .contentType("application/json").content("""{"tz":"UTC","params":{"from":"2026-06-01","to":"2026-08-30","growth_model":"$model"}}"""))
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).withFailMessage(run.toString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(6)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(6)
            val forecast = run["result"]["forecast"]
            assertThat(forecast["status"].asString()).isEqualTo("ready")
            assertThat(forecast["projected_cost_usd"].asDouble()).isCloseTo(expected,org.assertj.core.api.Assertions.within(0.001))
            assertThat(forecast["observed_days"].asInt()).isEqualTo(90)
            assertThat(forecast["daily"].size()).isEqualTo(30)
            assertThat(forecast["forecast_from"].asString()).isEqualTo("2026-08-30")
            assertThat(run["result"]["findings"].single()["rule_id"].asString()).isEqualTo("projected_cost")
        }
    }

    private fun onboardingRun(bearer: String, reason: String? = "onboarding cohort review") =
        mvc.perform(post("/v1/scenarios/S3-3/runs").header("Authorization","Bearer $bearer")
            .also { if(reason!=null) it.header("X-Audit-Reason",reason) }.contentType("application/json")
            .content("""{"tz":"UTC","params":{"cohort_from":"2026-09-01","cohort_to":"2026-09-02"}}"""))

    @Test fun `온보딩 시나리오는 코호트 밖 첫 사용을 제외하고 동일 설치의 이후 활동을 추적한다`() {
        val fresh = installations(5); val old = installations(5); val later = installations(5)
        (fresh+old+later).forEach { installationCreated(it,"2026-08-01T00:00:00Z") }
        seedPoints(fresh.flatMap { listOf(promptEvent(it,at="2026-09-01T00:00:00Z"),
            point(it,120.0,at="2026-09-03T00:00:00Z").replace("claude_code.session.count","claude_code.active_time.total")) }+
            old.flatMap { listOf(promptEvent(it,at="2026-08-31T23:59:59Z"),
                point(it,9999.0,at="2026-09-03T00:00:00Z").replace("claude_code.session.count","claude_code.active_time.total")) }+
            later.flatMap { listOf(promptEvent(it,at="2026-09-02T00:00:00Z"),
                point(it,9999.0,at="2026-09-03T00:00:00Z").replace("claude_code.session.count","claude_code.active_time.total")) })
        val bearer = token()
        onboardingRun(bearer,null).andExpect(status().isForbidden)
        val id = mapper.readTree(onboardingRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).withFailMessage(run.toString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        assertThat(run["result"]["frames"]["active_time"]["frames"].single()["data"]["values"][0][0].asDouble()).isEqualTo(600.0)
        val retention = run["result"]["frames"]["onboarding_retention"]["frames"].toList()
        assertThat(retention.map { it["schema"]["fields"][0]["labels"]["cohort_week"].asString() }.distinct()).containsExactly("2026-08-31")
        assertThat(retention.first()["data"]["values"][2][0].asInt()).isEqualTo(5)
        assertThat(retention.first()["data"]["values"][0][0].asDouble()).isEqualTo(1.0)
        assertThat(run["result"]["applied_filters"]["observed_to"].asString()).isEqualTo(run["resolved_to"].asString())
        (fresh+old+later).forEach { assertThat(run.toString()).doesNotContain(it.toString()) }
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        onboardingRun(token()).andExpect(status().isForbidden)
    }

    @Test fun `온보딩 코호트 소집단은 기간 내 다른 설치로 마스킹을 해제하지 않는다`() {
        val fresh = installations(4); val old = installations(5)
        (fresh+old).forEach { installationCreated(it,"2026-08-01T00:00:00Z") }
        seedPoints(fresh.flatMap { listOf(promptEvent(it),point(it,120.0).replace("claude_code.session.count","claude_code.active_time.total")) }+
            old.flatMap { listOf(promptEvent(it,at="2026-08-31T00:00:00Z"),point(it,9999.0).replace("claude_code.session.count","claude_code.active_time.total")) })
        val bearer = token()
        val id = mapper.readTree(onboardingRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).withFailMessage(run.toString()).isEqualTo("succeeded")
        assertThat(run["result"]["frames"]["active_time"]["frames"].single()["data"]["values"][0][0].isNull).isTrue()
        assertThat(run["result"]["findings"].size()).isZero()
    }

    private fun premiumRun(bearer: String, patterns: List<String>) = startRun(bearer,"S1-2",
        mapper.writeValueAsString(mapOf("from" to "2026-09-01","to" to "2026-09-02","premium_model_patterns" to patterns)))

    @Test fun `프리미엄 패턴은 전체 이름 대소문자와 별표만 해석하고 모델 비용과 토큰을 선택한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(modelCost(it,"claude-opus",2.0),modelCost(it,"claude-sonnet",99.0),
            tokenEvent(it,10,20,0,0),promptEvent(it),toolEvent(it,true)) })
        val bearer = token()
        for ((patterns,expected) in listOf(listOf("*opus*") to 1,listOf("opus") to 0,listOf("*OPUS*") to 0,
            listOf("claude-opus","*opus*") to 1,listOf("*") to 2)) {
            val id = mapper.readTree(premiumRun(bearer,patterns).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).withFailMessage(run.toString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
            assertThat(run["result"]["findings"].size()).isEqualTo(expected)
            if(expected==1) assertThat(run["result"]["findings"].single()["evidence"]["cost_usd"].asDouble()).isEqualTo(10.0)
            // 모델 정보가 없는 지표는 기간·팀 참고값으로 유지한다.
            val prompts = run["result"]["frames"]["prompts_per_session"]["frames"].single()
            assertThat(prompts["data"]["values"][0][0].asDouble()).isEqualTo(1.0)
            if(patterns==listOf("*")) {
                val tokens = run["result"]["frames"]["tokens"]["frames"].single { it["schema"]["fields"][0].path("labels").path("model").asString("")=="test" }
                assertThat(tokens["data"]["values"][0][0].asDouble()).isEqualTo(150.0)
            }
        }
    }

    @Test fun `프리미엄 패턴은 SQL 특수문자를 문자로 처리하고 소집단을 숨긴다`() {
        val ids = installations(5)
        val literal = "opus_%'model"
        seedPoints(ids.flatMap { listOf(modelCost(it,literal,2.0),modelCost(it,"opusXanything'model",99.0)) })
        val bearer = token()
        val id = mapper.readTree(premiumRun(bearer,listOf(literal)).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).withFailMessage(run.toString()).isEqualTo("succeeded")
        assertThat(run["result"]["findings"].single()["evidence"]["cost_usd"].asDouble()).isEqualTo(10.0)
        seedPoints(ids.take(4).map { modelCost(it,literal,2.0) })
        val small = mapper.readTree(premiumRun(bearer,listOf(literal)).andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        assertThat(readRun(small,bearer)["result"]["findings"].size()).isZero()
        premiumRun(bearer,emptyList()).andExpect(status().isBadRequest)
        premiumRun(bearer,listOf("a".repeat(201))).andExpect(status().isBadRequest)
    }

    @Test fun `모델 effort 시나리오는 delta 비용 차원과 토큰 세션을 제공한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(effortCost(it,2.0,"high","fast"),effortCost(it,3.0,"low","normal"),
            effortCost(it,999.0,"high","fast",true),costEvent(it,9999.0),tokenEvent(it,100,50,0,0),point(it,1.0)) })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S1-6","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("cost","tokens","sessions")
        assertThat(frames["tokens"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(750.0)
        assertThat(frames["sessions"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        val findings = run["result"]["findings"].toList()
        assertThat(findings).hasSize(4)
        assertThat(findings.map { it["evidence"]["cost_usd"].asDouble() }).containsExactlyInAnyOrder(10.0,15.0,10.0,15.0)
        assertThat(findings.map { it["evidence"]["effort"].asString() }).containsExactlyInAnyOrder("high","low","","")
        assertThat(findings.map { it["evidence"]["speed"].asString() }).containsExactlyInAnyOrder("fast","normal","","")
        assertThat(findings.all { it["severity"].asString()=="info" }).isTrue()
    }

    @Test fun `모델 effort 시나리오는 소집단과 영 비용에서 낭비를 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { effortCost(it,100.0,"high","fast") },emptyList(),
            ids.map { effortCost(it,0.0,"high","fast") })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S1-6","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `집중도 시나리오는 익명 요약과 로렌츠 곡선을 제공한다`() {
        val ids = installations(5)
        seedPoints(ids.mapIndexed { index,id -> tokenEvent(id,(index+1)*10,0,0,0) } + ids.map { toolEvent(it,true) })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S3-2","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        val result = run["result"]
        assertThat(result["frames"].propertyNames()).containsExactlyInAnyOrder("usage_concentration","tool_calls","tokens")
        val frames = result["frames"]["usage_concentration"]["frames"].toList()
        assertThat(frames).hasSize(2)
        val curve = frames.single { it["schema"]["fields"][0]["name"].asString()=="population_share" }
        assertThat(curve["data"]["values"][0].size()).isEqualTo(6)
        assertThat(curve["data"]["values"][2][5].asDouble()).isEqualTo(1.0)
        assertThat(result["findings"].size()).isEqualTo(1)
        assertThat(result["findings"][0]["evidence"]["top_decile_share"].asDouble()).isEqualTo(1.0/3)
        ids.forEach { assertThat(result.toString()).doesNotContain(it.toString()) }
    }

    @Test fun `집중도 시나리오는 소집단 곡선과 영 분모 판정을 숨긴다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { tokenEvent(it,100,0,0,0) },emptyList(),ids.map { tokenEvent(it,0,0,0,0) })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S3-2","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
            if(rows.size==4) run["result"]["frames"]["usage_concentration"]["frames"].forEach { frame ->
                frame["data"]["values"].forEach { column ->
                    assertThat(column.size()).isEqualTo(1)
                    assertThat(column[0].isNull).isTrue()
                }
            }
        }
    }

    @Test fun `경영 보고 시나리오는 일곱 지표와 사용자당 비용을 제공한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(point(it,1.0),tokenEvent(it,100,50,0,0),userTime(it,60.0),costEvent(it,3.0),
            sessionOutput(it,"s","claude_code.lines_of_code.count",10.0),sessionOutput(it,"s","claude_code.commit.count",2.0),
            sessionOutput(it,"s","claude_code.pull_request.count",1.0)) })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S8-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(7)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(7)
        val expected = mapOf("sessions" to 5.0,"tokens" to 750.0,"active_time" to 300.0,"lines_of_code" to 50.0,
            "commits" to 10.0,"pull_requests" to 5.0,"cost_per_active_user" to 3.0)
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrderElementsOf(expected.keys)
        for((metric,value) in expected) assertThat(frames[metric]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(value)
        assertThat(run["result"]["findings"].size()).isEqualTo(1)
        assertThat(run["result"]["findings"][0]["evidence"]["count"].asDouble()).isEqualTo(5.0)
    }

    @Test fun `경영 보고 시나리오는 소집단과 미관측에서 ROI를 추정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { point(it,1.0) },emptyList(),ids.map { point(it,0.0) })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S8-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `시간대 시나리오는 현지 요일 시간의 168칸과 제한 이벤트를 제공한다`() {
        val ids = installations(5)
        jdbc.sql("UPDATE enrollment.tenants SET timezone='Asia/Seoul' WHERE id=:tenant").param("tenant",tenant).update()
        val start = java.time.Instant.parse("2026-08-31T00:00:00Z")
        seedPoints((0..167).flatMap { hour -> ids.map { promptEvent(it,at=start.plusSeconds(hour*3600L).toString()) } } +
            ids.map { llmEvent(it,1,429) })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S2-1","""{"from":"2026-08-31T00:00:00Z","to":"2026-09-07T00:00:00Z"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("usage_heatmap","rate_limit_events","session_last_event")
        val heatmap = frames["usage_heatmap"]["frames"].toList()
        assertThat(heatmap).hasSize(168)
        assertThat(heatmap.all { it["data"]["values"][0][0].asDouble()==5.0 }).isTrue()
        assertThat(heatmap.map { it["schema"]["fields"][0]["labels"]["hour"].asString() }.toSet()).hasSize(24)
        assertThat(heatmap.map { it["schema"]["fields"][0]["labels"]["weekday"].asString() }.toSet()).hasSize(7)
        val findings = run["result"]["findings"]
        assertThat(findings.size()).isEqualTo(1)
        assertThat(findings[0]["evidence"]["count"].asDouble()).isEqualTo(5.0)
        assertThat(findings[0]["evidence"]["date"].asString()).isEqualTo("2026-08-31T15:00:00Z")
    }

    @Test fun `시간대 시나리오는 소집단과 미관측 제한을 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { llmEvent(it,1,429) },emptyList(),ids.map { llmEvent(it,1,200) })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S2-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `고급 기능 시나리오는 서브에이전트 비용과 보조 지표를 제공한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(subagentCost(it,2.0,"subagent"),subagentCost(it,6.0,"main"),
            mcpEvent(it,"connected"),promptEvent(it),toolEvent(it,true),toolEvent(it,false)) })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S3-5","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("mcp_connections","subagent_cost_ratio","command_prompt_ratio","tool_failure_rate")
        assertThat(frames["mcp_connections"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
        assertThat(frames["tool_failure_rate"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(0.5)
        val findings = run["result"]["findings"]
        assertThat(findings.size()).isEqualTo(1)
        assertThat(findings[0]["rule_id"].asString()).isEqualTo("observed_subagent_cost")
        assertThat(findings[0]["evidence"]["ratio"].asDouble()).isEqualTo(0.25)
    }

    @Test fun `고급 기능 시나리오는 소집단 분모 영과 서브에이전트 미관측에서 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { subagentCost(it,3.0,"subagent") },emptyList(),
            ids.map { subagentCost(it,0.0,"subagent") },ids.map { subagentCost(it,3.0,"main") })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S3-5","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `채택 시나리오는 팀별 다섯 지표와 관측 채택률을 제공한다`() {
        val team = costTeams().first()
        val ids = installations(5,team)
        seedPoints(ids.flatMap { id -> listOf(promptEvent(id,session="s"),promptEvent(id,session="s"),
            toolEvent(id,true),mcpEvent(id,"connected")).map { inTeam(it,team) } })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S3-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(5)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(5)
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("active_users","adoption_rate","prompts_per_session","tool_calls","mcp_connections")
        for(metric in listOf("active_users","tool_calls","mcp_connections"))
            assertThat(frames[metric]["frames"][0]["data"]["values"][0][0].asDouble()).isEqualTo(5.0)
        val prompts = frames["prompts_per_session"]["frames"][0]
        val p50 = prompts["schema"]["fields"].toList().indexOfFirst { it["name"].asString()=="p50" }
        assertThat(prompts["data"]["values"][p50][0].asDouble()).isEqualTo(2.0)
        val findings = run["result"]["findings"]
        assertThat(findings.size()).isEqualTo(1)
        assertThat(findings[0]["evidence"]["team_id"].asString()).isEqualTo(team.toString())
        assertThat(findings[0]["evidence"]["adoption_rate"].asDouble()).isEqualTo(1.0)
    }

    @Test fun `채택 시나리오는 소집단과 미관측에서 판정하지 않는다`() {
        val team = costTeams().first()
        val ids = installations(5,team)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { inTeam(promptEvent(it),team) },emptyList())) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S3-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `벤더 시나리오는 제품 모델 토큰과 제품 비용을 분리한다`() {
        val ids = installations(5)
        fun product(row: String, product: String): String = (mapper.readTree(row) as tools.jackson.databind.node.ObjectNode).put("product",product).toString()
        seedPoints(ids.flatMap { id -> listOf(tokenEvent(id,100,50,0,0),costEvent(id,2.0),
            product(tokenEvent(id,10,20,0,0),"codex"),product(costEvent(id,3.0),"codex")) })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S8-4","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(2)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(2)
        assertThat(run["result"]["frames"].propertyNames()).containsExactlyInAnyOrder("tokens","cost")
        val findings = run["result"]["findings"].toList()
        assertThat(findings.map { it["evidence"]["product"].asString() }).containsExactly("claude_code","codex")
        assertThat(findings.map { it["evidence"]["cost_usd"].asDouble() }).containsExactly(10.0,15.0)
        val tokenFrames = run["result"]["frames"]["tokens"]["frames"].toList()
        val observed = tokenFrames.filter { it["schema"]["fields"][0]["labels"]["model"].asString()=="test" }
        val missing = tokenFrames.filter { it["schema"]["fields"][0]["labels"]["model"].asString()=="claude-test" }
        assertThat(observed.map { it["data"]["values"][0][0].asDouble() }).containsExactlyInAnyOrder(750.0,150.0)
        assertThat(missing).hasSize(2)
        assertThat(missing.all { it["data"]["values"][0][0].isNull }).isTrue()
    }

    @Test fun `벤더 시나리오는 소집단 미관측 영 비용을 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { costEvent(it,3.0) },emptyList(),ids.map { costEvent(it,0.0) })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S8-4","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `팀 활용 시나리오는 네 지표의 팀별 기간 합계를 제공한다`() {
        val teams = costTeams().take(2)
        val rows = teams.flatMapIndexed { index, team -> installations(5,team).flatMap { id ->
            listOf(point(id,(index+1).toDouble(),team=team),inTeam(userTime(id,60.0),team),
                inTeam(sessionOutput(id,"s","claude_code.lines_of_code.count",10.0),team))
        } }
        seedPoints(rows)
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S3-4","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
        val frames = run["result"]["frames"]
        assertThat(frames.propertyNames()).containsExactlyInAnyOrder("sessions","active_time","lines_of_code","adoption_rate")
        for(metric in listOf("sessions","active_time","lines_of_code")) {
            val byTeam = frames[metric]["frames"].toList().associate { f ->
                f["schema"]["fields"][0]["labels"]["team"].asString() to f["data"]["values"][0][0].asDouble()
            }
            assertThat(byTeam.keys).containsExactlyInAnyOrderElementsOf(teams.map { it.toString() })
            assertThat(byTeam.values).containsExactlyInAnyOrderElementsOf(when(metric) {
                "sessions" -> listOf(5.0,10.0); "active_time" -> listOf(300.0,300.0); else -> listOf(50.0,50.0)
            })
        }
        assertThat(run["result"]["findings"].size()).isEqualTo(2)
    }

    @Test fun `팀 활용 시나리오는 소집단을 판정에서 제외하고 admin 팀으로 제한한다`() {
        val teams = costTeams().take(2)
        val ids = teams.map { installations(5,it) }
        seedPoints(ids[0].map { point(it,1.0,team=teams[0]) } + ids[1].take(4).map { point(it,100.0,team=teams[1]) })
        val bearer = token()
        val ownerId = mapper.readTree(startRun(bearer,"S3-4","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        assertThat(readRun(ownerId,bearer)["result"]["findings"].size()).isEqualTo(1)
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)")
            .param("team",teams[0]).param("member",member).update()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        val admin = token()
        val id = mapper.readTree(startRun(admin,"S3-4","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,admin)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["result"]["frames"].properties().flatMap { it.value["frames"].toList() }
            .flatMap { it["schema"]["fields"].toList() }.map { it.path("labels").path("team").asString() }.toSet())
            .containsExactly(teams[0].toString())
    }

    @Test fun `유즈케이스 시나리오는 action별 기간 합계를 분리한다`() {
        val ids = installations(5)
        val bearer = token()
        seedPoints(ids.flatMap { listOf(toolEvent(it,true,"read"),toolEvent(it,false,"read"),toolEvent(it,true,"write")) })
        val id = mapper.readTree(startRun(bearer,"S4-6","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(2)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(2)
        assertThat(run["result"]["frames"].propertyNames()).containsExactlyInAnyOrder("tool_calls","lines_of_code")
        val findings = run["result"]["findings"].toList()
        assertThat(findings.map { it["evidence"]["action"].asString() }).containsExactly("read","write")
        assertThat(findings.map { it["evidence"]["calls"].asDouble() }).containsExactly(10.0,5.0)
        assertThat(findings.all { it["severity"].asString()=="info" }).isTrue()
    }

    @Test fun `유즈케이스 시나리오는 action 소집단과 미관측을 제외한다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.map { toolEvent(it,true,"read") } + ids.take(4).map { toolEvent(it,true,"write") },emptyList())) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S4-6","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isEqualTo(if(rows.isEmpty()) 0 else 1)
            if(rows.isNotEmpty()) assertThat(run["result"]["findings"][0]["evidence"]["action"].asString()).isEqualTo("read")
        }
    }

    @Test fun `대기 시나리오는 분 임계값과 p90을 비교하고 경계값을 제외한다`() {
        val ids = installations(5)
        val bearer = token()
        seedPoints(ids.flatMap { listOf(gateEvent(it,120000),promptEvent(it)) })
        val id = mapper.readTree(startRun(bearer,"S4-8","""{"from":"2026-09-01","to":"2026-09-02","wait_thresholds_min":[3,1,2]}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        assertThat(run["result"]["frames"].propertyNames()).containsExactlyInAnyOrder("gate_wait_ms","tool_rejections","usage_heatmap")
        val findings = run["result"]["findings"]
        assertThat(findings.size()).isEqualTo(1)
        assertThat(findings[0]["evidence"]["p90_ms"].asDouble()).isEqualTo(120000.0)
        assertThat(findings[0]["evidence"]["threshold_min"].asDouble()).isEqualTo(1.0)
        startRun(bearer,"S4-8","""{"wait_thresholds_min":[-1]}""").andExpect(status().isBadRequest)
    }

    @Test fun `대기 시나리오는 소집단 미관측과 빈 임계값에서 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for((rows, thresholds) in listOf(ids.take(4).map { gateEvent(it,120000) } to "[0]",
            emptyList<String>() to "[0]", ids.map { gateEvent(it,120000) } to "[]")) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S4-8","""{"from":"2026-09-01","to":"2026-09-02","wait_thresholds_min":$thresholds}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `프롬프트 시나리오는 중앙값 초과와 토큰을 함께 제공한다`() {
        val ids = installations(5)
        val bearer = token()
        for(count in listOf(1,2)) {
            seedPoints(ids.flatMap { id -> (1..count).map { promptEvent(id,session="s") } + tokenEvent(id,100,50,0,0) })
            val id = mapper.readTree(startRun(bearer,"S4-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(2)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(2)
            val result = run["result"]
            assertThat(result["frames"].propertyNames()).containsExactlyInAnyOrder("prompts_per_session","tokens")
            assertThat(result["findings"].size()).isEqualTo(if(count==2) 1 else 0)
            if(count==2) {
                assertThat(result["findings"][0]["rule_id"].asString()).isEqualTo("multiple_prompts_per_session")
                assertThat(result["findings"][0]["evidence"]["p50"].asDouble()).isEqualTo(2.0)
            }
            assertThat(result["frames"]["tokens"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(750.0)
        }
    }

    @Test fun `프롬프트 시나리오는 마스킹과 미관측에서 판정하지 않는다`() {
        val ids = installations(4)
        val bearer = token()
        for(rows in listOf(ids.flatMap { id -> (1..3).map { promptEvent(id,session="s") } },emptyList())) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S4-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `캐싱 시나리오는 토큰과 프롬프트 분포를 조회하고 캐시 영을 안내한다`() {
        val ids = installations(5)
        val bearer = token()
        for (read in listOf(0,100)) {
            seedPoints(ids.flatMap { listOf(tokenEvent(it,100,50,read,0),promptEvent(it,session="s"),promptEvent(it,session="s")) })
            val id = mapper.readTree(startRun(bearer,"S1-4","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
            val result = run["result"]
            assertThat(result["frames"].propertyNames()).containsExactlyInAnyOrder("tokens","cache_read_ratio","prompts_per_session")
            assertThat(result["findings"].size()).isEqualTo(if(read==0) 1 else 0)
            if(read==0) assertThat(result["findings"][0]["rule_id"].asString()).isEqualTo("no_cache_reads")
            val prompts = result["frames"]["prompts_per_session"]["frames"][0]
            val index = prompts["schema"]["fields"].toList().indexOfFirst { it["name"].asString()=="p50" }
            assertThat(prompts["data"]["values"][index][0].asDouble()).isEqualTo(2.0)
        }
    }

    @Test fun `캐싱 시나리오는 마스킹 영 분모와 불완전 토큰에서 안내하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { tokenEvent(it,100,50,0,0) },ids.map { tokenEvent(it,0,0,0,0) },
            ids.map { tokenEvent(it,null,50,0,0) })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S1-4","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `대화 시나리오는 산출 없는 세션 비율과 마지막 이벤트를 연결한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(promptEvent(it,session="output"),promptEvent(it,session="no-output"),
            sessionOutput(it,"output","claude_code.commit.count")) })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S4-2","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
        assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
        val result = run["result"]
        assertThat(result["frames"].propertyNames()).containsExactlyInAnyOrder("abandoned_session_ratio","session_last_event","api_error_rate")
        assertThat(result["findings"].size()).isEqualTo(1)
        assertThat(result["findings"][0]["rule_id"].asString()).isEqualTo("sessions_without_output")
        assertThat(result["findings"][0]["evidence"]["ratio"].asDouble()).isEqualTo(.5)
        assertThat(result["frames"]["session_last_event"]["frames"][0]["data"]["values"][1][0].asInt()).isEqualTo(10)
    }

    @Test fun `대화 시나리오는 산출 존재 소집단 미관측에서 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.flatMap { listOf(promptEvent(it,session="s"),sessionOutput(it,"s","claude_code.commit.count")) },
            ids.take(4).map { promptEvent(it,session="s") },emptyList())) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S4-2","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["result"]["findings"].size()).isZero()
        }
    }

    @Test fun `예산 시나리오는 USD와 백만 토큰을 각 팀의 관측 합계와 비교한다`() {
        val teams = costTeams()
        val rows = teams.flatMap { team -> installations(5,team).flatMap {
            listOf(costEvent(it,3.0,team=team),inTeam(tokenEvent(it,100,100,0,0),team))
        } }
        seedPoints(rows)
        val bearer = token()
        for (scale in listOf(1,2,3)) {
            val params = """{"from":"2026-09-01","to":"2026-09-02","budget_by_team":{"${teams[0]}":{"usd":${7.5*scale}},"${teams[1]}":{"tokens_m":${0.0005*scale}}}}"""
            val id = mapper.readTree(startRun(bearer,"S1-1",params).andExpect(status().isAccepted)
                .andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
            val result = run["result"]
            assertThat(result["target_page"].asString()).isEqualTo("P1")
            assertThat(result["applied_filters"]["filters"]["team_ids"].toList().map { it.asString() })
                .containsExactlyInAnyOrder(teams[0].toString(),teams[1].toString())
            assertThat(result["frames"].propertyNames()).containsExactlyInAnyOrder("tokens","cost","adoption_rate")
            assertThat(result["frames"]["cost"]["frames"].size()).isEqualTo(2)
            assertThat(result["findings"].size()).isEqualTo(if(scale==1) 2 else 0)
            if(scale==1) {
                val findings = result["findings"].toList()
                assertThat(findings.map { it["evidence"]["ratio"].asDouble() }).containsOnly(2.0)
                assertThat(findings.map { it["evidence"]["unit"].asString() }).containsExactlyInAnyOrder("USD","million_tokens")
                assertThat(findings.map { it["rule_id"].asString() }).containsOnly("budget_exceeded")
            }
        }
    }

    @Test fun `예산 시나리오는 소집단 미관측을 경고하지 않고 다른 팀 예산을 거부한다`() {
        val teams = costTeams()
        val ids = installations(4,teams[0])
        seedPoints(ids.map { costEvent(it,100.0,team=teams[0]) })
        val bearer = token()
        val params = """{"from":"2026-09-01","to":"2026-09-02","budget_by_team":{"${teams[0]}":{"usd":1},"${teams[1]}":{"tokens_m":1}}}"""
        val id = mapper.readTree(startRun(bearer,"S1-1",params).andExpect(status().isAccepted)
            .andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val run = readRun(id,bearer)
        assertThat(run["status"].asString()).isEqualTo("succeeded")
        assertThat(run["result"]["findings"].size()).isZero()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)")
            .param("team",teams[0]).param("member",member).update()
        startRun(token(),"S1-1",params).andExpect(status().isForbidden)
        startRun(token(),"S1-1","""{"budget_by_team":{"${teams[0]}":{"usd":0}}}""").andExpect(status().isBadRequest)
    }

    @Test fun `에이전트 시나리오는 실패율 임계값과 네 지표를 실행한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(toolEvent(it,true),toolEvent(it,false),toolEvent(it,null),costEvent(it,3.0)) })
        val bearer = token()
        for (threshold in listOf(0.1,0.5,0.9)) {
            val id = mapper.readTree(startRun(bearer,"S7-1",
                """{"from":"2026-09-01","to":"2026-09-02","failure_threshold":$threshold}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["step"].asInt()).isEqualTo(4)
            assertThat(run["progress"]["total"].asInt()).isEqualTo(4)
            val result = run["result"]
            assertThat(result["frames"].propertyNames()).containsExactlyInAnyOrder("tool_failure_rate","tool_calls","subagent_activity","cost")
            assertThat(result["findings"].size()).isEqualTo(if(threshold==0.1) 1 else 0)
            if(threshold==0.1) {
                assertThat(result["findings"][0]["rule_id"].asString()).isEqualTo("high_tool_failure_rate")
                assertThat(result["findings"][0]["evidence"]["ratio"].asDouble()).isEqualTo(.5)
            }
            assertThat(result["frames"]["tool_calls"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(15.0)
            assertThat(result["frames"]["cost"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(15.0)
        }
        startRun(bearer,"S7-1","""{"failure_threshold":1.01}""").andExpect(status().isBadRequest)
    }

    @Test fun `에이전트 시나리오는 소집단과 성공 여부 미관측을 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for(rows in listOf(ids.take(4).map { toolEvent(it,false) },ids.map { toolEvent(it,null) })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S7-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["params"]["failure_threshold"].asDouble()).isEqualTo(.05)
            assertThat(run["result"]["findings"].size()).isZero()
            assertThat(run["result"]["frames"]["tool_failure_rate"]["frames"][0]["data"]["values"][1][0].isNull).isTrue()
        }
    }

    @Test fun `에이전트 시나리오도 큐 대기 중 관리자 팀 권한 회수를 반영한다`() {
        val team = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'실행 팀')").param("id",team).param("tenant",tenant).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)").param("team",team).param("member",member).update()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        val bearer = token()
        val id = mapper.readTree(startRun(bearer,"S7-1","""{"from":"2026-09-01","to":"2026-09-02"}""")
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=now() WHERE member_id=:id").param("id",member).update()
        scenarioRuns.runOne()
        val row = runStore.get(tenant,UUID.fromString(id))!!
        assertThat(row.status).isEqualTo("failed")
        assertThat(row.result).isNull()
        mvc.perform(get("/v1/scenario-runs/$id").header("Authorization","Bearer $bearer")).andExpect(status().isNotFound)
    }

    @Test fun `컨텍스트 시나리오는 세 지표와 임계값 초과 판정을 실행한다`() {
        val ids = installations(5)
        seedPoints(ids.flatMap { listOf(tokenEvent(it,200,10,0,0),compactionEvent(it,100,25)) })
        val bearer = token()
        for (threshold in listOf(10,20,30)) {
            val id = mapper.readTree(startRun(bearer,"S1-5",
                """{"from":"2026-09-01","to":"2026-09-02","io_ratio_threshold":$threshold}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["progress"]["total"].asInt()).isEqualTo(3)
            assertThat(run["progress"]["step"].asInt()).isEqualTo(3)
            val result = run["result"]
            assertThat(result["target_page"].asString()).isEqualTo("P2")
            assertThat(result["frames"].propertyNames()).containsExactlyInAnyOrder("input_output_ratio","compactions","compaction_reduction")
            assertThat(result["findings"].size()).isEqualTo(if(threshold==10) 1 else 0)
            if(threshold==10) {
                assertThat(result["findings"][0]["rule_id"].asString()).isEqualTo("high_io_ratio")
                assertThat(result["findings"][0]["evidence"]["ratio"].asDouble()).isEqualTo(20.0)
            }
            assertThat(result["frames"]["compactions"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(5.0)
            assertThat(result["frames"]["compaction_reduction"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(.75)
        }
    }

    @Test fun `컨텍스트 시나리오는 소집단과 분모 영을 판정하지 않는다`() {
        val ids = installations(5)
        val bearer = token()
        for (rows in listOf(ids.take(4).map { tokenEvent(it,200,1,0,0) },ids.map { tokenEvent(it,200,0,0,0) })) {
            seedPoints(rows)
            val id = mapper.readTree(startRun(bearer,"S1-5","""{"from":"2026-09-01","to":"2026-09-02"}""")
                .andExpect(status().isAccepted).andReturn().response.contentAsString)["run_id"].asString()
            scenarioRuns.runOne()
            val run = readRun(id,bearer)
            assertThat(run["status"].asString()).isEqualTo("succeeded")
            assertThat(run["params"]["io_ratio_threshold"].asDouble()).isEqualTo(10.0)
            assertThat(run["result"]["findings"].size()).isZero()
            assertThat(run["result"]["frames"]["input_output_ratio"]["frames"][0]["data"]["values"][1][0].isNull).isTrue()
        }
    }

    @Test fun `실제 비용 시나리오는 큐 워커 결과 조회까지 연결된다`() {
        val team = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'실행 팀')").param("id",team).param("tenant",tenant).update()
        val ids = installations(5,team)
        seedPoints(ids.flatMap { id -> listOf(costEvent(id,40.0,team=team)) + listOf("2026-08-29","2026-08-30","2026-08-31").map { costEvent(id,10.0,"${it}T12:00:00Z") } })
        val bearer = token()
        val response = startRun(bearer).andExpect(status().isAccepted).andReturn().response
        val queued = mapper.readTree(response.contentAsString)
        val id = queued["run_id"].asString()
        assertThat(response.getHeader("Location")).isEqualTo("/v1/scenario-runs/$id")
        assertThat(response.getHeader("Retry-After")).isEqualTo("2")
        assertThat(queued["status"].asString()).isEqualTo("queued")
        scenarioRuns.runOne()
        val completed = readRun(id,bearer)
        assertThat(completed["status"].asString()).isEqualTo("succeeded")
        assertThat(completed["progress"]["step"].asInt()).isEqualTo(4)
        assertThat(completed["findings_count"]["anomaly"].asInt()).isEqualTo(1)
        assertThat(completed["result"]["frames"]["cost"]["frames"][0]["data"]["values"][1][0].asDouble()).isEqualTo(200.0)
        val teamFrames = completed["result"]["frames"]["cost"]["frames"].toList().filter { it["schema"]["frame_type"].asString()=="table" }
        assertThat(teamFrames).isNotEmpty()
        assertThat(teamFrames.sumOf { it["data"]["values"][0][0].asDouble() }).isEqualTo(200.0)
        val finding = completed["result"]["findings"].first { it["rule_id"].asString()=="spike_day" }
        assertThat(finding["evidence"]["ratio"].asDouble()).isEqualTo(3.0)
        mvc.perform(post("/v1/scenario-runs/$id/cancel").header("Authorization","Bearer $bearer")).andExpect(status().isConflict)
    }

    @Test fun `마스킹된 비용 시나리오는 수치 판정 근거를 만들지 않는다`() {
        val team = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'실행 팀')").param("id",team).param("tenant",tenant).update()
        val ids = installations(4,team)
        seedPoints(ids.flatMap { id -> listOf(costEvent(id,40.0,team=team)) + listOf("2026-08-29","2026-08-30","2026-08-31").map { costEvent(id,10.0,"${it}T12:00:00Z") } })
        val bearer = token()
        val id = mapper.readTree(startRun(bearer).andReturn().response.contentAsString)["run_id"].asString()
        scenarioRuns.runOne()
        val result = readRun(id,bearer)["result"]
        val tables = result["frames"]["cost"]["frames"].toList().filter { it["schema"]["frame_type"].asString()=="table" }
        assertThat(tables).hasSize(1)
        assertThat(tables[0]["data"]["values"][0][0].isNull).isTrue()
        assertThat(result["findings"].size()).isZero()
        assertThat(result["frames"]["cost"]["frames"][0]["data"]["values"][1][0].isNull).isTrue()
    }

    @Test fun `실행 입력 오류 불가 미지원과 동시 상한을 HTTP에서 구분한다`() {
        val bearer = token()
        startRun(bearer,params="""{"moving_avg_days":"7"}""").andExpect(status().isBadRequest)
        mvc.perform(get("/v1/scenario-runs/not-a-uuid").header("Authorization","Bearer $bearer")).andExpect(status().isBadRequest)
        mvc.perform(post("/v1/scenarios/S1-3/runs").param("wait","invalid").header("Authorization","Bearer $bearer")
            .contentType("application/json").content("""{"params":{}}""")).andExpect(status().isBadRequest)

        startRun(bearer,"S5-1", "{}").andExpect(status().isConflict)
        startRun(bearer,"S8-2", """{"from":"now-1d"}""").andExpect(status().isBadRequest)
        startRun(bearer,"S9-1", "{}").andExpect(status().isNotFound)
        repeat(3) { startRun(bearer).andExpect(status().isAccepted) }
        startRun(bearer).andExpect(status().isTooManyRequests)
        mvc.perform(post("/v1/scenarios/S1-3/runs").contentType("application/json").content("""{"params":{}}"""))
            .andExpect(status().isUnauthorized)
    }

    @Test fun `동시 admission은 여러 연결에서도 세 개만 허용한다`() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val tasks = (1..8).map { pool.submit<Boolean> {
                gate.await()
                runStore.enqueue(UUID.randomUUID(),tenant,"S1-3","{}","{}",clock.instant().minusSeconds(60),clock.instant(),member)
            } }
            gate.countDown()
            assertThat(tasks.count { it.get(10,java.util.concurrent.TimeUnit.SECONDS) }).isEqualTo(3)
        } finally { pool.shutdownNow() }
    }

    @Test fun `취소와 lease 만료는 늦은 워커 결과를 차단한다`() {
        val bearer = token()
        val id = mapper.readTree(startRun(bearer).andReturn().response.contentAsString)["run_id"].asString()
        val claimed = requireNotNull(runStore.claim())
        mvc.perform(post("/v1/scenario-runs/$id/cancel").header("Authorization","Bearer $bearer")).andExpect(status().isOk)
        assertThat(runStore.finish(claimed,"{}",null)).isFalse()
        assertThat(runStore.progress(claimed,1)).isFalse()
        assertThat(readRun(id,bearer)["status"].asString()).isEqualTo("cancelled")
        val expiredId = mapper.readTree(startRun(bearer).andReturn().response.contentAsString)["run_id"].asString()
        val expired = requireNotNull(runStore.claim())
        jdbc.sql("UPDATE dashboard.scenario_runs SET lease_until=now()-interval '1 second' WHERE id=:id")
            .param("id",UUID.fromString(expiredId)).update()
        assertThat(runStore.finish(expired,"{}",null)).isFalse()
        assertThat(runStore.claim()).isNull()
        assertThat(readRun(expiredId,bearer)["error"]["error"].asString()).isEqualTo("worker_lease_expired")
    }

    @Test fun `실행 중 계정 변경은 실패 처리하고 다른 tenant 조회는 숨긴다`() {
        val bearer = token()
        val id = mapper.readTree(startRun(bearer).andReturn().response.contentAsString)["run_id"].asString()
        jdbc.sql("UPDATE enrollment.members SET status='suspended' WHERE id=:id").param("id",member).update()
        scenarioRuns.runOne()
        val failed = requireNotNull(runStore.get(tenant,UUID.fromString(id)))
        assertThat(failed.status).isEqualTo("failed")
        assertThat(mapper.readTree(failed.error)["error"].asString()).isEqualTo("forbidden")
        assertThat(failed.result).isNull()
        assertThatThrownBy { scenarioRuns.get(UUID.fromString(id),com.team376.pulsemetry.security.user.UserIdentity(
            member,UUID.randomUUID(),"owner",UUID.randomUUID(),null,"web")) }.hasMessage("not_found")
    }

    @Test fun `admin 실행의 팀 범위는 큐 대기 이후에도 재검증한다`() {
        val team = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name) VALUES (:id,:tenant,'실행 팀')").param("id",team).param("tenant",tenant).update()
        jdbc.sql("INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES (:team,:member)").param("team",team).param("member",member).update()
        jdbc.sql("UPDATE enrollment.members SET role='admin' WHERE id=:id").param("id",member).update()
        val bearer = token()
        val queued = mapper.readTree(startRun(bearer).andExpect(status().isAccepted).andReturn().response.contentAsString)
        assertThat(queued["params"]["team_ids"][0].asString()).isEqualTo(team.toString())
        val id = queued["run_id"].asString()
        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=now() WHERE member_id=:id").param("id",member).update()
        mvc.perform(get("/v1/scenario-runs/$id").header("Authorization","Bearer $bearer")).andExpect(status().isNotFound)
        scenarioRuns.runOne()
        assertThat(runStore.get(tenant,UUID.fromString(id))!!.status).isEqualTo("failed")
        assertThat(runStore.get(tenant,UUID.fromString(id))!!.result).isNull()
    }

    @Test fun `지표 조회 오류는 일부 결과를 성공으로 저장하지 않는다`() {
        val ids = installations(5)
        seedPoints(ids.map { costEvent(it,10.0) })
        discountContract()
        discountContract()
        val bearer = token()
        val queued = mapper.readTree(mvc.perform(post("/v1/scenarios/S1-3/runs").header("Authorization","Bearer $bearer")
            .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02","moving_avg_days":3},"price_basis":"contract"}"""))
            .andExpect(status().isAccepted).andReturn().response.contentAsString)
        scenarioRuns.runOne()
        val failed = readRun(queued["run_id"].asString(),bearer)
        assertThat(failed["status"].asString()).isEqualTo("failed")
        assertThat(failed["result"].isNull).isTrue()
        assertThat(failed["error"]["error"].asString()).isEqualTo("contract_overlap")
    }

    @Test fun `wait 요청은 워커가 완료하면 결과를 200으로 반환한다`() {
        seedPoints(emptyList())
        val bearer = token()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<org.springframework.test.web.servlet.MvcResult> {
                mvc.perform(post("/v1/scenarios/S1-3/runs").param("wait","true").header("Authorization","Bearer $bearer")
                    .contentType("application/json").content("""{"params":{"from":"2026-09-01","to":"2026-09-02"}}"""))
                    .andExpect(status().isOk).andReturn()
            }
            val deadline = System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos()
            while(jdbc.sql("SELECT count(*) FROM dashboard.scenario_runs").query(Long::class.java).single()==0L && System.nanoTime()<deadline)
                Thread.sleep(20)
            scenarioRuns.runOne()
            assertThat(mapper.readTree(future.get(10,java.util.concurrent.TimeUnit.SECONDS).response.contentAsString)["status"].asString())
                .isEqualTo("succeeded")
        } finally { executor.shutdownNow() }
    }

}
