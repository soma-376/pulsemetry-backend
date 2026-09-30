package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.connector.vendor.ConnectorCredential
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptor
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 연결의 확인 결과 (ADR 0048 §6). */
enum class ConnectionCheck(val wire: String) {
	UNVERIFIED("unverified"), VERIFIED("verified"), INVALID_CREDENTIALS("invalid_credentials"),
	INSUFFICIENT_PERMISSION("insufficient_permission"), UNAVAILABLE("unavailable");

	companion object {
		fun of(wire: String): ConnectionCheck = entries.first { it.wire == wire }
	}
}

/** 활성 벤더 연결의 비밀 아닌 기록. 자격증명은 갱신 시각만 담는다. */
data class ConnectionRecord(
	val id: UUID,
	val tenantId: UUID,
	val vendorId: String,
	val connector: String,
	val settings: Map<String, String>,
	val credentialUpdatedAt: Instant,
	val check: ConnectionCheck,
	val checkedAt: Instant?,
	val lastSyncSucceededAt: Instant?,
	val lastSyncFailedAt: Instant?,
	val lastSyncError: String?,
	val version: Long,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	val syncStatus: SyncStatus get() = SyncStatus.of(lastSyncSucceededAt, lastSyncFailedAt)
}

/** 활성 연결 읽기 — 암호문 열을 고르지 않는다. 조회 앱(dashboard-api)도 이것을 쓴다. */
object VendorConnections {
	private const val COLUMNS = """id, tenant_id, vendor_id, connector, settings::text AS settings, credential_updated_at, check_status, checked_at,
		last_sync_succeeded_at, last_sync_failed_at, last_sync_error, version, created_at, updated_at"""

	fun active(jdbc: JdbcClient, mapper: ObjectMapper, tenant: UUID): List<ConnectionRecord> =
		jdbc.sql("SELECT $COLUMNS FROM enrollment.vendor_connections WHERE tenant_id = :tenant AND deleted_at IS NULL ORDER BY vendor_id")
			.param("tenant", tenant).query { rs, _ -> record(rs, mapper) }.list()

	internal fun active(jdbc: JdbcClient, mapper: ObjectMapper, tenant: UUID, vendorId: String, lock: Boolean): ConnectionRecord? =
		jdbc.sql("SELECT $COLUMNS FROM enrollment.vendor_connections WHERE tenant_id = :tenant AND vendor_id = :vendor AND deleted_at IS NULL" + if (lock) " FOR UPDATE" else "")
			.param("tenant", tenant).param("vendor", vendorId).query { rs, _ -> record(rs, mapper) }.optional().orElse(null)

	private fun record(rs: ResultSet, mapper: ObjectMapper) = ConnectionRecord(
		id = rs.getObject("id", UUID::class.java),
		tenantId = rs.getObject("tenant_id", UUID::class.java),
		vendorId = rs.getString("vendor_id"),
		connector = rs.getString("connector"),
		settings = mapper.readTree(rs.getString("settings")).properties().associate { it.key to it.value.asString() },
		credentialUpdatedAt = rs.getTimestamp("credential_updated_at").toInstant(),
		check = ConnectionCheck.of(rs.getString("check_status")),
		checkedAt = rs.getTimestamp("checked_at")?.toInstant(),
		lastSyncSucceededAt = rs.getTimestamp("last_sync_succeeded_at")?.toInstant(),
		lastSyncFailedAt = rs.getTimestamp("last_sync_failed_at")?.toInstant(),
		lastSyncError = rs.getString("last_sync_error"),
		version = rs.getLong("version"),
		createdAt = rs.getTimestamp("created_at").toInstant(),
		updatedAt = rs.getTimestamp("updated_at").toInstant(),
	)
}

/**
 * 벤더 연결의 추가·교체·삭제·확인 기록 (ADR 0048 §6). 자격증명은 [CredentialCipher] 의 암호문으로만 저장하고, 반환값·예외 어디에도 싣지 않는다.
 * 쓰기는 등록 제품 행을 잠가 좌석 원장의 쓰기와 직렬화한다. 벤더 호출(확인)은 이 저장소가 하지 않는다 — 트랜잭션 밖에서 앱이 한다.
 */
class VendorConnectionStore(
	private val jdbc: JdbcClient,
	manager: PlatformTransactionManager,
	private val clock: Clock,
	private val cipher: CredentialCipher,
	private val mapper: ObjectMapper,
) {
	private val tx = TransactionTemplate(manager)

	/**
	 * 연결을 만들거나(기대 판 0) 교체한다(기대 판 = 현재 판). 커넥터는 제품과 현재 계약의 플랜으로 [connectorFor] 가 고른다 — 이 배포에 구현이 없으면 null 이고 422다.
	 * 교체는 확인 상태를 `unverified` 로 되돌리고, 커넥터나 설정이 바뀌면 동기화 기록도 비운다(다른 대상의 기록이다).
	 */
	fun save(tenant: UUID, actor: UUID, vendorId: String, settings: Map<String, String>, credential: String, expectedVersion: Long,
		connectorFor: (product: String, plan: String?) -> ConnectorDescriptor?): ConnectionRecord = write {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val product = RegisteredProducts.lock(jdbc, tenant, vendorId)
		val descriptor = connectorFor(product.kind, product.plan) ?: fail("connector_unavailable", 422, "vendorId")
		if (settings.keys != descriptor.settingKeys.toSet() || settings.values.any { it.isBlank() || it.length > 200 || it.any(Char::isISOControl) }) fail("invalid_request", 400, "settings")
		if (credential.isBlank() || credential.length > 8192) fail("invalid_request", 400, "credential")
		val current = VendorConnections.active(jdbc, mapper, tenant, vendorId, lock = true)
		val now = now()
		val settingsJson = mapper.writeValueAsString(settings.toSortedMap())
		if (current == null) {
			if (expectedVersion != 0L) fail("version_conflict", 409, "expectedVersion")
			val id = UUID.randomUUID()
			val sealed = cipher.seal(credential, aad(tenant, vendorId, id))
			jdbc.sql("""INSERT INTO enrollment.vendor_connections (id, tenant_id, vendor_id, connector, settings, credential_ciphertext, credential_key_id, credential_updated_at,
					check_status, version, created_at, created_by, updated_at, updated_by)
				VALUES (:id, :tenant, :vendor, :connector, CAST(:settings AS jsonb), :ciphertext, :key, :now, 'unverified', 1, :now, :actor, :now, :actor)""")
				.param("id", id).param("tenant", tenant).param("vendor", vendorId).param("connector", descriptor.id).param("settings", settingsJson)
				.param("ciphertext", sealed.ciphertext).param("key", sealed.keyId).param("now", Timestamp.from(now)).param("actor", actor).update()
		} else {
			if (expectedVersion != current.version) fail("version_conflict", 409, "expectedVersion")
			val retarget = current.connector != descriptor.id || current.settings != settings
			val sealed = cipher.seal(credential, aad(tenant, vendorId, current.id))
			jdbc.sql("""UPDATE enrollment.vendor_connections SET connector = :connector, settings = CAST(:settings AS jsonb), credential_ciphertext = :ciphertext,
					credential_key_id = :key, credential_updated_at = :now, check_status = 'unverified', checked_at = NULL, version = version + 1, updated_at = :now, updated_by = :actor
					${if (retarget) ", last_sync_succeeded_at = NULL, last_sync_failed_at = NULL, last_sync_error = NULL" else ""}
				WHERE id = :id""")
				.param("connector", descriptor.id).param("settings", settingsJson).param("ciphertext", sealed.ciphertext).param("key", sealed.keyId)
				.param("now", Timestamp.from(now)).param("actor", actor).param("id", current.id).update()
		}
		VendorConnections.active(jdbc, mapper, tenant, vendorId, lock = false)!!
	}

	/** 연결을 지운다 — 암호문을 즉시 비우고 행은 이력으로 남긴다. 좌석 원장은 그대로다(권위가 수동으로 돌아간다). */
	fun delete(tenant: UUID, actor: UUID, vendorId: String, expectedVersion: Long) {
		write {
			RegisteredProducts.requireManager(jdbc, tenant, actor)
			RegisteredProducts.lock(jdbc, tenant, vendorId)
			val current = VendorConnections.active(jdbc, mapper, tenant, vendorId, lock = true) ?: fail("not_found", 404, "vendorId")
			if (current.version != expectedVersion) fail("version_conflict", 409)
			erase(jdbc, current.id, actor, now())
		}
	}

	/** 확인 호출에 쓸 연결과 자격증명. 행위자를 다시 확인한다. */
	fun target(tenant: UUID, actor: UUID, vendorId: String): Pair<ConnectionRecord, ConnectorCredential> = read {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val record = VendorConnections.active(jdbc, mapper, tenant, vendorId, lock = false) ?: fail("not_found", 404, "vendorId")
		record to credential(record)
	}

	/** 동기화가 쓸 자격증명. 연결이 지워졌으면 null. */
	fun credential(tenant: UUID, connectionId: UUID): Pair<ConnectionRecord, ConnectorCredential>? = read {
		val vendor = jdbc.sql("SELECT vendor_id FROM enrollment.vendor_connections WHERE id = :id AND tenant_id = :tenant AND deleted_at IS NULL")
			.param("id", connectionId).param("tenant", tenant).query(String::class.java).optional().orElse(null)
		vendor?.let { VendorConnections.active(jdbc, mapper, tenant, it, lock = false) }?.let { it to credential(it) }
	}

	/** 확인 결과를 남긴다. 호출하는 사이 연결이 바뀌었으면(판이 다르면) 쓰지 않는다. 판은 올리지 않는다 — 설정이 아니라 상태다. */
	fun recordCheck(tenant: UUID, vendorId: String, expectedVersion: Long, check: ConnectionCheck): ConnectionRecord = write {
		val current = VendorConnections.active(jdbc, mapper, tenant, vendorId, lock = true)
		if (current == null || current.version != expectedVersion) fail("version_conflict", 409)
		jdbc.sql("UPDATE enrollment.vendor_connections SET check_status = :check, checked_at = :now WHERE id = :id")
			.param("check", check.wire).param("now", Timestamp.from(now())).param("id", current.id).update()
		VendorConnections.active(jdbc, mapper, tenant, vendorId, lock = false)!!
	}

	fun find(tenant: UUID, vendorId: String): ConnectionRecord? = VendorConnections.active(jdbc, mapper, tenant, vendorId, lock = false)

	/** 등록 제품의 좌석 원천 — 연결 명령의 응답. 없거나 보관한 제품이면 404. */
	fun view(tenant: UUID, vendorId: String): SeatSourceView = read {
		val product = RegisteredProducts.find(jdbc, tenant, vendorId, lock = false)
		SeatSourceView.of(product.kind, product.plan, find(tenant, vendorId))
	}

	private fun credential(record: ConnectionRecord): ConnectorCredential {
		val (ciphertext, keyId) = jdbc.sql("SELECT credential_ciphertext, credential_key_id FROM enrollment.vendor_connections WHERE id = :id")
			.param("id", record.id).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		return ConnectorCredential(cipher.open(keyId, ciphertext, aad(record.tenantId, record.vendorId, record.id)))
	}

	private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
	private fun <T> write(block: () -> T): T = tx.execute { block() } as T
	private fun <T> read(block: () -> T): T = TransactionTemplate(tx.transactionManager!!).apply { isReadOnly = true }.execute { block() } as T
	private fun fail(code: String, status: Int, field: String? = null): Nothing = throw ManagementException(code, status, field)

	companion object {
		/** 암호문을 행에 묶는다 — 다른 조직·제품·연결의 행으로 옮기면 풀리지 않는다. */
		internal fun aad(tenant: UUID, vendorId: String, connectionId: UUID) = "vendor-connection/$tenant/$vendorId/$connectionId"

		/** 연결을 지운다(암호문·키·선점을 비운다). 등록 제품 보관도 이것을 부른다. */
		fun erase(jdbc: JdbcClient, connectionId: UUID, actor: UUID, now: Instant) {
			jdbc.sql("""UPDATE enrollment.vendor_connections SET deleted_at = :now, deleted_by = :actor, credential_ciphertext = NULL, credential_key_id = NULL,
					credential_updated_at = NULL, sync_claimed_by = NULL, sync_claimed_until = NULL WHERE id = :id AND deleted_at IS NULL""")
				.param("now", Timestamp.from(now)).param("actor", actor).param("id", connectionId).update()
		}

		/** 등록 제품을 보관할 때 그 제품의 활성 연결을 지운다 (ADR 0048 §6). */
		fun eraseForVendor(jdbc: JdbcClient, tenant: UUID, vendorId: String, actor: UUID, now: Instant) {
			jdbc.sql("SELECT id FROM enrollment.vendor_connections WHERE tenant_id = :tenant AND vendor_id = :vendor AND deleted_at IS NULL FOR UPDATE")
				.param("tenant", tenant).param("vendor", vendorId).query { rs, _ -> rs.getObject(1, UUID::class.java) }.list().forEach { erase(jdbc, it, actor, now) }
		}
	}
}
