package com.team376.pulsemetry.enrollment.mail

import com.team376.pulsemetry.persistence.enrollment.mail.ClaimedMail
import com.team376.pulsemetry.persistence.enrollment.mail.MailDispatcher
import com.team376.pulsemetry.persistence.enrollment.mail.MailOutbox
import com.team376.pulsemetry.persistence.enrollment.mail.MailPolicy
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransport
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransportFailure
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 메일 발송 설정 (ADR 0037). 켰을 때 아래 값은 모두 기본값이 없는 필수값이다. */
@ConfigurationProperties("pulsemetry.mail")
class MailProperties {
    var enabled: Boolean = false
    /** 발신 주소 */
    var from: String = ""
    /** 대기 중인 본문을 암호화하는 키. Base64 32바이트 */
    var encryptionKey: String = ""
    /** 발송 작업이 outbox 를 보는 주기 */
    var dispatchInterval: Duration? = null
    /** 일시 실패 뒤 다시 시도하기까지의 간격 */
    var retryInterval: Duration? = null
    /** 한 메일을 보내려고 시도하는 최대 횟수 */
    var maxAttempts: Int? = null
    /** SMTP 연결·읽기·쓰기 각각의 제한 시간 */
    var sendTimeout: Duration? = null
    var smtp: Smtp = Smtp()

    class Smtp {
        var host: String = ""
        var port: Int? = null
        var username: String = ""
        var password: String = ""
        /** STARTTLS 를 요구하는가. 운영 SMTP 는 true 다 */
        var starttls: Boolean? = null
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.mail", name = ["enabled"], havingValue = "true")
class MailConfig {
    @Bean
    fun mailOutbox(jdbc: JdbcClient, manager: PlatformTransactionManager, clock: Clock, properties: MailProperties): MailOutbox {
        val key = properties.encryptionKey
        require(key.isNotBlank() && runCatching { Base64.getDecoder().decode(key).size == 32 }.getOrDefault(false)) { "pulsemetry.mail.encryption-key 는 Base64 32바이트여야 한다" }
        // 선점 임대는 한 번의 발송이 걸릴 수 있는 시간(연결·인증·전송·응답)보다 길어야 한다.
        return MailOutbox(jdbc, manager, clock, key, MailPolicy(positive(properties.retryInterval, "retry-interval"),
            requireNotNull(properties.maxAttempts) { missing("max-attempts") }, positive(properties.sendTimeout, "send-timeout").multipliedBy(4)))
    }

    @Bean
    fun mailTransport(properties: MailProperties): MailTransport {
        val smtp = properties.smtp
        require(smtp.host.isNotBlank()) { missing("smtp.host") }
        val port = requireNotNull(smtp.port) { missing("smtp.port") }
        require(port in 1..65535) { "pulsemetry.mail.smtp.port 가 올바르지 않다" }
        require(smtp.username.isNotBlank()) { missing("smtp.username") }
        require(smtp.password.isNotBlank()) { missing("smtp.password") }
        val starttls = requireNotNull(smtp.starttls) { missing("smtp.starttls") }
        require(properties.from.isNotBlank()) { missing("from") }
        try { InternetAddress(properties.from, true) } catch (_: AddressException) { throw IllegalArgumentException("pulsemetry.mail.from 이 메일 주소가 아니다") }
        val timeout = positive(properties.sendTimeout, "send-timeout").toMillis().toString()
        val sender = JavaMailSenderImpl().apply {
            host = smtp.host; this.port = port; username = smtp.username; password = smtp.password; protocol = "smtp"; defaultEncoding = "UTF-8"
            javaMailProperties.apply {
                setProperty("mail.smtp.auth", "true")
                setProperty("mail.smtp.starttls.enable", starttls.toString())
                setProperty("mail.smtp.starttls.required", starttls.toString())
                setProperty("mail.smtp.connectiontimeout", timeout)
                setProperty("mail.smtp.timeout", timeout)
                setProperty("mail.smtp.writetimeout", timeout)
            }
        }
        return SmtpMailTransport(sender, properties.from)
    }

    @Bean
    fun mailDispatcher(outbox: MailOutbox, transport: MailTransport) = MailDispatcher(outbox, transport)

    @Bean
    fun mailDispatchJob(dispatcher: MailDispatcher, properties: MailProperties) = MailDispatchJob(positive(properties.dispatchInterval, "dispatch-interval")) { dispatcher.runOnce() }

    private fun missing(key: String) = "pulsemetry.mail.$key 가 비어 있다"
    private fun positive(value: Duration?, key: String): Duration {
        val duration = requireNotNull(value) { missing(key) }
        require(!duration.isNegative && !duration.isZero) { "pulsemetry.mail.$key 는 0보다 커야 한다" }
        return duration
    }
}

/** SMTP 로 한 통을 보낸다. 실패는 다시 시도할 만한 것과 아닌 것으로 나눠 돌려준다. 서버 응답 원문은 싣지 않는다. */
class SmtpMailTransport(private val sender: JavaMailSender, private val from: String) : MailTransport {
    override fun send(mail: ClaimedMail) {
        try {
            val message = sender.createMimeMessage()
            MimeMessageHelper(message, false, "UTF-8").apply { setFrom(from); setTo(mail.recipient); setSubject(mail.subject); setText(mail.body, false) }
            // 받은 쪽에서 같은 메일의 재전송을 알아볼 수 있게 한다.
            message.setHeader("X-Pulsemetry-Mail-Id", mail.id.toString())
            sender.send(message)
        } catch (error: MailException) {
            throw classify(error)
        } catch (error: MessagingException) {
            throw classify(error)
        }
    }

    private fun classify(error: Exception): MailTransportFailure {
        val causes = chain(error)
        if (causes.any { it is AddressException }) return MailTransportFailure(true, "invalid_address")
        causes.filterIsInstance<SMTPAddressFailedException>().firstOrNull()?.let { return smtp(it.returnCode, "recipient_rejected", "recipient_deferred") }
        causes.filterIsInstance<SMTPSenderFailedException>().firstOrNull()?.let { return smtp(it.returnCode, "message_rejected", "smtp_deferred") }
        causes.filterIsInstance<SMTPSendFailedException>().firstOrNull()?.let { return smtp(it.returnCode, "message_rejected", "smtp_deferred") }
        if (causes.any { it is MailAuthenticationException || it is AuthenticationFailedException }) return MailTransportFailure(false, "smtp_auth_failed")
        return MailTransportFailure(false, "smtp_unavailable")
    }

    /** 5xx 는 다시 보내도 같은 답이다. 4xx 와 그 밖의 코드는 나중에 다시 시도한다. */
    private fun smtp(code: Int, rejected: String, deferred: String) =
        if (code in 500..599) MailTransportFailure(true, rejected, code.toString()) else MailTransportFailure(false, deferred, code.takeIf { it > 0 }?.toString())

    /** Spring 과 Jakarta Mail 은 원인을 cause·nextException·failedMessages 세 곳에 나눠 담는다. */
    private fun chain(root: Throwable): List<Throwable> {
        val seen = LinkedHashSet<Throwable>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && seen.size < 64) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            current.cause?.let(queue::add)
            if (current is MessagingException) current.nextException?.let(queue::add)
            if (current is MailSendException) { queue.addAll(current.failedMessages.values); queue.addAll(current.messageExceptions) }
        }
        return seen.toList()
    }
}

/**
 * 발송 작업의 주기 실행. 한 인스턴스 안에서는 한 번에 하나만 돈다(앞 실행이 끝난 뒤 [interval] 만큼 쉰다).
 * 실행이 예외로 끝나도 다음 주기는 계속된다.
 */
class MailDispatchJob(private val interval: Duration, private val run: () -> Unit) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    @Synchronized override fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "mail-dispatch").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                // 예외 원문에는 드라이버가 바인딩한 값이 섞일 수 있어 종류만 남긴다.
                try { run() } catch (error: Exception) { log.warn("메일 발송 작업이 실패했다 error={}", error.javaClass.simpleName) }
            }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    @Synchronized override fun isRunning() = executor != null

    private companion object {
        val log = LoggerFactory.getLogger(MailDispatchJob::class.java)
    }
}
