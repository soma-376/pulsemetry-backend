package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.secret.SecretToken
import com.team376.pulsemetry.enrollment.secret.TelemetryTokenHasher
import com.team376.pulsemetry.enrollment.support.ContractSchemas
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationStatus
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

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

	@Autowired
	private lateinit var telemetryTokenHasher: TelemetryTokenHasher

	@Autowired
	private lateinit var dataSource: DataSource

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
	@DisplayName("기존 활성 토큰이 폐기된다 — 재발급은 곧 무효화다")
	fun previousTokensAreRevoked() {
		val installationToken = data.credential(installationId)
		// 유일 인덱스(V3)로 활성 토큰은 installation 당 최대 1개다
		data.telemetryToken(installationId)
		assertThat(data.activeTelemetryTokenCount(installationId)).isEqualTo(1)

		postReissue("Bearer $installationToken")

		// 새로 발급된 하나만 살아 있어야 한다
		assertThat(data.activeTelemetryTokenCount(installationId)).isEqualTo(1)
		assertThat(data.countRows("telemetry_tokens")).isEqualTo(2)
		assertThat(data.countRows("telemetry_tokens WHERE revoked_at IS NOT NULL")).isEqualTo(1)
	}

	@Test
	@DisplayName("새 토큰이 응답의 토큰과 같은 해시로 저장된다")
	fun issuedTokenIsStoredHashedOnly() {
		val installationToken = data.credential(installationId)

		val response = postReissue("Bearer $installationToken")
		val issued = objectMapper.readTree(response.body()).get("telemetry_token").asString()

		assertThat(
			data.singleColumn("SELECT token_hash FROM enrollment.telemetry_tokens WHERE revoked_at IS NULL"),
		).isEqualTo(telemetryTokenHasher.hex(issued)).isNotEqualTo(issued)
	}

	@Test
	@DisplayName("재발급이 invited 멤버를 active 로 보정한다 — 전환 도입 이전 설치의 복구 경로")
	fun reissueActivatesInvitedMember() {
		val invited = data.member(tenantId, status = MemberStatus.invited)
		val invitationId = data.invitation(tenantId, invited.id, InvitationCode.generate()).id
		val installation = data.installation(tenantId, invited.id, invitationId)
		val installationToken = data.credential(installation.id)

		val response = postReissue("Bearer $installationToken")

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(data.singleColumn("SELECT status FROM enrollment.members WHERE id = '${invited.id}'"))
			.isEqualTo("active")
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

	// ── 동시성 ───────────────────────────────────────────────────────────────

	/**
	 * 재발급은 installation 행 잠금으로 직렬화되므로 겹쳐도 **모두 성공한다** — enroll 과 다르다.
	 * enroll 은 초대 코드 하나를 놓고 정확히 하나만 이기지만, 재발급은 기다렸다 이어서 수행한다.
	 *
	 * 잠금이 없으면 READ COMMITTED 에서 겹친 트랜잭션은 서로의 미커밋 INSERT 를 못 보고
	 * 각자 토큰을 넣어 `ux_telemetry_tokens_installation_active`(V3) 를 위반한다 — 진 쪽은 200 이 아니다.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	@DisplayName("같은 installation 으로 동시 재발급 8건이 전부 200 이고 활성 토큰은 1개만 남는다")
	fun concurrentReissuesAllSucceedAndLeaveOneActiveToken() {
		val installationToken = data.credential(installationId)
		// 폐기 대상 활성 토큰을 미리 심는다. 잠금이 없으면 겹친 요청들이 바로 이 행에서 경합하다가
		// 서로의 미커밋 INSERT 를 못 본 채 각자 INSERT 해 깨진다 — 겹치기만 하면 반드시 실패한다.
		//
		// 겹침 자체를 API 가 보장하지는 않는다. invokeAll 의 계약은 "전부 완료될 때까지 기다린다"
		// 뿐이라 동시 시작도 최소 병렬도도 약속하지 않는다. 다만 send() 가 소켓 대기에서 캐리어를
		// 놓으므로 첫 응답이 돌아오기 전에 8건이 모두 나가고, 겹침은 이 시점부터 서버 스레드가
		// 정한다. jdk.virtualThreadScheduler.parallelism 을 1 로 낮춰 확인했을 때도 잠금을 빼면
		// 3회 모두 {200=3, 500=5} 로 검출됐다 — 확률적 검출이지만 병렬도에 기대지 않는다.
		data.telemetryToken(installationId)
		// Hikari 기본 풀(10)보다 작아야 한다. 잠금을 기다리는 동안에도 커넥션을 쥐고 있어서,
		// 풀을 넘기면 connection-timeout 3초(application.yaml)에 걸려 잠금과 무관하게 깨진다.
		val attempts = 8

		val responses = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
			executor.invokeAll(
				List(attempts) { Callable { postReissue("Bearer $installationToken") } },
			).map { it.get() }
		}

		// 잠금이 사라지면 여기가 깨진다. 상태 코드를 먼저 보는 이유는 본문 파싱이 앞서면
		// 500 바디에서 터져 실패 원인이 가려지기 때문이다.
		val statuses = responses.map { it.statusCode() }
		assertThat(statuses)
			.describedAs("응답 코드 분포=%s", statuses.groupingBy { it }.eachCount())
			.containsOnly(200)

		// 부분 유니크 인덱스(V3)가 사라지면 여기가 깨진다.
		assertThat(data.activeTelemetryTokenCount(installationId)).isEqualTo(1)
		assertThat(data.countRows("telemetry_tokens")).isEqualTo(attempts + 1L)
		assertThat(data.countRows("telemetry_tokens WHERE revoked_at IS NOT NULL")).isEqualTo(attempts.toLong())

		// 살아남은 하나는 응답으로 나간 토큰 중 하나여야 한다 — 아무도 받지 못한 유령 토큰이 아니다.
		val issued = responses.map { objectMapper.readTree(it.body()).get("telemetry_token").asString() }
		assertThat(issued).doesNotHaveDuplicates()
		assertThat(data.singleColumn("SELECT token_hash FROM enrollment.telemetry_tokens WHERE revoked_at IS NULL"))
			.isIn(issued.map { telemetryTokenHasher.hex(it) })
	}

	/**
	 * 위 버스트 테스트가 못 하는 일을 한다.
	 *
	 * 버스트는 요청들이 실제로 겹쳐야만 의미가 있는데, 겹침은 API 가 보장하지 않는다.
	 * 안 겹친 날에도 8건 모두 200 이라 **아무것도 검증하지 않고 초록**이 될 수 있다.
	 *
	 * 여기서는 경합을 우연에 맡기지 않고 **구성으로 강제한다** — 테스트가 먼저 installation 행을
	 * 잠가 두고 재발급을 던진다. 서비스가 그 행을 잠그지 않는 구현이면 요청이 그냥 끝나 버려
	 * 대기 세션이 생기지 않고, 그 사실만으로 실패한다. 스케줄러도 병렬도도 개입하지 않는다.
	 *
	 * 프로브가 `FOR UPDATE` 가 아니라 `FOR NO KEY UPDATE` 로 잡는 것이 핵심이다.
	 * `FOR UPDATE` 로 잡으면 `telemetry_tokens` INSERT 의 외래키 검사(참조되는 installation 행에
	 * `FOR KEY SHARE` 를 잡는다)까지 막혀서, 서비스가 잠금을 안 잡아도 대기 세션이 생긴다 —
	 * 서비스가 아니라 외래키를 검증하는 테스트가 되어 버린다. `FOR NO KEY UPDATE` 는
	 * `FOR KEY SHARE` 와 충돌하지 않으므로 서비스의 잠금하고만 부딪힌다.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	@DisplayName("재발급은 installation 행 잠금을 기다린다 — 먼저 잠가 두면 요청이 막힌다")
	fun reissueWaitsForInstallationRowLock() {
		val installationToken = data.credential(installationId)

		// 첫 요청은 서블릿·Hibernate 지연 초기화 때문에 느리다. 잠그기 전에 한 번 데워 두면
		// 「요청이 DB 까지 닿지 못해 판정 불가」 상황이 사실상 사라진다.
		assertThat(postReissue("Bearer $installationToken").statusCode()).isEqualTo(200)

		Executors.newVirtualThreadPerTaskExecutor().use { executor ->
			val probeConnection = dataSource.connection
			val pending = try {
				probeConnection.autoCommit = false
				lockInstallationRow(probeConnection, installationId)

				val submitted = executor.submit(Callable { postReissue("Bearer $installationToken") })

				// 무엇을 기다리는지까지 본다 — installations 행의 행 잠금이어야 한다.
				// Hibernate 는 PostgreSQL 에서 PESSIMISTIC_WRITE 를 `for update` 가 아니라
				// `for no key update` 로 낸다. 방언이 바뀌어도 깨지지 않게 두 형태를 다 받는다.
				assertThat(awaitBlockedSession(submitted))
					.describedAs("막혀 있는 세션이 실행 중인 SQL")
					.anySatisfy { query ->
						assertThat(query.lowercase())
							.contains("enrollment.installations")
							.containsPattern("for (no key )?update")
					}

				assertThat(submitted.isDone)
					.describedAs("행 잠금을 기다리는 중이므로 아직 끝나 있으면 안 된다")
					.isFalse()
				submitted
			} finally {
				// 잠금을 반드시 놓는다. 남겨 두면 다음 테스트의 TRUNCATE 가 영원히 막힌다.
				probeConnection.rollback()
				probeConnection.close()
			}

			// 잠금이 풀리면 이어서 성공해야 한다 — 진 쪽은 실패가 아니라 지연이다.
			val response = pending.get(30, TimeUnit.SECONDS)
			assertThat(response.statusCode()).isEqualTo(200)
			assertThat(data.activeTelemetryTokenCount(installationId)).isEqualTo(1)
		}
	}

	// ── 헬퍼 ─────────────────────────────────────────────────────────────────

	/**
	 * 다른 세션이 installation 행을 먼저 잠근 상태를 만든다. 서비스의 findWithLockById 와 같은 행이다.
	 *
	 * `FOR UPDATE` 가 아니라 `FOR NO KEY UPDATE` 인 이유는 위 테스트 KDoc 에 적었다 —
	 * 외래키 검사까지 막아 버리면 잠금이 없어도 통과하는 테스트가 된다.
	 */
	private fun lockInstallationRow(connection: Connection, id: UUID) {
		connection.prepareStatement("SELECT id FROM enrollment.installations WHERE id = ? FOR NO KEY UPDATE").use { statement ->
			statement.setObject(1, id)
			statement.executeQuery().use { rows ->
				check(rows.next()) { "잠글 installation 행이 없다" }
			}
		}
	}

	/**
	 * 잠금을 기다리는 세션이 나타날 때까지 기다린다.
	 *
	 * 마감까지 나타나지 않으면 재발급이 installation 행을 잠그지 않는다는 뜻이다 — 이게 회귀 신호다.
	 */
	private fun awaitBlockedSession(pending: Future<*>): List<String> {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
		while (System.nanoTime() < deadline) {
			val blocked = data.blockedSessionQueries()
			if (blocked.isNotEmpty()) return blocked

			// 잠금을 쥔 채인데 요청이 끝났다면 그 행을 기다리지 않았다는 뜻이다. 느린 환경과
			// 헷갈릴 여지가 없는 확정 회귀 신호이고, 잠금이 없으면 여기까지 수십 ms 면 온다.
			if (pending.isDone) {
				throw AssertionError("잠금을 쥔 채인데 재발급이 끝났다 — installation 행을 잠그지 않는다")
			}
			Thread.sleep(25)
		}

		// 막히지도 끝나지도 않았다 = 요청이 DB 에 닿지 못했다. 잠금 유무를 판정할 수 없으므로
		// 회귀라고 단정하지 않는다. 폴링 질의는 계속 성공했으니 DB 가 아니라 HTTP·서블릿 경로 문제다.
		throw AssertionError(
			"15초 안에 재발급이 DB 에 닿지 않아 잠금 유무를 판정할 수 없었다. " +
				"폴링 질의는 계속 성공했으므로 DB 가 아니라 HTTP·서블릿 경로가 막힌 것이다 — 환경 문제일 수 있다",
		)
	}

	private fun postReissue(authorization: String?): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/installations/telemetry-token"))
			.POST(HttpRequest.BodyPublishers.noBody())
		authorization?.let { builder.header("Authorization", it) }
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
	}

	private fun errorCode(response: HttpResponse<String>): String =
		objectMapper.readTree(response.body()).get("error").asString()
}
