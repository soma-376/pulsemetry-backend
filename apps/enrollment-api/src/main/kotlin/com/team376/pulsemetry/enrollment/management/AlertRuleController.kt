package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.persistence.enrollment.alert.AlertRuleState
import com.team376.pulsemetry.persistence.enrollment.alert.AlertRuleStore
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

/** 알림 규칙의 켜기·끄기와 확인. 등록 제품 기준은 허브 ADR 0008, 평가는 dashboard-api의 주기 작업이 한다. */
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class AlertRuleController(private val auth: UserAuthService, private val store: AlertRuleStore) {

    @PatchMapping("/settings/alert-rules/{ruleId}")
    fun rule(@PathVariable organizationId: UUID, @PathVariable ruleId: String, @RequestBody body: JsonNode, request: HttpServletRequest): ResponseEntity<*> {
        val actor = managementActor(auth, organizationId, request)
        val expected = expectedVersion(body)
        val enabled = body.path("enabled").takeIf { it.isBoolean }?.asBoolean() ?: invalid("enabled")
        val rule = store.setEnabled(organizationId, actor, ruleId, expected, enabled)
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(wire(rule))
    }

    /** 알림 확인 (ADR 0051 §6). 이미 확인한 알림은 그 기록을 그대로 돌려준다 — 다시 보내도 같다. */
    @PostMapping("/alerts/{alertId}/acknowledge")
    fun acknowledge(@PathVariable organizationId: UUID, @PathVariable alertId: String, @RequestBody body: JsonNode, request: HttpServletRequest): ResponseEntity<*> {
        val actor = managementActor(auth, organizationId, request)
        val id = runCatching { UUID.fromString(alertId) }.getOrNull() ?: throw ManagementException("not_found", 404)
        val ack = store.acknowledge(organizationId, actor, id, expectedVersion(body))
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(linkedMapOf("alertId" to ack.alertId.toString(), "version" to ack.version,
            "acknowledgedAt" to ack.acknowledgedAt.toString(), "acknowledgedBy" to ack.acknowledgedBy.toString()))
    }

    private fun expectedVersion(body: JsonNode): Long =
        body.path("expectedVersion").takeIf { it.isIntegralNumber && it.asLong() >= 0 }?.asLong() ?: invalid("expectedVersion")

    private fun invalid(field: String): Nothing = throw ManagementException("invalid_request", 400, field)

    companion object {
        /** 설정 조회(dashboard-api)의 `alertRules` 항목과 같은 키다. */
        fun wire(rule: AlertRuleState): Map<String, Any?> = linkedMapOf(
            "ruleId" to rule.ruleId,
            "version" to rule.version,
            "enabled" to rule.enabled,
            "availability" to if (rule.available) "available" else "unavailable",
            "reason" to rule.reason,
            "threshold" to linkedMapOf("value" to rule.thresholdValue.toDouble(), "unit" to rule.thresholdUnit),
            "evaluationWindow" to rule.evaluationWindow,
            "comparisonWindow" to rule.comparisonWindow,
        )

    }
}
