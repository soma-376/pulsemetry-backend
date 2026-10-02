package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.enrollment.support.ContractSchemas
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.security.user.UserIdentity
import com.team376.pulsemetry.security.user.UserJwt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `GET /v1/manifest` 재동기화(명세 §11.1, ADR 0019). 봉투의 오라클은 명세다 — 5키, 나머지 네 키는 §11 의 TokenResponse,
 * AT 클레임은 §11 의 클레임 표. 봉투 안의 `manifest` 만 원격 telemetryctl develop 의 `enrollment-manifest` 스키마 원본으로 본다
 * (원격에는 재조회 봉투·클레임의 스키마가 없다).
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties=["pulsemetry.user-auth.enabled=true"])
@Import(PostgresContainerConfig::class,EnrollmentTestData::class,AuthClockConfig::class)
class ManifestResyncApiTest {
    @LocalServerPort private var port:Int=0
    @Autowired private lateinit var data:EnrollmentTestData
    @Autowired private lateinit var jdbc:JdbcClient
    @Autowired private lateinit var mapper:ObjectMapper
    @Autowired private lateinit var auth:UserAuthService
    @Autowired private lateinit var clock:AuthTestClock
    @Autowired private lateinit var manager:PlatformTransactionManager
    @MockitoSpyBean private lateinit var jwt:UserJwt
    private val http=HttpClient.newHttpClient()
    private lateinit var tenant:UUID
    private lateinit var member:UUID
    private lateinit var old:JsonNode

    @BeforeEach fun setup() {
        data.reset(); jdbc.sql("TRUNCATE enrollment.auth_attempts").update()
        clock.now=Instant.parse("2026-09-09T12:00:00Z")
        tenant=data.tenant().id;member=data.member(tenant,"user@example.com").id
        val code=InvitationCode.generate()
        data.invitation(tenant,member,code,expiresAt=clock.now.plusSeconds(3600))
        data.activeManifest(tenant,member,3)
        assertThat(post("signup",mapOf("code" to code,"email" to "user@example.com","password" to "correct-password-123")).statusCode()).isEqualTo(201)
        val login=post("login",mapOf("tenant_id" to tenant,"email" to "user@example.com","password" to "correct-password-123"))
        assertThat(login.statusCode()).isEqualTo(200)
        old=mapper.readTree(login.body())
    }
    private fun post(path:String,body:Any)=http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/auth/$path"))
        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString())
    private fun get(token:String=old["refresh_token"].asString(),condition:Boolean=false):HttpResponse<String> {
        val b=HttpRequest.newBuilder(URI("http://localhost:$port/v1/manifest")).header("Authorization","Bearer $token")
        if(condition) b.header("If-None-Match","*").header("If-Modified-Since","Wed, 09 Sep 2099 00:00:00 GMT")
        return http.send(b.GET().build(),HttpResponse.BodyHandlers.ofString())
    }
    private fun update(sql:String) { jdbc.sql(sql).update() }
    private fun revision()=jdbc.sql("SELECT manifest_revision FROM enrollment.user_sessions").query(Int::class.javaObjectType).single()
    private fun refreshCount()=jdbc.sql("SELECT count(*) FROM enrollment.user_refresh_tokens").query(Long::class.javaObjectType).single()

    @Test fun `재동기화 봉투와 JWT 클레임은 같은 최신 revision이며 캐시를 쓰지 않는다`() {
        update("UPDATE enrollment.manifests SET version=4")
        val r=get(condition=true)
        assertThat(r.statusCode()).isEqualTo(200)
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
        assertThat(r.headers().firstValue("Pragma").orElse("")).isEqualTo("no-cache")
        assertThat(r.headers().firstValue("ETag")).isEmpty()
        val body=mapper.readTree(r.body())
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("manifest","access_token","refresh_token","token_type","expires_in")
        val manifestErrors=ContractSchemas.validate(ContractSchemas.manifestSchema(),mapper.writeValueAsString(body["manifest"]))
        assertThat(manifestErrors).describedAs(ContractSchemas.describe(manifestErrors)).isEmpty()
        assertThat(body["token_type"].asString()).isEqualTo("Bearer")
        assertThat(body["expires_in"].isIntegralNumber).isTrue()
        assertThat(body["expires_in"].asInt()).isEqualTo(300)
        assertThat(body["refresh_token"].asString()).matches("urt_[A-Za-z0-9_-]{43}")
        assertThat(body["access_token"].isString).isTrue()
        assertThat(body["manifest"]["config_revision"].asInt()).isEqualTo(4)
        val identity=auth.verifyCurrentRevision(body["access_token"].asString())
        assertThat(identity.revision).isEqualTo(4)
        assertThat(identity.memberId).isEqualTo(member)
        val parts=body["access_token"].asString().split('.')
        val header=mapper.readTree(String(Base64.getUrlDecoder().decode(parts[0])))
        assertThat(header["alg"].asString()).isEqualTo("RS256")
        assertThat(header["typ"].asString()).isEqualTo("JWT")
        assertThat(header["kid"].asString()).isNotBlank()
        val claims=mapper.readTree(String(Base64.getUrlDecoder().decode(parts[1])))
        assertThat(claims.propertyNames()).containsExactlyInAnyOrder("iss","aud","sub","tenant_id","role","sid","manifest_revision","iat","exp","jti")
        assertThat(claims["sub"].asString()).isEqualTo(member.toString())
        assertThat(claims["tenant_id"].asString()).isEqualTo(tenant.toString())
        assertThat(claims["role"].asString()).isIn("owner","admin","member")
        assertThat(UUID.fromString(claims["sid"].asString())).isNotNull()
        assertThat(claims["manifest_revision"].isIntegralNumber).isTrue()
        assertThat(claims["manifest_revision"].asInt()).isEqualTo(4)
        assertThat(claims["exp"].asLong()-claims["iat"].asLong()).isEqualTo(300)
        assertThat(claims["jti"].asString()).isNotBlank()
        assertThatThrownBy { auth.verifyCurrentRevision(old["access_token"].asString()) }
            .isInstanceOfSatisfying(UserAuthException::class.java) { assertThat(it.code).isEqualTo("manifest_revision_mismatch") }
        assertThat(revision()).isEqualTo(4)
        assertThat(get().statusCode()).isEqualTo(401)
        assertThat(get(body["refresh_token"].asString()).statusCode()).isEqualTo(401)
    }

    @Test fun `AT와 설치 토큰은 재동기화 자격증명이 아니다`() {
        for(t in listOf(old["access_token"].asString(),"pit_"+"a".repeat(43),"ptt_"+"a".repeat(43),""))
            assertThat(get(t).statusCode()).isEqualTo(401)
        val missing=http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/manifest")).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertThat(missing.statusCode()).isEqualTo(401)
        assertThat(get().statusCode()).isEqualTo(200)
    }

    @Test fun `활성 정책이 없거나 파싱 검증이 실패하면 RT 소비까지 롤백한다`() {
        update("UPDATE enrollment.manifests SET is_active=false")
        assertThat(get().statusCode()).isEqualTo(409)
        update("UPDATE enrollment.manifests SET is_active=true")
        val original=jdbc.sql("SELECT manifest::text FROM enrollment.manifests").query(String::class.java).single()
        for(json in listOf("{}",original.dropLast(1)+",\"unexpected\":true}",original.replace("https://otlp.pulsemetry.example.com","http://untrusted.example.com"), original.replace("\"collect_user_prompts\": false,", ""), original.replace("\"logs\": false", "\"logs\": \"false\""))) {
            jdbc.sql("UPDATE enrollment.manifests SET manifest=CAST(:json AS jsonb), version=4").param("json",json).update()
            val r=get()
            assertThat(r.statusCode()).isEqualTo(409)
            assertThat(mapper.readTree(r.body())["error"].asString()).isEqualTo("manifest_not_configured")
            assertThat(revision()).isEqualTo(3)
            assertThat(refreshCount()).isEqualTo(1)
        }
        jdbc.sql("UPDATE enrollment.manifests SET manifest=CAST(:json AS jsonb)").param("json",original).update()
        assertThat(get().statusCode()).isEqualTo(200)
    }

    @Test fun `서명 실패는 session revision과 RT 소비를 모두 롤백한다`() {
        update("UPDATE enrollment.manifests SET version=4")
        val identity = auth.verify(old["access_token"].asString()).copy(revision = 4)
        doThrow(IllegalStateException("test signing failure")).`when`(jwt).issue(identity)
        val r=get()
        assertThat(r.statusCode()).isEqualTo(503)
        assertThat(r.headers().firstValue("Retry-After")).isPresent()
        assertThat(r.body()).doesNotContain("test signing failure",old["refresh_token"].asString())
        assertThat(revision()).isEqualTo(3)
        assertThat(refreshCount()).isEqualTo(1)
        reset(jwt)
        assertThat(get().statusCode()).isEqualTo(200)
    }

    @Test fun `새 RT INSERT 실패도 소비와 session revision을 되돌린다`() {
        update("UPDATE enrollment.manifests SET version=4")
        update("""CREATE FUNCTION enrollment.reject_new_refresh() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test insert failure''; END'""")
        update("CREATE TRIGGER reject_new_refresh BEFORE INSERT ON enrollment.user_refresh_tokens FOR EACH ROW EXECUTE FUNCTION enrollment.reject_new_refresh()")
        try {
            assertThat(get().statusCode()).isEqualTo(503)
            assertThat(revision()).isEqualTo(3)
            assertThat(refreshCount()).isEqualTo(1)
        } finally {
            update("DROP TRIGGER reject_new_refresh ON enrollment.user_refresh_tokens")
            update("DROP FUNCTION enrollment.reject_new_refresh()")
        }
        assertThat(get().statusCode()).isEqualTo(200)
    }

    @Test fun `동시 재동기화는 하나만 성공하고 중복은 세션을 폐기한다`() {
        val gate=CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val futures=(1..2).map { pool.submit(Callable { gate.await();get() }) };gate.countDown()
            val responses=futures.map { it.get() }
            assertThat(responses.map { it.statusCode() }).containsExactlyInAnyOrder(200,401)
            val winner=mapper.readTree(responses.first { it.statusCode()==200 }.body())
            assertThat(get(winner["refresh_token"].asString()).statusCode()).isEqualTo(401)
        }
    }

    @Test fun `정책 활성화는 응답의 정책과 토큰을 다른 revision으로 섞지 못한다`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val identity = auth.verify(old["access_token"].asString())
        doAnswer { invocation ->
            entered.countDown();check(release.await(10,TimeUnit.SECONDS));invocation.callRealMethod()
        }.`when`(jwt).issue(identity)
        Executors.newFixedThreadPool(2).use { pool ->
            val resync=pool.submit(Callable { get() })
            try {
                check(entered.await(10,TimeUnit.SECONDS))
                val writer=pool.submit(Callable { TransactionTemplate(manager).executeWithoutResult {
                    update("UPDATE enrollment.manifests SET is_active=false WHERE is_active=true")
                    data.activeManifest(tenant,member,4)
                } })
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                while(data.blockedSessionQueries().none { it.contains("UPDATE enrollment.manifests") } && System.nanoTime()<deadline) Thread.sleep(20)
                assertThat(data.blockedSessionQueries().any { it.contains("UPDATE enrollment.manifests") }).isTrue()
                release.countDown()
                val r=resync.get(10,TimeUnit.SECONDS);writer.get(10,TimeUnit.SECONDS)
                assertThat(r.statusCode()).isEqualTo(200)
                val body=mapper.readTree(r.body())
                assertThat(body["manifest"]["config_revision"].asInt()).isEqualTo(3)
                assertThat(auth.verify(body["access_token"].asString()).revision).isEqualTo(3)
                reset(jwt)
                val next=get(body["refresh_token"].asString())
                assertThat(next.statusCode()).isEqualTo(200)
                assertThat(mapper.readTree(next.body())["manifest"]["config_revision"].asInt()).isEqualTo(4)
            } finally { release.countDown() }
        }
    }

    @Test fun `응답을 잃은 RT를 재시도하지 않고 재로그인으로 복구한다`() {
        assertThat(get().statusCode()).isEqualTo(200) // 클라이언트가 응답 봉투를 저장하지 않은 상황.
        assertThat(get().statusCode()).isEqualTo(401)
        val login=post("login",mapOf("tenant_id" to tenant,"email" to "user@example.com","password" to "correct-password-123"))
        assertThat(login.statusCode()).isEqualTo(200)
        assertThat(get(mapper.readTree(login.body())["refresh_token"].asString()).statusCode()).isEqualTo(200)
    }

    companion object {
        @JvmStatic @DynamicPropertySource fun config(registry:DynamicPropertyRegistry)=AbstractUserAuthApiTest.config(registry)
    }
}
