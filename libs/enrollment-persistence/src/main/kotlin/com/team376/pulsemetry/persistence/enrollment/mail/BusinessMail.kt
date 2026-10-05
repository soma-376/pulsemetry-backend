package com.team376.pulsemetry.persistence.enrollment.mail

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val SEOUL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

/**
 * 응답과 목록에 내는 발송 상태 (ADR 0038). 발급·접수와 별개의 사실이다.
 * `sent` 는 SMTP 서버가 받았다는 뜻이고, 메일이 없으면 `not_sent` 와 그 이유를 낸다 — 적재된 것처럼 보이게 하지 않는다.
 */
object MailDeliveryView {
    fun of(delivery: MailDelivery): Map<String, Any?> =
        of(delivery.status, delivery.attempts, delivery.queuedAt, delivery.lastAttemptAt, delivery.finishedAt, delivery.failureCode)

    fun of(status: String, attempts: Int, queuedAt: Instant, lastAttemptAt: Instant?, finishedAt: Instant?, failureCode: String?): Map<String, Any?> = mapOf(
        "status" to status, "reason" to null, "queuedAt" to queuedAt.toString(), "lastAttemptAt" to lastAttemptAt?.toString(),
        "sentAt" to finishedAt?.takeIf { status == "sent" }?.toString(), "failureCode" to failureCode, "attempts" to attempts)

    /** 이 대상의 메일이 outbox 에 없다. 메일 기능이 꺼져 있으면 `mail_disabled`, 켜져 있는데 없으면 `not_queued` 다. */
    fun notSent(mailEnabled: Boolean): Map<String, Any?> = mapOf("status" to "not_sent", "reason" to if (mailEnabled) "not_queued" else "mail_disabled",
        "queuedAt" to null, "lastAttemptAt" to null, "sentAt" to null, "failureCode" to null, "attempts" to 0)
}

/**
 * 초대 메일 (ADR 0038). 초대 하나에 메일 하나다 — 중복 방지 키가 초대 ID 다.
 * 코드는 본문에만 싣는다. 수락 링크에는 fragment 로 붙여 서버 접근 로그와 Referer 에 남지 않게 한다.
 * 설치 명령의 주소는 기존 부트스트랩 명령과 같은 형태다(ADR 0005).
 * 본문은 그 코드에 남은 용도만 안내한다(ADR 0055) — 가입이 닫힌 코드에 계정 만들기 링크를, 설치를 마친 코드에 설치 명령을 싣지 않는다.
 */
class InvitationMailer(private val outbox: MailOutbox, acceptUrl: String, installBaseUrl: String) {
    private val acceptUrl = acceptUrl.trim().also {
        val uri = runCatching { URI(it) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.fragment == null) { "초대 수락 주소는 fragment 없는 http(s) 주소여야 한다" }
    }
    private val installBaseUrl = installBaseUrl.trimEnd('/')

    /**
     * 호출자의 트랜잭션 안에서 적재한다. 같은 초대로 다시 부르면 기존 메일의 상태를 돌려준다.
     * [signup]·[install] 은 그 코드로 아직 할 수 있는 일이다. 둘 다 없는 초대에는 메일을 만들지 않는다.
     */
    fun enqueue(invitation: UUID, organization: String, email: String, code: String, expiresAt: Instant,
        signup: Boolean = true, install: Boolean = true): MailDelivery {
        require(signup || install) { "남은 용도가 없는 초대에는 메일을 보내지 않는다" }
        val installOnly = install && !signup
        val sections = buildList {
            add(if (installOnly) "$organization 에서 Pulsemetry 설치 코드를 보냈습니다. 새 PC 등에 CLI 를 설치할 때 씁니다."
                else "$organization 에서 Pulsemetry 초대 코드를 보냈습니다.")
            add("${if (installOnly) "설치 코드" else "초대 코드"}: $code\n유효 기간: ${SEOUL.format(expiresAt)} (한국 시간)까지")
            if (signup) add("회사 SSO 로그인\n$acceptUrl")
            if (install) add("CLI 설치 — 터미널에 붙여넣습니다.\nmacOS·Linux: curl -fsSL '$installBaseUrl/unix?code=$code' | sh\nWindows: irm '$installBaseUrl/windows?code=$code' | iex")
            add(when {
                signup && install -> "회사 계정으로 SSO 로그인하세요. 이 코드는 CLI 설치에 한 번 쓸 수 있으며 로그인에는 사용하지 않습니다."
                install -> "이 코드는 설치에 한 번 쓸 수 있습니다. 로그인에는 쓸 수 없습니다."
                else -> "회사 계정으로 SSO 로그인하세요. 설치는 이미 마쳤습니다."
            } + " 관리자가 코드를 다시 발급하거나 초대를 취소하면 이 코드는 더 이상 쓸 수 없습니다.\n요청한 적 없는 초대라면 이 메일을 무시하세요.")
        }
        return outbox.enqueue(MailDraft(key(invitation), "invitation", email,
            if (installOnly) "Pulsemetry 설치 코드" else "Pulsemetry 초대 코드", sections.joinToString("\n\n")))
    }

    /** 폐기된 초대의 아직 보내지 않은 메일을 취소한다. 이미 나갔거나 보내는 중이면 false 다. */
    fun cancel(invitation: UUID): Boolean = outbox.cancel(key(invitation))

    companion object {
        fun key(invitation: UUID) = "invitation:$invitation"
    }
}

/** 접수된 도입 문의를 담당자에게 알린다. 문의 하나에 통지 하나다. */
class InquiryNotifier(private val outbox: MailOutbox, recipient: String) {
    private val recipient = recipient.trim().also { require(it.isNotEmpty() && it.none(Char::isWhitespace) && it.count { c -> c == '@' } == 1) { "문의 통지 수신 주소가 올바르지 않다" } }

    /** 호출자의 트랜잭션 안에서 적재한다. 회사명과 이메일은 본문에만 싣는다. */
    fun enqueue(inquiry: UUID, company: String, email: String, receivedAt: Instant): MailDelivery = outbox.enqueue(MailDraft("inquiry:$inquiry", "inquiry_notice", recipient,
        "Pulsemetry 도입 문의 접수", """
            |도입 문의가 접수되었습니다.
            |
            |접수 번호: $inquiry
            |접수 시각: ${SEOUL.format(receivedAt)} (한국 시간)
            |회사명: $company
            |회사 이메일: $email
            |
            |이 문의로 조직이나 계정은 만들어지지 않았습니다. 담당자가 확인한 뒤 첫 관리자를 초대합니다.
        """.trimMargin()))
}
