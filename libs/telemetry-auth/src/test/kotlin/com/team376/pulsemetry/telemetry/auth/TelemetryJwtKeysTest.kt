package com.team376.pulsemetry.telemetry.auth

import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** 키 파일 로더가 공급 실수를 기동 시점에 드러내고 메시지에 키 내용을 싣지 않는지 본다. */
class TelemetryJwtKeysTest {

	@TempDir
	lateinit var dir: Path

	@Test
	@DisplayName("PKCS8 개인키와 X.509 공개키를 읽고 kid 맵을 만든다")
	fun loads() {
		val key = TelemetryJwtFixtures.signingKey("tk-1")
		val (privatePath, publicPath) = key.writePem(dir)

		assertThat(TelemetryJwtKeys.privateKey(privatePath).modulus).isEqualTo(key.publicKey.modulus)
		assertThat(TelemetryJwtKeys.publicKeys(mapOf("tk-1" to publicPath.toString()))["tk-1"]?.modulus)
			.isEqualTo(key.publicKey.modulus)
	}

	@Test
	@DisplayName("없는 파일·빈 파일·다른 PEM·Base64 아님·RSA 2048비트 미만은 kid 와 경로만 담아 거부한다")
	fun rejects() {
		val key = TelemetryJwtFixtures.signingKey("tk-1")
		val (privatePath, publicPath) = key.writePem(dir)
		val empty = Files.writeString(dir.resolve("empty.pem"), "\n")
		val garbage = Files.writeString(dir.resolve("garbage.pem"), "-----BEGIN PUBLIC KEY-----\n!!!!\n-----END PUBLIC KEY-----\n")
		val (_, smallPublic) = TelemetryJwtFixtures.signingKey("small", bits = 1024).writePem(dir)

		assertThatThrownBy { TelemetryJwtKeys.publicKey(dir.resolve("missing.pem"), "tk-x") }
			.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("kid=tk-x").hasMessageContaining("missing.pem")
		assertThatThrownBy { TelemetryJwtKeys.publicKey(empty, "tk-1") }.hasMessageContaining("비어 있다")
		// 개인키 파일을 공개키 자리에 두었다.
		assertThatThrownBy { TelemetryJwtKeys.publicKey(privatePath, "tk-1") }.hasMessageContaining("PUBLIC KEY")
		assertThatThrownBy { TelemetryJwtKeys.privateKey(publicPath) }.hasMessageContaining("PRIVATE KEY")
		assertThatThrownBy { TelemetryJwtKeys.publicKey(garbage, "tk-1") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { TelemetryJwtKeys.publicKey(smallPublic, "small") }.hasMessageContaining("2048")

		val pemBody = Files.readString(privatePath).lines()[1]
		assertThatThrownBy { TelemetryJwtKeys.publicKey(privatePath, "tk-1") }
			.satisfies({ assertThat(it.message).doesNotContain(pemBody) })
	}

	@Test
	@DisplayName("빈 맵·빈 kid·빈 경로를 조용히 건너뛰지 않는다")
	fun mapEntries() {
		assertThatThrownBy { TelemetryJwtKeys.publicKeys(emptyMap()) }.hasMessageContaining("public-key-files")
		assertThatThrownBy { TelemetryJwtKeys.publicKeys(mapOf("tk-1" to " ")) }.hasMessageContaining("kid=tk-1")
		assertThatThrownBy { TelemetryJwtKeys.publicKeys(mapOf(" " to "/x")) }.hasMessageContaining("빈 kid")
	}
}
