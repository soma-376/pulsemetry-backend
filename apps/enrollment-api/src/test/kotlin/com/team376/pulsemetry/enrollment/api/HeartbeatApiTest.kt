package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.auth.AuthTestClock
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.support.ContractSchemas
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationStatus
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val RUN = "b3f1c2a49d5e4f60a1b2c3d4e5f60718"

/**
 * `POST /v1/installations/{installation_id}/heartbeat` (허브 `contracts/enrollment-api.md` §7, ADR 0040). 실제 HTTP 와 PostgreSQL 로 본다.
 * 기대값은 계약에서 온다 — 요청·응답은 telemetryctl 의 스키마 원본과 대조한다. 주기 5분, `Retry-After` 7초, 이력 보존 30일.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.heartbeat.enabled=true", "pulsemetry.heartbeat.report-interval=PT5M", "pulsemetry.heartbeat.retry-after=PT7S",
        "pulsemetry.heartbeat.history-retention=P30D"])
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class HeartbeatApiTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var data: EnrollmentTestData
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var clock: AuthTestClock
    private val http = HttpClient.newHttpClient()
    private val start = Instant.parse("2026-09-09T12:00:00Z")
    private val enrolled = Instant.parse("2026-09-01T00:00:00Z")

    private lateinit var tenant: UUID
    private lateinit var member: UUID
    private lateinit var installation: UUID
    private lateinit var token: String

    @BeforeEach fun setUp() {
        data.reset()
        clock.now = start
        tenant = data.tenant().id
        member = data.member(tenant).id
        installation = install(tenant, member)
        token = data.credential(installation)
    }

    private fun install(tenantId: UUID, memberId: UUID, status: InstallationStatus = InstallationStatus.active): UUID {
        val id = data.installation(tenantId, memberId, data.invitation(tenantId, memberId, InvitationCode.generate()).id, status).id
        jdbc.sql("UPDATE enrollment.installations SET created_at=:at, updated_at=:at WHERE id=:id").param("at", Timestamp.from(enrolled)).param("id", id).update()
        return id
    }

    /** 데몬이 보내는 본문. [skew] 는 데몬 시계가 서버보다 앞선 정도다. 시각은 서버 시각 기준으로 주고 여기서 데몬 시계로 옮긴다. */
    private fun report(revision: Any? = 3, run: String = RUN, mode: String = "local", forwarding: Boolean = true,
        receivingFor: Duration? = Duration.ofMinutes(10), delivered: Long = 120, lost: Long = 0, pending: Long = 2,
        lastDeliveredAgo: Duration? = Duration.ofSeconds(30), skew: Duration = Duration.ZERO, version: String = "0.2.0"): String {
        val sent = clock.now.plus(skew)
        return mapper.writeValueAsString(mapOf(
            "sent_at" to sent.toString(),
            "daemon" to mapOf("version" to version, "platform" to "darwin", "architecture" to "arm64", "run_id" to run),
            "applied_config_revision" to revision,
            "collection" to mapOf("mode" to mode, "receiving_since" to receivingFor?.let { sent.minus(it).toString() }, "forwarding" to forwarding,
                "delivered" to delivered, "lost" to lost, "pending" to pending, "last_delivered_at" to lastDeliveredAgo?.let { sent.minus(it).toString() }),
        ))
    }

    private fun post(body: String, id: Any = installation, authorization: String? = "Bearer $token", contentType: String = "application/json"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port/v1/installations/$id/heartbeat")).header("Content-Type", contentType)
        authorization?.let { builder.header("Authorization", it) }
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun ok(response: HttpResponse<String>): JsonNode {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return mapper.readTree(response.body())
    }

    private fun rejected(response: HttpResponse<String>, status: Int, code: String) {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status)
        assertThat(mapper.readTree(response.body()).propertyNames()).containsExactlyInAnyOrder("error", "message")
        assertThat(mapper.readTree(response.body()).path("error").asString()).isEqualTo(code)
    }

    private fun instant(sql: String, id: UUID = installation): Instant? =
        jdbc.sql(sql).param("id", id).query { rs, _ -> rs.getTimestamp(1)?.toInstant() }.optional().orElse(null)

    private fun lastSeen(id: UUID = installation) = jdbc.sql("SELECT last_seen_at FROM enrollment.installations WHERE id=:id").param("id", id)
        .query { rs, _ -> listOf(rs.getTimestamp(1)?.toInstant()) }.single().single()

    private fun segments(id: UUID = installation): List<Triple<Instant, Instant, Long>> = jdbc.sql(
        "SELECT from_at,to_at,lost FROM enrollment.installation_collection_segments WHERE installation_id=:id ORDER BY to_at,from_at").param("id", id)
        .query { rs, _ -> Triple(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getLong(3)) }.list()

    private fun appliedAt(manifestId: UUID, id: UUID = installation): List<Instant?> = jdbc.sql(
        "SELECT applied_at FROM enrollment.installation_manifest_assignments WHERE installation_id=:id AND manifest_id=:manifest")
        .param("id", id).param("manifest", manifestId).query { rs, _ -> listOf(rs.getTimestamp(1)?.toInstant()) }.list().map { it.single() }

    // ── 계약 ─────────────────────────────────────────────────────────────────

    @Test fun `응답은 계약의 네 키이고 스키마를 만족하며 캐시하지 않는다`() {
        data.activeManifest(tenant, member, version = 3)
        val body = report()
        assertThat(ContractSchemas.validate(ContractSchemas.heartbeatRequestSchema(), body)).isEmpty()

        val response = post(body)
        val json = ok(response)
        val errors = ContractSchemas.validate(ContractSchemas.heartbeatResponseSchema(), response.body())
        assertThat(errors).describedAs(ContractSchemas.describe(errors)).isEmpty()
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
            "received_at", "expected_config_revision", "acknowledged_config_revision", "report_interval_seconds")
        assertThat(Instant.parse(json.path("received_at").asString())).isEqualTo(start)
        assertThat(json.path("expected_config_revision").asInt()).isEqualTo(3)
        assertThat(json.path("acknowledged_config_revision").asInt()).isEqualTo(3)
        assertThat(json.path("report_interval_seconds").asInt()).isEqualTo(300)
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
    }

    @Test fun `계약 스키마가 받는 본문은 받고 거부하는 본문은 400이다`() {
        val baseDaemon = """{"version":"0.2.0","platform":"darwin","architecture":"arm64","run_id":"$RUN"}"""
        val baseCollection = """{"mode":"local","receiving_since":"2026-09-09T11:50:00Z","forwarding":true,"delivered":120,"lost":0,"pending":2,"last_delivered_at":"2026-09-09T11:59:30Z"}"""
        val daemon = baseDaemon
        val collection = baseCollection
        fun body(daemon: String = baseDaemon, revision: String = "3", collection: String = baseCollection, sent: String = "\"2026-09-09T12:00:00Z\"", extra: String = "") =
            """{"sent_at":$sent,"daemon":$daemon,"applied_config_revision":$revision,"collection":$collection$extra}"""
        fun collecting(change: String) = collection.replace(change.substringBefore("=>"), change.substringAfter("=>"))
        val bodies = listOf(
            body(),
            body(extra = ""","future_field":{"x":1}"""),
            body(daemon = daemon.replace("\"arm64\"", "\"arm64\",\"hostname\":\"ignored\"")),
            body(collection = """{"mode":"direct","receiving_since":null,"forwarding":false,"delivered":0,"lost":0,"pending":0,"last_delivered_at":null}"""),
            body(revision = "3.0"),
            body(sent = "\"2026-09-09T12:00:00.123456789Z\""),
            body(daemon = daemon.replace("darwin", "linux").replace("arm64", "amd64")),
            body(daemon = """{"version":"0.2.0","platform":"darwin","architecture":"arm64"}"""),
            body(daemon = daemon.replace("darwin", "macos")),
            body(daemon = daemon.replace("\"0.2.0\"", "\"\"")),
            body(daemon = daemon.replace("\"0.2.0\"", "\"${"9".repeat(65)}\"")),
            body(daemon = daemon.replace(RUN, "short")),
            body(daemon = daemon.replace(RUN, "has space in it 0123456789")),
            body(daemon = daemon.replace("\"arm64\"", "64")),
            body(daemon = "[]"),
            body(revision = "0"),
            body(revision = "\"3\""),
            body(revision = "3.5"),
            body(revision = "null"),
            body(sent = "\"2026-09-09 12:00:00\""),
            body(sent = "\"2026-09-09T21:00:00+09:00\""),
            body(sent = "1757419200"),
            body(collection = collecting("\"local\"=>\"proxy\"")),
            body(collection = collecting("\"forwarding\":true=>\"forwarding\":\"true\"")),
            body(collection = collecting("\"delivered\":120=>\"delivered\":-1")),
            body(collection = collecting("\"lost\":0=>\"lost\":\"0\"")),
            body(collection = collecting("\"pending\":2,=>")),
            body(collection = collecting("\"receiving_since\":\"2026-09-09T11:50:00Z\",=>")),
            body(collection = collecting("\"2026-09-09T11:50:00Z\"=>\"yesterday\"")),
            body(collection = collecting(",\"last_delivered_at\":\"2026-09-09T11:59:30Z\"=>")),
            """{"daemon":$daemon,"applied_config_revision":3,"collection":$collection}""",
            """{"sent_at":"2026-09-09T12:00:00Z","applied_config_revision":3,"collection":$collection}""",
            """{"sent_at":"2026-09-09T12:00:00Z","daemon":$daemon,"applied_config_revision":3}""",
            "[]",
            "null",
        )
        var accepted = 0
        for (body in bodies) {
            val valid = ContractSchemas.validate(ContractSchemas.heartbeatRequestSchema(), body).isEmpty()
            val response = post(body)
            assertThat(response.statusCode()).describedAs("%s → %s", body, response.body()).isEqualTo(if (valid) 200 else 400)
            if (valid) accepted++ else rejected(response, 400, "invalid_request")
        }
        // 목록이 한쪽으로 쏠리지 않았는지 — 받는 본문과 거부하는 본문이 둘 다 있다.
        assertThat(accepted).isEqualTo(7)
    }

    @Test fun `JSON이 아니거나 너무 크거나 보낸 시각보다 뒤인 시각은 400이다`() {
        rejected(post("{"), 400, "invalid_request")
        rejected(post(""), 400, "invalid_request")
        rejected(post(report(), contentType = "text/plain"), 400, "invalid_request")
        rejected(post(report().dropLast(1) + ""","padding":"${"x".repeat(17_000)}"}"""), 400, "invalid_request")
        // 스키마로는 표현하지 못하는 계약의 규칙이다.
        rejected(post(report(receivingFor = Duration.ofSeconds(-1))), 400, "invalid_request")
        rejected(post(report(lastDeliveredAgo = Duration.ofSeconds(-1))), 400, "invalid_request")
        assertThat(lastSeen()).isNull()
        assertThat(ok(post(report(receivingFor = Duration.ZERO, lastDeliveredAgo = Duration.ZERO))).path("received_at").asString()).isNotBlank()
    }

    // ── 인증 ─────────────────────────────────────────────────────────────────

    @Test fun `자격증명이 없거나 무효면 본문을 보지 않고 401이다`() {
        val telemetry = data.telemetryToken(installation)
        val revoked = data.credential(installation, revoked = true)
        for (authorization in listOf(null, "", "Bearer ", "Bearer pit_unknown", "Basic $token", "Bearer $telemetry", "Bearer $revoked")) {
            rejected(post(report(), authorization = authorization), 401, "unauthorized")
            // 본문이 틀려도 인증 실패가 먼저다.
            rejected(post("not json", authorization = authorization), 401, "unauthorized")
        }
        assertThat(lastSeen()).isNull()
        assertThat(data.countRows("installation_heartbeats")).isEqualTo(0)
    }

    @Test fun `경로의 설치가 자격증명의 설치와 다르면 403이고 어느 쪽에도 기록하지 않는다`() {
        val other = install(tenant, data.member(tenant).id)
        rejected(post(report(), id = other), 403, "forbidden")
        rejected(post(report(), id = UUID.randomUUID()), 403, "forbidden")
        rejected(post(report(), id = "not-a-uuid"), 403, "forbidden")
        assertThat(lastSeen()).isNull()
        assertThat(lastSeen(other)).isNull()
        assertThat(data.countRows("installation_heartbeats")).isEqualTo(0)
        assertThat(data.countRows("installation_collection_segments")).isEqualTo(0)
        // 대소문자만 다른 같은 ID 는 같은 설치다.
        ok(post(report(), id = installation.toString().uppercase()))
    }

    @Test fun `폐기된 설치는 403 installation_revoked다`() {
        val revokedInstallation = install(tenant, member, InstallationStatus.revoked)
        val credential = data.credential(revokedInstallation)
        rejected(post(report(), id = revokedInstallation, authorization = "Bearer $credential"), 403, "installation_revoked")
        assertThat(lastSeen(revokedInstallation)).isNull()
    }

    // ── 생존과 최신 상태 ─────────────────────────────────────────────────────

    @Test fun `생존 시각은 서버가 받은 시각이고 데몬 시계가 틀려도 구간의 길이는 맞다`() {
        clock.now = start.plusSeconds(90)
        val updatedBefore = instant("SELECT updated_at FROM enrollment.installations WHERE id=:id")
        // 데몬 시계가 세 시간 앞서 있다.
        ok(post(report(skew = Duration.ofHours(3), version = "0.3.1", delivered = 40, lost = 1, pending = 5)))

        assertThat(lastSeen()).isEqualTo(clock.now)
        assertThat(data.singleColumn("SELECT client_version FROM enrollment.installations WHERE id='$installation'")).isEqualTo("0.3.1")
        assertThat(instant("SELECT updated_at FROM enrollment.installations WHERE id=:id")).isEqualTo(updatedBefore)
        val row = jdbc.sql("SELECT * FROM enrollment.installation_heartbeats WHERE installation_id=:id").param("id", installation).query { rs, _ ->
            mapOf("received" to rs.getTimestamp("received_at").toInstant(), "run" to rs.getString("run_id"), "version" to rs.getString("daemon_version"),
                "architecture" to rs.getString("architecture"), "revision" to rs.getInt("applied_config_revision"), "mode" to rs.getString("mode"),
                "forwarding" to rs.getBoolean("forwarding"), "since" to rs.getTimestamp("receiving_since").toInstant(),
                "delivered" to rs.getLong("delivered"), "lost" to rs.getLong("lost"), "pending" to rs.getLong("pending"),
                "last" to rs.getTimestamp("last_delivered_at").toInstant())
        }.single()
        assertThat(row).isEqualTo(mapOf("received" to clock.now, "run" to RUN, "version" to "0.3.1", "architecture" to "arm64", "revision" to 3, "mode" to "local",
            "forwarding" to true, "since" to clock.now.minus(Duration.ofMinutes(10)), "delivered" to 40L, "lost" to 1L, "pending" to 5L,
            "last" to clock.now.minusSeconds(30)))
        assertThat(segments()).containsExactly(Triple(clock.now.minus(Duration.ofMinutes(10)), clock.now, 1L))
    }

    @Test fun `같은 상태를 다시 보고해도 설치당 최신 상태는 한 행이다`() {
        ok(post(report()))
        clock.now = start.plusSeconds(300)
        ok(post(report(receivingFor = Duration.ofMinutes(15), delivered = 130)))
        assertThat(data.countRows("installation_heartbeats")).isEqualTo(1)
        assertThat(lastSeen()).isEqualTo(clock.now)
        assertThat(data.singleColumn("SELECT delivered::text FROM enrollment.installation_heartbeats")).isEqualTo("130")
    }

    @Test fun `50자를 넘는 데몬 버전은 최신 상태에 그대로 남고 설치 행에는 잘라 넣는다`() {
        val long = "1.2.3-" + "a".repeat(58)
        assertThat(long).hasSize(64)
        ok(post(report(version = long)))
        assertThat(data.singleColumn("SELECT daemon_version FROM enrollment.installation_heartbeats")).isEqualTo(long)
        assertThat(data.singleColumn("SELECT client_version FROM enrollment.installations WHERE id='$installation'")).isEqualTo(long.take(50))
    }

    // ── 정책 적용 확인 ───────────────────────────────────────────────────────

    @Test fun `적용한 판을 처음 보고받은 시각이 적용 확인 시각이고 다시 보고해도 바뀌지 않는다`() {
        val manifest = data.activeManifest(tenant, member, version = 3).id
        assertThat(appliedAt(manifest)).isEmpty()

        clock.now = start.plusSeconds(10)
        assertThat(ok(post(report(revision = 3))).path("acknowledged_config_revision").asInt()).isEqualTo(3)
        assertThat(appliedAt(manifest)).containsExactly(clock.now)

        val first = clock.now
        clock.now = start.plusSeconds(310)
        assertThat(ok(post(report(revision = 3))).path("acknowledged_config_revision").asInt()).isEqualTo(3)
        assertThat(appliedAt(manifest)).containsExactly(first)
        assertThat(data.singleColumn("SELECT applied_manifest_id::text FROM enrollment.installation_heartbeats")).isEqualTo(manifest.toString())
    }

    @Test fun `등록 때 만든 배정 행은 보고를 받아야 적용 시각이 채워진다`() {
        val manifest = data.activeManifest(tenant, member, version = 3).id
        jdbc.sql("INSERT INTO enrollment.installation_manifest_assignments(installation_id,manifest_id,assigned_at) VALUES (:id,:manifest,:at)")
            .param("id", installation).param("manifest", manifest).param("at", Timestamp.from(enrolled)).update()
        assertThat(appliedAt(manifest)).containsExactly(null)

        ok(post(report(revision = 3)))
        assertThat(appliedAt(manifest)).containsExactly(start)
        assertThat(instant("SELECT assigned_at FROM enrollment.installation_manifest_assignments WHERE installation_id=:id")).isEqualTo(enrolled)
    }

    @Test fun `서버가 그 조직의 판으로 갖고 있지 않은 판은 적용 확인하지 않지만 생존은 기록한다`() {
        data.activeManifest(tenant, member, version = 3)
        val otherTenant = data.tenant().id
        val foreign = data.activeManifest(otherTenant, data.member(otherTenant).id, version = 7).id

        for (revision in listOf(99, 7)) {
            clock.now = clock.now.plusSeconds(60)
            val json = ok(post(report(revision = revision)))
            assertThat(json.path("acknowledged_config_revision").isNull).describedAs("판 %s", revision).isTrue()
            assertThat(json.path("expected_config_revision").asInt()).isEqualTo(3)
            assertThat(lastSeen()).isEqualTo(clock.now)
        }
        assertThat(data.countRows("installation_manifest_assignments")).isEqualTo(0)
        assertThat(appliedAt(foreign)).isEmpty()
        assertThat(data.singleColumn("SELECT applied_config_revision::text FROM enrollment.installation_heartbeats")).isEqualTo("7")
        assertThat(data.singleColumn("SELECT applied_manifest_id::text FROM enrollment.installation_heartbeats")).isNull()
    }

    @Test fun `응답의 기대 판은 그 조직의 활성 판이고 활성 판이 없으면 null이다`() {
        assertThat(ok(post(report(revision = 1))).path("expected_config_revision").isNull).isTrue()

        val old = data.activeManifest(tenant, member, version = 3).id
        assertThat(ok(post(report(revision = 3))).path("expected_config_revision").asInt()).isEqualTo(3)

        // 관리자가 정책을 바꿨다. 설치는 아직 옛 판을 집행한다.
        jdbc.sql("UPDATE enrollment.manifests SET is_active=false WHERE id=:id").param("id", old).update()
        data.activeManifest(tenant, member, version = 4)
        val json = ok(post(report(revision = 3)))
        assertThat(json.path("expected_config_revision").asInt()).isEqualTo(4)
        assertThat(json.path("acknowledged_config_revision").asInt()).isEqualTo(3)
    }

    // ── 수집 구간 ────────────────────────────────────────────────────────────

    @Test fun `손실 없이 이어지는 보고는 한 구간을 늘리고 손실은 그 사이만 따로 남긴다`() {
        val t0 = start
        ok(post(report()))
        assertThat(segments()).containsExactly(Triple(t0.minus(Duration.ofMinutes(10)), t0, 0L))

        val t1 = t0.plusSeconds(300)
        clock.now = t1
        ok(post(report(receivingFor = Duration.ofMinutes(15), delivered = 150)))
        assertThat(segments()).containsExactly(Triple(t0.minus(Duration.ofMinutes(10)), t1, 0L))

        // 두 개를 잃었다. 잃은 것은 직전 보고와 이번 보고 사이다.
        val t2 = t1.plusSeconds(300)
        clock.now = t2
        ok(post(report(receivingFor = Duration.ofMinutes(20), delivered = 160, lost = 2)))
        assertThat(segments()).containsExactly(Triple(t0.minus(Duration.ofMinutes(10)), t1, 0L), Triple(t1, t2, 2L))

        // 손실이 이어지면 손실 구간이 늘어난다.
        val t3 = t2.plusSeconds(300)
        clock.now = t3
        ok(post(report(receivingFor = Duration.ofMinutes(25), delivered = 165, lost = 3)))
        assertThat(segments()).containsExactly(Triple(t0.minus(Duration.ofMinutes(10)), t1, 0L), Triple(t1, t3, 3L))

        // 더 잃지 않으면 손실 없는 새 구간이 시작된다.
        val t4 = t3.plusSeconds(300)
        clock.now = t4
        ok(post(report(receivingFor = Duration.ofMinutes(30), delivered = 190, lost = 3)))
        assertThat(segments()).containsExactly(Triple(t0.minus(Duration.ofMinutes(10)), t1, 0L), Triple(t1, t3, 3L), Triple(t3, t4, 0L))
    }

    @Test fun `데몬이 다시 뜨면 새 구간이고 그 사이는 어느 구간에도 들지 않는다`() {
        ok(post(report()))
        val restarted = start.plusSeconds(900)
        clock.now = restarted
        // 새 프로세스: 누적 개수는 0 부터, 수신기는 1분 전에 떴다.
        ok(post(report(run = "c".repeat(32), receivingFor = Duration.ofMinutes(1), delivered = 3, lastDeliveredAgo = Duration.ofSeconds(5))))
        assertThat(segments()).containsExactly(
            Triple(start.minus(Duration.ofMinutes(10)), start, 0L), Triple(restarted.minus(Duration.ofMinutes(1)), restarted, 0L))
    }

    @Test fun `새 프로세스의 첫 보고에 손실이 있으면 그 구간 전체가 손실 구간이다`() {
        ok(post(report(lost = 4)))
        assertThat(segments()).containsExactly(Triple(start.minus(Duration.ofMinutes(10)), start, 4L))
    }

    @Test fun `수집 중이 아닌 보고는 구간을 만들지 않고 다시 수집하면 직전 보고 뒤부터 새 구간이다`() {
        ok(post(report(mode = "direct", forwarding = false, receivingFor = null, delivered = 0, pending = 0, lastDeliveredAgo = null)))
        clock.now = start.plusSeconds(60)
        ok(post(report(forwarding = false, delivered = 0, pending = 0, lastDeliveredAgo = null)))
        clock.now = start.plusSeconds(120)
        ok(post(report(receivingFor = null)))
        assertThat(segments()).isEmpty()
        assertThat(data.singleColumn("SELECT mode FROM enrollment.installation_heartbeats")).isEqualTo("local")
        assertThat(lastSeen()).isEqualTo(clock.now)

        // 같은 프로세스가 수집을 시작했다고 보고한다. 수신기가 30분 전부터 떠 있었다 해도 직전 보고보다 앞은 주장하지 않는다.
        val resumed = start.plusSeconds(420)
        clock.now = resumed
        ok(post(report(receivingFor = Duration.ofMinutes(30))))
        assertThat(segments()).containsExactly(Triple(start.plusSeconds(120), resumed, 0L))
    }

    @Test fun `수신기가 직전 보고 뒤에 다시 떴으면 그 시각부터 새 구간이다`() {
        ok(post(report()))
        val later = start.plusSeconds(600)
        clock.now = later
        ok(post(report(receivingFor = Duration.ofMinutes(2), delivered = 125)))
        assertThat(segments()).containsExactly(
            Triple(start.minus(Duration.ofMinutes(10)), start, 0L), Triple(later.minus(Duration.ofMinutes(2)), later, 0L))
    }

    @Test fun `같은 프로세스의 누적 개수가 줄면 이전 보고와 잇지 않는다`() {
        ok(post(report(delivered = 120, lost = 5)))
        val later = start.plusSeconds(300)
        clock.now = later
        ok(post(report(receivingFor = Duration.ofMinutes(15), delivered = 10, lost = 1)))
        assertThat(segments()).containsExactly(Triple(start.minus(Duration.ofMinutes(10)), start, 5L), Triple(later, later, 1L))
    }

    @Test fun `등록보다 앞선 수집은 등록 시각에서 자른다`() {
        clock.now = enrolled.plusSeconds(3600)
        ok(post(report(receivingFor = Duration.ofDays(3))))
        assertThat(segments()).containsExactly(Triple(enrolled, clock.now, 0L))
    }

    @Test fun `보존 기간이 지난 구간은 다음 보고 때 지운다`() {
        ok(post(report()))
        clock.now = start.plus(Duration.ofDays(30)).plusSeconds(1)
        ok(post(report(run = "d".repeat(32), receivingFor = Duration.ofMinutes(1))))
        assertThat(segments()).containsExactly(Triple(clock.now.minus(Duration.ofMinutes(1)), clock.now, 0L))
        // 다른 설치의 구간은 그 설치가 보고할 때만 지운다.
        assertThat(data.countRows("installation_collection_segments")).isEqualTo(1)
    }

    @Test fun `같은 설치의 보고가 동시에 와도 최신 상태는 한 행이고 구간이 겹쳐 생기지 않는다`() {
        ok(post(report()))
        clock.now = start.plusSeconds(300)
        val body = report(receivingFor = Duration.ofMinutes(15), delivered = 140)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val statuses = pool.invokeAll(List(12) { Callable { post(body).statusCode() } }).map { it.get(30, TimeUnit.SECONDS) }
            assertThat(statuses).containsOnly(200)
        } finally { pool.shutdownNow() }
        assertThat(data.countRows("installation_heartbeats")).isEqualTo(1)
        assertThat(segments()).containsExactly(Triple(start.minus(Duration.ofMinutes(10)), clock.now, 0L))
    }

    // ── 장애 ─────────────────────────────────────────────────────────────────

    @Test fun `저장소 장애는 401이나 403이 아니라 503과 Retry-After이고 아무것도 남기지 않는다`() {
        data.activeManifest(tenant, member, version = 3)
        jdbc.sql("ALTER TABLE enrollment.installation_heartbeats RENAME TO installation_heartbeats_unavailable").update()
        try {
            val response = post(report())
            rejected(response, 503, "heartbeat_unavailable")
            assertThat(response.headers().firstValue("Retry-After")).hasValue("7")
            assertThat(response.body()).doesNotContain("installation_heartbeats", "SQL", "relation")
        } finally { jdbc.sql("ALTER TABLE enrollment.installation_heartbeats_unavailable RENAME TO installation_heartbeats").update() }
        assertThat(lastSeen()).isNull()
        assertThat(data.countRows("installation_manifest_assignments")).isEqualTo(0)
        assertThat(data.countRows("installation_collection_segments")).isEqualTo(0)
        ok(post(report()))
    }
}
