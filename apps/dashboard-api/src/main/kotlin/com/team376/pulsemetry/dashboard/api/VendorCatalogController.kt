package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.util.Base64
import java.util.Locale

/** 전역 선택지다. 조직의 등록 벤더 ID(UUID)와 카탈로그 ID(kind)는 다른 식별자다. */
@RestController
class VendorCatalogController(private val mapper: ObjectMapper, private val catalog: VendorCatalog) {
    data class Vendor(val id: String, val provider: String, val displayName: String, val product: String, val allowsSeatTiers: Boolean)
    data class Page(val catalogVersion: String, val items: List<Vendor>, val totalCount: Int, val nextCursor: String?)
    data class Plans(val catalogVersion: String, val vendor: Vendor, val plans: List<VendorCatalog.Plan>)
    private fun summary(p: VendorCatalog.Product) = Vendor(p.id, p.provider, p.displayName, p.product, p.allowsSeatTiers)
    private fun authorize(principal: DashboardPrincipal) {
        if (principal.role != Role.ADMIN) throw DashboardException(ErrorCode.FORBIDDEN)
    }
    private fun invalid(): Nothing = throw DashboardException(ErrorCode.INVALID_REQUEST)

    @GetMapping("/api/v1/vendor-catalog")
    fun vendors(@AuthenticationPrincipal principal: DashboardPrincipal, @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "20") limit: Int, @RequestParam(required = false) cursor: String?): Page {
        authorize(principal)
        if (q.length > 200 || limit !in 1..100) invalid()
        val query = q.trim().lowercase(Locale.ROOT)
        val snapshot = catalog.snapshot()
        val matches = snapshot.products.filter { product ->
            listOf(product.id, product.provider, product.displayName, product.product).any { it.lowercase(Locale.ROOT).contains(query) }
        }.sortedBy { it.id }
        val after = cursor?.let {
            if (it.length > 2048) invalid()
            val value = runCatching { mapper.readTree(Base64.getUrlDecoder().decode(it)) }.getOrNull() ?: invalid()
            if (value.path("v").asString() != snapshot.version || value.path("q").asString() != query) invalid()
            value.path("after").asString().also { id -> if (matches.none { it.id == id }) invalid() }
        }
        val candidates = matches.filter { after == null || it.id > after }
        val page = candidates.take(limit)
        val next = if (candidates.size > limit) Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(
            mapOf("v" to snapshot.version, "q" to query, "after" to page.last().id))) else null
        return Page(snapshot.version, page.map(::summary), matches.size, next)
    }

    @GetMapping("/api/v1/vendor-catalog/{vendorId}/plans")
    fun plans(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable vendorId: String): Plans {
        authorize(principal)
        val snapshot = catalog.snapshot()
        val product = snapshot.products.find { it.id == vendorId } ?: throw DashboardException(ErrorCode.NOT_FOUND)
        return Plans(snapshot.version, summary(product), product.plans)
    }
}
