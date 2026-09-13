package com.team376.pulsemetry.dashboard.finding

import com.team376.pulsemetry.persistence.telemetry.DashboardClickHouseReader
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** 현재 활성 설치의 보존 이력만 조회한다. 과거 라이선스·상태를 복원하지 않는다. */
@Service
class DashboardInactivity(private val jdbc: JdbcClient, private val reader: DashboardClickHouseReader,
    private val mapper: ObjectMapper) {
    fun findings(user: UserIdentity, asOf: Instant, inactiveDays: Int): List<Map<String,Any>> {
        if(user.role!="owner") throw UserAuthException("forbidden",403)
        require(inactiveDays in 1..365)
        val candidates = jdbc.sql("""SELECT i.id,i.member_id,i.created_at FROM enrollment.installations i
            JOIN enrollment.members m ON m.id=i.member_id AND m.tenant_id=i.tenant_id
            WHERE i.tenant_id=:tenant AND i.status='active' AND m.status='active' AND i.created_at<:at
            ORDER BY i.id LIMIT 5001""")
            .param("tenant",user.tenantId).param("at",OffsetDateTime.ofInstant(asOf,java.time.ZoneOffset.UTC)).query().listOfRows()
        if(candidates.size>5000) throw DashboardReadException("query_too_wide",422)
        if(candidates.isEmpty()) return emptyList()
        val rows = mapper.readTree(reader.query("""SELECT installation_id,toString(max(ts)) AS last_at
            FROM enriched_events FINAL WHERE tenant_id={tenant:String}
                AND has({installations:Array(String)},installation_id) AND ts<parseDateTime64BestEffort({as_of:String},6,'UTC')
            GROUP BY installation_id""",mapOf("tenant" to user.tenantId.toString(),"as_of" to asOf.toString(),
                "installations" to candidates.joinToString(",","[","]") { "'${it["id"]}'" }),Duration.ofSeconds(30)))["data"]
        val last = rows.toList().associate { it["installation_id"].asString() to
            Instant.parse(it["last_at"].asString().replace(' ','T')+"Z") }
        val cutoff = asOf.minus(Duration.ofDays(inactiveDays.toLong()))
        val inactive = candidates.filter { row ->
            val created = when(val value = row["created_at"]) {
                is OffsetDateTime -> value.toInstant()
                is java.sql.Timestamp -> value.toInstant()
                else -> error("unexpected_timestamp_type")
            }
            (last[row["id"].toString()] ?: created) <= cutoff
        }
        // 설치 수 대신 실제 구성원 수로 소집단을 판정한다.
        if(inactive.map { it["member_id"] }.distinct().size<5) return emptyList()
        return listOf(mapOf("rule_id" to "observed_inactive_installations","severity" to "info","widget_id" to "W3.2",
            "title" to "최근 활동이 관측되지 않은 설치가 있습니다",
            "evidence" to mapOf("as_of" to asOf.toString(),"inactive_days" to inactiveDays,
                "cutoff" to cutoff.toString(),"installations" to inactive.size,
                "limitation" to "현재 활성 구성원·설치 중 기준 시점 이전에 생성된 설치의 보존 이력입니다. 마지막 관측 또는 미관측 설치의 생성 시점이 기준 경과일 이상인 경우이며 수집 누락·이력 삭제·실제 사용·라이선스 회수 가능성·절감액을 판단하지 않습니다.")))
    }
}
