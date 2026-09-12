package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode
import java.time.Instant

/** 입력된 분 단위 임계값과 관측 p90을 비교한다. Plan 여부나 전체 생산성 손실은 추정하지 않는다. */
internal object DashboardGateFindings {
    fun evaluate(result: JsonNode, thresholds: JsonNode): List<Map<String,Any>> {
        val limits = thresholds.toList().map { it.asDouble() }.distinct().sorted()
        return result["frames"].toList().flatMap { frame ->
            val fields = frame["schema"]["fields"].toList()
            val value = fields.indexOfFirst { it["name"].asString()=="p90" }
            val time = fields.indexOfFirst { it["type"].asString()=="time" }
            if(value<0 || time<0 || fields[value].path("config").path("suppressed").asBoolean(false)) emptyList()
            else frame["data"]["values"][value].toList().flatMapIndexed { i, cell ->
                if(!cell.isNumber || !cell.asDouble().isFinite()) emptyList()
                else limits.filter { cell.asDouble()/60000.0 > it }.map { threshold ->
                    mapOf("rule_id" to "high_gate_wait", "severity" to "info", "widget_id" to "W2.7",
                        "title" to "도구 승인 대기 p90이 입력 임계값을 초과했습니다",
                        "evidence" to mapOf("date" to Instant.ofEpochMilli(frame["data"]["values"][time][i].asLong()).toString(),
                            "p90_ms" to cell.asDouble(), "threshold_min" to threshold,
                            "dimensions" to fields[value].path("labels").properties().associate { it.key to it.value.asString() },
                            "limitation" to "팀별 관측 tool_gate 대기 시간입니다. 보조 지표는 선택 범위 전체 값입니다. Plan 전용 대기나 생산성 손실로 단정하지 않습니다."))
                }
            }
        }
    }
}
