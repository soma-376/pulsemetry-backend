package com.team376.pulsemetry.enrollment.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * PLAN.md §6.8 이 정한 설정 키.
 *
 * [publicBaseUrl] 은 초대 응답의 설치 명령과 부트스트랩 스크립트가 쓰는 서버 주소다.
 * **`Host` 헤더에서 유도하지 마라** — 공격자가 헤더를 바꾸면 사용자가 남의 서버에서
 * 바이너리를 받게 된다. 주소는 운영자가 설정으로 못박는 값이어야 한다.
 */
@ConfigurationProperties(prefix = "pulsemetry")
data class PulsemetryProperties(

	val publicBaseUrl: String,

	val admin: Admin,

	/**
	 * telemetry token HMAC-SHA256 키. auth-proxy(ai-telemetry-pipeline)와 **공유하는 값**이다 —
	 * 그쪽이 같은 키로 Bearer 토큰을 해시해 조회하므로, 값이 다르면 발급한 모든 토큰이 401 이 된다.
	 * dev 인프라에서는 `DevEdgeStack` 의 `TokenHashSecretArn` 이 가리키는 Secrets Manager 값을 쓴다.
	 * 비어 있으면 애플리케이션이 뜨지 않는다 ([TelemetryTokenHasher] 가 막는다).
	 */
	val tokenHashSecret: String,

	val invitation: Invitation = Invitation(),

	val binaries: Binaries = Binaries(),
) {
	init {
		// 이 값은 셸·PowerShell 스크립트 안에 문자열로 박혀 나간다.
		// 운영자가 실수로 따옴표나 명령 치환 문자를 넣으면 그게 곧 사용자 PC 의 임의 코드 실행이다.
		require(SAFE_BASE_URL.matches(publicBaseUrl)) {
			"pulsemetry.public-base-url 이 올바른 http(s) 주소가 아니다. " +
				"따옴표·공백·셸 메타문자를 쓸 수 없다."
		}
	}

	/** 뒤에 슬래시가 붙어 있어도 경로를 이어 붙일 수 있게 정리한다. */
	fun baseUrl(): String = publicBaseUrl.trimEnd('/')

	data class Admin(
		/** 관리자 API 의 정적 키 (L8). 비어 있으면 애플리케이션이 뜨지 않는다. */
		val apiToken: String,
	)

	data class Invitation(
		val defaultTtlHours: Long = 72,
	)

	data class Binaries(
		/** CLI 바이너리가 놓인 서버 로컬 디렉터리 (L9). S3·GitHub Releases 로 바꾸지 않는다. */
		val dir: String = "./binaries",
	)

	private companion object {
		/** http(s) 스킴 + 호스트/포트/경로. 따옴표·공백·`$`·백틱은 애초에 허용하지 않는다. */
		val SAFE_BASE_URL = Regex("^https?://[A-Za-z0-9._~:/?#\\[\\]@!&+*,;=%-]+$")
	}
}
