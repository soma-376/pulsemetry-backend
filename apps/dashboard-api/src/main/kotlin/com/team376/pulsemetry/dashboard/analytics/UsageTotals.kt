package com.team376.pulsemetry.dashboard.analytics

import java.math.BigDecimal
import java.time.Instant

/**
 * 한 그룹(조직·팀·모델·사용자·일자 중 하나의 축 값)의 사용량 카운터. snapshot payload 에서 원래 식별자로 다시 센 값이다 —
 * 하위 그룹의 distinct 를 더하지 않는다.
 *
 * 화면 값은 **null 규칙**을 거쳐서만 나온다.
 *
 * - 그룹에 사용량 행이 없으면 모든 값이 없다(null) — 0 은 완전한 관측으로 입증된 빈 사용량에만 쓰는데 v1 에는 그 근거가 없다.
 * - `activeUsers` — 식별된 구성원의 고유 수. 미식별 행이 섞여도 식별된 수는 버리지 않는다. 식별된 사람이 없으면 없다.
 * - `sessionCount` — 세션 없는 행이 하나라도 있으면 없다.
 * - 토큰 범주 — 그 범주의 누락이 하나라도 있으면 없다. 모든 행이 같은 non-null 의미 호환 그룹이어야 한다.
 *   검증된 Claude/Codex canonical 성분만 한 그룹이며 나머지는 원래 프로파일별로 분리한다(ADR 0061).
 * - API `total` — 파생 total 과 네 범주 중 하나라도 누락이 있으면 없다(같은 프로파일 조건도 같다).
 * - 금액 — 단가 없는 행이 하나라도 있으면 없다. 저장된 USD 환산액을 합산한다. 부분합을 총액으로 올리지 않는다.
 */
data class UsageTotals(
	val usageRows: Long,
	val identifiedUsers: Long,
	val unidentifiedRows: Long,
	val identifiedSessions: Long,
	val sessionlessRows: Long,
	val inputUncached: Component,
	val output: Component,
	val cacheRead: Component,
	val cacheWrite: Component,
	val totalDerived: Component,
	val apiTotalMissingRows: Long,
	val unverifiedRows: Long,
	val semanticsProfiles: Long,
	val cost: BigDecimal?,
	val unpricedRows: Long,
	val multiTeamRows: Long,
	/** 그룹 안 사용량 행의 마지막 source_time. 기간·축으로 이미 좁힌 범위의 값이다. */
	val lastSourceTime: Instant? = null,
) {
	data class Component(val sum: Long?, val missingRows: Long)

	val hasUsage: Boolean get() = usageRows > 0

	/** 그룹 전체가 한 의미 호환 그룹 안에 있다(ADR 0061). 미검증 행은 여전히 합산하지 않는다. */
	val semanticsUniform: Boolean get() = unverifiedRows == 0L && semanticsProfiles == 1L

	fun activeUsers(): Long? = identifiedUsers.takeIf { hasUsage && it > 0 }

	fun sessionCount(): Long? = identifiedSessions.takeIf { hasUsage && sessionlessRows == 0L }

	fun tokens(component: Component): Long? = component.sum.takeIf { hasUsage && semanticsUniform && component.missingRows == 0L }

	fun apiTotal(): Long? = totalDerived.sum.takeIf { hasUsage && semanticsUniform && apiTotalMissingRows == 0L }

	fun equivalentCost(): BigDecimal? = cost.takeIf { hasUsage && unpricedRows == 0L }

	/** 전체 금액과 구분하는 계산 가능한 관측 부분합(ADR 0063). */
	fun knownEquivalentCost(): BigDecimal? = cost.takeIf { hasUsage && usageRows > unpricedRows }

	/** 캐시 적중 분모 = inputUncached + cacheRead + cacheWrite. 셋 중 하나라도 없으면 없다. */
	fun cacheEligibleInput(): Long? {
		val parts = listOf(tokens(inputUncached), tokens(cacheRead), tokens(cacheWrite))
		return if (parts.any { it == null }) null else parts.sumOf { it!! }
	}

	/** cacheRead / 분모. 분모가 없거나 0 이면 없다. */
	fun cacheHitRatio(): Double? {
		val read = tokens(cacheRead) ?: return null
		val eligible = cacheEligibleInput()?.takeIf { it > 0 } ?: return null
		return read.toDouble() / eligible
	}

	companion object {
		/** 사용량 행이 없는 그룹. */
		val EMPTY = UsageTotals(
			0, 0, 0, 0, 0,
			Component(null, 0), Component(null, 0), Component(null, 0), Component(null, 0), Component(null, 0),
			0, 0, 0, null, 0, 0,
		)
	}
}
