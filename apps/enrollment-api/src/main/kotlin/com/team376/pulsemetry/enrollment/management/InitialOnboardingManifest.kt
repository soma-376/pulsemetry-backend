package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.contract.ManifestPayload
import com.team376.pulsemetry.enrollment.contract.OtlpSettings
import com.team376.pulsemetry.enrollment.contract.PrivacySettings
import com.team376.pulsemetry.enrollment.contract.SignalSettings
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** 최초 수집 설정은 명시적인 서버 설정에서 만든다. 요청의 Host 헤더를 사용하지 않는다. */
internal object InitialOnboardingManifest {
    fun create(endpoint: String, mapper: ObjectMapper): JsonNode {
        val manifest = ManifestPayload(
            schemaVersion = 1, configRevision = 1,
            otlp = OtlpSettings(endpoint, "http/protobuf"),
            signals = SignalSettings(logs = true, metrics = true, traces = true),
            privacy = PrivacySettings(),
        )
        if (!manifest.satisfiesContract()) throw ManagementException("manifest_not_configured", 409)
        return mapper.valueToTree(manifest)
    }
}
