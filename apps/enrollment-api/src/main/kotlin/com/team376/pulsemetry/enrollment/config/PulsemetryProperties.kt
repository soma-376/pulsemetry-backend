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

	val invitation: Invitation = Invitation(),
) {

	/** 뒤에 슬래시가 붙어 있어도 경로를 이어 붙일 수 있게 정리한다. */
	fun baseUrl(): String = publicBaseUrl.trimEnd('/')

	data class Admin(
		/** 관리자 API 의 정적 키 (L8). 비어 있으면 애플리케이션이 뜨지 않는다. */
		val apiToken: String,
	)

	data class Invitation(
		val defaultTtlHours: Long = 72,
	)
}
