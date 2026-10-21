package com.team376.pulsemetry.telemetry.ingress

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 두 앱이 같은 Kafka 접속 설정을 만드는지, MSK IAM 이 전용 역할을 고정 세션 이름으로 assume 하는지 본다(ADR 0066 Decision 9). */
class TelemetryKafkaConnectionTest {

	private val roleArn = "arn:aws:iam::123456789012:role/pulsemetry-dev-telemetry-gateway-msk"

	@Test
	@DisplayName("plaintext 는 bootstrap·client.id·PLAINTEXT 뿐이다")
	fun plaintext() {
		val props = TelemetryKafkaConnection("localhost:9092", TelemetryKafkaSecurity.PLAINTEXT).clientProperties("c-1")

		assertThat(props).containsExactlyInAnyOrderEntriesOf(
			mapOf("bootstrap.servers" to "localhost:9092", "client.id" to "c-1", "security.protocol" to "PLAINTEXT"),
		)
	}

	@Test
	@DisplayName("iam 은 SASL_SSL·AWS_MSK_IAM 이고 JAAS 가 역할 ARN·고정 세션 이름·STS 리전을 담는다")
	fun iam() {
		val props = TelemetryKafkaConnection(
			"b-1.msk.example:9098,b-2.msk.example:9098",
			TelemetryKafkaSecurity.IAM,
			iamRoleArn = roleArn,
			iamSessionName = "telemetry-gateway",
		).clientProperties("gw")

		assertThat(props["security.protocol"]).isEqualTo("SASL_SSL")
		assertThat(props["sasl.mechanism"]).isEqualTo("AWS_MSK_IAM")
		assertThat(props["sasl.client.callback.handler.class"]).isEqualTo("software.amazon.msk.auth.iam.IAMClientCallbackHandler")
		assertThat(props["sasl.jaas.config"]).isEqualTo(
			"software.amazon.msk.auth.iam.IAMLoginModule required " +
				"awsRoleArn=\"$roleArn\" awsRoleSessionName=\"telemetry-gateway\" awsStsRegion=\"ap-northeast-2\";",
		)
	}

	@Test
	@DisplayName("iam 은 역할 ARN·세션 이름이 형식에 맞아야 하고 JAAS 를 깨는 문자를 받지 않는다")
	fun iamValidation() {
		fun iam(arn: String, session: String) = TelemetryKafkaConnection("b:9098", TelemetryKafkaSecurity.IAM, arn, session)

		assertThatThrownBy { iam("", "telemetry-gateway") }.hasMessageContaining("PULSEMETRY_KAFKA_IAM_ROLE_ARN")
		assertThatThrownBy { iam("arn:aws:iam::123:role/x", "telemetry-gateway") }.hasMessageContaining("ROLE_ARN")
		assertThatThrownBy { iam("$roleArn\" awsDebugCreds=\"true", "telemetry-gateway") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { iam(roleArn, "") }.hasMessageContaining("PULSEMETRY_KAFKA_IAM_SESSION_NAME")
		assertThatThrownBy { iam(roleArn, "a;b") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { iam(roleArn, "x".repeat(65)) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("bootstrap 이 비거나 host:port 가 아니면 기동하지 않는다")
	fun bootstrapValidation() {
		assertThatThrownBy { TelemetryKafkaConnection(" ", TelemetryKafkaSecurity.PLAINTEXT) }.hasMessageContaining("PULSEMETRY_KAFKA_BOOTSTRAP_SERVERS")
		assertThatThrownBy { TelemetryKafkaConnection("localhost", TelemetryKafkaSecurity.PLAINTEXT) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { TelemetryKafkaConnection("a:1, b:2", TelemetryKafkaSecurity.PLAINTEXT) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("plaintext 는 IAM 값이 비어 있어도 된다")
	fun plaintextIgnoresIam() {
		assertThat(TelemetryKafkaConnection("kafka:9092", TelemetryKafkaSecurity.PLAINTEXT, "", "").toString()).contains("PLAINTEXT")
	}
}
