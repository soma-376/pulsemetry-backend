package com.team376.pulsemetry.enrollment.contract

import com.team376.pulsemetry.enrollment.support.ContractFixtures
import com.team376.pulsemetry.enrollment.support.ContractSchemas
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * 계약 테스트.
 *
 * **애플리케이션이 실제로 쓰는 ObjectMapper 를 주입받는다.** 테스트 전용 mapper 를 새로 만들면
 * `FAIL_ON_UNKNOWN_PROPERTIES` 같은 설정이 프로덕션과 갈라져도 초록불이 뜬다.
 *
 * 검증 오라클은 `telemetryctl/contracts` 의 스키마 파일 원본이다 (PLAN.md R3).
 */
@SpringBootTest
@Import(PostgresContainerConfig::class)
class EnrollmentContractTest {

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	// ── enroll 응답 봉투 ─────────────────────────────────────────────────────

	@Test
	@DisplayName("enroll 응답이 봉투 스키마를 만족한다")
	fun enrollmentResponseMatchesSchema() {
		val json = objectMapper.writeValueAsString(sampleEnrollment())

		val errors = ContractSchemas.validate(ContractSchemas.enrollmentSchema(), json)

		assertThat(errors)
			.describedAs("스키마 위반:\n%s\njson=%s", ContractSchemas.describe(errors), json)
			.isEmpty()
	}

	@Test
	@DisplayName("enroll 응답의 최상위 키가 정확히 4개다 — 하나만 더해도 클라이언트가 거부한다")
	fun enrollmentResponseHasExactlyFourTopLevelKeys() {
		val tree = objectMapper.readTree(objectMapper.writeValueAsString(sampleEnrollment()))

		assertThat(tree.propertyNames()).containsExactlyInAnyOrder(
			"installation_id",
			"installation_token",
			"telemetry_token",
			"manifest",
		)
	}

	@Test
	@DisplayName("manifest 안에 installation_id·토큰이 없다 — 봉투 분리 (A5)")
	fun manifestCarriesNoEnvelopeFields() {
		val tree = objectMapper.readTree(objectMapper.writeValueAsString(sampleEnrollment()))
		val manifestKeys = tree.get("manifest").propertyNames()

		assertThat(manifestKeys).doesNotContain(
			"installation_id",
			"installation_token",
			"telemetry_token",
		)
	}

	@Test
	@DisplayName("봉투 스키마는 여분 필드를 거부한다 — 테스트 오라클이 실제로 동작하는지 확인")
	fun schemaRejectsExtraEnvelopeField() {
		val json = objectMapper.writeValueAsString(sampleEnrollment())
			.replaceFirst("{", """{"member_email":"user@example.com",""")

		val errors = ContractSchemas.validate(ContractSchemas.enrollmentSchema(), json)

		assertThat(errors).isNotEmpty()
	}

	// ── manifest ─────────────────────────────────────────────────────────────

	@Test
	@DisplayName("manifest 직렬화가 manifest 스키마를 만족한다")
	fun manifestMatchesSchema() {
		val json = objectMapper.writeValueAsString(ContractFixtures.manifest())

		val errors = ContractSchemas.validate(ContractSchemas.manifestSchema(), json)

		assertThat(errors).describedAs(ContractSchemas.describe(errors)).isEmpty()
	}

	@Test
	@DisplayName("선택 필드를 생략한 manifest 도 스키마를 만족한다 — null 을 흘리지 않는다")
	fun minimalManifestMatchesSchema() {
		val json = objectMapper.writeValueAsString(ContractFixtures.minimalManifest())

		assertThat(json).doesNotContain("null")
		assertThat(ContractSchemas.validate(ContractSchemas.manifestSchema(), json)).isEmpty()
	}

	@Test
	@DisplayName("저장된 manifest 를 읽어 config_revision 만 갈아 끼운다")
	fun manifestConfigRevisionIsOverridden() {
		val stored = objectMapper.writeValueAsString(ContractFixtures.manifest(configRevision = 1))

		val republished = objectMapper.readValue(stored, ManifestPayload::class.java)
			.withConfigRevision(7)
		val json = objectMapper.writeValueAsString(republished)

		assertThat(objectMapper.readTree(json).get("config_revision").asInt()).isEqualTo(7)
		assertThat(ContractSchemas.validate(ContractSchemas.manifestSchema(), json)).isEmpty()
		// 나머지 설정은 그대로여야 한다
		assertThat(republished.otlp).isEqualTo(ContractFixtures.manifest().otlp)
	}

	@Test
	@DisplayName("계약을 어긴 manifest 는 스키마가 잡는다 — http endpoint")
	fun schemaRejectsPlainHttpEndpoint() {
		val broken = ContractFixtures.manifest()
			.copy(otlp = OtlpSettings(endpoint = "http://collector.example.com", protocol = "grpc"))

		val errors = ContractSchemas.validate(
			ContractSchemas.manifestSchema(),
			objectMapper.writeValueAsString(broken),
		)

		assertThat(errors).isNotEmpty()
	}

	@Test
	@DisplayName("계약을 어긴 manifest 는 스키마가 잡는다 — 지원하지 않는 protocol")
	fun schemaRejectsUnknownProtocol() {
		val broken = ContractFixtures.manifest()
			.copy(otlp = OtlpSettings(endpoint = "https://collector.example.com", protocol = "thrift"))

		val errors = ContractSchemas.validate(
			ContractSchemas.manifestSchema(),
			objectMapper.writeValueAsString(broken),
		)

		assertThat(errors).isNotEmpty()
	}

	// ── telemetry token 응답 ─────────────────────────────────────────────────

	@Test
	@DisplayName("telemetry token 응답이 스키마를 만족하고 최상위 키가 정확히 2개다")
	fun telemetryTokenResponseMatchesSchema() {
		val response = TelemetryTokenResponse(
			installationId = "0f5f2b3c-2d4e-4a1b-9c8d-6e7f80912345",
			telemetryToken = "ptt_ZXhhbXBsZS10ZWxlbWV0cnktdG9rZW4tdmFsdWUtMzI",
		)
		val json = objectMapper.writeValueAsString(response)

		assertThat(ContractSchemas.validate(ContractSchemas.telemetryTokenResponseSchema(), json)).isEmpty()
		assertThat(objectMapper.readTree(json).propertyNames())
			.containsExactlyInAnyOrder("installation_id", "telemetry_token")
	}

	// ── enroll 요청 ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("Go 클라이언트가 보내는 요청을 그대로 파싱한다")
	fun parsesRequestFromGoClient() {
		// Invite 에 omitempty 가 없어서 항상 실려 온다 (PLAN.md §9 5번).
		val raw = """
			{"code":"ABCD-EFGH-JKMN","platform":"darwin","architecture":"arm64",
			 "hostname":"my-macbook","client_version":"0.1.0","invite":""}
		""".trimIndent()

		val request = objectMapper.readValue(raw, EnrollRequest::class.java)

		assertThat(request.effectiveCode()).isEqualTo("ABCD-EFGH-JKMN")
		assertThat(request.effectivePlatform()).isEqualTo("darwin")
		assertThat(request.effectiveClientVersion()).isEqualTo("0.1.0")
	}

	@Test
	@DisplayName("빈 invite 필드만 있어도 요청 파싱이 깨지지 않는다")
	fun emptyInviteIsAccepted() {
		assertThatCode { objectMapper.readValue("""{"invite":""}""", EnrollRequest::class.java) }
			.doesNotThrowAnyException()
	}

	@Test
	@DisplayName("code 가 비면 deprecated invite 로 fallback 한다")
	fun fallsBackToDeprecatedInvite() {
		val request = objectMapper.readValue(
			"""{"code":"","invite":"ABCD-EFGH-JKMN"}""",
			EnrollRequest::class.java,
		)

		assertThat(request.effectiveCode()).isEqualTo("ABCD-EFGH-JKMN")
	}

	@Test
	@DisplayName("platform·client_version 도 deprecated 필드로 fallback 한다")
	fun fallsBackToDeprecatedPlatformAndVersion() {
		val request = objectMapper.readValue(
			"""{"invite":"ABCD-EFGH-JKMN","operating_environment":"linux","installer_version":"0.0.9"}""",
			EnrollRequest::class.java,
		)

		assertThat(request.effectivePlatform()).isEqualTo("linux")
		assertThat(request.effectiveClientVersion()).isEqualTo("0.0.9")
	}

	@Test
	@DisplayName("code·invite 가 둘 다 비면 null 이다 — 호출자가 400 을 낸다")
	fun noCodeAtAll() {
		val request = objectMapper.readValue("""{"invite":""}""", EnrollRequest::class.java)

		assertThat(request.effectiveCode()).isNull()
	}

	@Test
	@DisplayName("나머지 deprecated 필드도 받아들이되 무시한다")
	fun acceptsRemainingDeprecatedFields() {
		val raw = """
			{"invite":"ABCD-EFGH-JKMN","device_id":"dev-1","tools_detected":["claude","codex"]}
		""".trimIndent()

		val request = objectMapper.readValue(raw, EnrollRequest::class.java)

		assertThat(request.deviceId).isEqualTo("dev-1")
		assertThat(request.toolsDetected).containsExactly("claude", "codex")
	}

	@Test
	@DisplayName("스키마에 없는 필드가 오면 역직렬화가 실패한다 — 400 invalid_request 로 이어진다")
	fun unknownFieldIsRejected() {
		assertThatThrownBy {
			objectMapper.readValue("""{"invite":"","surprise":"boom"}""", EnrollRequest::class.java)
		}.isInstanceOf(JacksonException::class.java)
	}

	@Test
	@DisplayName("요청 DTO 를 되직렬화해도 요청 스키마를 만족한다")
	fun requestRoundTripMatchesSchema() {
		val request = EnrollRequest(
			code = "ABCD-EFGH-JKMN",
			platform = "darwin",
			architecture = "arm64",
			hostname = "my-macbook",
			clientVersion = "0.1.0",
			invite = "",
		)

		val json = objectMapper.writeValueAsString(request)
		val errors = ContractSchemas.validate(ContractSchemas.enrollRequestSchema(), json)

		assertThat(errors).describedAs(ContractSchemas.describe(errors)).isEmpty()
	}

	private fun sampleEnrollment() = EnrollmentResponse(
		installationId = "0f5f2b3c-2d4e-4a1b-9c8d-6e7f80912345",
		installationToken = "pit_ZXhhbXBsZS1pbnN0YWxsYXRpb24tdG9rZW4tdmFsdWUtMzI",
		telemetryToken = "ptt_ZXhhbXBsZS10ZWxlbWV0cnktdG9rZW4tdmFsdWUtMzI",
		manifest = ContractFixtures.manifest(),
	)
}
