package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.snapshot.Completeness
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 모든 분석 응답이 공유하는 틀 — snapshot 하나, 그 snapshot 의 관측 일자, 수신 이력, 그리고 그것으로 정한 **상태**(ADR 0023 §4).
 * 여러 endpoint 가 같은 규칙으로 `dataState`·coverage·비교 공개 여부를 정하도록 한 곳에서 만든다.
 *
 * - `dataState`: 현재 기간의 모든 날짜가 완전 관측이면 `ready`, 관측이 있거나 완전한 날짜가 있으면 `partial`, 없고 수신 이력이 있으면
 *   `no_data`, 둘 다 없으면 `never_observed`. 완전한 날짜는 snapshot 에 고정한 판정이다(ADR 0042). 수신 이력을 판정할 수 없으면 503 이다([IngestStatusReader.history]).
 * - 비교는 [ComparisonPolicy] 가 공개를 허락할 때만 계산한다 — 기본 정책은 두 기간 모두 완전 관측.
 * - `dataThrough` 는 현재 기간의 첫날부터 끊김 없이 이어진 완전한 날짜의 끝이다(없으면 null).
 * - 수집 운영 현황(`ingest`)은 설치 보고를 근거로 판정한다(ADR 0041). snapshot 밖의 현재 상태다.
 */
class AnalyticsFrames(
	private val snapshots: SnapshotService,
	private val references: SnapshotReferences,
	private val ingest: IngestStatusReader,
	private val comparison: ComparisonPolicy,
	private val thresholds: IngestThresholds,
	private val clock: Clock,
) {

	data class Frame(
		val organization: Organization,
		val period: ComparedPeriod,
		val snapshot: SnapshotManifestStore.Manifest,
		val history: IngestStatusReader.History,
		val observedDates: Set<LocalDate>,
		/** 완전 관측으로 판정해 snapshot 에 고정한 날짜(ADR 0042). */
		val completeDates: Set<LocalDate>,
		val currentCoverage: Coverage,
		val previousCoverage: Coverage?,
		val dataState: String,
		val comparable: Boolean,
		val now: Instant,
		val incompleteReasons: Map<LocalDate, List<String>> = emptyMap(),
	) {
		/** no_data·never_observed — 사용량 값을 내지 않는다(상태 우선). */
		val empty: Boolean get() = dataState != PARTIAL && dataState != READY

		/** 현재 기간 전체가 완전 관측이다 — 사용이 없으면 실제 0 이다. */
		val currentComplete: Boolean get() = currentCoverage.status == Coverage.COMPLETE

		/** 비교 기간 전체가 완전 관측이다. */
		val previousComplete: Boolean get() = previousCoverage?.status == Coverage.COMPLETE

		fun periodReasons(previous: Boolean = false): List<String> {
			val dates = (if (previous) period.previous else period.current)?.dates() ?: return emptyList()
			if (dates.all { it in completeDates }) return emptyList()
			val prefix = if (previous) "previous_" else ""
			return (listOf(if (previous) "previous_period_incomplete" else "current_period_incomplete") +
				dates.filter { it !in completeDates }.flatMap { incompleteReasons[it].orEmpty().map { reason -> prefix + reason } }).distinct()
		}

		fun dateReasons(date: LocalDate): List<String> = if (date in completeDates) emptyList()
			else incompleteReasons[date] ?: listOf("current_period_incomplete")

		/** 그날의 관측 상태 — `complete`(완전, 사용이 없으면 0) · `partial`(관측은 있으나 완전하다는 근거가 없다) · `unobserved`. */
		fun observation(date: LocalDate): String = when (date) {
			in completeDates -> Coverage.COMPLETE
			in observedDates -> Coverage.PARTIAL
			else -> UNOBSERVED
		}
	}

	/**
	 * [snapshotId] 가 있으면 그 snapshot 을 재사용하고(범위가 다르거나 만료면 409) 없으면 새로 만든다. [usesComparison] 이 거짓인 화면은
	 * snapshot 의 비교 방식을 따지지 않고, 비교도 내지 않는다.
	 */
	fun frame(
		organization: Organization,
		requestedBy: UUID,
		period: ComparedPeriod,
		usesComparison: Boolean,
		snapshotId: String?,
	): Frame {
		val history = ingest.history(organization.id)
		val snapshot = snapshots.obtain(organization.id, requestedBy, period, usesComparison, snapshotId)
		val observed = references.observedDates(snapshot)
		val complete = references.completeDates(snapshot)
		val currentCoverage = Coverage.of(period.current.dates(), observed, complete)
		val previousCoverage = if (usesComparison) period.previous?.let { p -> Coverage.of(p.dates(), observed, complete) } else null
		val dataState = when {
			currentCoverage.status == Coverage.COMPLETE -> READY
			currentCoverage.observedDays > 0 -> PARTIAL
			history.hasReceipts -> NO_DATA
			else -> NEVER_OBSERVED
		}
		return Frame(
			organization = organization,
			period = period,
			snapshot = snapshot,
			history = history,
			observedDates = observed,
			completeDates = complete,
			currentCoverage = currentCoverage,
			previousCoverage = previousCoverage,
			dataState = dataState,
			comparable = previousCoverage != null && comparison.comparable(currentCoverage, previousCoverage),
			now = clock.instant(),
			incompleteReasons = references.incompleteReasons(snapshot),
		)
	}

	fun meta(frame: Frame): AnalyticsPeriodMeta = AnalyticsPeriodMeta(
		organizationId = frame.organization.id.toString(),
		generatedAt = frame.now.toString(),
		dataThrough = Completeness.dataThrough(frame.period.current, frame.completeDates)?.toString(),
		currency = USD,
		startDate = frame.period.current.startDate.toString(),
		endDate = frame.period.current.endDate.toString(),
		timeZone = frame.period.current.zone.id,
		dayCount = frame.period.current.days,
		dataState = frame.dataState,
		currentCoverage = frame.currentCoverage,
	)

	/** 요청서의 `AnalyticsMeta` — 개요 meta + snapshot ID. 목록 화면만 싣는다(개요는 싣지 않는다). */
	fun analyticsMeta(frame: Frame): AnalyticsMeta = AnalyticsMeta.of(meta(frame), frame.snapshot.snapshotId)

	fun comparison(frame: Frame): AnalyticsComparison {
		val previous = frame.period.previous
		if (frame.period.mode == CompareMode.NONE || previous == null) {
			return AnalyticsComparison(CompareMode.NONE.wire, null, null, DISABLED, null, null)
		}
		return AnalyticsComparison(
			mode = frame.period.mode.wire,
			startDate = previous.startDate.toString(),
			endDate = previous.endDate.toString(),
			status = if (frame.comparable) AVAILABLE else UNAVAILABLE,
			reason = if (frame.comparable) null else Availability.SOURCE_NOT_AVAILABLE,
			coverage = frame.previousCoverage,
		)
	}

	/** 수집 운영 현황 — snapshot 밖의 현재 상태. */
	fun ingest(frame: Frame): AnalyticsIngest = status(frame.organization, frame.history, frame.now).ingest

	/** 선택 기간이 없는 화면(설정)도 같은 규칙으로 수집 현황을 낸다. */
	fun ingest(organization: Organization, now: Instant): AnalyticsIngest = status(organization, now).ingest

	/** 공통 헤더의 수집 현황 — 분석 응답의 `ingest` 조각과 같은 계산이다. 커버리지의 분자·분모를 함께 준다. */
	fun status(organization: Organization, now: Instant): IngestStatus = status(organization, ingest.history(organization.id), now)

	/** `ingest` 조각과 그 커버리지의 분자·분모. 조각은 화면 요청서의 모양 그대로라 분자·분모를 싣지 못한다. */
	data class IngestStatus(val ingest: AnalyticsIngest, val coverageTargetMembers: Long?, val coverageObservedMembers: Long?)

	/**
	 * 판정은 [IngestJudgement] 가 한다(ADR 0041). 여기서는 근거를 읽어 넘기고 응답 모양으로 옮긴다.
	 * 설치 보고·수신 조회가 실패하면 그 값은 null 이고 상태는 `unknown` 이다 — 0 이나 정상으로 바꾸지 않는다.
	 */
	private fun status(organization: Organization, history: IngestStatusReader.History, now: Instant): IngestStatus {
		val since = now.minus(thresholds.window)
		val observed = ingest.observedInstallations(organization.id, since)
		val judgement = IngestJudgement.judge(history.hasReceipts, ingest.installations(organization.id, since), observed, now, thresholds)
		return IngestStatus(
			AnalyticsIngest(
				status = judgement.status,
				reason = judgement.reason,
				asOf = now.toString(),
				firstObservedAt = history.summary?.firstObservedAt?.toString(),
				lastReceivedAt = history.summary?.lastReceivedAt?.toString(),
				windowMinutes = thresholds.window.toMinutes().toInt(),
				activeInstallations = judgement.activeInstallations,
				observedMembers = ingest.observedMembers(organization.id, observed),
				eligibleMembers = ingest.eligibleMembers(organization.id),
				coverageRatio = judgement.coverageRatio,
			),
			judgement.coverageTargetMembers,
			judgement.coverageObservedMembers,
		)
	}

	companion object {
		const val USD = "USD"
		const val READY = "ready"
		const val PARTIAL = "partial"
		const val NO_DATA = "no_data"
		const val UNOBSERVED = "unobserved"
		const val NEVER_OBSERVED = "never_observed"
		private const val DISABLED = "disabled"
		private const val AVAILABLE = "available"
		private const val UNAVAILABLE = "unavailable"
	}
}

/** 목록 화면의 기간 메타. 개요와 달리 재사용할 snapshot ID를 포함한다. */
data class AnalyticsMeta(
	val organizationId: String,
	val generatedAt: String,
	val dataThrough: String?,
	val currency: String,
	val startDate: String,
	val endDate: String,
	val timeZone: String,
	val dayCount: Int,
	val dataState: String,
	val currentCoverage: Coverage,
	val snapshotId: String,
) {
	companion object {
		fun of(meta: AnalyticsPeriodMeta, snapshotId: String) = AnalyticsMeta(
			meta.organizationId, meta.generatedAt, meta.dataThrough, meta.currency, meta.startDate, meta.endDate,
			meta.timeZone, meta.dayCount, meta.dataState, meta.currentCoverage, snapshotId,
		)
	}
}

/** 요청서의 `Page<T>`. [totalCount] 는 권한·필터를 적용한 전체 수이고 [items] 의 길이와 다르다. [nextCursor] 가 null 이면 끝이다. */
data class Page<T>(val items: List<T>, val totalCount: Int, val nextCursor: String?)

/** 요청서의 `Section<T>`. */
data class Section<T>(val availability: String, val reason: String?, val data: T?)
