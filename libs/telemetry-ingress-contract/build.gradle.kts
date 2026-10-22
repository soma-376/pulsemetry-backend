// Gateway 와 ingest Consumer 가 주고받는 Kafka record 의 계약 — envelope .proto · codec · DTO · 접속 설정 · 로그 이벤트 형식 (ADR 0066·0067).
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다. Spring·JPA·Kafka 클라이언트에 의존하지 않는다 —
// Kafka 설정은 키 문자열만 만들고 클라이언트는 각 앱이 끈다.
plugins {
	`java-library`
	// envelope 의 Java 코드를 생성한다. 이 모듈에만 적용한다.
	alias(libs.plugins.protobuf)
}

dependencies {
	// RawOtlpPayload 가 OtlpSignal·OtlpMediaType 을 공개 시그니처에 드러낸다.
	api(project(":libs:telemetry-otlp-http"))
	// 생성 코드의 런타임. 생성 타입은 codec 안에서만 쓰고 공개 시그니처에는 DTO 만 나타나므로 implementation 이다.
	implementation(libs.protobuf.java)

	testImplementation(libs.spring.boot.starter.test)
}

protobuf {
	protoc {
		// 카탈로그의 protobuf 판과 같은 protoc. 생성 코드와 런타임 판이 갈리면 첫 decode 에서 죽는다.
		artifact = libs.protoc.get().toString()
	}
}
