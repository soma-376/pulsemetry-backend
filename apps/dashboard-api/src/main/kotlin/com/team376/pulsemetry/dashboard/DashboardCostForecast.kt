package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 완결된 일별 관측값의 단순 외삽. 미관측 날짜를 영으로 대체하지 않는다. */
internal object DashboardCostForecast {
    const val HORIZON_DAYS = 30
    fun evaluate(result: JsonNode, from: Instant, to: Instant, zone: ZoneId, model: String): Map<String,Any> {
        require(model in setOf("constant","linear"))
        fun ceilDay(at: Instant): LocalDate {
            val day = at.atZone(zone).toLocalDate()
            return if(day.atStartOfDay(zone).toInstant()==at) day else day.plusDays(1)
        }
        val start = ceilDay(from)
        val end = to.atZone(zone).toLocalDate()
        val future = ceilDay(to)
        val dates = generateSequence(start) { it.plusDays(1) }.takeWhile { it<end }.toList()
        val observed = mutableMapOf<LocalDate,Double>()
        val frame = result.path("frames").singleOrNull()
        if(frame!=null) {
            val fields = frame["schema"]["fields"].toList()
            val time = fields.indexOfFirst { it["name"].asString()=="time" }
            val value = fields.indexOfFirst { it["name"].asString()=="value" }
            if(time>=0 && value>=0 && !fields[value].path("config").path("suppressed").asBoolean(false)) {
                frame["data"]["values"][time].toList().forEachIndexed { index,tick ->
                    val cell = frame["data"]["values"][value][index]
                    if(cell!=null && cell.isNumber && cell.asDouble().isFinite() && cell.asDouble()>=0)
                        observed[Instant.ofEpochMilli(tick.asLong()).atZone(zone).toLocalDate()] = cell.asDouble()
                }
            }
        }
        val validDays = dates.count { it in observed }
        val metadata = mapOf("growth_model" to model,"horizon_days" to HORIZON_DAYS,
            "training_from" to start.toString(),"training_to" to end.toString(),
            "forecast_from" to future.toString(),"forecast_to" to future.plusDays(HORIZON_DAYS.toLong()).toString(),
            "required_days" to dates.size,"observed_days" to validDays,
            "limitation" to "조회 범위에 완전히 포함된 현지 날짜의 관측 비용만 사용합니다. constant는 일평균 유지, linear는 최소제곱 직선의 외삽이며 음수 일별 예측은 0으로 제한합니다. 채택률·단가·계약·계절성 변화나 수집 누락을 통제하지 않고 청구액·예산 보장·신뢰구간을 제공하지 않습니다.")
        if(dates.size<2 || validDays!=dates.size) return metadata+mapOf("status" to "insufficient_data",
            "reason" to "complete_daily_cost_required")
        val ys = dates.map { observed.getValue(it) }
        val mean = ys.average()
        val center = (ys.size-1)/2.0
        val slope = if(model=="constant") 0.0 else ys.indices.sumOf { (it-center)*(ys[it]-mean) } /
            ys.indices.sumOf { (it-center)*(it-center) }
        val intercept = mean-slope*center
        val raw = (0 until HORIZON_DAYS).map { offset ->
            intercept+slope*ChronoUnit.DAYS.between(start,future.plusDays(offset.toLong())) }
        if(!mean.isFinite() || !slope.isFinite() || raw.any { !it.isFinite() } || !raw.sum().isFinite())
            return metadata+mapOf("status" to "insufficient_data","reason" to "non_finite_projection")
        val daily = raw.map { maxOf(0.0,it) }
        if(!daily.sum().isFinite()) return metadata+mapOf("status" to "insufficient_data","reason" to "non_finite_projection")
        return metadata+mapOf("status" to "ready","projected_cost_usd" to daily.sum(),"slope_usd_per_day" to slope,
            "clamped_to_zero" to raw.any { it<0 },"daily" to daily.mapIndexed { index,value ->
                mapOf("date" to future.plusDays(index.toLong()).toString(),"cost_usd" to value) })
    }
    fun findings(forecast: Map<String,Any>): List<Map<String,Any>> = listOf(mapOf(
        "rule_id" to if(forecast["status"]=="ready") "projected_cost" else "cost_forecast_unavailable",
        "severity" to "info","widget_id" to "W1.2",
        "title" to if(forecast["status"]=="ready") "관측 추세에 따른 비용 예측입니다" else "비용 예측에 필요한 관측 이력이 부족합니다",
        "evidence" to forecast.filterKeys { it!="daily" }))
}
