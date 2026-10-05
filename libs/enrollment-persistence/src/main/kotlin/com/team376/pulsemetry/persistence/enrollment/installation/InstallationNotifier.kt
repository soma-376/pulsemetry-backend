package com.team376.pulsemetry.persistence.enrollment.installation

import com.team376.pulsemetry.persistence.enrollment.mail.MailDraft
import com.team376.pulsemetry.persistence.enrollment.mail.MailOutbox
import com.team376.pulsemetry.persistence.enrollment.operation.Operation
import com.team376.pulsemetry.persistence.enrollment.operation.OperationKind
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 설치 업데이트 안내 (ADR 0043). 빈이 아니다 — 조립은 앱이 한다.
 *
 * 안내는 **설치를 쓰는 구성원에게 보내는 메일**이다. 원격으로 업데이트하거나 정책을 밀어 넣지 않는다. 본문은 telemetryctl 기본 브랜치에
 * 있는 명령과 동작만 안내한다(ADR 0053) — 지금의 데몬은 새 정책을 스스로 받아 오지 않으므로 다시 설치해야 새 정책이 적용된다고 적는다.
 * 안내 한 통은 작업(`installation_notification`)의 대상 하나이고, 그 대상의 결과는 **메일의 발송 결과**다(`sent` = 메일 공급자가 받음, ADR 0057).
 * 설치가 정책을 적용했다는 뜻이 아니다.
 */
class InstallationNotifier(
    private val outbox: MailOutbox,
    private val operations: OperationStore,
    private val jdbc: JdbcClient,
) {

    /** 안내 한 통의 내용. 비밀은 담지 않는다. */
    data class Notice(
        val installationId: UUID,
        val recipient: String,
        val organization: String,
        val hostname: String?,
        val platform: String,
        val desiredVersion: Long,
        /** 확인된 적용 판. 확인되지 않았으면 null. */
        val appliedVersion: Long?,
    )

    /** 호출자의 트랜잭션 안에서 적재한다. 같은 작업·설치로 다시 부르면 기존 메일의 상태를 돌려준다. */
    private fun enqueue(operationId: UUID, notice: Notice) = outbox.enqueue(MailDraft(key(operationId, notice.installationId), KIND, notice.recipient,
        "Pulsemetry 수집 정책 업데이트 확인 요청", """
            |${notice.organization}의 관리자가 새 수집 정책을 저장했습니다(정책 판 ${notice.desiredVersion}).
            |
            |이 PC의 Pulsemetry는 ${notice.appliedVersion?.let { "아직 이전 정책(판 $it)을 적용하고 있습니다" } ?: "새 정책을 적용했다는 보고를 아직 보내지 않았습니다"}.
            |기기: ${notice.hostname?.takeIf { it.isNotBlank() } ?: "(이름 없음)"} · ${notice.platform}
            |
            |확인해 주세요.
            |1. 터미널에서 pulsemetry status를 실행해 데몬이 돌고 있는지 봅니다. 돌고 있지 않으면 다시 띄웁니다.
            |2. 지금 설치된 Pulsemetry는 새 정책을 스스로 받아 오지 않습니다.
            |   새 정책을 적용하려면 관리자에게 설치 안내를 다시 받아 그 안내대로 다시 설치합니다.
            |
            |이 메일은 확인을 부탁하는 안내입니다. 원격으로 업데이트하거나 이 PC의 설정을 바꾸지 않습니다.
        """.trimMargin()))

    /**
     * 안내 작업을 만들고 대상마다 메일을 적재한다. 호출자의 트랜잭션에 참여한다 — 대상 검증·작업·메일이 함께 커밋되거나 함께 롤백된다.
     * 작업은 곧바로 `running` 이다. 대상의 결과는 메일이 끝난 뒤 [reconcile] 이 기록한다.
     */
    fun notify(tenantId: UUID, requestedBy: UUID, notices: List<Notice>): Operation {
        require(notices.isNotEmpty()) { "안내할 설치가 없다" }
        val created = operations.create(tenantId, OperationKind.INSTALLATION_NOTIFICATION, requestedBy, notices.map { it.installationId.toString() })
        for (notice in notices) enqueue(created.id, notice)
        return operations.start(tenantId, created.id)
    }

    /**
     * 끝난 메일의 결과를 작업의 대상 결과로 옮기고 옮긴 수를 돌려준다. 메일 발송 작업이 한 바퀴 돈 뒤에 부른다.
     * 보냄 → 대상 성공, 실패 → 대상 실패(메일의 실패 분류 코드), 취소 → 대상 실패(`cancelled`). 아직 보내는 중이면 건드리지 않는다.
     */
    fun reconcile(): Int {
        val finished = jdbc.sql(
            """SELECT o.tenant_id, o.id, t.target_id, m.status, m.failure_code
            FROM enrollment.operations o
            JOIN enrollment.operation_targets t ON t.operation_id = o.id
            JOIN enrollment.mail_outbox m ON m.dedup_key = 'installation-notice:' || o.id::text || ':' || t.target_id
            WHERE o.kind = :kind AND o.status = 'running' AND t.status = 'pending' AND m.status IN ('sent', 'failed', 'cancelled')
            ORDER BY o.created_at, t.position""",
        )
            .param("kind", OperationKind.INSTALLATION_NOTIFICATION.wire)
            .query { rs, _ ->
                Finished(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5))
            }
            .list()
        for (mail in finished) {
            when (mail.status) {
                "sent" -> operations.succeed(mail.tenantId, mail.operationId, mail.targetId)
                "cancelled" -> operations.fail(mail.tenantId, mail.operationId, mail.targetId, CANCELLED)
                else -> operations.fail(mail.tenantId, mail.operationId, mail.targetId, mail.failureCode ?: SEND_ERROR)
            }
        }
        return finished.size
    }

    private class Finished(val tenantId: UUID, val operationId: UUID, val targetId: String, val status: String, val failureCode: String?)

    companion object {
        const val KIND = "installation_notice"
        private const val CANCELLED = "cancelled"
        private const val SEND_ERROR = "send_error"

        fun key(operationId: UUID, installationId: UUID) = "installation-notice:$operationId:$installationId"
    }
}
