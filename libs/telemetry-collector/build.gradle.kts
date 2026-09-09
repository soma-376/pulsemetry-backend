// 파이프라인의 수집 단계 모듈 (ADR 0010). OTLP 수신 · 마스킹 · 원본 아카이브를 담는다.
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
}

dependencies {
	// OTLP 메시지 타입. protobuf-java 의 Builder 가 곧 가변 모델이라 마스킹이 그 위에서 바로 된다.
	// api() 다 — ExportLogsServiceRequest 등이 이 모듈의 공개 시그니처에 나타난다 (ADR 0008 규칙 3).
	api(libs.opentelemetry.proto)
	api(libs.protobuf.java)

	// OTLP/JSON 코덱이 쓰는 스트리밍 파서. 계약에 나타나지 않으므로 implementation 이다.
	implementation(libs.jackson.core)

	// 아카이브 적재 대상 (ADR 0012). S3ArchiveWriter 만 쓰고 SDK 타입은 ArchiveWriter 계약에
	// 나타나지 않으므로 implementation 이다 — 파일 구현만 쓰는 앱이 SDK 를 컴파일 의존하지 않는다.
	implementation(platform(libs.awssdk.bom))
	implementation(libs.awssdk.s3)

	testImplementation(libs.spring.boot.starter.test)
}
