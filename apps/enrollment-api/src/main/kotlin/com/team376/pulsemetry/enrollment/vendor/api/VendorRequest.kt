package com.team376.pulsemetry.enrollment.vendor.api

import com.team376.pulsemetry.enrollment.management.CommandValues.date
import com.team376.pulsemetry.enrollment.management.CommandRules.fail
import com.team376.pulsemetry.enrollment.management.CommandValues.long
import com.team376.pulsemetry.enrollment.management.CommandValues.text
import com.team376.pulsemetry.enrollment.vendor.application.ContractInput
import com.team376.pulsemetry.enrollment.vendor.application.TierInput
import com.team376.pulsemetry.enrollment.vendor.application.VendorInput
import tools.jackson.databind.JsonNode

internal class VendorRequest(private val body: JsonNode) : VendorInput {
    override val displayName get() = text(body, "displayName", 100)
    override val expectedVersion get() = long(body, "expectedVersion")
    override val kind get() = text(body, "kind", 50)
    override val hasContract get() = body.path("contract").let { !it.isMissingNode && !it.isNull }
    override val contract get() = ContractRequest(body.path("contract"))
}

internal class ContractRequest(private val body: JsonNode) : ContractInput {
    override val planId get() = text(body, "planId", 100)
    override val effectiveFrom get() = date(body, "effectiveFrom")
    override val effectiveTo get() = body.path("effectiveTo").takeUnless { it.isNull || it.isMissingNode }?.let { date(body, "effectiveTo") }
    override val termNote get() = body.path("termNote").takeUnless { it.isNull || it.isMissingNode }?.asString()?.also {
        if (it.length > 1000) fail("invalid_request", 400, "contract.termNote")
    }
    override val tiers: List<TierInput> get() {
        val values = body.path("tiers")
        if (!values.isArray) fail("invalid_request", 400, "contract.tiers")
        return values.toList().map(::TierRequest)
    }
}

internal class TierRequest(private val body: JsonNode) : TierInput {
    override val seats get() = long(body, "seats")
    override val monthlyFeePerSeatUsd get() = text(body, "monthlyFeePerSeatUsd", 40)
    override val label get() = text(body, "label", 100)
}
