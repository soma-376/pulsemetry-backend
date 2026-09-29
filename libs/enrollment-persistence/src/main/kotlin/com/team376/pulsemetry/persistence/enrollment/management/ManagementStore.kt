package com.team376.pulsemetry.persistence.enrollment.management

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ManagementException(val code: String, val status: Int, val field: String? = null) : RuntimeException(code)

/** 관리 명령의 원자성과 재시도 응답을 보장한다. 빈·HTTP·ClickHouse 의존성은 없다. */
class ManagementStore(private val jdbc: JdbcClient, manager: PlatformTransactionManager,
    private val mapper: ObjectMapper, private val clock: Clock, encryptionKey: String,
    initialManifest: () -> JsonNode) {
    private val tx = TransactionTemplate(manager)
    private val onboarding = OnboardingStore(jdbc, mapper, initialManifest)
    private val catalog = VendorCatalog(jdbc)
    private val random = SecureRandom()
    private val key = SecretKeySpec(Base64.getDecoder().decode(encryptionKey).also { require(it.size == 32) }, "AES")

    fun onboarding(tenant: UUID): JsonNode = onboarding.state(tenant)
    fun invitations(tenant: UUID, limit: Int, cursor: String?): JsonNode = onboarding.invitations(tenant, limit, cursor?.let(::uuid), clock.instant())

    fun command(tenant: UUID, actor: UUID, operation: String, body: JsonNode, idempotencyKey: String?, version: Long? = null): JsonNode =
        requireNotNull(tx.execute {
            // 조직별 관리 명령만 직렬화한다. 읽기와 다른 조직의 명령은 잠그지 않는다.
            val active = jdbc.sql("SELECT id FROM enrollment.tenants WHERE id=:tenant AND status='active' AND deleted_at IS NULL FOR UPDATE")
                .param("tenant", tenant).query(UUID::class.java).optional().orElse(null) ?: fail("not_found", 404)
            val role = jdbc.sql("SELECT role::text FROM enrollment.members WHERE tenant_id=:tenant AND id=:actor AND status='active' FOR SHARE")
                .param("tenant", active).param("actor", actor).query(String::class.java).optional().orElse(null)
            if (role !in setOf("owner", "admin")) fail("forbidden", 403)
            val hash = hash(canonical(body))
            val now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            if (operation.startsWith("POST ")) {
                if (idempotencyKey == null || !Regex("[A-Za-z0-9_-]{8,128}").matches(idempotencyKey)) fail("invalid_request", 400, "Idempotency-Key")
                val previous = jdbc.sql("""SELECT request_hash,encrypted_response,expires_at FROM enrollment.management_commands
                    WHERE tenant_id=:tenant AND actor_id=:actor AND command_path=:path AND idempotency_key=:key""")
                    .param("tenant", tenant).param("actor", actor).param("path", operation).param("key", idempotencyKey)
                    .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()) }.optional().orElse(null)
                if (previous != null && previous.third > now) {
                    if (previous.first != hash) fail("idempotency_conflict", 409)
                    return@execute mapper.readTree(decrypt(previous.second, "$tenant/$actor/$operation/$idempotencyKey"))
                }
                jdbc.sql("DELETE FROM enrollment.management_commands WHERE tenant_id=:tenant AND actor_id=:actor AND command_path=:path AND idempotency_key=:key")
                    .param("tenant", tenant).param("actor", actor).param("path", operation).param("key", idempotencyKey).update()
            }
            val result = when {
                operation == "PUT /collection-policy" -> onboarding.savePolicy(tenant, actor, body, now)
                operation == "POST /onboarding/complete" -> onboarding.complete(tenant, actor, now)
                operation == "POST /teams" -> createTeam(tenant, body, now)
                operation.startsWith("PATCH /teams/") -> editTeam(tenant, operation.substringAfterLast('/'), body, now)
                operation.startsWith("DELETE /teams/") -> archiveTeam(tenant, operation.substringAfterLast('/'), version, now)
                operation == "POST /member-team-assignments" -> assignTeams(tenant, body, now)
                operation == "POST /invitations/batch" -> invite(tenant, actor, body, now)
                operation.startsWith("POST /invitations/") && operation.endsWith("/revoke") -> revoke(tenant, operation.split('/')[2], now)
                operation.startsWith("POST /invitations/") && operation.endsWith("/reissue") -> reissue(tenant, actor, operation.split('/')[2], now)
                operation == "POST /vendors" -> saveVendor(tenant, actor, null, body, now)
                operation.startsWith("PATCH /vendors/") -> renameVendor(tenant, actor, operation.split('/')[2], body, now)
                operation.startsWith("PUT /vendors/") -> saveVendor(tenant, actor, operation.split('/')[2], body, now)
                operation.startsWith("DELETE /vendors/") -> removeVendor(tenant, actor, operation.split('/')[2], operation.endsWith("/contract"), version, now)
                else -> fail("not_found", 404)
            }
            if (operation.startsWith("POST ")) {
                jdbc.sql("""INSERT INTO enrollment.management_commands VALUES (:tenant,:actor,:path,:key,:hash,:response,:expires)""")
                    .param("tenant", tenant).param("actor", actor).param("path", operation).param("key", idempotencyKey)
                    .param("hash", hash).param("response", encrypt(mapper.writeValueAsString(result), "$tenant/$actor/$operation/$idempotencyKey"))
                    .param("expires", Timestamp.from(now.plusSeconds(86400))).update()
            }
            result
        })

    private fun createTeam(tenant: UUID, body: JsonNode, now: Instant): JsonNode {
        val name = teamName(tenant, body, null)
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO enrollment.teams(id,tenant_id,name,created_at,updated_at) VALUES (:id,:tenant,:name,:now,:now)")
            .param("id", id).param("tenant", tenant).param("name", name).param("now", Timestamp.from(now)).update()
        return node(mapOf("teamId" to id, "teamName" to name, "version" to now.toEpochMilli()))
    }
    private fun teamName(tenant: UUID, body: JsonNode, except: UUID?): String {
        val name = text(body, "teamName", 100)
        fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val names = jdbc.sql("SELECT id,name FROM enrollment.teams WHERE tenant_id=:tenant").param("tenant", tenant)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()
        if (names.any { it.first != except && normalized(it.second) == normalized(name) }) fail("team_name_conflict", 409, "teamName")
        return name
    }
    private fun editTeam(tenant: UUID, raw: String, body: JsonNode, now: Instant): JsonNode {
        val id = uuid(raw)
        val old = team(tenant, id)
        checkVersion(old, long(body, "expectedVersion"))
        val name = teamName(tenant, body, id)
        val updated = advance(now, old)
        jdbc.sql("UPDATE enrollment.teams SET name=:name,updated_at=:now WHERE tenant_id=:tenant AND id=:id")
            .param("name", name).param("now", Timestamp.from(updated)).param("tenant", tenant).param("id", id).update()
        return node(mapOf("teamId" to id, "teamName" to name, "version" to updated.toEpochMilli()))
    }
    private fun archiveTeam(tenant: UUID, raw: String, version: Long?, now: Instant): JsonNode {
        val id = uuid(raw)
        val old = team(tenant, id)
        checkVersion(old, version ?: fail("invalid_request", 400, "If-Match"))
        jdbc.sql("""UPDATE enrollment.members SET updated_at=GREATEST(date_trunc('milliseconds',:now::timestamptz),updated_at+interval '1 millisecond')
            WHERE tenant_id=:tenant AND id IN (SELECT member_id FROM enrollment.team_memberships WHERE team_id=:id AND left_at IS NULL)""")
            .param("now", Timestamp.from(now)).param("tenant", tenant).param("id", id).update()
        jdbc.sql("UPDATE enrollment.team_memberships SET left_at=:now WHERE team_id=:id AND left_at IS NULL")
            .param("now", Timestamp.from(now)).param("id", id).update()
        jdbc.sql("UPDATE enrollment.teams SET status='archived',updated_at=:now WHERE tenant_id=:tenant AND id=:id")
            .param("now", Timestamp.from(advance(now, old))).param("tenant", tenant).param("id", id).update()
        return node(emptyMap<String, String>())
    }
    private fun team(tenant: UUID, id: UUID): Instant = jdbc.sql("SELECT updated_at FROM enrollment.teams WHERE tenant_id=:tenant AND id=:id AND status='active' FOR UPDATE")
        .param("tenant", tenant).param("id", id).query { rs, _ -> rs.getTimestamp(1).toInstant() }.optional().orElse(null) ?: fail("not_found", 404, "teamId")

    private fun assignTeams(tenant: UUID, body: JsonNode, now: Instant): JsonNode {
        val items = array(body, "assignments")
        val seen = mutableSetOf<UUID>()
        val changes = items.map { item ->
            val member = uuid(text(item, "memberId", 36))
            if (!seen.add(member)) fail("invalid_request", 400, "assignments")
            val team = item.path("teamId").takeUnless { it.isNull || it.isMissingNode }?.let { uuid(it.asString()) }
            team?.let { team(tenant, it) }
            val updated = jdbc.sql("SELECT updated_at FROM enrollment.members WHERE tenant_id=:tenant AND id=:id AND status!='suspended' FOR UPDATE")
                .param("tenant", tenant).param("id", member).query { rs, _ -> rs.getTimestamp(1).toInstant() }.optional().orElse(null) ?: fail("not_found", 404, "memberId")
            checkVersion(updated, long(item, "expectedVersion"))
            Triple(member, team, advance(now, updated))
        }
        changes.forEach { (member, team, updated) ->
            jdbc.sql("UPDATE enrollment.team_memberships SET left_at=:now WHERE member_id=:id AND left_at IS NULL")
                .param("now", Timestamp.from(now)).param("id", member).update()
            if (team != null) addMembership(member, team, now)
            jdbc.sql("UPDATE enrollment.members SET updated_at=:now WHERE id=:id").param("now", Timestamp.from(updated)).param("id", member).update()
        }
        return node(mapOf("effectiveAt" to now.toString(), "members" to changes.map { (member, team, updated) -> mapOf("memberId" to member, "teamId" to team, "version" to updated.toEpochMilli()) }))
    }

    private fun invite(tenant: UUID, actor: UUID, body: JsonNode, now: Instant): JsonNode {
        val seen = mutableSetOf<String>()
        val results = array(body, "invitations").map { item ->
            val email = text(item, "email", 320).lowercase(Locale.ROOT)
            val role = text(item, "role", 20)
            val team = item.path("teamId").takeUnless { it.isNull || it.isMissingNode }?.let { uuid(it.asString()) }
            var reason: String? = null
            if (!Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email)) reason = "invalid_email"
            if (!seen.add(email)) reason = "duplicate_email"
            if (role !in setOf("admin", "member")) reason = "role_not_assignable"
            if (team != null && jdbc.sql("SELECT count(*) FROM enrollment.teams WHERE tenant_id=:tenant AND id=:id AND status='active'").param("tenant", tenant).param("id", team).query(Int::class.java).single() == 0) reason = "team_not_found"
            val existing = jdbc.sql("SELECT id,status::text FROM enrollment.members WHERE tenant_id=:tenant AND lower(email)=:email")
                .param("tenant", tenant).param("email", email).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()
            if (existing.size > 1) reason = "ambiguous_email"
            val status = when { reason != null -> "rejected"; existing.firstOrNull()?.second == "invited" -> "already_invited"; existing.isNotEmpty() -> "already_member"; else -> "issued" }
            var code: String? = null
            var invitation: UUID? = null
            if (status == "issued") {
                val member = UUID.randomUUID()
                jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role,status,created_at,updated_at) VALUES (:id,:tenant,:email,CAST(:role AS enrollment.member_role),'invited',:now,:now)")
                    .param("id", member).param("tenant", tenant).param("email", email).param("role", role).param("now", Timestamp.from(now)).update()
                if (team != null) addMembership(member, team, now)
                code = (1..12).map { "0123456789ABCDEFGHJKMNPQRSTVWXYZ"[random.nextInt(32)] }.joinToString("").chunked(4).joinToString("-")
                invitation = UUID.randomUUID()
                jdbc.sql("INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at,created_at) VALUES (:id,:tenant,:member,:actor,:hash,:expires,:now)")
                    .param("id", invitation).param("tenant", tenant).param("member", member).param("actor", actor).param("hash", hash(code))
                    .param("expires", Timestamp.from(now.plusSeconds(72 * 3600))).param("now", Timestamp.from(now)).update()
            }
            mapOf("email" to email, "invitationId" to invitation, "status" to status, "reason" to reason,
                "expiresAt" to if (invitation != null) now.plusSeconds(72 * 3600).toString() else null, "code" to code)
        }
        return node(mapOf("results" to results))
    }
    private fun revoke(tenant: UUID, raw: String, now: Instant): JsonNode {
        val count = jdbc.sql("UPDATE enrollment.invitations SET revoked_at=:now WHERE tenant_id=:tenant AND id=:id AND revoked_at IS NULL AND (used_at IS NULL OR signup_used_at IS NULL)")
            .param("now", Timestamp.from(now)).param("tenant", tenant).param("id", uuid(raw)).update()
        if (count == 0) fail("invitation_unavailable", 409)
        return node(emptyMap<String, String>())
    }
    private fun reissue(tenant: UUID, actor: UUID, raw: String, now: Instant): JsonNode {
        val old = jdbc.sql("""SELECT target_member_id,used_at,signup_used_at FROM enrollment.invitations
            WHERE tenant_id=:tenant AND id=:id AND revoked_at IS NULL FOR UPDATE""")
            .param("tenant", tenant).param("id", uuid(raw)).query { r, _ -> Triple(r.getObject(1, UUID::class.java), r.getTimestamp(2), r.getTimestamp(3)) }
            .optional().orElse(null) ?: fail("invitation_unavailable", 409)
        if (old.second != null && old.third != null) fail("invitation_unavailable", 409)
        revoke(tenant, raw, now)
        val code = (1..12).map { "0123456789ABCDEFGHJKMNPQRSTVWXYZ"[random.nextInt(32)] }.joinToString("").chunked(4).joinToString("-")
        val id = UUID.randomUUID()
        val expires = now.plusSeconds(72 * 3600)
        jdbc.sql("""INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at,created_at,used_at,signup_used_at)
            VALUES (:id,:tenant,:member,:actor,:hash,:expires,:now,:used,:signup)""")
            .param("id", id).param("tenant", tenant).param("member", old.first).param("actor", actor).param("hash", hash(code))
            .param("expires", Timestamp.from(expires)).param("now", Timestamp.from(now)).param("used", old.second).param("signup", old.third).update()
        return node(mapOf("invitationId" to id, "replacesInvitationId" to raw, "code" to code, "expiresAt" to expires.toString()))
    }
    private fun addMembership(member: UUID, team: UUID, now: Instant) {
        jdbc.sql("INSERT INTO enrollment.team_memberships(id,member_id,team_id,joined_at) VALUES (:id,:member,:team,:now)")
            .param("id", UUID.randomUUID()).param("member", member).param("team", team).param("now", Timestamp.from(now)).update()
    }

    private fun saveVendor(tenant: UUID, actor: UUID, vendor: String?, body: JsonNode, now: Instant): JsonNode {
        val today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        val displayName = text(body, "displayName", 100)
        val previous = vendor?.let { vendorRow(tenant, it) }
        if (vendor != null && previous == null) fail("not_found", 404)
        if (previous != null && previous.path("version").asLong() != long(body, "expectedVersion")) fail("version_conflict", 409)
        val kind = previous?.path("kind")?.asString() ?: text(body, "kind", 50)
        val product = catalog.find(kind) ?: fail("invalid_vendor", 422, "kind")
        if (vendor == null) {
            val duplicate = jdbc.sql("SELECT EXISTS (SELECT 1 FROM enrollment.managed_vendors WHERE tenant_id=:tenant AND kind=:kind AND NOT archived)")
                .param("tenant", tenant).param("kind", kind).query(Boolean::class.java).single()
            if (duplicate) fail("vendor_already_registered", 409, "kind")
        }
        val input = body.path("contract")
        if (vendor == null && (input.isNull || input.isMissingNode)) {
            val vendorId = UUID.randomUUID().toString()
            jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:tenant,:id,:kind,'manual',:now)")
                .param("tenant", tenant).param("id", vendorId).param("kind", kind).param("now", Timestamp.from(now)).update()
            appendVendor(tenant, actor, vendorId, now.toEpochMilli(), displayName, null, false, now)
            return vendorResponse(tenant, vendorRow(tenant, vendorId)!!, now)
        }
        val plan = text(input, "planId", 100)
        if (product.plans.none { it.id == plan }) fail("invalid_plan", 422, "contract.planId")
        // 정정은 기존 시작일을 유지한다. 클라이언트가 보낸 다른 시작일로 갱신하지 않는다.
        val previousContract = previous?.path("contract")?.takeUnless { it.isNull || it.isMissingNode }
        val from = previousContract?.path("effectiveFrom")?.asString()?.let(LocalDate::parse) ?: date(input, "effectiveFrom")
        val to = input.path("effectiveTo").takeUnless { it.isNull || it.isMissingNode }?.let { date(input, "effectiveTo") }
        if ((previousContract == null && from < today) || (to != null && (to < from || (to < today && to.toString() != previousContract?.path("effectiveTo")?.asString())))) fail("invalid_contract_period", 422, "contract.effectiveFrom")
        val tiers = input.path("tiers")
        if (!tiers.isArray || tiers.size() !in 1..(if (product.allowsSeatTiers) 3 else 1)) fail("invalid_request", 400, "contract.tiers")
        var monthly = BigDecimal.ZERO
        val parsed = (0 until tiers.size()).map { index ->
            val tier = tiers[index]
            val seats = long(tier, "seats")
            val feeText = text(tier, "monthlyFeePerSeatUsd", 40)
            if (seats !in 1..9007199254740991L || !Regex("[0-9]+(\\.[0-9]{1,12})?").matches(feeText)) fail("invalid_request", 400, "contract.tiers")
            val fee = feeText.toBigDecimal()
            monthly += fee * seats.toBigDecimal()
            if (monthly > BigDecimal("9007199254740991")) fail("invalid_request", 400, "contract.tiers")
            mapOf("tierId" to UUID.randomUUID().toString(), "label" to text(tier, "label", 100), "seats" to seats, "monthlyFeePerSeatUsd" to fee.toPlainString())
        }
        val version = maxOf(now.toEpochMilli(), (previous?.path("version")?.asLong() ?: 0) + 1)
        val vendorId = vendor ?: UUID.randomUUID().toString()
        if (vendor == null) jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:tenant,:id,:kind,'manual',:now)")
            .param("tenant", tenant).param("id", vendorId).param("kind", kind).param("now", Timestamp.from(now)).update()
        val contract = node(mapOf("version" to version, "planId" to plan, "effectiveFrom" to from.toString(), "effectiveTo" to to?.toString(),
            "termNote" to input.path("termNote").takeUnless { it.isNull || it.isMissingNode }?.asString()?.also { if (it.length > 1000) fail("invalid_request", 400, "contract.termNote") },
            "tiers" to parsed, "monthlySeatFeeUsd" to monthly.toPlainString(), "confirmedAt" to now.toString(), "confirmedBy" to actor.toString()))
        appendVendor(tenant, actor, vendorId, version, displayName, contract, false, now)
        return vendorResponse(tenant, vendorRow(tenant, vendorId)!!, now)
    }
    private fun renameVendor(tenant: UUID, actor: UUID, id: String, body: JsonNode, now: Instant): JsonNode {
        val previous = vendorRow(tenant, id) ?: fail("not_found", 404)
        if (previous.path("version").asLong() != long(body, "expectedVersion")) fail("version_conflict", 409)
        val name = text(body, "displayName", 100)
        val version = maxOf(now.toEpochMilli(), previous.path("version").asLong() + 1)
        val contract = previous.path("contract").takeUnless { it.isNull }
        appendVendor(tenant, actor, id, version, name, contract, false, now)
        return vendorResponse(tenant, vendorRow(tenant, id)!!, now)
    }
    private fun removeVendor(tenant: UUID, actor: UUID, id: String, contractOnly: Boolean, version: Long?, now: Instant): JsonNode {
        val previous = vendorRow(tenant, id) ?: fail("not_found", 404)
        if (version == null) fail("invalid_request", 400, "If-Match")
        if (previous.path("version").asLong() != version) fail("version_conflict", 409)
        if (!contractOnly && previous.path("source").asString() == "detected") fail("detected_vendor", 422)
        if (!contractOnly) jdbc.sql("UPDATE enrollment.managed_vendors SET archived=true WHERE tenant_id=:tenant AND vendor_id=:id")
            .param("tenant", tenant).param("id", id).update()
        appendVendor(tenant, actor, id, maxOf(now.toEpochMilli(), version + 1), previous.path("displayName").asString(), null, !contractOnly, now)
        return node(emptyMap<String, String>())
    }
    private fun appendVendor(tenant: UUID, actor: UUID, id: String, version: Long, name: String, contract: JsonNode?, archived: Boolean, now: Instant) {
        jdbc.sql("INSERT INTO enrollment.vendor_contract_versions VALUES (:tenant,:id,:version,:name,CAST(:contract AS jsonb),:archived,:now,:actor)")
            .param("tenant", tenant).param("id", id).param("version", version).param("name", name).param("contract", contract?.toString())
            .param("archived", archived).param("now", Timestamp.from(now)).param("actor", actor).update()
    }
    private fun vendorRow(tenant: UUID, id: String): JsonNode? = jdbc.sql("""SELECT v.*,c.version,c.display_name,c.contract::text,c.archived
        FROM enrollment.managed_vendors v JOIN LATERAL (SELECT * FROM enrollment.vendor_contract_versions
        WHERE tenant_id=v.tenant_id AND vendor_id=v.vendor_id ORDER BY version DESC LIMIT 1) c ON true
        WHERE v.tenant_id=:tenant AND v.vendor_id=:id AND NOT c.archived""")
        .param("tenant", tenant).param("id", id).query { rs, _ -> node(mapOf("vendorId" to id, "kind" to rs.getString("kind"), "source" to rs.getString("source"),
            "version" to rs.getLong("version"), "displayName" to rs.getString("display_name"), "contract" to rs.getString("contract")?.let(mapper::readTree))) }.optional().orElse(null)
    private fun vendorResponse(tenant: UUID, row: JsonNode, now: Instant): JsonNode = node(mapOf(
        "meta" to mapOf("organizationId" to tenant, "generatedAt" to now.toString(), "asOf" to now.toString(),
            "snapshotId" to "d.${Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(mapOf("v" to 1, "k" to "settings", "t" to tenant.toString(), "a" to now.toEpochMilli())))}",
            "currency" to "USD", "timeZone" to "Asia/Seoul"),
        "vendor" to mapOf("vendorId" to row.path("vendorId"), "displayName" to row.path("displayName"), "kind" to row.path("kind"), "source" to row.path("source"),
            "version" to row.path("version"), "firstSeenAt" to null, "lastSeenAt" to null, "activeUsers7d" to null, "activeUsers30d" to null,
            "observation" to "unobserved", "state" to if (contractStatus(row.path("contract"), now) == ContractStatus.active) "configured" else "needs_review",
            "contractStatus" to contractStatus(row.path("contract"), now), "contract" to row.path("contract"),
            "meteredMonthToDate" to mapOf("availability" to "unavailable", "reason" to "source_not_available", "data" to null), "checks" to emptyList<String>())))

    private fun contractStatus(contract: JsonNode, now: Instant): ContractStatus = ContractStatus.at(
        contract.path("effectiveFrom").takeUnless { it.isNull || it.isMissingNode }?.asString()?.let(LocalDate::parse),
        contract.path("effectiveTo").takeUnless { it.isNull || it.isMissingNode }?.asString()?.let(LocalDate::parse), now)
    private fun node(value: Any): JsonNode = mapper.valueToTree(value)
    private fun text(node: JsonNode, field: String, max: Int): String = node.path(field).takeIf { it.isString }?.asString()?.trim()?.takeIf { it.length in 1..max } ?: fail("invalid_request", 400, field)
    private fun long(node: JsonNode, field: String): Long {
        val value = node.path(field)
        if (!value.isNumber) fail("invalid_request", 400, field)
        return try { value.asString().toBigDecimal().longValueExact() } catch (_: ArithmeticException) { fail("invalid_request", 400, field) }
    }
    private fun date(node: JsonNode, field: String): LocalDate = try { LocalDate.parse(text(node, field, 10)) } catch (_: java.time.DateTimeException) { fail("invalid_request", 400, field) }
    private fun array(node: JsonNode, field: String): List<JsonNode> = node.path(field).takeIf { it.isArray && it.size() in 1..100 }?.toList() ?: fail("invalid_request", 400, field)
    private fun uuid(raw: String): UUID = try { UUID.fromString(raw).also { if (it.toString() != raw.lowercase()) fail("invalid_request", 400) } } catch (_: IllegalArgumentException) { fail("invalid_request", 400) }
    private fun checkVersion(old: Instant, expected: Long) { if (old.toEpochMilli() != expected) fail("version_conflict", 409) }
    private fun advance(now: Instant, old: Instant): Instant = Instant.ofEpochMilli(maxOf(now.toEpochMilli(), old.toEpochMilli() + 1))
    private fun canonical(node: JsonNode): String = when { node.isObject -> node.properties().sortedBy { it.key }.joinToString(",", "{", "}") { mapper.writeValueAsString(it.key) + ":" + canonical(it.value) }; node.isArray -> node.joinToString(",", "[", "]", transform = ::canonical); else -> node.toString() }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun encrypt(value: String, aad: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(aad.toByteArray()) }
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray()))
    }
    private fun decrypt(value: String, aad: String): String {
        val bytes = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0,12))); updateAAD(aad.toByteArray()) }
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8)
    }
    private fun fail(code: String, status: Int, field: String? = null): Nothing = throw ManagementException(code, status, field)
}
