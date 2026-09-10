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
    @Test fun `manifest 없는 owner 로그인 후 사용자 정보 조회하고 폐기하면 즉시 거부한다`() {
        val token = token()
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
        for (path in listOf("members", "contracts", "manifests")) {
            mvc.perform(get("/v1/meta/$path").header("Authorization", "Bearer $token")).andExpect(status().isOk)
        }
        mvc.perform(get("/v1/meta/members").param("team_id", other.toString()).header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden)
        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=now()").update()
        val empty = mvc.perform(get("/v1/meta/teams").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(mapper.readTree(empty)["items"].size()).isZero()
    }
    companion object {
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
            r.add("pulsemetry.dashboard.tenant-id") { tenant.toString() }
            r.add("pulsemetry.dashboard.issuer") { "https://auth.test" }
            r.add("pulsemetry.dashboard.active-kid") { "test" }
            r.add("pulsemetry.dashboard.private-key-file") { privateFile }
            r.add("pulsemetry.dashboard.public-key-files.test") { publicFile }
        }
    }
}
