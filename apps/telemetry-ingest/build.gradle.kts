// 텔레메트리 파이프라인 전 계층을 한 프로세스로 띄우는 배포 산출물 (허브 ADR 0005).
// 이 모듈에는 도메인 로직이 없다 — 라이브러리가 스테레오타입을 달지 않으므로(ADR 0011)
// 빈 등록 · 필터 체인 · 설정 바인딩을 여기서 손으로 한다 (ADR 0016).
plugins {
	alias(libs.plugins.spring.boot)
}

dependencies {
	// 적재 모듈이 api() 로 보강·변환 모듈을 함께 끌어온다 (ADR 0014).
	implementation(project(":libs:telemetry-persistence"))
	implementation(project(":libs:telemetry-collector"))
	// 두 리포지토리(TelemetryTokenRepository · TeamMembershipRepository)는 :libs:security 와
	// :libs:telemetry-enricher 가 api() 로 노출한다 — 생성자 인자 타입은 계약이다 (module-map 4절).
	implementation(project(":libs:security"))

	implementation(libs.spring.boot.starter.webmvc)
	// 필터 체인 배선. starter 를 쓰는 것은 :libs: 가 아니라 앱이므로 ADR 0011 위반이 아니다 —
	// 조립 앱이 자동설정을 켜는 자리이고, 명시적 SecurityFilterChain 이 기본 체인을 물린다.
	implementation(libs.spring.boot.starter.security)
	implementation(libs.jackson.module.kotlin)

	// S3ArchiveWriter 의 빈 등록이 이 앱에 있으므로 SDK 타입이 컴파일 의존이다 (ADR 0012).
	implementation(platform(libs.awssdk.bom))
	implementation(libs.awssdk.s3)

	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(testFixtures(project(":libs:enrollment-persistence")))
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	// ClickHouse 는 GenericContainer 로 띄운다 — 전용 모듈은 JDBC 드라이버를 요구한다.
	testImplementation(libs.testcontainers)
}

// 실행 산출물은 bootJar 하나다. plain jar 를 만들면 Dockerfile 이 둘 중 하나를 골라내야 한다.
tasks.jar {
	enabled = false
}

tasks.withType<Test>().configureEach {
	// 비어 있으면 TelemetryTokenHasher 생성자가 막아 애플리케이션이 뜨지 않는다.
	// 테스트 JVM 전체에 한 번만 주입해 모든 테스트가 같은 컨텍스트 캐시를 쓰게 한다
	// (@SpringBootTest(properties=…) 는 캐시를 쪼개 컨테이너가 하나 더 뜬다).
	systemProperty("pulsemetry.token-hash-secret", "test-token-hash-secret")
	systemProperty(
		"pulsemetry.telemetry.archive.dir",
		layout.buildDirectory.dir("test-archive").get().asFile.absolutePath,
	)
}
