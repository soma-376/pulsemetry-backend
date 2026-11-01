package com.team376.pulsemetry.persistence.enrollment.directory

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.time.Instant
import java.sql.Timestamp

/** 명령 트랜잭션이 호출하는 저장 연산. HTTP 경로로 업무를 선택하지 않는다. */
class DirectoryCommandStore(private val jdbc: JdbcClient) {
    fun insertTeam(id: UUID, tenant: UUID, name: String, now: Instant) = jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name,created_at,updated_at) VALUES (:id,:tenant,:name,:now,:now)")
            .param("id", id).param("tenant", tenant).param("name", name).param("now", Timestamp.from(now)).update()

    fun teamNames(tenant: UUID) = jdbc.sql("SELECT id,name FROM enrollment.teams WHERE tenant_id=:tenant").param("tenant", tenant)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()

    fun renameTeam(name: String, updated: Instant, tenant: UUID, id: UUID) = jdbc.sql("UPDATE enrollment.teams SET name=:name,updated_at=:now WHERE tenant_id=:tenant AND id=:id")
            .param("name", name).param("now", Timestamp.from(updated)).param("tenant", tenant).param("id", id).update()

    fun advanceTeamMembers(now: Instant, tenant: UUID, id: UUID) = jdbc.sql("""UPDATE enrollment.members SET updated_at=GREATEST(date_trunc('milliseconds',:now::timestamptz),updated_at+interval '1 millisecond')
            WHERE tenant_id=:tenant AND id IN (SELECT member_id FROM enrollment.team_memberships WHERE team_id=:id AND left_at IS NULL)""")
            .param("now", Timestamp.from(now)).param("tenant", tenant).param("id", id).update()

    fun closeTeamMemberships(now: Instant, id: UUID) = jdbc.sql("UPDATE enrollment.team_memberships SET left_at=:now WHERE team_id=:id AND left_at IS NULL")
            .param("now", Timestamp.from(now)).param("id", id).update()

    fun archiveTeam(updated: Instant, tenant: UUID, id: UUID) = jdbc.sql("UPDATE enrollment.teams SET status='archived',updated_at=:now WHERE tenant_id=:tenant AND id=:id")
            .param("now", Timestamp.from(updated)).param("tenant", tenant).param("id", id).update()

    fun lockTeamVersion(tenant: UUID, id: UUID) = jdbc.sql("SELECT updated_at FROM enrollment.teams WHERE tenant_id=:tenant AND id=:id AND status='active' FOR UPDATE")
        .param("tenant", tenant).param("id", id).query { rs, _ -> rs.getTimestamp(1).toInstant() }.optional().orElse(null)

    fun lockAssignableMemberVersion(tenant: UUID, member: UUID) = jdbc.sql("SELECT updated_at FROM enrollment.members WHERE tenant_id=:tenant AND id=:id AND status!='suspended' FOR UPDATE")
                .param("tenant", tenant).param("id", member).query { rs, _ -> rs.getTimestamp(1).toInstant() }.optional().orElse(null)

    fun updateMemberVersion(updated: Instant, member: UUID) = jdbc.sql("UPDATE enrollment.members SET updated_at=:now WHERE id=:id").param("now", Timestamp.from(updated)).param("id", member).update()

    fun closeMemberMemberships(now: Instant, member: UUID) = jdbc.sql("UPDATE enrollment.team_memberships SET left_at=:now WHERE member_id=:id AND left_at IS NULL")
            .param("now", Timestamp.from(now)).param("id", member).update()

    fun lockMember(tenant: UUID, id: UUID) = jdbc.sql("SELECT role::text,status::text,updated_at FROM enrollment.members WHERE tenant_id=:tenant AND id=:id FOR UPDATE")
            .param("tenant", tenant).param("id", id).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()) }
            .optional().orElse(null)

    fun openTeams(id: UUID) = jdbc.sql("SELECT team_id FROM enrollment.team_memberships WHERE member_id=:id AND left_at IS NULL")
            .param("id", id).query(UUID::class.java).list().toSet()

    fun plannedVendors(id: UUID) = jdbc.sql("SELECT planned_vendor_ids FROM enrollment.members WHERE id=:id").param("id", id)
            .query { rs, _ -> (rs.getArray(1).array as Array<*>).map { it as String } }.single()

    fun updateMember(vendors: List<String>, role: String, updated: Instant, id: UUID) = jdbc.sql("UPDATE enrollment.members SET role=CAST(:role AS enrollment.member_role),planned_vendor_ids=CAST(:vendors AS text[]),updated_at=:now WHERE id=:id")
                .param("vendors", vendors.joinToString(",", "{", "}")).param("role", role).param("now", Timestamp.from(updated)).param("id", id).update()

    fun currentTeam(id: UUID) = jdbc.sql("""SELECT t.id,t.name FROM enrollment.team_memberships tm JOIN enrollment.teams t ON t.id=tm.team_id
            WHERE tm.member_id=:id AND tm.left_at IS NULL""").param("id", id)
            .query { rs, _ -> mapOf("teamId" to rs.getString(1), "teamName" to rs.getString(2)) }.list().singleOrNull()

    fun lockRegisteredVendor(tenant: UUID, id: String) = jdbc.sql("SELECT vendor_id FROM enrollment.managed_vendors WHERE tenant_id=:tenant AND vendor_id=:id AND NOT archived FOR SHARE")
            .param("tenant", tenant).param("id", id).query(String::class.java).optional().isPresent

    fun activeTeamCount(tenant: UUID, team: UUID) = jdbc.sql("SELECT count(*) FROM enrollment.teams WHERE tenant_id=:tenant AND id=:id AND status='active'").param("tenant", tenant).param("id", team).query(Int::class.java).single()

    fun membersByEmail(tenant: UUID, email: String) = jdbc.sql("SELECT id,status::text FROM enrollment.members WHERE tenant_id=:tenant AND lower(email)=:email")
                .param("tenant", tenant).param("email", email).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()

    fun insertInvitedMember(member: UUID, tenant: UUID, email: String, role: String, now: Instant) = jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role,status,created_at,updated_at) VALUES (:id,:tenant,:email,CAST(:role AS enrollment.member_role),'invited',:now,:now)")
                        .param("id", member).param("tenant", tenant).param("email", email).param("role", role).param("now", Timestamp.from(now)).update()

    fun updatePlannedVendors(vendors: List<String>, member: UUID) = jdbc.sql("UPDATE enrollment.members SET planned_vendor_ids=CAST(:vendors AS text[]) WHERE id=:id")
                    .param("vendors", vendors.joinToString(",", "{", "}")).param("id", member).update()

    fun lockMemberVersion(member: UUID) = jdbc.sql("SELECT updated_at FROM enrollment.members WHERE id=:id FOR UPDATE").param("id", member)
            .query { rs, _ -> rs.getTimestamp(1).toInstant() }.single()

    fun memberOpenTeams(member: UUID) = jdbc.sql("SELECT team_id FROM enrollment.team_memberships WHERE member_id=:id AND left_at IS NULL")
            .param("id", member).query(UUID::class.java).list().toSet()

    fun updateInvitedRole(role: String, updated: Instant, member: UUID) = jdbc.sql("UPDATE enrollment.members SET role=CAST(:role AS enrollment.member_role),updated_at=:now WHERE id=:id")
            .param("role", role).param("now", Timestamp.from(updated)).param("id", member).update()

    fun organizationName(tenant: UUID) = jdbc.sql("SELECT name FROM enrollment.tenants WHERE id=:id").param("id", tenant).query(String::class.java).single()

    fun memberEmail(member: UUID) = jdbc.sql("SELECT email FROM enrollment.members WHERE id=:id").param("id", member).query(String::class.java).single()

    fun lockMemberContact(tenant: UUID, id: UUID) = jdbc.sql("SELECT email,status::text,updated_at FROM enrollment.members WHERE tenant_id=:tenant AND id=:id FOR UPDATE")
            .param("tenant", tenant).param("id", id).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()) }
            .optional().orElse(null)

    fun tenantName(tenant: UUID) = jdbc.sql("SELECT name FROM enrollment.tenants WHERE id=:id").param("id", tenant).query(String::class.java).single()

    fun insertMembership(member: UUID, team: UUID, now: Instant) = jdbc.sql("INSERT INTO enrollment.team_memberships(id,member_id,team_id,joined_at) VALUES (:id,:member,:team,:now)")
            .param("id", UUID.randomUUID()).param("member", member).param("team", team).param("now", Timestamp.from(now)).update()
}
