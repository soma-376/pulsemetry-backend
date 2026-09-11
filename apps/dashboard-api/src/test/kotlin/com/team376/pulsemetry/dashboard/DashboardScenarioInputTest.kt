package com.team376.pulsemetry.dashboard

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneId

class DashboardScenarioInputTest {
    private val mapper = JsonMapper.builder().build()
    private val scenarios = DashboardScenarioCatalog(mapper, DashboardMetricCatalog(mapper))
    private val now = Instant.parse("2026-09-10T12:00:00Z")
    private fun input(id: String = "S1-3", json: String = """{"params":{}}""", zone: String = "Asia/Seoul") =
        ScenarioParameterValidator.validate(scenarios.detail(id), mapper.readTree(json), zone, now)

    @Test fun `기본값과 요청 시간을 고정하고 호출자 JSON은 변경하지 않는다`() {
        val body = mapper.readTree("""{"params":{}}""")
        val result = ScenarioParameterValidator.validate(scenarios.detail("S1-3"), body, "Asia/Seoul", now)
        assertThat(body["params"].size()).isZero()
        assertThat(result.params["moving_avg_days"].asInt()).isEqualTo(7)
        assertThat(result.times["to"]).isEqualTo(now)
        assertThat(result.times["from"]).isEqualTo(now.minusSeconds(28 * 86400))
        assertThat(result.priceBasis).isEqualTo("list")
        assertThat(result.zone).isEqualTo(ZoneId.of("Asia/Seoul"))
    }

    @Test fun `문자열 숫자 null 미지정 필드와 소수 정수를 거부한다`() {
        for (json in listOf("{}", "null", "[]", """{"params":null}""", """{"params":{},"extra":1}""",
            """{"params":{"moving_avg_days":"7"}}""", """{"params":{"moving_avg_days":7.5}}""",
            """{"params":{"moving_avg_days":null}}""", """{"params":{"moving_avg_days":2}}""",
            """{"params":{"moving_avg_days":29}}""", """{"params":{"unknown":1}}""",
            """{"params":{},"price_basis":"free"}""", """{"params":{},"tz":null}"""))
            assertThatThrownBy { input(json = json) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `날짜 순서 기간 상한 윤일 timezone과 DST를 검증한다`() {
        for (json in listOf("""{"params":{"from":"now","to":"now-1d"}}""",
            """{"params":{"from":"now-367d"}}""", """{"params":{"from":"2026-02-29"}}""",
            """{"params":{},"tz":"invalid/zone"}"""))
            assertThatThrownBy { input(json = json) }.isInstanceOf(Exception::class.java)
        val result = input(json = """{"params":{"from":"2026-03-08","to":"2026-03-09"},"tz":"America/New_York","price_basis":"contract"}""")
        assertThat(result.times["to"]!!.epochSecond - result.times["from"]!!.epochSecond).isEqualTo(23 * 3600L)
        assertThat(result.priceBasis).isEqualTo("contract")
        assertThatThrownBy { input("S3-3", """{"params":{"cohort_from":"2026-09-02","cohort_to":"2026-09-01"}}""") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `예산은 올바른 팀 식별자와 양수인 단일 단위를 요구한다`() {
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertThat(input("S1-1", """{"params":{"budget_by_team":{"$id":{"usd":100}}}}""").params.has("budget_by_team")).isTrue()
        for (budget in listOf("{}", """{"$id":{}}""", """{"$id":{"usd":0}}""",
            """{"$id":{"usd":1,"tokens_m":1}}""", """{"$id":{"usd":1,"extra":1}}""", """{"bad":{"usd":1}}"""))
            assertThatThrownBy { input("S1-1", """{"params":{"budget_by_team":$budget}}""") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { input("S1-1", """{"params":{"team_ids":["bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"],"budget_by_team":{"$id":{"usd":1}}}}""") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `중복 과다 배열 축약 UUID와 동일 모델 비교를 거부한다`() {
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        for (teams in listOf("[\"$id\",\"$id\"]", "[\"1-1-1-1-1\"]", "[null]"))
            assertThatThrownBy { input(json = """{"params":{"team_ids":$teams}}""") }.isInstanceOf(IllegalArgumentException::class.java)
        val values = (1..101).joinToString(",") { "\"model-$it\"" }
        assertThatThrownBy { input("S6-4", """{"params":{"models":[$values]}}""") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { input("S8-3", """{"params":{"model_a":"same","model_b":"same"}}""") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `전후 비교는 현지 자정과 DST를 보존하고 52주를 각각 고정한다`() {
        for (id in listOf("S4-4","S8-6")) {
            val result = input(id,"""{"params":{"pivot_date":"2026-03-08","window_weeks":1},"tz":"America/New_York"}""")
            assertThat(result.times["compare_from"]).isEqualTo(Instant.parse("2026-03-01T05:00:00Z"))
            assertThat(result.times["compare_to"]).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"))
            assertThat(result.times["from"]).isEqualTo(result.times["compare_to"])
            assertThat(result.times["to"]).isEqualTo(Instant.parse("2026-03-15T04:00:00Z"))
            val long = input(id,"""{"params":{"pivot_date":"2026-09-01","window_weeks":52}}""")
            assertThat(java.time.Duration.between(long.times["compare_from"],long.times["to"]).toDays()).isEqualTo(728)
            assertThat(long.params.has("from")).isFalse()
            for (weeks in listOf(0,53)) assertThatThrownBy {
                input(id,"""{"params":{"pivot_date":"2026-09-01","window_weeks":$weeks}}""")
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun `정책 용도 비교는 선택 범위 안의 기준일만 허용한다`() {
        val result = input("S5-6","""{"params":{"from":"2026-08-25","to":"2026-09-03","pivot_date":"2026-09-01"}}""")
        assertThat(result.times["from"]).isEqualTo(Instant.parse("2026-08-31T15:00:00Z"))
        assertThat(result.times["compare_from"]).isEqualTo(Instant.parse("2026-08-24T15:00:00Z"))
        assertThat(result.params["from"].asString()).isEqualTo("2026-08-25")
        for (pivot in listOf("2026-08-24","2026-08-25","2026-09-03","2026-09-04"))
            assertThatThrownBy { input("S5-6","""{"params":{"from":"2026-08-25","to":"2026-09-03","pivot_date":"$pivot"}}""") }
                .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `모든 카탈로그 파라미터 스키마에 타입에 맞는 입력을 적용할 수 있다`() {
        val examples = mapOf("pivot_date" to "2026-09-01", "cohort_from" to "2026-08-01", "cohort_to" to "2026-09-01",
            "model_a" to "model-a", "model_b" to "model-b")
        for (summary in (scenarios.list(null, null, null, "")["items"] as List<*>)) {
            val id = (summary as tools.jackson.databind.JsonNode)["scenario_id"].asString()
            val detail = scenarios.detail(id)
            val params = mapper.createObjectNode()
            for ((key, schema) in detail["params_schema"]["properties"].properties()) {
                if (schema.has("default")) continue
                when (schema["type"].asString()) {
                    "array" -> params.putArray(key)
                    "object" -> params.set(key, mapper.readTree("""{"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa":{"usd":100}}"""))
                    "string" -> params.put(key, examples[key] ?: "example")
                }
            }
            val body = mapper.createObjectNode().also { it.set("params", params) }
            assertThatCode { ScenarioParameterValidator.validate(detail, body, "UTC", now) }.doesNotThrowAnyException()
        }
    }
}
