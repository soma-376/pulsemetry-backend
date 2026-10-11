package com.team376.pulsemetry.enrollment.installation

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.team376.pulsemetry.enrollment.contract.ErrorResponse
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.service.InstallationCredentialVerifier
import com.team376.pulsemetry.persistence.enrollment.installation.CollectionMode
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationReport
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationReportStore
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationManifestAssignmentRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.ManifestRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 설치 보고 수신 설정 (ADR 0040). 켰을 때 세 값은 기본값이 없는 필수값이다. */
@ConfigurationProperties("pulsemetry.heartbeat")
class HeartbeatProperties {
    var enabled: Boolean = false
    /** 응답으로 데몬에 주는 보고 주기. 계약의 범위(60초~3600초) 안이어야 한다 */
    var reportInterval: Duration? = null
    /** 저장소 장애(503)의 `Retry-After` */
    var retryAfter: Duration? = null
    /** 수집 구간 이력을 남겨 두는 기간 */
    var historyRetention: Duration? = null
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.heartbeat", name = ["enabled"], havingValue = "true")
class HeartbeatConfig {
    @Bean
    fun heartbeatService(properties: HeartbeatProperties, verifier: InstallationCredentialVerifier, installations: InstallationRepository,
        manifests: ManifestRepository, assignments: InstallationManifestAssignmentRepository, jdbc: JdbcClient, manager: PlatformTransactionManager,
        clock: Clock): HeartbeatService {
        val interval = requireNotNull(properties.reportInterval) { "pulsemetry.heartbeat.report-interval 이 비어 있다" }
        require(interval.toSeconds() in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS && interval.toNanosPart() == 0) {
            "pulsemetry.heartbeat.report-interval 은 ${MIN_INTERVAL_SECONDS}초 이상 ${MAX_INTERVAL_SECONDS}초 이하의 초 단위 값이어야 한다"
        }
        val retryAfter = requireNotNull(properties.retryAfter) { "pulsemetry.heartbeat.retry-after 가 비어 있다" }
        require(retryAfter.toSeconds() >= 1) { "pulsemetry.heartbeat.retry-after 는 1초 이상이어야 한다" }
        val retention = requireNotNull(properties.historyRetention) { "pulsemetry.heartbeat.history-retention 이 비어 있다" }
        require(retention >= Duration.ofDays(1)) { "pulsemetry.heartbeat.history-retention 은 하루 이상이어야 한다" }
        return HeartbeatService(verifier, installations, manifests, assignments, InstallationReportStore(jdbc, retention),
            TransactionTemplate(manager), clock, interval.toSeconds().toInt(), retryAfter.toSeconds())
    }

    private companion object {
        /** 계약이 정한 주기의 범위 (허브 `contracts/enrollment-api.md` §7). */
        const val MIN_INTERVAL_SECONDS = 60L
        const val MAX_INTERVAL_SECONDS = 3600L
    }
}

/**
 * 설치 보고의 검증과 기록 (ADR 0040, 허브 ADR 0010).
 *
 * 인증은 설치 자격증명이고 판정은 토큰 재발급과 같다([InstallationCredentialVerifier]). 경로의 설치가 자격증명의 설치와 다르면 403 이다.
 * 생존 시각은 서버가 받은 시각이다. 보고한 manifest 판이 그 조직의 판일 때만 적용 확인을 기록한다 — 모르는 판이어도 생존은 기록한다.
 */
class HeartbeatService(
    private val verifier: InstallationCredentialVerifier,
    private val installations: InstallationRepository,
    private val manifests: ManifestRepository,
    private val assignments: InstallationManifestAssignmentRepository,
    private val store: InstallationReportStore,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val reportIntervalSeconds: Int,
    val retryAfterSeconds: Long,
) {
    /** 본문을 읽기 전에 부른다. 자격증명이 없거나 무효면 401 이다. */
    fun authenticate(authorization: String?) {
        verifier.credential(authorization)
    }

    fun record(authorization: String?, installationId: String, report: InstallationReport): HeartbeatResponse = requireNotNull(tx.execute {
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        // 설치 행을 잠근다. 같은 설치의 보고는 하나씩 처리된다.
        val installation = verifier.verify(authorization).installation
        val id = installation.id
        if (id.toString() != installationId.lowercase()) throw EnrollmentException.forbidden()
        val tenantId = installation.tenantId
        val enrolledAt = installation.createdAt

        val applied = manifests.findByTenantIdAndVersion(tenantId, report.appliedConfigRevision)
        val expected = manifests.findByTenantIdAndIsActiveTrue(tenantId)?.version
        val appliedId = applied?.id
        val acknowledged = applied?.version

        installations.recordSeen(id, now, report.daemonVersion.take(CLIENT_VERSION_LENGTH))
        if (appliedId != null) assignments.acknowledge(id, appliedId, now)
        store.record(id, enrolledAt, report, appliedId, now)
        HeartbeatResponse(now.toString(), expected, acknowledged, reportIntervalSeconds)
    })

    private companion object {
        /** `installations.client_version` 의 길이. */
        const val CLIENT_VERSION_LENGTH = 50
    }
}

/** `null` 도 키로 나간다 — 계약의 네 키는 모두 필수다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class HeartbeatResponse(
    @JsonProperty("received_at") val receivedAt: String,
    @JsonProperty("expected_config_revision") val expectedConfigRevision: Int?,
    @JsonProperty("acknowledged_config_revision") val acknowledgedConfigRevision: Int?,
    @JsonProperty("report_interval_seconds") val reportIntervalSeconds: Int,
)

@RestController
@ConditionalOnProperty(prefix = "pulsemetry.heartbeat", name = ["enabled"], havingValue = "true")
class HeartbeatController(private val service: HeartbeatService, private val mapper: ObjectMapper) {
    @PostMapping("/api/v1/installations/{installationId}/heartbeat")
    fun heartbeat(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable installationId: String,
        request: HttpServletRequest,
    ): ResponseEntity<HeartbeatResponse> {
        // 인증되지 않은 요청의 본문은 읽지 않는다. 본문을 읽는 동안에는 트랜잭션도 잠금도 잡지 않는다.
        service.authenticate(authorization)
        val report = HeartbeatRequests.parse(mapper, request)
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.record(authorization, installationId, report))
    }
}

/** 저장소 장애는 401·403 이 아니다 — 데몬이 재등록이 필요하다고 오해한다. 503 과 `Retry-After` 로 답한다. */
@Order(-10)
@RestControllerAdvice(assignableTypes = [HeartbeatController::class])
@ConditionalOnProperty(prefix = "pulsemetry.heartbeat", name = ["enabled"], havingValue = "true")
class HeartbeatErrors(private val service: HeartbeatService) {
    @ExceptionHandler(DataAccessException::class, TransactionException::class)
    fun unavailable(): ResponseEntity<ErrorResponse> = ResponseEntity.status(503)
        .header(HttpHeaders.RETRY_AFTER, service.retryAfterSeconds.toString()).header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(ErrorResponse("heartbeat_unavailable", "설치 보고를 기록하지 못했습니다. 잠시 후 다시 시도합니다."))
}

/**
 * 요청 본문의 해석. 명세(`docs/enrollment-server-spec.md` §4.5)의 요청 표가 받는 것을 받고 거부하는 것을 거부한다.
 * 모르는 키는 무시한다. 타입을 바꿔 읽지 않는다 — 문자열 `"3"` 은 정수 3 이 아니다.
 */
internal object HeartbeatRequests {
    private const val MAX_BODY_BYTES = 16 * 1024
    private val TIMESTAMP = Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z""")
    private val RUN_ID = Regex("[A-Za-z0-9_-]{16,64}")
    private val PLATFORMS = setOf("darwin", "linux", "windows")

    fun parse(mapper: ObjectMapper, request: HttpServletRequest): InstallationReport {
        if (request.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") throw invalid()
        val bytes = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) throw invalid()
        val root = try { mapper.readTree(bytes) } catch (_: JacksonException) { throw invalid() }
        return parse(root)
    }

    fun parse(root: JsonNode?): InstallationReport {
        val body = obj(root)
        val daemon = obj(body.get("daemon"))
        val collection = obj(body.get("collection"))
        val sentAt = time(body.get("sent_at"))
        if (text(daemon.get("platform"), 16) !in PLATFORMS) throw invalid()
        val runId = text(daemon.get("run_id"), 64)
        if (!RUN_ID.matches(runId)) throw invalid()
        val revision = count(body.get("applied_config_revision"))
        if (revision !in 1..Int.MAX_VALUE) throw invalid()
        val mode = CollectionMode.of(text(collection.get("mode"), 16)) ?: throw invalid()
        val forwarding = collection.get("forwarding")?.takeIf { it.isBoolean }?.booleanValue() ?: throw invalid()
        val receivingSince = timeOrNull(collection, "receiving_since")
        val lastDeliveredAt = timeOrNull(collection, "last_delivered_at")
        // 본문의 어떤 시각도 보낸 시각보다 뒤일 수 없다.
        if (receivingSince?.isAfter(sentAt) == true || lastDeliveredAt?.isAfter(sentAt) == true) throw invalid()
        return InstallationReport(sentAt, text(daemon.get("version"), 64), text(daemon.get("architecture"), 32), runId, revision.toInt(), mode,
            receivingSince, forwarding, count(collection.get("delivered")), count(collection.get("lost")), count(collection.get("pending")), lastDeliveredAt)
    }

    private fun obj(node: JsonNode?): JsonNode = node?.takeIf { it.isObject } ?: throw invalid()

    private fun text(node: JsonNode?, max: Int): String =
        node?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotEmpty() && it.length <= max } ?: throw invalid()

    /** 0 이상의 정수. JSON Schema 의 integer 와 같이 `3.0` 도 정수다. 소수부가 있거나 범위를 넘으면 거부한다. */
    private fun count(node: JsonNode?): Long {
        val number = node?.takeIf { it.isNumber }?.decimalValue() ?: throw invalid()
        return try { number.longValueExact() } catch (_: ArithmeticException) { throw invalid() }.takeIf { it >= 0 } ?: throw invalid()
    }

    private fun time(node: JsonNode?): Instant {
        val value = node?.takeIf { it.isString }?.stringValue()?.takeIf(TIMESTAMP::matches) ?: throw invalid()
        return try { Instant.parse(value) } catch (_: DateTimeException) { throw invalid() }
    }

    /** 키는 있어야 한다. 값은 시각이거나 null 이다. */
    private fun timeOrNull(parent: JsonNode, name: String): Instant? {
        val node = parent.get(name) ?: throw invalid()
        return if (node.isNull) null else time(node)
    }

    private fun invalid() = EnrollmentException.malformedReport()
}
