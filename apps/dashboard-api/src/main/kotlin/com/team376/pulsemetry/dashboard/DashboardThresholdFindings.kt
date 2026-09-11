package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode
import java.time.Instant

/** 명시한 시나리오의 운영 규칙: 임계값 초과를 검토 신호로 제공한다. 판정식 문자열은 실행하지 않는다. */
internal object DashboardThresholdFindings {
    private data class Rule(val id: String, val title: String, val widget: String, val limitation: String)
    fun evaluate(scenario: String, result: JsonNode, threshold: Double): List<Map<String,Any>> {
        val rule = when(scenario) {
            "S1-4" -> Rule("no_cache_reads","유효 토큰 요청에서 캐시 읽기가 관측되지 않았습니다","W2.6",
                "프롬프트 원문 유사도와 반복 여부는 측정하지 않습니다. 캐싱 가능성이나 절감 효과를 보장하지 않습니다.")
            "S1-5" -> Rule("high_io_ratio","입력/출력 토큰 비율이 임계값을 초과했습니다","W2.6",
                "컨텍스트 첨부 여부는 관측하지 못합니다. 입력 토큰 사용을 검토하세요.")
            "S8-1" -> Rule("observed_reporting_usage","경영 보고용 세션 사용량이 관측되었습니다","W1.1",
                "재무 데이터와 연결하지 않은 사용량입니다. ROI·절감액·생산성 향상을 계산하거나 보장하지 않습니다.")
            "S2-2" -> Rule("observed_rate_limit_pressure","Rate Limit 이벤트가 관측되었습니다","W3.3",
                "관측된 제한 이벤트 수입니다. 재시도 고갈 이벤트가 없어 상시 도달·작업 중단·실제 한도 소진을 확정하지 않습니다.")
            "S2-1" -> Rule("observed_rate_limits","Rate Limit 이벤트가 관측되었습니다","W2.3",
                "시간대별 프롬프트 수와 관측 제한 이벤트입니다. 오전·오후 변동의 원인이나 작업 중단을 확정하지 않습니다.")
            "S3-5" -> Rule("observed_subagent_cost","서브에이전트 비용이 관측되었습니다","W2.8",
                "query_source 메트릭의 비용 비율입니다. 스킬·플러그인 사용률이나 고급 기능 숙련도를 측정하지 않습니다.")
            "S6-5" -> Rule("observed_api_retries","API 재시도가 관측되었습니다","W3.1",
                "전체 관측 호출 중 attempt가 1보다 큰 호출의 비율이며 attempt 미수집 호출도 분모에 포함됩니다. 재시도 고갈·스톰 여부를 확정하지 않으며 전체 관측 비용을 재시도 추가 비용으로 해석하지 않습니다.")
            "S7-1" -> Rule("high_tool_failure_rate","도구 실패율이 임계값을 초과했습니다","W2.10",
                "도구 호출 실패율이며 에이전트 태스크 완료 여부나 성공률을 직접 측정하지 않습니다.")
            "S4-1" -> Rule("multiple_prompts_per_session","세션별 프롬프트 수 중앙값이 1을 초과했습니다","W2.2",
                "프롬프트 이벤트 수이며 원문 반복이나 재시도를 의미하지 않습니다. 정상적인 다중 대화일 수 있습니다.")
            "S4-2" -> Rule("sessions_without_output","산출물이 관측되지 않은 세션이 있습니다","W2.2",
                "조회 기간의 관측 산출만 비교합니다. 세션 종료나 사용자의 대화 포기를 확정하지 않습니다.")
            else -> error("unsupported_scenario")
        }
        val findings = mutableListOf<Map<String,Any>>()
        for (frame in result["frames"].toList()) {
            val fields = frame["schema"]["fields"].toList()
            val value = fields.indexOfFirst { it["name"].asString()==if(scenario=="S4-1") "p50" else "value" }
            val time = fields.indexOfFirst { it["type"].asString()=="time" }
            if(value<0 || time<0 || fields[value].path("config").path("suppressed").asBoolean(false)) continue
            frame["data"]["values"][value].toList().forEachIndexed { i, cell ->
                if(cell.isNumber && cell.asDouble().isFinite() && (if(scenario=="S1-4") cell.asDouble()==0.0 else cell.asDouble()>threshold)) findings += mapOf(
                    "rule_id" to rule.id, "severity" to "info", "widget_id" to rule.widget,
                    "title" to rule.title,
                    "evidence" to mapOf("date" to Instant.ofEpochMilli(frame["data"]["values"][time][i].asLong()).toString(),
                        (when(scenario) { "S4-1" -> "p50"; "S2-1","S2-2","S8-1" -> "count"; else -> "ratio" }) to cell.asDouble(), "threshold" to threshold,
                        "limitation" to rule.limitation))
            }
        }
        return findings
    }
}
