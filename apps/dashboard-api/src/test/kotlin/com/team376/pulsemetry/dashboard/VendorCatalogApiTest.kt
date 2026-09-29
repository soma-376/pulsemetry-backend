package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp.Companion.json
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class VendorCatalogApiTest : AbstractDashboardApiTest() {
    private val admin = DashboardPrincipal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN)
    private val headers = mapOf("Authorization" to TestDashboardAuthenticator.header(admin))

    @Test fun `DB 변경은 HTTP 선택지에 반영되고 이전 카탈로그 커서는 거부된다`() {
        val jdbc = DashboardTestStores.writer
        val id = "test_" + UUID.randomUUID().toString().replace("-", "")
        val first = json(http.send("/api/v1/vendor-catalog?limit=2", headers = headers))
        val cursor = first.path("nextCursor").asString()
        jdbc.sql("INSERT INTO enrollment.vendor_catalog_products(id,vendor_id,display_name,product,allows_seat_tiers) VALUES (:id,'other','새 제품','Test',false)").param("id", id).update()
        try {
            jdbc.sql("INSERT INTO enrollment.vendor_catalog_plans(product_id,id,display_name) VALUES (:id,'new_plan','새 플랜')").param("id", id).update()
            val found = json(http.send("/api/v1/vendor-catalog?q=$id", headers = headers))
            assertThat(found.path("items")[0].path("id").asString()).isEqualTo(id)
            assertThat(found.path("catalogVersion").asString()).isNotEqualTo(first.path("catalogVersion").asString())
            assertThat(http.send("/api/v1/vendor-catalog?limit=2&cursor=$cursor", headers = headers).statusCode()).isEqualTo(400)
            val plans = json(http.send("/api/v1/vendor-catalog/$id/plans", headers = headers))
            assertThat(plans.path("plans")[0].path("id").asString()).isEqualTo("new_plan")
            jdbc.sql("UPDATE enrollment.vendor_catalog_plans SET active=false WHERE product_id=:id").param("id", id).update()
            assertThat(json(http.send("/api/v1/vendor-catalog/$id/plans", headers = headers)).path("plans").size()).isZero()
            jdbc.sql("UPDATE enrollment.vendor_catalog_products SET active=false WHERE id=:id").param("id", id).update()
            assertThat(http.send("/api/v1/vendor-catalog/$id/plans", headers = headers).statusCode()).isEqualTo(404)
        } finally {
            jdbc.sql("DELETE FROM enrollment.vendor_catalog_plans WHERE product_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM enrollment.vendor_catalog_products WHERE id=:id").param("id", id).update()
        }
    }
    @Test fun `카탈로그는 인증과 관리자 권한을 요구한다`() {
        assertThat(http.send("/api/v1/vendor-catalog").statusCode()).isEqualTo(401)
        assertThat(http.send("/api/v1/vendor-catalog", headers = mapOf("Authorization" to TestDashboardAuthenticator.header(admin.copy(role = Role.MEMBER)))).statusCode()).isEqualTo(403)
    }
    @Test fun `벤더 검색과 커서 페이지가 플랜을 일괄 전송하지 않는다`() {
        val first = http.send("/api/v1/vendor-catalog?limit=2", headers = headers)
        assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(200)
        val page = json(first)
        assertThat(page.path("items").size()).isEqualTo(2)
        assertThat(page.path("items")[0].has("plans")).isFalse()
        val cursor = page.path("nextCursor").asString()
        val next = json(http.send("/api/v1/vendor-catalog?limit=2&cursor=$cursor", headers = headers))
        assertThat(next.path("items")[0].path("id").asString()).isNotEqualTo(page.path("items")[0].path("id").asString())
        assertThat(http.send("/api/v1/vendor-catalog?q=other&cursor=$cursor", headers = headers).statusCode()).isEqualTo(400)
        val found = json(http.send("/api/v1/vendor-catalog?q=anthropic", headers = headers))
        assertThat(found.path("totalCount").asInt()).isEqualTo(1)
        assertThat(found.path("items")[0].path("id").asString()).isEqualTo("claude_team")
    }
    @Test fun `플랜은 해당 벤더만 반환하며 잘못된 요청을 거부한다`() {
        val plans = http.send("/api/v1/vendor-catalog/claude_team/plans", headers = headers)
        assertThat(plans.statusCode()).isEqualTo(200)
        val rows = json(plans).path("plans")
        assertThat((0 until rows.size()).map { rows[it].path("id").asString() }).containsExactly("team", "enterprise")
        assertThat(http.send("/api/v1/vendor-catalog/missing/plans", headers = headers).statusCode()).isEqualTo(404)
        assertThat(http.send("/api/v1/vendor-catalog?limit=101", headers = headers).statusCode()).isEqualTo(400)
        assertThat(http.send("/api/v1/vendor-catalog?cursor=bad", headers = headers).statusCode()).isEqualTo(400)
    }
}
