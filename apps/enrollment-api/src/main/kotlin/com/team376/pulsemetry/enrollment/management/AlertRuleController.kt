package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.persistence.enrollment.alert.AlertList
import com.team376.pulsemetry.persistence.enrollment.alert.AlertRuleState
import com.team376.pulsemetry.persistence.enrollment.alert.AlertRuleStore
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * 알림 규칙의 켜기·끄기와 모델·도구 목록의 교체 (ADR 0051 §3). 평가·알림은 여기 없다.
 *
 * - 규칙 응답은 설정 조회의 `alertRules` 항목과 같은 모양이다. 근거가 없는 규칙을 켜면 422 `alert_rule_unavailable` 이고 `details.reason` 이 사유다.
 * - 목록 응답은 `{list, alertRules}` — 목록을 채우면 기대는 규칙이 켤 수 있게 되므로 네 규칙의 현재 상태를 같이 준다.
 * - PATCH·PUT 이고 판이 재시도를 막으므로 `Idempotency-Key` 를 받지 않는다.
 */
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}/settings")
class AlertRuleController(private val auth: UserAuthService, private val store: AlertRuleStore) {

    @PatchMapping("/alert-rules/{ruleId}")
    fun rule(@PathVariable organizationId: UUID, @PathVariable ruleId: String, @RequestBody body: JsonNode, request: HttpServletRequest): ResponseEntity<*> {
        val actor = managementActor(auth, organizationId, request)
        val expected = expectedVersion(body)
        val enabled = body.path("enabled").takeIf { it.isBoolean }?.asBoolean() ?: invalid("enabled")
        val rule = store.setEnabled(organizationId, actor, ruleId, expected, enabled)
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(wire(rule))
    }

    @PutMapping("/alert-lists/{listId}")
    fun list(@PathVariable organizationId: UUID, @PathVariable listId: String, @RequestBody body: JsonNode, request: HttpServletRequest): ResponseEntity<*> {
        val actor = managementActor(auth, organizationId, request)
        val expected = expectedVersion(body)
        val entries = body.path("entries").takeIf { it.isArray && it.toList().all { entry -> entry.isString } }?.toList()?.map { it.asString() } ?: invalid("entries")
        val (list, rules) = store.replaceList(organizationId, actor, listId, expected, entries)
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(mapOf("list" to wire(list), "alertRules" to rules.map(::wire)))
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

        fun wire(list: AlertList): Map<String, Any?> = linkedMapOf(
            "listId" to list.listId, "version" to list.version, "entries" to list.entries, "updatedAt" to list.updatedAt?.toString(),
        )
    }
}
