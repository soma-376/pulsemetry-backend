package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.contract.ManifestPayload
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class InitialOnboardingManifestTest {
    private val mapper = jacksonObjectMapper()

    @Test fun `명시한 수집 주소로 계약에 맞는 초기 설정을 생성하고 민감 정보 수집을 끈다`() {
        for (endpoint in listOf("http://localhost:4316", "https://ingest.example.test")) {
            val node = InitialOnboardingManifest.create(endpoint, mapper)
            val manifest = mapper.treeToValue(node, ManifestPayload::class.java)
            assertThat(manifest.satisfiesContract()).isTrue()
            assertThat(manifest.otlp.endpoint).isEqualTo(endpoint)
            assertThat(node.properties().map { it.key }).containsExactlyInAnyOrder("schema_version", "config_revision", "otlp", "signals", "privacy")
            assertThat(node.path("privacy").properties().map { it.value.asBoolean() }).containsOnly(false)
            assertThat(node.path("signals").properties().map { it.value.asBoolean() }).containsOnly(true)
        }
    }

    @Test fun `수집 주소가 없거나 클라이언트 계약에 어긋나면 최초 설정을 생성하지 않는다`() {
        for (endpoint in listOf("", "not-a-url", "http://ingest.example.test", "http://localhost.evil.test", "ftp://localhost")) {
            assertThatThrownBy { InitialOnboardingManifest.create(endpoint, mapper) }
                .isInstanceOf(ManagementException::class.java).hasMessage("manifest_not_configured")
        }
    }
}
