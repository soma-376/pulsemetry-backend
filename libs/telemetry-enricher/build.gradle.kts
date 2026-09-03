// 파이프라인의 보강 단계 모듈 (ADR 0010). 사원 정보 결합을 담는다.
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
	// 정규화 이벤트 fixture 와 그 리더를 적재 모듈에 노출한다 (ADR 0008 규칙 6).
	`java-test-fixtures`
}

dependencies {
	// 단계 모듈 사이의 데이터 타입 간선 (ADR 0014). 금지된 것은 이웃의 seam 인터페이스를
	// 구현하는 것이지 공개된 데이터 타입을 참조하는 것이 아니다.
	// api() 다 — Normalized 가 Enricher.enrich 의 시그니처에, Envelope 가 Enriched 의 접근자에 나타난다.
	api(project(":libs:telemetry-adapter"))

	// as-of 조인은 읽기 전용이다. team_memberships 의 쓰기 소유는 관리자 API 그대로다 (ADR 0008 규칙 1).
	// 리포지토리 타입이 OrgProvider 의 생성자에만 나타나고 이 모듈의 계약에는 없으므로 implementation 이다.
	implementation(project(":libs:enrollment-persistence"))

	// golden fixture 를 읽는 리더가 쓰는 스트리밍 파서. 어댑터와 같은 이유로 databind 는 쓰지 않는다.
	testFixturesApi(project(":libs:telemetry-adapter"))
	testFixturesImplementation(libs.jackson.core)

	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.data.jpa)
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	// PostgresContainerConfig 는 영속성 모듈이 testFixtures 로 노출한다 (ADR 0008 규칙 6).
	testImplementation(testFixtures(project(":libs:enrollment-persistence")))
}
