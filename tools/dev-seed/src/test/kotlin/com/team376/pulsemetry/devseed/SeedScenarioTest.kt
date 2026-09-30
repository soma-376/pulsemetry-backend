package com.team376.pulsemetry.devseed

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SeedScenarioTest {
    private val date = LocalDate.of(2026, 9, 28)
    private val a = scenario("A", date)
    private val b = scenario("B", date)
    private val c = scenario("C", date)

    @Test fun `A만 정책과 벤더 선택을 갖춘 온보딩 완료 조직이다`() {
        val completedAt = a.rows.getValue("enrollment.tenants").single()["onboarding_completed_at"]
        assertEquals("2026-07-29T15:00:00Z", completedAt)
        val onboarding = a.rows.getValue("enrollment.organization_onboarding").single()
        assertEquals(completedAt, onboarding["policy_confirmed_at"])
        assertEquals(id("A/member/0"), onboarding["completed_by"])
        assertEquals(setOf("claude_team", "openai_biz", "copilot", "cursor"), a.rows.getValue("enrollment.managed_vendors").map { it["kind"] }.toSet())
        assertTrue(a.rows.getValue("enrollment.vendor_contract_versions").all { it["archived"] == false })
        for (data in listOf(b, c)) {
            assertNull(data.rows.getValue("enrollment.tenants").single()["onboarding_completed_at"])
            assertFalse(data.rows.containsKey("enrollment.organization_onboarding"))
        }
        assertTrue(listOf(a, b, c).flatMap { it.rows.getValue("enrollment.members") }.none { it["email"] == "local-owner@example.com" })
    }

    @Test fun `A는 합성 좌석 계약과 계약 미입력을 구분하고 공급자 기록을 계약에 귀속시키지 않는다`() {
        val fixture = json.readTree(encode(frontendFixture(a)))
        val vendors = fixture.path("managedVendors").toList()
        val claude = vendors.single { it.path("kind").asString() == "claude_team" }
        val openai = vendors.single { it.path("kind").asString() == "openai_biz" }
        assertEquals("team", claude.path("contract").path("planId").asString())
        assertEquals(10, claude.path("contract").path("tiers").sumOf { it.path("seats").asInt() })
        assertEquals("360", claude.path("contract").path("monthlySeatFeeUsd").asString())
        assertEquals("business", openai.path("contract").path("planId").asString())
        assertEquals(8, openai.path("contract").path("tiers").sumOf { it.path("seats").asInt() })
        assertEquals("400", openai.path("contract").path("monthlySeatFeeUsd").asString())
        assertEquals(2, openai.path("version").asInt())
        assertEquals("active", claude.path("contractStatus").asString())
        assertEquals("active", openai.path("contractStatus").asString())
        val cursor = vendors.single { it.path("kind").asString() == "cursor" }
        assertTrue(cursor.path("contract").isNull)
        assertEquals("missing", cursor.path("contractStatus").asString())
        val copilot = vendors.single { it.path("kind").asString() == "copilot" }
        assertEquals("expired", copilot.path("contractStatus").asString())
        assertEquals("needs_review", copilot.path("state").asString())
        assertEquals("2026-09-27", copilot.path("contract").path("effectiveTo").asString())
        assertEquals("95", copilot.path("contract").path("monthlySeatFeeUsd").asString())
        assertTrue(vendors.all { it.path("activeUsers7d").isNull && it.path("observation").asString() == "unobserved" })
        assertEquals(8, fixture.path("usage").path("activeUsers").asInt())
        assertEquals(10, fixture.path("policyRollout").path("applied").asInt())
        assertEquals(11, fixture.path("policyRollout").path("eligible").asInt())
        assertEquals(1, fixture.path("policyRollout").path("unknown").asInt())
        assertEquals(0, fixture.path("policyRollout").path("outdated").asInt())
        val serialized = encode(fixture)
        for (secret in listOf("password_hash", "development_password", "code_hash", "invitation_codes", SEED_PASSWORD)) {
            assertFalse(serialized.contains(secret))
        }
        assertEquals(encode(frontendFixture(a)), encode(frontendFixture(scenario("A", date))))
    }

    @Test fun `같은 기준일은 동일하고 조직과 다른 기준일은 구분된다`() {
        assertEquals(a.fingerprint, scenario("A", date).fingerprint)
        assertEquals(3, listOf(a, b, c).map { it.tenantId }.distinct().size)
        assertNotEquals(a.fingerprint, scenario("A", date.plusDays(1)).fingerprint)
        assertEquals(a.tenantId, scenario("A", date.plusDays(1)).tenantId)
    }

    @Test fun `양 도구를 쓰는 구성원은 조직에서 한 명이다`() {
        assertTrue((a.summary()["period_events"] as Int) > 1000)
        assertEquals(8, a.summary()["period_active_users"])
        val both = a.events.filter { it["member_id"] == id("A/member/2") }
        assertEquals(setOf("claude_code", "codex"), both.map { it["product"] }.toSet())
        assertEquals(2, a.events.map { it["semantics_profile"] }.distinct().size)
    }

    @Test fun `팩트의 팀은 이벤트 시점의 소속과 일치한다`() {
        val memberships = a.rows.getValue("enrollment.team_memberships")
        for (event in a.events) {
            val time = Instant.parse(event["source_time"].toString())
            val expected = memberships.filter {
                it["member_id"] == event["member_id"] && Instant.parse(it["joined_at"].toString()) <= time &&
                    (it["left_at"] == null || time < Instant.parse(it["left_at"].toString()))
            }.map { it["team_id"] }
            assertEquals(expected, event["team_ids_as_of"])
        }
        assertEquals(setOf(id("A/team/0"), id("A/team/1")), a.events.filter { it["member_id"] == id("A/member/2") }.map { it["team_id_as_of"] }.toSet())
    }

    @Test fun `B는 첫 온보딩을 위한 조직과 오너만 생성한다`() {
        assertEquals(setOf("enrollment.tenants", "enrollment.members"), b.rows.keys)
        val owner = b.rows.getValue("enrollment.members").single()
        assertEquals("owner@seed-b.example.test", owner["email"])
        assertEquals("owner", owner["role"])
        assertEquals("active", owner["status"])
        assertFalse(owner["password_hash"]?.toString().isNullOrBlank())
        assertNull(b.rows.getValue("enrollment.tenants").single()["onboarding_completed_at"])
        assertTrue(b.invitationCodes.isEmpty())
        assertTrue(b.events.isEmpty()); assertTrue(b.ledger.isEmpty())
    }

    @Test fun `알 수 없는 비용과 무료 계약을 구분한다`() {
        val incomplete = c.events.filter { (it["quality_flags"] as List<*>).isNotEmpty() }
        assertEquals(4, incomplete.size)
        assertTrue(incomplete.all { it["tokens_output"] == null && it["cost_estimated_usd"] == null })
        assertEquals(false, c.summary()["period_cost_complete"])
        assertTrue(a.rows.getValue("enrollment.contract_term_commitments").any { it["commitment_amount"] == 0 })
        assertFalse(c.rows.containsKey("enrollment.contracts"))
    }

    @Test fun `사용량 없는 최근 수신과 과거 수신만 있는 상태를 구분한다`() {
        assertFalse(a.events.any { it["member_id"] == id("A/member/10") })
        assertEquals(listOf(0), a.ledger.filter { it["installation_id"] == id("A/installation/10") }.map { it["record_count"] })
        assertTrue(a.ledger.filter { it["installation_id"] == id("A/installation/11") }.all { it["received_time"].toString() < "2026-08-30" })
    }

    @Test fun `A 가상 요율은 캐시를 이중 합산하지 않고 십진수로 계산한다`() {
        val claude = seedModels.getValue("claude_code").first()
        assertEquals(0, BigDecimal("22.05").compareTo(claude.cost(1_000_000, 1_000_000, 1_000_000, 1_000_000)))
        val codex = seedModels.getValue("codex").first()
        assertEquals(0, BigDecimal("10.2").compareTo(codex.cost(1_000_000, 1_000_000, 1_000_000, 0)))
        assertTrue(a.events.all { (it["cost_estimated_usd"] as BigDecimal).signum() > 0 })
        assertTrue(a.events.all { it["cost_reported_usd"] == null })
    }

    @Test fun `초대는 대기 만료 사용됨 상태가 있다`() {
        val invitations = a.rows.getValue("enrollment.invitations")
        assertEquals(11, invitations.count { it["used_at"] != null })
        assertEquals(2, invitations.count { it["used_at"] == null })
        assertEquals(setOf("pending", "expired"), a.invitationCodes.keys)
        assertTrue(a.invitationCodes.values.all { Regex("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$").matches(it) })
    }

    @Test fun `PostgreSQL 신원과 소속이 설치 및 사용 기록의 참조를 모두 만족한다`() {
        for (data in listOf(a, c)) {
            val members = data.rows.getValue("enrollment.members").associateBy { it["id"] }
            val teams = data.rows.getValue("enrollment.teams").associateBy { it["id"] }
            val installations = data.rows.getValue("enrollment.installations").associateBy { it["id"] }
            val invitations = data.rows.getValue("enrollment.invitations").associateBy { it["id"] }
            val manifests = data.rows.getValue("enrollment.manifests").associateBy { it["id"] }
            assertEquals(1, manifests.values.count { it["is_active"] == true })
            for (row in members.values + teams.values + installations.values + invitations.values + manifests.values) {
                assertEquals(data.tenantId, row["tenant_id"])
            }
            for (row in installations.values) {
                assertEquals("active", members.getValue(row["member_id"])["status"])
                assertEquals(row["member_id"], invitations.getValue(row["invitation_id"])["target_member_id"])
                assertTrue(invitations.getValue(row["invitation_id"])["used_at"] != null)
            }
            for (row in data.rows.getValue("enrollment.installation_manifest_assignments")) {
                assertTrue(row["installation_id"] in installations)
                assertTrue(row["manifest_id"] in manifests)
                assertTrue(row["applied_at"] == null || row["assigned_at"].toString() <= row["applied_at"].toString())
            }
            for (row in data.rows.getValue("enrollment.team_memberships")) {
                assertTrue(row["member_id"] in members)
                assertTrue(row["team_id"] in teams)
                assertTrue(row["left_at"] == null || row["joined_at"].toString() < row["left_at"].toString())
            }
            for (row in data.events) {
                assertEquals(row["member_id"], installations.getValue(row["installation_id"])["member_id"])
                assertTrue(Instant.parse(installations.getValue(row["installation_id"])["created_at"].toString()) <= Instant.parse(row["source_time"].toString()))
                assertEquals(data.tenantId, row["tenant_id"])
            }
            assertTrue(data.ledger.all { it["installation_id"] in installations })
        }
        val activeMembers = a.rows.getValue("enrollment.members").filter { it["status"] == "active" }
        assertEquals(12, activeMembers.size)
        val assignedMembers = a.rows.getValue("enrollment.team_memberships").filter { it["left_at"] == null }.map { it["member_id"] }.toSet()
        assertEquals(listOf(id("A/member/9")), activeMembers.filter { it["id"] !in assignedMembers }.map { it["id"] })
    }

    @Test fun `A의 두 번째 설치는 동일 구성원이며 수신과 적용 이력을 꾸미지 않는다`() {
        val installations = a.rows.getValue("enrollment.installations")
        assertEquals(11, installations.size)
        assertEquals(10, installations.map { it["member_id"] }.distinct().size)
        assertEquals(setOf("windows", "macos", "linux"), installations.map { it["platform"] }.toSet())
        val secondary = installations.single { it["id"] == id("A/installation/2/secondary") }
        assertEquals(id("A/member/2"), secondary["member_id"])
        assertNull(secondary["last_seen_at"])
        assertFalse(a.ledger.any { it["installation_id"] == secondary["id"] })
        val fixture = json.readTree(encode(frontendFixture(a)))
        assertTrue(fixture.path("installations").single { it.path("installationId").asString() == secondary["id"] }
            .path("appliedPolicyVersion").isNull)
    }

    @Test fun `A의 주 설치는 기준 시각까지 손실 없이 수집했다고 보고했고 보고한 적 없는 설치와 C는 그대로 둔다`() {
        val installations = a.rows.getValue("enrollment.installations").associateBy { it["id"] }
        val heartbeats = a.rows.getValue("enrollment.installation_heartbeats")
        val segments = a.rows.getValue("enrollment.installation_collection_segments")
        val primaries = (2 until 12).map { id("A/installation/$it") }
        assertEquals(primaries, heartbeats.map { it["installation_id"] })
        assertEquals(primaries, segments.map { it["installation_id"] })
        // 기준 시각은 기준일의 서울 자정이다.
        val reportedAt = "2026-09-27T15:00:00Z"
        for (report in heartbeats) {
            val installation = installations.getValue(report["installation_id"])
            assertEquals(reportedAt, report["received_at"])
            // 수집 중(로컬 배선·전달·수신)이고 잃거나 밀린 것이 없다.
            assertEquals(listOf("local", true, 0, 0, null), listOf(report["mode"], report["forwarding"], report["lost"], report["pending"], report["pending_since"]))
            assertEquals(installation["created_at"], report["receiving_since"])
            // 전달한 개수와 마지막 전달 시각은 그 설치의 수신 기록과 같다. 보고 시각보다 뒤가 아니다.
            val receipts = a.ledger.filter { it["installation_id"] == report["installation_id"] }.map { it["received_time"].toString() }
            assertTrue(receipts.isNotEmpty())
            assertEquals(receipts.size, report["delivered"])
            assertEquals(receipts.max(), report["last_delivered_at"])
            assertTrue(Instant.parse(report["last_delivered_at"].toString()) <= Instant.parse(reportedAt))
            // 적용한 판은 그 조직의 활성 manifest 다.
            assertEquals(1, report["applied_config_revision"])
            assertEquals(a.rows.getValue("enrollment.manifests").single()["id"], report["applied_manifest_id"])
            assertEquals(installation["architecture"], report["architecture"])
            // 설치의 생존 시각은 마지막 보고를 받은 시각이다.
            assertEquals(reportedAt, installation["last_seen_at"])
            // 구간은 그 프로세스가 등록 뒤로 끊김 없이 수집한 하나다.
            val segment = segments.single { it["installation_id"] == report["installation_id"] }
            assertEquals(listOf(report["run_id"], installation["created_at"], reportedAt, 0), listOf(segment["run_id"], segment["from_at"], segment["to_at"], segment["lost"]))
        }
        assertEquals(10, heartbeats.map { it["run_id"] }.distinct().size)
        assertTrue(heartbeats.all { Regex("[0-9a-f]{32}").matches(it["run_id"].toString()) })
        // 두 번째 설치는 보고한 적이 없다. C는 설치 보고라는 근거가 없는 조직으로 남긴다(수집 상태 확인 불가).
        assertNull(installations.getValue(id("A/installation/2/secondary"))["last_seen_at"])
        for (table in listOf("enrollment.installation_heartbeats", "enrollment.installation_collection_segments")) {
            assertFalse(c.rows.containsKey(table))
            assertFalse(b.rows.containsKey(table))
        }
    }

    @Test fun `A 계약 이력의 합계와 확인자가 일치하고 초기 미입력 버전을 보존한다`() {
        val versions = a.rows.getValue("enrollment.vendor_contract_versions")
        val registered = a.rows.getValue("enrollment.managed_vendors").map { it["vendor_id"] }.toSet()
        val members = a.rows.getValue("enrollment.members").map { it["id"] }.toSet()
        for (version in versions) {
            assertTrue(version["vendor_id"] in registered)
            assertTrue(version["recorded_by"] in members)
            val contract = (version["contract"] as? String)?.let(json::readTree) ?: continue
            assertEquals((version["version"] as Number).toLong(), contract.path("version").asLong())
            assertEquals(version["recorded_by"], contract.path("confirmedBy").asString())
            val expected = contract.path("tiers").fold(BigDecimal.ZERO) { total, tier ->
                total + tier.path("monthlyFeePerSeatUsd").asString().toBigDecimal() * tier.path("seats").asLong().toBigDecimal()
            }
            assertEquals(0, expected.compareTo(contract.path("monthlySeatFeeUsd").asString().toBigDecimal()))
        }
        val openai = versions.filter { it["vendor_id"] == id("A/vendor/openai_biz") }.sortedBy { (it["version"] as Number).toLong() }
        assertEquals(2, openai.size)
        assertNull(openai.first()["contract"])
        assertTrue(openai.first()["recorded_at"].toString() < openai.last()["recorded_at"].toString())
        assertTrue(openai.last()["contract"] != null)
    }

    @Test fun `초기화 SQL은 선택한 시드 조직만 지운다`() {
        val tables = setOf("tenants", "members", "team_memberships", "installations", "invitations", "telemetry_tokens", "contract_memberships", "user_sessions")
        val statements = resetStatements("A", tables)
        assertEquals(tables.size, statements.size)
        assertTrue(statements.all { "WHERE" in it && id("A") in it && id("B") !in it && "TRUNCATE" !in it })
        assertFailsWith<IllegalArgumentException> { resetStatements("real-tenant", tables) }
        // 작업 기록은 구성원을 가리킨다. 대상 → 작업 → 구성원 순서로 지운다.
        val order = resetStatements("A", tables + setOf("operations", "operation_targets")).map { it.substringAfter("enrollment.").substringBefore(" ") }
        assertTrue(order.indexOf("operation_targets") < order.indexOf("operations") && order.indexOf("operations") < order.indexOf("members"))
        // 설치 보고는 설치를 가리킨다. 설치보다 먼저 지운다.
        val reports = resetStatements("A", tables + setOf("installation_heartbeats", "installation_collection_segments")).map { it.substringAfter("enrollment.").substringBefore(" ") }
        assertTrue(listOf("installation_heartbeats", "installation_collection_segments").all { it in reports && reports.indexOf(it) < reports.indexOf("installations") })
    }
}
