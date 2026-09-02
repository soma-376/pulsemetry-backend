package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * V4 가 **기존 데이터를 보존한 채** `cognito_user_sub` 자리를 걷어내는지 확인한다 (ADR 0007 Follow-up).
 *
 * `DROP COLUMN` 은 비가역이다 — 배포된 DB 에서 한 번 돌면 그 컬럼의 값은 백업에서만 복구된다.
 * 그래서 "새 스키마가 이렇게 생겼다" 가 아니라 **"이미 있던 행이 그대로 남는가"** 를 본다.
 * 다른 마이그레이션 테스트([EnrollmentSchemaMigrationTest])는 전부 적용된 뒤의 최종 형태만 보므로
 * 이 질문에 답할 수 없다. 여기서만 V3 까지 올린 뒤 데이터를 넣고 V4 를 적용한다.
 *
 * 컨테이너는 스프링 컨텍스트가 띄운 것을 그대로 쓰되(컨테이너를 하나 더 띄우지 않는다),
 * **별도 데이터베이스**를 만들어 거기서 단계별 마이그레이션을 돌린다. 컨텍스트의 DB 는 이미
 * 최신까지 적용돼 있어 V3 상태를 재현할 수 없기 때문이다.
 */
class MembersCognitoRemovalMigrationTest : AbstractPersistenceIntegrationTest() {

	@Autowired
	private lateinit var postgres: PostgreSQLContainer

	private lateinit var probeDatabase: String
	private lateinit var probe: JdbcClient

	private lateinit var tenantId: UUID
	private lateinit var memberWithSubId: UUID
	private lateinit var memberWithoutSubId: UUID
	private lateinit var installationId: UUID

	/** V4 적용 **직전**의 members 행. 적용 후와 비교할 기준이다. */
	private lateinit var before: Map<UUID, MemberRow>

	@BeforeEach
	fun migrateAcrossV4WithExistingData() {
		probeDatabase = "v4_probe_" + UUID.randomUUID().toString().replace("-", "")
		onAdmin("CREATE DATABASE $probeDatabase")
		probe = JdbcClient.create(probeDataSource())

		// ① cognito_user_sub 가 아직 살아 있는 시점까지만 올린다.
		migrateTo(MigrationVersion.fromVersion("3"))
		seedExistingData()
		before = readMembers()

		// ② 여기서 컬럼이 걷힌다.
		migrateTo(MigrationVersion.LATEST)
	}

	@AfterEach
	fun dropProbeDatabase() {
		// FORCE 는 남은 연결을 끊는다. 없으면 Flyway 가 쓰던 연결 때문에 55006 으로 실패할 수 있다.
		onAdmin("DROP DATABASE IF EXISTS $probeDatabase WITH (FORCE)")
	}

	// ── 데이터 보존 ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("V4 가 기존 member 행을 하나도 잃지 않는다 — 남는 컬럼의 값이 전부 그대로다")
	fun existingMembersSurviveUntouched() {
		val after = readMembers()

		assertThat(after).containsExactlyInAnyOrderEntriesOf(before)
		assertThat(after.keys).containsExactlyInAnyOrder(memberWithSubId, memberWithoutSubId)
	}

	@Test
	@DisplayName("member 를 참조하던 installation 이 그대로 남는다 — 컬럼 제거가 FK 로 번지지 않는다")
	fun referencingRowsSurvive() {
		val referencedMemberId = probe
			.sql("SELECT member_id FROM enrollment.installations WHERE id = :id")
			.param("id", installationId)
			.query(UUID::class.java)
			.single()

		assertThat(referencedMemberId).isEqualTo(memberWithSubId)
	}

	@Test
	@DisplayName("Flyway 가 V4 를 성공으로 기록한다")
	fun flywayAppliedV4() {
		val applied = probe
			.sql("SELECT success FROM enrollment.flyway_schema_history WHERE version = '4'")
			.query(Boolean::class.javaObjectType)
			.single()

		assertThat(applied).isTrue()
	}

	// ── 걷어낸 것 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("cognito_user_sub 컬럼과 그에 딸린 유니크 제약·인덱스가 함께 사라진다")
	fun cognitoColumnConstraintAndIndexAreGone() {
		assertThat(columnNames("members")).doesNotContain("cognito_user_sub")

		// 이름으로 지우지 않고 DROP COLUMN 에 딸려 지우므로, 파이프라인 DDL 로 baseline 된 DB 의
		// 자동 생성 이름(members_tenant_id_cognito_user_sub_key 등)도 같이 사라진다.
		// 그래서 이름이 아니라 "members 에 걸린 cognito 관련 객체가 없다" 로 확인한다.
		assertThat(constraintNames("members")).noneMatch { it.contains("cognito") }
		assertThat(indexNames("members")).noneMatch { it.contains("cognito") }
	}

	// ── 새로 생긴 자리 ───────────────────────────────────────────────────────

	@Test
	@DisplayName("password_hash 자리가 생기고 기존 행에는 NULL 로 남는다 — 기존 구성원이 잠기지 않는다")
	fun passwordHashPlaceholderIsAddedAsNullable() {
		assertThat(columnNames("members")).contains("password_hash")

		val nullable = probe
			.sql(
				"""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_schema = 'enrollment' AND table_name = 'members' AND column_name = 'password_hash'
				""",
			)
			.query(String::class.java)
			.single()

		assertThat(nullable).isEqualTo("YES")

		val withoutPassword = probe
			.sql("SELECT count(*) FROM enrollment.members WHERE password_hash IS NULL")
			.query(Long::class.javaObjectType)
			.single()

		assertThat(withoutPassword).isEqualTo(2L)
	}

	// ── 남아 있어야 하는 불변식 ──────────────────────────────────────────────

	@Test
	@DisplayName("(tenant_id, email) 유니크는 그대로 살아 있다 — 구성원 식별은 이제 이것뿐이다")
	fun tenantEmailUniqueStillEnforced() {
		assertThatThrownBy {
			insertMember(email = "admin@example.com", role = "member", status = "active")
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	// ── 픽스처 ───────────────────────────────────────────────────────────────

	private fun seedExistingData() {
		tenantId = UUID.randomUUID()
		probe
			.sql("INSERT INTO enrollment.tenants (id, name) VALUES (:id, :name)")
			.param("id", tenantId)
			.param("name", "테스트 조직")
			.update()

		// 실제로 값이 들어 있던 행. 이 값만 사라지고 나머지는 남아야 한다.
		memberWithSubId = insertMember(
			email = "admin@example.com",
			role = "admin",
			status = "active",
			displayName = "관리자",
			cognitoUserSub = "ap-northeast-2:0000-1111-2222",
		)

		// CLI 전용 구성원. 애초에 sub 가 NULL 이었다 — 제거가 이 행에 아무 영향이 없어야 한다.
		memberWithoutSubId = insertMember(
			email = "cli@example.com",
			role = "member",
			status = "invited",
		)

		val invitationId = UUID.randomUUID()
		probe
			.sql(
				"""
				INSERT INTO enrollment.invitations
					(id, tenant_id, target_member_id, created_by_member_id, code_hash, expires_at)
				VALUES (:id, :tenantId, :memberId, :memberId, :codeHash, :expiresAt)
				""",
			)
			.param("id", invitationId)
			.param("tenantId", tenantId)
			.param("memberId", memberWithSubId)
			.param("codeHash", UUID.randomUUID().toString().replace("-", "").repeat(2))
			.param("expiresAt", OffsetDateTime.now().plusHours(72))
			.update()

		installationId = UUID.randomUUID()
		probe
			.sql(
				"""
				INSERT INTO enrollment.installations
					(id, tenant_id, member_id, invitation_id, platform)
				VALUES (:id, :tenantId, :memberId, :invitationId, CAST('macos' AS enrollment.platform_type))
				""",
			)
			.param("id", installationId)
			.param("tenantId", tenantId)
			.param("memberId", memberWithSubId)
			.param("invitationId", invitationId)
			.update()
	}

	private fun insertMember(
		email: String,
		role: String,
		status: String,
		displayName: String? = null,
		cognitoUserSub: String? = null,
	): UUID {
		val id = UUID.randomUUID()
		// cognito_user_sub 는 V4 이후 존재하지 않으므로, 그 컬럼을 쓰는 INSERT 는 seed 시점에만 성립한다.
		val columns = if (cognitoUserSub == null) "" else ", cognito_user_sub"
		val values = if (cognitoUserSub == null) "" else ", :cognitoUserSub"
		val statement = probe
			.sql(
				"""
				INSERT INTO enrollment.members (id, tenant_id, email, display_name, role, status$columns)
				VALUES (
					:id, :tenantId, :email, :displayName,
					CAST(:role AS enrollment.member_role),
					CAST(:status AS enrollment.member_status)$values
				)
				""",
			)
			.param("id", id)
			.param("tenantId", tenantId)
			.param("email", email)
			.param("displayName", displayName)
			.param("role", role)
			.param("status", status)
		if (cognitoUserSub != null) {
			statement.param("cognitoUserSub", cognitoUserSub)
		}
		statement.update()
		return id
	}

	private fun readMembers(): Map<UUID, MemberRow> =
		probe
			.sql(
				"""
				SELECT id, tenant_id, email, display_name, role::text AS role, status::text AS status,
				       created_at, updated_at
				FROM enrollment.members
				""",
			)
			.query(
				RowMapper { rs, _ ->
					MemberRow(
						id = rs.getObject("id", UUID::class.java),
						tenantId = rs.getObject("tenant_id", UUID::class.java),
						email = rs.getString("email"),
						displayName = rs.getString("display_name"),
						role = rs.getString("role"),
						status = rs.getString("status"),
						createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
						updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
					)
				},
			)
			.list()
			.associateBy { it.id }

	private fun columnNames(table: String): List<String> =
		probe
			.sql(
				"""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'enrollment' AND table_name = :table
				""",
			)
			.param("table", table)
			.query(String::class.java)
			.list()
			.filterNotNull()

	private fun constraintNames(table: String): List<String> =
		probe
			.sql(
				"""
				SELECT c.conname FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = 'enrollment' AND t.relname = :table
				""",
			)
			.param("table", table)
			.query(String::class.java)
			.list()
			.filterNotNull()

	private fun indexNames(table: String): List<String> =
		probe
			.sql(
				"""
				SELECT indexname FROM pg_indexes
				WHERE schemaname = 'enrollment' AND tablename = :table
				""",
			)
			.param("table", table)
			.query(String::class.java)
			.list()
			.filterNotNull()

	// ── 단계별 마이그레이션을 위한 별도 데이터베이스 ─────────────────────────

	private fun migrateTo(target: MigrationVersion) {
		Flyway.configure()
			.dataSource(probeJdbcUrl(), postgres.username, postgres.password)
			.locations("classpath:db/migration")
			.schemas("enrollment")
			.defaultSchema("enrollment")
			.target(target)
			.load()
			.migrate()
	}

	private fun probeJdbcUrl(): String =
		"jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$probeDatabase"

	private fun probeDataSource(): DataSource =
		DriverManagerDataSource(probeJdbcUrl(), postgres.username, postgres.password)

	/**
	 * CREATE·DROP DATABASE 는 트랜잭션 블록 안에서 돌 수 없다.
	 * 확장 질의 프로토콜을 타지 않도록 단순 [java.sql.Statement] 로 실행한다.
	 */
	private fun onAdmin(sql: String) {
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { it.execute(sql) }
		}
	}

	private data class MemberRow(
		val id: UUID,
		val tenantId: UUID,
		val email: String,
		val displayName: String?,
		val role: String,
		val status: String,
		val createdAt: OffsetDateTime,
		val updatedAt: OffsetDateTime,
	)
}
