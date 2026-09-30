// 데스크탑 CLI 전용 인증(enrollment) 서버. 실행 가능한 배포 산출물이다.
plugins {
	alias(libs.plugins.spring.boot)
}

dependencies {
	implementation(project(":libs:enrollment-persistence"))
	// telemetry token 해시. 발급(이 앱)과 검증(:libs:security)이 같은 연산을 써야 하므로
	// 정의를 한 벌로 둔다 (ADR 0008 규칙 3·5). 이 의존은 필터 체인을 켜지 않는다 — ADR 0011.
	implementation(project(":libs:security"))
	// telemetry_ops 스키마의 마이그레이션. 이 앱이 RDS 마이그레이션을 실행하는 유일한 프로세스라 그 몫을
	// 함께 진다 (ADR 0021). 요약 writer 는 ingest 가 쓰고, 이 앱은 DDL 만 적용한다.
	implementation(project(":libs:telemetry-ops-persistence"))
	implementation(libs.spring.boot.starter.webmvc)
	implementation(libs.spring.boot.starter.security)
	// outbox 의 메일을 SMTP 로 보낸다 (ADR 0037). JavaMailSender 는 설정(pulsemetry.mail.*)으로 직접 만든다.
	implementation(libs.spring.boot.starter.mail)
	// 일시 실패와 영구 실패를 SMTP 응답 코드로 가른다. 그 코드는 구현 쪽 예외 타입에 있다.
	implementation(libs.angus.mail)
	implementation(libs.jackson.module.kotlin)
	// manifest 재동기화가 저장된 정책을 telemetryctl 원본 스키마로 검증한다 (ADR 0019).
	// 계약 테스트도 같은 검증기를 쓴다.
	implementation(libs.json.schema.validator)

	testImplementation(libs.spring.boot.starter.webmvc.test)
	// PostgresContainerConfig 는 영속성 모듈이 testFixtures 로 노출한다.
	testImplementation(testFixtures(project(":libs:enrollment-persistence")))
	// 좌석 동기화 통합 테스트가 벤더 API 모의 서버(JDK 내장 HTTP 서버)를 쓴다.
	testImplementation(testFixtures(project(":libs:vendor-connector")))
	// 앱 컨텍스트가 뜨려면 실제 PostgreSQL 이 필요하다 (Flyway 가 기동 시 마이그레이션한다).
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.postgresql)
	// 메일 수신 컨테이너로 실제 SMTP 발송을 검증한다.
	testImplementation(libs.testcontainers)
}

// 실행 산출물은 bootJar 하나다. plain jar 를 만들면 Dockerfile 이 둘 중 하나를 골라내야 한다.
tasks.jar {
	enabled = false
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
	// telemetry token 해시 키도 admin 키와 같은 이유로 비어 있으면 기동이 실패한다.
	systemProperty("pulsemetry.token-hash-secret", "test-token-hash-secret")
	systemProperty("pulsemetry.public-base-url", "https://get.pulsemetry.example.com")

	// 바이너리 서빙 테스트가 파일을 놓을 자리. 고정 경로를 미리 주고 테스트가 직접 채운다 —
	// @DynamicPropertySource 를 쓰면 컨텍스트 캐시가 쪼개져 컨테이너가 하나 더 뜬다.
	systemProperty(
		"pulsemetry.binaries.dir",
		layout.buildDirectory.dir("test-binaries").get().asFile.absolutePath,
	)
}

// 재동기화는 저장된 정책을 원본 JSON Schema로 검사한다. 소스 복사본 없이 배포 jar에 계약을 포함한다.
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
	doFirst {
		check(file("$contractsDir/enrollment-manifest.schema.json").isFile) {
			"PULSEMETRY_CONTRACTS_DIR에 원본 manifest 스키마가 필요하다"
		}
	}
	from(contractsDir) {
		include("enrollment-manifest.schema.json")
		into("contracts")
	}
}
