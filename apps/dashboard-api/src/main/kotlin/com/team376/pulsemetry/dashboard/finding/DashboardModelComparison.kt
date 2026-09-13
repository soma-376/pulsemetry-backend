package com.team376.pulsemetry.dashboard.finding

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** 모델별 독립 집계를 유지한다. 동일 사람이 양쪽 모델을 사용할 수 있다. */
internal object DashboardModelComparison {
    fun label(result: JsonNode, model: String): List<JsonNode> = result["frames"].toList().map { original ->
        val frame = original.deepCopy() as ObjectNode
        frame["schema"]["fields"].forEach { field ->
            if(field["type"].asString()=="number") {
                val labels = (field as ObjectNode).withObject("/labels")
                labels.put("model",model)
            }
        }
        frame
    }

    fun findings(metric: String, a: JsonNode, b: JsonNode, modelA: String, modelB: String): List<Map<String,Any>> {
        val statistic = if(metric in setOf("prompts_per_session","llm_duration_ms")) "p50" else "value"
        fun value(result: JsonNode): Double? {
            val frame = result["frames"].singleOrNull() ?: return null
            val fields = frame["schema"]["fields"].toList()
            val index = fields.indexOfFirst { it["name"].asString()==statistic }
            if(index<0 || fields[index].path("config").path("suppressed").asBoolean(false)) return null
            val cell = frame["data"]["values"][index].firstOrNull() ?: return null
            return if(cell.isNumber && cell.asDouble().isFinite()) cell.asDouble() else null
        }
        val av = value(a) ?: return emptyList()
        val bv = value(b) ?: return emptyList()
        val delta = bv-av
        if(delta==0.0 || !delta.isFinite()) return emptyList()
        return listOf(mapOf("rule_id" to "observed_model_difference","severity" to "info",
            "widget_id" to if(metric=="cost") "W2.5" else "W3.1",
            "title" to "선택한 모델의 관측값 차이가 있습니다",
            "evidence" to mapOf("metric_id" to metric,"statistic" to statistic,"model_a" to modelA,"model_b" to modelB,
                "model_a_value" to av,"model_b_value" to bv,"delta_b_minus_a" to delta,
                "limitation" to "같은 기간의 모델별 독립 집계입니다. 이용자가 중복될 수 있고 요청량·업무·배정을 통제하지 않습니다. 비용은 기간 합계이며 모델 우열·통계적 유의성·인과 효과를 판정하지 않습니다.")))
    }
}
