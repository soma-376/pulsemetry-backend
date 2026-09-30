package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 모든 분석 응답이 공유하는 틀 — snapshot 하나, 그 snapshot 의 관측 일자, 수신 이력, 그리고 그것으로 정한 **상태**(ADR 0023 §4).
 * 여러 endpoint 가 같은 규칙으로 `dataState`·coverage·비교 공개 여부·가격 혼재를 정하도록 한 곳에서 만든다.
 *
 * - `dataState`: 현재 기간의 관측 일자가 있으면 `partial`, 없고 수신 이력이 있으면 `no_data`, 둘 다 없으면 `never_observed`.
 *   v1 은 완전 관측의 근거가 없어 `ready` 를 내지 않는다. 수신 이력을 판정할 수 없으면 503 이다([IngestStatusReader.history]).
 * - 비교는 [ComparisonPolicy] 가 공개를 허락할 때만 계산한다. v1 은 공개하지 않는다.
 * - `dataThrough` 는 null — 공통 데이터 완료 경계를 보증할 근거가 없다.
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
		val currentCoverage: Coverage,
		val previousCoverage: Coverage?,
		val dataState: String,
		val comparable: Boolean,
		val now: Instant,
	) {
		/** no_data·never_observed — 사용량 값을 내지 않는다(상태 우선). */
		val empty: Boolean get() = dataState != PARTIAL

		val pricingMixed: Boolean get() = snapshot.pricingVersions.size > 1

		fun observed(date: LocalDate): Boolean = date in observedDates
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
		val currentCoverage = Coverage.of(period.current.dates().count { it in observed })
		val previousCoverage = if (usesComparison) period.previous?.let { p -> Coverage.of(p.dates().count { it in observed }) } else null
		val dataState = when {
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
			currentCoverage = currentCoverage,
			previousCoverage = previousCoverage,
			dataState = dataState,
			comparable = previousCoverage != null && comparison.comparable(currentCoverage, previousCoverage),
			now = clock.instant(),
		)
	}

	fun meta(frame: Frame): OverviewResponse.Meta = OverviewResponse.Meta(
		organizationId = frame.organization.id.toString(),
		generatedAt = frame.now.toString(),
		dataThrough = null,
		currency = USD,
		startDate = frame.period.current.startDate.toString(),
		endDate = frame.period.current.endDate.toString(),
		timeZone = frame.period.current.zone.id,
		dayCount = frame.period.current.days,
		dataState = frame.dataState,
		currentCoverage = frame.currentCoverage,
		pricingVersion = frame.snapshot.pricingVersions.singleOrNull(),
	)

	/** 요청서의 `AnalyticsMeta` — 개요 meta + snapshot ID. 목록 화면만 싣는다(개요는 싣지 않는다). */
	fun analyticsMeta(frame: Frame): AnalyticsMeta = AnalyticsMeta.of(meta(frame), frame.snapshot.snapshotId)

	fun comparison(frame: Frame): OverviewResponse.Comparison {
		val previous = frame.period.previous
		if (frame.period.mode == CompareMode.NONE || previous == null) {
			return OverviewResponse.Comparison(CompareMode.NONE.wire, null, null, DISABLED, null, null)
		}
		return OverviewResponse.Comparison(
			mode = frame.period.mode.wire,
			startDate = previous.startDate.toString(),
			endDate = previous.endDate.toString(),
			status = if (frame.comparable) AVAILABLE else UNAVAILABLE,
			reason = if (frame.comparable) null else Availability.SOURCE_NOT_AVAILABLE,
			coverage = frame.previousCoverage,
		)
	}

	/** 수집 운영 현황 — snapshot 밖의 현재 상태. */
	fun ingest(frame: Frame): OverviewResponse.Ingest = status(frame.organization, frame.history, frame.now).ingest

	/** 선택 기간이 없는 화면(설정)도 같은 규칙으로 수집 현황을 낸다. */
	fun ingest(organization: Organization, now: Instant): OverviewResponse.Ingest = status(organization, now).ingest

	/** 공통 헤더의 수집 현황 — 분석 응답의 `ingest` 조각과 같은 계산이다. 커버리지의 분자·분모를 함께 준다. */
	fun status(organization: Organization, now: Instant): IngestStatus = status(organization, ingest.history(organization.id), now)

	/** `ingest` 조각과 그 커버리지의 분자·분모. 조각은 화면 요청서의 모양 그대로라 분자·분모를 싣지 못한다. */
	data class IngestStatus(val ingest: OverviewResponse.Ingest, val coverageTargetMembers: Long?, val coverageObservedMembers: Long?)

	/**
	 * 판정은 [IngestJudgement] 가 한다(ADR 0041). 여기서는 근거를 읽어 넘기고 응답 모양으로 옮긴다.
	 * 설치 보고·수신 조회가 실패하면 그 값은 null 이고 상태는 `unknown` 이다 — 0 이나 정상으로 바꾸지 않는다.
	 */
	private fun status(organization: Organization, history: IngestStatusReader.History, now: Instant): IngestStatus {
		val since = now.minus(thresholds.window)
		val observed = ingest.observedInstallations(organization.id, since)
		val judgement = IngestJudgement.judge(history.hasReceipts, ingest.installations(organization.id, since), observed, now, thresholds)
		return IngestStatus(
			OverviewResponse.Ingest(
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
		const val PARTIAL = "partial"
		const val NO_DATA = "no_data"
		const val NEVER_OBSERVED = "never_observed"
		private const val DISABLED = "disabled"
		private const val AVAILABLE = "available"
		private const val UNAVAILABLE = "unavailable"
	}
}

/** 요청서의 `AnalyticsMeta = OverviewResponse["meta"] & { snapshotId }`. */
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
	val pricingVersion: String?,
	val snapshotId: String,
) {
	companion object {
		fun of(meta: OverviewResponse.Meta, snapshotId: String) = AnalyticsMeta(
			meta.organizationId, meta.generatedAt, meta.dataThrough, meta.currency, meta.startDate, meta.endDate,
			meta.timeZone, meta.dayCount, meta.dataState, meta.currentCoverage, meta.pricingVersion, snapshotId,
		)
	}
}

/** 요청서의 `Page<T>`. [totalCount] 는 권한·필터를 적용한 전체 수이고 [items] 의 길이와 다르다. [nextCursor] 가 null 이면 끝이다. */
data class Page<T>(val items: List<T>, val totalCount: Int, val nextCursor: String?)

/** 요청서의 `Section<T>`. */
data class Section<T>(val availability: String, val reason: String?, val data: T?)
