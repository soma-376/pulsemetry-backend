package com.team376.pulsemetry.dashboard.meta

import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

/**
 * 조직 디렉터리(팀) 조회. tenant 와 현재 팀 범위를 SQL 단계에서 제한한다.
 *
 * `/v1/meta/teams` 와 `/v1/meta/filters` 가 같은 팀 목록을 쓴다. 컨트롤러가 다른 컨트롤러를 주입받지 않도록
 * 조회를 여기에 둔다. 인가 판정(`DashboardAccess.teamIds`)과는 다른 것이다 — 이쪽은 표시용 컬럼
 * (`name` · `status` · `member_count`)을 돌려주고 보관된 팀을 거른다.
 */
@Service
class DashboardDirectory(private val jdbc: JdbcClient) {
    fun teams(user: UserIdentity, archived: Boolean): List<Map<String, Any?>> = jdbc.sql("""SELECT t.id AS team_id,t.name,t.status::text,
            (SELECT count(DISTINCT m.id) FROM enrollment.team_memberships tm JOIN enrollment.members m ON m.id=tm.member_id
             WHERE tm.team_id=t.id AND tm.left_at IS NULL AND m.status='active' AND m.tenant_id=t.tenant_id) AS member_count
            FROM enrollment.teams t WHERE t.tenant_id=:tenant AND (:archived OR t.status='active')
            AND (:owner OR EXISTS (SELECT 1 FROM enrollment.team_memberships own WHERE own.team_id=t.id
            AND own.member_id=:member AND own.left_at IS NULL)) ORDER BY t.name,t.id""")
            .param("tenant", user.tenantId).param("archived", archived).param("owner", user.role == "owner")
            .param("member", user.memberId).query().listOfRows()
}
