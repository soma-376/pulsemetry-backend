// 컨텍스트에 속하지 않는 횡단 인증 모듈 (ADR 0007 · 0008 규칙 2).
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
}

dependencies {
	// 읽기 전용 의존이다. telemetry_tokens 의 쓰기 소유는 enrollment-api 그대로다 (ADR 0008 규칙 1).
	// 기본은 implementation 이다 — 이 모듈의 계약에 영속성 타입이 나타나지 않는다 (규칙 3).
	implementation(project(":libs:enrollment-persistence"))

	// starter 가 아니라 개별 모듈이다. spring-boot-starter-security 를 쓰면 자동설정 모듈이 딸려 오고,
	// 자동설정은 클래스패스만 보고 발동하므로 이 라이브러리를 올린 앱의 엔드포인트가 전부 잠긴다.
	// 조립은 앱이 한다 (ADR 0011).
	implementation(libs.spring.security.core)
	implementation(libs.spring.security.web)

	// 서블릿 API 는 런타임에 컨테이너가 준다. 라이브러리가 끌고 가면 앱의 것과 겹친다.
	compileOnly(libs.jakarta.servlet.api)

	// 판정 테스트는 실제 PostgreSQL 위에서 돈다 — 거부 사유가 전부 스키마의 컬럼에서 나오기 때문이다.
	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.data.jpa)
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	// PostgresContainerConfig · EnrollmentFixtures 는 영속성 모듈이 testFixtures 로 노출한다 (ADR 0008 규칙 6).
	testImplementation(testFixtures(project(":libs:enrollment-persistence")))
	// 필터는 서블릿 타입을 직접 다루므로 테스트에서는 실물이 필요하다.
	testImplementation(libs.jakarta.servlet.api)
}
