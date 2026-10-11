package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.mail.MailConfig
import com.team376.pulsemetry.enrollment.mail.MailProperties
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.support.MailpitServer
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationStatus
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationNotifier
import com.team376.pulsemetry.persistence.enrollment.mail.MailDispatcher
import com.team376.pulsemetry.persistence.enrollment.mail.MailOutbox
import com.team376.pulsemetry.persistence.enrollment.operation.Operation
import com.team376.pulsemetry.persistence.enrollment.operation.OperationReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID

/**
 * 설치 업데이트 안내 (ADR 0043). 안내는 설치를 쓰는 구성원에게 가는 메일이고, 작업(ADR 0039)의 대상 결과는 그 메일의 발송 결과다.
 * 발송 작업은 테스트가 직접 돌린다(주기는 하루). 활성 정책은 판 3이고 판 2는 그 전 판이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.management.invitation-accept-url=https://app.example.test/invite",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000",
        "pulsemetry.mail.enabled=true", "pulsemetry.mail.provider=smtp", "pulsemetry.mail.from=no-reply@example.test", "pulsemetry.mail.encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "pulsemetry.mail.dispatch-interval=PT24H", "pulsemetry.mail.retry-interval=PT5M", "pulsemetry.mail.max-attempts=3", "pulsemetry.mail.send-timeout=PT5S",
        "pulsemetry.mail.smtp.username=test-user", "pulsemetry.mail.smtp.password=test-password", "pulsemetry.mail.smtp.starttls=false"])
class InstallationNotificationApiTest : AbstractUserAuthApiTest() {
    @Autowired private lateinit var dispatcher: MailDispatcher
    @Autowired private lateinit var outbox: MailOutbox
    @Autowired private lateinit var notifier: InstallationNotifier
    @Autowired private lateinit var notifiers: ObjectProvider<InstallationNotifier>
    private val reader by lazy { OperationReader(jdbc) }
    private lateinit var token: String

    @BeforeEach fun prepare() {
        jdbc.sql("TRUNCATE enrollment.mail_outbox").update()
        MailpitServer.reset()
        token = adminToken()
        // 지금 활성 판(3)의 앞 판. 판 2를 적용했다고 확인된 설치가 "낡은" 설치다.
        jdbc.sql("""INSERT INTO enrollment.manifests(tenant_id, version, manifest, is_active, created_by_member_id, activated_at)
            SELECT tenant_id, 2, manifest, false, created_by_member_id, activated_at - interval '1 day' FROM enrollment.manifests WHERE tenant_id=:tenant AND is_active""")
            .param("tenant", tenant).update()
    }

    /** 구성원 한 명과 그의 설치 하나. [applied] 는 설치 보고로 적용을 확인한 판이고 null 이면 확인된 판이 없다. */
    private fun install(email: String, applied: Int? = null, memberStatus: MemberStatus = MemberStatus.active,
        status: InstallationStatus = InstallationStatus.active, organization: UUID = tenant): UUID {
        val owner = data.member(organization, email, MemberRole.member, memberStatus).id
        val installation = data.installation(organization, owner, data.invitation(organization, owner, InvitationCode.generate()).id, status).id
        if (applied != null) {
            jdbc.sql("""INSERT INTO enrollment.installation_manifest_assignments(installation_id, manifest_id, applied_at)
                SELECT :installation, id, :at FROM enrollment.manifests WHERE tenant_id=:tenant AND version=:version""")
                .param("installation", installation).param("at", Timestamp.from(clock.now)).param("tenant", organization).param("version", applied).update()
        }
        return installation
    }

    /** 설치 보고(ADR 0040)로 지금 [version] 판을 집행한다고 알린다. */
    private fun report(installation: UUID, version: Int) {
        jdbc.sql("""INSERT INTO enrollment.installation_heartbeats(installation_id, received_at, run_id, daemon_version, architecture, applied_config_revision,
                applied_manifest_id, mode, forwarding, receiving_since, delivered, lost, pending, last_delivered_at)
            SELECT :installation, :at, 'b3f1c2a49d5e4f60a1b2c3d4e5f60718', '0.2.0', 'arm64', :version, id, 'local', true, :at, 0, 0, 0, NULL
            FROM enrollment.manifests WHERE tenant_id=:tenant AND version=:version""")
            .param("installation", installation).param("at", Timestamp.from(clock.now)).param("tenant", tenant).param("version", version).update()
    }

    private fun notify(ids: List<Any>, expected: Any? = 3, key: String = UUID.randomUUID().toString()): HttpResponse<String> =
        manage("POST", "/installation-update-notifications", mapOf("installationIds" to ids.map { it.toString() }, "expectedPolicyVersion" to expected), token, key)

    private fun accepted(response: HttpResponse<String>): JsonNode {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(202)
        return mapper.readTree(response.body())
    }

    private fun error(response: HttpResponse<String>): List<Any?> {
        val body = mapper.readTree(response.body()).path("error")
        return listOf(response.statusCode(), body.path("code").asString(), body.path("fieldErrors").singleOrNull()?.path("field")?.asString())
    }

    private fun operation(id: Any): Operation = requireNotNull(reader.find(tenant, UUID.fromString(id.toString())))
    private fun targets(operation: Operation) = operation.targets.map { listOf(it.targetId, it.status.wire, it.reason) }
    private fun mailsTo(email: String) = MailpitServer.received().filter { MailpitServer.recipients(it) == listOf(email) }
    private fun operationCount() = data.countRows("operations")

    @Test fun `안내를 요청하면 작업을 만들어 202 와 작업 위치를 돌려주고 발송 작업이 보낸 메일이 설치의 구성원에게 도착한다`() {
        val outdated = install("outdated@example.test", applied = 2)
        val unknown = install("unknown@example.test")
        val response = notify(listOf(unknown, outdated))
        val body = accepted(response)
        val id = body.path("operationId").asString()
        assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/organizations/$tenant/operations/$id")
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
        // 작업 상태 조회와 같은 모양이다. 접수만 했으므로 대상은 모두 기다리는 중이고 요청한 순서다.
        assertThat(body.propertyNames().toList()).containsExactlyInAnyOrder("operationId", "kind", "status", "createdAt", "completedAt", "results",
            "canRestore", "restoreUntil", "retention")
        assertThat(listOf(body.path("kind").asString(), body.path("status").asString(), body.path("canRestore").asBoolean())).isEqualTo(listOf("installation_notification", "running", false))
        assertThat(listOf(body.path("completedAt"), body.path("restoreUntil"), body.path("retention")).all { it.isNull }).isTrue()
        assertThat(body.path("results").toList().map { listOf(it.path("targetId").asString(), it.path("status").asString()) })
            .containsExactly(listOf(unknown.toString(), "pending"), listOf(outdated.toString(), "pending"))
        assertThat(outbox.delivery(InstallationNotifier.key(UUID.fromString(id), outdated))?.status).isEqualTo("queued")

        // 보내기 전에는 결과를 옮길 것이 없다.
        assertThat(notifier.reconcile()).isEqualTo(0)
        assertThat(dispatcher.runOnce()).isEqualTo(2)
        val toOutdated = MailpitServer.text(mailsTo("outdated@example.test").single())
        assertThat(toOutdated).contains("정책 판 3", "아직 이전 정책(판 2)을 적용하고 있습니다", "my-macbook", "pulsemetry status",
            "새 정책을 스스로 받아 오지 않습니다", "다시 설치합니다", "원격으로 업데이트하거나 이 PC의 설정을 바꾸지 않습니다")
        // telemetryctl 기본 브랜치에 없는 명령·동작을 안내하지 않는다(ADR 0053) — 사용자 로그인 명령도, 보고 때의 자동 재조회도 없다.
        assertThat(toOutdated).doesNotContain("pulsemetry login", "로그인 필요", "다음 보고 때")
        assertThat(MailpitServer.text(mailsTo("unknown@example.test").single())).contains("정책 판 3", "새 정책을 적용했다는 보고를 아직 보내지 않았습니다")
        // 발송 결과를 대상 결과로 옮긴다. 한 번 옮긴 결과는 다시 옮기지 않는다.
        assertThat(operation(id).status.wire).isEqualTo("running")
        assertThat(notifier.reconcile()).isEqualTo(2)
        assertThat(notifier.reconcile()).isEqualTo(0)
        val done = operation(id)
        assertThat(done.status.wire).isEqualTo("succeeded")
        assertThat(done.completedAt).isNotNull()
        assertThat(targets(done)).containsExactly(listOf(unknown.toString(), "succeeded", null), listOf(outdated.toString(), "succeeded", null))
        // 안내는 설치를 바꾸지 않는다 — 적용 기록은 그대로다.
        assertThat(data.singleColumn("SELECT count(*) FROM enrollment.installation_manifest_assignments WHERE installation_id='$unknown'")).isEqualTo("0")
    }

    @Test fun `대상의 결과는 메일마다 따로 채워진다 — 거절된 메일은 실패, 취소된 메일은 취소, 나머지는 성공이다`() {
        val rejected = install("rejected@example.test", applied = 2)
        val later = install("later@example.test")
        val cancelled = install("cancelled@example.test")
        val id = UUID.fromString(accepted(notify(listOf(rejected, later, cancelled))).path("operationId").asString())
        // 재시도 대기 중인 메일을 흉내 낸다 — 이번 바퀴에는 나가지 않는다.
        jdbc.sql("UPDATE enrollment.mail_outbox SET next_attempt_at = next_attempt_at + interval '1 hour' WHERE dedup_key=:key")
            .param("key", InstallationNotifier.key(id, later)).update()
        assertThat(outbox.cancel(InstallationNotifier.key(id, cancelled))).isTrue()
        MailpitServer.chaos(recipient = 550)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(notifier.reconcile()).isEqualTo(2)
        var current = operation(id)
        assertThat(current.status.wire).isEqualTo("running")
        assertThat(targets(current)).containsExactly(listOf(rejected.toString(), "failed", "recipient_rejected"), listOf(later.toString(), "pending", null),
            listOf(cancelled.toString(), "failed", "cancelled"))

        MailpitServer.chaos()
        clock.now = clock.now.plusSeconds(3600)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(notifier.reconcile()).isEqualTo(1)
        current = operation(id)
        assertThat(current.status.wire).isEqualTo("partially_failed")
        assertThat(targets(current)[1]).isEqualTo(listOf(later.toString(), "succeeded", null))
        assertThat(mailsTo("later@example.test")).hasSize(1)
        assertThat(mailsTo("rejected@example.test")).isEmpty()
        assertThat(mailsTo("cancelled@example.test")).isEmpty()
    }

    @Test fun `앱이 띄운 발송 주기는 보낸 뒤 결과를 작업에 옮긴다`() {
        val target = install("periodic@example.test", applied = 2)
        val id = accepted(notify(listOf(target))).path("operationId").asString()
        val job = MailConfig().mailDispatchJob(dispatcher, MailProperties().apply { dispatchInterval = Duration.ofMillis(20) }, notifiers)
        job.start()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (operation(id).status.wire == "running" && System.nanoTime() < deadline) Thread.sleep(20)
        } finally { job.stop() }
        assertThat(targets(operation(id))).containsExactly(listOf(target.toString(), "succeeded", null))
        assertThat(mailsTo("periodic@example.test")).hasSize(1)
    }

    @Test fun `같은 멱등 키로 다시 보내면 같은 작업을 가리키고 메일을 다시 적재하지 않는다`() {
        val target = install("retry@example.test", applied = 2)
        val first = notify(listOf(target), key = "notify-retry-1")
        val second = notify(listOf(target), key = "notify-retry-1")
        assertThat(accepted(second).path("operationId").asString()).isEqualTo(accepted(first).path("operationId").asString())
        assertThat(second.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"))
        assertThat(operationCount()).isEqualTo(1)
        assertThat(data.countRows("mail_outbox")).isEqualTo(1)
        // 같은 키로 다른 요청을 보내면 충돌이다.
        assertThat(error(notify(listOf(target, install("other@example.test")), key = "notify-retry-1"))).isEqualTo(listOf(409, "idempotency_conflict", null))
        // 새 키는 새 안내다 — 관리자가 다시 알리는 것이다.
        assertThat(accepted(notify(listOf(target))).path("operationId").asString()).isNotEqualTo(accepted(first).path("operationId").asString())
        assertThat(operationCount()).isEqualTo(2)
        assertThat(dispatcher.runOnce()).isEqualTo(2)
        assertThat(mailsTo("retry@example.test")).hasSize(2)
    }

    @Test fun `대상을 모두 확인하기 전에는 아무것도 보내지 않는다`() {
        val outdated = install("ok@example.test", applied = 2)
        val applied = install("applied@example.test", applied = 3)
        val revoked = install("revoked@example.test", status = InstallationStatus.revoked)
        val suspended = install("suspended@example.test", memberStatus = MemberStatus.suspended)
        val otherTenant = data.tenant().id
        val foreign = install("foreign@example.test", organization = otherTenant)

        // 이미 기대 판을 적용했다고 확인된 설치, 폐기된 설치, 활성이 아닌 구성원의 설치는 안내할 수 없다.
        for (unavailable in listOf(applied, revoked, suspended)) {
            assertThat(error(notify(listOf(outdated, unavailable)))).isEqualTo(listOf(409, "installation_unavailable", "installationIds"))
        }
        // 이 조직의 설치가 아니면 없는 것과 같다.
        assertThat(error(notify(listOf(outdated, foreign)))).isEqualTo(listOf(404, "not_found", "installationIds"))
        assertThat(error(notify(listOf(outdated, UUID.randomUUID())))).isEqualTo(listOf(404, "not_found", "installationIds"))
        // 관리자가 본 판이 지금 활성 판이 아니다.
        assertThat(error(notify(listOf(outdated), expected = 2))).isEqualTo(listOf(409, "version_conflict", "expectedPolicyVersion"))
        assertThat(error(notify(listOf(outdated), expected = 4))).isEqualTo(listOf(409, "version_conflict", "expectedPolicyVersion"))
        // 요청의 모양.
        assertThat(error(notify(listOf(outdated), expected = 0))).isEqualTo(listOf(400, "invalid_request", "expectedPolicyVersion"))
        assertThat(error(notify(listOf(outdated), expected = null))).isEqualTo(listOf(400, "invalid_request", "expectedPolicyVersion"))
        assertThat(error(notify(listOf(outdated), expected = "3"))).isEqualTo(listOf(400, "invalid_request", "expectedPolicyVersion"))
        assertThat(error(notify(emptyList()))).isEqualTo(listOf(400, "invalid_request", "installationIds"))
        assertThat(error(notify(List(101) { UUID.randomUUID() }))).isEqualTo(listOf(400, "invalid_request", "installationIds"))
        assertThat(error(notify(listOf(outdated, outdated)))).isEqualTo(listOf(400, "invalid_request", "installationIds"))
        // 표준 하이픈 표기가 아닌 UUID(다른 관리 명령과 같은 규칙).
        assertThat(error(notify(listOf(outdated.toString().replace("-", ""))))[0]).isEqualTo(400)
        assertThat(error(notify(listOf("1-1-1-1-1")))[0]).isEqualTo(400)
        assertThat(error(notify(listOf("not-a-uuid")))[0]).isEqualTo(400)
        val numbers = manage("POST", "/installation-update-notifications", mapOf("installationIds" to listOf(1), "expectedPolicyVersion" to 3), token)
        assertThat(error(numbers)).isEqualTo(listOf(400, "invalid_request", "installationIds"))

        assertThat(operationCount()).isEqualTo(0)
        assertThat(data.countRows("mail_outbox")).isEqualTo(0)
        // 확인을 통과하는 대상만 보내면 접수한다. 판 3을 적용한 적이 있어도 지금 판 2를 집행한다고 보고하는 설치는 안내할 수 있다.
        val rolledBack = install("rolled-back@example.test", applied = 3)
        report(rolledBack, 2)
        report(applied, 3)
        assertThat(error(notify(listOf(outdated, applied)))).isEqualTo(listOf(409, "installation_unavailable", "installationIds"))
        accepted(notify(listOf(outdated, rolledBack)))
    }

    companion object {
        @JvmStatic @DynamicPropertySource fun smtp(registry: DynamicPropertyRegistry) = MailpitServer.register(registry)
    }
}
