package com.team376.pulsemetry.enrollment.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.net.URI

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["pulsemetry.user-auth.enabled=true"])
class OidcLoginApiTest : OidcTestSupport() {
    override val testIssuer get() = provider.issuer
    @BeforeEach fun resetProvider() {
        provider.fault = MockOidcProvider.Fault.NONE
        provider.email = email
        provider.subject = testSubject
    }

    @Test fun `OIDC 왕복 PKCE와 nonce 검증 후 일회성 code로만 토큰을 교환한다`() {
        val start = begin()
        assertThat(query(start.location)).doesNotContainKey("login_hint")
        val callback = provider.authorize(start.location)
        assertTokens(callback(callback, start.cookie))
        assertFailure(callback(callback, start.cookie), "login_expired", false)
    }

    @Test fun `등록된 이메일 힌트를 정규화하고 특수문자를 보존하여 전달한다`() {
        sql("UPDATE enrollment.members SET email='other+demo@example.com'")
        provider.email = "other+demo@example.com"
        val start = begin(" Other+demo@Example.com ")
        assertThat(query(start.location)["login_hint"]).isEqualTo("other+demo@example.com")
        // 실제 신원은 서명된 IdP 응답으로 확인한다.
        assertTokens(callback(provider.authorize(start.location), start.cookie))
    }

    @Test fun `잘못되거나 중복된 로그인 힌트는 IdP로 전달하지 않는다`() {
        val invalidHints = listOf(arrayOf(""), arrayOf("not-email"), arrayOf("a".repeat(250) + "@example.com"),
            arrayOf("a@b.com", "other@b.com"), arrayOf("a\u0000@b.com"))
        for (hints in invalidHints) {
            val response = mvc.perform(get("/api/v1/auth/oidc/authorize").param("tenant_id", tenant.toString())
                .param("redirect_uri", redirect).param("state", "client-state-1234567890")
                .param("code_challenge", challenge).param("code_challenge_method", "S256")
                .param("login_hint", *hints)).andReturn().response
            assertThat(response.status).isEqualTo(400)
            assertThat(response.getHeader("Location")).isNull()
        }
    }

    @Test fun `로그인 중 회사 설정이 바뀌면 기존 callback을 거부한다`() {
        val start = begin()
        sql("UPDATE enrollment.tenants SET oidc_client_id='changed-client'")
        assertThat(callback(provider.authorize(start.location), start.cookie).response.status).isEqualTo(401)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.user_authorization_codes").query(Long::class.java).single()).isZero()
    }

    @Test fun `비밀 참조가 준비되지 않은 회사는 인증 장애로 반환한다`() {
        sql("UPDATE enrollment.tenants SET oidc_client_secret_ref='config:missing'")
        val response = mvc.perform(get("/api/v1/auth/oidc/authorize").param("tenant_id", tenant.toString())
            .param("redirect_uri", redirect).param("state", "client-state-1234567890")
            .param("code_challenge", challenge).param("code_challenge_method", "S256")).andReturn().response
        assertThat(response.status).isEqualTo(503)
    }

    @ParameterizedTest
    @EnumSource(value = MockOidcProvider.Fault::class, names = ["ISSUER", "AUDIENCE", "NONCE", "EXPIRED", "SIGNATURE", "EMAIL_UNVERIFIED", "EMAIL_MISSING", "EMAIL_VERIFICATION_MISSING"])
    fun `잘못된 ID token은 로그인하지 못한다`(fault: MockOidcProvider.Fault) {
        provider.fault = fault
        val start = begin()
        assertFailure(callback(provider.authorize(start.location), start.cookie), "invalid_credentials")
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.user_authorization_codes").query(Long::class.java).single()).isZero()
    }

    @Test fun `IdP token endpoint 장애는 프론트에 안전한 장애 코드로 복귀한다`() {
        provider.fault = MockOidcProvider.Fault.UNAVAILABLE
        val start = begin()
        assertFailure(callback(provider.authorize(start.location), start.cookie), "auth_unavailable")
    }

    @Test fun `state 변조와 쿠키 없는 callback은 거부한다`() {
        val start = begin()
        val uri = provider.authorize(start.location)
        assertFailure(callback(uri), "login_expired", false)
        val altered = URI(uri.toString().substringBefore("&state=") + "&state=forged")
        assertFailure(callback(altered, start.cookie), "invalid_credentials")
    }

    @Test fun `DB 사전 등록되지 않은 subject는 이메일이 같아도 거부한다`() {
        sql("UPDATE enrollment.members SET oidc_subject='someone-else'")
        val start = begin()
        assertFailure(callback(provider.authorize(start.location), start.cookie), "member_not_allowed")
    }

    @Test fun `비활성 회원은 IdP 인증 성공 후에도 거부한다`() {
        sql("UPDATE enrollment.members SET status='suspended'")
        val start = begin()
        assertFailure(callback(provider.authorize(start.location), start.cookie), "member_not_allowed")
    }

    @Test fun `비활성 registration은 이메일 탐색과 로그인 시작에서 모두 제외한다`() {
        sql("UPDATE enrollment.tenants SET sso_enabled=false")
        assertThat(mapper.readTree(post("organizations", mapOf("email" to email)).body()).path("organizations").size()).isZero()
        val response = mvc.perform(get("/api/v1/auth/oidc/authorize").param("tenant_id", tenant.toString())
            .param("redirect_uri", redirect).param("state", "client-state-1234567890")
            .param("code_challenge", challenge).param("code_challenge_method", "S256")).andReturn().response
        assertThat(response.status).isEqualTo(403)
    }

    @Test fun `검증 이메일 요구가 없는 사전 연결 제공자는 누락 클레임으로도 인증한다`() {
        sql("UPDATE enrollment.tenants SET oidc_require_verified_email=false")
        provider.fault = MockOidcProvider.Fault.EMAIL_VERIFICATION_MISSING
        val start = begin()
        assertTokens(callback(provider.authorize(start.location), start.cookie))
    }

    @Test fun `잘못된 시작 요청은 외부 URL로 리다이렉트하지 않는다`() {
        for (target in listOf("https://evil.example/callback", "$redirect?next=evil")) {
            val response = mvc.perform(get("/api/v1/auth/oidc/authorize").param("tenant_id", tenant.toString())
                .param("redirect_uri", target).param("state", "client-state-1234567890")
                .param("code_challenge", challenge).param("code_challenge_method", "S256")).andReturn().response
            assertThat(response.status).isEqualTo(400)
            assertThat(response.getHeader("Location")).isNull()
        }
    }

    @Test fun `OIDC 임시 쿠키만으로 업무 API에 접근할 수 없다`() {
        val start = begin()
        assertThat(mvc.perform(get("/api/v1/auth/me").cookie(start.cookie)).andReturn().response.status).isEqualTo(401)
    }

    @Test fun `만료된 JDBC 왕복 세션은 callback을 수용하지 않는다`() {
        val start = begin()
        sql("UPDATE enrollment.oidc_login_sessions SET creation_time=0,last_access_time=0,expiry_time=600000")
        assertFailure(callback(provider.authorize(start.location), start.cookie), "login_expired", false)
    }

    @Test fun `알 수 없는 조직이나 중복 필수 파라미터는 시작을 거부한다`() {
        fun request() = get("/api/v1/auth/oidc/authorize").param("redirect_uri", redirect)
            .param("state", "client-state-1234567890").param("code_challenge", challenge).param("code_challenge_method", "S256")
        assertThat(mvc.perform(request().param("tenant_id", java.util.UUID.randomUUID().toString())).andReturn().response.status).isEqualTo(403)
        assertThat(mvc.perform(request().param("tenant_id", tenant.toString(), tenant.toString())).andReturn().response.status).isEqualTo(400)
        assertThat(mvc.perform(request().param("tenant_id", tenant.toString()).param("state", "another-state-1234567")).andReturn().response.status).isEqualTo(400)
    }

    @Test fun `IdP 취소는 원래 client state와 함께 복귀하고 세션을 폐기한다`() {
        val start = begin()
        val upstreamState = query(start.location).getValue("state")
        assertFailure(callback(URI("http://localhost:8080/api/v1/auth/oidc/callback/mock?error=access_denied&state=$upstreamState&redirect_uri=https://evil.test"), start.cookie), "login_cancelled")
    }

    private fun assertFailure(result: org.springframework.test.web.servlet.MvcResult, code: String, hasState: Boolean = true) {
        val response = result.response
        assertThat(response.status).isEqualTo(302)
        val location = URI(response.getHeader("Location")!!)
        assertThat(location.toString()).startsWith(if (hasState) "$redirect?" else "http://localhost:3000/auth/callback?")
        assertThat(query(location)["error"]).isEqualTo(code)
        if (hasState) assertThat(query(location)["state"]).isEqualTo("client-state-1234567890")
        else assertThat(query(location)).doesNotContainKey("state")
        assertThat(query(location)).doesNotContainKeys("code", "access_token", "error_description")
        if (hasState) assertThat(jdbc.sql("SELECT count(*) FROM enrollment.oidc_login_sessions").query(Long::class.java).single()).isZero()
    }

    @Test fun `이메일 탐색은 여러 회사의 최소 정보만 반환하며 인증하지 않는다`() {
        val other = data.tenant().id
        val otherMember = data.member(other, email).id
        jdbc.sql("UPDATE enrollment.members SET oidc_subject='other' WHERE id=:id")
            .param("id", otherMember).update()
        configureTenant(other)
        val response = post("organizations", mapOf("email" to " USER@EXAMPLE.COM "))
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
        val items = mapper.readTree(response.body()).path("organizations")
        assertThat(items.size()).isEqualTo(2)
        assertThat((0 until items.size()).map { items[it].path("organizationId").asString() }).containsExactlyInAnyOrder(tenant.toString(), other.toString())
        assertThat(items.all { it.size() == 2 && it.has("organizationName") }).isTrue()
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.user_sessions").query(Long::class.java).single()).isZero()
    }

    @Test fun `초대와 미연결 활성 회원은 탐색되며 미등록 정지 미설정 회사는 제외한다`() {
        assertThat(mapper.readTree(post("organizations", mapOf("email" to "unknown@example.com")).body()).path("organizations").size()).isZero()
        for (change in listOf("status='suspended'")) {
            sql("UPDATE enrollment.members SET $change")
            assertThat(mapper.readTree(post("organizations", mapOf("email" to email)).body()).path("organizations").size()).isZero()
        }
        for (status in listOf("invited", "active")) {
            sql("UPDATE enrollment.members SET status='$status',oidc_subject=NULL")
            assertThat(mapper.readTree(post("organizations", mapOf("email" to email)).body()).path("organizations").size()).isEqualTo(1)
        }
        bindIdentity()
        sql("UPDATE enrollment.tenants SET sso_enabled=false")
        assertThat(mapper.readTree(post("organizations", mapOf("email" to email)).body()).path("organizations").size()).isZero()
    }

    @Test fun `탐색 입력 검사와 IP 제한 및 CORS`() {
        assertThat(post("organizations", mapOf("email" to "not-email")).statusCode()).isEqualTo(400)
        val preflight = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/auth/organizations")
            .header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type")).andReturn().response
        assertThat(preflight.status).isEqualTo(200)
        assertThat(preflight.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000")
        repeat(29) { assertThat(post("organizations", mapOf("email" to email)).statusCode()).isEqualTo(200) }
        assertThat(post("organizations", mapOf("email" to email)).statusCode()).isEqualTo(429)
    }

    @Test fun `초대 회원의 최초 로그인은 sub와 활성 상태만 저장하고 재로그인한다`() {
        sql("UPDATE enrollment.members SET status='invited',oidc_subject=NULL")
        val original = jdbc.sql("SELECT id,tenant_id,email,role FROM enrollment.members WHERE id=:id").param("id", member).query().singleRow()
        val invites = jdbc.sql("SELECT * FROM enrollment.invitations").query().listOfRows()
        val start = begin(" USER@EXAMPLE.COM ")
        assertTokens(callback(provider.authorize(start.location), start.cookie))
        assertThat(jdbc.sql("SELECT oidc_subject FROM enrollment.members WHERE id=:id").param("id", member).query(String::class.java).single()).isEqualTo(testSubject)
        assertThat(jdbc.sql("SELECT status::text FROM enrollment.members WHERE id=:id").param("id", member).query(String::class.java).single()).isEqualTo("active")
        assertThat(jdbc.sql("SELECT id,tenant_id,email,role FROM enrollment.members WHERE id=:id").param("id", member).query().singleRow()).isEqualTo(original)
        assertThat(jdbc.sql("SELECT * FROM enrollment.invitations").query().listOfRows()).isEqualTo(invites)
        val again = begin(email)
        assertTokens(callback(provider.authorize(again.location), again.cookie))
    }

    @Test fun `활성 미연결 회원도 최초 로그인에서 연결한다`() {
        sql("UPDATE enrollment.members SET oidc_subject=NULL")
        val start = begin(email)
        assertTokens(callback(provider.authorize(start.location), start.cookie))
    }

    @Test fun `미등록 이메일로 authorize를 직접 호출해도 IdP로 이동하지 않는다`() {
        val response = mvc.perform(get("/api/v1/auth/oidc/authorize").param("tenant_id", tenant.toString())
            .param("redirect_uri", redirect).param("state", "client-state-1234567890")
            .param("code_challenge", challenge).param("code_challenge_method", "S256")
            .param("login_hint", "unknown@example.com")).andReturn().response
        assertThat(response.status).isEqualTo(403)
        assertThat(response.getHeader("Location")).isNull()
    }

    @Test fun `최초 연결은 힌트만으로 또는 대상 회원 없는 왕복으로 허용하지 않는다`() {
        sql("UPDATE enrollment.members SET status='invited',oidc_subject=NULL")
        val noHint = begin()
        assertFailure(callback(provider.authorize(noHint.location), noHint.cookie), "member_not_allowed")
        val start = begin(email)
        provider.email = "someone-else@example.com"
        assertFailure(callback(provider.authorize(start.location), start.cookie), "member_not_allowed")
        assertUnlinked()
    }

    @ParameterizedTest
    @EnumSource(value = MockOidcProvider.Fault::class, names = ["EMAIL_UNVERIFIED", "EMAIL_MISSING", "EMAIL_VERIFICATION_MISSING"])
    fun `기존 연결의 이메일 검증 옵션을 꺼도 최초 연결에는 검증 이메일이 필요하다`(fault: MockOidcProvider.Fault) {
        sql("UPDATE enrollment.members SET status='invited',oidc_subject=NULL")
        sql("UPDATE enrollment.tenants SET oidc_require_verified_email=false")
        provider.fault = fault
        val start = begin(email)
        assertFailure(callback(provider.authorize(start.location), start.cookie), "member_not_allowed")
        assertUnlinked()
    }

    @Test fun `로그인 중 회원 정지와 이메일 변경은 연결을 거부한다`() {
        for (change in listOf("status='suspended'", "email='changed@example.com'")) {
            sql("UPDATE enrollment.members SET status='invited',oidc_subject=NULL,email='user@example.com'")
            val start = begin(email)
            sql("UPDATE enrollment.members SET $change")
            assertFailure(callback(provider.authorize(start.location), start.cookie), "member_not_allowed")
            assertThat(jdbc.sql("SELECT count(*) FROM enrollment.members WHERE oidc_subject IS NOT NULL").query(Long::class.java).single()).isZero()
        }
    }

    @Test fun `같은 이메일의 새 sub나 다른 등록 회원의 sub로 연결을 교체하지 않는다`() {
        val other = data.member(tenant, "other@example.com").id
        jdbc.sql("UPDATE enrollment.members SET status='active',oidc_subject='other-sub' WHERE id=:id").param("id", other).update()
        for (subject in listOf("new-sub", "other-sub")) {
            provider.subject = subject
            val start = begin(email)
            assertFailure(callback(provider.authorize(start.location), start.cookie), "member_not_allowed")
        }
        assertThat(jdbc.sql("SELECT oidc_subject FROM enrollment.members WHERE id=:id").param("id", member).query(String::class.java).single()).isEqualTo(testSubject)
    }

    @Test fun `다른 회원에 연결된 sub는 최초 연결도 거부하고 활성화를 롤백한다`() {
        sql("UPDATE enrollment.members SET status='invited',oidc_subject=NULL")
        val other = data.member(tenant, "other@example.com").id
        jdbc.sql("UPDATE enrollment.members SET status='active',oidc_subject=:sub WHERE id=:id").param("id", other).param("sub", testSubject).update()
        val start = begin(email)
        assertFailure(callback(provider.authorize(start.location), start.cookie), "member_not_allowed")
        assertUnlinked()
    }

    @Test fun `대소문자만 다른 중복 이메일은 조회와 최초 연결에서 거부한다`() {
        sql("UPDATE enrollment.members SET status='invited',oidc_subject=NULL")
        val start = begin(email)
        data.member(tenant, "USER@example.com")
        assertThat(mapper.readTree(post("organizations", mapOf("email" to email)).body()).path("organizations").size()).isZero()
        assertFailure(callback(provider.authorize(start.location), start.cookie), "member_not_allowed")
        assertUnlinked()
    }

    @Test fun `다른 회사나 issuer로 동일 이메일 회원을 최초 연결할 수 없다`() {
        sql("UPDATE enrollment.members SET status='invited',oidc_subject=NULL")
        val other = data.tenant().id
        configureTenant(other)
        for ((selectedTenant, issuer) in listOf(other to testIssuer, tenant to "https://wrong.example.com")) {
            org.assertj.core.api.Assertions.assertThatThrownBy {
                auth.authorizeOidc(selectedTenant, issuer, testSubject, redirect, "client-state-1234567890", challenge,
                    "S256", member, email, email)
            }.isInstanceOf(com.team376.pulsemetry.security.user.UserAuthException::class.java).hasMessage("member_not_allowed")
        }
        assertUnlinked()
    }

    @Test fun `동시 최초 연결은 서로 다른 sub 중 하나만 저장하고 같은 sub는 재사용한다`() {
        for (subjects in listOf(listOf("sub-a", "sub-b"), listOf("sub-a", "sub-a"))) {
            sql("DELETE FROM enrollment.user_authorization_codes")
            sql("UPDATE enrollment.members SET status='invited',oidc_subject=NULL")
            val gate = java.util.concurrent.CyclicBarrier(2)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val tasks = subjects.map { subject -> executor.submit<Boolean> {
                    gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    try {
                        auth.authorizeOidc(tenant, testIssuer, subject, redirect, "client-state-1234567890", challenge,
                            "S256", member, email, email)
                        true
                    } catch (e: com.team376.pulsemetry.security.user.UserAuthException) {
                        assertThat(e.code).isEqualTo("member_not_allowed")
                        false
                    }
                } }
                val successes = tasks.count { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }
                assertThat(successes).isEqualTo(if (subjects.distinct().size == 1) 2 else 1)
                assertThat(jdbc.sql("SELECT count(*) FROM enrollment.user_authorization_codes").query(Long::class.java).single()).isEqualTo(successes.toLong())
            } finally { executor.shutdownNow() }
        }
    }

    private fun assertUnlinked() {
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.members WHERE id=:id AND oidc_subject IS NULL AND status='invited'")
            .param("id", member).query(Long::class.java).single()).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.user_authorization_codes").query(Long::class.java).single()).isZero()
    }

    companion object {
        private val provider = MockOidcProvider()
        @JvmStatic @DynamicPropertySource fun oidc(registry: DynamicPropertyRegistry) = properties(registry, provider.issuer)
        @JvmStatic @AfterAll fun stopProvider() = provider.close()
    }
}
