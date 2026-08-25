package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * V1__enrollment_schema.sql 이 실제 PostgreSQL 에 적용되는지, 그리고
 * dbml 의 enum 이 Postgres native enum 타입으로 살아있는지 확인한다 (ADR 0009).
 *
 * 제약은 카탈로그 조회(존재 여부)와 실제 INSERT(동작) 양쪽으로 본다.
 * 존재만 확인하면 조건이 잘못 걸려 있어도 통과하기 때문이다.
 */
@Transactional
class EnrollmentSchemaMigrationTest : AbstractPersistenceIntegrationTest() {

	@Autowired
	private lateinit var jdbcClient: JdbcClient

	// ── 마이그레이션 자체 ────────────────────────────────────────────────────

	@Test
	@DisplayName("Flyway 가 V1 마이그레이션을 성공으로 기록한다")
	fun flywayAppliedV1() {
		val applied = jdbcClient
			.sql(
				"""
				SELECT success FROM enrollment.flyway_schema_history
				WHERE version = '1'
				""",
			)
			.query(Boolean::class.javaObjectType)
			.single()

		assertThat(applied).isTrue()
	}

	@Test
	@DisplayName("dbml 의 enrollment 테이블 14종이 모두 생성된다")
	fun allTablesCreated() {
		val tables = jdbcClient
			.sql(
				"""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'enrollment' AND table_type = 'BASE TABLE'
				""",
			)
			.query(String::class.java)
			.list()

		assertThat(tables).contains(
			"tenants",
			"teams",
			"team_memberships",
			"members",
			"invitations",
			"installations",
			"installation_credentials",
			"telemetry_tokens",
			"manifests",
			"installation_manifest_assignments",
			"contracts",
			"contract_term_commitments",
			"contract_token_discounts",
			"contract_memberships",
		)
	}

	// ── enum 이식 방식 (ADR 0009 — native enum, 파이프라인 DDL 과 형태 일치) ──

	@Test
	@DisplayName("dbml 의 enum 10종이 Postgres native enum 타입으로 생성된다")
	fun nativeEnumTypesExist() {
		val enumTypes = jdbcClient
			.sql(
				"""
				SELECT t.typname FROM pg_type t
				JOIN pg_namespace n ON n.oid = t.typnamespace
				WHERE n.nspname = 'enrollment' AND t.typtype = 'e'
				""",
			)
			.query(String::class.java)
			.list()

		assertThat(enumTypes).containsExactlyInAnyOrder(
			"tenant_status",
			"team_status",
			"member_role",
			"member_status",
			"installation_status",
			"platform_type",
			"ai_vendor",
			"contract_type",
			"contract_status",
			"token_type",
		)
	}

	@Test
	@DisplayName("enum 라벨이 dbml 의 값과 정확히 일치한다 — 라벨이 곧 계약이다")
	fun enumLabelsMatchDbml() {
		assertThat(enumLabels("member_status")).containsExactly("invited", "active", "suspended")
		assertThat(enumLabels("member_role")).containsExactly("owner", "admin", "member")
		assertThat(enumLabels("platform_type")).containsExactly("windows", "macos", "linux")
		assertThat(enumLabels("installation_status")).containsExactly("active", "revoked")
		assertThat(enumLabels("tenant_status")).containsExactly("active", "suspended", "terminated")
		assertThat(enumLabels("team_status")).containsExactly("active", "archived")
		assertThat(enumLabels("ai_vendor")).containsExactly("anthropic", "openai", "google")
		assertThat(enumLabels("contract_type")).containsExactly("term_commitment", "token_discount")
		assertThat(enumLabels("contract_status")).containsExactly("draft", "active", "expired", "terminated")
		assertThat(enumLabels("token_type")).containsExactly("input", "output", "cache_read", "cache_create", "all")
	}

	@Test
	@DisplayName("platform enum 이 정규화되지 않은 값을 거부한다")
	fun platformCheckRejectsRawGoos() {
		val tenantId = insertTenant()
		val memberId = insertMember(tenantId, "user@example.com")
		val invitationId = insertInvitation(tenantId, memberId)

		// 클라이언트는 runtime.GOOS 를 그대로 보낸다. 서버가 macos 로 정규화하지 않으면 여기서 막힌다.
		assertThatThrownBy {
			insertInstallation(tenantId, memberId, invitationId, platform = "darwin")
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("platform enum 이 정규화된 값은 통과시킨다")
	fun platformCheckAcceptsNormalizedValues() {
		val tenantId = insertTenant()
		val memberId = insertMember(tenantId, "user@example.com")

		listOf("windows", "macos", "linux").forEach { platform ->
			val invitationId = insertInvitation(tenantId, memberId)
			insertInstallation(tenantId, memberId, invitationId, platform = platform)
		}

		val stored = jdbcClient
			.sql("SELECT count(*) FROM enrollment.installations WHERE tenant_id = :tenantId")
			.param("tenantId", tenantId)
			.query(Long::class.javaObjectType)
			.single()

		assertThat(stored).isEqualTo(3L)
	}

	@Test
	@DisplayName("member role enum 이 정의되지 않은 역할을 거부한다")
	fun memberRoleCheckRejectsUnknownRole() {
		val tenantId = insertTenant()

		assertThatThrownBy {
			insertMember(tenantId, "root@example.com", role = "superadmin")
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	// ── 유니크 제약 ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("dbml 의 유니크 제약이 모두 살아 있다")
	fun uniqueConstraintsExist() {
		assertThat(constraintNames('u')).contains(
			"uq_tenants_slug",
			"uq_teams_tenant_name",
			"uq_members_tenant_cognito_user_sub",
			"uq_members_tenant_email",
			"uq_invitations_code_hash",
			"uq_installation_credentials_credential_hash",
			"uq_telemetry_tokens_token_hash",
			"uq_manifests_tenant_version",
			"uq_contracts_tenant_contract_no",
			"uq_contract_token_discounts_contract_model_token_from",
		)
	}

	@Test
	@DisplayName("초대 코드 해시는 전역 유일하다 — 같은 코드가 두 번 발급될 수 없다")
	fun invitationCodeHashIsUnique() {
		val tenantId = insertTenant()
		val memberId = insertMember(tenantId, "user@example.com")
		val codeHash = "a".repeat(64)

		insertInvitation(tenantId, memberId, codeHash = codeHash)

		assertThatThrownBy {
			insertInvitation(tenantId, memberId, codeHash = codeHash)
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("같은 tenant 안에서 email 은 유일하다")
	fun memberEmailIsUniquePerTenant() {
		val tenantId = insertTenant()
		insertMember(tenantId, "dup@example.com")

		assertThatThrownBy {
			insertMember(tenantId, "dup@example.com")
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	// ── manifests 부분 유니크 인덱스 (SCHEMA-DRIFT) ──────────────────────────

	@Test
	@DisplayName("manifests 에 (tenant_id) WHERE is_active 부분 유니크 인덱스가 있다")
	fun activeManifestPartialUniqueIndexExists() {
		val indexDef = jdbcClient
			.sql(
				"""
				SELECT indexdef FROM pg_indexes
				WHERE schemaname = 'enrollment' AND indexname = 'ux_manifests_tenant_active'
				""",
			)
			.query(String::class.java)
			.single()

		assertThat(indexDef)
			.containsIgnoringCase("CREATE UNIQUE INDEX")
			.containsIgnoringCase("WHERE is_active")
	}

	@Test
	@DisplayName("tenant 당 active manifest 는 하나뿐이다")
	fun onlyOneActiveManifestPerTenant() {
		val tenantId = insertTenant()
		val memberId = insertMember(tenantId, "admin@example.com")

		insertManifest(tenantId, memberId, version = 1, isActive = true)

		assertThatThrownBy {
			insertManifest(tenantId, memberId, version = 2, isActive = true)
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("비활성 manifest 는 tenant 당 여러 개일 수 있다")
	fun manyInactiveManifestsPerTenant() {
		val tenantId = insertTenant()
		val memberId = insertMember(tenantId, "admin@example.com")

		insertManifest(tenantId, memberId, version = 1, isActive = false)
		insertManifest(tenantId, memberId, version = 2, isActive = false)
		insertManifest(tenantId, memberId, version = 3, isActive = true)

		val total = jdbcClient
			.sql("SELECT count(*) FROM enrollment.manifests WHERE tenant_id = :tenantId")
			.param("tenantId", tenantId)
			.query(Long::class.javaObjectType)
			.single()

		assertThat(total).isEqualTo(3L)
	}

	@Test
	@DisplayName("manifest 컬럼이 jsonb 이라 JSON 연산자를 쓸 수 있다")
	fun manifestColumnIsJsonb() {
		val tenantId = insertTenant()
		val memberId = insertMember(tenantId, "admin@example.com")
		insertManifest(
			tenantId,
			memberId,
			version = 1,
			isActive = true,
			manifestJson = """{"schema_version": 1, "otlp": {"protocol": "http/protobuf"}}""",
		)

		val protocol = jdbcClient
			.sql(
				"""
				SELECT manifest -> 'otlp' ->> 'protocol' FROM enrollment.manifests
				WHERE tenant_id = :tenantId
				""",
			)
			.param("tenantId", tenantId)
			.query(String::class.java)
			.single()

		assertThat(protocol).isEqualTo("http/protobuf")
	}

	// ── 복합 PK ──────────────────────────────────────────────────────────────

	@Test
	@DisplayName("installation_manifest_assignments 의 PK 는 (installation_id, manifest_id) 복합키다")
	fun assignmentHasCompositePrimaryKey() {
		val pkColumns = jdbcClient
			.sql(
				"""
				SELECT a.attname FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
				JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
				WHERE n.nspname = 'enrollment'
				  AND t.relname = 'installation_manifest_assignments'
				  AND c.contype = 'p'
				ORDER BY k.ord
				""",
			)
			.query(String::class.java)
			.list()

		assertThat(pkColumns).containsExactly("installation_id", "manifest_id")
	}

	// ── 픽스처 ───────────────────────────────────────────────────────────────

	private fun constraintNames(contype: Char): List<String?> =
		jdbcClient
			.sql(
				"""
				SELECT c.conname FROM pg_constraint c
				JOIN pg_namespace n ON n.oid = c.connamespace
				WHERE n.nspname = 'enrollment' AND c.contype = :contype
				""",
			)
			.param("contype", contype.toString())
			.query(String::class.java)
			.list()

	private fun enumLabels(typeName: String): List<String?> =
		jdbcClient
			.sql(
				"""
				SELECT e.enumlabel FROM pg_enum e
				JOIN pg_type t ON t.oid = e.enumtypid
				JOIN pg_namespace n ON n.oid = t.typnamespace
				WHERE n.nspname = 'enrollment' AND t.typname = :typeName
				ORDER BY e.enumsortorder
				""",
			)
			.param("typeName", typeName)
			.query(String::class.java)
			.list()

	private fun insertTenant(): UUID {
		val id = UUID.randomUUID()
		jdbcClient
			.sql("INSERT INTO enrollment.tenants (id, name) VALUES (:id, :name)")
			.param("id", id)
			.param("name", "테스트 조직")
			.update()
		return id
	}

	private fun insertMember(tenantId: UUID, email: String, role: String = "owner"): UUID {
		val id = UUID.randomUUID()
		jdbcClient
			.sql(
				// enum 컬럼에 varchar 파라미터를 그대로 넣으면 42804 로 거부된다 — 명시적 CAST 가 필요하다.
				// CAST 를 거치면 미정의 라벨은 22P02(invalid input value) 로 실패한다.
				"""
				INSERT INTO enrollment.members (id, tenant_id, email, role, status)
				VALUES (:id, :tenantId, :email, CAST(:role AS enrollment.member_role), 'active')
				""",
			)
			.param("id", id)
			.param("tenantId", tenantId)
			.param("email", email)
			.param("role", role)
			.update()
		return id
	}

	private fun insertInvitation(
		tenantId: UUID,
		memberId: UUID,
		codeHash: String = UUID.randomUUID().toString().replace("-", "").repeat(2),
	): UUID {
		val id = UUID.randomUUID()
		jdbcClient
			.sql(
				"""
				INSERT INTO enrollment.invitations
					(id, tenant_id, target_member_id, created_by_member_id, code_hash, expires_at)
				VALUES (:id, :tenantId, :memberId, :memberId, :codeHash, :expiresAt)
				""",
			)
			.param("id", id)
			.param("tenantId", tenantId)
			.param("memberId", memberId)
			.param("codeHash", codeHash)
			.param("expiresAt", OffsetDateTime.now().plusHours(72))
			.update()
		return id
	}

	private fun insertInstallation(
		tenantId: UUID,
		memberId: UUID,
		invitationId: UUID,
		platform: String,
	): UUID {
		val id = UUID.randomUUID()
		jdbcClient
			.sql(
				"""
				INSERT INTO enrollment.installations
					(id, tenant_id, member_id, invitation_id, platform)
				VALUES (:id, :tenantId, :memberId, :invitationId, CAST(:platform AS enrollment.platform_type))
				""",
			)
			.param("id", id)
			.param("tenantId", tenantId)
			.param("memberId", memberId)
			.param("invitationId", invitationId)
			.param("platform", platform)
			.update()
		return id
	}

	private fun insertManifest(
		tenantId: UUID,
		memberId: UUID,
		version: Int,
		isActive: Boolean,
		manifestJson: String = """{"schema_version": 1}""",
	): UUID {
		val id = UUID.randomUUID()
		jdbcClient
			.sql(
				"""
				INSERT INTO enrollment.manifests
					(id, tenant_id, version, manifest, is_active, created_by_member_id)
				VALUES (:id, :tenantId, :version, CAST(:manifest AS jsonb), :isActive, :memberId)
				""",
			)
			.param("id", id)
			.param("tenantId", tenantId)
			.param("version", version)
			.param("manifest", manifestJson)
			.param("isActive", isActive)
			.param("memberId", memberId)
			.update()
		return id
	}
}
