package com.team376.pulsemetry.security.support

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * 인증 라이브러리에는 실행 가능한 애플리케이션이 없다. 통합 테스트용 최소 진입점이다.
 *
 * **엔티티와 리포지토리 패키지를 명시하는 것이 이 클래스의 요점이다.** 컴포넌트 스캔은
 * `@SpringBootApplication` 이 붙은 패키지(`…security.support`)부터 훑는데 영속성 모듈은 그 아래에 없다.
 * 앱 모듈이 메인 클래스를 루트(`com.team376.pulsemetry`)에 두어 이 선언 없이 동작하는 것과 대비된다
 * (ADR 0008 규칙 2).
 *
 * 이 클래스가 곧 조립의 예행이다 — 라이브러리는 스테레오타입을 달지 않으므로(ADR 0011)
 * 빈을 엮는 일은 언제나 앱이나 테스트가 한다.
 */
@SpringBootApplication
@EntityScan(basePackages = ["com.team376.pulsemetry.persistence.enrollment.entity"])
@EnableJpaRepositories(basePackages = ["com.team376.pulsemetry.persistence.enrollment.repository"])
class SecurityTestApplication
