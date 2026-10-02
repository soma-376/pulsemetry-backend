package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Pulsemetry 를 사용하는 고객 조직.
 *
 * 필수 컬럼에는 기본값을 두지 않는다 — 호출자가 반드시 채우게 하기 위해서다.
 * Hibernate 가 쓰는 no-arg 생성자는 kotlin-jpa(noarg) 플러그인이 만들어 준다.
 */
@Entity
@Table(name = "tenants", schema = "enrollment")
class Tenant(

	@Column(name = "name", nullable = false, length = 100)
	var name: String,

	@Id
	@Column(name = "id", nullable = false)
	var id: UUID = UUID.randomUUID(),

	@Column(name = "slug", length = 100)
	var slug: String? = null,

	@Column(name = "timezone", nullable = false, length = 50)
	var timezone: String = "Asia/Seoul",

	@Column(name = "logo_url")
	var logoUrl: String? = null,

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "tenant_status")
	var status: TenantStatus = TenantStatus.active,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.now(),

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.now(),

	@Column(name = "deleted_at")
	var deletedAt: Instant? = null,

	@Column(name = "onboarding_completed_at")
	var onboardingCompletedAt: Instant? = null,

	@Column(name = "oidc_issuer", length = 512)
	var oidcIssuer: String? = null,
	@Column(name = "oidc_client_id", length = 255)
	var oidcClientId: String? = null,
	@Column(name = "oidc_client_secret_ref", length = 255)
	var oidcClientSecretRef: String? = null,
	@Column(name = "sso_enabled", nullable = false)
	var ssoEnabled: Boolean = false,
	@Column(name = "oidc_require_verified_email", nullable = false)
	var oidcRequireVerifiedEmail: Boolean = false,
) {
	/** DB의 생성 컬럼과 동일하게 완료 시각으로 판정한다(ADR 0032). */
	@get:Transient
	val onboardingCompleted: Boolean get() = onboardingCompletedAt != null
}
