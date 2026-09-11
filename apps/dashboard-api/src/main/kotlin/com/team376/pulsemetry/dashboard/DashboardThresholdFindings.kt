package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode
import java.time.Instant

/** S1-5/S7-1 운영 규칙: 임계값 초과를 검토 신호로 제공한다. 판정식 문자열은 실행하지 않는다. */
internal object DashboardThresholdFindings {
    fun evaluate(scenario: String, result: JsonNode, threshold: Double): List<Map<String,Any>> {
        require(scenario in setOf("S1-5","S7-1"))
        val context = scenario=="S1-5"
        val rule = if(context) "high_io_ratio" else "high_tool_failure_rate"
        val title = if(context) "입력/출력 토큰 비율이 임계값을 초과했습니다" else "도구 실패율이 임계값을 초과했습니다"
        val limitation = if(context) "컨텍스트 첨부 여부는 관측하지 못합니다. 입력 토큰 사용을 검토하세요."
            else "도구 호출 실패율이며 에이전트 태스크 완료 여부나 성공률을 직접 측정하지 않습니다."
        val findings = mutableListOf<Map<String,Any>>()
        for (frame in result["frames"].toList()) {
            val fields = frame["schema"]["fields"].toList()
            val value = fields.indexOfFirst { it["name"].asString()=="value" }
            val time = fields.indexOfFirst { it["type"].asString()=="time" }
            if(value<0 || time<0 || fields[value].path("config").path("suppressed").asBoolean(false)) continue
            frame["data"]["values"][value].toList().forEachIndexed { i, cell ->
                if(cell.isNumber && cell.asDouble().isFinite() && cell.asDouble()>threshold) findings += mapOf(
                    "rule_id" to rule, "severity" to "info", "widget_id" to if(context) "W2.6" else "W2.10",
                    "title" to title,
                    "evidence" to mapOf("date" to Instant.ofEpochMilli(frame["data"]["values"][time][i].asLong()).toString(),
                        "ratio" to cell.asDouble(), "threshold" to threshold,
                        "limitation" to limitation))
            }
        }
        return findings
    }
}
