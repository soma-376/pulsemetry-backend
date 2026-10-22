// OTLP 접수 Gateway (ADR 0066). JWT 만 검증하고 Kafka 에 envelope 를 발행한 뒤 broker ack 를 받아 응답한다.
// **DB·JPA·S3·ClickHouse 에 의존하지 않는다** — 그래서 :libs:security(JPA)·:libs:telemetry-collector(S3 SDK)를 끌지 않는다.
plugins {
	alias(libs.plugins.spring.boot)
}

dependencies {
	implementation(project(":libs:telemetry-auth"))
	// envelope·codec·Kafka 접속 설정·로그 이벤트. OTLP/HTTP 표면(:libs:telemetry-otlp-http)을 api() 로 함께 끌어온다.
	implementation(project(":libs:telemetry-ingress-contract"))

	implementation(libs.spring.boot.starter.webmvc)
	implementation(libs.jackson.module.kotlin)
	// producer 만 쓴다. spring-kafka 의 KafkaTemplate 은 실패 로그(LoggingProducerListener)에 payload 일부를 남길 수 있어 쓰지 않는다.
	implementation(libs.kafka.clients)
	// MSK IAM SASL — 설정 문자열의 클래스 이름으로만 쓰므로 런타임 의존이다.
	runtimeOnly(libs.aws.msk.iam.auth)

	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(testFixtures(project(":libs:telemetry-auth")))
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.kafka)
	testImplementation(libs.testcontainers.junit.jupiter)
}

// 실행 산출물은 bootJar 하나다. plain jar 를 만들면 Dockerfile 이 둘 중 하나를 골라내야 한다.
tasks.jar {
	enabled = false
}

springBoot {
	mainClass.set("com.team376.pulsemetry.TelemetryGatewayApplicationKt")
}
