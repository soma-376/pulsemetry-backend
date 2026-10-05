package com.team376.pulsemetry.enrollment.mail

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.inquiry.InquiryProperties
import com.team376.pulsemetry.enrollment.management.ManagementProperties
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationNotifier
import com.team376.pulsemetry.persistence.enrollment.mail.InquiryNotifier
import com.team376.pulsemetry.persistence.enrollment.mail.InvitationMailer
import com.team376.pulsemetry.persistence.enrollment.mail.MailDispatcher
import com.team376.pulsemetry.persistence.enrollment.mail.MailOutbox
import com.team376.pulsemetry.persistence.enrollment.mail.MailPolicy
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransport
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.transaction.PlatformTransactionManager
import software.amazon.awssdk.regions.Region
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 메일 발송 설정 (ADR 0037 · 0057). 켰을 때 아래 값은 기본값이 없는 필수값이다.
 * 발송 구현([provider])별 값은 고른 구현의 것만 본다 — SES 를 고르면 SMTP 계정을, SMTP 를 고르면 SES 리전을 요구하지 않는다.
 */
@ConfigurationProperties("pulsemetry.mail")
class MailProperties {
    var enabled: Boolean = false
    /** 발송 구현. `smtp`(로컬·테스트) 또는 `ses`(배포). 켰으면 반드시 고른다 — 한쪽이 실패해도 다른 쪽으로 넘어가지 않는다 */
    var provider: String = ""
    /** 발신 주소 */
    var from: String = ""
    /** 대기 중인 본문을 암호화하는 키. Base64 32바이트. 발송 구현의 자격 증명이 아니다 */
    var encryptionKey: String = ""
    /** 발송 작업이 outbox 를 보는 주기 */
    var dispatchInterval: Duration? = null
    /** 일시 실패 뒤 다시 시도하기까지의 간격 */
    var retryInterval: Duration? = null
    /** 한 메일을 보내려고 시도하는 최대 횟수 */
    var maxAttempts: Int? = null
    /** 한 번의 발송 제한 시간. SMTP 는 연결·읽기·쓰기 각각의 제한이고, SES 는 API 호출 전체의 제한이다. 선점 임대는 이 값의 네 배다 */
    var sendTimeout: Duration? = null
    var smtp: Smtp = Smtp()
    var ses: Ses = Ses()

    class Smtp {
        var host: String = ""
        var port: Int? = null
        var username: String = ""
        var password: String = ""
        /** STARTTLS 를 요구하는가. 운영 SMTP 는 true 다 */
        var starttls: Boolean? = null
    }

    class Ses {
        /** SES 를 부를 리전. 다른 AWS 설정(환경의 기본 리전 등)에서 추론하지 않는다 */
        var region: String = ""
        /** 요청에 실을 configuration set 이름. 비어 있으면 요청에서 뺀다 */
        var configurationSet: String = ""
    }
}

/** outbox 의 메일 ID 를 싣는 헤더. 두 발송 구현이 같은 이름을 쓴다 — 공급자의 중복 방지 키가 아니다. */
const val MAIL_ID_HEADER = "X-Pulsemetry-Mail-Id"

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

    /**
     * 고른 발송 구현 하나를 만든다(ADR 0057). 만들 때 SMTP 서버나 AWS 에 말하지 않는다.
     * SES 구현은 SDK 클라이언트를 갖고 있어 컨텍스트가 닫힐 때 함께 닫힌다(`AutoCloseable`).
     */
    @Bean
    fun mailTransport(properties: MailProperties): MailTransport {
        val provider = properties.provider
        require(provider.isNotBlank()) { missing("provider") }
        require(provider in PROVIDERS) { "pulsemetry.mail.provider 는 ${PROVIDERS.joinToString(" 또는 ")} 다" }
        require(properties.from.isNotBlank()) { missing("from") }
        try { InternetAddress(properties.from, true) } catch (_: AddressException) { throw IllegalArgumentException("pulsemetry.mail.from 이 메일 주소가 아니다") }
        val timeout = positive(properties.sendTimeout, "send-timeout")
        return if (provider == "ses") sesTransport(properties, timeout) else smtpTransport(properties, timeout)
    }

    private fun smtpTransport(properties: MailProperties, timeout: Duration): MailTransport {
        val smtp = properties.smtp
        require(smtp.host.isNotBlank()) { missing("smtp.host") }
        val port = requireNotNull(smtp.port) { missing("smtp.port") }
        require(port in 1..65535) { "pulsemetry.mail.smtp.port 가 올바르지 않다" }
        require(smtp.username.isNotBlank()) { missing("smtp.username") }
        require(smtp.password.isNotBlank()) { missing("smtp.password") }
        val starttls = requireNotNull(smtp.starttls) { missing("smtp.starttls") }
        val millis = timeout.toMillis().toString()
        val sender = JavaMailSenderImpl().apply {
            host = smtp.host; this.port = port; username = smtp.username; password = smtp.password; protocol = "smtp"; defaultEncoding = "UTF-8"
            javaMailProperties.apply {
                setProperty("mail.smtp.auth", "true")
                setProperty("mail.smtp.starttls.enable", starttls.toString())
                setProperty("mail.smtp.starttls.required", starttls.toString())
                setProperty("mail.smtp.connectiontimeout", millis)
                setProperty("mail.smtp.timeout", millis)
                setProperty("mail.smtp.writetimeout", millis)
            }
        }
        return SmtpMailTransport(sender, properties.from)
    }

    /**
     * 자격 증명은 기본 공급자 체인(배포에서는 task role)이다. 정적 키 설정을 두지 않는다.
     * 발송 한 번(자격 증명 조회 포함)은 send-timeout 안에 끝난다 — 선점 임대(네 배)는 그 위의 여유다(`SesMailTransport`).
     */
    private fun sesTransport(properties: MailProperties, timeout: Duration): MailTransport {
        val ses = properties.ses
        require(ses.region.isNotBlank()) { missing("ses.region") }
        require(REGION.matches(ses.region)) { "pulsemetry.mail.ses.region 이 리전 이름이 아니다" }
        val configurationSet = ses.configurationSet.takeIf { it.isNotBlank() }
        require(configurationSet == null || CONFIGURATION_SET.matches(configurationSet)) { "pulsemetry.mail.ses.configuration-set 이 configuration set 이름이 아니다" }
        return SesMailTransport.create(Region.of(ses.region), timeout, properties.from, configurationSet)
    }

    @Bean
    fun mailDispatcher(outbox: MailOutbox, transport: MailTransport) = MailDispatcher(outbox, transport)

    /** 관리 기능과 메일을 함께 켠 배포에서만 초대 메일을 적재한다. 설치 명령의 주소는 부트스트랩 주소와 같은 설정이다. */
    @Bean
    @ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
    fun invitationMailer(outbox: MailOutbox, management: ManagementProperties, server: PulsemetryProperties): InvitationMailer {
        require(management.invitationAcceptUrl.isNotBlank()) { "pulsemetry.management.invitation-accept-url 가 비어 있다" }
        return InvitationMailer(outbox, management.invitationAcceptUrl, server.baseUrl())
    }

    /** 문의 접수와 메일을 함께 켠 배포에서만 담당자에게 통지한다. */
    @Bean
    @ConditionalOnProperty(prefix = "pulsemetry.inquiries", name = ["enabled"], havingValue = "true")
    fun inquiryNotifier(outbox: MailOutbox, inquiries: InquiryProperties): InquiryNotifier {
        require(inquiries.notificationRecipient.isNotBlank()) { "pulsemetry.inquiries.notification-recipient 가 비어 있다" }
        try { InternetAddress(inquiries.notificationRecipient, true) } catch (_: AddressException) { throw IllegalArgumentException("pulsemetry.inquiries.notification-recipient 가 메일 주소가 아니다") }
        return InquiryNotifier(outbox, inquiries.notificationRecipient)
    }

    /**
     * 관리 기능과 메일을 함께 켠 배포에서만 설치 업데이트 안내를 보낸다(ADR 0043). 작업 기록(ADR 0039)의 첫 생산자다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
    fun installationNotifier(outbox: MailOutbox, jdbc: JdbcClient, manager: PlatformTransactionManager, clock: Clock): InstallationNotifier =
        InstallationNotifier(outbox, OperationStore(jdbc, manager, clock), jdbc)

    /** 한 바퀴 보낸 뒤 끝난 안내 메일의 결과를 작업에 옮긴다 — 같은 주기다. */
    @Bean
    fun mailDispatchJob(dispatcher: MailDispatcher, properties: MailProperties, installationNotifier: ObjectProvider<InstallationNotifier>) =
        MailDispatchJob(positive(properties.dispatchInterval, "dispatch-interval")) {
            dispatcher.runOnce()
            installationNotifier.ifAvailable?.reconcile()
        }

    private fun missing(key: String) = "pulsemetry.mail.$key 가 비어 있다"
    private fun positive(value: Duration?, key: String): Duration {
        val duration = requireNotNull(value) { missing(key) }
        require(!duration.isNegative && !duration.isZero) { "pulsemetry.mail.$key 는 0보다 커야 한다" }
        return duration
    }

    private companion object {
        val PROVIDERS = listOf("smtp", "ses")
        /** 예: `ap-northeast-2`. 목록과 대조하지 않는다 — SDK 보다 새 리전도 받는다 */
        val REGION = Regex("[a-z]{2}(-[a-z]+)+-[0-9]+")
        /** SES 의 configuration set 이름 규칙(영문·숫자·`_`·`-`, 64자 이하) */
        val CONFIGURATION_SET = Regex("[A-Za-z0-9_-]{1,64}")
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
