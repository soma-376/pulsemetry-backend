package com.team376.pulsemetry.dashboard.finding

import tools.jackson.databind.JsonNode

/** 직군 정보 대신 공개 가능한 팀별 채택률을 제공한다. 팀 간 우열은 추정하지 않는다. */
internal object DashboardAdoptionFindings {
    fun evaluate(result: JsonNode): List<Map<String,Any>> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val index = fields.indexOfFirst { it["name"].asString()=="value" }
        if(index<0) return@mapNotNull null
        val field = fields[index]
        val team = field.path("labels").path("team").asString("")
        val cell = frame["data"]["values"][index][0]
        if(team.isBlank() || team=="__other__" || field.path("config").path("suppressed").asBoolean(false) ||
            cell==null || !cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) null
        else mapOf("rule_id" to "observed_team_adoption", "severity" to "info", "widget_id" to "W1.6",
            "title" to "팀별 채택률이 관측되었습니다",
            "evidence" to mapOf("team_id" to team,"adoption_rate" to cell.asDouble(),
                "limitation" to "직군 정보가 없어 팀으로 집계합니다. 관측 활성 사용자 기준이며 직군 간 격차나 팀의 우열을 판정하지 않습니다."))
    }.sortedBy { (it.getValue("evidence") as Map<*,*>)["team_id"].toString() }
}
