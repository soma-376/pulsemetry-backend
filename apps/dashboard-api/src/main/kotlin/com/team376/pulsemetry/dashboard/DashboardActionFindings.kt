package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode

/** 공개 가능한 action별 관측 건수만 안내한다. 숨겨진 그룹의 비중과 업무 주제는 추정하지 않는다. */
internal object DashboardActionFindings {
    fun evaluate(result: JsonNode): List<Map<String,Any>> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val index = fields.indexOfFirst { it["name"].asString()=="value" }
        if(index<0) return@mapNotNull null
        val field = fields[index]
        val action = field.path("labels").path("action").asString("")
        val cell = frame["data"]["values"][index][0]
        if(action.isBlank() || action=="__other__" || field.path("config").path("suppressed").asBoolean(false) ||
            cell==null || !cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) null
        else mapOf("rule_id" to "observed_tool_action", "severity" to "info", "widget_id" to "W2.8",
            "title" to "도구 action별 호출이 관측되었습니다",
            "evidence" to mapOf("action" to action,"calls" to cell.asDouble(),
                "limitation" to "도구 action 분포이며 업무 주제나 유즈케이스 분류가 아닙니다. 미분류 action과 숨겨진 그룹의 비중은 추정하지 않습니다."))
    }.sortedBy { (it.getValue("evidence") as Map<*,*>)["action"].toString() }
}
