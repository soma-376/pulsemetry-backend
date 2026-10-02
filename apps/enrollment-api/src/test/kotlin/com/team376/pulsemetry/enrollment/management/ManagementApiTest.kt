package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig

import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** 실제 PostgreSQL·HTTP로 검증한다. 동시 소비는 서로 다른 커넥션의 트랜잭션이다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class ManagementApiTest : AbstractUserAuthApiTest() {
    @Test fun `관리 명령은 팀 중복 생성과 오래된 버전의 배정을 거부한다`() {
        val token = adminToken()
        val key = UUID.randomUUID().toString()
        val input = mapOf("teamName" to "플랫폼")
        val created = manage("POST", "/teams", input, token, key)
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(manage("POST", "/teams", input, token, key).body()).isEqualTo(created.body())
        assertThat(manage("POST", "/teams", mapOf("teamName" to "다른 팀"), token, key).statusCode()).isEqualTo(409)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.teams").query(Int::class.java).single()).isEqualTo(1)
        val teamId = mapper.readTree(created.body()).path("teamId").asString()
        val version = jdbc.sql("SELECT updated_at FROM enrollment.members WHERE id=:id").param("id", member).query { r, _ -> r.getTimestamp(1).toInstant().toEpochMilli() }.single()
        val assignment = mapOf("assignments" to listOf(mapOf("memberId" to member, "teamId" to teamId, "expectedVersion" to version)))
        assertThat(manage("POST", "/member-team-assignments", assignment, token).statusCode()).isEqualTo(200)
        assertThat(manage("POST", "/member-team-assignments", assignment, token).statusCode()).isEqualTo(409)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.team_memberships WHERE left_at IS NULL").query(Int::class.java).single()).isEqualTo(1)
    }

    @Test fun `초대 재시도는 같은 코드를 반환하고 DB 재시도 본문은 암호화한다`() {
        val token = adminToken()
        val key = UUID.randomUUID().toString()
        val input = mapOf("invitations" to listOf(mapOf("email" to "new@example.test", "teamId" to null, "role" to "member")))
        val issued = manage("POST", "/invitations/batch", input, token, key)
        assertThat(issued.statusCode()).isEqualTo(200)
        assertThat(manage("POST", "/invitations/batch", input, token, key).body()).isEqualTo(issued.body())
        val result = mapper.readTree(issued.body()).path("results")[0]
        assertThat(result.path("status").asString()).isEqualTo("issued")
        assertThat(InvitationCode.matches(result.path("code").asString())).isTrue()
        val stored = jdbc.sql("SELECT encrypted_response FROM enrollment.management_commands WHERE idempotency_key=:key").param("key", key).query(String::class.java).single()
        assertThat(stored).doesNotContain(result.path("code").asString(), "new@example.test")
        assertThat(manage("POST", "/invitations/batch", input, token).body()).contains("already_invited")
    }

    @Test fun `계약 월 요금과 변경 이력을 보존하고 오래된 삭제를 거부한다`() {
        val token = adminToken()
        val contract = mapOf("planId" to "team", "effectiveFrom" to "2026-09-09", "effectiveTo" to null, "termNote" to "월 계약",
            "tiers" to listOf(mapOf("label" to "표준", "seats" to 2, "monthlyFeePerSeatUsd" to "30.000000")))
        val created = manage("POST", "/vendors", mapOf("kind" to "claude_team", "displayName" to "Claude", "contract" to contract), token)
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201)
        val vendor = mapper.readTree(created.body()).path("vendor")
        assertThat(vendor.path("contractStatus").asString()).isEqualTo("active")
        assertThat(vendor.path("contract").path("monthlySeatFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("60")
        val path = "/vendors/${vendor.path("vendorId").asString()}/contract"
        val input = mapOf("expectedVersion" to vendor.path("version").asLong(), "displayName" to "Claude 새 계약", "contract" to contract)
        val saved = manage("PUT", path, input, token)
        assertThat(saved.statusCode()).isEqualTo(200)
        assertThat(manage("PUT", path, input, token).statusCode()).isEqualTo(409)
        val current = mapper.readTree(saved.body()).path("vendor").path("version").asLong()
        assertThat(manage("DELETE", path, null, token, etag = "\"vendor-${vendor.path("version").asLong()}\"").statusCode()).isEqualTo(409)
        assertThat(manage("DELETE", path, null, token, etag = "\"vendor-$current\"").statusCode()).isEqualTo(204)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_contract_versions").query(Int::class.java).single()).isEqualTo(3)
    }

    @Test fun `OpenAI 복수 좌석 계약은 저장하고 단일 좌석 제품은 복수 구성을 거부한다`() {
        val token = adminToken()
        // 단가는 계산 검증용 입력이며 공급사의 공시 가격이 아니다.
        val contract = mapOf("planId" to "business", "effectiveFrom" to "2026-09-09",
            "tiers" to listOf(
                mapOf("label" to "Standard", "seats" to 10, "monthlyFeePerSeatUsd" to "20"),
                mapOf("label" to "Premium", "seats" to 2, "monthlyFeePerSeatUsd" to "100")))
        val created = manage("POST", "/vendors", mapOf("kind" to "openai_biz", "displayName" to "OpenAI", "contract" to contract), token)
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201)
        val saved = mapper.readTree(created.body()).path("vendor").path("contract")
        assertThat(saved.path("tiers").size()).isEqualTo(2)
        assertThat(saved.path("monthlySeatFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("400")
        for ((kind, plan) in listOf("copilot" to "copilot_business", "gemini" to "gemini_standard")) {
            val rejected = manage("POST", "/vendors", mapOf("kind" to kind, "displayName" to kind,
                "contract" to (contract + ("planId" to plan))), token)
            assertThat(rejected.statusCode()).withFailMessage(rejected.body()).isEqualTo(400)
            assertThat(rejected.body()).contains("contract.tiers")
        }
    }

    @Test fun `DB에 추가한 제품과 플랜으로 계약을 저장하고 비활성화 후 신규 저장을 거부한다`() {
        val token = adminToken()
        val id = "test_" + UUID.randomUUID().toString().replace("-", "")
        jdbc.sql("INSERT INTO enrollment.vendor_catalog_products(id,vendor_id,display_name,product,allows_seat_tiers) VALUES (:id,'other','새 제품','Test',false)").param("id", id).update()
        try {
            jdbc.sql("INSERT INTO enrollment.vendor_catalog_plans(product_id,id,display_name) VALUES (:id,'new_plan','새 플랜')").param("id", id).update()
            val contract = mapOf("planId" to "new_plan", "effectiveFrom" to "2026-09-09",
                "tiers" to listOf(mapOf("label" to "표준", "seats" to 3, "monthlyFeePerSeatUsd" to "10")))
            val input = mapOf("kind" to id, "displayName" to "새 제품", "contract" to contract)
            val created = manage("POST", "/vendors", input, token)
            assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201)
            val vendor = mapper.readTree(created.body()).path("vendor")
            val path = "/vendors/${vendor.path("vendorId").asString()}/contract"
            val update = input + ("expectedVersion" to vendor.path("version").asLong())
            jdbc.sql("UPDATE enrollment.vendor_catalog_plans SET active=false WHERE product_id=:id").param("id", id).update()
            val invalidPlan = manage("PUT", path, update, token)
            assertThat(invalidPlan.statusCode()).isEqualTo(422)
            assertThat(invalidPlan.body()).contains("invalid_plan")
            jdbc.sql("UPDATE enrollment.vendor_catalog_products SET active=false WHERE id=:id").param("id", id).update()
            val invalidProduct = manage("PUT", path, update, token)
            assertThat(invalidProduct.statusCode()).isEqualTo(422)
            assertThat(invalidProduct.body()).contains("invalid_vendor")
            assertThat(jdbc.sql("SELECT contract->>'planId' FROM enrollment.vendor_contract_versions WHERE vendor_id=:id")
                .param("id", vendor.path("vendorId").asString()).query(String::class.java).single()).isEqualTo("new_plan")
        } finally {
            jdbc.sql("DELETE FROM enrollment.vendor_catalog_plans WHERE product_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM enrollment.vendor_catalog_products WHERE id=:id").param("id", id).update()
        }
    }

    @Test fun `같은 제품 동시 등록은 하나만 성공하고 삭제하면 새 ID로 재등록한다`() {
        val token = adminToken()
        val input = mapOf("kind" to "claude_team", "displayName" to "Claude")
        val gate = CountDownLatch(1)
        val created = Executors.newFixedThreadPool(2).use { pool ->
            val requests = (1..2).map { pool.submit(Callable { gate.await(); manage("POST", "/vendors", input, token) }) }
            gate.countDown()
            val responses = requests.map { it.get() }
            assertThat(responses.map { it.statusCode() }).containsExactlyInAnyOrder(201, 409)
            assertThat(responses.single { it.statusCode() == 409 }.body()).contains("vendor_already_registered")
            mapper.readTree(responses.single { it.statusCode() == 201 }.body()).path("vendor")
        }
        val id = created.path("vendorId").asString()
        val initialVersion = created.path("version").asLong()
        org.assertj.core.api.Assertions.assertThatThrownBy {
            jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:tenant,:id,'claude_team','manual',now())")
                .param("tenant", tenant).param("id", UUID.randomUUID().toString()).update()
        }.isInstanceOf(org.springframework.dao.DuplicateKeyException::class.java)
        val rename = manage("PATCH", "/vendors/$id", mapOf("expectedVersion" to initialVersion, "displayName" to "팀용 Claude"), token)
        assertThat(rename.statusCode()).withFailMessage(rename.body()).isEqualTo(200)
        val renamed = mapper.readTree(rename.body()).path("vendor")
        assertThat(renamed.path("contract").isNull).isTrue()
        assertThat(renamed.path("displayName").asString()).isEqualTo("팀용 Claude")
        assertThat(manage("PATCH", "/vendors/$id", mapOf("expectedVersion" to initialVersion, "displayName" to "덮어쓰기"), token).statusCode()).isEqualTo(409)
        assertThat(manage("DELETE", "/vendors/$id", null, token, etag = "\"vendor-${renamed.path("version").asLong()}\"").statusCode()).isEqualTo(204)
        val next = manage("POST", "/vendors", input, token)
        assertThat(next.statusCode()).withFailMessage(next.body()).isEqualTo(201)
        assertThat(mapper.readTree(next.body()).at("/vendor/vendorId").asString()).isNotEqualTo(id)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_contract_versions WHERE vendor_id=:id").param("id", id).query(Int::class.java).single()).isEqualTo(3)
        assertThat(jdbc.sql("SELECT archived FROM enrollment.managed_vendors WHERE vendor_id=:id").param("id", id).query(Boolean::class.java).single()).isTrue()
    }

    @Test fun `계약 정정은 과거 시작일과 이전 값을 보존하고 현재 월 금액만 다시 계산한다`() {
        var token = adminToken()
        val contract = mapOf("planId" to "team", "effectiveFrom" to "2026-09-09", "effectiveTo" to null, "termNote" to null,
            "tiers" to listOf(mapOf("label" to "표준", "seats" to 2, "monthlyFeePerSeatUsd" to "30")))
        val created = manage("POST", "/vendors", mapOf("kind" to "claude_team", "displayName" to "Claude", "contract" to contract), token)
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201)
        val id = mapper.readTree(created.body()).at("/vendor/vendorId").asString()
        clock.now = clock.now.plusSeconds(86400)
        token = mapper.readTree(login().body()).path("access_token").asString()
        val correction = contract + mapOf("effectiveFrom" to "2026-09-10", "tiers" to listOf(mapOf("label" to "표준", "seats" to 3, "monthlyFeePerSeatUsd" to "40")))
        val saved = manage("PUT", "/vendors/$id/contract", mapOf("expectedVersion" to mapper.readTree(created.body()).at("/vendor/version").asLong(), "displayName" to "Claude", "contract" to correction), token)
        assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(200)
        val current = mapper.readTree(saved.body()).at("/vendor/contract")
        assertThat(current.path("effectiveFrom").asString()).isEqualTo("2026-09-09")
        assertThat(current.path("monthlySeatFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("120")
        val history = jdbc.sql("SELECT contract::text FROM enrollment.vendor_contract_versions WHERE vendor_id=:id ORDER BY version").param("id", id).query(String::class.java).list().map { mapper.readTree(it) }
        assertThat(history.map { it.path("monthlySeatFeeUsd").asString().toBigDecimal().stripTrailingZeros().toPlainString() }).containsExactly("60", "120")
        assertThat(history.map { it.path("effectiveFrom").asString() }).containsOnly("2026-09-09")
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.contracts").query(Int::class.java).single()).isZero()
    }

    @Test fun `일반 구성원과 다른 조직은 관리 API에 접근하지 못한다`() {
        val token = tokens().path("access_token").asString()
        assertThat(manage("POST", "/teams", mapOf("teamName" to "거부"), token).statusCode()).isEqualTo(403)
        tenant = UUID.randomUUID()
        assertThat(manage("POST", "/teams", mapOf("teamName" to "거부"), token).statusCode()).isEqualTo(404)
    }

    @Test fun `내 정보는 토큰의 조직이며 허용 origin만 CORS에 통과한다`() {
        val token = adminToken()
        val me = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/auth/me")).header("Authorization", "Bearer $token").GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(me.statusCode()).isEqualTo(200)
        assertThat(mapper.readTree(me.body()).path("organizationId").asString()).isEqualTo(tenant.toString())
        for ((origin, status) in listOf("http://localhost:3000" to 200, "https://untrusted.example" to 403)) {
            val cors = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/organizations/$tenant/teams"))
                .header("Origin", origin).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            assertThat(cors.statusCode()).isEqualTo(status)
        }
    }

    @Test fun `조직과 오너만 있어도 로그인 갱신 최초 정책 저장과 온보딩 완료가 가능하다`() {
        provisionMember()
        sql("DELETE FROM enrollment.manifests")
        sql("DELETE FROM enrollment.invitations")
        sql("UPDATE enrollment.members SET role='owner'")
        val response = login()
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        val loginTokens = mapper.readTree(response.body())
        val token = loginTokens.path("access_token").asString()
        assertThat(auth.verify(token).revision).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.manifests").query(Int::class.java).single()).isZero()
        val initial = mapper.readTree(manage("GET", "/onboarding", null, token).body())
        assertThat(initial.path("completed").asBoolean()).isFalse()
        assertThat(initial.path("policy").path("version").asInt()).isZero()
        assertThat(initial.path("policy").path("collectRawContent").isNull).isTrue()
        assertThat(initial.path("nextStep").asString()).isEqualTo("collection")
        assertThat(manage("PUT", "/collection-policy", mapOf("expectedVersion" to 1, "collectRawContent" to false), token).statusCode()).isEqualTo(409)
        val policy = manage("PUT", "/collection-policy", mapOf("expectedVersion" to 0, "collectRawContent" to true), token)
        assertThat(policy.statusCode()).withFailMessage(policy.body()).isEqualTo(200)
        assertThat(mapper.readTree(policy.body()).path("version").asInt()).isEqualTo(1)
        val manifest = mapper.readTree(jdbc.sql("SELECT manifest::text FROM enrollment.manifests WHERE tenant_id=:tenant AND is_active")
            .param("tenant", tenant).query(String::class.java).single())
        assertThat(mapper.treeToValue(manifest, com.team376.pulsemetry.enrollment.contract.ManifestPayload::class.java).satisfiesContract()).isTrue()
        assertThat(manifest.path("otlp").path("endpoint").asString()).isEqualTo("https://ingest.example.test")
        assertThat(manifest.path("privacy").path("collect_user_prompts").asBoolean()).isTrue()
        assertThat(manifest.path("privacy").path("collect_assistant_responses").asBoolean()).isTrue()
        assertThat(manifest.path("privacy").path("collect_tool_content").asBoolean()).isFalse()
        assertThat(manage("PUT", "/collection-policy", mapOf("expectedVersion" to 0, "collectRawContent" to false), token).statusCode()).isEqualTo(409)
        assertThat(manage("POST", "/onboarding/complete", emptyMap<String, String>(), token).statusCode()).isEqualTo(409)
        assertThat(manage("POST", "/vendors", mapOf("kind" to "claude_team", "displayName" to "Claude"), token).statusCode()).isEqualTo(201)
        val complete = manage("POST", "/onboarding/complete", emptyMap<String, String>(), token)
        assertThat(complete.statusCode()).withFailMessage(complete.body()).isEqualTo(200)
        assertThat(mapper.readTree(complete.body()).path("completed").asBoolean()).isTrue()
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.contracts").query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_contract_versions WHERE contract IS NOT NULL").query(Int::class.java).single()).isZero()
        val refreshed = refresh(loginTokens.path("refresh_token").asString())
        assertThat(refreshed.statusCode()).isEqualTo(200)
        assertThat(auth.verify(mapper.readTree(refreshed.body()).path("access_token").asString()).revision).isZero()
        assertThat(auth.verify(mapper.readTree(login().body()).path("access_token").asString()).revision).isEqualTo(1)
    }

    @Test fun `최초 정책 동시 저장은 한 번만 성공한다`() {
        sql("DELETE FROM enrollment.manifests")
        val token = adminToken()
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val requests = listOf(false, true).map { choice -> pool.submit(Callable {
                gate.await()
                manage("PUT", "/collection-policy", mapOf("expectedVersion" to 0, "collectRawContent" to choice), token).statusCode()
            }) }
            gate.countDown()
            assertThat(requests.map { it.get() }).containsExactlyInAnyOrder(200, 409)
        }
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.manifests WHERE is_active").query(Int::class.java).single()).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.organization_onboarding").query(Int::class.java).single()).isEqualTo(1)
    }

    @Test fun `온보딩은 명시적인 정책과 계약 없는 벤더 선택으로 완료한다`() {
        val token = adminToken()
        val initial = mapper.readTree(manage("GET", "/onboarding", null, token).body())
        assertThat(initial.path("policy").path("confirmed").asBoolean()).isFalse()
        assertThat(initial.path("nextStep").asString()).isEqualTo("collection")
        assertThat(manage("POST", "/onboarding/complete", emptyMap<String, String>(), token).statusCode()).isEqualTo(409)
        val policy = manage("PUT", "/collection-policy", mapOf("expectedVersion" to 3, "collectRawContent" to false), token)
        assertThat(policy.statusCode()).withFailMessage(policy.body()).isEqualTo(200)
        assertThat(manage("PUT", "/collection-policy", mapOf("expectedVersion" to 3, "collectRawContent" to true), token).statusCode()).isEqualTo(409)
        assertThat(mapper.readTree(manage("GET", "/onboarding", null, token).body()).path("nextStep").asString()).isEqualTo("vendors")
        val created = manage("POST", "/vendors", mapOf("kind" to "claude_team", "displayName" to "Claude"), token)
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201)
        assertThat(mapper.readTree(created.body()).path("vendor").path("contract").isNull).isTrue()
        val complete = manage("POST", "/onboarding/complete", emptyMap<String, String>(), token)
        assertThat(complete.statusCode()).withFailMessage(complete.body()).isEqualTo(200)
        assertThat(mapper.readTree(complete.body()).path("completed").asBoolean()).isTrue()
        val reopened = mapper.readTree(manage("GET", "/onboarding", null, token).body())
        assertThat(reopened.path("nextStep").asString()).isEqualTo("complete")
        assertThat(reopened.path("selectedVendorCount").asInt()).isEqualTo(1)
        val stored = jdbc.sql("SELECT onboarding_completed,onboarding_completed_at FROM enrollment.tenants WHERE id=:tenant")
            .param("tenant", tenant).query { r, _ -> r.getBoolean(1) to r.getTimestamp(2).toInstant().toString() }.single()
        assertThat(stored.first).isTrue()
        assertThat(stored.second).isEqualTo(reopened.path("completedAt").asString())
        val repeated = mapper.readTree(manage("POST", "/onboarding/complete", emptyMap<String, String>(), token).body())
        assertThat(repeated.path("completedAt")).isEqualTo(reopened.path("completedAt"))
    }

    @Test fun `정책 변경은 두 원문 플래그만 바꾸고 이전 manifest를 보존한다`() {
        val token = adminToken()
        val before = jdbc.sql("SELECT manifest::text FROM enrollment.manifests WHERE tenant_id=:tenant AND is_active").param("tenant", tenant).query(String::class.java).single()
        val response = manage("PUT", "/collection-policy", mapOf("expectedVersion" to 3, "collectRawContent" to true), token)
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        val after = mapper.readTree(jdbc.sql("SELECT manifest::text FROM enrollment.manifests WHERE tenant_id=:tenant AND is_active").param("tenant", tenant).query(String::class.java).single())
        val original = mapper.readTree(before)
        assertThat(after.path("otlp")).isEqualTo(original.path("otlp"))
        assertThat(after.path("signals")).isEqualTo(original.path("signals"))
        assertThat(after.path("privacy").path("collect_user_prompts").asBoolean()).isTrue()
        assertThat(after.path("privacy").path("collect_assistant_responses").asBoolean()).isTrue()
        for (key in listOf("collect_tool_details", "collect_tool_content", "collect_user_email", "collect_raw_api_bodies"))
            assertThat(after.path("privacy").path(key)).isEqualTo(original.path("privacy").path(key))
        assertThat(jdbc.sql("SELECT manifest::text FROM enrollment.manifests WHERE tenant_id=:tenant AND version=3").param("tenant", tenant).query(String::class.java).single()).isEqualTo(before)
        assertThat(mapper.readTree(response.body()).path("existingInstallationsUpdated").asBoolean()).isFalse()
    }

    @Test fun `미등록 벤더와 다른 벤더 플랜을 저장하지 않는다`() {
        val token = adminToken()
        assertThat(manage("POST", "/vendors", mapOf("kind" to "unknown", "displayName" to "잘못된 제품"), token).statusCode()).isEqualTo(422)
        val contract = mapOf("planId" to "business", "effectiveFrom" to "2026-09-09", "tiers" to listOf(mapOf("label" to "표준", "seats" to 2, "monthlyFeePerSeatUsd" to "30")))
        assertThat(manage("POST", "/vendors", mapOf("kind" to "claude_team", "displayName" to "Claude", "contract" to contract), token).statusCode()).isEqualTo(422)
    }

    @Test fun `재발급은 이전 코드를 폐기하고 재시도에 같은 코드를 반환한다`() {
        val token = adminToken()
        val issued = mapper.readTree(manage("POST", "/invitations/batch", mapOf("invitations" to listOf(mapOf("email" to "reissue@example.test", "role" to "member"))), token).body()).path("results")[0]
        val path = "/invitations/${issued.path("invitationId").asString()}/reissue"
        val key = UUID.randomUUID().toString()
        val renewed = manage("POST", path, emptyMap<String, String>(), token, key)
        assertThat(renewed.statusCode()).withFailMessage(renewed.body()).isEqualTo(200)
        assertThat(manage("POST", path, emptyMap<String, String>(), token, key).body()).isEqualTo(renewed.body())
        assertThat(manage("POST", path, emptyMap<String, String>(), token).statusCode()).isEqualTo(409)
        val oldCode = code
        code = issued.path("code").asString()
        assertThat(enroll().statusCode()).isEqualTo(409)
        code = mapper.readTree(renewed.body()).path("code").asString()
        assertThat(enroll().statusCode()).isEqualTo(201)
        code = oldCode
        val listing = manage("GET", "/invitations?limit=1", null, token)
        assertThat(listing.statusCode()).withFailMessage(listing.body()).isEqualTo(200)
        assertThat(listing.body()).doesNotContain(issued.path("code").asString(), "code_hash", "password")
        assertThat(mapper.readTree(listing.body()).path("nextCursor").isNull).isFalse()
    }

    @Test fun `재발급해도 이미 소비한 설치 권한은 다시 열리지 않는다`() {
        val token = adminToken()
        val issued = mapper.readTree(manage("POST", "/invitations/batch", mapOf("invitations" to listOf(mapOf("email" to "consumed@example.test", "role" to "member"))), token).body()).path("results")[0]
        val id = UUID.fromString(issued.path("invitationId").asString())
        jdbc.sql("UPDATE enrollment.invitations SET used_at=:now WHERE id=:id").param("now", java.sql.Timestamp.from(clock.now)).param("id", id).update()
        val renewed = manage("POST", "/invitations/$id/reissue", emptyMap<String, String>(), token)
        assertThat(renewed.statusCode()).isEqualTo(409)
    }

    @Test fun `온보딩 조회도 일반 구성원과 다른 조직을 거부한다`() {
        val token = tokens().path("access_token").asString()
        assertThat(manage("GET", "/onboarding", null, token).statusCode()).isEqualTo(403)
        tenant = UUID.randomUUID()
        assertThat(manage("GET", "/onboarding", null, token).statusCode()).isEqualTo(404)
    }

}
