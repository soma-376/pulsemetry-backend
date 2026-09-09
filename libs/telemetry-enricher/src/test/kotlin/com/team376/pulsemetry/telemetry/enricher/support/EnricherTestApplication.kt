package com.team376.pulsemetry.telemetry.enricher.support

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * 보강 라이브러리에는 실행 가능한 애플리케이션이 없다. 통합 테스트용 최소 진입점이다.
 *
 * 엔티티·리포지토리 패키지를 명시하는 것이 요점이다 — 컴포넌트 스캔은 이 클래스의 패키지부터
 * 훑는데 영속성 모듈은 그 아래에 없다(ADR 0008 규칙 2). `:libs:security` 의
 * `SecurityTestApplication` 과 같은 모양이다.
 *
 * 이 클래스가 조립의 예행이다 — 라이브러리는 스테레오타입을 달지 않으므로(ADR 0011)
 * `OrgProvider` 와 `Enricher` 를 엮는 일은 언제나 앱이나 테스트가 한다.
 */
@SpringBootApplication
@EntityScan(basePackages = ["com.team376.pulsemetry.persistence.enrollment.entity"])
@EnableJpaRepositories(basePackages = ["com.team376.pulsemetry.persistence.enrollment.repository"])
class EnricherTestApplication
