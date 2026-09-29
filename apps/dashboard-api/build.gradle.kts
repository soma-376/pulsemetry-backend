// 분석 조회 API 의 배포 산출물 (ADR 0022). 원천 스키마를 소유한 라이브러리를 읽기 소비자로만 쓴다.
// 라이브러리가 스테레오타입을 달지 않으므로(ADR 0011) 빈 등록 · 필터 체인 · 설정 바인딩을 여기서 손으로 한다.
plugins {
	alias(libs.plugins.spring.boot)
}

dependencies {
	implementation(project(":libs:security"))
	// enrollment 스키마(조직·팀·구성원)를 읽는다. 쓰기 소유는 그대로다 — 이 앱의 RDS 계정은 SELECT 만 갖는다 (ADR 0022 §2·§4).
	// JPA·JDBC 를 api() 로 노출하므로 엔티티·리포지토리·JdbcClient 를 여기서 바로 쓴다.
	implementation(project(":libs:enrollment-persistence"))
	// 수집 운영 기록(tenant 생애 요약·백필 완료)을 읽는다 — 쓰기 소유는 그 모듈이고 DDL 은 enrollment-api 가 적용한다 (ADR 0021).
	implementation(project(":libs:telemetry-ops-persistence"))

	// dashboard_cache 스키마의 두 번째 Flyway 인스턴스(ADR 0023 §3). 자동설정 없이 API 만 쓴다 — 자동설정은 enrollment 용이고 꺼져 있다.
	implementation(libs.flyway.core)

	implementation(libs.spring.boot.starter.webmvc)
	// 필터 체인 배선. starter 는 조립하는 앱이 켠다 — :libs: 에는 붙이지 않는다 (ADR 0011 · 0016).
	implementation(libs.spring.boot.starter.security)
	implementation(libs.jackson.module.kotlin)

	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(testFixtures(project(":libs:enrollment-persistence")))
	// 분석 테이블(ClickHouse) 스키마를 테스트 저장소에 세운다. 운영에서는 ingest 가 적용한다.
	testImplementation(project(":libs:telemetry-persistence"))
	testImplementation(libs.testcontainers.postgresql)
	// ClickHouse 는 GenericContainer 로 띄운다 — 전용 모듈은 JDBC 드라이버를 요구한다.
	testImplementation(libs.testcontainers)
}

// 실행 산출물은 bootJar 하나다. plain jar 를 만들면 Dockerfile 이 둘 중 하나를 골라내야 한다.
tasks.jar {
	enabled = false
}

tasks.withType<Test>().configureEach {
	// 기본값이 없는 필수 설정(ADR 0022 §5). 테스트 JVM 전체에 한 번만 주입해 모든 테스트가 같은 컨텍스트 캐시를 쓰게 한다.
	systemProperty("pulsemetry.dashboard.retry-after", "2s")
}
