package com.team376.pulsemetry.dashboard.ingest

import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 원천과 별개인 dashboard 자기 캐시 계정으로만 쓴다. 같은 기준의 최초 결과는 만료까지 불변이다. */
class LossSnapshotStore(private val cache: JdbcClient, private val mapper: ObjectMapper) {
    fun find(tenant: UUID, end: Instant, now: Instant): LossSnapshot? = cache.sql("""SELECT payload::text
        FROM dashboard_cache.transmission_loss_snapshots WHERE tenant_id=:tenant AND window_end=:end AND expires_at>:now""")
        .param("tenant", tenant).param("end", end.toString()).param("now", Timestamp.from(now))
        .query { rs, _ -> mapper.readValue(rs.getString(1), LossSnapshot::class.java) }.optional().orElse(null)

    fun save(tenant: UUID, end: Instant, snapshot: LossSnapshot, now: Instant): LossSnapshot {
        cache.sql("DELETE FROM dashboard_cache.transmission_loss_snapshots WHERE expires_at<=:now")
            .param("now", Timestamp.from(now)).update()
        cache.sql("""INSERT INTO dashboard_cache.transmission_loss_snapshots(tenant_id,window_end,expires_at,payload)
            VALUES (:tenant,:end,:expires,CAST(:payload AS jsonb)) ON CONFLICT (tenant_id,window_end) DO NOTHING""")
            .param("tenant", tenant).param("end", end.toString()).param("expires", Timestamp.from(end.plus(LIFETIME)))
            .param("payload", mapper.writeValueAsString(snapshot)).update()
        return requireNotNull(find(tenant, end, now)) { "손실 조회 기준이 만료되었다" }
    }

    companion object { val LIFETIME: Duration = Duration.ofMinutes(10) }
}

data class LossSnapshot(val summary: TransmissionLoss, val installations: List<LossInstallation>)
