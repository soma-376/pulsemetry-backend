package com.team376.pulsemetry.devseed

import tools.jackson.databind.JsonNode
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
        // 관측은 명시 매핑으로만 붙는다(ADR 0044): claude_code → claude_team, codex → openai_biz. Cursor·Copilot 은 매핑이 없어 관측할 수 없다.
        for (observed in listOf(claude, openai)) {
            assertEquals("partial", observed.path("observation").asString())
            assertTrue(observed.path("firstSeenAt").asString() < observed.path("lastSeenAt").asString())
            assertTrue(observed.path("lastSeenAt").asString() < "2026-09-27T15:00:00Z")
            assertTrue(observed.path("activeUsers7d").asInt() in 1..8 && observed.path("activeUsers30d").asInt() in observed.path("activeUsers7d").asInt()..8)
        }
        for (unmapped in listOf(cursor, copilot)) {
            assertEquals("unobserved", unmapped.path("observation").asString())
            assertTrue(listOf("activeUsers7d", "activeUsers30d", "firstSeenAt", "lastSeenAt").all { unmapped.path(it).isNull })
        }
        assertEquals(8, fixture.path("usage").path("activeUsers").asInt())
        // 판 2를 주 설치 2~8이 적용했고 9~11은 판 1에 머물며 두 번째 설치는 확인된 판이 없다.
        assertEquals(2, fixture.path("policyRollout").path("desiredVersion").asInt())
        assertEquals(7, fixture.path("policyRollout").path("applied").asInt())
        assertEquals(11, fixture.path("policyRollout").path("eligible").asInt())
        assertEquals(1, fixture.path("policyRollout").path("unknown").asInt())
        assertEquals(3, fixture.path("policyRollout").path("outdated").asInt())
        assertEquals(2, fixture.path("onboarding").path("policy").path("version").asInt())
        val notifiable = fixture.path("installations").toList().filter { it.path("canNotify").asBoolean() }.map { it.path("installationId").asString() }
        assertEquals((9..11).map { id("A/installation/$it") } + id("A/installation/2/secondary"), notifiable)
        val reported = fixture.path("installations").toList().associate { it.path("installationId").asString() to it.path("lastHeartbeatAt") }
        assertTrue((2..11).all { reported.getValue(id("A/installation/$it")).asString() == "2026-09-27T15:00:00Z" })
        assertTrue(reported.getValue(id("A/installation/2/secondary")).isNull)
        val serialized = encode(fixture)
        for (secret in listOf("password_hash", "development_password", "code_hash", "invitation_codes", SEED_PASSWORD)) {
            assertFalse(serialized.contains(secret))
        }
        assertEquals(encode(frontendFixture(a)), encode(frontendFixture(scenario("A", date))))
    }

    @Test fun `A의 좌석은 사람별 원장이다 — 수동·커넥터 원천과 미연결 좌석이 있고 구매 수량으로 채우지 않는다`() {
        val seats = a.rows.getValue("enrollment.seat_assignments")
        val events = a.rows.getValue("enrollment.seat_assignment_events")
        val vendor = { kind: String -> id("A/vendor/$kind") }
        assertEquals<Map<Any?, Int>>(mapOf("manual" to 7, "connector" to 3), seats.groupingBy { it["source"] }.eachCount())
        assertEquals<Map<String, Int>>(mapOf(vendor("claude_team") to 5, vendor("openai_biz") to 2, vendor("copilot") to 3), seats.groupingBy { it["vendor_id"] as String }.eachCount())
        assertTrue(seats.none { it["vendor_id"] == vendor("cursor") }, "Cursor 는 좌석을 기록하지 않았다")
        assertEquals<List<Any?>>(listOf("contractor@partner.example.test", "seed-bot"), seats.filter { it["member_id"] == null }.map { it["account"] }.sortedBy { it.toString() })
        // 계약의 구매 수량(Claude 10석)과 좌석 수(5)는 다르다 — 수량에서 만들지 않았다.
        assertNotEquals<Int>(10, seats.count { it["vendor_id"] == vendor("claude_team") })
        // 판마다 이력 한 행. 수동은 관리자, 커넥터는 동기화 실행이 행위자다.
        assertEquals(seats.map { it["id"] }.toSet(), events.map { it["seat_assignment_id"] }.toSet())
        events.forEach { event ->
            val seat = seats.single { it["id"] == event["seat_assignment_id"] }
            if (seat["source"] == "connector") assertEquals(listOf(id("A/seat-sync-run/copilot"), null), listOf(event["sync_run_id"], event["actor_id"]))
            else assertEquals(listOf(null, id("A/member/0")), listOf(event["sync_run_id"], event["actor_id"]))
        }
        assertEquals("github_login", seats.single { it["account"] == "seed-dev-7" }["account_kind"])
        val connection = a.rows.getValue("enrollment.vendor_connections").single()
        assertEquals(a.rows.getValue("enrollment.seat_sync_runs").single()["finished_at"], connection["last_sync_succeeded_at"])
        // A 만 좌석이 있다.
        assertNull(c.rows["enrollment.seat_assignments"])
    }

    @Test fun `C의 청구 누계는 시드 원천이고 계약액과 다르며, 같은 실행의 좌석 목록은 실패해 좌석이 없다`() {
        val billing = c.rows.getValue("enrollment.vendor_billing_periods").single()
        val connection = c.rows.getValue("enrollment.vendor_connections").single()
        val contract = c.rows.getValue("enrollment.vendor_contract_versions").single()["contract"].toString()
        assertEquals(listOf("seed", null, "usage_spend", false), listOf(billing["source"], billing["connection_id"], billing["kind"], billing["finalized"]))
        assertTrue(billing["period_start"].toString() < billing["period_end"].toString())
        assertEquals(connection["last_billing_succeeded_at"], billing["fetched_at"])
        // 청구 누계는 계약의 월 요금(120)과 다른 값이다 — 계약액을 복사하지 않는다.
        assertTrue("\"monthlySeatFeeUsd\":\"120\"" in contract && billing["amount_usd"] != "120")
        assertEquals(listOf("cursor_enterprise", null, "vendor_unavailable"), listOf(connection["connector"], connection["last_sync_succeeded_at"], connection["last_sync_error"]))
        assertNull(c.rows["enrollment.seat_assignments"])
        assertNull(a.rows["enrollment.vendor_billing_periods"])
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
            // 적용한 판은 그 설치가 적용을 확인받은 가장 높은 판이다 — 2~8은 판 2, 9~11은 판 1.
            val adopted = report["installation_id"] in (2..8).map { id("A/installation/$it") }
            assertEquals(if (adopted) 2 else 1, report["applied_config_revision"])
            assertEquals(id(if (adopted) "A/manifest/2" else "A/manifest"), report["applied_manifest_id"])
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

    @Test fun `A는 일주일 전 정책을 바꿨고 설치 일부만 새 판 적용을 보고했다`() {
        val manifests = a.rows.getValue("enrollment.manifests").sortedBy { it["version"] as Int }
        assertEquals(listOf(1 to false, 2 to true), manifests.map { it["version"] to it["is_active"] })
        assertEquals(listOf("2026-07-29T15:00:00Z", "2026-09-20T15:00:00Z"), manifests.map { it["activated_at"] })
        val (v1, v2) = manifests.map { json.readTree(it["manifest"].toString()) }
        // 바뀐 것은 판 번호와 도구 세부 수집뿐이다. 사용량 시그널과 원문(프롬프트·응답) 선택은 그대로다.
        assertEquals(listOf(1, 2), listOf(v1, v2).map { it.path("config_revision").asInt() })
        assertEquals(v1.path("signals"), v2.path("signals"))
        assertEquals(listOf(false, true), listOf(v1, v2).map { it.at("/privacy/collect_tool_details").asBoolean() })
        fun others(manifest: JsonNode) = manifest.path("privacy").properties().filter { it.key != "collect_tool_details" }.map { it.key to it.value.asBoolean() }
        assertEquals(others(v1), others(v2))
        assertEquals(listOf(false, false), listOf(v2.at("/privacy/collect_user_prompts"), v2.at("/privacy/collect_assistant_responses")).map { it.asBoolean() })
        // 새 판의 적용 확인은 적용한 설치에만 있다. 두 번째 설치는 새 판으로 등록했으나 적용을 보고하지 않았다.
        val onV2 = a.rows.getValue("enrollment.installation_manifest_assignments").filter { it["manifest_id"] == id("A/manifest/2") }
        assertEquals((2..8).map { id("A/installation/$it") }, onV2.filter { it["applied_at"] != null }.map { it["installation_id"] })
        assertEquals(listOf(id("A/installation/2/secondary")), onV2.filter { it["applied_at"] == null }.map { it["installation_id"] })
        assertTrue(onV2.filter { it["applied_at"] != null }.all { it["applied_at"].toString() >= "2026-09-20T15:00:00Z" })
        // C는 판 하나만 있다.
        assertEquals(listOf(1 to true), c.rows.getValue("enrollment.manifests").map { it["version"] to it["is_active"] })
    }

    @Test fun `A만 회수 기준과 집계 보존을 저장했고 그 판은 manifest 판과 따로다`() {
        // ADR 0046: 저장값은 허용 목록 안이고 판 1, 저장한 사람은 정책을 바꾼 관리자, 저장 시각은 정책을 바꾼 날이다.
        val stored = a.rows.getValue("enrollment.organization_policy_settings").single()
        assertEquals(listOf<Any?>(id("A"), 30, 24, 1, "2026-09-20T15:00:00Z", id("A/member/1")),
            listOf("tenant_id", "reclaim_idle_days", "aggregate_retention_months", "version", "updated_at", "updated_by").map { stored[it] })
        assertTrue(listOf(b, c).none { it.rows.containsKey("enrollment.organization_policy_settings") })
    }

    @Test fun `A만 알림 규칙 셋을 켰고 사용 기록에는 허용 목록 밖 모델의 호출이 있다`() {
        // ADR 0051: 켠 규칙은 근거가 있어야 한다 — 급증은 수집 구간 보고(A 에 있다), 모델·도구는 비지 않은 목록. 한도 초과는 켜지 않는다.
        val rules = a.rows.getValue("enrollment.organization_alert_rules")
        assertEquals(setOf("spend_spike", "model_not_allowed", "tool_unapproved"), rules.map { it["rule_id"] }.toSet())
        assertTrue(rules.all { it["enabled"] == true && it["updated_at"] == "2026-09-20T15:00:00Z" })
        assertTrue(a.rows.getValue("enrollment.installation_collection_segments").isNotEmpty())
        val allowed = a.rows.getValue("enrollment.organization_alert_list_entries").filter { it["list_id"] == "allowed_models" }.map { it["entry"].toString() }
        fun allowedModel(model: String) = allowed.any { if (it.endsWith("*")) model.startsWith(it.dropLast(1)) else model == it }
        // 켠 시각 24시간 전부터의 사용 대표 행 중 허용 목록 밖 모델이 있어야 평가가 알림을 만든다.
        val since = "2026-09-19T15:00:00Z"
        val violating = a.events.filter { it["event_type"] == "model.response.usage" && it["source_time"].toString() >= since && !allowedModel(it["model"].toString()) }
            .map { it["model"] }.toSet()
        assertEquals(setOf("claude-opus-4", "o3"), violating)
        assertTrue(listOf(b, c).none { it.rows.containsKey("enrollment.organization_alert_rules") })
        // 프론트 fixture 도 같은 규칙·목록을 싣는다.
        val fixture = json.readTree(encode(frontendFixture(a)))
        assertEquals(setOf("spend_spike", "model_not_allowed", "tool_unapproved"), fixture.path("alertRules").toList().map { it.path("ruleId").asString() }.toSet())
        assertEquals(allowed.sorted(), fixture.path("alertLists").path("allowed_models").toList().map { it.asString() })
    }

    @Test fun `fixture 의 관측 매핑은 enrollment 마이그레이션의 매핑과 같다`() {
        val sql = requireNotNull(javaClass.getResourceAsStream("/db/migration/V18__vendor_catalog_observed_products.sql")).use { it.readBytes().decodeToString() }
        val pairs = Regex("""\('([a-z_]+)', '([a-z_]+)'\)""").findAll(sql).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(pairs, OBSERVED_PRODUCTS)
    }

    @Test fun `C는 어느 카탈로그 제품에도 매핑되지 않는 관측을 하나 갖고 그것은 사용량 행이 아니다`() {
        val unmapped = c.events.filter { it["product"] !in OBSERVED_PRODUCTS.keys }
        assertEquals(listOf("unknown"), unmapped.map { it["product"] })
        val row = unmapped.single()
        assertEquals(listOf("generic", "vendor.unknown", "none"), listOf(row["mapping_status"], row["event_type"], row["usage_role"]))
        assertTrue(listOf("tokens_input", "cost_estimated_usd", "session_id", "model").all { row[it] == null })
        // A의 관측은 모두 매핑된 제품이다.
        assertTrue(a.events.all { it["product"] in OBSERVED_PRODUCTS.keys })
    }

    @Test fun `A는 제품이 섞인 팀과 한 제품만 쓰는 팀을 모두 갖는다`() {
        // 팀별 제품 집계(대시보드 ADR 0045)가 섞인 팀·단일 제품 팀을 모두 보여 줄 수 있어야 한다. 최근 28일 기준.
        val start = date.minusDays(28).atStartOfDay(seoul).toInstant().toString()
        val byTeam = a.events.filter { it["source_time"].toString() >= start && it["team_id_as_of"] != null }
            .groupBy { it["team_id_as_of"] }.mapValues { (_, rows) -> rows.map { it["product"] }.toSet() }
        assertTrue(byTeam.values.any { it == setOf("claude_code", "codex") })
        assertTrue(byTeam.values.any { it.size == 1 })
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
        val order = resetStatements("A", tables + setOf("operations", "operation_targets", "retention_cleanup_requests")).map { it.substringAfter("enrollment.").substringBefore(" ") }
        assertTrue(order.indexOf("operation_targets") < order.indexOf("operations") && order.indexOf("operations") < order.indexOf("members"))
        // 보존 정리 요청은 작업을 가리킨다(ADR 0047). 작업보다 먼저 지운다.
        assertTrue(order.indexOf("retention_cleanup_requests") in 0 until order.indexOf("operations"))
        // 설치 보고는 설치를 가리킨다. 설치보다 먼저 지운다.
        val reports = resetStatements("A", tables + setOf("installation_heartbeats", "installation_collection_segments")).map { it.substringAfter("enrollment.").substringBefore(" ") }
        assertTrue(listOf("installation_heartbeats", "installation_collection_segments").all { it in reports && reports.indexOf(it) < reports.indexOf("installations") })
        // 조직 정책 설정은 조직과 저장한 구성원을 가리킨다. 구성원보다 먼저 지운다.
        val policies = resetStatements("A", tables + "organization_policy_settings")
        assertTrue(policies.single { "organization_policy_settings" in it }.let { policies.indexOf(it) < policies.indexOfFirst { s -> "enrollment.members " in s } })
        // 알림 규칙·목록·확인(ADR 0051)은 구성원을 가리킨다. 항목은 목록보다, 모두 구성원보다 먼저 지운다.
        val alerts = resetStatements("A", tables + setOf("alert_acknowledgements", "organization_alert_list_entries", "organization_alert_lists", "organization_alert_rules"))
            .map { it.substringAfter("enrollment.").substringBefore(" ") }
        assertTrue(alerts.indexOf("organization_alert_list_entries") < alerts.indexOf("organization_alert_lists"))
        assertTrue(listOf("alert_acknowledgements", "organization_alert_list_entries", "organization_alert_lists", "organization_alert_rules")
            .all { alerts.indexOf(it) in 0 until alerts.indexOf("members") })
        // 회수·복원 기록(ADR 0049)은 작업·좌석을 가리킨다. 좌석·작업보다 먼저 지운다.
        val controls = resetStatements("A", tables + setOf("seat_controls", "seat_reclaim_previews", "seat_assignments", "operations"))
            .map { it.substringAfter("enrollment.").substringBefore(" ") }
        assertTrue(listOf("seat_controls", "seat_reclaim_previews").all { controls.indexOf(it) in 0 until minOf(controls.indexOf("seat_assignments"), controls.indexOf("operations")) })
    }
}
