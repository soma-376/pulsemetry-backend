package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import org.springframework.stereotype.Service
import java.time.Clock

/** 스크립트 발급의 읽기 전용 사전 검사(ADR 0005). 최종 허가·소비는 enroll의 조건부 UPDATE만 한다. */
@Service
class BootstrapInvitationValidator(private val invitations: InvitationRepository, private val clock: Clock) {
    fun validate(raw: String?): String {
        if (raw.isNullOrBlank()) throw EnrollmentException.missingCode()
        val code = InvitationCode.normalize(raw) ?: throw EnrollmentException.malformedCode()
        // 형식 오류는 DB 연결도 얻지 않는다. 읽기 전용 트랜잭션은 repository 조회에서 시작한다.
        val invitation = invitations.findByCodeHash(Sha256.hex(code)) ?: throw EnrollmentException.invitationNotFound()
        // 최종 소비 실패와 같은 우선순위다. DB 예외를 잡아 코드 오류로 바꾸지 않는다.
        when {
            invitation.isUsed() -> throw EnrollmentException.invitationUsed()
            invitation.isRevoked() -> throw EnrollmentException.invitationRevoked()
            invitation.isExpiredAt(clock.instant()) -> throw EnrollmentException.invitationExpired()
        }
        return code
    }
}
