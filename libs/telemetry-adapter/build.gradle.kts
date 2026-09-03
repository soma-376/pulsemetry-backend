// 파이프라인의 변환 단계 모듈 (ADR 0010). OTLP 읽기 · 정규화 모델 · 벤더별 매핑을 담는다.
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.
plugins {
	`java-library`
}

dependencies {
	// OTLP 메시지 타입. 수집 단계가 넘겨주는 요청이 곧 이 모듈의 입력이다.
	// api() 다 — ExportLogsServiceRequest 등이 Normalizer 의 공개 시그니처에 나타난다 (ADR 0008 규칙 3).
	api(libs.opentelemetry.proto)
	api(libs.protobuf.java)

	// canonical JSON 인코더가 쓰는 스트리밍 생성기. 계약에 나타나지 않으므로 implementation 이다.
	// databind 는 쓰지 않는다 — 수집 모듈과 같은 이유로 디스크립터·모델을 직접 순회한다.
	implementation(libs.jackson.core)

	// 단계 모듈끼리는 project() 간선을 두지 않는다. 수집 모듈의 SignalConsumer 로 잇는 배선은
	// 조립 앱이 한다 (ADR 0011). 그래서 :libs:telemetry-collector 의존이 여기 없다.

	testImplementation(libs.spring.boot.starter.test)
}
