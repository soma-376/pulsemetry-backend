package com.team376.pulsemetry.dashboard.query

import com.team376.pulsemetry.dashboard.catalog.DashboardMetricDefinition
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.durationMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupLimit
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.people
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.ratioMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.sessionMetrics
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.tokenRatios
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.topGroupMetrics
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

// 본문은 원본 들여쓰기를 그대로 둔다 — SQL 리터럴이 executed_sql 로 응답에 실리므로 공백 한 칸도 바꾸지 않는다.
/** 리더가 돌려준 행을 Grafana 식 프레임으로 조립한다 — 마스킹 · 단위 · data_quality · 비교 diff · 정렬. */
internal class DashboardQueryFrames(private val mapper: ObjectMapper) {
    fun build(q: DashboardQueryItem, definition: DashboardMetricDefinition, current: Rows, previous: Rows?,
        interval: String, ticks: List<Instant>, previousTicks: List<Instant>?, names: Map<String, String>): List<Map<String, Any>> {
        fun key(row: JsonNode) = q.groupBy.indices.map { row["g$it"].asString() }
        val groups = current.data.groupBy(::key)
        val comparisons = previous?.data?.groupBy(::key).orEmpty()
        val keys = (groups.keys + comparisons.keys).distinct()
        val limitedKeys = if(q.metricId=="session_last_event" && q.groupBy.size>1) keys.map { it.dropLast(1) }.distinct()
            else if(q.metricId=="onboarding_retention") keys.map { it.dropLast(if(q.frameType=="timeseries") 1 else 2) }.distinct() else keys
        if (limitedKeys.size > groupLimit(q)+(if (q.metricId in topGroupMetrics && limitedKeys.any { it.all { value -> value=="__other__" } }) 1 else 0)) throw DashboardReadException("query_too_wide", 422)
        val frameType = q.frameType ?: definition.defaultFrameType
        if(q.metricId=="vendor_account_mismatch" && frameType=="table") return mismatchTable(q,current,previous)
        val frames = keys.flatMap { group ->
            val rows = groups[group].orEmpty()
            val prior = comparisons[group].orEmpty()
            val labels = q.groupBy.zip(group).toMap().toMutableMap()
            if (q.metricId=="onboarding_retention") {
                rows.firstOrNull()?.get("cohort_week")?.let { labels["cohort_week"] = it.asString() }
                prior.firstOrNull()?.get("cohort_week")?.let { labels["cohort_week_compare"] = it.asString() }
            }
            labels["team"]?.let { labels["team_id"] = it; labels["team_name"] = names[it] ?: it }
            val fields = mutableListOf<Map<String, Any?>>()
            val values = mutableListOf<List<Any?>>()
            val timeseries = frameType == "timeseries"
            val suppressed = (rows+prior).any { it["people"].asLong() < 5 }
            if (timeseries) {
                fields += mapOf("name" to "time", "type" to "time")
                values += ticks.map { it.toEpochMilli() }
            }
            fun add(name: String, series: List<JsonNode>, seriesTicks: List<Instant>?, column: String = "value") {
                fields += mapOf("name" to name, "type" to "number", "labels" to labels,
                    "config" to mapOf("unit" to if (q.metricId in setOf("subagent_activity","hook_executions") && column=="ratio") "ratio" else if (q.metricId in setOf("cost_per_active_user","cost_per_user_hour","model_unit_price") && column=="numerator") "USD" else if (q.metricId=="model_unit_price" && column=="denominator") "token" else if (q.metricId=="cost_per_user_hour" && column=="denominator") "h" else if (q.metricId in setOf("subagent_cost_ratio","cost_anomaly","contract_commitment_burn") && column!="value") "USD" else if (column=="value" || q.metricId in durationMetrics) definition.unit else if (q.metricId=="automation_ratio") "s" else if (q.metricId in setOf("compaction_reduction","usage_concentration") || q.metricId in tokenRatios) "token" else "count", "suppressed" to suppressed,
                        "group_size" to if (suppressed) null else (rows+prior).minOfOrNull { it["people"].asLong() }))
                val byTime = series.associateBy { it["bucket"].asLong() }
                val retentionWeeks = if(q.metricId=="onboarding_retention" && timeseries) series.associateBy {
                    it["g${q.groupBy.size-1}"].asInt()+it["g${q.groupBy.size}"].asInt()
                } else emptyMap()
                val aligned = if(q.metricId=="onboarding_retention" && timeseries) ticks.indices.map { retentionWeeks[it] }
                    else if (timeseries) ticks.indices.map { index -> seriesTicks?.getOrNull(index)?.let { byTime[it.toEpochMilli()] } }
                    else listOf(series.firstOrNull())
                values += aligned.map { row -> row?.let {
                    if (suppressed || it["points"].asLong()==0L || it[column].isNull) null else it[column].asDouble()
                } }
            }
            if (q.metricId in sessionMetrics || q.metricId in durationMetrics) {
                for (column in if (q.metricId=="llm_duration_ms") listOf("p50","p95","p99") else listOf("p50","p90")) {
                    add(column,rows,ticks,column)
                    if (previous!=null) add("${column}_compare",prior,previousTicks,column)
                }
            } else {
                val valueName = if (q.metricId=="contract_commitment_burn") "burn_ratio" else "value"
                add(valueName, rows, ticks)
                if (previous != null) add("${valueName}_compare", prior, previousTicks)
            }
            if (q.metricId in setOf("subagent_activity","hook_executions")) {
                add("ratio",rows,ticks,"ratio")
                if (previous!=null) add("ratio_compare",prior,previousTicks,"ratio")
            }
            if (q.metricId in ratioMetrics || q.metricId in setOf("subagent_activity","hook_executions") || q.metricId in setOf("adoption_rate", "telemetry_coverage")) {
                for (column in listOf("numerator", "denominator")) {
                    val name = if (q.metricId=="contract_commitment_burn") (if (column=="numerator") "cost_usd" else "commitment_amount") else column
                    add(name,rows,ticks,column)
                    if (previous!=null) add("${name}_compare",prior,previousTicks,column)
                }
            }
            val cumulative = (rows+prior).sumOf { it["cumulative"].asLong() }
            val unknownAttempts = (rows+prior).sumOf { it["unknown_attempts"]?.asLong() ?: 0 }
            val incompleteCompactions = (rows+prior).sumOf { it["incomplete_compactions"]?.asLong() ?: 0 }
            val quality = mutableListOf<String>()
            if (incompleteCompactions>0) quality += if (suppressed) "전후 토큰 쌍이 없는 압축 이벤트를 감소율에서 제외" else
                "전후 토큰 쌍이 없는 압축 이벤트 ${incompleteCompactions}개를 감소율에서 제외"
            if (q.metricId=="subagent_activity") quality += "도구 호출에서 관측된 agent_id 수와 호출 비율; 완료·성공 여부는 판정하지 않음"
            if (q.metricId=="abandoned_session_ratio") quality += "조회 기간 내 로그 세션과 관측 산출만 판정; 세션 종료를 보장하지 않으며 미관측 산출이 있을 수 있음"
            if (q.metricId in setOf("cost","subagent_cost_ratio","cost_per_active_user","cost_per_user_hour","model_unit_price")) {
                quality += "원천: ${if (q.metricId=="subagent_cost_ratio") "metrics" else q.source ?: "events"}; 가격 기준: ${q.priceBasis ?: "list"}; 누락·음수 비용 제외, 청구액 아님"
                if (q.priceBasis=="contract") quality += "현재 계약 정보의 배정·유효 기간과 제품 벤더·모델 부분 문자열 일치, all 배율만 적용; 일치 없음은 1배"
                if (q.metricId=="model_unit_price") quality += "1토큰당 비용; 비용과 input/output/cache_read/cache_create가 모두 있는 비음수 호출의 합계 비율, 이벤트 원천만 사용"
                if (q.metricId=="cost_per_active_user") quality += "비용 / 활성 구성원; 사용자 활동 시간 원천이 없으면 관측 구성원 수 사용"
                if (q.metricId=="cost_per_user_hour") quality += "비용 / 사용자 활동 시간(시간 단위); cli·누적·음수 시간 제외, 시간 미관측은 분모 null"
                if (q.metricId=="subagent_cost_ratio") quality += "query_source=subagent 비용 / 전체 메트릭 비용; 귀속 누락도 전체 비용에 포함"
                if (q.metricId=="cost" && q.source!="metrics") quality += if (suppressed) "reported·estimated 관측 비용 포함" else
                    "reported ${(rows+prior).sumOf { it["reported"].asLong() }}개, estimated ${(rows+prior).sumOf { it["estimated"].asLong() }}개 관측 비용"
            }
            if (q.metricId=="contract_commitment_burn") quality += "owner 전사 범위; 조회·약정·배정 기간과 벤더가 일치하는 이벤트 계약 비용 / 전체 USD 약정액; 시계열은 버킷별 비용 비율이며 누적 소진율은 계약 시작부터 scalar 조회; all 배율만 적용, 약정액 누락·0은 비율 null, 청구액 아님"
            if (q.metricId=="cost_anomaly") quality += "일별 비용 / 직전 ${(q.params["moving_avg_days"] ?: q.params["window_days"])?.asInt() ?: 7}개 달력일 평균 - 1; 원천: ${if ("agent_name" in q.groupBy) "metrics" else "events"}; 가격 기준: ${q.priceBasis}; 기준일 누락·평균 0은 null, 기준일 소집단도 마스킹; 시작일은 자정부터, 마지막 날은 조회 종료까지, 단일값·표는 마지막 관측일; 청구액 아님"
            if (q.metricId=="vendor_account_mismatch") quality += "설치별 마지막 비어 있지 않은 로그·스팬 이메일과 현재 등록 이메일을 대소문자 구분 없이 비교; 시계열은 버킷별 판정, 동률은 sequence·event_id 순서; 주소는 반환하지 않음"
            if (q.metricId=="onboarding_retention") {
                quality += "필터·권한 범위의 보존된 첫 사용 주 코호트; 월요일 기준 설치 잔존율, 소집단은 구성원 수; 비교는 상대 코호트 주·경과 주차 정렬"
                if ((rows+prior).any { !it["complete"].asBoolean() }) quality += "아직 관측이 끝나지 않은 주차는 비율·재사용 수 미판정; 코호트 크기는 유지"
            }
            if (q.metricId=="onboarding_ttfu") quality += "필터·권한 범위의 보존 이력에서 첫 관측이 조회 기간에 속한 설치; 생성 이전 이벤트 제외, 과거 이력 유실 시 실제 첫 사용과 다를 수 있음; 생성 시각도 이벤트 정밀도에 맞춰 정수 초로 절삭"
            if (q.metricId=="usage_concentration") quality += "Q26 llm_call 이벤트 원천; 네 토큰 값이 모두 있는 비음수 호출만 사용; 상위 인원은 ceil(인원*0.1), 설치를 사람으로 병합하며 미매핑 설치는 별도 익명 단위"
            if (q.metricId=="session_last_event") quality += "Q25 로그의 마지막 관측 유형; 오류는 api_error, 긴 대기·실제 종료는 미판정; 시각·sequence 동률은 event_id 순서"
            if (q.metricId=="tokens") quality += "원천: ${q.source ?: "events"}; 관측된 토큰 값만 합산하며 누락·음수는 제외"
            if (q.metricId in tokenRatios) quality += "llm_call 이벤트 원천; 필요한 토큰 값이 모두 있는 호출의 합계 비율, 누락·음수 호출 제외"
            if (q.metricId=="llm_ttft_ms") quality += "조회 기간 내 설치·제품·요청별 유효 로그 우선, 없으면 스팬; 요청 ID 누락은 세션·모델별 소스 선택"
            if (q.metricId=="llm_stop_reasons") quality += "llm_call·llm_response 관측 이벤트 수; 사유 누락은 빈 라벨"
            if (q.metricId=="tool_rejections" && q.params["decided_by"]?.size()?.let { it>0 }==true)
                quality += "선택한 결정 주체만 포함: " + q.params.getValue("decided_by").toList().joinToString(",") { it.asString() }
            if (q.metricId=="edit_acceptance_rate" && q.params.containsKey("language"))
                quality += "편집 결정의 language 정확 일치: " + q.params.getValue("language").asString()
            if (q.metricId=="api_error_rate") quality += "호출 시도 단위 오류율이며 최종 재시도 실패율이 아님"
            if (cumulative>0) quality += if (suppressed) "누적 temporality 포인트 제외" else "누적 temporality 포인트 ${cumulative}개 제외"
            if (q.metricId=="api_retry_attempts" && unknownAttempts>0) quality += if (suppressed)
                "attempt 누락 호출이 분모에 포함됨; 재시도 여부 미판정" else "attempt 누락 호출 ${unknownAttempts}개가 분모에 포함됨; 재시도 여부 미판정"
            val summary = mapOf("schema" to mapOf("ref_id" to q.refId, "metric_id" to q.metricId,
                "frame_type" to frameType, "fields" to fields,
                "meta" to mapOf("definition" to definition.definition, "caveat" to definition.caveat,
                    "resolved_interval" to interval, "source_columns" to definition.sourceColumns,
                    "active_user_definition" to if ((rows+prior).all { it["active_time_definition"].asInt()==1 }) "active_time_user" else "any_event",
                    "executed_sql" to current.sql, "suppressed_groups" to if (suppressed) listOf(group.joinToString("/").ifEmpty { "all" }) else emptyList(),
                    "data_quality" to quality)),
                "data" to mapOf("values" to values))
            if (q.metricId=="usage_concentration" && frameType in setOf("table","distribution")) {
                // 소집단은 곡선의 행 개수도 숨긴다. 비교 기간마다 독립적인 인구 좌표를 제공한다.
                val curveFields = mutableListOf<Map<String,Any?>>()
                val curveValues = mutableListOf<List<Any?>>()
                val count = if (suppressed) 1 else maxOf(1, (rows.firstOrNull()?.get("lorenz_cumulative")?.size() ?: 0)+1,
                    (prior.firstOrNull()?.get("lorenz_cumulative")?.size() ?: 0)+1)
                fun curve(series: List<JsonNode>, compare: Boolean) {
                    val row = series.firstOrNull()
                    val cumulativeValues = row?.get("lorenz_cumulative")?.toList().orEmpty()
                    val total = row?.get("denominator")?.asDouble() ?: 0.0
                    for (column in listOf("population_share","lorenz_cumulative","usage_share")) {
                        curveFields += fields.first()+mapOf("name" to column+if (compare) "_compare" else "",
                            "config" to ((fields.first().getValue("config") as Map<*,*>)+mapOf("unit" to if (column=="lorenz_cumulative") "token" else "ratio")))
                        curveValues += (0 until count).map { index ->
                            if (suppressed || cumulativeValues.isEmpty() || index>cumulativeValues.size) null else {
                                val cumulativeValue = if (index==0) 0.0 else cumulativeValues[index-1].asDouble()
                                when(column) {
                                    "population_share" -> index.toDouble()/cumulativeValues.size
                                    "lorenz_cumulative" -> cumulativeValue
                                    else -> if (total==0.0) null else cumulativeValue/total
                                }
                            }
                        }
                    }
                }
                curve(rows,false)
                if (previous!=null) curve(prior,true)
                val curve = mapOf("schema" to ((summary.getValue("schema") as Map<*,*>)+mapOf("fields" to curveFields)),
                    "data" to mapOf("values" to curveValues))
                listOf(summary,curve)
            } else if (frameType=="distribution" && q.metricId in setOf("prompts_per_session","onboarding_ttfu")) {
                val histogramFields = mutableListOf<Map<String,Any?>>(mapOf("name" to "bucket", "type" to "string"),
                    fields.first()+mapOf("name" to "count", "config" to ((fields.first().getValue("config") as Map<*,*>)+mapOf("unit" to "count"))))
                fun counts(row: JsonNode?): List<Any?> = (1..5).map { if (suppressed) null else row?.get("b$it")?.asLong() }
                val histogramValues = mutableListOf<List<Any?>>(if (q.metricId=="onboarding_ttfu") listOf("<1h","1h–1d","1d–7d","7d–30d","30d+") else listOf("1","2–3","4–7","8–15","16+"),counts(rows.firstOrNull()))
                if (previous!=null) {
                    histogramFields += histogramFields[1]+mapOf("name" to "count_compare")
                    histogramValues += counts(prior.firstOrNull())
                }
                val histogram = mapOf("schema" to ((summary.getValue("schema") as Map<*,*>) + mapOf("fields" to histogramFields)),
                    "data" to mapOf("values" to histogramValues))
                listOf(histogram,summary)
            } else listOf(summary)
        }
        // 마스킹된 실제 값으로 정렬하지 않는다. 순서도 값에 대한 단서가 될 수 있다.
        return frames.sortedWith(compareBy<Map<String, Any>> { frame ->
            if (q.order == "label_asc") 0.0 else {
                val node = mapper.valueToTree<JsonNode>(frame)
                val index = node["schema"]["fields"].toList().indexOfFirst { it["type"].asString()=="number" }
                val numbers = node["data"]["values"][index].toList().filter { !it.isNull }
                if (numbers.isEmpty()) Double.POSITIVE_INFINITY else numbers.sumOf { it.asDouble() } * if (q.order=="value_desc") -1 else 1
            }
        }.thenBy { mapper.valueToTree<JsonNode>(it)["schema"]["fields"].toString() })
    }
    private fun mismatchTable(q: DashboardQueryItem, current: Rows, previous: Rows?): List<Map<String,Any>> {
        val all = current.data+previous?.data.orEmpty()
        if(all.isEmpty()) return emptyList()
        val hidden = all.any { it["people"].asLong()<5 }
        val currentById = current.data.associateBy { it["installation_id"].asString() }
        val priorById = previous?.data.orEmpty().associateBy { it["installation_id"].asString() }
        val ids = (currentById.keys+priorById.keys).filter { id ->
            currentById[id]?.get("mismatch")?.asInt()==1 || priorById[id]?.get("mismatch")?.asInt()==1 }.sorted()
        if(!hidden && ids.size>groupLimit(q)) throw DashboardReadException("query_too_wide",422)
        if(!hidden && ids.isEmpty()) return emptyList()
        fun domain(row: JsonNode?, key: String): String? = row?.get(key)?.asString()?.let {
            if('@' in it && it.substringAfterLast('@').isNotBlank()) "***@"+it.substringAfterLast('@').lowercase() else null }
        val columns = listOf("installation_id","vendor_email","registered_email")+
            if(previous!=null) listOf("vendor_email_compare","registered_email_compare") else emptyList()
        val values = columns.map { column ->
            if(hidden) listOf(null) else ids.map { id -> when(column) {
                "installation_id" -> id
                "vendor_email" -> domain(currentById[id],"latest_email")
                "registered_email" -> domain(currentById[id],"registered_email")
                "vendor_email_compare" -> domain(priorById[id],"latest_email")
                else -> domain(priorById[id],"registered_email")
            } }
        }
        return listOf(mapOf("schema" to mapOf("ref_id" to q.refId,"metric_id" to q.metricId,"frame_type" to "table",
            "fields" to columns.map { mapOf("name" to it,"type" to "string","config" to mapOf("suppressed" to hidden)) },
            "meta" to mapOf("executed_sql" to current.sql,"data_quality" to listOf("이메일 로컬 부분은 숨기고 도메인만 표시; 마지막 비어 있지 않은 관측 주소"))),
            "data" to mapOf("values" to values)))
    }
}
