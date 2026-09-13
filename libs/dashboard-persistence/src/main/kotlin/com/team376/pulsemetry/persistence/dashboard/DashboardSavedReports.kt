package com.team376.pulsemetry.persistence.dashboard

import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/** 저장 리포트는 실행을 참조한다. 실행 행 잠금과 인가 트랜잭션은 조립 앱에서 묶는다. */
class DashboardSavedReports(private val jdbc: JdbcClient) {
    fun lockRun(tenant: UUID, id: UUID): Boolean = jdbc.sql("SELECT id FROM dashboard.scenario_runs WHERE tenant_id=:tenant AND id=:id FOR UPDATE")
        .param("tenant",tenant).param("id",id).query(UUID::class.java).optional().isPresent

    fun save(id: UUID, tenant: UUID, run: UUID, member: UUID, name: String, note: String?, mode: String) {
        jdbc.sql("""INSERT INTO dashboard.saved_reports(id,tenant_id,run_id,created_by_member_id,name,note,time_mode)
            VALUES (:id,:tenant,:run,:member,:name,:note,CAST(:mode AS dashboard.time_mode))""")
            .param("id",id).param("tenant",tenant).param("run",run).param("member",member).param("name",name)
            .param("note",note,java.sql.Types.VARCHAR).param("mode",mode).update()
    }

    fun get(tenant: UUID, id: UUID): Map<String,Any?>? = jdbc.sql("$projection WHERE s.tenant_id=:tenant AND s.id=:id")
        .param("tenant",tenant).param("id",id).query { r, _ -> row(r) }.optional().orElse(null)

    fun list(tenant: UUID, member: UUID, owner: Boolean, teams: String, before: Instant?, beforeId: UUID?, limit: Int): List<Map<String,Any?>> {
        val where = mutableListOf("s.tenant_id=:tenant")
        val params = mutableMapOf<String,Any>("tenant" to tenant,"limit" to limit)
        if (!owner) {
            where += """r.created_by_member_id=:member AND r.execution->>'actor_role'='admin'
                AND r.execution->'organization_scope'='false'::jsonb AND jsonb_array_length(r.execution->'team_ids')>0
                AND r.execution->'team_ids' <@ CAST(:teams AS jsonb)"""
            params["member"]=member; params["teams"]=teams
        }
        before?.let {
            where += "(s.created_at,s.id)<(:before,:beforeId)"
            params["before"]=java.sql.Timestamp.from(it); params["beforeId"]=requireNotNull(beforeId)
        }
        return jdbc.sql("$projection WHERE ${where.joinToString(" AND ")} ORDER BY s.created_at DESC,s.id DESC LIMIT :limit")
            .params(params).query { r, _ -> row(r) }.list()
    }

    fun delete(tenant: UUID, id: UUID): Boolean = jdbc.sql("DELETE FROM dashboard.saved_reports WHERE tenant_id=:tenant AND id=:id")
        .param("tenant",tenant).param("id",id).update()==1

    fun hasSaved(tenant: UUID, run: UUID): Boolean = jdbc.sql("SELECT EXISTS(SELECT 1 FROM dashboard.saved_reports WHERE tenant_id=:tenant AND run_id=:run)")
        .param("tenant",tenant).param("run",run).query(Boolean::class.java).single()

    fun deleteRun(tenant: UUID, id: UUID): Boolean = jdbc.sql("DELETE FROM dashboard.scenario_runs WHERE tenant_id=:tenant AND id=:id AND status NOT IN ('queued','running')")
        .param("tenant",tenant).param("id",id).update()==1

    private val projection = """SELECT s.id,s.run_id,s.name,s.note,s.time_mode::text,s.created_by_member_id,s.created_at,
        r.scenario_id,m.display_name FROM dashboard.saved_reports s
        JOIN dashboard.scenario_runs r ON r.id=s.run_id AND r.tenant_id=s.tenant_id
        JOIN enrollment.members m ON m.id=s.created_by_member_id AND m.tenant_id=s.tenant_id"""
    private fun row(r: java.sql.ResultSet): Map<String,Any?> = mapOf(
        "saved_id" to r.getObject("id",UUID::class.java),"run_id" to r.getObject("run_id",UUID::class.java),
        "scenario_id" to r.getString("scenario_id"),"name" to r.getString("name"),"note" to r.getString("note"),
        "time_mode" to r.getString("time_mode"),"created_at" to r.getTimestamp("created_at").toInstant().toString(),
        "created_by" to mapOf("member_id" to r.getObject("created_by_member_id",UUID::class.java),"display_name" to r.getString("display_name")),
        "share_path" to "/runs/${r.getObject("run_id",UUID::class.java)}")
}
