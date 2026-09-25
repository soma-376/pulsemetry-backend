package com.team376.pulsemetry.telemetry.adapter.observation.semantics

import com.team376.pulsemetry.telemetry.adapter.observation.InputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.OutputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.WireValue

/** cache write 성분의 상태(ADR 0020 §4). "해당 없음"은 프로파일에 고정된 사실이지 미보고의 대체가 아니다. */
public enum class CacheWriteStatus(override val wire: String) : WireValue {
	/** 원본이 보고하고, 산식에 필요하다. */
	REPORTED("reported"),

	/** 그 적용 범위에서 cache write 가 존재하지 않음을 소스와 fixture 로 확정했다. */
	NOT_APPLICABLE("not_applicable"),
	UNKNOWN("unknown"),
}

/** 프로파일이 적용되는 범위. 범위 밖은 unknown 이다. */
public data class SemanticsScope(
	val product: Product,
	/** producer 이름과 버전(사람이 읽는 서술 — 실제 매칭은 [com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile.versions]). */
	val producerVersions: String,
	/** 공급자 범위. 근거가 없으면 "unknown" 이라 적는다. */
	val provider: String,
	/** 설정 범위(예: 기본 설정). */
	val configuration: String,
)

/**
 * 토큰 성분의 포함관계를 고정한 검증된 프로파일(ADR 0020 §4, 부록 C). `semantics_profile` 이 [id] 다.
 *
 * **검증 완료로 만들려면 원천 근거(소스 또는 공식 문서)와 저장소 fixture 가 둘 다 있어야 한다.**
 * [evidencePath] 는 저장소 안의 근거 문서 경로다.
 */
public data class SemanticsProfile(
	val id: String,
	val inputSemantics: InputSemantics,
	val outputSemantics: OutputSemantics,
	val cacheWrite: CacheWriteStatus,
	val scope: SemanticsScope,
	val evidencePath: String,
) {
	init {
		require(id.isNotBlank() && evidencePath.isNotBlank()) { "프로파일에는 ID 와 근거 경로가 있다" }
	}
}
