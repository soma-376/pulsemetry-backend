package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.enrollment.secret.TelemetryTokenHasher
import com.team376.pulsemetry.enrollment.support.ContractSchemas
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * `POST /v1/enroll` 통합 테스트.
 *
 * 실제 서버를 띄우고 실제 HTTP 로 부른다. MockMvc 를 쓰지 않는 이유는 동시성 테스트 때문이다 —
 * 같은 초대 코드로 진짜 동시 요청이 들어왔을 때 정확히 하나만 성공하는지가 이 phase 의 핵심이고,
 * 그건 서로 다른 커넥션·트랜잭션에서만 검증된다.
 *
 * 그래서 이 클래스에는 `@Transactional` 이 없다. 준비 데이터는 커밋되고, 매 테스트 전에 비운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class, EnrollmentTestData::class)
class EnrollApiTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	@Autowired
	private lateinit var data: EnrollmentTestData

	@Autowired
	private lateinit var telemetryTokenHasher: TelemetryTokenHasher

	private val http: HttpClient = HttpClient.newHttpClient()

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID

	@BeforeEach
	fun setUp() {
		data.reset()
		tenantId = data.tenant().id
		memberId = data.member(tenantId).id
	}

	// ── 정상 경로 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("정상 요청은 201 과 계약을 만족하는 봉투를 돌려준다")
	fun enrollSucceeds() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation()

		val response = postEnroll(enrollBody(code))

		assertThat(response.statusCode()).isEqualTo(201)
		val errors = ContractSchemas.validate(ContractSchemas.enrollmentSchema(), response.body())
		assertThat(errors)
			.describedAs("스키마 위반:\n%s\nbody=%s", ContractSchemas.describe(errors), response.body())
			.isEmpty()
	}

	@Test
	@DisplayName("응답 최상위 키가 정확히 4개다")
	fun responseHasExactlyFourKeys() {
		data.activeManifest(tenantId, memberId)
		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(objectMapper.readTree(response.body()).propertyNames())
			.containsExactlyInAnyOrder(
				"installation_id",
				"installation_token",
				"telemetry_token",
				"manifest",
			)
	}

	@Test
	@DisplayName("토큰이 규격대로 나온다 — pit_ / ptt_")
	fun tokensUseContractPrefixes() {
		data.activeManifest(tenantId, memberId)
		val body = objectMapper.readTree(postEnroll(enrollBody(seedInvitation())).body())

		assertThat(body.get("installation_token").asString()).startsWith("pit_")
		assertThat(body.get("telemetry_token").asString()).startsWith("ptt_")
	}

	@Test
	@DisplayName("config_revision 이 manifests.version 으로 덮어써진다")
	fun configRevisionComesFromManifestVersion() {
		data.activeManifest(tenantId, memberId, version = 7)
		val body = objectMapper.readTree(postEnroll(enrollBody(seedInvitation())).body())

		// 저장된 jsonb 의 config_revision 은 1 이지만 응답은 version 을 따른다
		assertThat(body.get("manifest").get("config_revision").asInt()).isEqualTo(7)
	}

	@Test
	@DisplayName("manifest 안에 봉투 필드가 섞이지 않는다 (A5)")
	fun manifestStaysPure() {
		data.activeManifest(tenantId, memberId)
		val manifest = objectMapper.readTree(postEnroll(enrollBody(seedInvitation())).body()).get("manifest")

		assertThat(manifest.propertyNames())
			.doesNotContain("installation_id", "installation_token", "telemetry_token")
	}

	@Test
	@DisplayName("installation·자격증명·telemetry token·manifest 배정이 모두 저장된다")
	fun persistsAllRows() {
		data.activeManifest(tenantId, memberId)
		postEnroll(enrollBody(seedInvitation()))

		assertThat(data.countRows("installations")).isEqualTo(1)
		assertThat(data.countRows("installation_credentials")).isEqualTo(1)
		assertThat(data.countRows("telemetry_tokens")).isEqualTo(1)
		assertThat(data.countRows("installation_manifest_assignments")).isEqualTo(1)
	}

	@Test
	@DisplayName("배정된 manifest 의 applied_at 은 NULL 이다 — 아직 적용 보고를 못 받았다")
	fun assignmentIsNotAppliedYet() {
		data.activeManifest(tenantId, memberId)
		postEnroll(enrollBody(seedInvitation()))

		assertThat(
			data.countRows("installation_manifest_assignments WHERE applied_at IS NULL"),
		).isEqualTo(1)
	}

	@Test
	@DisplayName("platform=darwin 이 macos 로 저장된다")
	fun darwinIsNormalizedToMacos() {
		data.activeManifest(tenantId, memberId)
		postEnroll(enrollBody(seedInvitation(), platform = "darwin"))

		assertThat(data.singleColumn("SELECT platform FROM enrollment.installations")).isEqualTo("macos")
	}

	@Test
	@DisplayName("windows·linux 는 그대로 저장된다")
	fun otherPlatformsPassThrough() {
		data.activeManifest(tenantId, memberId)

		postEnroll(enrollBody(seedInvitation(), platform = "windows"))
		assertThat(data.singleColumn("SELECT platform FROM enrollment.installations")).isEqualTo("windows")

		data.reset()
		tenantId = data.tenant().id
		memberId = data.member(tenantId).id
		data.activeManifest(tenantId, memberId)
		postEnroll(enrollBody(seedInvitation(), platform = "linux"))
		assertThat(data.singleColumn("SELECT platform FROM enrollment.installations")).isEqualTo("linux")
	}

	@Test
	@DisplayName("code 없이 deprecated invite 만 있어도 성공한다")
	fun deprecatedInviteStillWorks() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation()

		val response = postEnroll(
			"""{"code":"","invite":"$code","platform":"darwin","architecture":"arm64",
			   "hostname":"my-macbook","client_version":"0.1.0"}""",
		)

		assertThat(response.statusCode()).isEqualTo(201)
	}

	@Test
	@DisplayName("소문자·하이픈 없는 코드도 정규화되어 통과한다")
	fun codeIsNormalizedBeforeLookup() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation()

		val response = postEnroll(enrollBody(code.replace("-", "").lowercase()))

		assertThat(response.statusCode()).isEqualTo(201)
	}

	@Test
	@DisplayName("토큰 원본은 DB 에 없다 — 해시만 저장한다 (R4·L11)")
	fun tokensAreStoredHashedOnly() {
		data.activeManifest(tenantId, memberId)
		val body = objectMapper.readTree(postEnroll(enrollBody(seedInvitation())).body())
		val installationToken = body.get("installation_token").asString()
		val telemetryToken = body.get("telemetry_token").asString()

		assertThat(data.singleColumn("SELECT credential_hash FROM enrollment.installation_credentials"))
			.isEqualTo(Sha256.hex(installationToken))
			.isNotEqualTo(installationToken)
		// telemetry token 만 HMAC 이다 — auth-proxy 가 같은 연산으로 조회한다.
		assertThat(data.singleColumn("SELECT token_hash FROM enrollment.telemetry_tokens"))
			.isEqualTo(telemetryTokenHasher.hex(telemetryToken))
			.isNotEqualTo(telemetryToken)
	}

	// ── 멤버 상태 전환 ───────────────────────────────────────────────────────

	@Test
	@DisplayName("enroll 이 invited 멤버를 active 로 전환한다 — auth-proxy 가 invited 를 거부하기 때문이다")
	fun enrollActivatesInvitedMember() {
		data.activeManifest(tenantId, memberId)
		val invited = data.member(tenantId, status = MemberStatus.invited)
		data.invitation(tenantId, invited.id, "AAAA-BBBB-CCCC")

		val response = postEnroll(enrollBody("AAAA-BBBB-CCCC"))

		assertThat(response.statusCode()).isEqualTo(201)
		assertThat(memberStatus(invited.id)).isEqualTo("active")
	}

	@Test
	@DisplayName("이미 active 인 멤버는 그대로 active 다 — 새 기기 설치 경로")
	fun enrollKeepsActiveMemberActive() {
		data.activeManifest(tenantId, memberId)

		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(response.statusCode()).isEqualTo(201)
		assertThat(memberStatus(memberId)).isEqualTo("active")
	}

	@Test
	@DisplayName("suspended 멤버는 403 이고 상태도 그대로다 — 정지 해제는 설치의 부수효과가 아니다")
	fun enrollDoesNotTouchSuspendedMember() {
		data.activeManifest(tenantId, memberId)
		val suspended = data.member(tenantId, status = MemberStatus.suspended)
		data.invitation(tenantId, suspended.id, "AAAA-BBBB-DDDD")

		val response = postEnroll(enrollBody("AAAA-BBBB-DDDD"))

		assertThat(response.statusCode()).isEqualTo(403)
		assertThat(memberStatus(suspended.id)).isEqualTo("suspended")
	}

	// ── 초대 상태별 실패 ─────────────────────────────────────────────────────

	@Test
	@DisplayName("같은 코드를 두 번 쓰면 409 invitation_used")
	fun reusedCodeIsRejected() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation()

		assertThat(postEnroll(enrollBody(code)).statusCode()).isEqualTo(201)
		val second = postEnroll(enrollBody(code))

		assertThat(second.statusCode()).isEqualTo(409)
		assertThat(errorCode(second)).isEqualTo("invitation_used")
		assertThat(data.countRows("installations")).isEqualTo(1)
	}

	@Test
	@DisplayName("만료된 코드는 410 invitation_expired")
	fun expiredCodeIsRejected() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation(expiresAt = Instant.now().minus(1, ChronoUnit.HOURS))

		val response = postEnroll(enrollBody(code))

		assertThat(response.statusCode()).isEqualTo(410)
		assertThat(errorCode(response)).isEqualTo("invitation_expired")
	}

	@Test
	@DisplayName("폐기된 코드는 409 invitation_revoked")
	fun revokedCodeIsRejected() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation(revokedAt = Instant.now())

		val response = postEnroll(enrollBody(code))

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("invitation_revoked")
	}

	@Test
	@DisplayName("등록되지 않은 코드는 404 invitation_not_found")
	fun unknownCodeIsRejected() {
		data.activeManifest(tenantId, memberId)

		val response = postEnroll(enrollBody("ZZZZ-ZZZZ-ZZZZ"))

		assertThat(response.statusCode()).isEqualTo(404)
		assertThat(errorCode(response)).isEqualTo("invitation_not_found")
	}

	// ── 요청 형식 실패 ───────────────────────────────────────────────────────

	@Test
	@DisplayName("코드 형식이 어긋나면 400 invalid_request")
	fun malformedCodeIsRejected() {
		val response = postEnroll(enrollBody("nope"))

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(errorCode(response)).isEqualTo("invalid_request")
	}

	@Test
	@DisplayName("코드가 아예 없으면 400 invalid_request")
	fun missingCodeIsRejected() {
		val response = postEnroll("""{"invite":"","platform":"darwin"}""")

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(errorCode(response)).isEqualTo("invalid_request")
	}

	@Test
	@DisplayName("계약에 없는 필드가 오면 400 invalid_request")
	fun unknownFieldIsRejected() {
		val response = postEnroll(
			"""{"code":"ABCD-EFGH-JKMN","platform":"darwin","invite":"","surprise":"boom"}""",
		)

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(errorCode(response)).isEqualTo("invalid_request")
	}

	@Test
	@DisplayName("깨진 JSON 은 400 이고 본문을 되돌려주지 않는다 (R4)")
	fun brokenJsonIsRejectedWithoutEcho() {
		val response = postEnroll("""{"code":"ABCD-EFGH-JKMN",""")

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(response.body()).doesNotContain("ABCD-EFGH-JKMN")
	}

	@Test
	@DisplayName("지원하지 않는 platform 은 400 invalid_request")
	fun unsupportedPlatformIsRejected() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation()

		val response = postEnroll(enrollBody(code, platform = "plan9"))

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(errorCode(response)).isEqualTo("invalid_request")
	}

	@Test
	@DisplayName("platform 검증에서 실패하면 초대 소비도 롤백된다")
	fun failedPlatformRollsBackConsumption() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation()

		postEnroll(enrollBody(code, platform = "plan9"))

		// 같은 코드로 다시 시도하면 성공해야 한다
		assertThat(postEnroll(enrollBody(code)).statusCode()).isEqualTo(201)
	}

	@Test
	@DisplayName("에러 본문은 error·message 두 필드뿐이다")
	fun errorBodyShape() {
		val response = postEnroll(enrollBody("nope"))

		assertThat(objectMapper.readTree(response.body()).propertyNames())
			.containsExactlyInAnyOrder("error", "message")
	}

	// ── manifest 미설정 ──────────────────────────────────────────────────────

	@Test
	@DisplayName("활성 manifest 가 없으면 409 manifest_not_configured")
	fun missingManifestIsRejected() {
		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("manifest_not_configured")
	}

	@Test
	@DisplayName("manifest 가 없으면 installation 도 남지 않는다 — 전부 롤백")
	fun missingManifestRollsBackEverything() {
		postEnroll(enrollBody(seedInvitation()))

		assertThat(data.countRows("installations")).isZero()
		assertThat(data.countRows("installation_credentials")).isZero()
		assertThat(data.countRows("telemetry_tokens")).isZero()
	}

	@Test
	@DisplayName("저장된 manifest 가 계약을 어기면 409 manifest_not_configured")
	fun brokenManifestIsRejected() {
		data.brokenManifest(tenantId, memberId)

		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("manifest_not_configured")
	}

	@Test
	@DisplayName("저장된 endpoint 가 https 도 localhost 도 아니면 409 — 클라이언트가 거부할 응답을 내려보내지 않는다")
	fun manifestWithDisallowedEndpointIsRejected() {
		data.manifestOf(
			tenantId,
			memberId,
			manifestJsonWith("https://otlp.pulsemetry.example.com", "http://collector.example.com"),
		)

		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("manifest_not_configured")
	}

	@Test
	@DisplayName("저장된 protocol 이 계약에 없는 값이면 409")
	fun manifestWithUnsupportedProtocolIsRejected() {
		data.manifestOf(tenantId, memberId, manifestJsonWith("http/protobuf", "thrift"))

		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("manifest_not_configured")
	}

	@Test
	@DisplayName("저장된 schema_version 이 1 미만이면 409")
	fun manifestWithTooLowSchemaVersionIsRejected() {
		data.manifestOf(
			tenantId,
			memberId,
			manifestJsonWith("\"schema_version\": 1", "\"schema_version\": 0"),
		)

		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("manifest_not_configured")
	}

	@Test
	@DisplayName("저장된 timeout_ms 가 1 미만이면 409")
	fun manifestWithNonPositiveTimeoutIsRejected() {
		data.manifestOf(
			tenantId,
			memberId,
			manifestJsonWith("\"timeout_ms\": 10000", "\"timeout_ms\": 0"),
		)

		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("manifest_not_configured")
	}

	@Test
	@DisplayName("저장된 compression 이 none·gzip 밖의 값이면 409")
	fun manifestWithUnsupportedCompressionIsRejected() {
		data.manifestOf(
			tenantId,
			memberId,
			manifestJsonWith("\"compression\": \"gzip\"", "\"compression\": \"zstd\""),
		)

		val response = postEnroll(enrollBody(seedInvitation()))

		assertThat(response.statusCode()).isEqualTo(409)
		assertThat(errorCode(response)).isEqualTo("manifest_not_configured")
	}

	// ── 정지된 구성원 ────────────────────────────────────────────────────────

	@Test
	@DisplayName("정지된 구성원의 유효한 코드는 403 이고 코드는 소비되지 않는다")
	fun suspendedMemberCannotConsumeInvitation() {
		data.activeManifest(tenantId, memberId)
		val suspendedId = data.member(tenantId, status = MemberStatus.suspended).id
		val code = InvitationCode.generate()
		data.invitation(tenantId, suspendedId, code)

		val first = postEnroll(enrollBody(code))
		val second = postEnroll(enrollBody(code))

		assertThat(first.statusCode()).isEqualTo(403)
		assertThat(errorCode(first)).isEqualTo("forbidden")
		// 소비가 롤백됐으므로 재시도도 같은 403 이다 — invitation_used 로 바뀌면 소비가 남은 것이다
		assertThat(second.statusCode()).isEqualTo(403)
		assertThat(errorCode(second)).isEqualTo("forbidden")
		assertThat(data.countRows("installations")).isZero()
	}

	// ── 동시성 ───────────────────────────────────────────────────────────────

	@Test
	@DisplayName("같은 코드로 동시 요청 8개를 보내면 정확히 1개만 201 이다 (A6)")
	fun concurrentEnrollmentsElectExactlyOneWinner() {
		data.activeManifest(tenantId, memberId)
		val code = seedInvitation()
		val attempts = 8

		val statuses = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
			executor.invokeAll(
				List(attempts) { Callable { postEnroll(enrollBody(code)).statusCode() } },
			).map { it.get() }
		}

		assertThat(statuses.count { it == 201 })
			.describedAs("응답 코드 분포=%s", statuses.groupingBy { it }.eachCount())
			.isEqualTo(1)
		assertThat(statuses.count { it == 409 }).isEqualTo(attempts - 1)
		assertThat(data.countRows("installations")).isEqualTo(1)
		assertThat(data.countRows("installation_credentials")).isEqualTo(1)
	}

	// ── 헬퍼 ─────────────────────────────────────────────────────────────────

	private fun seedInvitation(
		expiresAt: Instant = Instant.now().plus(72, ChronoUnit.HOURS),
		revokedAt: Instant? = null,
	): String {
		val code = "ABCD-EFGH-JKMN"
		data.invitation(tenantId, memberId, code, expiresAt = expiresAt, revokedAt = revokedAt)
		return code
	}

	private fun memberStatus(id: UUID): String? =
		data.singleColumn("SELECT status FROM enrollment.members WHERE id = '$id'")

	private fun enrollBody(code: String, platform: String = "darwin"): String =
		"""
		{"code":"$code","platform":"$platform","architecture":"arm64",
		 "hostname":"my-macbook","client_version":"0.1.0","invite":""}
		"""

	private fun postEnroll(body: String): HttpResponse<String> =
		http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/enroll"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
		)

	/** 정상 manifest 의 한 값만 바꿔 계약 위반을 만든다. 나머지는 그대로라 위반 지점이 하나뿐이다. */
	private fun manifestJsonWith(target: String, replacement: String): String {
		val json = EnrollmentTestData.MANIFEST_JSON
		require(json.contains(target)) { "정상 manifest 에 '$target' 이 없다" }
		return json.replace(target, replacement)
	}

	private fun errorCode(response: HttpResponse<String>): String =
		objectMapper.readTree(response.body()).get("error").asString()
}
