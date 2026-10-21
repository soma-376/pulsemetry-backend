// OTLP/HTTP 의 표면 — 응답 본문·gRPC 상태 코드·signal 경로·media type·Content-Encoding 허용 목록 (ADR 0067).
// 수집 모듈과 Gateway 가 같은 바이트를 내야 해서 둘이 공유한다. Gateway 가 S3 SDK 를 가진 수집 모듈에 의존하지 않게 따로 둔다.
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다. Spring·Kafka·JPA 에 의존하지 않는다.
plugins {
	`java-library`
}

dependencies {
	// google.rpc.Status 를 직접 쓰는 데 CodedOutputStream 만 쓴다. 공개 시그니처에 protobuf 타입이 없으므로 implementation 이다.
	implementation(libs.protobuf.java)

	testImplementation(libs.spring.boot.starter.test)
}
