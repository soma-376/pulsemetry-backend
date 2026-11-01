// 파이프라인의 보강 단계 모듈 (ADR 0010). 사원 정보 결합을 담는다.
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
}

dependencies {
	// 관측·보강 결과가 공개 시그니처에 나타난다. 정규화 실행을 끌어오지 않는다 (ADR 0075).
	api(project(":libs:telemetry-model"))

	// as-of 조인은 읽기 전용이다. team_memberships 의 쓰기 소유는 관리자 API 그대로다 (ADR 0008 규칙 1).
	// api() 다 — ObservationEnricher 의 public 생성자가 리포지토리를 받는다. 소비자가 그 타입 없이는
	// 조립할 수 없으므로 계약이다 (module-map 4절).
	api(project(":libs:enrollment-persistence"))

	testImplementation(libs.spring.boot.starter.test)
	// ID·해시가 보강 뒤에도 유지되는지 실제 정규화 결과로 검증하는 테스트 의존이다.
	testImplementation(project(":libs:telemetry-adapter"))
	testImplementation(libs.spring.boot.starter.data.jpa)
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	// PostgresContainerConfig 는 영속성 모듈이 testFixtures 로 노출한다 (ADR 0008 규칙 6).
	testImplementation(testFixtures(project(":libs:enrollment-persistence")))
}
