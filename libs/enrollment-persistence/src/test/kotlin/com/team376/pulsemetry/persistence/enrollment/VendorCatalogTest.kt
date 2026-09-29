package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog
import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional

@Transactional
class VendorCatalogTest : AbstractPersistenceIntegrationTest() {
    @Autowired private lateinit var jdbc: JdbcClient

    @Test fun `기존 제품과 플랜 ID 순서 및 과금 속성을 보존한다`() {
        val catalog = VendorCatalog(jdbc).snapshot()
        assertThat(catalog.products.map { it.id }).containsExactly("claude_team", "openai_biz", "cursor", "copilot", "gemini", "other")
        assertThat(catalog.products.sumOf { it.plans.size }).isEqualTo(12)
        assertThat(catalog.products.first().plans.map { it.id }).containsExactly("team", "enterprise")
        assertThat(catalog.products.single { it.id == "gemini" }.plans.map { it.separateUsageBilling }).containsOnly(false)
        assertThat(VendorCatalog(jdbc).snapshot().version).isEqualTo(catalog.version)
    }

    @Test fun `DB에서 추가한 제품과 플랜은 같은 저장소 인스턴스에 즉시 보인다`() {
        val catalog = VendorCatalog(jdbc)
        val before = catalog.snapshot().version
        jdbc.sql("INSERT INTO enrollment.vendor_catalog_vendors VALUES ('test_vendor','테스트 공급사')").update()
        jdbc.sql("INSERT INTO enrollment.vendor_catalog_products(id,vendor_id,display_name,product,allows_seat_tiers) VALUES ('test_product','test_vendor','테스트 제품','Test',false)").update()
        jdbc.sql("INSERT INTO enrollment.vendor_catalog_plans(product_id,id,display_name) VALUES ('test_product','team','테스트 플랜')").update()
        assertThat(catalog.find("test_product")!!.plans.map { it.id }).containsExactly("team")
        assertThat(catalog.snapshot().version).isNotEqualTo(before)
        val added = catalog.snapshot().version
        jdbc.sql("UPDATE enrollment.vendor_catalog_plans SET display_name='변경한 플랜' WHERE product_id='test_product'").update()
        assertThat(catalog.snapshot().version).isNotEqualTo(added)
    }

    @Test fun `비활성 플랜과 제품은 선택지에서 빠지고 전체 비활성화도 빈 목록이다`() {
        val catalog = VendorCatalog(jdbc)
        jdbc.sql("UPDATE enrollment.vendor_catalog_plans SET active=false WHERE product_id='claude_team' AND id='team'").update()
        assertThat(catalog.find("claude_team")!!.plans.map { it.id }).containsExactly("enterprise")
        jdbc.sql("UPDATE enrollment.vendor_catalog_products SET active=false WHERE id='claude_team'").update()
        assertThat(catalog.find("claude_team")).isNull()
        jdbc.sql("UPDATE enrollment.vendor_catalog_products SET active=false").update()
        assertThat(catalog.snapshot().products).isEmpty()
    }
}
