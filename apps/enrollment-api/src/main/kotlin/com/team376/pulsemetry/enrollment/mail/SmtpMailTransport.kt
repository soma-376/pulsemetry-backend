package com.team376.pulsemetry.enrollment.mail

import com.team376.pulsemetry.persistence.enrollment.mail.ClaimedMail
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransport
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransportFailure
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.internet.AddressException
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper

/**
 * SMTP 로 한 통을 보낸다 — 로컬·테스트의 발송 구현이다(ADR 0057). 실패는 다시 시도할 만한 것과 아닌 것으로 나눠 돌려준다.
 * 서버 응답 원문은 싣지 않는다.
 */
class SmtpMailTransport(private val sender: JavaMailSender, private val from: String) : MailTransport {
    override fun send(mail: ClaimedMail) {
        try {
            val message = sender.createMimeMessage()
            MimeMessageHelper(message, false, "UTF-8").apply { setFrom(from); setTo(mail.recipient); setSubject(mail.subject); setText(mail.body, false) }
            // 받은 쪽에서 같은 메일의 재전송을 알아볼 수 있게 한다.
            message.setHeader(MAIL_ID_HEADER, mail.id.toString())
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
