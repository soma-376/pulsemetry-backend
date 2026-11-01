package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class ManagementReplayCompatibilityTest : AbstractUserAuthApiTest() {
    @Autowired lateinit var executor: ManagementExecutor

    @Test fun `이전 배포 형식의 암호문과 요청 해시를 그대로 재생한다`() {
        val token = adminToken()
        val key = UUID.randomUUID().toString()
        val teamId = UUID.randomUUID()
        val response = """{"teamId":"$teamId","teamName":"기존 팀","version":7}"""
        // 구현의 암호화·정규화 도우미를 사용하지 않는 이전 저장 형식의 fixture다.
        val requestHash = MessageDigest.getInstance("SHA-256").digest("""{"teamName":"기존 팀"}""".toByteArray()).joinToString("") { "%02x".format(it) }
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("$tenant/$member/POST /teams/$key".toByteArray())
        val encrypted = Base64.getEncoder().encodeToString(nonce + cipher.doFinal(response.toByteArray()))
        jdbc.sql("INSERT INTO enrollment.management_commands VALUES (:tenant,:actor,'POST /teams',:key,:hash,:response,:expires)")
            .param("tenant", tenant).param("actor", member).param("key", key).param("hash", requestHash)
            .param("response", encrypted).param("expires", Timestamp.from(clock.now.plusSeconds(3600))).update()
        val replay = manage("POST", "/teams", mapOf("teamName" to "기존 팀"), token, key)
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(mapper.readTree(replay.body())).isEqualTo(mapper.readTree(response))
        assertThat(replay.headers().firstValue("Location").orElseThrow()).endsWith("/teams/$teamId")
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.teams").query(Int::class.java).single()).isZero()
        assertThat(manage("POST", "/teams", mapOf("teamName" to "다른 팀"), token, key).statusCode()).isEqualTo(409)
    }

    @Test fun `동시 재시도는 조직 잠금 아래 하나의 업무와 하나의 암호문만 남긴다`() {
        val token = adminToken()
        val key = UUID.randomUUID().toString()
        val gate = CountDownLatch(1)
        val responses = Executors.newFixedThreadPool(2).use { pool ->
            val tasks = (1..2).map { pool.submit(Callable { gate.await(); manage("POST", "/teams", mapOf("teamName" to "동시 팀"), token, key) }) }
            gate.countDown()
            tasks.map { it.get() }
        }
        assertThat(responses.map { it.statusCode() }).containsExactly(201, 201)
        assertThat(responses[0].body()).isEqualTo(responses[1].body())
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.teams").query(Int::class.java).single()).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.management_commands WHERE idempotency_key=:key").param("key", key).query(Int::class.java).single()).isEqualTo(1)
    }

    @Test fun `업무 뒤 응답 생성이 실패하면 업무 쓰기와 멱등 기록을 함께 롤백한다`() {
        adminToken()
        val id = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        assertThatThrownBy {
            executor.execute(tenant, member, "POST /teams", "hash", key, true) {
                jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name,created_at,updated_at) VALUES (:id,:tenant,'rollback',now(),now())")
                    .param("id", id).param("tenant", tenant).update()
                error("response failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.teams WHERE id=:id").param("id", id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.management_commands WHERE idempotency_key=:key").param("key", key).query(Int::class.java).single()).isZero()
    }
}
