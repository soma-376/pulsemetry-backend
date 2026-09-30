// 벤더 좌석 커넥터 (ADR 0048). 포트(인터페이스·값 타입)와 벤더별 구현을 담는다 — 조립은 앱이 한다 (ADR 0011).
// 벤더 HTTP 가 이 모듈의 아웃바운드 기술이다. 영속성(JDBC)과 기술이 달라 모듈을 나눈다 (module-map §4).
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다. Spring 에 의존하지 않는다.
plugins {
	`java-library`
	// 모의 벤더 서버를 앱의 통합 테스트와 나눈다 (ADR 0008 — 별도 test 모듈을 만들지 않는다).
	`java-test-fixtures`
}

dependencies {
	// 벤더 응답(JSON)을 트리로 읽는다. 버전은 Boot BOM 이 관리한다(Jackson 3).
	implementation("tools.jackson.core:jackson-databind")

	testImplementation(libs.spring.boot.starter.test)
}

// 벤더 실계정 검증(읽기 전용, docs/vendor-connector-verification.md). 빌드·테스트에 들어가지 않는 별도 태스크다 —
// 자격증명 환경 변수가 없으면 건너뛰지 않고 종료 코드 2로 실패한다.
//   PULSEMETRY_VERIFY_CREDENTIAL=… PULSEMETRY_VERIFY_SETTING_ORGANIZATION=… ./gradlew :libs:vendor-connector:verifyVendorAccount -Pvendor=copilot
tasks.register<JavaExec>("verifyVendorAccount") {
	group = "vendor"
	description = "벤더 실계정으로 연결 확인과 좌석 목록을 읽는다(읽기 전용)"
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("com.team376.pulsemetry.connector.vendor.VendorAccountCheckKt")
	args(providers.gradleProperty("vendor").getOrElse(""))
}
