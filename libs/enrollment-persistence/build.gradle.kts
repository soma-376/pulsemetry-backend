// enrollment 스키마의 영속성 계층. 애플리케이션 모듈들이 공유한다. (ADR 0002)
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
	// 테스트 지원 코드를 소비 모듈에 노출한다 (ADR 0008 — 별도 test 모듈을 만들지 않는다).
	`java-test-fixtures`
	// @Entity·@Embeddable 에 no-arg 생성자를 생성한다.
	alias(libs.plugins.kotlin.jpa)
}

dependencies {
	// api: 소비자(앱 모듈)의 컴파일 클래스패스에도 노출한다.
	// 엔티티·리포지토리·DataSource·JdbcClient 는 앱 코드가 직접 다루는 타입이다.
	api(libs.spring.boot.starter.data.jpa)
	api(libs.spring.boot.starter.jdbc)

	// 스키마의 진실원인 Flyway 마이그레이션.
	implementation(libs.spring.boot.starter.flyway)
	runtimeOnly(libs.flyway.database.postgresql)
	runtimeOnly(libs.postgresql)

	// 앱 모듈이 같은 컨테이너 설정을 쓰도록 노출한다.
	// PostgreSQLContainer 는 @Bean 시그니처에 나타나므로 소비자의 컴파일 클래스패스에 있어야 한다 — api 다.
	testFixturesApi(libs.testcontainers.postgresql)
	testFixturesImplementation(libs.spring.boot.starter.test)
	testFixturesImplementation(libs.spring.boot.testcontainers)

	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	testImplementation(libs.testcontainers.junit.jupiter)
}
