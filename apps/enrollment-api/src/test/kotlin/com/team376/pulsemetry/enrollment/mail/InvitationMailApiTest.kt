package com.team376.pulsemetry.enrollment.mail

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.inquiry.InquiryProperties
import com.team376.pulsemetry.enrollment.management.ManagementProperties
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.support.MailpitServer
import com.team376.pulsemetry.persistence.enrollment.mail.MailDispatcher
import com.team376.pulsemetry.persistence.enrollment.mail.MailOutbox
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.util.UUID

private const val ACCEPT_URL = "https://app.example.test/invite"
private const val STAFF = "sales@example.test"
/** 테스트 JVM 의 `pulsemetry.public-base-url` (build.gradle.kts). 설치 명령이 이 주소로 만들어진다. */
private const val INSTALL_BASE = "https://get.pulsemetry.example.com"

/**
 * 초대 발급·재발급·취소와 문의 접수가 메일을 적재하고, 발송 작업이 실제 SMTP 로 보낸 메일이 수신 컨테이너에 도착하는지 본다(ADR 0037·0038).
 * 발송 작업은 테스트가 직접 돌린다(주기는 하루). 재시도 간격 5분, 최대 시도 3회.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.management.invitation-accept-url=$ACCEPT_URL",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000",
        "pulsemetry.inquiries.enabled=true", "pulsemetry.inquiries.duplicate-window=PT10M", "pulsemetry.inquiries.rate-limit.requests=20",
        "pulsemetry.inquiries.rate-limit.window=PT1M", "pulsemetry.inquiries.allowed-origins=http://localhost:3000",
        "pulsemetry.inquiries.notification-recipient=$STAFF",
        "pulsemetry.mail.enabled=true", "pulsemetry.mail.from=no-reply@example.test", "pulsemetry.mail.encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "pulsemetry.mail.dispatch-interval=PT24H", "pulsemetry.mail.retry-interval=PT5M", "pulsemetry.mail.max-attempts=3", "pulsemetry.mail.send-timeout=PT5S",
        "pulsemetry.mail.smtp.username=test-user", "pulsemetry.mail.smtp.password=test-password", "pulsemetry.mail.smtp.starttls=false"])
class InvitationMailApiTest : AbstractUserAuthApiTest() {
    @Autowired private lateinit var dispatcher: MailDispatcher
    @Autowired private lateinit var outbox: MailOutbox
    private lateinit var token: String

    @BeforeEach fun prepare() {
        jdbc.sql("TRUNCATE enrollment.mail_outbox, enrollment.inquiries, enrollment.inquiry_attempts").update()
        MailpitServer.reset()
        token = adminToken()
    }

    private fun invite(email: String, key: String = UUID.randomUUID().toString(), role: String = "member"): JsonNode {
        val response = manage("POST", "/invitations/batch", mapOf("invitations" to listOf(mapOf("email" to email, "teamId" to null, "role" to role))), token, key)
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return mapper.readTree(response.body()).path("results")[0]
    }
    private fun reissue(invitation: String, key: String = UUID.randomUUID().toString()): JsonNode {
        val response = manage("POST", "/invitations/$invitation/reissue", emptyMap<String, String>(), token, key)
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return mapper.readTree(response.body())
    }
    /** 목록에서 그 초대의 발송 상태. */
    private fun listed(invitation: String): JsonNode = mapper.readTree(manage("GET", "/invitations?limit=100", null, token).body()).path("items").toList()
        .single { it.path("invitationId").asString() == invitation }.path("delivery")
    private fun mailsTo(email: String) = MailpitServer.received().filter { MailpitServer.recipients(it) == listOf(email) }
    private fun signup(email: String, code: String) = post("signup", mapOf("code" to code, "email" to email, "password" to password)).statusCode()
    private fun outboxStatus(invitation: String) = outbox.delivery("invitation:$invitation")?.status

    @Test fun `초대를 발급하면 같은 트랜잭션에서 메일을 적재하고 발송 작업이 보낸 메일이 실제로 도착한다`() {
        val issued = invite("new@example.test")
        // 발급 결과의 의미는 그대로다. 발송은 따로 말한다 — 아직 적재됐을 뿐이다.
        assertThat(issued.path("status").asString()).isEqualTo("issued")
        assertThat(InvitationCode.matches(issued.path("code").asString())).isTrue()
        val queued = issued.path("delivery")
        assertThat(queued.propertyNames().toList()).containsExactlyInAnyOrder("status", "reason", "queuedAt", "lastAttemptAt", "sentAt", "failureCode", "attempts")
        assertThat(listOf(queued.path("status").asString(), queued.path("queuedAt").asString(), queued.path("attempts").asInt())).isEqualTo(listOf("queued", clock.now.toString(), 0))
        assertThat(listOf(queued.path("reason"), queued.path("sentAt"), queued.path("failureCode")).all { it.isNull }).isTrue()
        assertThat(listed(issued.path("invitationId").asString()).path("status").asString()).isEqualTo("queued")
        assertThat(MailpitServer.received()).isEmpty()
        // 대기 중인 본문은 암호문이라 DB 에 코드 원문이 없다.
        assertThat(jdbc.sql("SELECT encrypted_body || subject || recipient FROM enrollment.mail_outbox").query(String::class.java).single()).doesNotContain(issued.path("code").asString())

        clock.now = clock.now.plusSeconds(30)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        val message = mailsTo("new@example.test").single()
        val code = issued.path("code").asString()
        // 제목에는 코드가 없다. 코드는 본문에만, 수락 링크에는 fragment 로만 실린다.
        assertThat(message.path("Subject").asString()).isEqualTo("Pulsemetry 초대 코드").doesNotContain(code)
        val text = MailpitServer.text(message)
        assertThat(text).contains("테스트 조직", "초대 코드: $code", "$ACCEPT_URL#code=$code",
            "curl -fsSL '$INSTALL_BASE/unix?code=$code' | sh", "irm '$INSTALL_BASE/windows?code=$code' | iex", "2026-09-12 21:00 (한국 시간)까지")
        assertThat(text).doesNotContain("$ACCEPT_URL?code=")

        val sent = listed(issued.path("invitationId").asString())
        assertThat(listOf(sent.path("status").asString(), sent.path("sentAt").asString(), sent.path("lastAttemptAt").asString(), sent.path("attempts").asInt()))
            .isEqualTo(listOf("sent", clock.now.toString(), clock.now.toString(), 1))
        assertThat(sent.path("failureCode").isNull).isTrue()
        // 메일로 받은 코드로 실제로 가입한다.
        assertThat(signup("new@example.test", code)).isEqualTo(201)
    }

    @Test fun `같은 멱등 키의 재시도는 메일을 한 통만 만든다`() {
        val key = UUID.randomUUID().toString()
        val first = invite("once@example.test", key)
        assertThat(invite("once@example.test", key)).isEqualTo(first)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox").query(Int::class.java).single()).isEqualTo(1)
        // 이미 초대한 사람을 다시 초대해도 새 메일은 없다.
        val again = invite("once@example.test")
        assertThat(again.path("status").asString()).isEqualTo("already_invited")
        assertThat(again.path("delivery").isNull).isTrue()
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(mailsTo("once@example.test")).hasSize(1)

        val renewKey = UUID.randomUUID().toString()
        val renewed = reissue(first.path("invitationId").asString(), renewKey)
        assertThat(reissue(first.path("invitationId").asString(), renewKey)).isEqualTo(renewed)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(mailsTo("once@example.test")).hasSize(2)
    }

    @Test fun `재발급은 아직 나가지 않은 옛 메일을 취소하고 새 코드의 메일만 보낸다`() {
        val issued = invite("renew@example.test")
        val renewed = reissue(issued.path("invitationId").asString())
        assertThat(renewed.path("delivery").path("status").asString()).isEqualTo("queued")
        assertThat(outboxStatus(issued.path("invitationId").asString())).isEqualTo("cancelled")
        assertThat(listed(issued.path("invitationId").asString()).path("status").asString()).isEqualTo("cancelled")
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        val text = MailpitServer.text(mailsTo("renew@example.test").single())
        assertThat(text).contains("초대 코드: ${renewed.path("code").asString()}").doesNotContain(issued.path("code").asString())
        assertThat(listed(renewed.path("invitationId").asString()).path("status").asString()).isEqualTo("sent")
        // 옛 코드는 죽었고 메일로 받은 새 코드만 쓸 수 있다.
        assertThat(signup("renew@example.test", issued.path("code").asString())).isEqualTo(409)
        assertThat(signup("renew@example.test", renewed.path("code").asString())).isEqualTo(201)
    }

    @Test fun `이미 나간 메일 뒤의 재발급은 새 메일을 한 통 더 보낸다`() {
        val issued = invite("twice@example.test")
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        val renewed = reissue(issued.path("invitationId").asString())
        // 나간 메일은 되돌릴 수 없다. 상태도 sent 로 남는다.
        assertThat(outboxStatus(issued.path("invitationId").asString())).isEqualTo("sent")
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        val texts = mailsTo("twice@example.test").map(MailpitServer::text)
        assertThat(texts).hasSize(2)
        assertThat(texts.count { it.contains(renewed.path("code").asString()) }).isEqualTo(1)
        assertThat(texts.count { it.contains(issued.path("code").asString()) }).isEqualTo(1)
    }

    @Test fun `초대를 취소하면 아직 보내지 않은 메일은 나가지 않는다`() {
        val issued = invite("cancelled@example.test")
        assertThat(manage("POST", "/invitations/${issued.path("invitationId").asString()}/revoke", emptyMap<String, String>(), token).statusCode()).isEqualTo(204)
        assertThat(outboxStatus(issued.path("invitationId").asString())).isEqualTo("cancelled")
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        assertThat(MailpitServer.received()).isEmpty()
        // 취소한 사람을 다시 초대하면 새 초대의 메일이 나간다.
        val again = invite("cancelled@example.test")
        assertThat(again.path("status").asString()).isEqualTo("issued")
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(MailpitServer.text(mailsTo("cancelled@example.test").single())).contains(again.path("code").asString())
    }

    @Test fun `발송 실패는 사유와 함께 목록에 보이고 발급 결과를 바꾸지 않는다`() {
        val rejected = invite("rejected@example.test")
        MailpitServer.chaos(recipient = 550)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        var delivery = listed(rejected.path("invitationId").asString())
        assertThat(listOf(delivery.path("status").asString(), delivery.path("failureCode").asString(), delivery.path("attempts").asInt())).isEqualTo(listOf("failed", "recipient_rejected", 1))
        assertThat(delivery.path("sentAt").isNull).isTrue()

        // 일시 실패는 재시도 대기(queued)와 마지막 사유로 보인다. 간격 뒤에 SMTP 가 돌아오면 도착한다.
        val deferred = invite("deferred@example.test")
        MailpitServer.chaos(recipient = 451)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        delivery = listed(deferred.path("invitationId").asString())
        assertThat(listOf(delivery.path("status").asString(), delivery.path("failureCode").asString(), delivery.path("attempts").asInt())).isEqualTo(listOf("queued", "recipient_deferred", 1))
        MailpitServer.chaos()
        clock.now = clock.now.plusSeconds(300)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(listed(deferred.path("invitationId").asString()).path("status").asString()).isEqualTo("sent")
        assertThat(mailsTo("deferred@example.test")).hasSize(1)
        assertThat(mailsTo("rejected@example.test")).isEmpty()
        // 초대 자체는 살아 있다 — 실패한 것은 메일이다.
        val pending = mapper.readTree(manage("GET", "/invitations?limit=100&status=pending", null, token).body()).path("items").toList().map { it.path("invitationId").asString() }
        assertThat(pending).contains(rejected.path("invitationId").asString(), deferred.path("invitationId").asString())
    }

    @Test fun `메일을 적재한 적 없는 초대는 발송하지 않았다고 표시한다`() {
        // 시드의 초대는 관리자 키 경로로 만든 것이라 메일이 없다.
        val delivery = mapper.readTree(manage("GET", "/invitations?limit=100", null, token).body()).path("items").toList().single().path("delivery")
        assertThat(listOf(delivery.path("status").asString(), delivery.path("reason").asString(), delivery.path("attempts").asInt())).isEqualTo(listOf("not_sent", "not_queued", 0))
        assertThat(listOf(delivery.path("queuedAt"), delivery.path("sentAt"), delivery.path("lastAttemptAt"), delivery.path("failureCode")).all { it.isNull }).isTrue()
    }

    @Test fun `문의가 접수되면 담당자에게 통지가 도착하고 재전송은 통지를 다시 만들지 않는다`() {
        fun inquire() = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/inquiries")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("company" to "코드웍스", "email" to "Lead@Example.test")))).build(), HttpResponse.BodyHandlers.ofString())
        val first = inquire()
        assertThat(first.statusCode()).isEqualTo(201)
        val receipt = mapper.readTree(first.body())
        // 문의 응답은 그대로다. 통지는 내부 일이라 접수자에게 싣지 않는다.
        assertThat(receipt.propertyNames().toList()).containsExactlyInAnyOrder("inquiryId", "status", "receivedAt")
        assertThat(mapper.readTree(inquire().body())).isEqualTo(receipt)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox WHERE kind='inquiry_notice'").query(Int::class.java).single()).isEqualTo(1)
        assertThat(jdbc.sql("SELECT encrypted_body || subject FROM enrollment.mail_outbox").query(String::class.java).single()).doesNotContain("코드웍스", "lead@example.test")

        assertThat(dispatcher.runOnce()).isEqualTo(1)
        val message = mailsTo(STAFF).single()
        assertThat(message.path("Subject").asString()).isEqualTo("Pulsemetry 도입 문의 접수")
        assertThat(MailpitServer.text(message)).contains("접수 번호: ${receipt.path("inquiryId").asString()}", "회사명: 코드웍스", "회사 이메일: lead@example.test", "접수 시각: 2026-09-09 21:00 (한국 시간)")
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        assertThat(MailpitServer.received()).hasSize(1)
    }

    @Test fun `메일과 함께 켠 기능은 수락 주소와 통지 주소가 없으면 기동하지 않는다`() {
        val config = MailConfig()
        val server = PulsemetryProperties(INSTALL_BASE, PulsemetryProperties.Admin("test-admin-token"), "test-token-hash-secret")
        config.invitationMailer(outbox, ManagementProperties().apply { invitationAcceptUrl = ACCEPT_URL }, server)
        config.inquiryNotifier(outbox, InquiryProperties().apply { notificationRecipient = STAFF })
        assertThatThrownBy { config.invitationMailer(outbox, ManagementProperties(), server) }.hasMessageContaining("pulsemetry.management.invitation-accept-url")
        // 코드를 fragment 로 붙이므로 주소에 fragment 가 이미 있으면 안 된다.
        for (bad in listOf("app.example.test/invite", "ftp://app.example.test/invite", "$ACCEPT_URL#section")) {
            assertThatThrownBy { config.invitationMailer(outbox, ManagementProperties().apply { invitationAcceptUrl = bad }, server) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { config.inquiryNotifier(outbox, InquiryProperties()) }.hasMessageContaining("pulsemetry.inquiries.notification-recipient")
        assertThatThrownBy { config.inquiryNotifier(outbox, InquiryProperties().apply { notificationRecipient = "not an address" }) }.hasMessageContaining("pulsemetry.inquiries.notification-recipient")
    }

    companion object {
        @JvmStatic @DynamicPropertySource fun smtp(registry: DynamicPropertyRegistry) = MailpitServer.register(registry)
    }
}
