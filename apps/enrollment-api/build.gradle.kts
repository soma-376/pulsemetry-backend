// 데스크탑 CLI 전용 인증(enrollment) 서버. 실행 가능한 배포 산출물이다.
plugins {
	alias(libs.plugins.spring.boot)
}

dependencies {
	implementation(project(":libs:enrollment-persistence"))
	implementation(libs.spring.boot.starter.webmvc)
	implementation(libs.jackson.module.kotlin)

	testImplementation(libs.spring.boot.starter.webmvc.test)
	// 앱 컨텍스트가 뜨려면 실제 PostgreSQL 이 필요하다 (Flyway 가 기동 시 마이그레이션한다).
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	testImplementation(libs.json.schema.validator)
}

// 계약 테스트는 telemetryctl 의 스키마 파일을 직접 읽는다 (복사본을 두면 드리프트가 생긴다).
// 로컬에서는 형제 디렉터리에 있고, CI 에서는 telemetryctl 을 따로 체크아웃하므로 env 로 덮어쓴다.
val contractsDir: String = providers.environmentVariable("PULSEMETRY_CONTRACTS_DIR")
	.getOrElse(rootProject.projectDir.parentFile.resolve("telemetryctl/contracts").absolutePath)

tasks.withType<Test>().configureEach {
	systemProperty("pulsemetry.contracts.dir", contractsDir)

	// 관리자 키가 비어 있으면 애플리케이션이 뜨지 않는다. 테스트 JVM 전체에 한 번만 주입해
	// 모든 테스트가 같은 컨텍스트 캐시를 쓰게 한다 (@SpringBootTest(properties=...) 는 캐시를 쪼갠다).
	systemProperty("pulsemetry.admin.api-token", "test-admin-token")
	systemProperty("pulsemetry.public-base-url", "https://get.pulsemetry.example.com")

	// 바이너리 서빙 테스트가 파일을 놓을 자리. 고정 경로를 미리 주고 테스트가 직접 채운다 —
	// @DynamicPropertySource 를 쓰면 컨텍스트 캐시가 쪼개져 컨테이너가 하나 더 뜬다.
	systemProperty(
		"pulsemetry.binaries.dir",
		layout.buildDirectory.dir("test-binaries").get().asFile.absolutePath,
	)
}
