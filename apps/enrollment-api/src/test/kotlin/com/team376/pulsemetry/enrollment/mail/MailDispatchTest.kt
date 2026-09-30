package com.team376.pulsemetry.enrollment.mail

import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.auth.AuthTestClock
import com.team376.pulsemetry.enrollment.support.MailpitServer
import com.team376.pulsemetry.persistence.enrollment.mail.ClaimedMail
import com.team376.pulsemetry.persistence.enrollment.mail.MailDelivery
import com.team376.pulsemetry.persistence.enrollment.mail.MailDispatcher
import com.team376.pulsemetry.persistence.enrollment.mail.MailDraft
import com.team376.pulsemetry.persistence.enrollment.mail.MailOutbox
import com.team376.pulsemetry.persistence.enrollment.mail.MailPolicy
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransport
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransportFailure
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
private const val FROM = "no-reply@example.test"
/** 본문에 실리는 비밀의 대역. 실제 초대 코드가 아니다. */
private const val SECRET = "FAKE-CODE-0001"

/**
 * 실제 PostgreSQL 과 실제 SMTP(메일 수신 컨테이너)로 검증한다 (ADR 0037).
 * 주기 작업은 하루 주기로 두어 끼어들지 않게 하고, 테스트가 발송 작업을 직접 돌린다.
 * 재시도 간격 5분, 최대 시도 3회, SMTP 제한 시간 5초(선점 임대 20초)다.
 */
@SpringBootTest(properties = ["pulsemetry.mail.enabled=true", "pulsemetry.mail.from=$FROM", "pulsemetry.mail.encryption-key=$KEY",
    "pulsemetry.mail.dispatch-interval=PT24H", "pulsemetry.mail.retry-interval=PT5M", "pulsemetry.mail.max-attempts=3", "pulsemetry.mail.send-timeout=PT5S",
    "pulsemetry.mail.smtp.username=test-user", "pulsemetry.mail.smtp.password=test-password", "pulsemetry.mail.smtp.starttls=false"])
@Import(PostgresContainerConfig::class, AuthClockConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class MailDispatchTest {
    @Autowired private lateinit var outbox: MailOutbox
    @Autowired private lateinit var dispatcher: MailDispatcher
    @Autowired private lateinit var job: MailDispatchJob
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var manager: PlatformTransactionManager
    @Autowired private lateinit var clock: AuthTestClock
    private val start = Instant.parse("2026-09-09T12:00:00Z")

    @BeforeEach fun setup() {
        jdbc.sql("TRUNCATE enrollment.mail_outbox").update()
        clock.now = start
        MailpitServer.reset()
    }

    private fun chaos(recipient: Int? = null, sender: Int? = null, authentication: Int? = null) = MailpitServer.chaos(recipient, sender, authentication)
    private fun received(): List<JsonNode> = MailpitServer.received()
    private fun draft(key: String, recipient: String = "member@example.test", subject: String = "Pulsemetry 초대", body: String = "초대 코드: $SECRET\n코드는 72시간 동안 유효합니다.") =
        MailDraft(key, "invitation", recipient, subject, body)
    private fun delivery(key: String): MailDelivery = requireNotNull(outbox.delivery(key))
    private fun storedBody(key: String): String? = jdbc.sql("SELECT encrypted_body FROM enrollment.mail_outbox WHERE dedup_key=:key").param("key", key)
        .query { rs, _ -> rs.getString(1).orEmpty() }.single().ifEmpty { null }

    @Test fun `적재한 메일이 SMTP로 실제로 도착하고 본문은 끝날 때까지만 암호문으로 남는다`(output: CapturedOutput) {
        val queued = outbox.enqueue(draft("invitation:1"))
        assertThat(queued.status).isEqualTo("queued")
        assertThat(queued.attempts).isEqualTo(0)
        assertThat(queued.queuedAt).isEqualTo(start)
        assertThat(queued.finishedAt).isNull()
        // 대기 중인 본문은 암호문이다. 평문 비밀이 DB 에 없다.
        assertThat(storedBody("invitation:1")).isNotNull().doesNotContain(SECRET, "초대 코드")
        assertThat(received()).isEmpty()

        clock.now = start.plusSeconds(7)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        val message = received().single()
        assertThat(MailpitServer.recipients(message)).isEqualTo(listOf("member@example.test"))
        assertThat(message.path("From").path("Address").asString()).isEqualTo(FROM)
        assertThat(message.path("Subject").asString()).isEqualTo("Pulsemetry 초대")
        assertThat(MailpitServer.text(message)).isEqualTo("초대 코드: $SECRET\n코드는 72시간 동안 유효합니다.")
        assertThat(MailpitServer.header(message, "X-Pulsemetry-Mail-Id")).isEqualTo(listOf(queued.id.toString()))

        val sent = delivery("invitation:1")
        assertThat(listOf(sent.status, sent.attempts, sent.failureCode, sent.failureDetail)).isEqualTo(listOf("sent", 1, null, null))
        assertThat(sent.lastAttemptAt).isEqualTo(start.plusSeconds(7))
        assertThat(sent.finishedAt).isEqualTo(start.plusSeconds(7))
        // 보낸 뒤에는 암호문도 남기지 않는다.
        assertThat(storedBody("invitation:1")).isNull()
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        assertThat(received()).hasSize(1)
        // 본문과 제목은 로그에 없다.
        assertThat(output.all).contains(queued.id.toString()).doesNotContain(SECRET, "Pulsemetry 초대")
    }

    @Test fun `같은 중복 방지 키로 다시 적재해도 메일은 한 통이고 처음 내용으로 나간다`() {
        val first = outbox.enqueue(draft("invitation:dup"))
        val again = outbox.enqueue(draft("invitation:dup", recipient = "other@example.test", body = "다른 본문"))
        assertThat(again).isEqualTo(first)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox").query(Int::class.java).single()).isEqualTo(1)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(MailpitServer.recipients(received().single())).isEqualTo(listOf("member@example.test"))
        // 보낸 뒤의 재적재도 다시 보내지 않는다.
        assertThat(outbox.enqueue(draft("invitation:dup")).status).isEqualTo("sent")
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        assertThat(received()).hasSize(1)
    }

    @Test fun `적재는 호출자의 트랜잭션에 묶여 롤백되면 메일도 없다`() {
        val tx = TransactionTemplate(manager)
        assertThatThrownBy {
            tx.executeWithoutResult {
                jdbc.sql("INSERT INTO enrollment.inquiry_attempts(subject_hash,window_started_at) VALUES ('mail-tx-test',now())").update()
                outbox.enqueue(draft("invitation:rolled-back"))
                throw IllegalStateException("업무 쓰기 실패")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(outbox.delivery("invitation:rolled-back")).isNull()
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.inquiry_attempts WHERE subject_hash='mail-tx-test'").query(Int::class.java).single()).isEqualTo(0)
        tx.executeWithoutResult { outbox.enqueue(draft("invitation:committed")) }
        assertThat(delivery("invitation:committed").status).isEqualTo("queued")
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(received()).hasSize(1)
    }

    @Test fun `두 발송 작업이 동시에 돌아도 메일마다 한 통만 나간다`() {
        val keys = (1..8).map { "invitation:parallel-$it" }
        keys.forEachIndexed { index, key -> outbox.enqueue(draft(key, subject = "병렬 ${index + 1}")) }
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val runs = (1..2).map { pool.submit(Callable { gate.await(); dispatcher.runOnce() }) }
            gate.countDown()
            assertThat(runs.sumOf { it.get() }).isEqualTo(8)
        } finally { pool.shutdownNow() }
        assertThat(received().map { it.path("Subject").asString() }.sorted()).isEqualTo((1..8).map { "병렬 $it" }.sorted())
        assertThat(keys.map { delivery(it).let { d -> d.status to d.attempts } }.toSet()).isEqualTo(setOf("sent" to 1))
    }

    @Test fun `한 메일을 여럿이 동시에 선점해도 하나만 잡는다`() {
        outbox.enqueue(draft("invitation:one"))
        val pool = Executors.newFixedThreadPool(6)
        val gate = CountDownLatch(1)
        try {
            val claims = (1..6).map { pool.submit(Callable { gate.await(); outbox.claim() }) }
            gate.countDown()
            assertThat(claims.mapNotNull { it.get() }).hasSize(1)
        } finally { pool.shutdownNow() }
        assertThat(delivery("invitation:one").let { it.status to it.attempts }).isEqualTo("sending" to 1)
    }

    @Test fun `일시 실패는 간격을 두고 다시 시도하고 최대 시도 뒤에 실패와 사유를 남긴다`(output: CapturedOutput) {
        outbox.enqueue(draft("invitation:deferred"))
        chaos(recipient = 451)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        var state = delivery("invitation:deferred")
        assertThat(listOf(state.status, state.attempts, state.failureCode, state.failureDetail, state.finishedAt)).isEqualTo(listOf("queued", 1, "recipient_deferred", "451", null))
        // 다시 시도할 메일의 본문은 아직 필요하다.
        assertThat(storedBody("invitation:deferred")).isNotNull()
        // 간격이 지나기 전에는 다시 시도하지 않는다.
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        clock.now = start.plusSeconds(299)
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        clock.now = start.plusSeconds(300)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(delivery("invitation:deferred").let { it.status to it.attempts }).isEqualTo("queued" to 2)
        clock.now = start.plusSeconds(600)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        state = delivery("invitation:deferred")
        assertThat(listOf(state.status, state.attempts, state.failureCode, state.failureDetail)).isEqualTo(listOf("failed", 3, "recipient_deferred", "451"))
        assertThat(state.finishedAt).isEqualTo(start.plusSeconds(600))
        assertThat(storedBody("invitation:deferred")).isNull()
        // 실패로 닫힌 메일은 다시 보내지 않는다.
        chaos()
        clock.now = start.plusSeconds(3600)
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        assertThat(received()).isEmpty()
        assertThat(output.all).contains("recipient_deferred").doesNotContain(SECRET)
    }

    @Test fun `일시 실패 뒤 SMTP가 돌아오면 다음 시도에서 도착한다`() {
        outbox.enqueue(draft("invitation:recovered"))
        chaos(recipient = 451)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        chaos()
        clock.now = start.plusSeconds(300)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        val state = delivery("invitation:recovered")
        assertThat(listOf(state.status, state.attempts, state.failureCode, state.failureDetail)).isEqualTo(listOf("sent", 2, null, null))
        assertThat(received()).hasSize(1)
    }

    @Test fun `영구 실패는 다시 시도하지 않고 바로 실패로 닫는다`() {
        outbox.enqueue(draft("invitation:rejected"))
        outbox.enqueue(draft("invitation:bad-sender"))
        outbox.enqueue(draft("invitation:bad-address", recipient = "not an address"))
        chaos(recipient = 550)
        // 주소 형식 오류는 SMTP 에 가기 전에, 수신 거부는 SMTP 에서 난다.
        assertThat(dispatcher.runOnce()).isEqualTo(3)
        assertThat(delivery("invitation:rejected").let { listOf(it.status, it.attempts, it.failureCode, it.failureDetail) }).isEqualTo(listOf("failed", 1, "recipient_rejected", "550"))
        assertThat(delivery("invitation:bad-address").let { listOf(it.status, it.attempts, it.failureCode) }).isEqualTo(listOf("failed", 1, "invalid_address"))
        assertThat(delivery("invitation:bad-sender").status).isEqualTo("failed")
        // 발신 주소를 거절당한 경우도 5xx 면 영구 실패다.
        outbox.enqueue(draft("invitation:sender-rejected"))
        chaos(sender = 550)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(delivery("invitation:sender-rejected").let { listOf(it.status, it.attempts, it.failureCode, it.failureDetail) }).isEqualTo(listOf("failed", 1, "message_rejected", "550"))
        chaos()
        clock.now = start.plusSeconds(3600)
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        assertThat(received()).isEmpty()
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox WHERE encrypted_body IS NOT NULL").query(Int::class.java).single()).isEqualTo(0)
    }

    @Test fun `인증 실패와 연결 실패는 일시 실패다`() {
        outbox.enqueue(draft("invitation:auth"))
        chaos(authentication = 535)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(delivery("invitation:auth").let { listOf(it.status, it.attempts, it.failureCode) }).isEqualTo(listOf("queued", 1, "smtp_auth_failed"))

        // 아무도 듣지 않는 포트로 보낸다.
        val closed = ServerSocket(0).use { it.localPort }
        val unreachable = MailConfig().mailTransport(properties().apply { smtp.host = "127.0.0.1"; smtp.port = closed; sendTimeout = Duration.ofSeconds(2) })
        assertThatThrownBy { unreachable.send(ClaimedMail(java.util.UUID.randomUUID(), "invitation", "member@example.test", "제목", "본문", 1)) }
            .isInstanceOfSatisfying(MailTransportFailure::class.java) { assertThat(listOf(it.permanent, it.code, it.detail)).isEqualTo(listOf(false, "smtp_unavailable", null)) }
    }

    @Test fun `보내기 전의 메일만 취소할 수 있고 취소한 메일은 나가지 않는다`() {
        outbox.enqueue(draft("invitation:cancelled"))
        outbox.enqueue(draft("invitation:kept"))
        clock.now = start.plusSeconds(3)
        assertThat(outbox.cancel("invitation:cancelled")).isTrue()
        val cancelled = delivery("invitation:cancelled")
        assertThat(listOf(cancelled.status, cancelled.attempts, cancelled.finishedAt)).isEqualTo(listOf("cancelled", 0, start.plusSeconds(3)))
        assertThat(storedBody("invitation:cancelled")).isNull()
        assertThat(outbox.cancel("invitation:cancelled")).isFalse()
        assertThat(outbox.cancel("invitation:unknown")).isFalse()
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(received()).hasSize(1)
        // 이미 보낸 메일과 보내는 중인 메일은 취소하지 못한다.
        assertThat(outbox.cancel("invitation:kept")).isFalse()
        assertThat(delivery("invitation:kept").status).isEqualTo("sent")
        outbox.enqueue(draft("invitation:in-flight"))
        assertThat(outbox.claim()).isNotNull()
        assertThat(outbox.cancel("invitation:in-flight")).isFalse()
        assertThat(delivery("invitation:in-flight").status).isEqualTo("sending")
    }

    @Test fun `선점한 작업이 죽으면 임대가 끝난 뒤 다시 보내고 최대 시도를 채웠으면 결과 불명으로 닫는다`() {
        outbox.enqueue(draft("invitation:orphan"))
        // 선점만 하고 결과를 기록하지 않은 작업.
        assertThat(outbox.claim()?.attempt).isEqualTo(1)
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        clock.now = start.plusSeconds(19)
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        clock.now = start.plusSeconds(20)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(delivery("invitation:orphan").let { it.status to it.attempts }).isEqualTo("sent" to 2)
        assertThat(received()).hasSize(1)

        outbox.enqueue(draft("invitation:lost"))
        for (attempt in 1..3) {
            assertThat(outbox.claim()?.attempt).isEqualTo(attempt)
            clock.now = clock.now.plusSeconds(20)
        }
        assertThat(dispatcher.runOnce()).isEqualTo(0)
        val lost = delivery("invitation:lost")
        assertThat(listOf(lost.status, lost.attempts, lost.failureCode, lost.finishedAt)).isEqualTo(listOf("failed", 3, "outcome_unknown", clock.now))
        assertThat(storedBody("invitation:lost")).isNull()
        assertThat(received()).hasSize(1)
    }

    @Test fun `본문을 읽지 못하는 메일은 시도 실패로 기록하고 뒤의 메일을 막지 않는다`() {
        outbox.enqueue(draft("invitation:unreadable"))
        clock.now = start.plusSeconds(1)
        outbox.enqueue(draft("invitation:readable"))
        // 다른 키로 암호화된 본문. 키를 바꾼 뒤에 남은 대기 메일과 같다.
        val foreign = MailOutbox(jdbc, manager, clock, "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", MailPolicy(Duration.ofMinutes(5), 3, Duration.ofSeconds(20)))
        foreign.enqueue(draft("invitation:foreign-source"))
        jdbc.sql("""UPDATE enrollment.mail_outbox SET encrypted_body=(SELECT encrypted_body FROM enrollment.mail_outbox WHERE dedup_key='invitation:foreign-source')
            WHERE dedup_key='invitation:unreadable'""").update()
        jdbc.sql("DELETE FROM enrollment.mail_outbox WHERE dedup_key='invitation:foreign-source'").update()
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(delivery("invitation:readable").status).isEqualTo("sent")
        assertThat(delivery("invitation:unreadable").let { listOf(it.status, it.attempts, it.failureCode) }).isEqualTo(listOf("queued", 1, "send_error"))
        assertThat(received()).hasSize(1)
    }

    @Test fun `보낸 뒤 결과 기록이 실패하면 메일을 실패로 바꾸지 않고 임대가 끝난 뒤 다시 선점되게 둔다`() {
        outbox.enqueue(draft("invitation:unrecorded"))
        // 발송은 성공했는데 결과를 기록할 테이블에 닿지 못하는 상황.
        val breaking = MailTransport { jdbc.sql("ALTER TABLE enrollment.mail_outbox RENAME TO mail_outbox_unavailable").update() }
        try {
            assertThatThrownBy { MailDispatcher(outbox, breaking).runOnce() }.isInstanceOf(DataAccessException::class.java)
        } finally { jdbc.sql("ALTER TABLE enrollment.mail_outbox_unavailable RENAME TO mail_outbox").update() }
        val state = delivery("invitation:unrecorded")
        assertThat(listOf(state.status, state.attempts, state.failureCode, state.finishedAt)).isEqualTo(listOf("sending", 1, null, null))
        assertThat(storedBody("invitation:unrecorded")).isNotNull()
        // 적어도 한 번 — 임대가 끝나면 같은 메일이 다시 나간다.
        clock.now = start.plusSeconds(20)
        assertThat(dispatcher.runOnce()).isEqualTo(1)
        assertThat(delivery("invitation:unrecorded").let { it.status to it.attempts }).isEqualTo("sent" to 2)
    }

    @Test fun `분류하지 못한 발송 오류는 원문을 남기지 않고 일시 실패로 기록한다`(output: CapturedOutput) {
        outbox.enqueue(draft("invitation:boom"))
        assertThat(MailDispatcher(outbox, MailTransport { throw IllegalStateException("본문 $SECRET 이 섞인 예외") }).runOnce()).isEqualTo(1)
        assertThat(delivery("invitation:boom").let { listOf(it.status, it.attempts, it.failureCode, it.failureDetail) }).isEqualTo(listOf("queued", 1, "send_error", null))
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox WHERE failure_detail LIKE '%FAKE%' OR failure_code LIKE '%FAKE%'").query(Int::class.java).single()).isEqualTo(0)
        assertThat(output.all).contains("IllegalStateException").doesNotContain(SECRET)
    }

    @Test fun `잘못된 메일은 적재하지 않는다`() {
        for (bad in listOf(draft(""), draft("k".repeat(201)), draft("invitation:x", recipient = ""), draft("invitation:x", subject = "제목\r\nBcc: other@example.test"),
            draft("invitation:x", subject = "가".repeat(201)), draft("invitation:x", body = " "), MailDraft("invitation:x", "", "member@example.test", "제목", "본문"))) {
            assertThatThrownBy { outbox.enqueue(bad) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox").query(Int::class.java).single()).isEqualTo(0)
    }

    @Test fun `켰을 때 필수 설정이 하나라도 비면 기동하지 않는다`() {
        val jdbcMock = mock(JdbcClient::class.java)
        val managerMock = mock(PlatformTransactionManager::class.java)
        fun outboxOf(change: MailProperties.() -> Unit) = MailConfig().mailOutbox(jdbcMock, managerMock, Clock.systemUTC(), properties().apply(change))
        fun transportOf(change: MailProperties.() -> Unit) = MailConfig().mailTransport(properties().apply(change))
        fun jobOf(change: MailProperties.() -> Unit) = MailConfig().mailDispatchJob(dispatcher, properties().apply(change))
        outboxOf {}; transportOf {}; jobOf {}
        assertThatThrownBy { outboxOf { encryptionKey = "" } }.hasMessageContaining("pulsemetry.mail.encryption-key")
        assertThatThrownBy { outboxOf { encryptionKey = "c2hvcnQ=" } }.hasMessageContaining("pulsemetry.mail.encryption-key")
        assertThatThrownBy { outboxOf { retryInterval = null } }.hasMessageContaining("pulsemetry.mail.retry-interval")
        assertThatThrownBy { outboxOf { maxAttempts = null } }.hasMessageContaining("pulsemetry.mail.max-attempts")
        assertThatThrownBy { outboxOf { maxAttempts = 0 } }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { outboxOf { sendTimeout = null } }.hasMessageContaining("pulsemetry.mail.send-timeout")
        assertThatThrownBy { outboxOf { sendTimeout = Duration.ZERO } }.hasMessageContaining("pulsemetry.mail.send-timeout")
        assertThatThrownBy { transportOf { smtp.host = "" } }.hasMessageContaining("pulsemetry.mail.smtp.host")
        assertThatThrownBy { transportOf { smtp.port = null } }.hasMessageContaining("pulsemetry.mail.smtp.port")
        assertThatThrownBy { transportOf { smtp.port = 70000 } }.hasMessageContaining("pulsemetry.mail.smtp.port")
        assertThatThrownBy { transportOf { smtp.username = "" } }.hasMessageContaining("pulsemetry.mail.smtp.username")
        assertThatThrownBy { transportOf { smtp.password = "" } }.hasMessageContaining("pulsemetry.mail.smtp.password")
        assertThatThrownBy { transportOf { smtp.starttls = null } }.hasMessageContaining("pulsemetry.mail.smtp.starttls")
        assertThatThrownBy { transportOf { from = "" } }.hasMessageContaining("pulsemetry.mail.from")
        assertThatThrownBy { transportOf { from = "not an address" } }.hasMessageContaining("pulsemetry.mail.from")
        assertThatThrownBy { jobOf { dispatchInterval = null } }.hasMessageContaining("pulsemetry.mail.dispatch-interval")
        // 앱이 띄운 주기 작업은 돌고 있다(이 테스트에서는 하루 주기라 끼어들지 않는다).
        assertThat(job.isRunning).isTrue()
    }

    @Test fun `주기 작업은 앞 실행이 실패해도 계속 돌고 멈추면 더 돌지 않는다`() {
        val runs = AtomicInteger()
        val periodic = MailDispatchJob(Duration.ofMillis(20)) { if (runs.incrementAndGet() == 1) throw IllegalStateException("첫 실행 실패") }
        assertThat(periodic.isRunning).isFalse()
        periodic.start()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (runs.get() < 3 && System.nanoTime() < deadline) Thread.sleep(10)
            assertThat(runs.get()).isGreaterThanOrEqualTo(3)
            assertThat(periodic.isRunning).isTrue()
        } finally { periodic.stop() }
        assertThat(periodic.isRunning).isFalse()
        val stopped = runs.get()
        Thread.sleep(100)
        assertThat(runs.get()).isLessThanOrEqualTo(stopped + 1)
    }

    private fun properties() = MailProperties().apply {
        enabled = true; from = FROM; encryptionKey = KEY
        dispatchInterval = Duration.ofHours(24); retryInterval = Duration.ofMinutes(5); maxAttempts = 3; sendTimeout = Duration.ofSeconds(5)
        smtp.host = MailpitServer.host; smtp.port = MailpitServer.smtpPort; smtp.username = "test-user"; smtp.password = "test-password"; smtp.starttls = false
    }

    companion object {
        @JvmStatic @DynamicPropertySource fun smtp(registry: DynamicPropertyRegistry) = MailpitServer.register(registry)
    }
}

/** 기본 설정에서는 메일이 꺼져 있다. outbox 도 발송 작업도 없다. */
@SpringBootTest
@Import(PostgresContainerConfig::class)
class MailDisabledTest {
    @Autowired private lateinit var outbox: ObjectProvider<MailOutbox>
    @Autowired private lateinit var job: ObjectProvider<MailDispatchJob>
    @Autowired private lateinit var transport: ObjectProvider<MailTransport>

    @Test fun `꺼져 있으면 적재할 곳도 발송 작업도 없다`() {
        assertThat(outbox.ifAvailable).isNull()
        assertThat(job.ifAvailable).isNull()
        assertThat(transport.ifAvailable).isNull()
    }
}
