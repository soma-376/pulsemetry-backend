package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode
import java.time.Instant

/** 명세의 세 규칙만 고정 코드로 평가한다. 판정식 문자열을 실행하지 않으며 null·마스킹은 건너뛴다. */
internal object DashboardSpikeFindings {
    private data class Series(val times: List<Long>, val values: List<Double?>, val label: String)
    private fun series(result: JsonNode): List<Series> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val valueIndex = fields.indexOfFirst { it["name"].asString()=="value" }
        val timeIndex = fields.indexOfFirst { it["type"].asString()=="time" }
        if(valueIndex<0 || timeIndex<0 || fields[valueIndex].path("config").path("suppressed").asBoolean(false)) return@mapNotNull null
        Series(frame["data"]["values"][timeIndex].toList().map { it.asLong() },
            frame["data"]["values"][valueIndex].toList().map { if(it.isNumber && it.asDouble().isFinite()) it.asDouble() else null },
            fields[valueIndex].path("labels").path("model").asString("unknown"))
    }
    fun evaluate(frames: Map<String,JsonNode>, thresholdPct: Double): List<Map<String,Any>> {
        val findings = mutableListOf<Map<String,Any>>()
        fun add(rule: String, severity: String, title: String, widget: String, evidence: Map<String,Any>) {
            findings += mapOf("rule_id" to rule,"severity" to severity,"title" to title,"widget_id" to widget,"evidence" to evidence)
        }
        for (s in series(frames.getValue("cost_anomaly"))) s.values.forEachIndexed { i, value ->
            if(value!=null && value>=thresholdPct/100) add("spike_day","anomaly","일 비용이 이동평균 대비 임계를 초과했습니다", "W1.2",
                mapOf("date" to Instant.ofEpochMilli(s.times[i]).toString(),"ratio" to value,"threshold_pct" to thresholdPct))
        }
        for (s in series(frames.getValue("api_retry_attempts"))) s.values.forEachIndexed { i, value ->
            if(value!=null && value>=0.05) add("retry_cost","info","재시도 요청 비율이 5% 이상입니다", "W2.5",
                mapOf("date" to Instant.ofEpochMilli(s.times[i]).toString(),"ratio" to value))
        }
        val cost = frames.getValue("cost")
        val groups = series(cost)
        // 상위 N 절단 가능성, 소집단, 누락 비용이 있으면 비중 변화를 추정하지 않는다.
        if(groups.isNotEmpty() && groups.size<100 && groups.size==cost["frames"].size() && groups.all { it.times==groups.first().times }) {
            for(i in 1 until groups.first().times.size) {
                if(groups.any { it.values[i]==null || it.values[i-1]==null }) continue
                val current = groups.sumOf { it.values[i]!! }
                val previous = groups.sumOf { it.values[i-1]!! }
                if(current<=0 || previous<=0) continue
                for(group in groups) {
                    val delta = group.values[i]!!/current-group.values[i-1]!!/previous
                    if(delta>=0.3) add("top_mover","warning","모델 비용 비중이 전일 대비 30%p 이상 증가했습니다", "W2.5",
                        mapOf("date" to Instant.ofEpochMilli(group.times[i]).toString(),"model" to group.label,"share_change" to delta))
                }
            }
        }
        return findings
    }
}
