package com.team376.pulsemetry.persistence.telemetryops

import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * RDS `telemetry_ops` 에 닿지 못했거나 실행 중 끊겼다 — **일시 장애**다. 조립 앱이 **503** 과 `Retry-After` 로 매핑한다
 * (허브 ADR 0006). 수신 기록의 내구성을 확인하지 못한 push 에 성공을 돌려주지 않는 것이 이 예외의 쓰임이다(ADR 0021 §1).
 *
 * 영구 오류 짝을 두지 않는다. 요약 쓰기의 입력은 검증된 tenant 와 서버 시각뿐이라 "같은 요청은 다시 보내도 실패한다"고
 * 말할 근거가 없다 — 분류되지 않은 [SQLException] 은 그대로 전파되고 앱 표의 "그 밖의 예외"(503)가 된다. 잘못 폐기하는
 * 비용은 되돌릴 수 없다.
 */
public class TelemetryOpsUnavailableException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause) {

	internal companion object {
		/**
		 * 다시 보내면 나을 수 있는 실패인가. **넓히지 마라.** 연결(08)·자원 부족(53)·운영자 종료와 statement timeout(57P01–03·
		 * 57014)·직렬화 실패와 교착(40001·40P01)·잠금 대기 실패(55P03), 그리고 스키마가 아직 적용되지 않은 것(3F000·42P01 —
		 * DDL 은 `:apps:enrollment-api` 기동이 적용하므로 배포 순서의 문제다, ADR 0021 §3)만이다.
		 */
		fun isTransient(exception: SQLException): Boolean {
			if (exception is SQLTransientException || exception is SQLRecoverableException) return true
			val state = exception.sqlState ?: return false
			return state.startsWith("08") || state.startsWith("53") || state in TRANSIENT_STATES
		}

		private val TRANSIENT_STATES = setOf("57P01", "57P02", "57P03", "57014", "40001", "40P01", "55P03", "3F000", "42P01")

		/** [block] 의 일시 실패만 이 예외로 감싼다. */
		inline fun <T> classified(action: String, block: () -> T): T =
			try {
				block()
			} catch (exception: SQLException) {
				if (isTransient(exception)) {
					throw TelemetryOpsUnavailableException("telemetry_ops $action: ${exception.sqlState} ${exception.message}", exception)
				}
				throw exception
			}
	}
}
