package com.team376.pulsemetry.dashboard

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId

class DashboardCostForecastTest {
    private val mapper = JsonMapper.builder().build()
    private val zone = ZoneId.of("America/New_York")
    private val start = LocalDate.parse("2026-03-01")
    private fun frame(values: List<Double?>, suppressed: Boolean = false): JsonNode = mapper.valueToTree(mapOf("frames" to listOf(
        mapOf("schema" to mapOf("fields" to listOf(mapOf("name" to "time"),mapOf("name" to "value","config" to mapOf("suppressed" to suppressed)))),
            "data" to mapOf("values" to listOf(values.indices.map { start.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() },values))))))
    private fun forecast(values: List<Double?>, model: String = "constant", suppressed: Boolean = false) =
        DashboardCostForecast.evaluate(frame(values,suppressed),start.atStartOfDay(zone).toInstant(),
            start.plusDays(values.size.toLong()).atStartOfDay(zone).toInstant(),zone,model)

    @Test fun `DST 날짜를 유지하며 관측 영과 미관측 마스킹을 구분한다`() {
        val ready = forecast(List(90) { 0.0 })
        assertThat(ready["status"]).isEqualTo("ready")
        assertThat(ready["projected_cost_usd"]).isEqualTo(0.0)
        assertThat(ready["required_days"]).isEqualTo(90)
        assertThat(forecast(List(90) { if(it==10) null else 1.0 })["status"]).isEqualTo("insufficient_data")
        assertThat(forecast(List(90) { 1.0 },suppressed=true)["status"]).isEqualTo("insufficient_data")
    }
    @Test fun `부분 날짜는 훈련과 예측에서 제외하고 음수 추세는 영으로 제한한다`() {
        val result = DashboardCostForecast.evaluate(frame(listOf(999.0,3.0,2.0,1.0,999.0)),
            start.atStartOfDay(zone).toInstant().plusSeconds(3600),start.plusDays(4).atStartOfDay(zone).toInstant().plusSeconds(3600),zone,"linear")
        assertThat(result["required_days"]).isEqualTo(3)
        assertThat(result["projected_cost_usd"]).isEqualTo(0.0)
        assertThat(result["clamped_to_zero"]).isEqualTo(true)
        assertThat(result["forecast_from"]).isEqualTo("2026-03-06")
    }
}
