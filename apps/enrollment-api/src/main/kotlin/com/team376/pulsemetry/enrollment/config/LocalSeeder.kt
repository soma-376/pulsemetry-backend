package com.team376.pulsemetry.enrollment.config

import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.entity.Tenant
import com.team376.pulsemetry.persistence.enrollment.repository.ManifestRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TenantRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 로컬 개발용 tenant · owner member · 활성 manifest v1 을 넣는다.
 *
 * Flyway 마이그레이션에는 시드 데이터를 넣지 않는다(운영 DB 에도 그대로 들어가기 때문). 그래서
 * 이 시더가 그 몫을 대신한다 — 이게 없으면 갓 띄운 로컬 서버의 첫 enroll 이
 * 409 `manifest_not_configured` 로 실패한다.
 *
 * **`local` 프로파일에서만 뜬다.** 테스트 컨텍스트에 딸려 들어가면 행 수를 세는 단언들이 깨진다.
 */
@Component
@Profile("local")
class LocalSeeder(
	private val tenants: TenantRepository,
	private val members: MemberRepository,
	private val manifests: ManifestRepository,
	private val clock: Clock,
	/**
	 * 시드 manifest 의 `otlp.endpoint`. 파이프라인 compose 와 함께 쓸 때는 같은 값을 주입해
	 * 두 시드(backend · pipeline)가 한 곳에서 정의된 값을 공유한다.
	 *
	 * 기본값이 `:4316`(auth-proxy 경유 정상 경로)인 이유 — 계약이 허용하는 http 는
	 * host 가 정확히 `localhost` 인 것 전부이므로 `:4316` 도 허용된다.
	 * (이전의 `:4318` 고정은 ":4318 만 계약이 허용한다" 는 잘못된 근거 위에 있었고,
	 * `:4318` 은 collector 직결 포트라 auth-proxy 인증·신원 귀속을 건너뛰는 데다
	 * telemetryctl 로컬 수신기 기본 포트와 겹쳐 자기참조가 된다 — 기본값으로 부적절하다.)
	 */
	@Value("\${pulsemetry.local-seed.otlp-endpoint:http://localhost:4316}")
	private val seedOtlpEndpoint: String,
) : ApplicationRunner {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * 멱등하다 — 이미 tenant 가 있으면 아무것도 하지 않는다.
	 *
	 * 재기동할 때마다 tenant 가 하나씩 늘면 어느 UUID 를 초대 요청에 넣어야 할지 알 수 없게 된다.
	 */
	@Transactional
	override fun run(args: ApplicationArguments) {
		val existing = tenants.findAll().firstOrNull()
		if (existing != null) {
			log.info("local 시드를 건너뛴다 — 이미 tenant 가 있다 (tenant_id={})", existing.id)
			return
		}

		val now = clock.instant()
		val tenant = tenants.save(
			Tenant(name = SEED_TENANT_NAME, createdAt = now, updatedAt = now),
		)
		val owner = members.save(
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

		// 관리자가 POST /v1/invitations 본문에 그대로 넣어야 하는 값이라 로그로 찍는다.
		log.info(
			"local 시드를 넣었다 — tenant_id={} created_by_member_id={} (email={})",
			tenant.id,
			owner.id,
			owner.email,
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
		  "signals": { "logs": false, "metrics": true, "traces": true },
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
	}
}
