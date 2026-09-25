// 분석 조회 API 의 배포 산출물 (ADR 0022). 원천 스키마를 소유한 라이브러리를 읽기 소비자로만 쓴다.
// 라이브러리가 스테레오타입을 달지 않으므로(ADR 0011) 빈 등록 · 필터 체인 · 설정 바인딩을 여기서 손으로 한다.
plugins {
	alias(libs.plugins.spring.boot)
}

dependencies {
	implementation(libs.spring.boot.starter.webmvc)
	// 필터 체인 배선. starter 는 조립하는 앱이 켠다 — :libs: 에는 붙이지 않는다 (ADR 0011 · 0016).
	implementation(libs.spring.boot.starter.security)
	implementation(libs.jackson.module.kotlin)

	testImplementation(libs.spring.boot.starter.webmvc.test)
}

// 실행 산출물은 bootJar 하나다. plain jar 를 만들면 Dockerfile 이 둘 중 하나를 골라내야 한다.
tasks.jar {
	enabled = false
}

tasks.withType<Test>().configureEach {
	// 기본값이 없는 필수 설정(ADR 0022 §5). 테스트 JVM 전체에 한 번만 주입해 모든 테스트가 같은 컨텍스트 캐시를 쓰게 한다.
	systemProperty("pulsemetry.dashboard.retry-after", "2s")
}
