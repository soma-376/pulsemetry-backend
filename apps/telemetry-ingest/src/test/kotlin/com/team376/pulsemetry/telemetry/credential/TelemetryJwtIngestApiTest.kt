package com.team376.pulsemetry.telemetry.credential

import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryTokenRevokeReason
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationEntryPoint
import com.team376.pulsemetry.security.TelemetryTokenHasher
import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import com.team376.pulsemetry.telemetry.otlp.OtlpResponses
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestJwtKeys
import com.team376.pulsemetry.telemetry.support.IngestTestData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * HTTP ingest 의 telemetry JWT 호환(ADR 0065 Decision 7). 전환·복구 기간에 같은 ingest 가 `ptt_` 와 JWT 를 함께 받는다.
 * JWT 는 서명·용도·시간과 RDB 판정으로만 통과하고 해시로 조회하지 않는다. 401 본문은 하나다.
 */
@ExtendWith(OutputCaptureExtension::class)
class TelemetryJwtIngestApiTest : AbstractIngestIntegrationTest() {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var data: IngestTestData

	@Autowired
	private lateinit var hasher: TelemetryTokenHasher

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private val http: HttpClient = HttpClient.newHttpClient()

	@BeforeEach
	fun reset() = data.clear()

	@AfterEach
	fun cleanUp() = data.clear()

	@Test
	@DisplayName("유효한 telemetry JWT 는 200 이고 jti 로 자격 기록을 읽는다 — 해시 조회는 하지 않는다")
	fun validJwtIsAccepted() {
		val seeded = data.seedJwt()

		val response = post(seeded.rawToken)

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.body()).isEqualTo("""{"partialSuccess":{}}""")
		verify(telemetryTokens).findCredentialRowById(seeded.credentialId!!)
		verify(telemetryTokens, never()).findAuthRowByTokenHash(ArgumentMatchers.anyString())
	}

	@Test
	@DisplayName("ptt_ 는 그대로 해시로 통과한다 — 같은 ingest 가 두 형식을 받는다")
	fun opaqueStillWorks() {
		assertThat(post(data.seed().rawToken).statusCode()).isEqualTo(200)
	}

	@Test
	@DisplayName("서명이 틀린 JWT 는 해시가 DB 에 있어도 401 이다 — JWT 를 해시로 조회하지 않는다")
	fun forgedJwtIsNotHashLookedUp() {
		val seeded = data.seed()
		val stranger = TelemetryJwtFixtures.signingKey(IngestJwtKeys.KID)
		val forged = TelemetryJwtFixtures.sign(
			TelemetryJwtFixtures.validHeader(IngestJwtKeys.KID).build(),
			TelemetryJwtFixtures.validClaims(tenantId = seeded.tenantId, installationId = seeded.installationId).build(),
			stranger.privateKey,
		)
		// 위조 토큰의 해시를 활성 opaque 행처럼 심는다. 해시 조회였다면 통과했을 것이다.
		jdbc.update("UPDATE enrollment.telemetry_tokens SET token_hash = ? WHERE installation_id = ?", hasher.hex(forged), seeded.installationId)

		assertUnauthorized(post(forged))
	}

	@Test
	@DisplayName("만료·회전·보안 폐기·차단·불일치 JWT 는 모두 같은 401 본문이다")
	fun rejectionsShareOneBody() {
		val expired = data.seedJwt(issuedAt = Instant.now().minus(4, ChronoUnit.HOURS))
		assertUnauthorized(post(expired.rawToken))

		val rotated = data.seedJwt(revokedAt = Instant.now().minusSeconds(1), revokeReason = TelemetryTokenRevokeReason.rotated)
		assertUnauthorized(post(rotated.rawToken))

		val security = data.seedJwt(revokedAt = Instant.now().plusSeconds(600), revokeReason = TelemetryTokenRevokeReason.security)
		assertUnauthorized(post(security.rawToken))

		val suspended = data.seedJwt()
		jdbc.update("UPDATE enrollment.members SET status = 'suspended' WHERE id = ?", suspended.memberId)
		assertUnauthorized(post(suspended.rawToken))

		// 서명은 맞지만 claim 의 tenant 가 행과 다르다.
		val owner = data.seedJwt()
		val mismatched = TelemetryJwtFixtures.sign(
			TelemetryJwtFixtures.validHeader(IngestJwtKeys.KID).build(),
			TelemetryJwtFixtures.validClaims(
				credentialId = owner.credentialId!!, tenantId = UUID.randomUUID(), installationId = owner.installationId,
				issuedAt = owner.issuedAt!!,
			).build(),
			IngestJwtKeys.KEY.privateKey,
		)
		assertUnauthorized(post(mismatched))

		// 모르는 jti.
		val unknown = IngestJwtKeys.KEY.issuer().issue(UUID.randomUUID(), owner.tenantId, owner.installationId, Instant.now()).token
		assertUnauthorized(post(unknown))
	}

	@Test
	@DisplayName("JWT 판정의 DB 장애는 401 이 아니라 503 + Retry-After 다")
	fun storeFailureIs503() {
		val seeded = data.seedJwt()
		doThrow(DataAccessResourceFailureException("db down")).`when`(telemetryTokens)
			.findCredentialRowById(ArgumentMatchers.any() ?: UUID.randomUUID())

		val response = post(seeded.rawToken)

		assertThat(response.statusCode()).isEqualTo(503)
		assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
	}

	@Test
	@DisplayName("Bearer 원문은 거부 로그에 남지 않는다 — 카나리아 문자열 검사")
	fun bearerNeverLogged(output: CapturedOutput) {
		val canary = "CANARYJWT" + UUID.randomUUID().toString().replace("-", "")
		val seeded = data.seedJwt(revokedAt = Instant.now().minusSeconds(1), revokeReason = TelemetryTokenRevokeReason.rotated)

		assertUnauthorized(post("$canary.$canary.$canary"))
		assertUnauthorized(post("ptt_$canary"))
		assertUnauthorized(post(seeded.rawToken))

		assertThat(output.all).doesNotContain(canary).doesNotContain(seeded.rawToken)
		assertThat(output.all).contains("detail=credential_rotated").contains("tokenId=${seeded.credentialId}")
	}

	@Test
	@DisplayName("Gateway 와 ingest 의 401 본문은 같은 바이트다")
	fun unauthorizedBodyMatchesGateway() {
		assertThat(TelemetryTokenAuthenticationEntryPoint.BODY).isEqualTo(OtlpResponses.UNAUTHORIZED_BODY)
	}

	private fun assertUnauthorized(response: HttpResponse<String>) {
		assertThat(response.statusCode()).isEqualTo(401)
		assertThat(response.body()).isEqualTo(TelemetryTokenAuthenticationEntryPoint.BODY)
	}

	private fun post(token: String): HttpResponse<String> {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/logs"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer $token")
			.POST(HttpRequest.BodyPublishers.ofByteArray(ONE_LOG))
			.build()
		return http.send(request, HttpResponse.BodyHandlers.ofString())
	}

	private companion object {
		val ONE_LOG: ByteArray = """
			{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"claude-code"}}]},
			 "scopeLogs":[{"logRecords":[{"timeUnixNano":"${Instant.now().epochSecond * 1_000_000_000L}",
			   "body":{"stringValue":"claude_code.user_prompt"},
			   "attributes":[{"key":"session.id","value":{"stringValue":"jwt-session"}}]}]}]}]}
		""".trimIndent().toByteArray()
	}
}
