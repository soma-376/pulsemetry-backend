package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode
import java.time.Instant

/** S1-5 운영 규칙: 임계값 초과를 검토 신호로 제공하며 컨텍스트 과다 첨부로 단정하지 않는다. */
internal object DashboardContextFindings {
    fun evaluate(result: JsonNode, threshold: Double): List<Map<String,Any>> {
        val findings = mutableListOf<Map<String,Any>>()
        for (frame in result["frames"].toList()) {
            val fields = frame["schema"]["fields"].toList()
            val value = fields.indexOfFirst { it["name"].asString()=="value" }
            val time = fields.indexOfFirst { it["type"].asString()=="time" }
            if(value<0 || time<0 || fields[value].path("config").path("suppressed").asBoolean(false)) continue
            frame["data"]["values"][value].toList().forEachIndexed { i, cell ->
                if(cell.isNumber && cell.asDouble().isFinite() && cell.asDouble()>threshold) findings += mapOf(
                    "rule_id" to "high_io_ratio", "severity" to "info", "widget_id" to "W2.6",
                    "title" to "입력/출력 토큰 비율이 임계값을 초과했습니다",
                    "evidence" to mapOf("date" to Instant.ofEpochMilli(frame["data"]["values"][time][i].asLong()).toString(),
                        "ratio" to cell.asDouble(), "threshold" to threshold,
                        "limitation" to "컨텍스트 첨부 여부는 관측하지 못합니다. 입력 토큰 사용을 검토하세요."))
            }
        }
        return findings
    }
}
