package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.persistence.telemetry.DashboardClickHouseReader
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

/** UTC epoch에 정렬한 전사 고정 창의 거부 응답 관측이다. 공격 여부를 추론하지 않는다. */
@Service
class DashboardProbeWindows(private val jdbc: JdbcClient, private val reader: DashboardClickHouseReader,
    private val mapper: ObjectMapper) {
    fun findings(user: UserIdentity, from: Instant, to: Instant, minutes: Int, threshold: Int): List<Map<String,Any>> {
        if(user.role!="owner") throw UserAuthException("forbidden",403)
        require(minutes in 1..1440 && threshold in 1..10000 && from<to)
        val members = jdbc.sql("""SELECT i.id,i.member_id FROM enrollment.installations i
            JOIN enrollment.members m ON m.id=i.member_id AND m.tenant_id=i.tenant_id
            WHERE i.tenant_id=:tenant ORDER BY i.id LIMIT 5001""")
            .param("tenant",user.tenantId).query().listOfRows()
        if(members.size>5000) throw DashboardReadException("query_too_wide",422)
        if(members.isEmpty()) return emptyList()
        val rows = mapper.readTree(reader.query("""SELECT intDiv(toUnixTimestamp(ts),{seconds:UInt32})*{seconds:UInt32} AS window_start,
            count() AS refusals FROM enriched_events FINAL
            WHERE tenant_id={tenant:String} AND mapContains({members:Map(String,String)},installation_id)
                AND ts>=parseDateTime64BestEffort({from:String},6,'UTC')
                AND ts<parseDateTime64BestEffort({to:String},6,'UTC')
                AND signal IN ('log','span') AND JSONExtractString(raw_json,'type')='llm_response'
                AND JSONExtractString(raw_json,'payload','stop_reason')='refusal'
            GROUP BY window_start HAVING refusals>={threshold:UInt32}
                AND uniqExact({members:Map(String,String)}[installation_id])>=5
            ORDER BY window_start LIMIT 1001""",mapOf("tenant" to user.tenantId.toString(),
                "members" to members.joinToString(",","{","}") { "'${it["id"]}':'${it["member_id"]}'" },
                "from" to from.toString(),"to" to to.toString(),"seconds" to (minutes*60).toString(),
                "threshold" to threshold.toString()),Duration.ofSeconds(30)))["data"].toList()
        if(rows.size>1000) throw DashboardReadException("query_too_wide",422)
        return rows.map { row ->
            val start = Instant.ofEpochSecond(row["window_start"].asLong())
            val end = start.plusSeconds(minutes*60L)
            mapOf("rule_id" to "observed_repeated_refusals","severity" to "info","widget_id" to "W3.3",
                "title" to "시간 창 내 반복 거부 응답이 관측되었습니다",
                "evidence" to mapOf("window_start" to start.toString(),"window_end" to end.toString(),
                    "observed_from" to maxOf(start,from).toString(),"observed_to" to minOf(end,to).toString(),
                    "probe_window_min" to minutes,"probe_count" to threshold,"refusals" to row["refusals"].asLong(),
                    "limitation" to "UTC epoch 정렬 고정 창의 전사 거부 응답 합계입니다. 조회 범위 밖은 포함하지 않으며 이동 창·개인별 반복·인젝션·탈옥 공격 여부를 판단하지 않습니다. 창마다 거부 응답 구성원 5명 이상일 때만 표시합니다."))
        }
    }
}
