package com.team376.pulsemetry.devseed

import java.time.Instant
import java.time.LocalDate
import com.team376.pulsemetry.persistence.enrollment.management.ContractStatus
import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog

/** 관측 제품 → 카탈로그 제품. enrollment V18(`vendor_catalog_observed_products`)과 같다 — SeedScenarioTest 가 대조한다(ADR 0044). */
internal val OBSERVED_PRODUCTS = mapOf("claude_code" to "claude_team", "codex" to "openai_biz")

/** DB 조회/수정 없이 A 시나리오와 카탈로그를 공개 화면 fixture로 내보낸다. 인증 정보는 allowlist 밖이다. */
internal fun frontendFixture(data: SeedData): Row {
    require(data.scenario == "A")
    // 오프라인 화면 테스트용 고정본이다. 운영 카탈로그는 DB만 읽는다(ADR 0035).
    val snapshot = VendorCatalog.Snapshot(requireNotNull(VendorCatalog::class.java.getResourceAsStream("/fixtures/vendor-catalog.json")).use { stream ->
        json.readTree(stream).toList().map { p -> VendorCatalog.Product(
            p.path("id").asString(), p.path("provider").asString(), p.path("displayName").asString(),
            p.path("product").asString(), p.path("allowsSeatTiers").asBoolean(),
            p.path("plans").toList().map { f -> VendorCatalog.Plan(f.path("id").asString(), f.path("displayName").asString(),
                f.path("billing").asString(), f.path("separateUsageBilling").asBoolean()) }) }
    })
    fun rows(table: String) = data.rows["enrollment.$table"].orEmpty()
    val tenant = rows("tenants").single()
    val origin = tenant.getValue("created_at")
    val catalog = snapshot.products.map { product -> mapOf(
        "id" to product.id, "provider" to product.provider, "displayName" to product.displayName,
        "product" to product.product, "allowsSeatTiers" to product.allowsSeatTiers,
    ) }
    val plans = snapshot.products.associate { product -> product.id to product.plans.map { plan -> mapOf(
        "id" to plan.id, "displayName" to plan.displayName, "billing" to plan.billing,
        "separateUsageBilling" to plan.separateUsageBilling,
    ) } }
    // 벤더 관측 지표는 API 와 같은 규칙이다(ADR 0044): 명시 매핑으로 잇는 관측만, 기준 시각(기준일 0시) 전, 최근 7·30일은 기준일 전날까지.
    // 이 fixture 는 수집 구간 근거로 완전성을 판정하지 않는다 — 관측이 있으면 partial 이고 창에서 센 0 은 null 이다.
    val asOf = data.asOf.atStartOfDay(seoul).toInstant()
    val observedRows = data.events.filter { it["record_status"] == "active" && it["signal"] == "log" && Instant.parse(it.getValue("source_time").toString()) < asOf }
        .groupBy { OBSERVED_PRODUCTS[it["product"]] }
    fun observation(kind: Any?): Map<String, Any?> {
        val observed = observedRows[kind].orEmpty()
        if (kind !in OBSERVED_PRODUCTS.values || observed.isEmpty()) {
            return mapOf("activeUsers7d" to null, "activeUsers30d" to null, "firstSeenAt" to null, "lastSeenAt" to null, "observation" to "unobserved")
        }
        val times = observed.map { Instant.parse(it.getValue("source_time").toString()) }
        fun users(days: Long) = observed.filter {
            val time = Instant.parse(it.getValue("source_time").toString())
            time >= data.asOf.minusDays(days).atStartOfDay(seoul).toInstant() && time < asOf
        }.mapNotNull { it["member_id"] }.distinct().size.takeIf { it > 0 }
        return mapOf("activeUsers7d" to users(7), "activeUsers30d" to users(30), "firstSeenAt" to times.min().toString(),
            "lastSeenAt" to times.max().toString(), "observation" to "partial")
    }
    val vendors = rows("managed_vendors").map { vendor ->
        val version = rows("vendor_contract_versions").filter { it["vendor_id"] == vendor["vendor_id"] }
            .maxBy { (it.getValue("version") as Number).toLong() }
        val contract = (version["contract"] as? String)?.let(json::readTree)
        val status = ContractStatus.at(contract?.path("effectiveFrom")?.asString()?.let(LocalDate::parse),
            contract?.path("effectiveTo")?.takeUnless { it.isNull }?.asString()?.let(LocalDate::parse), data.asOf.atStartOfDay(seoul).toInstant())
        mapOf("vendorId" to vendor["vendor_id"], "kind" to vendor["kind"], "displayName" to version["display_name"],
            "source" to vendor["source"], "version" to version["version"], "state" to if (status == ContractStatus.active) "configured" else "needs_review", "contractStatus" to status,
            "contract" to contract,
        ) + observation(vendor["kind"])
    }
    val members = rows("members").map { member -> mapOf(
        "memberId" to member["id"], "email" to member["email"], "displayName" to member["display_name"],
        "role" to member["role"], "status" to member["status"],
        "teamId" to rows("team_memberships").singleOrNull { it["member_id"] == member["id"] && it["left_at"] == null }?.get("team_id"),
    ) }
    val manifestVersions = rows("manifests").associate { it["id"] to (it.getValue("version") as Number).toLong() }
    val desiredVersion = (rows("manifests").single { it["is_active"] == true }.getValue("version") as Number).toLong()
    val memberStatus = rows("members").associate { it["id"] to it["status"] }
    val installations = rows("installations").filter { it["status"] == "active" }.map { installation ->
        // 지금 집행하는 판 — 설치 보고가 있으면 그 판, 없으면 적용 확인 기록의 가장 높은 판(API 와 같은 규칙, ADR 0043).
        val report = rows("installation_heartbeats").singleOrNull { it["installation_id"] == installation["id"] }
        val applied = if (report != null) report["applied_manifest_id"]?.let(manifestVersions::getValue) else rows("installation_manifest_assignments")
            .filter { it["installation_id"] == installation["id"] && it["applied_at"] != null }
            .maxOfOrNull { manifestVersions.getValue(it["manifest_id"]) }
        mapOf(
            "installationId" to installation["id"], "memberId" to installation["member_id"],
            "agentVersion" to installation["client_version"], "appliedPolicyVersion" to applied,
            "lastHeartbeatAt" to report?.get("received_at"),
            // 로컬 프로필(관리 기능·메일 켬)의 응답과 같다 — 구성원이 활성이고 새 판 적용이 확인되지 않은 설치만 안내할 수 있다(ADR 0043).
            "canNotify" to (memberStatus[installation["member_id"]] == "active" && (applied == null || applied < desiredVersion)),
        )
    }
    val applied = installations.count { (it["appliedPolicyVersion"] as? Long)?.let { version -> version >= desiredVersion } == true }
    val unknown = installations.count { it["appliedPolicyVersion"] == null }
    val start = data.asOf.minusDays(28).atStartOfDay(seoul).toInstant().toString()
    val recent = data.events.filter { it.getValue("source_time").toString() >= start }
    return linkedMapOf(
        "fixtureVersion" to 1, "asOf" to data.asOf.toString(), "synthetic" to true,
        "organization" to mapOf("organizationId" to data.tenantId, "name" to tenant["name"], "timezone" to "Asia/Seoul"),
        "catalog" to mapOf("catalogVersion" to snapshot.version, "items" to catalog, "plans" to plans),
        "teams" to rows("teams").map { mapOf("teamId" to it["id"], "teamName" to it["name"], "version" to 1) },
        "members" to members, "managedVendors" to vendors, "installations" to installations,
        "onboarding" to mapOf("organizationId" to data.tenantId, "completed" to true, "completedAt" to origin,
            "policy" to mapOf("confirmed" to true, "confirmedAt" to origin, "version" to desiredVersion, "collectRawContent" to false),
            "selectedVendorCount" to vendors.size, "canComplete" to true, "nextStep" to "complete"),
        "policyRollout" to mapOf("desiredVersion" to desiredVersion, "eligible" to installations.size, "applied" to applied,
            "outdated" to installations.size - applied - unknown, "unknown" to unknown),
        "usage" to mapOf("startDate" to data.asOf.minusDays(28).toString(), "endDate" to data.asOf.minusDays(1).toString(),
            "activeUsers" to recent.map { it["member_id"] }.distinct().size, "eventCount" to recent.size,
            "byProduct" to recent.groupBy { it["product"] }.mapValues { (_, events) -> mapOf("activeUsers" to events.map { it["member_id"] }.distinct().size) }),
        // 0원 약정과 null, 구 계약 ID 처리 회귀 검증을 위해 보존한다. 좌석 계약으로 변환하지 않는다.
        "legacyContracts" to rows("contracts").map { contract ->
            val term = rows("contract_term_commitments").single { it["contract_id"] == contract["id"] }
            mapOf("contractId" to contract["id"], "provider" to contract["vendor"], "contractType" to contract["contract_type"],
                "commitmentMonths" to term["commitment_months"], "commitmentAmountUsd" to term["commitment_amount"].toString())
        },
    )
}
