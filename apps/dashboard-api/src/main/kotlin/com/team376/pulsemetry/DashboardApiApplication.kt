package com.team376.pulsemetry

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

/**
 * 분석 조회 API 를 띄운다 (ADR 0022).
 *
 * **루트 패키지에 있는 것이 의도다** (모듈 지도 3절). 원천을 읽는 라이브러리의 JPA 가
 * `com.team376.pulsemetry.persistence.enrollment` 아래에 있어, 이 클래스를 `...dashboard` 에 두면
 * 컴포넌트 스캔에 걸리지 않는다. 앱끼리는 의존하지 않으므로 세 메인 클래스가 한 클래스패스에 오르지 않는다.
 *
 * **인메모리 사용자 자동설정을 끈다.** 인증은 포트 하나가 하는데(ADR 0022 §3), 켜 두면 Boot 이 무작위 비밀번호의
 * 사용자를 만들어 로그에 찍는다 — 어느 체인도 쓰지 않는 자격이 하나 더 생긴다.
 */
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class DashboardApiApplication

fun main(args: Array<String>) {
	runApplication<DashboardApiApplication>(*args)
}
