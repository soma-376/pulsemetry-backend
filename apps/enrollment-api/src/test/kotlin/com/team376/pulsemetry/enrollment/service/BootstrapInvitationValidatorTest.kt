package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.api.BootstrapController
import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Clock

class BootstrapInvitationValidatorTest {
    @Test fun `DB 장애는 코드 오류 스크립트나 정상 다운로드로 바뀌지 않는다`() {
        val repository = mock(InvitationRepository::class.java)
        val failure = DataAccessResourceFailureException("database unavailable")
        `when`(repository.findByCodeHash(Sha256.hex("ABCD-EFGH-JKMN"))).thenThrow(failure)
        val scripts = BootstrapScripts(PulsemetryProperties("https://get.example.test", PulsemetryProperties.Admin("test"), "test"))
        val controller = BootstrapController(scripts, BootstrapInvitationValidator(repository, Clock.systemUTC()))
        assertThatThrownBy { controller.unix("ABCD-EFGH-JKMN") }.isSameAs(failure)
        assertThatThrownBy { controller.windows("ABCD-EFGH-JKMN") }.isSameAs(failure)
    }

    @Test fun `누락과 주입 형식은 DB에 묻지 않는다`() {
        val repository = mock(InvitationRepository::class.java)
        val validator = BootstrapInvitationValidator(repository, Clock.systemUTC())
        for (raw in listOf(null, "", " ", "'; echo unsafe")) assertThatThrownBy { validator.validate(raw) }
            .isInstanceOf(com.team376.pulsemetry.enrollment.error.EnrollmentException::class.java)
        verifyNoInteractions(repository)
    }
}
