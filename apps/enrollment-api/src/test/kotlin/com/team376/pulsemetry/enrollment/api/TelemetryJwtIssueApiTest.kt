package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.support.ContractSchemas
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryTokenFormat
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryTokenRevokeReason
import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import com.team376.pulsemetry.security.TelemetryTokenHasher
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtVerifier
import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * 발급 설정을 켰을 때 최초 enrollment 와 재발급이 telemetry JWT 를 내는지 본다(ADR 0065 Decision 1·3·5).
 *
 * 응답 봉투의 키 수와 스키마는 그대로여야 한다 — 값만 JWT 다. 키쌍은 매번 새로 만든다.
 * 설정이 다른 컨텍스트라 Postgres 컨테이너가 하나 더 뜬다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class, EnrollmentTestData::class)
class TelemetryJwtIssueApiTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	@Autowired
	private lateinit var data: EnrollmentTestData

	@Autowired
	private lateinit var hasher: TelemetryTokenHasher

	@Autowired
	private lateinit var telemetryTokens: TelemetryTokenRepository

	private val http: HttpClient = HttpClient.newHttpClient()
	private val verifier: TelemetryJwtVerifier = KEY.verifier()

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID

	@BeforeEach
	fun setUp() {
		data.reset()
		tenantId = data.tenant().id
		memberId = data.member(tenantId).id
	}

	@Test
	@DisplayName("enroll 은 같은 네 키의 봉투에 telemetry JWT 를 싣고, 행 id·시각·kid·해시가 JWT 와 같다")
	fun enrollIssuesJwt() {
		data.activeManifest(tenantId, memberId)
		val code = "ABCD-EFGH-JKMN"
		data.invitation(tenantId, memberId, code)

		val response = postEnroll(code)

		assertThat(response.statusCode()).isEqualTo(201)
		val body = objectMapper.readTree(response.body())
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("installation_id", "installation_token", "telemetry_token", "manifest")
		assertThat(ContractSchemas.validate(ContractSchemas.enrollmentSchema(), response.body())).isEmpty()

		val token = body.get("telemetry_token").asString()
		val installationId = UUID.fromString(body.get("installation_id").asString())
		val verified = verifier.verify(token, Instant.now())
		assertThat(verified.installationId).isEqualTo(installationId)
		assertThat(verified.tenantId).isEqualTo(tenantId)

		val row = telemetryTokens.findById(verified.credentialId).orElseThrow()
		assertThat(row.installationId).isEqualTo(installationId)
		assertThat(row.tokenFormat).isEqualTo(TelemetryTokenFormat.jwt)
		assertThat(row.issuedAt).isEqualTo(verified.issuedAt)
		assertThat(row.expiresAt).isEqualTo(verified.expiresAt)
		assertThat(row.signingKid).isEqualTo(KEY.kid)
		// 구 ingest 롤백 호환 — JWT 전문의 HMAC 을 그대로 저장한다.
		assertThat(row.tokenHash).isEqualTo(hasher.hex(token))
		assertThat(row.revokedAt).isNull()
	}

	@Test
	@DisplayName("재발급도 JWT 이고 이전 활성 토큰은 rotated 로 폐기되며 활성은 하나다")
	fun reissueRotates() {
		val invitationId = data.invitation(tenantId, memberId, InvitationCode.generate()).id
		val installationId = data.installation(tenantId, memberId, invitationId).id
		val installationToken = data.credential(installationId)
		data.telemetryToken(installationId)

		val first = postReissue(installationToken)
		val second = postReissue(installationToken)

		assertThat(first.statusCode()).isEqualTo(200)
		assertThat(objectMapper.readTree(second.body()).propertyNames()).containsExactlyInAnyOrder("installation_id", "telemetry_token")
		assertThat(ContractSchemas.validate(ContractSchemas.telemetryTokenResponseSchema(), second.body())).isEmpty()

		val firstJwt = verifier.verify(objectMapper.readTree(first.body()).get("telemetry_token").asString(), Instant.now())
		val secondJwt = verifier.verify(objectMapper.readTree(second.body()).get("telemetry_token").asString(), Instant.now())
		assertThat(secondJwt.credentialId).isNotEqualTo(firstJwt.credentialId)

		val rotated = telemetryTokens.findById(firstJwt.credentialId).orElseThrow()
		assertThat(rotated.revokeReason).isEqualTo(TelemetryTokenRevokeReason.rotated)
		assertThat(rotated.revokedAt).isNotNull()
		assertThat(telemetryTokens.findById(secondJwt.credentialId).orElseThrow().revokedAt).isNull()
		assertThat(data.activeTelemetryTokenCount(installationId)).isEqualTo(1)
		// 최초의 ptt_ 행도 회전으로 폐기됐다.
		assertThat(data.countRows("telemetry_tokens WHERE revoke_reason = 'rotated'")).isEqualTo(2)
	}

	private fun postEnroll(code: String): HttpResponse<String> = http.send(
		HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/enroll"))
			.header("Content-Type", "application/json")
			.POST(
				HttpRequest.BodyPublishers.ofString(
					"""{"code":"$code","platform":"darwin","architecture":"arm64","hostname":"h","client_version":"0.1.0","invite":""}""",
					StandardCharsets.UTF_8,
				),
			)
			.build(),
		HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
	)

	private fun postReissue(installationToken: String): HttpResponse<String> = http.send(
		HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/installations/telemetry-token"))
			.header("Authorization", "Bearer $installationToken")
			.POST(HttpRequest.BodyPublishers.noBody())
			.build(),
		HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
	)

	companion object {
		private val KEY = TelemetryJwtFixtures.signingKey("tk-enroll")
		private val PEM = KEY.writePem(Files.createTempDirectory("telemetry-jwt-enroll"))

		@JvmStatic
		@DynamicPropertySource
		fun telemetryJwt(registry: DynamicPropertyRegistry) {
			registry.add("pulsemetry.telemetry-jwt.issue-enabled") { "true" }
			registry.add("pulsemetry.telemetry-jwt.active-kid") { KEY.kid }
			registry.add("pulsemetry.telemetry-jwt.private-key-file") { PEM.first.toString() }
			registry.add("pulsemetry.telemetry-jwt.public-key-files.${KEY.kid}") { PEM.second.toString() }
		}
	}
}
