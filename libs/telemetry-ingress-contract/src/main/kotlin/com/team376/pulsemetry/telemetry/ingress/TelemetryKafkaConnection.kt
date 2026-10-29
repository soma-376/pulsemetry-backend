package com.team376.pulsemetry.telemetry.ingress

/**
 * Kafka 접속 방식. 환경변수 `PULSEMETRY_KAFKA_SECURITY` 의 값이다(`plaintext`·`iam`·`mtls`).
 *
 * - [PLAINTEXT] — 로컬 Compose·테스트. 인증·암호화 없음.
 * - [MTLS] — Strimzi. TLS + workload별 사용자 인증서, PKCS12 파일(ADR 0070).
 * - [IAM] — MSK. TLS + SASL `AWS_MSK_IAM`, 포트 9098. 자격은 Pod Identity → workload 전용 MSK 역할 assume 이다.
 */
public enum class TelemetryKafkaSecurity { PLAINTEXT, IAM, MTLS }

/**
 * Gateway(producer)와 ingest(consumer)가 같이 쓰는 Kafka 접속 설정(ADR 0066 Decision 9).
 *
 * 두 앱이 같은 접속 연산을 써야 해서 여기 한 벌을 둔다 — 정의가 갈리면 한쪽만 MSK 에 붙는다. kafka-clients 에 의존하지 않고
 * **설정 키 문자열만 만든다.** 클라이언트 생성은 각 앱이 한다.
 *
 * ## MSK IAM 은 전용 역할을 고정 세션 이름으로 assume 한다
 *
 * Pod Identity 자격이 갱신될 때마다 세션 이름이 바뀌면 MSK 가 기존 연결의 재인증을 거부한다. 그래서 workload 마다 전용 MSK 역할을
 * `awsRoleArn` + 고정 `awsRoleSessionName` + `awsStsRegion` 으로 assume 한다. Pod 역할에는 그 역할의 `sts:AssumeRole` 만 있다(infra).
 * 장기 access key 를 쓰지 않는다.
 *
 * 역할 ARN·세션 이름은 JAAS 문자열 안에 따옴표로 들어가므로 형식을 엄격히 본다 — 따옴표·세미콜론이 섞이면 JAAS 가 깨지거나
 * 옵션이 주입된다. 위반은 기동 실패다.
 */
public class TelemetryKafkaConnection(
	public val bootstrapServers: String,
	public val security: TelemetryKafkaSecurity,
	public val iamRoleArn: String = "",
	public val iamSessionName: String = "",
	public val iamStsRegion: String = DEFAULT_STS_REGION,
	public val tls: TelemetryKafkaTls = TelemetryKafkaTls(),
) {
	init {
		require(bootstrapServers.isNotBlank()) { "Kafka bootstrap servers(PULSEMETRY_KAFKA_BOOTSTRAP_SERVERS)가 비어 있다" }
		require(BOOTSTRAP.matches(bootstrapServers)) { "Kafka bootstrap servers 는 host:port 를 쉼표로 이은 값이어야 한다" }
		if (security == TelemetryKafkaSecurity.IAM) {
			require(ROLE_ARN.matches(iamRoleArn)) {
				"PULSEMETRY_KAFKA_IAM_ROLE_ARN 이 IAM 역할 ARN 형식이 아니다(security=iam 에는 workload 전용 MSK 역할이 필요하다)"
			}
			require(SESSION_NAME.matches(iamSessionName)) {
				"PULSEMETRY_KAFKA_IAM_SESSION_NAME 은 2~64자의 [A-Za-z0-9+=,.@_-] 여야 한다(고정 세션 이름)"
			}
			require(REGION.matches(iamStsRegion)) { "MSK IAM STS 리전 형식이 아니다: $iamStsRegion" }
		}
	}

	/** 공통 접속 설정. [clientId] 는 브로커 로그·쿼터의 식별자다. 비밀번호가 담긴 반환 map은 로그로 출력하지 않는다. */
	public fun clientProperties(clientId: String): Map<String, Any> = buildMap {
		put("bootstrap.servers", bootstrapServers)
		put("client.id", clientId)
		when (security) {
			TelemetryKafkaSecurity.PLAINTEXT -> put("security.protocol", "PLAINTEXT")
			TelemetryKafkaSecurity.MTLS -> putAll(tls.clientProperties())
			TelemetryKafkaSecurity.IAM -> {
				put("security.protocol", "SASL_SSL")
				put("sasl.mechanism", IAM_MECHANISM)
				put("sasl.jaas.config", jaasConfig())
				put("sasl.client.callback.handler.class", IAM_CALLBACK_HANDLER)
			}
		}
	}

	/** 로그용 요약. 역할 ARN 은 비밀이 아니지만 계정 ID 를 담으므로 세션 이름까지만 남긴다. */
	override fun toString(): String = "TelemetryKafkaConnection(security=$security, bootstrap=$bootstrapServers, session=${iamSessionName.ifBlank { "-" }})"

	private fun jaasConfig(): String =
		"$IAM_LOGIN_MODULE required awsRoleArn=\"$iamRoleArn\" awsRoleSessionName=\"$iamSessionName\" awsStsRegion=\"$iamStsRegion\";"

	public companion object {
		public const val DEFAULT_STS_REGION: String = "ap-northeast-2"
		public const val IAM_MECHANISM: String = "AWS_MSK_IAM"
		public const val IAM_LOGIN_MODULE: String = "software.amazon.msk.auth.iam.IAMLoginModule"
		public const val IAM_CALLBACK_HANDLER: String = "software.amazon.msk.auth.iam.IAMClientCallbackHandler"

		private val BOOTSTRAP = Regex("""[A-Za-z0-9.\-]+:\d{1,5}(,[A-Za-z0-9.\-]+:\d{1,5})*""")
		private val ROLE_ARN = Regex("""arn:aws[a-z-]*:iam::\d{12}:role/[A-Za-z0-9+=,.@_/-]{1,512}""")
		private val SESSION_NAME = Regex("""[A-Za-z0-9+=,.@_-]{2,64}""")
		private val REGION = Regex("""[a-z]{2}(-[a-z]+)+-\d""")
	}
}
