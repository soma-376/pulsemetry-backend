package com.team376.pulsemetry.telemetry.adapter.observation

import java.time.Instant

/**
 * 두 분석 테이블이 공유하는 봉투 49컬럼 중 **정규화 단계가 채우는 42컬럼**(ADR 0020 §1).
 *
 * 나머지 일곱은 다음 단계의 몫이라 여기 없다 — 조직 보강의 다섯은 [OrgAttribution], 적재의 둘은
 * [RowVersioning] 이다. 세 조각을 합친 필드 집합이 봉투와 1:1 이다(`ObservationColumnsTest`).
 * 속성 순서는 DDL 의 컬럼 순서를 따른다.
 *
 * nullable 은 "미보고"다. 분류 값은 null 대신 `none`/`unknown` 이다.
 */
public data class ObservationEnvelope(
	public val tenantId: String,
	public val installationId: String,
	/** 64자리 소문자 hex SHA-256(ADR 0020 §2). */
	public val observationId: String,
	/** 64자리 소문자 hex SHA-256. 진단 컬럼이지 키가 아니다(ADR 0020 §3). */
	public val analysisHash: String,
	public val schemaVersion: Int = SCHEMA_VERSION,
	public val identityVersion: String,
	public val sourceIdentityKind: SourceIdentityKind,
	public val sourceIdentityNamespace: String? = null,
	public val nativeObservationId: String? = null,
	public val recordStatus: RecordStatus = RecordStatus.ACTIVE,
	public val exclusionReason: String? = null,
	public val mappingStatus: MappingStatus,
	public val qualityFlags: List<QualityFlag> = emptyList(),
	public val sourceTime: EpochNanos,
	public val sourceTimeOrigin: SourceTimeOrigin,
	public val eventTime: EpochNanos? = null,
	public val observedTime: EpochNanos? = null,
	public val receivedTime: EpochNanos,
	public val signal: ObservationSignal,
	public val product: Product,
	public val surface: Surface,
	public val productVersion: String? = null,
	public val serviceName: String? = null,
	public val serviceVersion: String? = null,
	public val serviceInstanceId: String? = null,
	public val scopeName: String? = null,
	public val scopeVersion: String? = null,
	public val resourceSchemaUrl: String? = null,
	public val scopeSchemaUrl: String? = null,
	public val originalName: String? = null,
	public val mappingVersion: String,
	public val usageRole: UsageRole,
	public val usageScope: UsageScope,
	public val workloadKind: WorkloadKind,
	public val sessionId: String? = null,
	public val sessionIdNamespace: String? = null,
	public val model: String? = null,
	public val archiveRef: String? = null,
	public val archiveSelector: String? = null,
	public val maskingVersion: String,
	/** allowlist 속성의 typed 원형([TypedMetadata.toJson]). */
	public val metadataJson: String,
	/** allowlist 중 레코드 스칼라 키를 문자열로 편 조회용 파생값([ScalarAttrs]). */
	public val attrs: Map<String, String> = emptyMap(),
) {
	init {
		requireHex64("observation_id", observationId)
		requireHex64("analysis_hash", analysisHash)
		require(schemaVersion in 0..UINT16_MAX) { "schema_version 은 UInt16 이다: $schemaVersion" }
		require((archiveRef == null) == (archiveSelector == null)) { "archive_ref 와 archive_selector 는 함께 있거나 함께 없다" }
		require(recordStatus == RecordStatus.EXCLUDED || exclusionReason == null) { "active 행에 exclusion_reason 을 두지 않는다" }
	}

	public companion object {
		/** 정규화 계약의 버전(ADR 0020). 과거 모델의 번호와 연속성을 가정하지 않는다. */
		public const val SCHEMA_VERSION: Int = 1
	}
}

/** 품질 플래그를 더한 사본. 플래그는 중복 없이 선언 순서로 둔다. */
public fun ObservationEnvelope.withFlags(vararg flags: QualityFlag): ObservationEnvelope =
	if (flags.isEmpty()) this else copy(qualityFlags = (qualityFlags + flags).distinct().sortedBy { it.ordinal })

/**
 * 조직 보강 단계가 채우는 봉투 다섯 컬럼(ADR 0020 §5). 셋 다 `observation_id`·`analysis_hash` 의 재료가 아니다.
 */
public data class OrgAttribution(
	public val memberId: String?,
	public val teamIdAsOf: String?,
	public val teamIdsAsOf: List<String>,
	public val enrichmentJson: String,
	public val enrichmentVersion: String,
)

/**
 * 적재 단계가 채우는 봉투 두 컬럼. `row_version = (normalizer_rev << 32) | ingest_seq`(ADR 0020 §3).
 *
 * live 수신은 [live] 로 만든다. 재처리 경로는 자기 `ingest_seq` 를 [of] 에 넘긴다 — 그 번호의 규칙은 재처리 기능을 만들 때
 * 정한다(ADR 0020 Follow-up).
 */
public data class RowVersioning(
	public val normalizerRev: UInt,
	public val rowVersion: ULong,
) {
	init {
		require(rowVersion shr 32 == normalizerRev.toULong()) { "row_version 의 상위 32비트가 normalizer_rev 가 아니다" }
	}

	public companion object {
		/**
		 * 배포된 정규화 규칙의 버전(ADR 0020 §3). 매핑·스키마·의미 프로파일·가격 프로파일·보강 규칙 중 하나라도 바뀌면
		 * 올린다 — 높은 값이 언제나 이기므로 구 규칙의 결과가 늦게 와도 새 결과를 되돌리지 못한다. 1 부터 시작한다.
		 */
		public const val NORMALIZER_REV: UInt = 1u

		public fun of(normalizerRev: UInt, ingestSeq: UInt): RowVersioning =
			RowVersioning(normalizerRev, (normalizerRev.toULong() shl 32) or ingestSeq.toULong())

		/**
		 * live 수신의 버전 — `ingest_seq` 는 archive receipt 수신 시각의 epoch 초다(ADR 0020 §3). UInt32 범위 밖의 시각은
		 * 거부한다. 실행 시각이 아니라 receipt 의 시각을 받는다 — 같은 push 의 행은 모두 같은 버전이다.
		 */
		public fun live(receivedAt: Instant, normalizerRev: UInt = NORMALIZER_REV): RowVersioning {
			val seconds = receivedAt.epochSecond
			require(seconds in 0..UInt.MAX_VALUE.toLong()) { "ingest_seq 는 UInt32 다 — receipt 수신 시각이 범위 밖이다: $receivedAt" }
			return of(normalizerRev, seconds.toUInt())
		}
	}
}

internal const val UINT16_MAX: Int = 65_535

internal fun requireHex64(column: String, value: String) = Hex64.require(column, value)
