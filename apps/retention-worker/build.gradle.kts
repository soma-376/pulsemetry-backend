// 조직별 보존 삭제 작업의 실행 산출물 (ADR 0024 §5). 서버가 아니다 — tenant 하나를 처리하고 종료 코드로 끝난다.
// 쓰기 소유 모듈의 코드(경계·작업 기록·fence·삭제)를 조립만 한다. 라이브러리가 스테레오타입을 달지 않으므로(ADR 0011)
// 빈 등록을 여기서 손으로 한다.
plugins {
	alias(libs.plugins.spring.boot)
}

dependencies {
	// RDS telemetry_ops — 삭제 경계(쓰기)와 작업 기록(쓰기). DDL 은 enrollment-api 가 적용한다 (ADR 0021 §3).
	implementation(project(":libs:telemetry-ops-persistence"))
	// ClickHouse — fence(쓰기)·process list(읽기)·두 분석 테이블 DELETE. DDL 은 ingest 가 적용한다 (ADR 0015).
	implementation(project(":libs:telemetry-persistence"))

	// DataSource 하나. 웹·JPA·Flyway 자동설정은 올리지 않는다.
	implementation(libs.spring.boot.starter.jdbc)
	runtimeOnly(libs.postgresql)

	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.testcontainers.postgresql)
	// ClickHouse 는 GenericContainer 로 띄운다 — 전용 모듈은 JDBC 드라이버를 요구한다.
	testImplementation(libs.testcontainers)
	// 테스트가 DataSource 를 직접 만든다(PGSimpleDataSource).
	testImplementation(libs.postgresql)
}

// 실행 산출물은 bootJar 하나다. plain jar 를 만들면 Dockerfile 이 둘 중 하나를 골라내야 한다.
tasks.jar {
	enabled = false
}
