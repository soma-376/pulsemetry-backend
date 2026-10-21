package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TelemetryTokenRepository : JpaRepository<TelemetryToken, UUID> {

	fun findByTokenHash(tokenHash: String): TelemetryToken?

	fun findAllByInstallationIdAndRevokedAtIsNull(installationId: UUID): List<TelemetryToken>

	/**
	 * 해당 installation 의 살아있는 telemetry token 을 전부 정상 회전(`rotated`)으로 폐기한다.
	 * 재발급(`POST /api/v1/installations/telemetry-token`, `docs/enrollment-server-spec.md` §4.3)은
	 * 새 토큰을 만들기 전에 이걸 먼저 부른다.
	 *
	 * **`revoked_at IS NULL` 인 행만 건드린다** — 이미 폐기된 행의 사유(특히 `security`)를 회전으로 덮어쓰지 않는다
	 * (ADR 0065 Decision 5).
	 *
	 * @return 폐기된 행 수. 이미 폐기된 토큰은 세지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"""
		UPDATE TelemetryToken t
		SET t.revokedAt = :now,
		    t.revokeReason = com.team376.pulsemetry.persistence.enrollment.entity.TelemetryTokenRevokeReason.rotated
		WHERE t.installationId = :installationId
		  AND t.revokedAt IS NULL
		""",
	)
	fun revokeActiveByInstallationId(
		@Param("installationId") installationId: UUID,
		@Param("now") now: Instant,
	): Int

	/**
	 * OTLP 인증이 쓰는 조회. 토큰 해시 하나로 신원과 거부 사유 판정에 필요한 상태를 한 번에 가져온다.
	 *
	 * **`WHERE` 절에서 상태를 거르지 않는다.** 거부 사유 아홉 가지를 구분하려면 행을 받아 온 뒤
	 * 애플리케이션이 순서대로 판정해야 하기 때문이다. 판정 체인은 `:libs:security` 가 갖는다.
	 *
	 * **네이티브인 이유는 셋이다.**
	 * 1. enum 컬럼을 JPQL 로 비교하면 Hibernate 가 Java enum 단순명에서 유도한 타입명으로 캐스팅해
	 *    실제 DB 타입과 어긋나 42704 로 죽는다 ([EnrollmentRepositories] 의 같은 주석).
	 * 2. 엔티티에 연관관계가 없다 — 전부 raw UUID 컬럼이라 객체 그래프 탐색이 성립하지 않는다.
	 * 3. 이 경로는 텔레메트리 요청마다 돈다. 단건 조회 네 번으로 쪼개면 왕복이 네 배가 된다.
	 *
	 * **상태를 `::text` 로, 폐기 시각을 `IS NOT NULL` 로 투영하는 것은 의도다.**
	 * 이식 원본(auth-proxy)이 문자열 동등 비교와 널 검사로 판정하므로, 같은 모양으로 받아야
	 * 동작이 같아진다. 특히 enum 에 값이 추가됐을 때 — 원본은 member·tenant 의 모르는 상태를
	 * **통과**시키는데, Kotlin enum 으로 매핑하면 역직렬화에서 터진다.
	 */
	@Query(
		nativeQuery = true,
		value = """
		SELECT tt.id                       AS tokenId,
		       (tt.revoked_at IS NOT NULL) AS tokenRevoked,
		       i.tenant_id                 AS tenantId,
		       i.id                        AS installationId,
		       i.member_id                 AS memberId,
		       i.status::text              AS installationStatus,
		       (i.revoked_at IS NOT NULL)  AS installationRevoked,
		       m.status::text              AS memberStatus,
		       t.status::text              AS tenantStatus,
		       (t.deleted_at IS NOT NULL)  AS tenantDeleted
		  FROM enrollment.telemetry_tokens AS tt
		  JOIN enrollment.installations AS i ON i.id = tt.installation_id
		  JOIN enrollment.members AS m ON m.id = i.member_id
		  JOIN enrollment.tenants AS t ON t.id = i.tenant_id
		 WHERE tt.token_hash = :tokenHash
		 LIMIT 1
		""",
	)
	fun findAuthRowByTokenHash(@Param("tokenHash") tokenHash: String): TelemetryTokenAuthRow?

	/**
	 * telemetry JWT 의 RDB 판정이 쓰는 조회(ADR 0065 Decision 6). `jti`(= 행 id) 하나로 자격 기록과 설치·회원·조직 상태를
	 * 함께 읽는다. **해시로 찾지 않는다** — JWT 는 서명으로 이미 출처를 확인했고, 해시는 구 ingest 롤백 호환용이다.
	 *
	 * [findAuthRowByTokenHash] 와 같은 이유로 네이티브이고 상태를 `::text` 로 투영한다. 형식·사유도 문자열로 받는다 —
	 * 판정이 모르는 값을 거부할 수 있어야 한다.
	 */
	@Query(
		nativeQuery = true,
		value = """
		SELECT tt.id                       AS tokenId,
		       (tt.revoked_at IS NOT NULL) AS tokenRevoked,
		       i.tenant_id                 AS tenantId,
		       i.id                        AS installationId,
		       i.member_id                 AS memberId,
		       i.status::text              AS installationStatus,
		       (i.revoked_at IS NOT NULL)  AS installationRevoked,
		       m.status::text              AS memberStatus,
		       t.status::text              AS tenantStatus,
		       (t.deleted_at IS NOT NULL)  AS tenantDeleted,
		       tt.token_format             AS tokenFormat,
		       tt.issued_at                AS issuedAt,
		       tt.expires_at               AS expiresAt,
		       tt.signing_kid              AS signingKid,
		       tt.revoked_at               AS revokedAt,
		       tt.revoke_reason            AS revokeReason
		  FROM enrollment.telemetry_tokens AS tt
		  JOIN enrollment.installations AS i ON i.id = tt.installation_id
		  JOIN enrollment.members AS m ON m.id = i.member_id
		  JOIN enrollment.tenants AS t ON t.id = i.tenant_id
		 WHERE tt.id = :credentialId
		""",
	)
	fun findCredentialRowById(@Param("credentialId") credentialId: UUID): TelemetryCredentialRow?
}

/**
 * [TelemetryTokenRepository.findAuthRowByTokenHash] 의 투영.
 *
 * 상태가 `String` 인 것은 매핑 편의가 아니라 계약이다 — 이식 원본의 판정이 문자열 동등 비교다.
 */
interface TelemetryTokenAuthRow {
	val tokenId: UUID
	val tokenRevoked: Boolean
	val tenantId: UUID
	val installationId: UUID
	val memberId: UUID
	val installationStatus: String
	val installationRevoked: Boolean
	val memberStatus: String
	val tenantStatus: String
	val tenantDeleted: Boolean
}

/**
 * [TelemetryTokenRepository.findCredentialRowById] 의 투영. 설치·회원·조직 상태는 [TelemetryTokenAuthRow] 와 같고
 * (`TelemetryTokenDecision` 을 그대로 쓴다), 자격 기록의 형식·시각·kid·폐기 사유를 더한다.
 *
 * [tokenFormat]·[revokeReason] 이 문자열인 것은 의도다 — 모르는 값이 역직렬화 예외가 아니라 판정의 거부가 되게 한다.
 */
interface TelemetryCredentialRow : TelemetryTokenAuthRow {
	val tokenFormat: String
	val issuedAt: Instant
	val expiresAt: Instant?
	val signingKid: String?
	val revokedAt: Instant?
	val revokeReason: String?
}
