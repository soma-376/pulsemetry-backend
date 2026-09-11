package com.team376.pulsemetry.persistence.dashboard

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

data class DashboardRunRow(val id: UUID, val tenant: UUID, val scenario: String, val status: String,
    val params: String, val execution: String, val from: Instant, val to: Instant, val creator: UUID,
    val created: Instant, val finished: Instant?, val result: String?, val error: String?, val step: Int, val claim: UUID?)

/** tenant 행 잠금으로 admission을 직렬화하고 claim token으로 늦게 끝난 워커의 덮어쓰기를 막는다. */
class DashboardRuns(private val jdbc: JdbcClient, manager: PlatformTransactionManager) {
    private val tx = TransactionTemplate(manager)
    fun enqueue(id: UUID, tenant: UUID, scenario: String, params: String, execution: String,
        from: Instant, to: Instant, creator: UUID): Boolean = tx.execute {
        jdbc.sql("SELECT id FROM enrollment.tenants WHERE id=:tenant FOR UPDATE").param("tenant",tenant).query(UUID::class.java).single()
        val active = jdbc.sql("SELECT count(*) FROM dashboard.scenario_runs WHERE tenant_id=:tenant AND status IN ('queued','running')")
            .param("tenant",tenant).query(Long::class.java).single()
        if (active >= 3) return@execute false
        jdbc.sql("""INSERT INTO dashboard.scenario_runs(id,tenant_id,scenario_id,params,execution,resolved_from,resolved_to,created_by_member_id)
            VALUES (:id,:tenant,:scenario,CAST(:params AS jsonb),CAST(:execution AS jsonb),:from,:to,:creator)""")
            .param("id",id).param("tenant",tenant).param("scenario",scenario).param("params",params).param("execution",execution)
            .param("from",java.sql.Timestamp.from(from)).param("to",java.sql.Timestamp.from(to)).param("creator",creator).update()
        true
    }

    fun get(tenant: UUID, id: UUID): DashboardRunRow? = jdbc.sql("SELECT * FROM dashboard.scenario_runs WHERE tenant_id=:tenant AND id=:id")
        .param("tenant",tenant).param("id",id).query { r, _ -> row(r) }.optional().orElse(null)

    /** 인가와 필터를 페이지 절단 전에 적용하고 큰 결과 프레임은 읽지 않는다. */
    fun list(tenant: UUID, member: UUID, owner: Boolean, teamsJson: String, scenario: String?, status: String?,
        creator: UUID?, beforeTime: Instant?, beforeId: UUID?, limit: Int): List<Map<String,Any?>> {
        val conditions = mutableListOf("r.tenant_id=:tenant")
        val params = mutableMapOf<String,Any>("tenant" to tenant,"limit" to limit)
        if (!owner) {
            conditions += """r.created_by_member_id=:member AND r.execution->>'actor_role'='admin'
                AND r.execution->'organization_scope'='false'::jsonb
                AND jsonb_array_length(r.execution->'team_ids')>0
                AND r.execution->'team_ids' <@ CAST(:teams AS jsonb)"""
            params["member"]=member; params["teams"]=teamsJson
        }
        scenario?.let { conditions += "r.scenario_id=:scenario"; params["scenario"]=it }
        status?.let { conditions += "r.status::text=:status"; params["status"]=it }
        creator?.let { conditions += "r.created_by_member_id=:creator"; params["creator"]=it }
        beforeTime?.let {
            conditions += "(r.created_at,r.id)<(:beforeTime,:beforeId)"
            params["beforeTime"]=java.sql.Timestamp.from(it); params["beforeId"]=requireNotNull(beforeId)
        }
        return jdbc.sql("""SELECT r.id,r.scenario_id,r.status::text,r.created_at,r.finished_at,
            r.resolved_from,r.resolved_to,r.created_by_member_id,m.display_name,
            (SELECT count(*) FROM jsonb_array_elements(r.result->'findings') f WHERE f->>'severity'='info') AS info_count,
            (SELECT count(*) FROM jsonb_array_elements(r.result->'findings') f WHERE f->>'severity'='warning') AS warning_count,
            (SELECT count(*) FROM jsonb_array_elements(r.result->'findings') f WHERE f->>'severity'='anomaly') AS anomaly_count,
            (SELECT s.id FROM dashboard.saved_reports s WHERE s.tenant_id=r.tenant_id AND s.run_id=r.id
                ORDER BY s.created_at DESC,s.id DESC LIMIT 1) AS saved_id
            FROM dashboard.scenario_runs r JOIN enrollment.members m ON m.id=r.created_by_member_id AND m.tenant_id=r.tenant_id
            WHERE ${conditions.joinToString(" AND ")} ORDER BY r.created_at DESC,r.id DESC LIMIT :limit""")
            .params(params).query { r, _ -> mapOf<String,Any?>(
                "run_id" to r.getObject("id",UUID::class.java),"scenario_id" to r.getString("scenario_id"),
                "status" to r.getString("status"),"created_at" to r.getTimestamp("created_at").toInstant().toString(),
                "finished_at" to r.getTimestamp("finished_at")?.toInstant()?.toString(),
                "params_summary" to "${r.getTimestamp("resolved_from").toInstant()} ~ ${r.getTimestamp("resolved_to").toInstant()}",
                "created_by" to mapOf("member_id" to r.getObject("created_by_member_id",UUID::class.java),"display_name" to r.getString("display_name")),
                "findings_count" to mapOf("info" to r.getLong("info_count"),"warning" to r.getLong("warning_count"),"anomaly" to r.getLong("anomaly_count")),
                "saved_id" to r.getObject("saved_id",UUID::class.java)) }.list()
    }

    fun claim(): DashboardRunRow? = tx.execute {
        jdbc.sql("""UPDATE dashboard.scenario_runs SET status='failed',finished_at=now(),lease_until=NULL,claim_token=NULL,
            error='{"error":"worker_lease_expired","message":"워커 lease가 만료되었습니다"}'::jsonb
            WHERE status='running' AND lease_until < now()""").update()
        val id = jdbc.sql("""SELECT id FROM dashboard.scenario_runs WHERE status='queued'
            ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED""").query(UUID::class.java).optional().orElse(null)
            ?: return@execute null
        jdbc.sql("""UPDATE dashboard.scenario_runs SET status='running',claim_token=:claim,lease_until=now()+interval '2 minutes'
            WHERE id=:id RETURNING *""").param("claim",UUID.randomUUID()).param("id",id).query { r, _ -> row(r) }.single()
    }

    fun progress(row: DashboardRunRow, step: Int): Boolean = jdbc.sql("""UPDATE dashboard.scenario_runs
        SET progress_step=:step,lease_until=now()+interval '2 minutes'
        WHERE id=:id AND status='running' AND claim_token=:claim AND lease_until>=now()""")
        .param("step",step).param("id",row.id).param("claim",row.claim).update() == 1

    fun finish(row: DashboardRunRow, result: String?, error: String?): Boolean = jdbc.sql("""UPDATE dashboard.scenario_runs
        SET status=CAST(:status AS dashboard.run_status),result=CAST(:result AS jsonb),error=CAST(:error AS jsonb),
        finished_at=now(),lease_until=NULL,claim_token=NULL
        WHERE id=:id AND status='running' AND claim_token=:claim AND lease_until>=now()""")
        .param("status",if (error==null) "succeeded" else "failed")
        .param("result",result,java.sql.Types.VARCHAR).param("error",error,java.sql.Types.VARCHAR)
        .param("id",row.id).param("claim",row.claim).update() == 1

    fun cancel(tenant: UUID, id: UUID): Boolean = jdbc.sql("""UPDATE dashboard.scenario_runs SET status='cancelled',finished_at=now(),
        lease_until=NULL,claim_token=NULL WHERE tenant_id=:tenant AND id=:id AND status IN ('queued','running')""")
        .param("tenant",tenant).param("id",id).update() == 1

    private fun row(r: java.sql.ResultSet) = DashboardRunRow(r.getObject("id",UUID::class.java),r.getObject("tenant_id",UUID::class.java),
        r.getString("scenario_id"),r.getString("status"),r.getString("params"),r.getString("execution"),
        r.getTimestamp("resolved_from").toInstant(),r.getTimestamp("resolved_to").toInstant(),r.getObject("created_by_member_id",UUID::class.java),
        r.getTimestamp("created_at").toInstant(),r.getTimestamp("finished_at")?.toInstant(),r.getString("result"),r.getString("error"),
        r.getInt("progress_step"),r.getObject("claim_token",UUID::class.java))
}
