package com.team376.pulsemetry.dashboard.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 이 앱의 설정 표면.
 *
 * 운영 수치와 저장소 계정에는 **기본값이 없다**(ADR 0022 §4·§5). 비어 있으면 기동이 실패한다 —
 * 조용히 뜬 기본값이 배포 환경의 값처럼 보이면 안 된다. 저장소 계정 네 묶음은 그 연결을 처음 쓰는 구현이 더한다.
 */
@ConfigurationProperties(prefix = "pulsemetry.dashboard")
data class DashboardApiProperties(

	/** 503 응답의 `Retry-After`. 초 단위로 싣는다. */
	val retryAfter: Duration,
) {
	init {
		require(retryAfter.toSeconds() >= 1) {
			"pulsemetry.dashboard.retry-after 는 1초 이상이어야 한다."
		}
	}
}
