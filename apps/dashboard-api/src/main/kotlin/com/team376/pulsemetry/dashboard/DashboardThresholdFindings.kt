package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode
import java.time.Instant

/** 명시한 시나리오의 운영 규칙: 임계값 초과를 검토 신호로 제공한다. 판정식 문자열은 실행하지 않는다. */
internal object DashboardThresholdFindings {
    private data class Rule(val id: String, val title: String, val widget: String, val limitation: String)
    fun evaluate(scenario: String, result: JsonNode, threshold: Double, details: Map<String,Any> = emptyMap()): List<Map<String,Any>> {
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
            "S5-2" -> Rule("observed_mcp_connections","MCP 연결 이벤트가 관측되었습니다","W3.2",
                "연결 상태 이벤트와 세션별 읽기 도구 사용량입니다. 외부 전송 내용·목적지·승인 여부를 확인하지 않으므로 코드·문서 유출을 탐지하거나 확정하지 않습니다.")
            "S5-4" -> Rule("observed_vendor_mismatch","등록 계정과 다른 벤더 계정이 관측되었습니다","W3.3",
                "일별 설치의 마지막 비어 있지 않은 로그·스팬 계정과 현재 등록 계정을 대소문자 구분 없이 비교한 설치 수입니다. 주소는 반환하지 않으며 비인가 사용·개인 계정·섀도우 AI를 확정하지 않습니다.")
            "S4-3" -> Rule("observed_edit_acceptance","사용자 편집 수락이 관측되었습니다","W2.7",
                "언어 선택은 편집 수락률에만 적용합니다. 코드량·커밋·PR은 기간 전체 보조 지표이며 revert·코드 품질·생산성 향상을 측정하지 않습니다.")
            "S5-7" -> Rule("observed_fast_approvals","임계 시간 미만의 사용자 승인이 관측되었습니다","W3.3",
                "유효 대기 시간이 있는 사용자 accept 중 threshold_ms 미만 비율입니다. 승인 내용의 검증 여부·실제 반출·PR과의 인과관계를 확인하지 않습니다.")
            "S6-1" -> Rule("observed_quality_refusals","모델 거부 응답이 관측되었습니다","W2.7",
                "선택 모델의 거부 응답 수입니다. 모델명이 없는 원본은 모델 선택 시 제외됩니다. 설문·품질 점수·거부의 적절성이나 사용자 만족도를 측정하지 않습니다.")
            "S6-4" -> Rule("observed_first_token_latency","첫 토큰 응답 지연이 관측되었습니다","W3.1",
                "선택한 모델의 첫 토큰 지연 p90입니다. 별도 SLA·장애 기준이나 원인 분석 없이 장애·품질 저하를 확정하지 않습니다.")
            "S6-5" -> Rule("observed_api_retries","API 재시도가 관측되었습니다","W3.1",
                "전체 관측 호출 중 attempt가 1보다 큰 호출의 비율이며 attempt 미수집 호출도 분모에 포함됩니다. 재시도 고갈·스톰 여부를 확정하지 않으며 전체 관측 비용을 재시도 추가 비용으로 해석하지 않습니다.")
            "S7-3" -> Rule("observed_tool_rejections","도구 거절이 관측되었습니다","W3.3",
                "관측된 도구 거절 수입니다. 거절 사유·정책 변경·실제 에스컬레이션을 확인하지 못하므로 정책 위반이나 악의적 사용을 확정하지 않습니다.")
            "S7-2" -> Rule("high_read_density","세션별 읽기 도구 호출 수 p90이 임계값을 초과했습니다","W3.2",
                "read·search·fetch 도구 호출 수의 세션별 p90입니다. 데이터 내용·접근 권한·외부 전송을 확인하지 않으므로 부적절한 접근이나 유출을 확정하지 않습니다.")
            "S7-4" -> Rule("observed_telemetry_coverage","텔레메트리 수집 커버리지가 관측되었습니다","W3.4",
                "조회 기간의 관측 설치 수와 현재 활성 설치 수의 비율입니다. 인증 감사·리텐션 삭제 실행 데이터가 없어 감사 완전성이나 보존 정책 준수를 확인하지 않습니다.")
            "S7-1" -> Rule("high_tool_failure_rate","도구 실패율이 임계값을 초과했습니다","W2.10",
                "도구 호출 실패율이며 에이전트 태스크 완료 여부나 성공률을 직접 측정하지 않습니다.")
            "S4-1" -> Rule("multiple_prompts_per_session","세션별 프롬프트 수 중앙값이 1을 초과했습니다","W2.2",
                "프롬프트 이벤트 수이며 원문 반복이나 재시도를 의미하지 않습니다. 정상적인 다중 대화일 수 있습니다.")
            "S4-5" -> Rule("observed_command_prompts","명령 프롬프트가 관측되었습니다","W2.8",
                "전체 프롬프트 중 선택한 command_name의 비율입니다. 명령명 미지정 시 모든 명령을 포함하며 스킬·템플릿 내용이나 생산성 향상을 측정하지 않습니다.")
            "S4-2" -> Rule("sessions_without_output","산출물이 관측되지 않은 세션이 있습니다","W2.2",
                "조회 기간의 관측 산출만 비교합니다. 세션 종료나 사용자의 대화 포기를 확정하지 않습니다.")
            else -> error("unsupported_scenario")
        }
        val findings = mutableListOf<Map<String,Any>>()
        for (frame in result["frames"].toList()) {
            val fields = frame["schema"]["fields"].toList()
            val value = fields.indexOfFirst { it["name"].asString()==when(scenario) { "S4-1" -> "p50"; "S6-4","S7-2" -> "p90"; else -> "value" } }
            val time = fields.indexOfFirst { it["type"].asString()=="time" }
            if(value<0 || time<0 || fields[value].path("config").path("suppressed").asBoolean(false)) continue
            frame["data"]["values"][value].toList().forEachIndexed { i, cell ->
                if(cell.isNumber && cell.asDouble().isFinite() && (if(scenario=="S1-4") cell.asDouble()==0.0 else cell.asDouble()>threshold)) findings += mapOf(
                    "rule_id" to rule.id, "severity" to "info", "widget_id" to rule.widget,
                    "title" to rule.title,
                    "evidence" to (mapOf("date" to Instant.ofEpochMilli(frame["data"]["values"][time][i].asLong()).toString(),
                        (when(scenario) { "S7-2" -> "p90_calls_per_session"; "S6-4" -> "p90_ms"; "S4-1" -> "p50"; "S2-1","S2-2","S5-2","S5-4","S6-1","S7-3","S8-1" -> "count"; else -> "ratio" }) to cell.asDouble(), "threshold" to threshold,
                        "limitation" to rule.limitation) + details))
            }
        }
        return findings
    }
}
