package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * 좌석 수동 기록 — 단건 배정·보정·해제와 CSV 가져오기 (ADR 0048 §3의 1·2행). 기대값은 ADR 과 Enrollment 명세 §12 "좌석 수동 기록"에서 쓴다:
 * 수동은 커넥터가 없는 플랜의 권위이고 연결 전에는 임시다, 연결이 있으면 배정·해제·가져오기는 409 이고 보정만 된다, 구매 수량은 좌석이 아니라
 * 넘어도 거절하지 않고 경고한다, CSV 는 모든 행을 검증한 뒤 한 트랜잭션으로 적용하고 오류가 하나라도 있으면 아무것도 바꾸지 않는다.
 * 벤더 연결 기능은 꺼져 있다 — 수동 기록은 그것과 무관하게 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class SeatManualApiTest : AbstractUserAuthApiTest() {

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun ok(response: HttpResponse<String>, status: Int = 200): JsonNode {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status)
        return json(response)
    }
    private fun errorOf(response: HttpResponse<String>) = response.statusCode() to json(response).path("error").path("code").asString()

    /** 등록 제품과 계약(등급 둘: 표준 [seats]석, 프리미엄 1석). */
    private fun vendor(kind: String, plan: String, token: String, seats: Int = 3): JsonNode {
        val tiers = listOf(mapOf("label" to "Standard", "seats" to seats, "monthlyFeePerSeatUsd" to "20")) +
            if (kind == "openai_biz" || kind == "claude_team" || kind == "cursor") listOf(mapOf("label" to "Premium", "seats" to 1, "monthlyFeePerSeatUsd" to "60")) else emptyList()
        val created = manage("POST", "/vendors", mapOf("kind" to kind, "displayName" to kind,
            "contract" to mapOf("planId" to plan, "effectiveFrom" to "2026-09-09", "tiers" to tiers)), token)
        return ok(created, 201).path("vendor")
    }
    private fun tierId(vendor: JsonNode, label: String) = vendor.at("/contract/tiers").toList().single { it.path("label").asString() == label }.path("tierId").asString()

    private fun history(seatId: String): List<String> = jdbc.sql("SELECT version || ':' || state || ':' || source FROM enrollment.seat_assignment_events WHERE seat_assignment_id = CAST(:id AS uuid) ORDER BY version")
        .param("id", seatId).query { rs, _ -> rs.getString(1) }.list()
    private fun seatCount(): Int = jdbc.sql("SELECT count(*) FROM enrollment.seat_assignments").query(Int::class.java).single()

    @Test fun `단건 — 배정·보정·해제·재배정이 같은 좌석 ID 의 판과 이력으로 남고 이메일이 한 구성원과 같으면 잇는다`() {
        val token = adminToken()
        val dana = data.member(tenant, "dana@example.test").id
        val openai = vendor("openai_biz", "business", token)
        val vendorId = openai.path("vendorId").asString()
        val key = UUID.randomUUID().toString()
        val body = mapOf("account" to " Dana@Example.test ", "tierId" to tierId(openai, "Standard"), "note" to "첫 배정")

        val created = manage("POST", "/vendors/$vendorId/seats", body, token, key = key)
        val seat = ok(created, 201).path("seat")
        val seatId = seat.path("seatAssignmentId").asString()
        assertThat(created.headers().firstValue("Location")).hasValue("/api/v1/organizations/$tenant/vendors/$vendorId/seats/$seatId")
        assertThat(created.headers().firstValue("ETag")).hasValue("\"seat-1\"")
        assertThat(listOf(seat.path("account").asString(), seat.path("state").asString(), seat.path("source").asString(), seat.path("memberId").asString(), seat.path("memberLink").asString()))
            .containsExactly("dana@example.test", "assigned", "manual", dana.toString(), "email_match")
        assertThat(json(created).path("provisional").asBoolean()).describedAs("커넥터가 없는 플랜의 수동 기록은 임시가 아니다").isFalse()
        assertThat(json(manage("POST", "/vendors/$vendorId/seats", body, token, key = key)).path("seat").path("seatAssignmentId").asString()).describedAs("같은 키의 재시도").isEqualTo(seatId)
        assertThat(errorOf(manage("POST", "/vendors/$vendorId/seats", body, token))).describedAs("새 키로 같은 계정 — 판 없이는 충돌").isEqualTo(409 to "version_conflict")
        assertThat(errorOf(manage("POST", "/vendors/$vendorId/seats", body + ("expectedVersion" to 1), token))).isEqualTo(409 to "seat_already_held")

        // 보정: 관리자 연결 → 잇지 않음 → 자동으로 되돌림. 계약 등급 바꿈.
        val other = data.member(tenant, "other@example.test").id
        val linked = ok(manage("PATCH", "/vendors/$vendorId/seats/$seatId", mapOf("expectedVersion" to 1, "memberId" to other.toString(), "tierId" to tierId(openai, "Premium")), token)).path("seat")
        assertThat(listOf(linked.path("memberId").asString(), linked.path("memberLink").asString(), linked.path("tierId").asString(), linked.path("version").asLong()))
            .containsExactly(other.toString(), "admin", tierId(openai, "Premium"), 2L)
        val unlinked = ok(manage("PATCH", "/vendors/$vendorId/seats/$seatId", mapOf("expectedVersion" to 2, "memberId" to null), token)).path("seat")
        assertThat(unlinked.path("memberId").isNull to unlinked.path("memberLink").asString()).isEqualTo(true to "admin")
        val automatic = ok(manage("PATCH", "/vendors/$vendorId/seats/$seatId", mapOf("expectedVersion" to 3, "memberLink" to "automatic"), token)).path("seat")
        assertThat(automatic.path("memberId").asString() to automatic.path("memberLink").asString()).isEqualTo(dana.toString() to "email_match")
        assertThat(errorOf(manage("PATCH", "/vendors/$vendorId/seats/$seatId", mapOf("expectedVersion" to 3, "note" to "x"), token))).isEqualTo(409 to "version_conflict")

        val released = ok(manage("POST", "/vendors/$vendorId/seats/$seatId/release", mapOf("expectedVersion" to 4), token)).path("seat")
        assertThat(listOf(released.path("state").asString(), released.path("releasedAt").asString())).containsExactly("released", clock.now.toString())
        val again = manage("POST", "/vendors/$vendorId/seats", mapOf("account" to "dana@example.test", "expectedVersion" to 5), token)
        assertThat(ok(again).path("seat").let { it.path("seatAssignmentId").asString() to it.path("version").asLong() }).describedAs("재배정은 200 과 같은 좌석").isEqualTo(seatId to 6L)
        assertThat(history(seatId)).containsExactly("1:assigned:manual", "2:assigned:manual", "3:assigned:manual", "4:assigned:manual", "5:released:manual", "6:assigned:manual")
    }

    @Test fun `입력 검증 — 계정 형식·계약 등급·구성원의 조직·다른 제품의 좌석 경로`() {
        val token = adminToken()
        val openai = vendor("openai_biz", "business", token).path("vendorId").asString()
        val copilot = vendor("copilot", "copilot_business", token).path("vendorId").asString()
        assertThat(errorOf(manage("POST", "/vendors/$openai/seats", mapOf("account" to "octocat"), token))).isEqualTo(400 to "invalid_request")
        assertThat(errorOf(manage("POST", "/vendors/$copilot/seats", mapOf("account" to "octocat"), token))).describedAs("GitHub 로그인 계정 종류는 없다(ADR 0054)").isEqualTo(400 to "invalid_request")
        assertThat(errorOf(manage("POST", "/vendors/$openai/seats", mapOf("account" to "a@example.test", "tierId" to "nope"), token))).isEqualTo(422 to "invalid_tier")
        val stranger = data.member(data.tenant().id, "stranger@example.test").id
        assertThat(errorOf(manage("POST", "/vendors/$openai/seats", mapOf("account" to "a@example.test", "memberId" to stranger.toString()), token))).isEqualTo(404 to "not_found")
        // 연동 대상이 아닌 제품은 커넥터가 없는 플랜이다 — 수동 기록이 주 원천이고(임시 아님) 계정은 이메일이다.
        val created = ok(manage("POST", "/vendors/$copilot/seats", mapOf("account" to "Octo@Example.test"), token), 201)
        val seat = created.path("seat")
        assertThat(seat.path("account").asString() to created.path("provisional").asBoolean()).isEqualTo("octo@example.test" to false)
        assertThat(errorOf(manage("POST", "/vendors/$openai/seats/${seat.path("seatAssignmentId").asString()}/release", mapOf("expectedVersion" to 1), token)))
            .describedAs("다른 제품의 좌석 경로").isEqualTo(404 to "not_found")
    }

    @Test fun `커넥터 플랜에 연결이 없으면 수동 기록은 임시이고, 연결이 생기면 배정·해제·가져오기는 409 이지만 보정은 된다`() {
        val token = adminToken()
        val cursor = vendor("cursor", "cursor_enterprise", token).path("vendorId").asString()
        val created = ok(manage("POST", "/vendors/$cursor/seats", mapOf("account" to "octo@example.test"), token), 201)
        assertThat(created.path("provisional").asBoolean()).isTrue()
        val seatId = created.at("/seat/seatAssignmentId").asString()
        jdbc.sql("""INSERT INTO enrollment.vendor_connections (id, tenant_id, vendor_id, connector, settings, credential_ciphertext, credential_key_id, credential_updated_at,
                check_status, version, created_at, created_by, updated_at, updated_by)
            VALUES (gen_random_uuid(), :tenant, :vendor, 'cursor_enterprise', '{}', 'opaque', 'k1', now(), 'unverified', 1, now(), :member, now(), :member)""")
            .param("tenant", tenant).param("vendor", cursor).param("member", member).update()

        assertThat(errorOf(manage("POST", "/vendors/$cursor/seats", mapOf("account" to "hubot@example.test"), token))).isEqualTo(409 to "connector_managed")
        assertThat(errorOf(manage("POST", "/vendors/$cursor/seats/$seatId/release", mapOf("expectedVersion" to 1), token))).isEqualTo(409 to "connector_managed")
        assertThat(errorOf(manage("POST", "/vendors/$cursor/seats/import", mapOf("mode" to "preview", "csv" to "account\nhubot@example.test\n"), token))).isEqualTo(409 to "connector_managed")
        val noted = ok(manage("PATCH", "/vendors/$cursor/seats/$seatId", mapOf("expectedVersion" to 1, "note" to "벤더 콘솔 확인 필요"), token))
        assertThat(noted.at("/seat/note").asString() to noted.path("provisional").asBoolean()).isEqualTo("벤더 콘솔 확인 필요" to false)
    }

    @Test fun `구매 수량은 좌석이 아니다 — 넘어도 거절하지 않고 경고한다`() {
        val token = adminToken()
        val copilot = vendor("copilot", "copilot_business", token, seats = 1).path("vendorId").asString()
        assertThat(ok(manage("POST", "/vendors/$copilot/seats", mapOf("account" to "octo@example.test"), token), 201).path("warnings").toList()).isEmpty()
        assertThat(ok(manage("POST", "/vendors/$copilot/seats", mapOf("account" to "hubot@example.test"), token), 201).path("warnings").toList().map { it.asString() })
            .containsExactly("exceeds_contracted_seats")
        assertThat(seatCount()).isEqualTo(2)
    }

    @Test fun `CSV — 미리보기는 아무것도 쓰지 않고, 오류가 하나라도 있으면 적용도 아무것도 바꾸지 않으며, 같은 파일을 다시 보내면 전부 unchanged 다`() {
        val token = adminToken()
        val dana = data.member(tenant, "dana@example.test").id
        val openai = vendor("openai_biz", "business", token)
        val vendorId = openai.path("vendorId").asString()
        // 해제된 좌석 하나와 배정된 좌석 하나를 먼저 둔다.
        val old = ok(manage("POST", "/vendors/$vendorId/seats", mapOf("account" to "old@example.test"), token), 201).at("/seat/seatAssignmentId").asString()
        ok(manage("POST", "/vendors/$vendorId/seats/$old/release", mapOf("expectedVersion" to 1), token))
        val kept = ok(manage("POST", "/vendors/$vendorId/seats", mapOf("account" to "kept@example.test"), token), 201).at("/seat/seatAssignmentId").asString()
        ok(manage("POST", "/vendors/$vendorId/seats", mapOf("account" to "gone@example.test"), token), 201)

        val bad = "﻿account,status,tier,member_email\r\nnew@example.test,,standard,\r\n\"old@example.test\",assigned,,dana@example.test\r\nnot-an-email,,,\r\n" +
            "kept@example.test,assigned,Premium,\r\nkept@example.test,,,\r\nmissing@example.test,released,,\r\nnew2@example.test,assigned,Gold,nobody@example.test\r\n" +
            "gone@example.test,released,Standard,\r\n"
        val preview = ok(manage("POST", "/vendors/$vendorId/seats/import", mapOf("mode" to "preview", "csv" to bad), token)).path("import")
        assertThat(preview.path("applied").asBoolean()).isFalse()
        assertThat(preview.path("rows").toList().map { Triple(it.path("line").asInt(), it.path("action").asString(null as String?), it.path("errors").toList().map { e -> e.path("field").asString() + ":" + e.path("code").asString() }) })
            .containsExactly(
                Triple(2, "create", emptyList()), Triple(3, "reassign", emptyList()), Triple(4, null, listOf("account:invalid_account")),
                Triple(5, "update", emptyList()), Triple(6, null, listOf("account:duplicate_account")), Triple(7, null, listOf("account:not_found")),
                Triple(8, null, listOf("tier:invalid_tier", "member_email:member_not_found")),
                // 해제 행에 등급·구성원을 적으면 무엇을 뜻하는지 모른다.
                Triple(9, null, listOf("tier:not_applicable")))
        assertThat(preview.at("/summary/errors").asInt()).isEqualTo(5)
        val before = seatCount()

        val rejected = manage("POST", "/vendors/$vendorId/seats/import", mapOf("mode" to "apply", "csv" to bad), token)
        assertThat(errorOf(rejected)).isEqualTo(422 to "seat_import_invalid")
        assertThat(json(rejected).at("/details/rows").size()).isEqualTo(8)
        assertThat(seatCount()).describedAs("오류가 있으면 아무것도 바꾸지 않는다").isEqualTo(before)

        val good = "account,status,tier,member_email\nnew@example.test,,standard,\nold@example.test,assigned,,dana@example.test\nkept@example.test,assigned,Premium,\ngone@example.test,released,,\n"
        val applied = ok(manage("POST", "/vendors/$vendorId/seats/import", mapOf("mode" to "apply", "csv" to good), token)).path("import")
        assertThat(applied.path("applied").asBoolean()).isTrue()
        assertThat(applied.path("rows").toList().map { it.path("action").asString() }).containsExactly("create", "reassign", "update", "release")
        val seats = jdbc.sql("SELECT account || ':' || state || ':' || source || ':' || coalesce(tier_id,'-') || ':' || coalesce(member_link,'-') FROM enrollment.seat_assignments ORDER BY account")
            .query { rs, _ -> rs.getString(1) }.list()
        assertThat(seats).containsExactly(
            "gone@example.test:released:csv:-:-",
            "kept@example.test:assigned:manual:${tierId(openai, "Premium")}:-",
            "new@example.test:assigned:csv:${tierId(openai, "Standard")}:-",
            "old@example.test:assigned:csv:-:admin")
        assertThat(history(kept).last()).describedAs("등급만 바뀐 행은 원천이 그대로다").isEqualTo("2:assigned:manual")
        assertThat(jdbc.sql("SELECT member_id FROM enrollment.seat_assignments WHERE account = 'old@example.test'").query(UUID::class.java).single()).isEqualTo(dana)

        val again = ok(manage("POST", "/vendors/$vendorId/seats/import", mapOf("mode" to "apply", "csv" to good), token)).path("import")
        assertThat(again.path("rows").toList().map { it.path("action").asString() }).containsOnly("unchanged")
        assertThat(again.path("digest").asString()).isEqualTo(applied.path("digest").asString())
    }

    @Test fun `CSV — 파일 자체가 틀리면 400 invalid_csv 이고, 이메일 외의 개인 정보 열은 받지 않는다`() {
        val token = adminToken()
        val vendorId = vendor("openai_biz", "business", token).path("vendorId").asString()
        fun reason(csv: String) = manage("POST", "/vendors/$vendorId/seats/import", mapOf("mode" to "preview", "csv" to csv), token).let {
            assertThat(errorOf(it)).isEqualTo(400 to "invalid_csv")
            json(it).at("/details/reason").asString()
        }
        assertThat(reason("account,name\na@example.test,Dana\n")).isEqualTo("unknown_column")
        assertThat(reason("status\nassigned\n")).isEqualTo("missing_account_column")
        assertThat(reason("")).isEqualTo("missing_header")
        assertThat(reason("account,account\na@example.test,b@example.test\n")).isEqualTo("duplicate_column")
        assertThat(reason("account\n\"a@example.test\n")).isEqualTo("malformed_quotes")
        assertThat(reason("account\n" + (1..5001).joinToString("\n") { "u$it@example.test" })).isEqualTo("too_many_rows")
        assertThat(errorOf(manage("POST", "/vendors/$vendorId/seats/import", mapOf("mode" to "later", "csv" to "account\n"), token))).isEqualTo(400 to "invalid_request")
        // 따옴표 안의 쉼표·빈 줄은 된다.
        val quoted = ok(manage("POST", "/vendors/$vendorId/seats/import", mapOf("mode" to "preview", "csv" to "account,tier\n\n\"a@example.test\",\"Standard\"\n"), token)).path("import")
        assertThat(quoted.path("rows").toList().map { it.path("line").asInt() to it.path("action").asString() }).containsExactly(3 to "create")
    }

    @Test fun `조직 경계와 권한 — 다른 조직의 등록 제품은 없는 것이고 구성원(member)은 기록하지 못한다`() {
        val other = data.tenant().id
        jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,'other-openai','openai_biz','manual',now())").param("t", other).update()
        val memberToken = tokens().path("access_token").asString()
        assertThat(errorOf(manage("POST", "/vendors/other-openai/seats", mapOf("account" to "a@example.test"), memberToken))).isEqualTo(403 to "forbidden")
        jdbc.sql("UPDATE enrollment.members SET role='owner' WHERE id=:id").param("id", member).update()
        val ownerToken = mapper.readTree(login().body()).path("access_token").asString()
        assertThat(errorOf(manage("POST", "/vendors/other-openai/seats", mapOf("account" to "a@example.test"), ownerToken))).isEqualTo(404 to "not_found")
        val response = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/organizations/$other/vendors/other-openai/seats"))
            .header("Authorization", "Bearer $ownerToken").header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString("""{"account":"a@example.test"}""")).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(errorOf(response)).isEqualTo(404 to "not_found")
        assertThat(seatCount()).isZero()
    }

    @Test fun `CSV — 최대 5000행은 받고, 미리보기 뒤 대상이 바뀌면 적용은 그때의 원장으로 다시 계산한다`() {
        val token = adminToken()
        val vendorId = vendor("openai_biz", "business", token).path("vendorId").asString()
        fun import(mode: String, csv: String) = ok(manage("POST", "/vendors/$vendorId/seats/import", mapOf("mode" to mode, "csv" to csv), token)).path("import")
        // 명세 §12: 최대 5,000행 — 그 경계는 받는다(5,001행은 too_many_rows, 위 시험).
        val largest = import("preview", "account\n" + (1..5000).joinToString("\n") { "u$it@example.test" })
        assertThat(largest.path("rows").size()).isEqualTo(5000)
        assertThat(largest.at("/summary/errors").asInt()).isZero()
        assertThat(seatCount()).describedAs("미리보기는 쓰지 않는다").isZero()

        // 미리보기 뒤 다른 관리자가 같은 계정을 배정했다 — 적용은 지금 원장으로 계산해 새 좌석을 만들지 않는다.
        val csv = "account\nlate@example.test\n"
        assertThat(import("preview", csv).path("rows").toList().map { it.path("action").asString() }).containsExactly("create")
        ok(manage("POST", "/vendors/$vendorId/seats", mapOf("account" to "late@example.test"), token), 201)
        assertThat(import("apply", csv).path("rows").toList().map { it.path("action").asString() }).containsExactly("unchanged")
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.seat_assignments WHERE account = 'late@example.test'").query(Int::class.java).single()).isEqualTo(1)
    }
}
