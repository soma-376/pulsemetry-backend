package com.team376.pulsemetry.dashboard.analytics

import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals

class SharedValueJsonTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `공유 값으로 이동해도 null과 비교 필드의 JSON 계약을 유지한다`() {
        val value = Metric<String>(null, "unavailable", listOf("pricing_missing"),
            Change("unavailable", listOf("previous_period_incomplete"), null, null, null, null))
        assertEquals(mapper.readTree("""{"value":null,"status":"unavailable","reasons":["pricing_missing"],"comparison":{"status":"unavailable","reasons":["previous_period_incomplete"],"previousValue":null,"absoluteChange":null,"percentChange":null,"change":null}}"""),
            mapper.valueToTree(value))
        assertEquals(mapper.readTree("""{"teamId":null,"teamName":"미배정"}"""), mapper.valueToTree(TeamRef(null, UNASSIGNED_NAME)))
    }

    @Test
    fun `기간 사용량과 원천 가용성의 미확인 사유를 혼합하지 않는다`() {
        assertEquals(listOf("no_data"), UsageValues.value<Long>(null).reasons)
        assertEquals(listOf("value_unavailable"), AvailabilityValues.value<Long>(null).reasons)
        assertEquals(Value(0L, "complete", emptyList()), UsageValues.value(0L))
    }
}
