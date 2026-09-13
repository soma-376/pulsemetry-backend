package com.team376.pulsemetry

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * 관리자 대시보드 API — 웹 로그인 · 조회 · 시나리오 실행을 한 프로세스로 띄운다 (ADR 0019).
 *
 * **루트 패키지에 있는 것이 의도다** (ADR 0008 규칙 2, 모듈 지도 3절). 대시보드는 `JdbcClient` 만 쓰지만
 * `:libs:enrollment-persistence` 가 `api()` 로 끌어오는 JPA 가 `com.team376.pulsemetry.persistence.enrollment`
 * 아래에 있어, 스캔 범위에 들어온 엔티티 · 리포지토리는 만들어질 뿐 쓰이지 않는다. 그 매핑이 스키마와
 * 어긋나면 첫 조회가 아니라 기동에서 드러나도록 `application.yaml` 이 `hibernate.default_schema` 와
 * `ddl-auto: validate` 를 둔다 — `:apps:enrollment-api` · `:apps:telemetry-ingest` 와 같은 짝이다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class DashboardApplication

fun main(args: Array<String>) {
	runApplication<DashboardApplication>(*args)
}
