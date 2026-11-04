package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore

/** 버킷의 배치와 값의 의미. asOf는 수신 이력 복원 시각이 아닌 발생 시각의 배타적 상한이다. */
data class TrendAggregation(
	val mode: String,
	val intervalSeconds: Long,
	val valueMode: String,
	val rangeStart: String,
	val rangeEnd: String,
	val asOf: String,
)

/** PROJ-315 인계용 공통 계약. 사용량 Value의 지표 결손과 수집 상태를 분리한다. */
data class CollectionPolicyAnnotations(
	val policyVersion: String,
	val asOf: String,
	val status: String,
	val annotations: List<CollectionAnnotation>,
) {
	companion object {
		/**
		 * 아직 PROJ-315 판정 근거가 없으므로 완전/불완전을 추론하지 않는다.
		 * 향후 어댑터도 현재 설치 상태가 아닌 manifest에 고정한 정책·근거만 투영해야 한다.
		 * PROJ-316 손실 payload 수를 사용량 시각 버킷에 배분하지 않는다.
		 */
		fun pending(snapshot: SnapshotManifestStore.Manifest) = CollectionPolicyAnnotations(
			policyVersion = "proj-315-pending",
			asOf = snapshot.asOf.toString(),
			status = "pending",
			annotations = listOf(CollectionAnnotation(
				scope = "period",
				rangeStart = snapshot.current.from.toString(),
				rangeEnd = snapshot.current.until.toString(),
				teamId = null,
				reasons = listOf("policy_integration_pending"),
				evidenceRefs = emptyList(),
			)),
		)
	}
}

data class CollectionAnnotation(
	val scope: String,
	val rangeStart: String,
	val rangeEnd: String,
	val teamId: String?,
	val reasons: List<String>,
	val evidenceRefs: List<String>,
)
