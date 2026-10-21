package com.team376.pulsemetry.telemetry.diagnostics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SafeDiagnosticTest {
    @Test
    fun causeTypesAndFramesRemainWithoutMessagesOrSuppressedPayload() {
        val cause = IllegalArgumentException("Bearer CANARY-token")
        val failure = IllegalStateException("query payload CANARY-body", cause)
        failure.addSuppressed(RuntimeException("CANARY-suppressed"))
        val rendered = SafeDiagnostic.of(failure)
        assertThat(rendered).contains("IllegalStateException", "IllegalArgumentException", "SafeDiagnosticTest")
        assertThat(rendered).doesNotContain("CANARY", "Bearer", "query payload")
        assertThat(rendered.length).isLessThanOrEqualTo(4096)
    }
}
