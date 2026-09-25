package com.team376.pulsemetry.retention

import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator

/**
 * 명령 하나를 실행하고 그 결과를 종료 코드로 낸다(ADR 0024 §5 — 트리거는 내부 명령뿐이다).
 *
 * | 종료 코드 | 뜻 |
 * |---|---|
 * | 0 | 논리 삭제 완료 |
 * | 1 | 실패 — 기록이 남았으면 `failed` |
 * | 2 | 인자 오류 — 아무것도 하지 않았다 |
 * | 3 | 미완료(`incomplete`) — 같은 인자로 다시 실행한다 |
 */
class RetentionCommandRunner(private val job: RetentionJob) : ApplicationRunner, ExitCodeGenerator {

	@Volatile
	private var exitCode: Int = EXIT_FAILED

	override fun run(args: ApplicationArguments) {
		val command = try {
			RetentionCommand.parse(args)
		} catch (exception: IllegalArgumentException) {
			log.error("retention command rejected: {} — usage: --{}=<uuid> --{}=<N> --{}=<ISO-8601>", exception.message,
				RetentionCommand.TENANT, RetentionCommand.RETENTION_MONTHS, RetentionCommand.AS_OF)
			exitCode = EXIT_USAGE
			return
		}
		exitCode = when (job.run(command).status) {
			RetentionOperationStatus.LOGICALLY_DELETED -> EXIT_DELETED
			RetentionOperationStatus.INCOMPLETE -> EXIT_INCOMPLETE
			RetentionOperationStatus.FAILED, RetentionOperationStatus.RUNNING -> EXIT_FAILED
		}
	}

	override fun getExitCode(): Int = exitCode

	companion object {
		const val EXIT_DELETED = 0
		const val EXIT_FAILED = 1
		const val EXIT_USAGE = 2
		const val EXIT_INCOMPLETE = 3

		private val log = LoggerFactory.getLogger(RetentionCommandRunner::class.java)
	}
}
