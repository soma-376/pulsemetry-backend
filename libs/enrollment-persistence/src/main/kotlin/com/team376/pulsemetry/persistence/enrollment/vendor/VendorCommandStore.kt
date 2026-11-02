package com.team376.pulsemetry.persistence.enrollment.vendor

import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.time.Instant
import java.sql.Timestamp
import com.team376.pulsemetry.persistence.enrollment.seat.VendorConnectionStore
import com.team376.pulsemetry.persistence.enrollment.seat.VendorConnections

/** 명령 트랜잭션이 호출하는 저장 연산. HTTP 경로로 업무를 선택하지 않는다. */
class VendorCommandStore(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    private fun node(value: Any): JsonNode = mapper.valueToTree(value)
    fun registeredKindExists(tenant: UUID, kind: String) = jdbc.sql("SELECT EXISTS (SELECT 1 FROM enrollment.managed_vendors WHERE tenant_id=:tenant AND kind=:kind AND NOT archived)")
                .param("tenant", tenant).param("kind", kind).query(Boolean::class.java).single()

    fun insertVendor(tenant: UUID, vendorId: String, kind: String, now: Instant) = jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:tenant,:id,:kind,'manual',:now)")
                .param("tenant", tenant).param("id", vendorId).param("kind", kind).param("now", Timestamp.from(now)).update()

    fun insertRegisteredVendor(tenant: UUID, vendorId: String, kind: String, now: Instant) = jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:tenant,:id,:kind,'manual',:now)")
            .param("tenant", tenant).param("id", vendorId).param("kind", kind).param("now", Timestamp.from(now)).update()

    fun archiveVendor(tenant: UUID, id: String) = jdbc.sql("UPDATE enrollment.managed_vendors SET archived=true WHERE tenant_id=:tenant AND vendor_id=:id")
            .param("tenant", tenant).param("id", id).update()

    fun appendVersion(tenant: UUID, id: String, version: Long, name: String, contract: JsonNode?, archived: Boolean, now: Instant, actor: UUID) = jdbc.sql("INSERT INTO enrollment.vendor_contract_versions VALUES (:tenant,:id,:version,:name,CAST(:contract AS jsonb),:archived,:now,:actor)")
            .param("tenant", tenant).param("id", id).param("version", version).param("name", name).param("contract", contract?.toString())
            .param("archived", archived).param("now", Timestamp.from(now)).param("actor", actor).update()

    fun findVendor(tenant: UUID, id: String) = jdbc.sql("""SELECT v.*,c.version,c.display_name,c.contract::text,c.archived
        FROM enrollment.managed_vendors v JOIN LATERAL (SELECT * FROM enrollment.vendor_contract_versions
        WHERE tenant_id=v.tenant_id AND vendor_id=v.vendor_id ORDER BY version DESC LIMIT 1) c ON true
        WHERE v.tenant_id=:tenant AND v.vendor_id=:id AND NOT c.archived""")
        .param("tenant", tenant).param("id", id).query { rs, _ -> node(mapOf("vendorId" to id, "kind" to rs.getString("kind"), "source" to rs.getString("source"),
            "version" to rs.getLong("version"), "displayName" to rs.getString("display_name"), "contract" to rs.getString("contract")?.let(mapper::readTree))) }.optional().orElse(null)
    fun connections(tenant: UUID) = VendorConnections.active(jdbc, mapper, tenant)
    fun eraseConnection(tenant: UUID, id: String, actor: UUID, now: Instant) = VendorConnectionStore.eraseForVendor(jdbc, tenant, id, actor, now)
}
