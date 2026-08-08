package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.secret.SecretToken
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.enrollment.support.ContractSchemas
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.support.PostgresContainerConfig
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * `POST /v1/installations/telemetry-token` 통합 테스트 (PLAN.md §6.3).
 *
 * 재발급의 의미는 "새 토큰을 준다" 가 아니라 **"이전 토큰을 전부 무효로 만든다"** 이다.
 * 그래서 발급된 토큰 하나보다 폐기된 토큰의 개수를 더 꼼꼼히 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class, EnrollmentTestData::class)
class TelemetryTokenApiTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	@Autowired
	private lateinit var data: EnrollmentTestData

	private val http: HttpClient = HttpClient.newHttpClient()

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID
	private lateinit var installationId: UUID

	@BeforeEach
	fun setUp() {
		data.reset()
		tenantId = data.tenant().id
		memberId = data.member(tenantId).id
		installationId = newInstallation()
	}

	/** 초대 코드 해시는 전역 유일이라 설치마다 새 코드를 뽑는다. */
	private fun newInstallation(status: InstallationStatus = InstallationStatus.active): UUID {
		val invitationId = data.invitation(tenantId, memberId, InvitationCode.generate()).id
		return data.installation(tenantId, memberId, invitationId, status).id
	}

	// ── 정상 경로 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("유효한 installation token 으로 200 과 새 telemetry token 을 받는다")
	fun reissueSucceeds() {
		val installationToken = data.credential(installationId)

		val response = postReissue("Bearer $installationToken")

		assertThat(response.statusCode()).isEqualTo(200)
		val body = objectMapper.readTree(response.body())
		assertThat(body.get("installation_id").asString()).isEqualTo(installationId.toString())
		assertThat(body.get("telemetry_token").asString()).startsWith("ptt_")
	}

	@Test
	@DisplayName("응답 최상위 키가 정확히 2개다 (§9 7번)")
	fun responseHasExactlyTwoKeys() {
		val installationToken = data.credential(installationId)

		val response = postReissue("Bearer $installationToken")

		assertThat(objectMapper.readTree(response.body()).propertyNames())
			.containsExactlyInAnyOrder("installation_id", "telemetry_token")
	}

	@Test
	@DisplayName("응답이 telemetry_token_response 스키마를 만족한다")
	fun responseMatchesContractSchema() {
		val installationToken = data.credential(installationId)

		val response = postReissue("Bearer $installationToken")

		val errors = ContractSchemas.validate(
			ContractSchemas.telemetryTokenResponseSchema(),
			response.body(),
		)
		assertThat(errors).describedAs(ContractSchemas.describe(errors)).isEmpty()
	}

	@Test
	@DisplayName("기존 활성 토큰이 전부 폐기된다 — 재발급은 곧 무효화다")
	fun previousTokensAreRevoked() {
		val installationToken = data.credential(installationId)
		repeat(3) { data.telemetryToken(installationId) }
		assertThat(data.activeTelemetryTokenCount(installationId)).isEqualTo(3)

		postReissue("Bearer $installationToken")

		// 새로 발급된 하나만 살아 있어야 한다
		assertThat(data.activeTelemetryTokenCount(installationId)).isEqualTo(1)
		assertThat(data.countRows("telemetry_tokens")).isEqualTo(4)
		assertThat(data.countRows("telemetry_tokens WHERE revoked_at IS NOT NULL")).isEqualTo(3)
	}

	@Test
	@DisplayName("새 토큰이 응답의 토큰과 같은 해시로 저장된다")
	fun issuedTokenIsStoredHashedOnly() {
		val installationToken = data.credential(installationId)

		val response = postReissue("Bearer $installationToken")
		val issued = objectMapper.readTree(response.body()).get("telemetry_token").asString()

		assertThat(
			data.singleColumn("SELECT token_hash FROM enrollment.telemetry_tokens WHERE revoked_at IS NULL"),
		).isEqualTo(Sha256.hex(issued)).isNotEqualTo(issued)
	}

	@Test
	@DisplayName("두 번 재발급하면 서로 다른 토큰이 나오고 이전 것은 죽는다")
	fun repeatedReissueRotatesToken() {
		val installationToken = data.credential(installationId)

		val first = objectMapper.readTree(postReissue("Bearer $installationToken").body())
			.get("telemetry_token").asString()
		val second = objectMapper.readTree(postReissue("Bearer $installationToken").body())
			.get("telemetry_token").asString()

		assertThat(second).isNotEqualTo(first)
		assertThat(data.activeTelemetryTokenCount(installationId)).isEqualTo(1)
	}

	@Test
	@DisplayName("자격증명의 last_used_at 이 갱신된다")
	fun lastUsedAtIsRecorded() {
		val installationToken = data.credential(installationId)
		assertThat(
			data.countRows("installation_credentials WHERE last_used_at IS NULL"),
		).isEqualTo(1)

		postReissue("Bearer $installationToken")

		assertThat(
			data.countRows("installation_credentials WHERE last_used_at IS NOT NULL"),
		).isEqualTo(1)
	}

	@Test
	@DisplayName("다른 installation 의 토큰은 건드리지 않는다")
	fun otherInstallationsAreUntouched() {
		val installationToken = data.credential(installationId)
		val otherInstallationId = newInstallation()
		data.telemetryToken(otherInstallationId)

		postReissue("Bearer $installationToken")

		assertThat(data.activeTelemetryTokenCount(otherInstallationId)).isEqualTo(1)
	}

	// ── 인증 실패 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("Authorization 헤더가 없으면 401 unauthorized")
	fun missingHeaderIsUnauthorized() {
		val response = postReissue(null)

		assertThat(response.statusCode()).isEqualTo(401)
		assertThat(errorCode(response)).isEqualTo("unauthorized")
	}

	@Test
	@DisplayName("Bearer 스킴이 아니면 401")
	fun wrongSchemeIsUnauthorized() {
		val installationToken = data.credential(installationId)

		assertThat(postReissue("Basic $installationToken").statusCode()).isEqualTo(401)
		assertThat(postReissue(installationToken).statusCode()).isEqualTo(401)
	}

	@Test
	@DisplayName("Bearer 뒤가 비어 있으면 401")
	fun emptyBearerIsUnauthorized() {
		assertThat(postReissue("Bearer ").statusCode()).isEqualTo(401)
		assertThat(postReissue("Bearer    ").statusCode()).isEqualTo(401)
	}

	@Test
	@DisplayName("등록되지 않은 토큰은 401")
	fun unknownTokenIsUnauthorized() {
		data.credential(installationId)

		val response = postReissue("Bearer ${SecretToken.installationToken()}")

		assertThat(response.statusCode()).isEqualTo(401)
		assertThat(errorCode(response)).isEqualTo("unauthorized")
	}

	@Test
	@DisplayName("폐기된 자격증명은 401")
	fun revokedCredentialIsUnauthorized() {
		val installationToken = data.credential(installationId, revoked = true)

		val response = postReissue("Bearer $installationToken")

		assertThat(response.statusCode()).isEqualTo(401)
		assertThat(errorCode(response)).isEqualTo("unauthorized")
	}

	@Test
	@DisplayName("Bearer 스킴 비교는 대소문자를 가리지 않는다 (RFC 7235)")
	fun bearerSchemeIsCaseInsensitive() {
		val installationToken = data.credential(installationId)

		assertThat(postReissue("bearer $installationToken").statusCode()).isEqualTo(200)
	}

	// ── 폐기된 installation ──────────────────────────────────────────────────

	@Test
	@DisplayName("폐기된 installation 은 403 installation_revoked")
	fun revokedInstallationIsForbidden() {
		val revokedInstallationId = newInstallation(InstallationStatus.revoked)
		val installationToken = data.credential(revokedInstallationId)

		val response = postReissue("Bearer $installationToken")

		assertThat(response.statusCode()).isEqualTo(403)
		assertThat(errorCode(response)).isEqualTo("installation_revoked")
	}

	@Test
	@DisplayName("폐기된 installation 에는 새 토큰이 발급되지 않는다")
	fun revokedInstallationGetsNoToken() {
		val revokedInstallationId = newInstallation(InstallationStatus.revoked)
		val installationToken = data.credential(revokedInstallationId)

		postReissue("Bearer $installationToken")

		assertThat(data.activeTelemetryTokenCount(revokedInstallationId)).isZero()
	}

	@Test
	@DisplayName("인증 실패 응답은 error·message 두 필드뿐이고 토큰을 되돌려주지 않는다 (R4)")
	fun errorBodyLeaksNothing() {
		val installationToken = data.credential(installationId, revoked = true)

		val response = postReissue("Bearer $installationToken")

		assertThat(objectMapper.readTree(response.body()).propertyNames())
			.containsExactlyInAnyOrder("error", "message")
		assertThat(response.body()).doesNotContain(installationToken)
	}

	// ── 헬퍼 ─────────────────────────────────────────────────────────────────

	private fun postReissue(authorization: String?): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/installations/telemetry-token"))
			.POST(HttpRequest.BodyPublishers.noBody())
		authorization?.let { builder.header("Authorization", it) }
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
	}

	private fun errorCode(response: HttpResponse<String>): String =
		objectMapper.readTree(response.body()).get("error").asString()
}
