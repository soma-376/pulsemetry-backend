package com.team376.pulsemetry.connector.vendor

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 벤더 좌석 커넥터의 포트 (ADR 0048 §8). 구현은 벤더별이고 벤더 문서(`docs/vendor-connector-evidence.md`)에 근거가 있는 기능만 갖는다.
 *
 * - 좌석 목록은 모든 커넥터가 한다. 해제·복원·청구는 **지원할 때만** non-null 이다 — 지원하지 않는 기능을 런타임 예외로 알리지 않는다.
 *   [descriptor] 의 capability 와 구현이 다르면 [SeatConnectors] 가 조립을 거부한다.
 * - 실패는 [ConnectorFailure] 하나로 던지고 종류로 나눈다. 메시지·원인에 자격증명을 싣지 않는다.
 */
interface SeatConnector {
	val descriptor: ConnectorDescriptor

	/** 자격증명과 설정이 유효한지 읽기 호출 하나로 확인한다. 실패는 [ConnectorFailure]. */
	fun verify(target: ConnectionTarget)

	/** 좌석 **전체** 목록. 모든 페이지를 읽었을 때만 돌려준다 — 부분 목록을 돌려주지 않는다(원장이 목록에 없는 좌석을 해제한다). */
	fun listSeats(target: ConnectionTarget): List<VendorSeat>

	val release: SeatRelease? get() = null
	val restore: SeatRestore? get() = null
	val billing: BillingReader? get() = null

	/** 구현이 실제로 갖는 기능. */
	fun implemented(): Set<Capability> = buildSet {
		add(Capability.SEAT_LIST)
		if (release != null) add(Capability.SEAT_RELEASE)
		if (restore != null) add(Capability.SEAT_RESTORE)
		if (billing != null) add(Capability.BILLING)
	}
}

/** 좌석 해제(회수). 요청 성공은 완료가 아니다 — 결과는 [ControlResult] 의 상태로 낸다. */
fun interface SeatRelease {
	fun release(target: ConnectionTarget, account: VendorAccount): ControlResult
}

/** 좌석 복원(재배정·재초대). */
fun interface SeatRestore {
	fun restore(target: ConnectionTarget, account: VendorAccount): ControlResult
}

/**
 * 벤더가 주는 지금 정산 기간의 청구 누계 (ADR 0050). 좌석 구독료가 아니다 — 사용 비용·사용 지출만 있다(벤더 문서).
 * 기간은 벤더가 정하거나(청구 주기) 벤더가 기간을 고르게 해 주면 [monthStart](조직 달력의 이번 달 시작)부터다. 끝은 [now] 이전이다.
 */
fun interface BillingReader {
	fun currentPeriod(target: ConnectionTarget, now: Instant, monthStart: Instant): BilledAmount
}

/** 연결 하나로 부르는 대상 — 비밀이 아닌 설정과 자격증명. */
class ConnectionTarget(val settings: Map<String, String>, val credential: ConnectorCredential) {
	override fun toString(): String = "ConnectionTarget(settings=$settings, credential=$credential)"
}

/** 자격증명. 자동 toString·equals 로 값이 새지 않게 data class 가 아니다. */
class ConnectorCredential(private val value: String) {
	init {
		require(value.isNotBlank()) { "자격증명이 비어 있다" }
	}

	/** 벤더 호출 직전에만 꺼낸다. */
	fun reveal(): String = value

	override fun toString(): String = "ConnectorCredential(****)"
}

/** 벤더가 보고한 보유 상태. 해제된 좌석은 목록에 나오지 않는다. */
enum class VendorSeatState { ASSIGNED, PENDING_ASSIGNMENT, PENDING_RELEASE }

/**
 * 벤더 목록의 좌석 하나.
 *
 * @property account 벤더 계정 키 — 설명의 [AccountKind] 로 정규화하기 전의 값이어도 된다(원장이 정규화한다).
 * @property vendorAccountRef 벤더 내부 ID(제어 호출에 쓴다). 계정 키가 곧 호출 식별자면 null.
 * @property email 벤더가 알려 준 이메일. 없으면 null.
 * @property releaseEffectiveOn [VendorSeatState.PENDING_RELEASE] 의 예정일.
 * @property tier 벤더가 준 등급. 주지 않으면 null — 추정하지 않는다.
 * @property assignedAt 벤더가 준 배정 시각. 없으면 null.
 * @property lastActivityAt 벤더가 준 마지막 활동. **null 은 모름이지 미사용이 아니다.**
 */
data class VendorSeat(
	val account: String,
	val vendorAccountRef: String? = null,
	val email: String? = null,
	val state: VendorSeatState = VendorSeatState.ASSIGNED,
	val releaseEffectiveOn: LocalDate? = null,
	val tier: String? = null,
	val assignedAt: Instant? = null,
	val lastActivityAt: Instant? = null,
) {
	init {
		require(account.isNotBlank()) { "벤더 계정 키가 비어 있다" }
		require(releaseEffectiveOn == null || state == VendorSeatState.PENDING_RELEASE) { "해제 예정일은 해제 예정 좌석에만 있다" }
	}
}

/** 제어 호출의 대상. */
data class VendorAccount(val account: String, val vendorAccountRef: String?)

/**
 * 제어 호출의 결과. [ControlStatus.SCHEDULED] 는 [effectiveOn] 에 효력이 생긴다 — 벤더가 응답에 날짜를 주지 않으면 null 이고 다음 동기화가 목록의 예정일로 채운다.
 */
data class ControlResult(val status: ControlStatus, val effectiveOn: LocalDate? = null) {
	init {
		require(effectiveOn == null || status == ControlStatus.SCHEDULED) { "효력일은 예정 결과에만 있다" }
	}
}

/** 벤더가 요청을 받아들인 뒤의 상태. [SCHEDULED] 는 해제 예정(주기 말 효력), [AWAITING_ACCEPTANCE] 는 초대 수락 대기다 — 둘 다 끝난 것이 아니다. */
enum class ControlStatus { COMPLETED, SCHEDULED, AWAITING_ACCEPTANCE }

/** 청구 금액 한 구간 [from, to). [finalized] 가 false 면 벤더가 뒤에 고칠 수 있는 값이다. 통화는 USD 만 받는다(ADR 0050 — 환율 원천이 없다). */
data class BilledAmount(val from: Instant, val to: Instant, val amount: BigDecimal, val currency: String, val kind: BilledKind, val finalized: Boolean) {
	init {
		require(from < to) { "청구 구간은 비어 있지 않다" }
		require(currency == "USD") { "USD 가 아닌 청구액은 받지 않는다" }
	}
}

enum class BilledKind(val wire: String) { USAGE_COST("usage_cost"), USAGE_SPEND("usage_spend") }

/**
 * 커넥터 호출의 실패. [Kind.permanent] 가 true 면 같은 입력으로 다시 불러도 같은 결과다(자격증명·권한·벤더 규칙).
 * [retryAfter] 는 벤더가 알려 준 대기 시간이다.
 */
class ConnectorFailure(val kind: Kind, val retryAfter: Duration? = null) : RuntimeException(kind.wire) {
	enum class Kind(val wire: String, val permanent: Boolean) {
		INVALID_CREDENTIALS("invalid_credentials", true),
		INSUFFICIENT_PERMISSION("insufficient_permission", true),
		/** IdP(SCIM·JIT)가 구성원을 관리해 벤더가 쓰기를 거절했다. */
		DIRECTORY_MANAGED("directory_managed", true),
		/** 벤더 규칙이 거절했다(관리자 제거 불가, 남은 좌석 없음 등). */
		VENDOR_REJECTED("vendor_rejected", true),
		RATE_LIMITED("rate_limited", false),
		UNAVAILABLE("vendor_unavailable", false),
		INVALID_RESPONSE("invalid_response", false),
	}
}
