package com.team376.pulsemetry.enrollment.config

import com.team376.pulsemetry.enrollment.service.TelemetryCredentialIssuer
import com.team376.pulsemetry.security.TelemetryTokenHasher
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtContract
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtIssuer
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtKeys
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.time.Duration

/**
 * telemetry JWT 발급 설정(ADR 0065). 사용자 JWT(`pulsemetry.user-auth`)와 키·설정을 공유하지 않는다.
 *
 * [issueEnabled] 는 기본 false 다. **모든 검증자(Gateway·HTTP ingest)에 공개키를 먼저 배포한 뒤에 켠다** — 먼저 켜면 새로
 * 발급한 토큰이 공개키 없는 검증자에서 401 이 된다(ADR 0065 Decision 8). 꺼져 있으면 키 파일을 읽지 않는다.
 *
 * [publicKeyFiles] 는 `kid → X.509 PEM 경로` 맵이다. 환경변수 이름은 kid 의 대소문자를 잃으므로 배포는
 * `SPRING_APPLICATION_JSON` 으로 넣는다(사용자 JWT 와 같은 방식). 발급자는 활성 kid 의 공개키로 개인키와의 짝을 기동 때 확인한다.
 */
@ConfigurationProperties(prefix = "pulsemetry.telemetry-jwt")
data class TelemetryJwtProperties(
	val issueEnabled: Boolean = false,
	val activeKid: String = "",
	val privateKeyFile: String = "",
	val publicKeyFiles: Map<String, String> = emptyMap(),
	/**
	 * 발급 수명. 계약값(3시간)이 기본이고 더 길게 둘 수 없다. 짧게 두는 것은 만료 뒤 재발급을 확인하는 검증 환경 용이다
	 * (`docs/telemetry-kafka-e2e.md`). 운영 배포에서는 설정하지 않는다.
	 */
	val lifetime: Duration = TelemetryJwtContract.LIFETIME,
)

/**
 * telemetry token 발급기를 조립한다. 라이브러리(`:libs:telemetry-auth`)는 스테레오타입을 달지 않으므로 여기서 엮는다(ADR 0011).
 *
 * 켜졌는데 키가 없거나 짝이 맞지 않으면 **기동이 실패한다** — 발급을 켠 채로 `ptt_` 로 조용히 되돌아가지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class TelemetryJwtConfig {

	@Bean
	fun telemetryCredentialIssuer(
		properties: TelemetryJwtProperties,
		hasher: TelemetryTokenHasher,
	): TelemetryCredentialIssuer = TelemetryCredentialIssuer(hasher, jwtIssuer(properties))

	internal fun jwtIssuer(properties: TelemetryJwtProperties): TelemetryJwtIssuer? {
		if (!properties.issueEnabled) {
			log.info("telemetry JWT 발급이 꺼져 있다 — ptt_ 를 발급한다")
			return null
		}
		require(properties.privateKeyFile.isNotBlank()) {
			"pulsemetry.telemetry-jwt.private-key-file 이 비어 있다. issue-enabled=true 에는 개인키가 필요하다."
		}
		val issuer = TelemetryJwtIssuer(
			activeKid = properties.activeKid,
			privateKey = TelemetryJwtKeys.privateKey(Path.of(properties.privateKeyFile)),
			publicKeys = TelemetryJwtKeys.publicKeys(properties.publicKeyFiles),
			lifetime = properties.lifetime,
		)
		log.info(
			"telemetry JWT 발급을 켠다: activeKid={} kids={} lifetime={}",
			properties.activeKid, properties.publicKeyFiles.keys.sorted(), properties.lifetime,
		)
		return issuer
	}

	private companion object {
		val log = LoggerFactory.getLogger(TelemetryJwtConfig::class.java)
	}
}
