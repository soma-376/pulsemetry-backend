package com.team376.pulsemetry.dashboard.finding

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 입력한 현지 날짜와 관측 세션을 연결한다. 주기성이나 인과 효과는 추정하지 않는다. */
internal object DashboardSprintFindings {
    fun evaluate(result: JsonNode, dates: JsonNode, zone: ZoneId): List<Map<String,Any>> {
        val selected = dates.toList().map { LocalDate.parse(it.asString()) }.toSet()
        val findings = mutableListOf<Map<String,Any>>()
        for(frame in result["frames"].toList()) {
            val fields = frame["schema"]["fields"].toList()
            val value = fields.indexOfFirst { it["name"].asString()=="value" }
            val time = fields.indexOfFirst { it["type"].asString()=="time" }
            if(value<0 || time<0 || fields[value].path("config").path("suppressed").asBoolean(false)) continue
            frame["data"]["values"][value].toList().forEachIndexed { i,cell ->
                if(!cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) return@forEachIndexed
                val at = Instant.ofEpochMilli(frame["data"]["values"][time][i].asLong())
                val date = at.atZone(zone).toLocalDate()
                if(date in selected) findings += mapOf("rule_id" to "observed_sprint_sessions","severity" to "info",
                    "widget_id" to "W2.3","title" to "지정한 스프린트 날짜에 세션이 관측되었습니다",
                    "evidence" to mapOf("sprint_date" to date.toString(),"date" to at.toString(),"tz" to zone.id,
                        "sessions" to cell.asDouble(),"limitation" to "선택한 현지 날짜의 관측 세션 수입니다. 스프린트 구간·반복 주기·업무 강도나 인과 효과를 추정하지 않습니다."))
            }
        }
        return findings.sortedBy { (it.getValue("evidence") as Map<*,*>)["sprint_date"].toString() }
    }
}
