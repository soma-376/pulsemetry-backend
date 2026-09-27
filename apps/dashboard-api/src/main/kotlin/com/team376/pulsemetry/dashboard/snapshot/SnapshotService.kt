package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.PageCursor
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * snapshot 의 수명 (ADR 0023 §4). **완결된 것만 공개하고, 정해진 유효기간·권한 안에서만 읽게 한다.**
 *
 * - [create] — build 뒤 공개 CAS. 실패하거나 CAS 가 거부되면 공개하지 않는다(503). 요청 안에서 동기로 한다 — 생성 중 상태의
 *   HTTP 표현은 프론트와 합의 전이다.
 * - [requireReady] — 매 요청 manifest 를 본다. 없음·다른 tenant·ready 아님·무효화·만료·삭제 정책 epoch 변경은 모두 `409 snapshot_expired`.
 *   물리 행이 남아 있어도 만료는 만료다. snapshot ID 는 권한 증명이 아니다 — 조직·행위 권한은 호출자가 이것보다 먼저 검사한다
 *   (`OrganizationAccess`).
 * - [obtain] — 요청이 snapshot ID 를 실었으면 그것을, 아니면 새로 만든다. 실은 snapshot 이 요청의 범위(기간·시간대·비교 방식)와 다르면
 *   409 다 — 날짜·필터가 바뀌면 새 조회다.
 *
 * manifest 는 RDS 에 있으므로 앱 재시작·다른 인스턴스가 같은 snapshot 을 읽는다. 프로세스 메모리를 진실원으로 삼지 않는다.
 */
class SnapshotService(
	private val builder: SnapshotBuilder,
	private val manifests: SnapshotManifestStore,
	private val boundaries: RetentionBoundaryReader,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(SnapshotService::class.java)

	fun create(tenantId: UUID, requestedBy: UUID, period: ComparedPeriod): SnapshotManifestStore.Manifest {
		val built = builder.build(SnapshotBuilder.Request(tenantId, requestedBy, period))
		val now = clock.instant()
		if (!manifests.publish(built.buildId, built.usageRows, built.observedDayRows, now, now + SnapshotBuilder.API_LIFETIME)) {
			manifests.markFailed(built.buildId, PUBLISH_REJECTED, now)
			log.warn("snapshot build {} 공개 CAS 거부 — 무효화·마감·삭제 정책 epoch 변경", built.buildId)
			throw SnapshotUnavailableException(PUBLISH_REJECTED)
		}
		return manifests.find(built.snapshotId) ?: error("공개한 snapshot ${built.snapshotId} 을 읽지 못했다")
	}

	fun requireReady(snapshotId: String, tenantId: UUID): SnapshotManifestStore.Manifest {
		if (!SnapshotIds.isWellFormed(snapshotId)) throw expired()
		val manifest = manifests.find(snapshotId) ?: throw expired()
		val now = clock.instant()
		val valid = manifest.tenantId == tenantId &&
			manifest.status == READY &&
			manifest.invalidatedAt == null &&
			manifest.expiresAt != null && now.isBefore(manifest.expiresAt) &&
			manifest.queryContract == SnapshotManifestStore.QUERY_CONTRACT &&
			manifest.policyEpoch == boundaries.read(tenantId).policyEpoch
		if (!valid) throw expired()
		return manifest
	}

	/**
	 * [comparedPeriod] 는 요청이 해석한 범위다. [usesComparison] 이 거짓인 endpoint(비교가 없는 화면)는 snapshot 의 비교 방식을 따지지 않는다.
	 */
	fun obtain(
		tenantId: UUID,
		requestedBy: UUID,
		comparedPeriod: ComparedPeriod,
		usesComparison: Boolean,
		snapshotId: String?,
	): SnapshotManifestStore.Manifest {
		if (snapshotId == null) return create(tenantId, requestedBy, comparedPeriod)
		val manifest = requireReady(snapshotId, tenantId)
		val sameScope = manifest.current == comparedPeriod.current && (!usesComparison || manifest.compareMode == comparedPeriod.mode)
		if (!sameScope) throw expired()
		return manifest
	}

	/** cursor 가 이 snapshot·이 목록 범위에서 만든 것이 아니면 400 `invalid_cursor` 다. */
	fun requireCursor(cursor: PageCursor, snapshotId: String, scope: String) {
		if (cursor.snapshotId != snapshotId || cursor.scope != scope) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
	}

	/** 권한·정책이 바뀌어 tenant 의 기존 snapshot 을 보여 줄 수 없을 때 부른다. 이벤트 원천이 아직 없어 호출자가 직접 부른다. */
	fun invalidateTenant(tenantId: UUID, reason: String): Int = manifests.invalidateTenant(tenantId, reason, clock.instant())

	private fun expired() = DashboardException(ErrorCode.SNAPSHOT_EXPIRED)

	companion object {
		const val PUBLISH_REJECTED = "publish_rejected"
		private const val READY = "ready"
		private const val CURSOR = "cursor"
	}
}
