package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode

/** *만 와일드카드로 허용하고 SQL LIKE의 나머지 특수 문자는 이스케이프한다. */
internal object DashboardPremiumModels {
    fun sqlPattern(pattern: String): String = pattern.replace("\\","\\\\").replace("%","\\%").replace("_","\\_").replace("*","%")

    fun findings(result: JsonNode, patterns: List<String>): List<Map<String,Any>> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val index = fields.indexOfFirst { it["name"].asString()=="value" }
        if(index<0) return@mapNotNull null
        val field = fields[index]
        val model = field.path("labels").path("model").asString("")
        val cell = frame["data"]["values"][index][0]
        if(model.isEmpty() || model=="__other__" || field.path("config").path("suppressed").asBoolean(false) ||
            cell==null || !cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) null
        else mapOf("rule_id" to "observed_premium_model_cost","severity" to "info","widget_id" to "W2.5",
            "title" to "지정한 모델 패턴의 비용이 관측되었습니다",
            "evidence" to mapOf("model" to model,"cost_usd" to cell.asDouble(),"premium_model_patterns" to patterns,
                "limitation" to "사용자가 지정한 패턴에 해당하는 모델의 관측 비용입니다. 모델 티어·작업 난이도·품질·대체 비용을 검증하지 않으므로 미스매치나 절감액을 판정하지 않습니다. 프롬프트·도구 호출은 모델 귀속이 없는 같은 기간·팀의 참고 지표입니다. 상위 100개 모델 밖의 합계는 기타로 제공하며 개별 판정하지 않습니다."))
    }
}
