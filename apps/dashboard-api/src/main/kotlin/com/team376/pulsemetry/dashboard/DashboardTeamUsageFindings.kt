package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode

/** 팀별 공개 관측을 안내한다. 팀 규모와 수집 범위 보정 없이 활용 우열을 판정하지 않는다. */
internal object DashboardTeamUsageFindings {
    fun evaluate(result: JsonNode): List<Map<String,Any>> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val index = fields.indexOfFirst { it["name"].asString()=="value" }
        if(index<0) return@mapNotNull null
        val field = fields[index]
        val team = field.path("labels").path("team").asString("")
        val cell = frame["data"]["values"][index][0]
        if(team.isBlank() || team=="__other__" || field.path("config").path("suppressed").asBoolean(false) ||
            cell==null || !cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) null
        else mapOf("rule_id" to "observed_team_usage", "severity" to "info", "widget_id" to "W1.6",
            "title" to "팀별 세션 사용량이 관측되었습니다",
            "evidence" to mapOf("team_id" to team,"sessions" to cell.asDouble(),
                "limitation" to "팀 규모와 수집 범위를 보정하지 않은 관측값입니다. 활용 우열이나 생산성 격차를 판정하지 않습니다."))
    }.sortedBy { (it.getValue("evidence") as Map<*,*>)["team_id"].toString() }
}
