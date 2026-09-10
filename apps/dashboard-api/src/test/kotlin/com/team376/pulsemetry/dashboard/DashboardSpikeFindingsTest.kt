package com.team376.pulsemetry.dashboard

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class DashboardSpikeFindingsTest {
    private val mapper = JsonMapper.builder().build()
    private fun frame(values: List<Double?>, model: String = "a", masked: Boolean = false): Map<String,Any> = mapOf(
        "schema" to mapOf("fields" to listOf(mapOf("name" to "time","type" to "time"),
            mapOf("name" to "value","type" to "number","labels" to mapOf("model" to model),"config" to mapOf("suppressed" to masked)))),
        "data" to mapOf("values" to listOf(listOf(0L,86400000L),values)))
    private fun result(vararg frames: Map<String,Any>): JsonNode = mapper.valueToTree(mapOf("frames" to frames.toList()))
    @Test fun `세 판정식은 수치 근거와 모델별 전일 비중으로 평가한다`() {
        val findings = DashboardSpikeFindings.evaluate(mapOf("cost" to result(frame(listOf(2.0,8.0)),frame(listOf(8.0,2.0),"b")),
            "cost_anomaly" to result(frame(listOf(null,3.0))),"api_retry_attempts" to result(frame(listOf(0.0,0.1)))),200.0)
        assertThat(findings.map { it["rule_id"] }).containsExactly("spike_day","retry_cost","top_mover")
        assertThat((findings.last()["evidence"] as Map<*,*>)["share_change"] as Double).isCloseTo(.6,within(.00001))
    }
    @Test fun `누락 마스킹과 기준 비용 0에서는 비중 변화를 만들지 않는다`() {
        for (first in listOf(frame(listOf(0.0,8.0)),frame(listOf(null,8.0)),frame(listOf(2.0,8.0),masked=true))) {
            val findings = DashboardSpikeFindings.evaluate(mapOf("cost" to result(first,frame(listOf(0.0,2.0),"b")),
                "cost_anomaly" to result(frame(listOf(null,null))),"api_retry_attempts" to result(frame(listOf(null,null)))),200.0)
            assertThat(findings).isEmpty()
        }
    }
    @Test fun `상위 백 개 절단 가능성이 있으면 모델 비중 판정을 생략한다`() {
        val groups = (1..100).map { frame(if(it==1) listOf(1.0,10000.0) else listOf(1.0,1.0),it.toString()) }
        val findings = DashboardSpikeFindings.evaluate(mapOf("cost" to result(*groups.toTypedArray()),
            "cost_anomaly" to result(frame(listOf(null,null))),"api_retry_attempts" to result(frame(listOf(null,null)))),200.0)
        assertThat(findings).isEmpty()
    }
}
