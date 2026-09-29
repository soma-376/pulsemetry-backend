package com.team376.pulsemetry.security.user

import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import java.time.Clock

/** 서명과 현재 계정·세션을 함께 확인한다. 조회 전용 DB 계정으로도 실행할 수 있다. */
class UserAccessVerifier(private val jwt: UserJwt, private val repository: UserAuthRepository, private val clock: Clock) {
    fun verify(token: String): UserIdentity {
        val identity = jwt.verify(token)
        val session = repository.session(identity.sessionId) ?: invalid()
        val member = repository.member(identity.memberId)?.takeIf { it.active() } ?: invalid()
        if (session.revokedAt != null || session.expiresAt <= clock.instant() || session.memberId != member.id ||
            member.tenantId != identity.tenantId || member.role != identity.role) invalid()
        return identity
    }
}
