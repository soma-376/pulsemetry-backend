package com.team376.pulsemetry.connector.vendor

/** 벤더 계정의 식별 방식 (ADR 0048 §1). 원장의 계정 키는 이 방식으로 정규화한 문자열이다. 지금 커넥터는 모두 이메일이다(ADR 0054). */
enum class AccountKind(val wire: String) {
	EMAIL("email");

	/** 계정 키 정규화. 형식이 맞지 않으면 null. */
	fun normalize(raw: String): String? {
		val value = raw.trim().lowercase()
		return value.takeIf { value.length <= 320 && EMAIL_FORMAT.matches(value) }
	}

	private companion object {
		val EMAIL_FORMAT = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
	}
}

enum class Capability(val wire: String) {
	SEAT_LIST("seat_list"),
	SEAT_RELEASE("seat_release"),
	SEAT_RESTORE("seat_restore"),
	BILLING("billing"),
}

/**
 * 커넥터 설명 — 호출 없이 읽는 값 (ADR 0048 §8). 어느 등록 제품·계약 플랜에 쓰는지, 계정 종류, 연결에 필요한 비밀 아닌 설정,
 * 벤더가 지원하는 기능([supported] — 벤더 문서 근거)과 이 저장소가 구현한 기능([capabilities] ⊆ supported).
 * 권위 판정(커넥터가 있는 플랜인가)과 조회 앱의 capability 표시가 이것만 본다. 구현이 늘면 [capabilities] 를 넓힌다.
 */
data class ConnectorDescriptor(
	val id: String,
	/** 카탈로그 제품 ID(`managed_vendors.kind`). */
	val product: String,
	/** 카탈로그 플랜 ID. */
	val plans: Set<String>,
	val accountKind: AccountKind,
	/** 연결에 필요한 비밀 아닌 설정의 키. 설정은 정확히 이 키들이어야 한다. */
	val settingKeys: List<String>,
	/** 벤더 문서가 근거를 준 기능. */
	val supported: Set<Capability>,
	/** 이 저장소가 구현한 기능. 구현이 이것과 다르면 조립이 실패한다. */
	val capabilities: Set<Capability>,
	/** 벤더 내부 ID([VendorAccount.vendorAccountRef])가 있어야 부를 수 있는 기능. 그 ID 가 없는 좌석(연결 전 수동 기록)에는 이 기능을 쓰지 못한다. */
	val accountRefRequired: Set<Capability> = emptySet(),
) {
	init {
		require(Capability.SEAT_LIST in capabilities) { "좌석 목록은 모든 커넥터가 한다" }
		require(supported.containsAll(capabilities)) { "벤더 문서에 근거가 없는 기능을 구현하지 않는다" }
		require(capabilities.containsAll(accountRefRequired)) { "구현하지 않은 기능의 호출 조건을 두지 않는다" }
		require(plans.isNotEmpty()) { "플랜이 없다" }
	}
}

/**
 * 벤더 문서가 근거를 준 커넥터들 (`docs/vendor-connector-evidence.md` "결론 — 구현 방식"). 문서가 `수동`·`미제공`으로 판정한 제품·플랜과
 * 연동 대상이 아닌 제품(GitHub Copilot·Gemini Code Assist — ADR 0054)은 없다 — 그 제품은 커넥터가 없는 플랜이다(ADR 0048 §3의 1행).
 */
object ConnectorDescriptors {
	private val LIST_RELEASE = setOf(Capability.SEAT_LIST, Capability.SEAT_RELEASE)

	/**
	 * Claude Enterprise: 구성원 조회·제거·재초대(수락 대기), 사용량 기반 계약의 사용 비용. 등급·활동 시각은 주지 않는다.
	 * 제거는 구성원 ID 로 부른다. 복원(재초대)은 구현하지 않는다 — 초대에 역할을 정해야 하는데 원장은 옛 역할을 모른다(ADR 0049). 청구는 비용 보고서(ADR 0050).
	 */
	val CLAUDE_ENTERPRISE = ConnectorDescriptor("claude_enterprise", "claude_team", setOf("enterprise"), AccountKind.EMAIL, emptyList(),
		setOf(Capability.SEAT_LIST, Capability.SEAT_RELEASE, Capability.SEAT_RESTORE, Capability.BILLING), LIST_RELEASE + Capability.BILLING,
		accountRefRequired = setOf(Capability.SEAT_RELEASE))

	/** Cursor Enterprise: 구성원 조회·제거, 현재 주기의 사용 지출(ADR 0050). 복원 API 는 없다. */
	val CURSOR_ENTERPRISE = ConnectorDescriptor("cursor_enterprise", "cursor", setOf("cursor_enterprise"), AccountKind.EMAIL, emptyList(),
		setOf(Capability.SEAT_LIST, Capability.SEAT_RELEASE, Capability.BILLING), LIST_RELEASE + Capability.BILLING)

	val ALL: List<ConnectorDescriptor> = listOf(CLAUDE_ENTERPRISE, CURSOR_ENTERPRISE)

	init {
		require(ALL.map { it.id }.toSet().size == ALL.size) { "커넥터 ID 가 겹친다" }
		require(ALL.flatMap { d -> d.plans.map { d.product to it } }.let { it.toSet().size == it.size }) { "한 플랜에 커넥터가 둘이다" }
		require(ALL.groupBy { it.product }.values.all { group -> group.map { it.accountKind }.toSet().size == 1 }) { "한 제품의 계정 종류가 갈린다" }
	}

	fun byId(id: String): ConnectorDescriptor? = ALL.find { it.id == id }

	/** 등록 제품·계약 플랜의 커넥터 설명. 없으면 그 플랜은 커넥터가 없다. */
	fun forPlan(product: String, plan: String?): ConnectorDescriptor? = plan?.let { ALL.find { d -> d.product == product && it in d.plans } }

	/** 제품의 계정 종류. 커넥터가 없는 제품은 이메일이다. */
	fun accountKind(product: String): AccountKind = ALL.firstOrNull { it.product == product }?.accountKind ?: AccountKind.EMAIL
}

/**
 * 앱이 조립한 커넥터 구현들. 설명의 capability 와 구현이 다르거나, 한 플랜에 구현이 둘이면 조립을 거부한다.
 * 설명이 있어도 구현이 없으면 그 플랜에는 연결을 만들 수 없다.
 */
class SeatConnectors(connectors: List<SeatConnector>) {
	private val connectors = connectors.toList()

	init {
		for (connector in this.connectors) {
			require(connector.implemented() == connector.descriptor.capabilities) {
				"커넥터 ${connector.descriptor.id}의 구현(${connector.implemented()})이 설명(${connector.descriptor.capabilities})과 다르다"
			}
		}
		require(this.connectors.map { it.descriptor.id }.toSet().size == this.connectors.size) { "커넥터 ID 가 겹친다" }
		val plans = this.connectors.flatMap { c -> c.descriptor.plans.map { c.descriptor.product to it } }
		require(plans.toSet().size == plans.size) { "한 플랜에 커넥터 구현이 둘이다" }
	}

	fun byId(id: String): SeatConnector? = connectors.find { it.descriptor.id == id }

	fun forPlan(product: String, plan: String?): SeatConnector? =
		plan?.let { connectors.find { c -> c.descriptor.product == product && it in c.descriptor.plans } }

	companion object {
		val NONE = SeatConnectors(emptyList())
	}
}
