package com.team376.pulsemetry.enrollment.mail

import com.team376.pulsemetry.connector.vendor.MockVendorServer
import com.team376.pulsemetry.connector.vendor.reply
import com.team376.pulsemetry.enrollment.auth.AuthTestClock
import com.team376.pulsemetry.persistence.enrollment.mail.ClaimedMail
import com.team376.pulsemetry.persistence.enrollment.mail.MailDelivery
import com.team376.pulsemetry.persistence.enrollment.mail.MailDeliveryView
import com.team376.pulsemetry.persistence.enrollment.mail.MailDispatcher
import com.team376.pulsemetry.persistence.enrollment.mail.MailDraft
import com.team376.pulsemetry.persistence.enrollment.mail.MailOutbox
import com.team376.pulsemetry.persistence.enrollment.mail.MailPolicy
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransport
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransportFailure
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val PATH = "/v2/email/outbound-emails"
private const val FROM = "no-reply@example.test"
private const val RECIPIENT = "member@example.test"
private const val SUBJECT = "Pulsemetry 초대"
/** 본문에 실리는 비밀의 대역. 실제 초대 코드가 아니다. */
private const val SECRET = "FAKE-CODE-0002"
private const val KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
/** AWS 오류 응답의 메시지 대역. 수신자 주소와 비밀이 섞여 있다 — 저장·로그에 나오면 안 된다. */
private const val AWS_MESSAGE = "Email address is not verified: $RECIPIENT $SECRET"

/**
 * SES 발송 구현을 **실제 SDK** 로 검증한다 (ADR 0057). SDK 는 로컬 HTTP 모의 서버(JDK 내장, 벤더 커넥터 테스트와 같은 것)에 말하고,
 * 자격 증명은 가짜 고정값, 리전은 테스트용으로 고정한다 — 외부 AWS 로 나가지 않는다.
 * outbox 와 묶은 검증은 실제 PostgreSQL 이다. 메일 기능이 꺼진 기본 컨텍스트를 쓰고 outbox 는 테스트가 직접 만든다.
 */
@SpringBootTest
@Import(PostgresContainerConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class SesMailTransportTest {
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var manager: PlatformTransactionManager
    private val mapper = JsonMapper.builder().build()
    private val server = MockVendorServer()
    private val transports = mutableListOf<SesMailTransport>()
    private val start = Instant.parse("2026-10-05T03:00:00Z")
    private val clock = AuthTestClock().apply { now = start }
    /** 발송 제한 시간 2초 — 선점 임대 8초다. */
    private val timeout = Duration.ofSeconds(2)
    private val outbox by lazy { MailOutbox(jdbc, manager, clock, KEY, MailPolicy(Duration.ofMinutes(5), 3, timeout.multipliedBy(4))) }

    @BeforeEach fun setup() {
        jdbc.sql("TRUNCATE enrollment.mail_outbox").update()
    }

    @AfterEach fun close() {
        transports.forEach(SesMailTransport::close)
        server.close()
    }

    private fun transport(configurationSet: String? = null, timeout: Duration = this.timeout, endpoint: URI = server.base) =
        SesMailTransport(SesMailTransport.client(Region.US_EAST_1, timeout) {
            it.endpointOverride(endpoint).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDPULSEMETRYTEST", "test-secret-not-real")))
        }, FROM, configurationSet).also(transports::add)

    private fun mail(recipient: String = RECIPIENT, attempt: Int = 1) =
        ClaimedMail(UUID.randomUUID(), "invitation", recipient, SUBJECT, "초대 코드: $SECRET\n코드는 72시간 동안 유효합니다.", attempt)

    private fun accepted(id: String = "0100019a-test-message-id") = reply(200, """{"MessageId":"$id"}""", "x-amzn-RequestId" to "req-accepted")
    private fun error(status: Int, type: String) =
        reply(status, """{"message":"$AWS_MESSAGE"}""", "x-amzn-ErrorType" to type, "x-amzn-RequestId" to "req-$status")

    private fun failureOf(block: () -> Unit): MailTransportFailure {
        var caught: MailTransportFailure? = null
        assertThatThrownBy(block).isInstanceOfSatisfying(MailTransportFailure::class.java) { caught = it }
        return requireNotNull(caught)
    }

    private fun draft(key: String) = MailDraft(key, "invitation", RECIPIENT, SUBJECT, "초대 코드: $SECRET")
    private fun delivery(key: String): MailDelivery = requireNotNull(outbox.delivery(key))
    private fun storedBody(key: String): String? = jdbc.sql("SELECT encrypted_body FROM enrollment.mail_outbox WHERE dedup_key=:key").param("key", key)
        .query { rs, _ -> rs.getString(1).orEmpty() }.single().ifEmpty { null }

    @Test fun `요청은 발신·수신자 한 명·UTF-8 제목과 본문·메일 ID 헤더·configuration set 을 싣고 접수 ID 는 로그에만 남는다`(output: CapturedOutput) {
        server.on("POST", PATH, accepted())
        val mail = mail(attempt = 2)
        transport("pulsemetry-test").send(mail)

        val request = server.requests(PATH).single()
        assertThat(request.method).isEqualTo("POST")
        // 가짜 자격 증명과 고정한 테스트 리전으로 SigV4 서명했다.
        assertThat(request.header("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=AKIDPULSEMETRYTEST/").contains("/us-east-1/ses/aws4_request")
        val body = mapper.readTree(request.body)
        assertThat(body.path("FromEmailAddress").asString()).isEqualTo(FROM)
        assertThat(body.path("Destination").path("ToAddresses").toList().map(JsonNode::asString)).isEqualTo(listOf(RECIPIENT))
        assertThat(body.path("Destination").has("CcAddresses") || body.path("Destination").has("BccAddresses")).isFalse()
        val simple = body.path("Content").path("Simple")
        assertThat(listOf(simple.path("Subject").path("Data").asString(), simple.path("Subject").path("Charset").asString())).isEqualTo(listOf(SUBJECT, "UTF-8"))
        assertThat(simple.path("Body").path("Text").path("Data").asString()).isEqualTo("초대 코드: $SECRET\n코드는 72시간 동안 유효합니다.")
        assertThat(simple.path("Body").path("Text").path("Charset").asString()).isEqualTo("UTF-8")
        // 일반 텍스트만 보낸다. HTML·저장 템플릿을 쓰지 않는다.
        assertThat(simple.path("Body").has("Html")).isFalse()
        assertThat(body.path("Content").has("Template") || body.path("Content").has("Raw")).isFalse()
        assertThat(simple.path("Headers").toList().map { it.path("Name").asString() to it.path("Value").asString() }).isEqualTo(listOf(MAIL_ID_HEADER to mail.id.toString()))
        assertThat(body.path("ConfigurationSetName").asString()).isEqualTo("pulsemetry-test")
        // 태그·답장 주소처럼 정하지 않은 값은 싣지 않는다.
        assertThat(body.has("EmailTags") || body.has("ReplyToAddresses")).isFalse()

        assertThat(output.all).contains("event=mail_provider_accepted provider=ses mail_id=${mail.id} kind=invitation attempt=2 provider_message_id=0100019a-test-message-id")
            .doesNotContain(SECRET, SUBJECT, RECIPIENT)
    }

    @Test fun `configuration set 이 비어 있으면 요청에서 뺀다`() {
        server.on("POST", PATH, accepted())
        transport(configurationSet = null).send(mail())
        assertThat(mapper.readTree(server.requests(PATH).single().body).has("ConfigurationSetName")).isFalse()
    }

    @Test fun `오류는 타입과 오류 코드로 가르고 공개 코드는 기존 어휘만 쓰며 SDK 는 한 번만 시도한다`(output: CapturedOutput) {
        val cases = listOf(
            Triple(400, "MessageRejected", Triple(true, "message_rejected", "ses_message_rejected")),
            Triple(400, "BadRequestException", Triple(true, "message_rejected", "ses_bad_request")),
            Triple(429, "TooManyRequestsException", Triple(false, "send_error", "ses_throttled")),
            Triple(400, "ThrottlingException", Triple(false, "send_error", "ses_throttled")),
            Triple(400, "LimitExceededException", Triple(false, "send_error", "ses_limit_exceeded")),
            Triple(400, "MailFromDomainNotVerifiedException", Triple(false, "send_error", "ses_configuration_error")),
            Triple(404, "NotFoundException", Triple(false, "send_error", "ses_configuration_error")),
            Triple(400, "SendingPausedException", Triple(false, "send_error", "ses_sending_disabled")),
            Triple(400, "AccountSuspendedException", Triple(false, "send_error", "ses_sending_disabled")),
            Triple(403, "AccessDeniedException", Triple(false, "send_error", "ses_auth_failed")),
            Triple(403, "UnrecognizedClientException", Triple(false, "send_error", "ses_auth_failed")),
            Triple(400, "ExpiredTokenException", Triple(false, "send_error", "ses_auth_failed")),
            Triple(500, "InternalFailure", Triple(false, "send_error", "ses_unavailable")),
            Triple(503, "ServiceUnavailable", Triple(false, "send_error", "ses_unavailable")),
            Triple(400, "SomethingNewException", Triple(false, "send_error", "ses_unknown_error")),
        )
        val ses = transport()
        for ((status, type, expected) in cases) {
            server.on("POST", PATH, error(status, type))
            val before = server.requests(PATH).size
            val failure = failureOf { ses.send(mail()) }
            assertThat(Triple(failure.permanent, failure.code, failure.detail)).withFailMessage("$status $type → ${failure.code}/${failure.detail}").isEqualTo(expected)
            // 429·5xx 도 SDK 가 다시 보내지 않는다 — 재시도는 outbox 의 몫이다.
            assertThat(server.requests(PATH).size - before).withFailMessage("$status $type").isEqualTo(1)
            assertThat(failure.message).doesNotContain(SECRET, RECIPIENT)
        }
        // AWS 오류 메시지 원문(수신자·비밀)은 로그에 없다. 세부 토큰과 요청 ID 는 있다.
        assertThat(output.all).contains("event=mail_provider_failed provider=ses", "detail=ses_message_rejected status=400 request_id=req-400", "detail=ses_throttled status=429")
            .doesNotContain(SECRET, RECIPIENT, "is not verified").doesNotContain("event=mail_provider_accepted")
    }

    @Test fun `응답이 제한 시간을 넘으면 한 번만 시도하고 선점 임대보다 훨씬 먼저 끝난다`() {
        server.on("POST", PATH, { Thread.sleep(4_000); MockVendorServer.Reply(200, """{"MessageId":"late"}""") })
        val ses = transport(timeout = Duration.ofSeconds(1))
        val began = System.nanoTime()
        val failure = failureOf { ses.send(mail()) }
        val elapsed = Duration.ofNanos(System.nanoTime() - began)
        assertThat(Triple(failure.permanent, failure.code, failure.detail)).isEqualTo(Triple(false, "send_error", "ses_timeout"))
        // 임대는 제한 시간의 네 배(4초)다. 호출 전체 제한(1초)에서 끊긴다.
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_500))
        assertThat(server.requests(PATH)).hasSize(1)
    }

    @Test fun `연결하지 못하면 일시 실패다`() {
        val closed = ServerSocket(0).use { it.localPort }
        val failure = failureOf { transport(endpoint = URI("http://127.0.0.1:$closed")).send(mail()) }
        assertThat(Triple(failure.permanent, failure.code, failure.detail)).isEqualTo(Triple(false, "send_error", "ses_unavailable"))
    }

    @Test fun `주소 형식이 틀렸거나 수신자가 한 명이 아니면 SES 에 묻지 않고 영구 실패다`() {
        server.on("POST", PATH, accepted())
        val ses = transport()
        for (bad in listOf("not an address", "$RECIPIENT, other@example.test", "member@")) {
            val failure = failureOf { ses.send(mail(recipient = bad)) }
            assertThat(Triple(failure.permanent, failure.code, failure.detail)).withFailMessage(bad).isEqualTo(Triple(true, "invalid_address", "ses_invalid_address"))
        }
        assertThat(server.requests(PATH)).isEmpty()
    }

    @Test fun `닫으면 SDK 클라이언트도 닫혀 더 보내지 않는다`() {
        server.on("POST", PATH, accepted())
        val ses = transport()
        ses.close()
        assertThatThrownBy { ses.send(mail()) }.isInstanceOf(RuntimeException::class.java)
        assertThat(server.requests(PATH)).isEmpty()
    }

    @Test fun `SES 를 고르면 SMTP 계정 없이 만들어지고 리전과 configuration set 만 검사한다`() {
        fun configured(change: MailProperties.() -> Unit) = MailConfig().mailTransport(MailProperties().apply {
            enabled = true; provider = "ses"; from = FROM; encryptionKey = KEY
            dispatchInterval = Duration.ofHours(24); retryInterval = Duration.ofMinutes(5); maxAttempts = 3; sendTimeout = Duration.ofSeconds(5)
            ses.region = "us-east-1"
        }.apply(change))
        // 만들 때 AWS 에 말하지 않는다 — 자격 증명은 첫 호출 때 찾는다.
        (configured {} as SesMailTransport).close()
        (configured { ses.configurationSet = "pulsemetry_mail-1" } as SesMailTransport).close()
        assertThatThrownBy { configured { ses.region = "" } }.hasMessageContaining("pulsemetry.mail.ses.region")
        assertThatThrownBy { configured { ses.region = "Seoul" } }.hasMessageContaining("pulsemetry.mail.ses.region")
        assertThatThrownBy { configured { ses.configurationSet = "bad name!" } }.hasMessageContaining("pulsemetry.mail.ses.configuration-set")
        assertThatThrownBy { configured { provider = "SES" } }.hasMessageContaining("pulsemetry.mail.provider")
        assertThatThrownBy { configured { sendTimeout = null } }.hasMessageContaining("pulsemetry.mail.send-timeout")
        assertThatThrownBy { configured { from = "" } }.hasMessageContaining("pulsemetry.mail.from")
    }

    @Test fun `SES 가 받으면 outbox 는 sent 가 되고 본문을 지우며 접수 로그가 outbox ID 와 시도 번호에 이어진다`(output: CapturedOutput) {
        server.on("POST", PATH, accepted("0100019a-outbox"))
        val queued = outbox.enqueue(draft("invitation:ses-sent"))
        assertThat(MailDispatcher(outbox, transport()).runOnce()).isEqualTo(1)
        val sent = delivery("invitation:ses-sent")
        assertThat(listOf(sent.status, sent.attempts, sent.failureCode, sent.failureDetail)).isEqualTo(listOf("sent", 1, null, null))
        assertThat(storedBody("invitation:ses-sent")).isNull()
        assertThat(mapper.readTree(server.requests(PATH).single().body).path("Content").path("Simple").path("Headers")[0].path("Value").asString()).isEqualTo(queued.id.toString())
        assertThat(output.all).contains("mail_id=${queued.id} kind=invitation attempt=1 provider_message_id=0100019a-outbox").doesNotContain(SECRET)
    }

    @Test fun `일시 오류는 outbox 가 간격을 두고 다시 보내고 영구 오류는 바로 닫으며 API 에는 세부 토큰을 내지 않는다`() {
        val ses = transport()
        outbox.enqueue(draft("invitation:ses-retry"))
        server.on("POST", PATH, error(503, "ServiceUnavailable"), accepted())
        assertThat(MailDispatcher(outbox, ses).runOnce()).isEqualTo(1)
        var state = delivery("invitation:ses-retry")
        assertThat(listOf(state.status, state.attempts, state.failureCode, state.failureDetail)).isEqualTo(listOf("queued", 1, "send_error", "ses_unavailable"))
        assertThat(storedBody("invitation:ses-retry")).isNotNull()
        // 공개 상태에는 기존 실패 코드만 있다.
        assertThat(MailDeliveryView.of(state).values.map { it.toString() }).noneMatch { it.startsWith("ses_") }
        assertThat(MailDispatcher(outbox, ses).runOnce()).isEqualTo(0)
        clock.now = start.plus(Duration.ofMinutes(5))
        assertThat(MailDispatcher(outbox, ses).runOnce()).isEqualTo(1)
        state = delivery("invitation:ses-retry")
        assertThat(listOf(state.status, state.attempts, state.failureCode)).isEqualTo(listOf("sent", 2, null))
        // outbox 시도 한 번이 SES 요청 한 번이다.
        assertThat(server.requests(PATH)).hasSize(2)

        outbox.enqueue(draft("invitation:ses-rejected"))
        server.on("POST", PATH, error(400, "MessageRejected"))
        assertThat(MailDispatcher(outbox, ses).runOnce()).isEqualTo(1)
        state = delivery("invitation:ses-rejected")
        assertThat(listOf(state.status, state.attempts, state.failureCode, state.failureDetail)).isEqualTo(listOf("failed", 1, "message_rejected", "ses_message_rejected"))
        assertThat(storedBody("invitation:ses-rejected")).isNull()
        assertThat(MailDeliveryView.of(state)["failureCode"]).isEqualTo("message_rejected")
        clock.now = start.plus(Duration.ofHours(1))
        assertThat(MailDispatcher(outbox, ses).runOnce()).isEqualTo(0)
        assertThat(server.requests(PATH)).hasSize(3)
        // 오류 원문은 저장하지 않는다.
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.mail_outbox WHERE failure_detail LIKE '%verified%' OR failure_detail LIKE '%FAKE%'").query(Int::class.java).single()).isEqualTo(0)
    }

    @Test fun `최대 시도까지 일시 오류면 실패로 닫는다`() {
        val ses = transport()
        outbox.enqueue(draft("invitation:ses-throttled"))
        server.on("POST", PATH, error(429, "TooManyRequestsException"))
        for (attempt in 1..3) {
            clock.now = start.plus(Duration.ofMinutes(5L * (attempt - 1)))
            assertThat(MailDispatcher(outbox, ses).runOnce()).isEqualTo(1)
        }
        val state = delivery("invitation:ses-throttled")
        assertThat(listOf(state.status, state.attempts, state.failureCode, state.failureDetail)).isEqualTo(listOf("failed", 3, "send_error", "ses_throttled"))
        assertThat(storedBody("invitation:ses-throttled")).isNull()
        assertThat(server.requests(PATH)).hasSize(3)
    }

    @Test fun `SES 가 받은 뒤 결과 기록이 실패하면 sending 으로 남고 임대가 끝난 뒤 다시 나갈 수 있다`(output: CapturedOutput) {
        server.on("POST", PATH, accepted("0100019a-first"), accepted("0100019a-second"))
        val ses = transport()
        val queued = outbox.enqueue(draft("invitation:ses-unrecorded"))
        // SES 는 받았는데 결과를 기록할 테이블에 닿지 못하는 상황.
        val breaking = MailTransport { ses.send(it); jdbc.sql("ALTER TABLE enrollment.mail_outbox RENAME TO mail_outbox_unavailable").update() }
        try {
            assertThatThrownBy { MailDispatcher(outbox, breaking).runOnce() }.isInstanceOf(DataAccessException::class.java)
        } finally { jdbc.sql("ALTER TABLE enrollment.mail_outbox_unavailable RENAME TO mail_outbox").update() }
        val state = delivery("invitation:ses-unrecorded")
        assertThat(listOf(state.status, state.attempts, state.failureCode, state.finishedAt)).isEqualTo(listOf("sending", 1, null, null))
        assertThat(storedBody("invitation:ses-unrecorded")).isNotNull()
        // 수락 로그는 있지만 outbox 의 발송 완료 로그는 없다 — 수락 로그를 DB 의 sent 로 읽지 않는다.
        assertThat(output.all).contains("mail_id=${queued.id} kind=invitation attempt=1 provider_message_id=0100019a-first")
            .doesNotContain("메일을 보냈다 id=${queued.id}")
        // 적어도 한 번 — 임대가 끝나면 같은 메일이 다시 나가고 SES 접수 ID 가 하나 더 생긴다.
        clock.now = start.plus(timeout.multipliedBy(4))
        assertThat(MailDispatcher(outbox, ses).runOnce()).isEqualTo(1)
        assertThat(delivery("invitation:ses-unrecorded").let { it.status to it.attempts }).isEqualTo("sent" to 2)
        assertThat(server.requests(PATH)).hasSize(2)
        assertThat(output.all).contains("mail_id=${queued.id} kind=invitation attempt=2 provider_message_id=0100019a-second")
    }
}

/** 배포와 같은 조립 — SES 설정만으로 뜬다. SMTP 계정은 없다. 발송 작업은 하루 주기라 이 테스트에서 AWS 를 부르지 않는다. */
@SpringBootTest(properties = ["pulsemetry.mail.enabled=true", "pulsemetry.mail.provider=ses", "pulsemetry.mail.from=$FROM", "pulsemetry.mail.encryption-key=$KEY",
    "pulsemetry.mail.dispatch-interval=PT24H", "pulsemetry.mail.retry-interval=PT5M", "pulsemetry.mail.max-attempts=3", "pulsemetry.mail.send-timeout=PT5S",
    "pulsemetry.mail.ses.region=us-east-1", "pulsemetry.mail.ses.configuration-set=pulsemetry-test"])
@Import(PostgresContainerConfig::class)
class SesMailConfigTest {
    @Autowired private lateinit var transport: MailTransport
    @Autowired private lateinit var properties: MailProperties
    @Autowired private lateinit var job: MailDispatchJob

    @Test fun `SES 를 고른 배포는 SMTP 설정 없이 뜨고 SES 발송 구현을 쓴다`() {
        assertThat(transport).isInstanceOf(SesMailTransport::class.java)
        assertThat(listOf(properties.ses.region, properties.ses.configurationSet, properties.smtp.host)).isEqualTo(listOf("us-east-1", "pulsemetry-test", ""))
        assertThat(job.isRunning).isTrue()
    }
}
