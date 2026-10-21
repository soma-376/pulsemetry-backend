// telemetry 전용 JWT 의 발급·검증과 키 로더 (ADR 0065). Gateway 와 ingest·enrollment 가 같은 규칙을 쓴다.
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
// RDB·JPA·Kafka·Spring 에 의존하지 않는다. Gateway 가 DB 없이 이 모듈만으로 인증하기 때문이다.
plugins {
	`java-library`
	// 키쌍·PEM·토큰 위조 도구를 Gateway·ingest·enrollment 테스트가 함께 쓴다 (ADR 0008 — 별도 test 모듈을 만들지 않는다).
	`java-test-fixtures`
}

dependencies {
	// 서명·검증. 공개 시그니처에는 JDK 의 RSA 키 타입만 나타나므로 implementation 이다.
	implementation(libs.nimbus.jose.jwt)

	// 위조 토큰을 만드는 fixture 가 nimbus 타입을 시그니처에 드러낸다.
	testFixturesApi(libs.nimbus.jose.jwt)

	// AssertJ·JUnit 파라미터 테스트. 테스트 클래스패스에만 오른다.
	testImplementation(libs.spring.boot.starter.test)
}
