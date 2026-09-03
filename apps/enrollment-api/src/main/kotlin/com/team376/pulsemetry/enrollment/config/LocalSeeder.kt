package com.team376.pulsemetry.enrollment.config

import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.persistence.enrollment.entity.Invitation
import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.entity.Team
import com.team376.pulsemetry.persistence.enrollment.entity.TeamMembership
import com.team376.pulsemetry.persistence.enrollment.entity.Tenant
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.ManifestRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TenantRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 로컬 개발용 tenant · owner member · 팀 · 소속 · 활성 manifest · 초대를 넣는다.
 *
 * Flyway 마이그레이션에는 시드 데이터를 넣지 않는다(운영 DB 에도 그대로 들어가기 때문). 그래서
 * 이 시더가 그 몫을 대신한다 — 이게 없으면 갓 띄운 로컬 서버의 첫 enroll 이
 * 409 `manifest_not_configured` 로 실패한다.
 *
 * **`local` 프로파일에서만 뜬다.** 테스트 컨텍스트에 딸려 들어가면 행 수를 세는 단언들이 깨진다.
 *
 * ## 파이프라인이 붙으면서 늘어난 것
 *
 * `:apps:telemetry-ingest` 의 보강 단계가 `installations → members → team_memberships` 로
 * 팀을 찾는다. 팀과 소속이 없으면 `enriched_events.team_ids_as_of` 가 언제나 비어서, 배선이
 * 틀린 것인지 데이터가 없는 것인지 로컬에서 구분할 수 없다.
 *
 * `ai-telemetry-pipeline` 의 `sql/rds/seed.sql` 이 이 자리를 대신하고 있었다. **시드의 진실원을
 * 이 파일 하나로 모은다** — SQL 시드는 backend 의 Flyway 보다 먼저 돌아 스키마가 없는 시점에
 * 적용되고, 두 벌이 갈리면 어느 쪽이 사실인지가 실행 순서로 정해진다.
 *
 * ## 단계마다 따로 확인한다
 *
 * 예전에는 tenant 하나만 보고 전부 건너뛰었다. 그러면 **이미 tenant 가 있는 로컬 DB 에는
 * 새 시드가 영원히 들어가지 않는다.** 항목마다 있는지 보고 없는 것만 만든다.
 */
@Component
@Profile("local")
class LocalSeeder(
	private val tenants: TenantRepository,
	private val members: MemberRepository,
	private val teams: TeamRepository,
	private val teamMemberships: TeamMembershipRepository,
	private val manifests: ManifestRepository,
	private val invitations: InvitationRepository,
	private val clock: Clock,
	/**
	 * 시드 manifest 의 `otlp.endpoint`.
	 *
	 * 기본값 `:4316` 은 `:apps:telemetry-ingest` 가 로컬에서 듣는 포트다. 구 auth-proxy 가
	 * 쓰던 포트를 그대로 물려받아, 데몬도 이 시드도 바꾸지 않고 새 앱으로 붙는다.
	 * `:4318` 은 telemetryctl 로컬 수신기의 기본 포트라 자기참조가 된다 — 쓰지 마라.
	 */
	@Value("\${pulsemetry.local-seed.otlp-endpoint:http://localhost:4316}")
	private val seedOtlpEndpoint: String,
) : ApplicationRunner {

	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional
	override fun run(args: ApplicationArguments) {
		val now = clock.instant()

		val tenant = tenants.findAll().firstOrNull() ?: tenants.save(
			Tenant(name = SEED_TENANT_NAME, createdAt = now, updatedAt = now),
		)

		val owner = members.findByTenantIdAndEmail(tenant.id, SEED_OWNER_EMAIL) ?: members.save(
			Member(
				tenantId = tenant.id,
				email = SEED_OWNER_EMAIL,
				displayName = "로컬 관리자",
				role = MemberRole.owner,
				status = MemberStatus.active,
				createdAt = now,
				updatedAt = now,
			),
		)

		val team = teams.findAllByTenantId(tenant.id).firstOrNull { it.name == SEED_TEAM_NAME }
			?: teams.save(
				Team(tenantId = tenant.id, name = SEED_TEAM_NAME, createdAt = now, updatedAt = now),
			)

		// 소속은 **초대 대상 member** 에 걸려야 한다. 보강이 installation 의 member 로 조인한다.
		if (teamMemberships.findAllByMemberId(owner.id).none { it.teamId == team.id }) {
			teamMemberships.save(
				TeamMembership(
					teamId = team.id,
					memberId = owner.id,
					// 이벤트 시각을 넉넉히 덮는다. as-of 판정이 걸러 내면 team_ids 가 빈다.
					joinedAt = now.minus(365, ChronoUnit.DAYS),
					leftAt = null,
				),
			)
		}

		if (manifests.findAll().none { it.tenantId == tenant.id }) {
			manifests.save(
				Manifest(
					tenantId = tenant.id,
					version = 1,
					manifest = seedManifestJson(),
					createdByMemberId = owner.id,
					isActive = true,
					createdAt = now,
					activatedAt = now,
				),
			)
		}

		val codeHash = Sha256.hex(SEED_INVITE_CODE)
		if (invitations.findByCodeHash(codeHash) == null) {
			invitations.save(
				Invitation(
					tenantId = tenant.id,
					targetMemberId = owner.id,
					createdByMemberId = owner.id,
					codeHash = codeHash,
					expiresAt = now.plus(SEED_INVITE_TTL_DAYS, ChronoUnit.DAYS),
					createdAt = now,
				),
			)
		}

		log.info(
			"local 시드 준비 완료 — tenant_id={} created_by_member_id={} (email={}) team_id={}",
			tenant.id,
			owner.id,
			owner.email,
			team.id,
		)
		log.info(
			"local 전용 초대 코드: {} — `pulsemetry enroll --invite {} --server http://localhost:8080`",
			SEED_INVITE_CODE,
			SEED_INVITE_CODE,
		)
	}

	/**
	 * host 는 정확히 `localhost` 여야 한다 — `http://127.0.0.1` 은 서버도 클라이언트도 거부한다.
	 * 포트는 [seedOtlpEndpoint] 설정값에서 온다.
	 */
	private fun seedManifestJson(): String = """
		{
		  "schema_version": 1,
		  "config_revision": 1,
		  "otlp": {
		    "endpoint": "$seedOtlpEndpoint",
		    "protocol": "http/protobuf",
		    "compression": "gzip",
		    "timeout_ms": 10000
		  },
		  "signals": { "logs": true, "metrics": true, "traces": true },
		  "privacy": {
		    "collect_user_prompts": false,
		    "collect_assistant_responses": false,
		    "collect_tool_details": false,
		    "collect_tool_content": false,
		    "collect_user_email": false,
		    "collect_raw_api_bodies": false
		  }
		}
	""".trimIndent()

	private companion object {
		const val SEED_TENANT_NAME = "로컬 개발 조직"
		const val SEED_OWNER_EMAIL = "local-owner@example.com"
		const val SEED_TEAM_NAME = "로컬 개발 팀"

		/**
		 * 고정 초대 코드. **`local` 프로파일 전용이다** — 가짜 tenant 하나뿐인 로컬 DB 에서만
		 * 의미가 있고, 공유 DB 를 향해 이 프로파일을 켜지 말라는 경고가 명세 9절에 있다.
		 * 값을 고정하는 이유는 재현성이다. 매번 새로 만들면 기동 로그를 놓쳤을 때 되찾을 방법이 없다.
		 */
		const val SEED_INVITE_CODE = "E2E0-0000-0001"
		const val SEED_INVITE_TTL_DAYS = 365L
	}
}
