package com.team376.pulsemetry.telemetry.consumer.batch

/**
 * 배치 리스너가 미완료 배치를 오류 처리기의 배치 fallback 으로 보낼 때 던지는 예외(PROJ-341). 실패 인덱스를 싣지 않는다 —
 * Spring 이 같은 메모리 배치로 리스너를 다시 부르고, 리스너는 작업 캐시에서 이어간다.
 *
 * 다음 호출까지의 대기는 예외가 아니라 [BatchDeliveryState] 가 담는다([BatchRetryBackOff]). 그래서 예외 클래스가 바뀌어도 대기
 * 일정이 처음으로 돌아가지 않는다. **원인 메시지를 담지 않는다** — Spring 이 예외를 로그에 남길 수 있고 원인(드라이버·SDK·
 * ClickHouse) 메시지에는 payload·SQL 조각이 섞일 수 있다. 원인은 클래스 이름만 남긴다. 스택을 만들지 않는다.
 */
sealed class BatchIncompleteException(
	val kind: String,
	val stage: String,
	val causeType: String,
) : RuntimeException("telemetry batch incomplete: kind=$kind stage=$stage cause=$causeType", null, false, false)

/** ClickHouse 연결 실패·408·429·5xx, 또는 입력 결함으로 확인되지 않은 오류 응답. 단기 예산을 쓰면 S3 넘김으로 간다. */
class ClickHouseBatchFailure(stage: String, causeType: String) : BatchIncompleteException("clickhouse", stage, causeType)

/** 인증 DB·보강·삭제 경계·요약(RDS), S3 아카이브, 넘김 목록 쓰기 실패. 넘길 근거가 없어 기다린다. */
class DependencyBatchFailure(stage: String, causeType: String) : BatchIncompleteException("dependency", stage, causeType)

/** 호출 마감에 닿았다. [progressed] 면 짧게, 아니면 장애 일정대로 기다린다. */
class BatchDeadlineReached(val progressed: Boolean) :
	BatchIncompleteException(if (progressed) "deadline_progress" else "deadline_stalled", "deadline", "-")

/** 분류하지 않은 예외. 레코드를 건너뛰지 않고 장애 일정대로 기다린다. */
class UnexpectedBatchFailure(stage: String, causeType: String) : BatchIncompleteException("unexpected", stage, causeType)

/** 팀 정책이 보류(HOLD)인 레코드가 있다 — 확정하지 않고 긴 대기·경보로 남긴다. */
class HeldBatchFailure(stage: String, reason: String) : BatchIncompleteException("held", stage, reason)

/** 의존성 호출 한 곳의 실패를 단계와 함께 감싼다. 리스너 최상위가 [DependencyBatchFailure] 로 바꾼다. */
internal class DependencyCallException(val stage: String, cause: Throwable) : RuntimeException(stage, cause)

/**
 * ClickHouse 쓰기·확인 한 곳의 실패. [code] 는 응답의 ClickHouse 오류 코드, [records] 는 실패한 요청에 실린 레코드의 배치 안 인덱스,
 * [common] 은 진단으로 공통 장애임을 확인했다는 뜻이다(단기 예산과 무관하게 넘김으로 간다).
 */
internal class ClickHouseCallException(
	val stage: String,
	val code: Int?,
	cause: Throwable,
	val records: Set<Int> = emptySet(),
	val memoryScope: String? = null,
	val common: Boolean = false,
) : RuntimeException(stage, cause)
