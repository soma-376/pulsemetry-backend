package com.team376.pulsemetry.enrollment.config

import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** 발급 설정이 켜졌을 때 키 공급 실수로 기동이 멈추는지, 꺼졌을 때 키를 읽지 않는지 본다(ADR 0065 Decision 2·8). */
class TelemetryJwtConfigTest {

	@TempDir
	lateinit var dir: Path

	private val config = TelemetryJwtConfig()

	@Test
	@DisplayName("꺼져 있으면 키 경로가 엉터리여도 발급기를 만들지 않는다 — ptt_ 그대로다")
	fun disabledIgnoresKeys() {
		assertThat(config.jwtIssuer(TelemetryJwtProperties(issueEnabled = false, privateKeyFile = "/nope"))).isNull()
	}

	@Test
	@DisplayName("켜졌는데 개인키·공개키 맵·활성 kid 가 없거나 짝이 틀리면 실패한다")
	fun enabledRequiresKeys() {
		val key = TelemetryJwtFixtures.signingKey("tk-1")
		val other = TelemetryJwtFixtures.signingKey("tk-2")
		val (privatePath, publicPath) = key.writePem(dir)
		val (_, otherPublic) = other.writePem(dir)

		assertThatThrownBy { config.jwtIssuer(TelemetryJwtProperties(issueEnabled = true, activeKid = "tk-1")) }
			.hasMessageContaining("private-key-file")
		assertThatThrownBy {
			config.jwtIssuer(TelemetryJwtProperties(issueEnabled = true, activeKid = "tk-1", privateKeyFile = privatePath.toString()))
		}.hasMessageContaining("public-key-files")
		assertThatThrownBy {
			config.jwtIssuer(
				TelemetryJwtProperties(true, "tk-1", privatePath.toString(), mapOf("tk-1" to otherPublic.toString())),
			)
		}.hasMessageContaining("짝이 아니다")
		assertThatThrownBy {
			config.jwtIssuer(TelemetryJwtProperties(true, "", privatePath.toString(), mapOf("tk-1" to publicPath.toString())))
		}.hasMessageContaining("active-kid")

		assertThat(
			config.jwtIssuer(TelemetryJwtProperties(true, "tk-1", privatePath.toString(), mapOf("tk-1" to publicPath.toString()))),
		).isNotNull()
	}
}
