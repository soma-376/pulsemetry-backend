package com.team376.pulsemetry.enrollment.vendor.application

import com.team376.pulsemetry.persistence.enrollment.seat.SeatSourceView
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

import com.team376.pulsemetry.persistence.enrollment.management.*
import com.team376.pulsemetry.persistence.enrollment.vendor.VendorCommandStore
import com.team376.pulsemetry.enrollment.management.CommandRules.fail

/** 기능의 명시적 관리 명령. 공통 실행 관문의 트랜잭션 안에서 호출한다. */
class VendorCommands(private val vendors: VendorCommandStore, private val catalog: VendorCatalog) {
    private val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
    fun saveVendor(tenant: UUID, actor: UUID, vendor: String?, input: VendorInput, now: Instant): Map<String, Any?> {
        val today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        val displayName = input.displayName
        val previous = vendor?.let { vendorRow(tenant, it) }
        if (vendor != null && previous == null) fail("not_found", 404)
        if (previous != null && previous.path("version").asLong() != input.expectedVersion) fail("version_conflict", 409)
        val kind = previous?.path("kind")?.asString() ?: input.kind
        val product = catalog.find(kind) ?: fail("invalid_vendor", 422, "kind")
        if (vendor == null) {
            val duplicate = vendors.registeredKindExists(tenant, kind)
            if (duplicate) fail("vendor_already_registered", 409, "kind")
        }
        if (vendor == null && !input.hasContract) {
            val vendorId = UUID.randomUUID().toString()
            vendors.insertVendor(tenant, vendorId, kind, now)
            appendVendor(tenant, actor, vendorId, now.toEpochMilli(), displayName, null, false, now)
            return vendorResponse(tenant, vendorRow(tenant, vendorId)!!, now)
        }
        val contractInput = input.contract
        val plan = contractInput.planId
        if (product.plans.none { it.id == plan }) fail("invalid_plan", 422, "contract.planId")
        // 정정은 기존 시작일을 유지한다. 클라이언트가 보낸 다른 시작일로 갱신하지 않는다.
        val previousContract = previous?.path("contract")?.takeUnless { it.isNull || it.isMissingNode }
        val from = previousContract?.path("effectiveFrom")?.asString()?.let(LocalDate::parse) ?: contractInput.effectiveFrom
        val to = contractInput.effectiveTo
        if ((previousContract == null && from < today) || (to != null && (to < from || (to < today && to.toString() != previousContract?.path("effectiveTo")?.asString())))) fail("invalid_contract_period", 422, "contract.effectiveFrom")
        val tiers = contractInput.tiers
        if (tiers.size !in 1..(if (product.allowsSeatTiers) 3 else 1)) fail("invalid_request", 400, "contract.tiers")
        var monthly = BigDecimal.ZERO
        val parsed = tiers.indices.map { index ->
            val tier = tiers[index]
            val seats = tier.seats
            val feeText = tier.monthlyFeePerSeatUsd
            if (seats !in 1..9007199254740991L || !Regex("[0-9]+(\\.[0-9]{1,12})?").matches(feeText)) fail("invalid_request", 400, "contract.tiers")
            val fee = feeText.toBigDecimal()
            monthly += fee * seats.toBigDecimal()
            if (monthly > BigDecimal("9007199254740991")) fail("invalid_request", 400, "contract.tiers")
            mapOf("tierId" to UUID.randomUUID().toString(), "label" to tier.label, "seats" to seats, "monthlyFeePerSeatUsd" to fee.toPlainString())
        }
        val version = maxOf(now.toEpochMilli(), (previous?.path("version")?.asLong() ?: 0) + 1)
        val vendorId = vendor ?: UUID.randomUUID().toString()
        if (vendor == null) vendors.insertRegisteredVendor(tenant, vendorId, kind, now)
        val contract = mapOf("version" to version, "planId" to plan, "effectiveFrom" to from.toString(), "effectiveTo" to to?.toString(),
            "termNote" to contractInput.termNote,
            "tiers" to parsed, "monthlySeatFeeUsd" to monthly.toPlainString(), "confirmedAt" to now.toString(), "confirmedBy" to actor.toString())
        appendVendor(tenant, actor, vendorId, version, displayName, mapper.valueToTree(contract), false, now)
        return vendorResponse(tenant, vendorRow(tenant, vendorId)!!, now)
    }

    fun renameVendor(tenant: UUID, actor: UUID, id: String, input: VendorInput, now: Instant): Map<String, Any?> {
        val previous = vendorRow(tenant, id) ?: fail("not_found", 404)
        if (previous.path("version").asLong() != input.expectedVersion) fail("version_conflict", 409)
        val name = input.displayName
        val version = maxOf(now.toEpochMilli(), previous.path("version").asLong() + 1)
        val contract = previous.path("contract").takeUnless { it.isNull }
        appendVendor(tenant, actor, id, version, name, contract, false, now)
        return vendorResponse(tenant, vendorRow(tenant, id)!!, now)
    }

    fun removeVendor(tenant: UUID, actor: UUID, id: String, contractOnly: Boolean, version: Long?, now: Instant): Map<String, Any?> {
        val previous = vendorRow(tenant, id) ?: fail("not_found", 404)
        if (version == null) fail("invalid_request", 400, "If-Match")
        if (previous.path("version").asLong() != version) fail("version_conflict", 409)
        if (!contractOnly && previous.path("source").asString() == "detected") fail("detected_vendor", 422)
        if (!contractOnly) vendors.archiveVendor(tenant, id)
        // 보관한 제품의 벤더 연결은 자격증명과 함께 지운다(ADR 0048 §6).
        if (!contractOnly) vendors.eraseConnection(tenant, id, actor, now)
        appendVendor(tenant, actor, id, maxOf(now.toEpochMilli(), version + 1), previous.path("displayName").asString(), null, !contractOnly, now)
        return emptyMap<String, String>()
    }

    private fun appendVendor(tenant: UUID, actor: UUID, id: String, version: Long, name: String, contract: JsonNode?, archived: Boolean, now: Instant) {
        vendors.appendVersion(tenant, id, version, name, contract, archived, now, actor)
    }

    private fun vendorRow(tenant: UUID, id: String): JsonNode? = vendors.findVendor(tenant, id)

    private fun vendorResponse(tenant: UUID, row: JsonNode, now: Instant): Map<String, Any?> = mapOf(
        "meta" to mapOf("organizationId" to tenant, "generatedAt" to now.toString(), "asOf" to now.toString(),
            "snapshotId" to "d.${Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(mapOf("v" to 1, "k" to "settings", "t" to tenant.toString(), "a" to now.toEpochMilli())))}",
            "currency" to "USD", "timeZone" to "Asia/Seoul"),
        "vendor" to mapOf("vendorId" to row.path("vendorId"), "displayName" to row.path("displayName"), "kind" to row.path("kind"), "source" to row.path("source"),
            "version" to row.path("version"), "firstSeenAt" to null, "lastSeenAt" to null, "activeUsers7d" to null, "activeUsers30d" to null,
            "observation" to "unobserved", "state" to if (contractStatus(row.path("contract"), now) == ContractStatus.active) "configured" else "needs_review",
            "contractStatus" to contractStatus(row.path("contract"), now), "contract" to row.path("contract"),
            "meteredMonthToDate" to mapOf("availability" to "unavailable", "reason" to "source_not_available", "data" to null), "checks" to emptyList<String>(),
            // 좌석 원천(ADR 0048) — 설정 조회의 벤더와 같은 모양이다.
            "seatSource" to SeatSourceView.of(row.path("kind").asString(), row.path("contract").path("planId").takeIf { it.isString }?.asString(),
                vendors.connections(tenant).find { it.vendorId == row.path("vendorId").asString() })))

    private fun contractStatus(contract: JsonNode, now: Instant): ContractStatus = ContractStatus.at(
        contract.path("effectiveFrom").takeUnless { it.isNull || it.isMissingNode }?.asString()?.let(LocalDate::parse),
        contract.path("effectiveTo").takeUnless { it.isNull || it.isMissingNode }?.asString()?.let(LocalDate::parse), now)
}
