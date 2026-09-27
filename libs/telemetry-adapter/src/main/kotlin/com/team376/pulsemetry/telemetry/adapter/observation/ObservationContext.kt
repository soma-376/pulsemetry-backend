package com.team376.pulsemetry.telemetry.adapter.observation

/**
 * 정규화가 받는 수집 시점의 문맥. 수집 단계의 영수증에서 값만 꺼내 조립 앱이 채운다 — 이 모듈은
 * 수집 모듈에 의존하지 않는다(ADR 0010 · 0014).
 *
 * 신뢰 신원은 여기서만 온다. 레코드의 자기신고 값(`developer.*`·`user.email` 등)은 allowlist metadata 일 뿐
 * 신원 컬럼의 출처가 아니다(ADR 0020 §5).
 */
public class ObservationContext(
	/** 인증이 검증한 tenant. */
	public val tenantId: String,
	/** 인증이 검증한 installation. */
	public val installationId: String,
	/** 서버 수신 시각. `received_time` 이고, 발생 시각을 대신하지 않는다. */
	public val receivedTime: EpochNanos,
	/** 원본에 적용한 마스킹 정책의 버전. `masking_version` 이다. */
	public val maskingVersion: String,
	/**
	 * 신뢰 신원을 원본에 심은 규칙의 버전. 재처리가 원래 신원 문맥을 재현할 때 쓴다.
	 * 관측 ID 의 `identity_version`(정규화 단계의 canonicalization 규칙 버전)과는 다른 값이다.
	 */
	public val stampVersion: String,
	/** 요청 기준 경로를 아카이브 참조로 바꾼다. 영수증이 없으면 null — 행은 `archive_receipt_missing` 이다. */
	public val archive: ArchiveLocator?,
)

/** 요청 기준 경로(resource/scope/record, metrics 는 .../metric/point 의 0 기반 인덱스)의 아카이브 위치. */
public fun interface ArchiveLocator {
	public fun locate(requestPath: List<Int>): ArchivePointer?
}

/** `archive_ref`(객체 URI)와 `archive_selector`(객체 안 위치). 실제로 쓴 객체에서만 온다(ADR 0020 §6). */
public class ArchivePointer(
	public val ref: String,
	public val selector: String,
)
