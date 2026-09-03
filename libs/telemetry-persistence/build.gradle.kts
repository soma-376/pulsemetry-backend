// telemetry 도메인의 적재 모듈 (ADR 0010). enriched_events 의 DDL 과 쓰기를 소유한다 —
// DDL 파일이 이 모듈 아래 있는 것이 쓰기 소유의 근거다 (ADR 0008 규칙 1 의 판정법).
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
}

dependencies {
	// 적재 대상 타입. insert(items: List<Enriched>) 가 이 모듈의 공개 시그니처다 (ADR 0014).
	// 어댑터의 model/ 과 NormalizedJson 은 이 간선을 타고 전이로 따라온다 — raw_json 이 그 값이다.
	api(project(":libs:telemetry-enricher"))

	// ClickHouse 접속에 드라이버를 쓰지 않는다. JDK 의 HttpClient 로 HTTP 인터페이스를 직접 부른다 —
	// 이식 원본이 "무거운 클라이언트 도입 금지" 로 stdlib 만 쓴 것과 같은 선택이고, 드라이버를 넣으면
	// 자체 오류 매핑이 "4xx 까지 전부 일시 장애" 라는 고정 동작을 덮는다. 신규 런타임 의존은 없다.

	testImplementation(libs.spring.boot.starter.test)
	// 적재 테스트는 실제 ClickHouse 로만 돈다 — ReplacingMergeTree 의 FINAL dedup 이 판정 대상이다.
	// GenericContainer 로 띄운다. 전용 모듈은 JDBC 드라이버를 요구하는데 이 모듈은 HTTP 로만 말한다.
	testImplementation(libs.testcontainers)
	testImplementation(libs.testcontainers.junit.jupiter)
	// golden 이벤트와 그 리더는 보강 모듈이 testFixtures 로 노출한다 (ADR 0008 규칙 6).
	testImplementation(testFixtures(project(":libs:telemetry-enricher")))
}
