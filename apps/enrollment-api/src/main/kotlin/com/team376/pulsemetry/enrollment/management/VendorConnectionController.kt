package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.connector.vendor.ConnectionTarget
import com.team376.pulsemetry.connector.vendor.ConnectorFailure
import com.team376.pulsemetry.connector.vendor.SeatConnectors
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.persistence.enrollment.seat.ConnectionCheck
import com.team376.pulsemetry.persistence.enrollment.seat.CredentialKeyUnavailable
import com.team376.pulsemetry.persistence.enrollment.seat.VendorConnectionStore
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * 등록 제품의 벤더 연결 — 추가·교체(PUT), 삭제(DELETE), 확인(POST verify) (ADR 0048 §6).
 *
 * - 자격증명은 요청 본문으로만 받고 응답·로그·오류에 싣지 않는다. PUT·DELETE 라 멱등 응답 기록(요청 해시·암호화 응답)을 남기지 않는다.
 * - 확인은 커넥터의 읽기 호출 하나다. 트랜잭션 밖에서 부르고, 그 사이 연결이 바뀌었으면 결과를 쓰지 않는다(409). 확인은 상태만 남기는 조회라 `Idempotency-Key` 를 받지 않는다.
 * - 응답은 `{seatSource}` — 설정 조회의 벤더 `seatSource` 와 같은 모양이다. ETag 는 `"connection-{판}"`.
 */
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.vendor-connections", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection")
class VendorConnectionController(private val auth: UserAuthService, private val store: VendorConnectionStore, private val connectors: SeatConnectors) {

    @PutMapping
    fun save(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @RequestBody body: JsonNode, request: HttpServletRequest): ResponseEntity<*> {
        val actor = managementActor(auth, organizationId, request)
        val expected = body.path("expectedVersion").takeIf { it.isIntegralNumber && it.asLong() >= 0 }?.asLong() ?: invalid("expectedVersion")
        val settings = body.path("settings").takeIf { it.isObject && it.properties().all { field -> field.value.isString } }
            ?.properties()?.associate { it.key to it.value.asString() } ?: invalid("settings")
        val credential = body.path("credential").takeIf { it.isString }?.asString() ?: invalid("credential")
        val saved = store.save(organizationId, actor, vendorId, settings, credential, expected) { product, plan -> connectors.forPlan(product, plan)?.descriptor }
        return respond(organizationId, vendorId, saved.version)
    }

    @DeleteMapping
    fun delete(@PathVariable organizationId: UUID, @PathVariable vendorId: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = managementActor(auth, organizationId, request)
        val version = request.getHeader("If-Match")?.let { Regex("\"connection-([0-9]+)\"").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() } ?: invalid("If-Match")
        store.delete(organizationId, actor, vendorId, version)
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build<Void>()
    }

    @PostMapping("/verify")
    fun verify(@PathVariable organizationId: UUID, @PathVariable vendorId: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = managementActor(auth, organizationId, request)
        val (connection, credential) = try { store.target(organizationId, actor, vendorId) }
            catch (_: CredentialKeyUnavailable) { throw ManagementException("credential_key_unavailable", 503) }
        val connector = connectors.byId(connection.connector) ?: throw ManagementException("connector_unavailable", 422, "vendorId")
        val check = try {
            connector.verify(ConnectionTarget(connection.settings, credential))
            ConnectionCheck.VERIFIED
        } catch (failure: ConnectorFailure) {
            when (failure.kind) {
                ConnectorFailure.Kind.INVALID_CREDENTIALS -> ConnectionCheck.INVALID_CREDENTIALS
                ConnectorFailure.Kind.INSUFFICIENT_PERMISSION -> ConnectionCheck.INSUFFICIENT_PERMISSION
                else -> ConnectionCheck.UNAVAILABLE
            }
        }
        val recorded = store.recordCheck(organizationId, vendorId, connection.version, check)
        return respond(organizationId, vendorId, recorded.version)
    }

    private fun respond(tenant: UUID, vendorId: String, version: Long): ResponseEntity<*> = ResponseEntity.ok().header("Cache-Control", "no-store")
        .eTag("connection-$version").body(mapOf("seatSource" to store.view(tenant, vendorId)))

    private fun invalid(field: String): Nothing = throw ManagementException("invalid_request", 400, field)
}
