package com.team376.pulsemetry.enrollment.seat

import com.team376.pulsemetry.connector.vendor.ClaudeEnterpriseConnector
import com.team376.pulsemetry.connector.vendor.ConnectionTarget
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors
import com.team376.pulsemetry.connector.vendor.ConnectorFailure
import com.team376.pulsemetry.connector.vendor.CopilotConnector
import com.team376.pulsemetry.connector.vendor.CursorEnterpriseConnector
import com.team376.pulsemetry.connector.vendor.GeminiConnector
import com.team376.pulsemetry.connector.vendor.HttpPolicy
import com.team376.pulsemetry.connector.vendor.SeatConnectors
import com.team376.pulsemetry.connector.vendor.VendorHttp
import com.team376.pulsemetry.enrollment.management.ManagementProperties
import com.team376.pulsemetry.persistence.enrollment.seat.CredentialCipher
import com.team376.pulsemetry.persistence.enrollment.seat.CredentialKeyUnavailable
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger
import com.team376.pulsemetry.persistence.enrollment.seat.VendorConnectionStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 벤더 연결과 좌석 동기화 설정 (ADR 0048 §6·§7). 켜면 관리 기능도 켜야 하고, 아래 값은 기준 주소를 빼고 모두 기본값 없는 필수값이다 — 없으면 기동이 실패한다.
 */
@ConfigurationProperties("pulsemetry.vendor-connections")
class VendorConnectionProperties {
    var enabled = false
    /** 키 ID → Base64 32바이트. 옛 키는 그 키로 암호화된 연결이 남아 있는 동안 둔다 */
    var credentialKeys: Map<String, String> = emptyMap()
    /** 새 암호문을 만드는 키의 ID */
    var credentialKeyId = ""
    var sync = Sync()
    var http = Http()
    /** 커넥터 ID → 벤더 API 기준 주소. 비우면 벤더 문서의 공식 주소다(모의 서버·스테이징에서만 바꾼다) */
    var baseUrls: Map<String, String> = emptyMap()
    /** Gemini 의 토큰 엔드포인트. 비우면 `https://oauth2.googleapis.com/token` */
    var geminiTokenUrl = ""

    class Sync {
        /** 연결 하나를 다시 동기화하는 간격(마지막 시도부터) */
        var interval: Duration? = null
        /** 동기화할 차례인 연결을 찾는 주기. 관리자의 "지금 동기화" 요청이 기다리는 최대 시간이기도 하다 */
        var checkInterval: Duration? = null
        /** 한 연결의 선점 기한. 한 번의 동기화(모든 페이지·재시도)보다 길어야 한다 */
        var lease: Duration? = null
    }

    class Http {
        /** 벤더 호출 하나의 시간 제한 */
        var requestTimeout: Duration? = null
        /** 호출 하나의 최대 시도 횟수(첫 시도 포함) */
        var maxAttempts: Int? = null
        /** 벤더가 대기 시간을 알려 주지 않은 일시 장애 뒤의 대기 */
        var retryBackoff: Duration? = null
        /** 벤더가 알려 준 대기 시간의 상한. 넘으면 기다리지 않고 실패로 남긴다 */
        var maxRetryWait: Duration? = null
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.vendor-connections", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(VendorConnectionProperties::class)
class SeatSyncConfig {
    @Bean
    fun vendorConnectionStore(jdbc: JdbcClient, manager: PlatformTransactionManager, clock: Clock, mapper: ObjectMapper,
        properties: VendorConnectionProperties, management: ObjectProvider<ManagementProperties>): VendorConnectionStore {
        requireNotNull(management.ifAvailable) { "pulsemetry.vendor-connections 는 pulsemetry.management 를 함께 켜야 한다" }
        require(properties.credentialKeyId.isNotBlank()) { missing("credential-key-id") }
        return VendorConnectionStore(jdbc, manager, clock, CredentialCipher(properties.credentialKeys, properties.credentialKeyId), mapper)
    }

    @Bean
    fun seatLedger(jdbc: JdbcClient, manager: PlatformTransactionManager, clock: Clock) = SeatLedger(jdbc, manager, clock)

    /** 벤더 문서에 근거가 있는 커넥터 넷. 기준 주소는 설정으로 바꿀 수 있다. */
    @Bean
    fun seatConnectors(properties: VendorConnectionProperties, clock: Clock): SeatConnectors {
        val http = properties.http
        val vendorHttp = VendorHttp(HttpPolicy(positive(http.requestTimeout, "http.request-timeout"),
            requireNotNull(http.maxAttempts) { missing("http.max-attempts") }.also { require(it >= 1) { "pulsemetry.vendor-connections.http.max-attempts 는 1 이상이어야 한다" } },
            nonNegative(http.retryBackoff, "http.retry-backoff"), nonNegative(http.maxRetryWait, "http.max-retry-wait")), clock = clock)
        val unknown = properties.baseUrls.keys - ConnectorDescriptors.ALL.map { it.id }.toSet()
        require(unknown.isEmpty()) { "pulsemetry.vendor-connections.base-urls 에 모르는 커넥터가 있다: $unknown" }
        fun base(id: String, default: String) = URI(properties.baseUrls[id]?.takeIf { it.isNotBlank() } ?: default)
        return SeatConnectors(listOf(
            ClaudeEnterpriseConnector(vendorHttp, base(ConnectorDescriptors.CLAUDE_ENTERPRISE.id, "https://api.anthropic.com")),
            CursorEnterpriseConnector(vendorHttp, base(ConnectorDescriptors.CURSOR_ENTERPRISE.id, "https://api.cursor.com")),
            CopilotConnector(vendorHttp, base(ConnectorDescriptors.COPILOT.id, "https://api.github.com")),
            GeminiConnector(vendorHttp, base(ConnectorDescriptors.GEMINI.id, "https://cloudcommerceconsumerprocurement.googleapis.com"),
                URI(properties.geminiTokenUrl.takeIf { it.isNotBlank() } ?: GeminiConnector.TOKEN_AUDIENCE), clock),
        ))
    }

    @Bean
    fun seatSynchronizer(ledger: SeatLedger, connections: VendorConnectionStore, connectors: SeatConnectors, properties: VendorConnectionProperties): SeatSynchronizer {
        val interval = positive(properties.sync.interval, "sync.interval")
        val lease = positive(properties.sync.lease, "sync.lease")
        val worker = "enrollment-api-${ProcessHandle.current().pid()}-${UUID.randomUUID().toString().take(8)}"
        return SeatSynchronizer(ledger, connections, connectors, interval, lease, worker)
    }

    @Bean
    fun seatSyncJob(synchronizer: SeatSynchronizer, properties: VendorConnectionProperties) =
        SeatSyncJob(positive(properties.sync.checkInterval, "sync.check-interval")) { synchronizer.runOnce() }

    private fun missing(key: String) = "pulsemetry.vendor-connections.$key 가 비어 있다"
    private fun positive(value: Duration?, key: String): Duration =
        requireNotNull(value) { missing(key) }.also { require(!it.isNegative && !it.isZero) { "pulsemetry.vendor-connections.$key 는 0보다 커야 한다" } }
    private fun nonNegative(value: Duration?, key: String): Duration =
        requireNotNull(value) { missing(key) }.also { require(!it.isNegative) { "pulsemetry.vendor-connections.$key 는 음수일 수 없다" } }
}

/**
 * 좌석 동기화 한 바퀴 (ADR 0048 §3·§7). 차례인 연결마다: 선점 → 자격증명 복호화 → 계약 플랜의 커넥터 확인 → 좌석 목록 → 원장 반영.
 * 실패는 실행 기록·연결의 실패 상태·요청 작업에 분류 코드로만 남고 원장은 바꾸지 않는다. **한 연결의 실패가 다른 연결을 막지 않는다.**
 *
 * | 실패 | 코드 |
 * | --- | --- |
 * | 커넥터 호출 | 커넥터 실패 종류(`invalid_credentials`·`insufficient_permission`·`vendor_rejected`·`rate_limited`·`vendor_unavailable`·`invalid_response` 등) |
 * | 계약 플랜이 연결의 커넥터와 다름(계약을 비웠거나 다른 플랜으로 정정) | `plan_mismatch` |
 * | 이 배포에 그 커넥터 구현이 없음 | `connector_unavailable` |
 * | 행의 암호화 키가 설정에 없음 | `credential_key_unavailable` |
 * | 목록을 원장에 넣을 수 없음(계정 키 형식·중복) | `invalid_listing` |
 * | 그 밖의 예외 | `sync_error` |
 */
class SeatSynchronizer(
    private val ledger: SeatLedger,
    private val connections: VendorConnectionStore,
    private val connectors: SeatConnectors,
    private val interval: Duration,
    private val lease: Duration,
    private val worker: String,
) {
    /** 한 바퀴의 결과 — 반영한 연결 수와 실패한 연결 수. 선점하지 못한 연결은 세지 않는다. */
    data class Round(val applied: Int, val failed: Int)

    fun runOnce(): Round {
        ledger.abandonRemovedRequests()
        var applied = 0
        var failed = 0
        ledger.dueConnections(interval).forEach { (tenant, connection) ->
            when (syncOne(tenant, connection)) {
                is SeatLedger.SyncResult.Applied -> applied++
                null -> Unit
                else -> failed++
            }
        }
        return Round(applied, failed)
    }

    /** 연결 하나. 선점하지 못하면 null, 실패는 [SeatLedger.SyncResult.Rejected] 로 돌려준다. */
    fun syncOne(tenant: UUID, connectionId: UUID): SeatLedger.SyncResult? {
        val run = try { ledger.startRun(tenant, connectionId, worker, lease) } catch (error: Exception) {
            log.warn("좌석 동기화를 선점하지 못했다 connection={} error={}", connectionId, error.javaClass.simpleName)
            return null
        } ?: return null
        val error = try {
            val (record, credential) = connections.credential(tenant, connectionId) ?: return ledger.failRun(run, "claim_lost").let { SeatLedger.SyncResult.Lost }
            val expected = try { connections.view(tenant, record.vendorId).connector?.connectorId } catch (_: Exception) { null }
            val connector = connectors.byId(record.connector)
            when {
                expected != record.connector -> "plan_mismatch"
                connector == null -> "connector_unavailable"
                else -> return ledger.applyListing(run, connector.listSeats(ConnectionTarget(record.settings, credential)))
            }
        } catch (failure: ConnectorFailure) {
            failure.kind.wire
        } catch (_: CredentialKeyUnavailable) {
            "credential_key_unavailable"
        } catch (error: Exception) {
            // 예외 원문에는 벤더 응답·바인딩 값이 섞일 수 있어 종류만 남긴다.
            log.warn("좌석 동기화가 예외로 끝났다 connection={} error={}", connectionId, error.javaClass.simpleName)
            "sync_error"
        }
        log.info("좌석 동기화 실패 connection={} error={}", connectionId, error)
        return if (ledger.failRun(run, error)) SeatLedger.SyncResult.Rejected(error) else SeatLedger.SyncResult.Lost
    }

    private companion object {
        val log = LoggerFactory.getLogger(SeatSynchronizer::class.java)
    }
}

/** 동기화의 주기 실행. 한 인스턴스 안에서는 한 번에 하나만 돈다(앞 실행이 끝난 뒤 [interval] 만큼 쉰다). 여러 인스턴스는 선점이 가른다. */
class SeatSyncJob(private val interval: Duration, private val run: () -> Unit) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    @Synchronized override fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "seat-sync").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                try { run() } catch (error: Exception) { log.warn("좌석 동기화 작업이 실패했다 error={}", error.javaClass.simpleName) }
            }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    @Synchronized override fun isRunning() = executor != null

    private companion object {
        val log = LoggerFactory.getLogger(SeatSyncJob::class.java)
    }
}
