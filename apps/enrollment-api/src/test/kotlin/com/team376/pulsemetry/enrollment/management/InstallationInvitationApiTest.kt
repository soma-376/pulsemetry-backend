package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.support.MailpitServer
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.mail.MailDispatcher
import com.team376.pulsemetry.persistence.enrollment.mail.MailOutbox
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private const val ACCEPT_URL = "https://app.example.test/invite"
/** 테스트 JVM 의 `pulsemetry.public-base-url` (build.gradle.kts). 설치 명령이 이 주소로 만들어진다. */
private const val INSTALL_BASE = "https://get.pulsemetry.example.com"

/**
 * 활성 구성원의 설치 전용 초대(ADR 0055). 새 코드로 실제 enroll 이 되고 가입은 409 이며, 메일은 설치 경로만 안내한다.
 * 기대값은 ADR 0055 와 명세 §12 "활성 구성원 설치 코드"에서 쓴다. 실제 PostgreSQL·HTTP·SMTP(메일 수신 컨테이너)로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.management.invitation-accept-url=$ACCEPT_URL",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000",
        "pulsemetry.mail.enabled=true", "pulsemetry.mail.from=no-reply@example.test", "pulsemetry.mail.encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "pulsemetry.mail.dispatch-interval=PT24H", "pulsemetry.mail.retry-interval=PT5M", "pulsemetry.mail.max-attempts=3", "pulsemetry.mail.send-timeout=PT5S",
        "pulsemetry.mail.smtp.username=test-user", "pulsemetry.mail.smtp.password=test-password", "pulsemetry.mail.smtp.starttls=false"])
class InstallationInvitationApiTest : AbstractUserAuthApiTest() {
    @Autowired private lateinit var dispatcher: MailDispatcher
    @Autowired private lateinit var outbox: MailOutbox
    private val earlier = Instant.parse("2026-09-01T00:00:00Z")

    @BeforeEach fun prepare() {
        jdbc.sql("TRUNCATE enrollment.mail_outbox").update()
        MailpitServer.reset()
    }

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun errorCode(response: HttpResponse<String>) = json(response).path("error").path("code").asString()
    /** 가입과 설치를 모두 마친 활성 구성원 — 새 PC 에 설치하려면 코드가 다시 필요하다. 버전은 시계보다 이른 값으로 고정한다. */
    private fun joined(email: String, status: MemberStatus = MemberStatus.active, tenantId: UUID = tenant): UUID {
        val id = data.member(tenantId, email, role = MemberRole.member, status = status).id
        val used = data.invitation(tenantId, id, InvitationCode.generate(), expiresAt = clock.now.plusSeconds(3600), usedAt = earlier).id
        jdbc.sql("UPDATE enrollment.invitations SET signup_used_at=:at WHERE id=:id").param("at", Timestamp.from(earlier)).param("id", used).update()
        jdbc.sql("UPDATE enrollment.members SET updated_at=:at WHERE id=:id").param("at", Timestamp.from(earlier)).param("id", id).update()
        return id
    }
    private fun issue(member: Any, token: String, key: String = UUID.randomUUID().toString(), version: Long? = earlier.toEpochMilli()) =
        manage("POST", "/members/$member/installation-invitations", if (version == null) emptyMap<String, Any>() else mapOf("expectedVersion" to version), token, key)
    private fun issued(member: Any, token: String, key: String = UUID.randomUUID().toString()): JsonNode {
        val response = issue(member, token, key)
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return json(response)
    }
    private fun enrollWith(code: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/enroll"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("code" to code, "platform" to "macos")))).build(),
        HttpResponse.BodyHandlers.ofString())
    private fun signupWith(email: String, code: String) = post("signup", mapOf("code" to code, "email" to email, "password" to password))
    private fun listed(token: String, invitation: String): JsonNode = json(manage("GET", "/invitations?limit=100", null, token)).path("items").toList()
        .single { it.path("invitationId").asString() == invitation }
    private fun invitationCount() = jdbc.sql("SELECT count(*) FROM enrollment.invitations").query(Int::class.java).single()

    @Test fun `활성 구성원에게 설치 전용 코드를 내면 그 코드로 설치는 되고 가입은 닫혀 있으며 메일은 설치 경로만 안내한다`() {
        val token = adminToken()
        val target = joined("laptop@example.test")

        val body = issued(target, token)

        assertThat(body.propertyNames().toList()).containsExactlyInAnyOrder("invitationId", "memberId", "replacesInvitationIds", "code", "expiresAt", "delivery")
        assertThat(body.path("memberId").asString()).isEqualTo(target.toString())
        assertThat(body.path("replacesInvitationIds").isArray && body.path("replacesInvitationIds").isEmpty).isTrue()
        val code = body.path("code").asString()
        assertThat(InvitationCode.matches(code)).isTrue()
        assertThat(body.path("expiresAt").asString()).isEqualTo(clock.now.plusSeconds(72 * 3600).toString())
        // 발급은 발송이 아니다 — 메일은 적재만 됐다(ADR 0038).
        assertThat(body.path("delivery").path("status").asString()).isEqualTo("queued")
        // 가입 경로는 발급 때 닫는다. 목록의 가입 소비 시각은 발급 시각이고, 설치는 남아 pending 이다.
        val pending = listed(token, body.path("invitationId").asString())
        assertThat(listOf(pending.path("status").asString(), pending.path("memberStatus").asString(), pending.path("memberId").asString()))
            .isEqualTo(listOf("pending", "active", target.toString()))
        assertThat(pending.path("signupUsedAt").asString()).isEqualTo(pending.path("createdAt").asString()).isEqualTo(clock.now.toString())
        assertThat(pending.path("installationUsedAt").isNull).isTrue()
        // 구성원 행은 바뀌지 않는다.
        assertThat(jdbc.sql("SELECT updated_at FROM enrollment.members WHERE id=:id").param("id", target).query { r, _ -> r.getTimestamp(1).toInstant() }.single()).isEqualTo(earlier)

        clock.now = clock.now.plusSeconds(30)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        val message = MailpitServer.received().single { MailpitServer.recipients(it) == listOf("laptop@example.test") }
        assertThat(message.path("Subject").asString()).isEqualTo("Pulsemetry 설치 코드").doesNotContain(code)
        val text = MailpitServer.text(message)
        assertThat(text).contains("테스트 조직", "설치 코드: $code", "curl -fsSL '$INSTALL_BASE/unix?code=$code' | sh", "irm '$INSTALL_BASE/windows?code=$code' | iex",
            "계정 만들기에는 쓸 수 없습니다")
        assertThat(text).doesNotContain(ACCEPT_URL, "#code=", "계정 만들기\n")

        assertThat(signupWith("laptop@example.test", code).statusCode()).isEqualTo(409)
        val enrolled = enrollWith(code)
        assertThat(enrolled.statusCode()).withFailMessage(enrolled.body()).isEqualTo(201)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.installations WHERE member_id=:id").param("id", target).query(Int::class.java).single()).isEqualTo(1)
        assertThat(listed(token, body.path("invitationId").asString()).path("status").asString()).isEqualTo("used")
        assertThat(enrollWith(code).statusCode()).isEqualTo(409)
    }

    @Test fun `같은 멱등 키의 재시도는 같은 코드이고 다시 내면 남은 설치 코드를 폐기하되 가입 권한이 남은 초대는 두며 메일은 새 코드 것만 나간다`() {
        val token = adminToken()
        val target = joined("desktop@example.test")
        // 가입 권한이 남은 초대 — 이 명령이 건드리지 않는다.
        val signupOpen = data.invitation(tenant, target, InvitationCode.generate(), expiresAt = clock.now.plusSeconds(3600)).id
        val key = UUID.randomUUID().toString()
        val first = issued(target, token, key)
        assertThat(issued(target, token, key)).isEqualTo(first)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox").query(Int::class.java).single()).isEqualTo(1)

        val second = issued(target, token)
        assertThat(second.path("replacesInvitationIds").toList().map { it.asString() }).containsExactly(first.path("invitationId").asString())
        assertThat(outbox.delivery("invitation:${first.path("invitationId").asString()}")?.status).isEqualTo("cancelled")
        assertThat(listed(token, first.path("invitationId").asString()).path("status").asString()).isEqualTo("revoked")
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.invitations WHERE id=:id AND revoked_at IS NULL").param("id", signupOpen).query(Int::class.java).single()).isEqualTo(1)

        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(MailpitServer.text(MailpitServer.received().single())).contains(second.path("code").asString()).doesNotContain(first.path("code").asString())
        val revoked = enrollWith(first.path("code").asString())
        assertThat(revoked.statusCode()).isEqualTo(409)
        assertThat(json(revoked).path("error").asString()).isEqualTo("invitation_revoked")
        assertThat(enrollWith(second.path("code").asString()).statusCode()).isEqualTo(201)
    }

    @Test fun `정지·초대 대기·다른 조직 구성원과 낡은 버전은 거절하고 아무것도 만들지 않는다`() {
        val token = adminToken()
        val suspended = joined("suspended@example.test", MemberStatus.suspended)
        val invited = joined("invited@example.test", MemberStatus.invited)
        val active = joined("active@example.test")
        val elsewhere = joined("elsewhere@example.test", tenantId = data.tenant().id)
        val before = invitationCount()

        assertThat(issue(suspended, token).let { it.statusCode() to errorCode(it) }).isEqualTo(409 to "member_suspended")
        assertThat(issue(invited, token).let { it.statusCode() to errorCode(it) }).isEqualTo(409 to "member_not_active")
        assertThat(issue(elsewhere, token).let { it.statusCode() to errorCode(it) }).isEqualTo(404 to "not_found")
        assertThat(issue(UUID.randomUUID(), token).statusCode()).isEqualTo(404)
        assertThat(issue(active, token, version = earlier.toEpochMilli() - 1).let { it.statusCode() to errorCode(it) }).isEqualTo(409 to "version_conflict")
        assertThat(issue(active, token, version = null).let { it.statusCode() to errorCode(it) }).isEqualTo(400 to "invalid_request")
        assertThat(invitationCount()).isEqualTo(before)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox").query(Int::class.java).single()).isZero()
    }

    @Test fun `구성원 역할의 호출자는 403 이다`() {
        val memberToken = tokens().path("access_token").asString()
        val target = joined("other@example.test")
        assertThat(issue(target, memberToken).let { it.statusCode() to errorCode(it) }).isEqualTo(403 to "forbidden")
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.invitations WHERE target_member_id=:id").param("id", target).query(Int::class.java).single()).isEqualTo(1)
    }

    @Test fun `가입을 마친 초대의 재발급 메일은 설치 경로만, 설치를 마친 초대의 재발급 메일은 계정 만들기만 안내한다`() {
        val token = adminToken()
        // 기본 구성원의 초대는 방금 가입에 썼고 설치가 남았다.
        val ownInvitation = jdbc.sql("SELECT id FROM enrollment.invitations WHERE target_member_id=:id").param("id", member).query(UUID::class.java).single()
        val installOnly = json(manage("POST", "/invitations/$ownInvitation/reissue", emptyMap<String, String>(), token))
        // 설치만 마치고 가입하지 않은 대기자.
        val pc = data.member(tenant, "pc-only@example.test", role = MemberRole.member, status = MemberStatus.active).id
        val installed = data.invitation(tenant, pc, InvitationCode.generate(), expiresAt = clock.now.plusSeconds(3600), usedAt = earlier).id
        val signupOnly = json(manage("POST", "/invitations/$installed/reissue", emptyMap<String, String>(), token))

        assertThat(dispatcher.runOnce()).isEqualTo(2)
        val toOwner = MailpitServer.received().single { MailpitServer.recipients(it) == listOf(email) }
        assertThat(toOwner.path("Subject").asString()).isEqualTo("Pulsemetry 설치 코드")
        assertThat(MailpitServer.text(toOwner)).contains("$INSTALL_BASE/unix?code=${installOnly.path("code").asString()}").doesNotContain("#code=")
        val toPc = MailpitServer.received().single { MailpitServer.recipients(it) == listOf("pc-only@example.test") }
        assertThat(toPc.path("Subject").asString()).isEqualTo("Pulsemetry 초대 코드")
        assertThat(MailpitServer.text(toPc)).contains("$ACCEPT_URL#code=${signupOnly.path("code").asString()}", "설치는 이미 마쳤습니다").doesNotContain("/unix?code=", "/windows?code=")
    }

    companion object {
        @JvmStatic @DynamicPropertySource fun smtp(registry: DynamicPropertyRegistry) = MailpitServer.register(registry)
    }
}
