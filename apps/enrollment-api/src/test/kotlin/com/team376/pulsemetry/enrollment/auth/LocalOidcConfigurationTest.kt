package com.team376.pulsemetry.enrollment.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.nio.file.Files
import java.nio.file.Path

/** 설정만 로딩한다. 실제 DB·Cognito·실행 중인 서버에 접속하지 않는다. */
class LocalOidcConfigurationTest {
    @TempDir lateinit var directory: Path

    private fun properties(local: Boolean, secrets: Map<String, String> = emptyMap()): OidcProperties {
        Files.writeString(directory.resolve("local-auth.properties"), "")
        val environment = StandardEnvironment().apply {
            propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            propertySources.addFirst(MapPropertySource("test", mapOf(
                "spring.config.location" to "classpath:/application.yaml",
                "spring.profiles.active" to if (local) "local" else "default",
                "PULSEMETRY_DEV_AUTH_DIR" to directory.toString(),
            ) + secrets))
        }
        ConfigDataEnvironmentPostProcessor.applyTo(environment)
        return Binder.get(environment).bind("pulsemetry.oidc", Bindable.of(OidcProperties::class.java)).get()
    }

    @Test fun `별도 Cognito 파일 없이 회사별 환경변수만 주입한다`() {
        val properties = properties(true, mapOf(
            "PULSEMETRY_COGNITO_A_CLIENT_SECRET" to "test-only-a",
            "PULSEMETRY_COGNITO_B_CLIENT_SECRET" to "test-only-b"))
        assertThat(properties.enabled).isTrue()
        assertThat(properties.callbackRegistrationId).isEqualTo("cognito")
        assertThat(properties.clientSecrets).containsExactlyInAnyOrderEntriesOf(
            mapOf("cognito-a" to "test-only-a", "cognito-b" to "test-only-b"))
        assertThat(Files.exists(directory.resolve("cognito.properties"))).isFalse()
    }

    @Test fun `비밀 누락은 다른 회사나 구 공유 풀 비밀로 대체하지 않는다`() {
        val properties = properties(true, mapOf(
            "PULSEMETRY_COGNITO_A_CLIENT_SECRET" to "test-only-a",
            "PULSEMETRY_COGNITO_CLIENT_SECRET" to "unused-old-secret"))
        assertThat(properties.clientSecrets["cognito-b"]).isEmpty()
        assertThat(properties.clientSecrets).doesNotContainKey("cognito")
    }

    @Test fun `기본 프로파일은 개발 Secret 환경변수가 있어도 OIDC가 비활성이다`() {
        val properties = properties(false, mapOf("PULSEMETRY_COGNITO_A_CLIENT_SECRET" to "unused"))
        assertThat(properties.enabled).isFalse()
        assertThat(properties.clientSecrets).isEmpty()
    }
}
