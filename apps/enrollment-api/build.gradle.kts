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

tasks.withType<Test>().configureEach {
	// 계약 테스트는 telemetryctl 의 스키마 파일을 직접 읽는다 (복사본을 두면 드리프트가 생긴다).
	// rootProject 는 pulsemetry-backend 이므로 그 부모가 soma-376 이다.
	systemProperty(
		"pulsemetry.contracts.dir",
		rootProject.projectDir.parentFile.resolve("telemetryctl/contracts").absolutePath,
	)
}
