// RDS telemetry_ops 스키마의 영속성 모듈 (ADR 0021). 수집 운영 기록(tenant 생애 요약·백필 기록·삭제 경계)의
// DDL 을 소유한다 — DDL 파일이 이 모듈 아래 있는 것이 쓰기 소유의 근거다 (ADR 0008 규칙 1 의 판정법).
// ClickHouse 쪽 운영 기록(수신 ledger)은 :libs:telemetry-persistence 가 소유한다 — 아웃바운드 기술이 다르다.
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
}

dependencies {
	// 마이그레이션을 Flyway API 로 직접 실행한다. starter 를 끌지 않는다 — 자동설정은 Flyway 빈이 하나라고
	// 가정하고, 이 스키마는 enrollment 와 다른 인스턴스·이력으로 돈다 (ADR 0011 · ADR 0021).
	implementation(libs.flyway.core)
	runtimeOnly(libs.flyway.database.postgresql)
	runtimeOnly(libs.postgresql)

	// 마이그레이션은 실제 PostgreSQL 위에서만 검증한다 (ADR 0004).
	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.testcontainers.postgresql)
	testImplementation(libs.testcontainers.junit.jupiter)
	// 테스트가 DataSource 를 직접 만든다(PGSimpleDataSource). main 은 드라이버를 런타임에만 쓴다.
	testImplementation(libs.postgresql)
	// V3 트리거가 연결될 조직 테이블도 원래 소유 모듈의 실제 마이그레이션으로 준비한다.
	testRuntimeOnly(project(":libs:enrollment-persistence"))
}
