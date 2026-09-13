package com.team376.pulsemetry.dashboard.finding

import tools.jackson.databind.JsonNode

/** 공급된 기간 예산과 관측 합계만 비교한다. 미관측을 0이나 유휴 예산으로 추정하지 않는다. */
internal object DashboardBudgetFindings {
    fun evaluate(frames: Map<String,JsonNode>, budgets: JsonNode): List<Map<String,Any>> {
        fun amounts(metric: String): Map<String,Double> = frames.getValue(metric)["frames"].toList().mapNotNull { frame ->
            val fields = frame["schema"]["fields"].toList()
            val index = fields.indexOfFirst { it["name"].asString()=="value" }
            if(index<0) return@mapNotNull null
            val field = fields[index]
            val team = field.path("labels").path("team").asString("")
            val cell = frame["data"]["values"][index][0]
            if(team.isEmpty() || team=="__other__" || field.path("config").path("suppressed").asBoolean(false) ||
                cell==null || !cell.isNumber || !cell.asDouble().isFinite()) null else team to cell.asDouble()
        }.toMap()
        val costs = amounts("cost")
        val tokens = amounts("tokens")
        return budgets.properties().sortedBy { it.key }.mapNotNull { (team, budget) ->
            val usd = budget.has("usd")
            val unit = if(usd) "USD" else "million_tokens"
            val limit = budget[if(usd) "usd" else "tokens_m"].asDouble()
            val observed = (if(usd) costs[team] else tokens[team]?.div(1_000_000)) ?: return@mapNotNull null
            if(observed<=limit) return@mapNotNull null
            val ratio = observed/limit
            mapOf("rule_id" to "budget_exceeded", "severity" to "warning", "widget_id" to "W1.3",
                "title" to "관측 사용량이 입력한 팀 예산을 초과했습니다",
                "evidence" to mapOf("team_id" to team,"unit" to unit,"observed" to observed,"budget" to limit,"ratio" to ratio.takeIf { it.isFinite() },
                    "limitation" to "입력한 조회 기간 예산과 관측 사용량의 비교이며 청구액이나 예산 배분의 적정성을 판정하지 않습니다."))
        }
    }
}
