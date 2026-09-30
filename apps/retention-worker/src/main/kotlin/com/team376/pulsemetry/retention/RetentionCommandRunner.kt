package com.team376.pulsemetry.retention

import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator

/**
 * 명령 하나, 또는 요청 모드(`--requests`)의 저장된 보존 정리 요청들을 실행하고 그 결과를 종료 코드로 낸다(ADR 0024 §5, ADR 0047).
 *
 * | 종료 코드 | 명령 하나 | 요청 모드 |
 * |---|---|---|
 * | 0 | 논리 삭제 완료 | 다룬 요청이 모두 논리 삭제 완료(요청이 없었어도 0) |
 * | 1 | 실패 — 기록이 남았으면 `failed` | 실패한 요청이 있다 |
 * | 2 | 인자 오류 — 아무것도 하지 않았다 | 인자 오류·요청 모드 설정 없음 — 아무것도 하지 않았다 |
 * | 3 | 미완료(`incomplete`) — 같은 인자로 다시 실행한다 | 미완으로 끝난 요청이 있다 — 다시 실행하면 이어서 끝낸다 |
 */
class RetentionCommandRunner(private val job: RetentionJob, private val requests: RetentionRequestProcessor) : ApplicationRunner, ExitCodeGenerator {

	@Volatile
	private var exitCode: Int = EXIT_FAILED

	override fun run(args: ApplicationArguments) {
		val invocation = try {
			RetentionCommand.invocation(args)
		} catch (exception: IllegalArgumentException) {
			log.error("retention command rejected: {} — usage: --{}=<uuid> --{}=<N> --{}=<ISO-8601> | --{}", exception.message,
				RetentionCommand.TENANT, RetentionCommand.RETENTION_MONTHS, RetentionCommand.AS_OF, RetentionCommand.REQUESTS)
			exitCode = EXIT_USAGE
			return
		}
		val command = when (invocation) {
			is RetentionInvocation.Requests -> {
				exitCode = requests.runAll()
				return
			}
			is RetentionInvocation.Single -> invocation.command
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
