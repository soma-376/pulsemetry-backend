package com.team376.pulsemetry.devseed

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HistoricalSeedTest {
    @TempDir lateinit var temp: Path
    private val end = LocalDate.parse("2026-10-26")
    private fun config(environment: String = "local") = HistoricalSeedConfig(environment, end, Instant.parse("2026-10-27T02:00:00Z"),
        "https://ingest.example.test", "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_test", "test", "config:cognito-a")

    @Test fun `실행 전날 180일과 settle을 고정하며 prod 및 원격 HTTP를 거부한다`() {
        val cfg = config()
        assertEquals(180, cfg.endDate.toEpochDay() - cfg.startDate.toEpochDay() + 1)
        assertFailsWith<IllegalArgumentException> { cfg.copy(environment = "prod") }
        assertFailsWith<IllegalArgumentException> { cfg.copy(endDate = end.minusDays(1)) }
        assertFailsWith<IllegalArgumentException> { cfg.copy(snapshotAt = Instant.parse("2026-10-26T15:09:59Z")) }
        assertEquals(Instant.parse("2026-10-26T15:10:00Z"), cfg.copy(snapshotAt = Instant.parse("2026-10-26T15:10:00Z")).snapshotAt)
        assertFailsWith<IllegalArgumentException> { cfg.copy(environment = "dev", endpoint = "http://localhost:4316") }
        assertFailsWith<IllegalArgumentException> { cfg.copy(oidcIssuer = "http://remote.example.test") }
        assertFailsWith<IllegalArgumentException> { cfg.copy(oidcSecretRef = "actual-client-secret") }
        assertEquals("http://localhost:29082", cfg.copy(oidcIssuer = "http://localhost:29082").oidcIssuer)
    }

    @Test fun `400명과 50팀의 실제 소속 및 398개 설치 보고가 과거부터 연결된다`() {
        val cfg = config()
        val rows = somaPostgres(cfg)
        val members = rows.getValue("enrollment.members")
        val teams = rows.getValue("enrollment.teams")
        val installations = rows.getValue("enrollment.installations")
        assertEquals(400, members.size)
        assertEquals(50, teams.size)
        assertEquals(398, installations.size)
        assertEquals(mapOf("owner" to 1, "admin" to 1, "member" to 398), members.groupingBy { it["role"].toString() }.eachCount())
        assertTrue(members.all { it["status"] == "active" && it["oidc_subject"] == null })
        assertEquals(listOf("owner@seed-a.example.test", "admin@seed-a.example.test"), members.take(2).map { it["email"] })
        val memberships = rows.getValue("enrollment.team_memberships")
        assertEquals(400, memberships.size)
        assertEquals(somaTeamSizes, teams.map { team -> memberships.count { it["team_id"] == team["id"] } })
        val before = cfg.startDate.atStartOfDay(seoul).toInstant()
        assertTrue(members.all { Instant.parse(it["created_at"].toString()) < before })
        assertTrue(memberships.all { Instant.parse(it["joined_at"].toString()) < before && it["left_at"] == null })
        assertTrue(installations.all { Instant.parse(it["created_at"].toString()) < before && it["last_seen_at"] == cfg.snapshotAt.toString() })
        assertEquals(398, rows.getValue("enrollment.installation_heartbeats").size)
        assertEquals(installations.map { it["id"] }.toSet(), rows.getValue("enrollment.installation_collection_segments").map { it["installation_id"] }.toSet())
        assertTrue(rows.getValue("enrollment.installation_collection_segments").all {
            Instant.parse(it["from_at"].toString()) < before && Instant.parse(it["to_at"].toString()) == cfg.snapshotAt && it["lost"] == 0
        })
        val tenant = rows.getValue("enrollment.tenants").single()
        assertEquals("soma", tenant["slug"])
        assertEquals(false, tenant["sso_enabled"])
        assertEquals(true, tenant["oidc_require_verified_email"])
        assertEquals(cfg.oidcIssuer, tenant["oidc_issuer"])
        assertEquals(2, rows.getValue("enrollment.manifests").size)
        assertEquals(7, rows.getValue("enrollment.seat_assignments").size)
        val memberIds = members.map { it["id"] }.toSet()
        assertTrue(rows.getValue("enrollment.seat_assignments").all { it["member_id"] == null || it["member_id"] in memberIds })
        assertTrue(rows.keys.indexOf("enrollment.invitations") < rows.keys.indexOf("enrollment.installations"))
    }

    @Test fun `180일 모든 팀에 세션이 있고 주말 사용량을 낮추며 398명 모두 과거 사용한다`() {
        for (last in listOf(end, LocalDate.parse("2028-02-29"), LocalDate.parse("2026-01-01"))) {
            val cfg = config().copy(endDate = last, snapshotAt = last.plusDays(1).atTime(11, 0).atZone(seoul).toInstant())
            var pairs = 0
            val observed = mutableSetOf<Int>()
            var day = cfg.startDate
            while (day <= cfg.endDate) {
                val sessions = somaSessions(cfg, day)
                assertEquals(50, sessions.map { somaMemberTeams.getValue(it.memberIndex) }.toSet().size)
                assertEquals(sessions.size, sessions.map { it.key }.toSet().size)
                if (day.dayOfWeek.value >= 6) {
                    assertEquals(50, sessions.size)
                    assertEquals(100, sessions.sumOf { it.calls })
                } else assertTrue(sessions.sumOf { it.calls } >= 796)
                observed.addAll(sessions.map { it.memberIndex })
                pairs += 50
                day = day.plusDays(1)
            }
            assertEquals(9_000, pairs)
            assertEquals((2..399).toSet(), observed)
        }
    }

    @Test fun `10개 사용 프로필을 50팀에 적용해 토큰 비용 및 수신을 검증한다`() {
        val cfg = config()
        val rows = somaPostgres(cfg)
        for (day in listOf(end, end.minusDays(1), end.minusDays(49), cfg.startDate)) {
            val usage = detailedUsage(end, rows, somaSessions(cfg, day), cfg.context)
            validateHistoricalDay(cfg, day, usage)
            assertEquals(50, usage.events.map { it["team_id_as_of"] }.toSet().size)
            assertEquals(setOf("claude-code-exclusive-v1", "codex-responses-inclusive-v1"), usage.events.map { it["semantics_profile"] }.toSet())
            assertEquals(usage.ledger.size, usage.events.map { it["session_id"] }.toSet().size)
        }
    }

    @Test fun `팀별 일일 후보는 규모를 보존하고 9000세션 15400관측으로 398명을 순환한다`() {
        val cfg = config().copy(activityProfile = "team-daily")
        val rows = somaPostgres(cfg)
        val observed = mutableSetOf<Int>()
        var sessionCount = 0
        var eventCount = 0
        var cacheReads = 0L
        var cacheCreates = 0L
        val models = mutableSetOf<String>()
        val profiles = mutableSetOf<String>()
        var day = cfg.startDate
        while (day <= cfg.endDate) {
            val sessions = somaSessions(cfg, day)
            assertEquals(50, sessions.size)
            assertEquals((0..49).toSet(), sessions.map { somaMemberTeams.getValue(it.memberIndex) }.toSet())
            assertTrue(sessions.all { it.calls == if (day.dayOfWeek.value >= 6) 1 else 2 })
            val usage = detailedUsage(end, rows, sessions, cfg.context)
            validateHistoricalDay(cfg, day, usage)
            assertEquals(50, usage.ledger.size)
            observed.addAll(sessions.map { it.memberIndex })
            sessionCount += sessions.size
            eventCount += usage.events.size
            usage.events.forEach {
                cacheReads += (it.getValue("tokens_cache_read") as Number).toLong()
                cacheCreates += (it.getValue("tokens_cache_create") as Number).toLong()
                models += it.getValue("model").toString()
                profiles += it.getValue("semantics_profile").toString()
            }
            day = day.plusDays(1)
        }
        assertEquals(9_000, sessionCount)
        assertEquals(15_400, eventCount)
        assertEquals((2..399).toSet(), observed)
        assertTrue(cacheReads > 0 && cacheCreates > 0)
        assertEquals(seedModels.values.flatten().map { it.name }.toSet(), models)
        assertEquals(setOf("claude-code-exclusive-v1", "codex-responses-inclusive-v1"), profiles)
        assertEquals(somaPostgres(config()), rows)
        assertNotEquals(fingerprintOf(config().descriptor()), fingerprintOf(cfg.descriptor()))
        assertTrue("activityProfile" !in config().descriptor())
        assertFailsWith<IllegalArgumentException> { cfg.copy(activityProfile = "unknown") }
    }

    @Test fun `local과 dev는 신원만 분리하고 같은 기간의 토큰 비용 시각을 재현한다`() {
        val local = config()
        val dev = config("dev")
        val left = detailedUsage(end, somaPostgres(local), somaSessions(local, end), local.context)
        val right = detailedUsage(end, somaPostgres(dev), somaSessions(dev, end), dev.context)
        val totalsLeft = HistoricalTotals().apply { left.events.forEach(::add) }.value()
        val totalsRight = HistoricalTotals().apply { right.events.forEach(::add) }.value()
        assertEquals(totalsLeft, totalsRight)
        assertNotEquals(local.tenantId, dev.tenantId)
        for (column in listOf("member_id", "installation_id", "team_id_as_of", "session_id", "observation_id"))
            assertTrue(left.events.map { it[column] }.toSet().intersect(right.events.map { it[column] }.toSet()).isEmpty(), column)
        assertEquals(left.events.map { it["source_time"] }.sortedBy { it.toString() }, right.events.map { it["source_time"] }.sortedBy { it.toString() })
    }

    @Test fun `손상된 토큰이나 수신 건수는 파일 생성 전에 실패한다`() {
        val cfg = config()
        val usage = detailedUsage(end, somaPostgres(cfg), somaSessions(cfg, end), cfg.context)
        assertFailsWith<IllegalStateException> {
            validateHistoricalDay(cfg, end, usage.copy(events = usage.events.mapIndexed { i, row -> if (i == 0) row + ("tokens_total_derived" to 0L) else row }))
        }
        assertFailsWith<IllegalStateException> {
            validateHistoricalDay(cfg, end, usage.copy(ledger = usage.ledger.drop(1)))
        }
    }

    @Test fun `단계 적재 파일은 알림을 끄고 원래 규칙과 판을 활성화 계약에 고정한다`() {
        val cfg = config().copy(activityProfile = "team-daily", stageAlertRules = true)
        val original = somaPostgres(cfg).getValue("enrollment.organization_alert_rules")
        val output = temp.resolve("staged")
        val manifest = exportHistoricalSeed(cfg, output)
        @Suppress("UNCHECKED_CAST")
        val activation = manifest.getValue("activationRules") as List<Row>
        assertEquals(original.map { linkedMapOf("ruleId" to it.getValue("rule_id"),
            "desiredEnabled" to it.getValue("enabled"), "expectedVersion" to it.getValue("version")) }, activation)
        val pg = json.readTree(Files.readString(output.resolve("postgres.json")))
        val staged = pg.path("tables").first { it.path("table").asString() == "enrollment.organization_alert_rules" }.path("rows")
        assertEquals(original.size, staged.size())
        staged.forEachIndexed { index, rule ->
            assertEquals(false, rule.path("enabled").asBoolean())
            assertEquals(original[index].getValue("version").toString(), rule.path("version").asString())
            assertEquals(original[index].getValue("updated_at").toString(), rule.path("updated_at").asString())
        }
        val descriptor = cfg.descriptor() + ("activationRules" to activation)
        assertEquals(fingerprintOf(descriptor), manifest.getValue("configHash"))
        assertTrue("stageAlertRules" !in config().descriptor())
    }

    @Test fun `월별 파일은 2000행과 4MiB를 지키며 해시 필드 및 권한을 검증한다`() {
        val batches = mutableListOf<Row>()
        val writer = HistoricalBatchWriter(temp, "2026-10", "telemetry_events", "source_time", "observation_id", batches)
        repeat(2100) { index -> writer.add(mapOf("observation_id" to "id-$index", "source_time" to "2026-10-26T00:00:00Z", "payload" to "한".repeat(1000))) }
        writer.finish()
        assertEquals(2100, batches.sumOf { (it["rows"] as Number).toInt() })
        assertTrue(batches.size >= 2)
        for (batch in batches) {
            assertTrue((batch["rows"] as Int) in 1..SEED_BATCH_ROWS)
            assertTrue((batch["bytes"] as Int) <= SEED_BATCH_BYTES)
            val file = temp.resolve(batch["file"].toString())
            assertEquals(batch["sha256"], fileSha256(file))
            assertEquals(batch["bytes"].toString().toLong(), Files.size(file))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
        }
        val receipts = mutableListOf<Row>()
        val receiptWriter = HistoricalBatchWriter(temp, "2026-10", "telemetry_ingest_ledger", "received_time", "receipt_id", receipts)
        receiptWriter.add(mapOf("receipt_id" to "later", "received_time" to "2026-10-26T00:00:00.000000001Z"))
        receiptWriter.add(mapOf("receipt_id" to "earlier", "received_time" to "2026-10-26T00:00:00Z"))
        receiptWriter.finish()
        assertEquals("2026-10-26T00:00:00Z", receipts.single()["timeMin"])
        assertEquals("2026-10-26T00:00:00.000000001Z", receipts.single()["timeMax"])
    }

    @Test fun `기존 A와 C의 전체 콘텐츠 지문은 일반화 전과 같다`() {
        val date = LocalDate.parse("2026-10-11")
        assertEquals("58cb20d3edfda4a51f00976d669b69d0c2f39ab4defbcbcb4401afc54d90fbb2", scenario("A", date).fingerprint)
        assertEquals("bf75b04fa3d4a25a368706c7c42d45061c015cc444c4dbd1c9a7d3ca5cc53b68", scenario("C", date).fingerprint)
    }
}
