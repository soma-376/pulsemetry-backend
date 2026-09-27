package com.team376.pulsemetry.telemetry.adapter.observation

/**
 * 정규화 실행 단위(push 하나의 제품 문서 하나)의 집계. 분석 테이블에 남지 않는 것을 센다(ADR 0020 §2).
 *
 * 이 값이 구조화 로그와 수신 ledger 의 `record_count`·`rejected_count`·`source_time_min/max` 의 재료다
 * (ADR 0021 §1). **`rejected_count` 는 분석 테이블에 넣지 않은 레코드 수**다 — `source_time` 을 얻지 못한 것과
 * 스팬 허용 목록 밖으로 버린 것을 합친다. 메트릭 라이브러리로 내보내지 않는다.
 *
 * 단일 스레드에서 한 번의 정규화가 채우고, 끝나면 읽기만 한다.
 */
public class NormalizationStats {

	private val rejections = sortedMapOf<SourceTimeRejection, Int>()

	/** 받은 레코드 수 — 로그 레코드·스팬·metric point. */
	public var received: Int = 0
		private set

	/** 스팬 허용 목록 밖이라 분석 테이블에서 뺀 스팬 수. */
	public var excludedSpans: Int = 0
		private set

	/** 분석 테이블로 간 관측의 `source_time` 최솟값·최댓값. 하나도 없으면 null. */
	public var sourceTimeMin: EpochNanos? = null
		private set
	public var sourceTimeMax: EpochNanos? = null
		private set

	/** 사유별 `source_time` 거부 수. 0 인 사유는 없다. */
	public val sourceTimeRejections: Map<SourceTimeRejection, Int> get() = rejections.toMap()

	/** 분석 테이블에 넣지 않은 레코드 수. */
	public val rejectedCount: Int get() = rejections.values.sum() + excludedSpans

	public fun recordReceived() {
		received++
	}

	public fun recordRejected(reason: SourceTimeRejection) {
		rejections.merge(reason, 1, Int::plus)
	}

	public fun recordExcludedSpan() {
		excludedSpans++
	}

	/**
	 * 제외한 스팬을 이름별로도 센다. 이름이 동적일 수 있어 서로 다른 이름을 [MAX_EXCLUDED_NAMES] 개까지만 두고
	 * 나머지는 [OTHER_NAMES] 에 합친다(ADR 0020 부록 A.4 — cardinality 제한).
	 */
	public fun recordExcludedSpan(name: String) {
		recordExcludedSpan()
		val key = if (name in excludedNames || excludedNames.size < MAX_EXCLUDED_NAMES) name else OTHER_NAMES
		excludedNames.merge(key, 1, Int::plus)
	}

	private val excludedNames = linkedMapOf<String, Int>()

	/** 제외한 스팬의 이름별 수. 진단용이다. */
	public val excludedSpanNames: Map<String, Int> get() = excludedNames.toMap()

	public companion object {
		public const val MAX_EXCLUDED_NAMES: Int = 64
		public const val OTHER_NAMES: String = "(other)"
	}

	/** 분석 테이블로 가는 관측의 시각을 범위에 더한다. */
	public fun recordAccepted(sourceTime: EpochNanos) {
		if (sourceTimeMin == null || sourceTime < sourceTimeMin!!) sourceTimeMin = sourceTime
		if (sourceTimeMax == null || sourceTime > sourceTimeMax!!) sourceTimeMax = sourceTime
	}

	/** [selection] 을 반영하고 그대로 돌려준다 — 거부면 사유를, 선택이면 시각 범위를 센다. */
	public fun record(selection: SourceTimeSelection): SourceTimeSelection {
		when (selection) {
			is SourceTimeSelection.Selected -> recordAccepted(selection.sourceTime)
			is SourceTimeSelection.Rejected -> recordRejected(selection.reason)
		}
		return selection
	}
}
