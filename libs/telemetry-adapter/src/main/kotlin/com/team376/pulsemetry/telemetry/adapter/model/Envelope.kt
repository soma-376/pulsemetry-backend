package com.team376.pulsemetry.telemetry.adapter.model

/**
 * 세 신호가 공유하는 공통 봉투.
 *
 * **모듈 경계를 넘는 공개 API 다.** 보강 단계(`:libs:telemetry-enricher`)와 적재 단계
 * (`:libs:telemetry-persistence`)가 이 타입을 그대로 받는다(PROJ-104). 필드를 바꾸면
 * 그 두 모듈과 golden fixture 가 함께 바뀐다.
 *
 * [recordId] 는 비어 있는 채로 만들어지고 `RecordId.finalize` 가 확정한다. 어댑터는
 * payload 를 채운 직후, **페어링 전에** 그것을 부른다.
 */
public class Envelope(
	public val identity: Identity,
	public val client: Client,
	public val timestamp: Double,
	public val sessionId: String,
	public val ingest: Ingest,
	public val schemaVersion: Int = SCHEMA_VERSION,
	public var recordId: String = "",
) {
	public companion object {
		public const val SCHEMA_VERSION: Int = 1

		/**
		 * `record_id` 해시에 쓰는 tenant 폴백.
		 *
		 * JSON 에는 `null` 을 싣지만 키 재료는 이 값으로 고정한다 — tenant 표기를 바꿨다고
		 * 과거 방출분의 키가 흔들리면 안 되기 때문이다.
		 */
		public const val TENANT_KEY_FALLBACK: String = "(unknown)"

		/**
		 * 세션 ID 를 찾지 못했을 때의 폴백.
		 *
		 * ⚠️ **이 값으로 떨어진 이벤트는 전부 한 버킷에 모인다.** 페어링이 세션 단위로 돌기
		 * 때문에 서로 다른 사용자의 결정·호출이 교차로 짝지어질 수 있다. 구 파이프라인의
		 * 알려진 결함이고 동작 동일성 때문에 그대로 옮긴다.
		 */
		public const val UNKNOWN_SESSION: String = "(unknown)"
	}
}

/**
 * 누구의 이벤트인가.
 *
 * [installationId] 가 정본 신뢰 키다 — 프록시가 검증해 수집 단계가 리소스 속성으로 심는다.
 * [memberId] 는 온보딩 때 회사가 박은 자기신고 값이고, [vendorEmail]·[vendorAccountId] 는
 * 벤더가 준 정보성 값이다(섀도우 AI 탐지용, 정본 아님).
 */
public class Identity(
	public val tenantId: String? = null,
	public val memberId: String? = null,
	public val installationId: String? = null,
	public val vendorEmail: String? = null,
	public val vendorAccountId: String? = null,
)

/** 어떤 제품·표면·버전에서 왔는가. */
public class Client(
	public val product: String,
	public val surface: Surface = Surface.UNKNOWN,
	public val version: String? = null,
)

/**
 * 수집 시점에 스탬프되는 툴 무관 메타데이터. 원본 payload 는 담지 않는다.
 *
 * JSON 키는 **`_ingest`** 다 — 밑줄이 붙는다. 이식 원본이 Python dataclass 필드명을
 * 그대로 직렬화했고 golden fixture 가 그 이름으로 굳어 있다.
 */
public class Ingest(
	public val adapterVersion: Int = 0,
	public val signal: SignalType = SignalType.LOG,
	public val sourceRecordId: String? = null,
	/** 벤더가 조인 키를 주지 않아 합성했는지 여부. 페어링이 이 표시를 보고 대상을 고른다. */
	public var callIdInferred: Boolean = false,
)
