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
	// 공개 저장 연산의 오류·좌석·계약 값이다. 순수 규칙의 단일 정의는 domain이 소유한다.
	api(project(":libs:enrollment-domain"))
	api(libs.jackson.module.kotlin)
	// 좌석 원장이 커넥터 설명(계정 종류·커넥터가 있는 플랜)과 벤더 목록 타입을 받는다 — 공개 시그니처에 나타나므로 api 다 (ADR 0048).
	api(project(":libs:vendor-connector"))
	// 공개 엔티티·리포지토리·JdbcClient 계약의 API만 노출한다. 자동설정과 드라이버는 앱이 선택한다 (ADR 0075).
	api("org.springframework.data:spring-data-jpa")
	api("org.springframework:spring-jdbc")
	api("jakarta.persistence:jakarta.persistence-api")
	// 엔티티의 JSON 열 매핑 annotation을 컴파일한다. ORM 실행 구현은 JPA를 선택한 앱이 제공한다.
	compileOnly("org.hibernate.orm:hibernate-core")

	// 앱 모듈이 같은 컨테이너 설정을 쓰도록 노출한다.
	// PostgreSQLContainer 는 @Bean 시그니처에 나타나므로 소비자의 컴파일 클래스패스에 있어야 한다 — api 다.
	testFixturesApi(libs.testcontainers.postgresql)
	testFixturesImplementation(libs.spring.boot.starter.test)
	testFixturesImplementation(libs.spring.boot.testcontainers)
	// 컨테이너 fixture를 쓰는 테스트에만 DB 자동설정·마이그레이션·드라이버를 제공한다.
	testFixturesRuntimeOnly(libs.spring.boot.starter.data.jpa)
	testFixturesRuntimeOnly(libs.spring.boot.starter.flyway)
	testFixturesRuntimeOnly(libs.flyway.database.postgresql)
	testFixturesRuntimeOnly(libs.postgresql)

	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.flyway.core)
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	testImplementation(libs.testcontainers.junit.jupiter)
}
