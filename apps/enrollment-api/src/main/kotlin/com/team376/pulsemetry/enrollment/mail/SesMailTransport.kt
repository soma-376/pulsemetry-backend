package com.team376.pulsemetry.enrollment.mail

import com.team376.pulsemetry.persistence.enrollment.mail.ClaimedMail
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransport
import com.team376.pulsemetry.persistence.enrollment.mail.MailTransportFailure
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException
import software.amazon.awssdk.services.sesv2.model.BadRequestException
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.LimitExceededException
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException
import software.amazon.awssdk.services.sesv2.model.MessageHeader
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException
import software.amazon.awssdk.services.sesv2.model.NotFoundException
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendingPausedException
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration

/**
 * SES API(`SendEmail`)로 한 통을 보낸다 — 배포 환경의 발송 구현이다(ADR 0057).
 *
 * 실패는 공개 분류 코드(ADR 0037 의 어휘 그대로)와 API 에 내지 않는 세부 토큰(`ses_*`)으로 나눠 돌려준다.
 * 판정은 SDK 예외 타입과 오류 코드·HTTP 상태로만 한다. AWS 오류 메시지 원문은 저장하지도 로그에 남기지도 않는다 —
 * 수신자 주소가 섞일 수 있다.
 *
 * SES 가 받은 메일의 `MessageId` 는 운영 로그에만 남긴다(`event=mail_provider_accepted`). 그 로그는 SES 수락의 증거이고
 * 뒤이은 outbox `sent` 기록의 성공을 뜻하지 않는다. 클라이언트는 이 객체가 갖고 [close] 에서 닫는다 —
 * 빈으로 만들면 컨텍스트가 닫힐 때 Spring 이 함께 닫는다.
 */
class SesMailTransport(private val client: SesV2Client, private val from: String, private val configurationSet: String?) : MailTransport, AutoCloseable {
    override fun send(mail: ClaimedMail) {
        // SES 에 묻기 전에 주소 형식과 수신자 한 명을 확인한다. SMTP 구현과 같은 판정이고 같은 공개 코드다.
        try { InternetAddress(mail.recipient, true) } catch (_: AddressException) { throw MailTransportFailure(true, "invalid_address", "ses_invalid_address") }
        val response = try {
            client.sendEmail(request(mail))
        } catch (error: SdkException) {
            val failure = classify(error)
            val service = error as? SdkServiceException
            log.warn("SES 발송이 실패했다 event=mail_provider_failed provider=ses mail_id={} kind={} attempt={} detail={} status={} request_id={} error={}",
                mail.id, mail.kind, mail.attempt, failure.detail, service?.statusCode(), service?.requestId(), error.javaClass.simpleName)
            throw failure
        }
        log.info("SES 가 메일을 접수했다 event=mail_provider_accepted provider=ses mail_id={} kind={} attempt={} provider_message_id={}",
            mail.id, mail.kind, mail.attempt, response.messageId())
    }

    override fun close() = client.close()

    private fun request(mail: ClaimedMail): SendEmailRequest = SendEmailRequest.builder()
        .fromEmailAddress(from)
        .destination { it.toAddresses(mail.recipient) }
        .content { content ->
            content.simple { message ->
                message.subject(utf8(mail.subject)).body { it.text(utf8(mail.body)) }
                    // 받은 쪽에서 같은 메일의 재전송을 알아볼 수 있게 한다. SES 의 중복 방지 키가 아니다.
                    .headers(MessageHeader.builder().name(MAIL_ID_HEADER).value(mail.id.toString()).build())
            }
        }
        // 비어 있으면 요청에서 뺀다. 이름을 지어내거나 리소스를 만들지 않는다.
        .configurationSetName(configurationSet)
        .build()

    private fun utf8(value: String) = Content.builder().data(value).charset("UTF-8").build()

    companion object {
        private val log = LoggerFactory.getLogger(SesMailTransport::class.java)

        /** 인증 실패를 뜻하는 AWS 오류 코드. 401·403 응답도 같은 칸이다. */
        private val AUTH_ERRORS = setOf("AccessDeniedException", "UnrecognizedClientException", "InvalidSignatureException",
            "ExpiredTokenException", "MissingAuthenticationTokenException")

        /**
         * 발송에 쓰는 SES 클라이언트. **SDK 는 한 번만 시도한다** — 재시도는 outbox 가 갖는다(시도 횟수 = outbox `attempts`).
         * [timeout] 은 호출 전체의 제한이고 한 시도·연결·읽기의 제한도 같은 값이다. 자격 증명은 기본 공급자 체인에서 찾는다
         * (배포에서는 enrollment-api task role). 만들 때 AWS 에 말하지 않는다. [customize] 는 테스트가 접속 주소와 가짜 자격 증명을 넣는 자리다.
         */
        fun client(region: Region, timeout: Duration, customize: (SesV2ClientBuilder) -> Unit = {}): SesV2Client = SesV2Client.builder()
            .region(region)
            .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(timeout).socketTimeout(timeout))
            .overrideConfiguration { it.retryStrategy(AwsRetryStrategy.doNotRetry()).apiCallTimeout(timeout).apiCallAttemptTimeout(timeout) }
            .also(customize)
            .build()

        /**
         * SDK 예외를 outbox 의 실패로 바꾼다. 메시지 문자열은 보지 않는다.
         * 발신 identity 미검증도 `MessageRejected` 로 올 수 있어 영구 실패로 닫힌다 — 배포 전 identity 검증으로 막는다(`docs/mail-operations.md`).
         */
        fun classify(error: SdkException): MailTransportFailure = when {
            error is MessageRejectedException -> MailTransportFailure(true, "message_rejected", "ses_message_rejected")
            error is BadRequestException -> MailTransportFailure(true, "message_rejected", "ses_bad_request")
            // 일일·초당 한도의 오류와 같다고 추측하지 않는다. 계정 한도 쪽 오류다.
            error is LimitExceededException -> retry("ses_limit_exceeded")
            error is TooManyRequestsException -> retry("ses_throttled")
            error is MailFromDomainNotVerifiedException || error is NotFoundException -> retry("ses_configuration_error")
            error is SendingPausedException || error is AccountSuspendedException -> retry("ses_sending_disabled")
            error is AwsServiceException && error.isThrottlingException -> retry("ses_throttled")
            error is SdkServiceException && (error.statusCode() == 401 || error.statusCode() == 403 || errorCode(error) in AUTH_ERRORS) -> retry("ses_auth_failed")
            error is SdkServiceException && error.statusCode() >= 500 -> retry("ses_unavailable")
            // 응답을 기다리다 끊었으면 SES 가 받았는지 알 수 없다. 다시 보내면 중복일 수 있다.
            error is ApiCallTimeoutException || error is ApiCallAttemptTimeoutException || causes(error).any { it is InterruptedIOException } -> retry("ses_timeout")
            error is SdkClientException && causes(error).any { it is IOException } -> retry("ses_unavailable")
            else -> retry("ses_unknown_error")
        }

        private fun retry(detail: String) = MailTransportFailure(false, "send_error", detail)

        private fun errorCode(error: SdkException): String = (error as? AwsServiceException)?.awsErrorDetails()?.errorCode().orEmpty()

        private fun causes(root: Throwable): Sequence<Throwable> = generateSequence(root) { current -> current.cause?.takeIf { it !== current } }.take(16)
    }
}
