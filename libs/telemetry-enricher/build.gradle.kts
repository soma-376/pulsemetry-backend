// 파이프라인의 보강 단계 모듈 (ADR 0010). 사원 정보 결합을 담는다.
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
}

dependencies {
	// 단계 모듈 사이의 데이터 타입 간선 (ADR 0014). 금지된 것은 이웃의 seam 인터페이스를
	// 구현하는 것이지 공개된 데이터 타입을 참조하는 것이 아니다.
	// api() 다 — 관측 타입(ObservationBatch·EventObservation·OrgAttribution)이 ObservationEnricher 와
	// EnrichedBatch 의 시그니처에 나타난다.
	api(project(":libs:telemetry-adapter"))

	// as-of 조인은 읽기 전용이다. team_memberships 의 쓰기 소유는 관리자 API 그대로다 (ADR 0008 규칙 1).
	// api() 다 — ObservationEnricher 의 public 생성자가 리포지토리를 받는다. 소비자가 그 타입 없이는
	// 조립할 수 없으므로 계약이다 (module-map 4절).
	api(project(":libs:enrollment-persistence"))

	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.data.jpa)
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	// PostgresContainerConfig 는 영속성 모듈이 testFixtures 로 노출한다 (ADR 0008 규칙 6).
	testImplementation(testFixtures(project(":libs:enrollment-persistence")))
}
