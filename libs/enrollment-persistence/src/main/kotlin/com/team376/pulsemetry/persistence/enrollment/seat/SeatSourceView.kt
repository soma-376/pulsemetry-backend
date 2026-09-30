package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors

/**
 * 등록 제품의 좌석 원천 — 설정 조회(dashboard-api)와 연결 명령(enrollment-api)의 응답이 같은 모양으로 낸다 (ADR 0048 §3·§6).
 * 자격증명은 설정됨 여부와 갱신 시각만 싣는다.
 *
 * @property authority 좌석의 권위 원천(`connector`·`manual`).
 * @property provisional 커넥터가 있는 플랜인데 연결이 없어 수동 기록이 임시로 권위를 갖는가.
 * @property connector 현재 계약 플랜의 커넥터 설명. 없으면 그 플랜은 수동 원천이다. `capabilities` 는 이 저장소가 구현한 기능, `supported` 는 벤더 문서가 근거를 준 기능이다.
 * @property connection 활성 연결. 없으면 null.
 */
data class SeatSourceView(val authority: String, val provisional: Boolean, val connector: Connector?, val connection: Connection?) {
	data class Connector(val connectorId: String, val accountKind: String, val capabilities: List<String>, val settingKeys: List<String>, val supported: List<String>)

	data class Connection(
		val connectionId: String,
		val version: Long,
		val connectorId: String,
		val settings: Map<String, String>,
		val credential: Credential,
		val check: Check,
		val sync: Sync,
		val createdAt: String,
		val updatedAt: String,
	)

	data class Credential(val configured: Boolean, val updatedAt: String)
	data class Check(val status: String, val checkedAt: String?)
	data class Sync(val status: String, val lastSucceededAt: String?, val lastFailedAt: String?, val lastError: String?)

	companion object {
		fun of(product: String, plan: String?, connection: ConnectionRecord?): SeatSourceView {
			val descriptor = ConnectorDescriptors.forPlan(product, plan)
			val authority = SeatAuthority.of(connectorPlan = descriptor != null, activeConnection = connection != null)
			return SeatSourceView(
				authority = authority.authority.wire,
				provisional = authority.provisional,
				connector = descriptor?.let { d ->
					Connector(d.id, d.accountKind.wire, d.capabilities.sortedBy { it.ordinal }.map { it.wire }, d.settingKeys, d.supported.sortedBy { it.ordinal }.map { it.wire })
				},
				connection = connection?.let {
					Connection(it.id.toString(), it.version, it.connector, it.settings.toSortedMap(), Credential(true, it.credentialUpdatedAt.toString()),
						Check(it.check.wire, it.checkedAt?.toString()),
						Sync(it.syncStatus.wire, it.lastSyncSucceededAt?.toString(), it.lastSyncFailedAt?.toString(), it.lastSyncError),
						it.createdAt.toString(), it.updatedAt.toString())
				},
			)
		}
	}
}
