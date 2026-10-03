package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authorization.DashboardAction
import com.team376.pulsemetry.dashboard.authorization.OrganizationAccess
import com.team376.pulsemetry.dashboard.config.DashboardApiProperties
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.persistence.enrollment.operation.Operation
import com.team376.pulsemetry.persistence.enrollment.operation.OperationReader
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperation
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStore
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 명령이 접수한 작업의 상태 (ADR 0039). 원천 계정으로 읽기만 한다 — 작업을 만들고 바꾸는 쪽은 명령을 받은 앱과 그 실행 주체다.
 *
 * 다른 조직의 작업과 없는 작업은 같은 404 다. 시스템이 아직 진행할 일이 남은 작업(`pending`·`running`)에만 `Retry-After` 를 싣는다 —
 * 조치 대기는 시간이 지나도 바뀌지 않고, 관리자의 확인으로만 바뀐다.
 */
@RestController
class OperationController(
	private val access: OrganizationAccess,
	private val operations: OperationReader,
	private val retention: RetentionOperationStore,
	private val properties: DashboardApiProperties,
	private val clock: Clock,
) {
	@GetMapping("/api/v1/organizations/{organizationId}/operations/{operationId}")
	fun operation(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		@PathVariable operationId: String,
	): ResponseEntity<OperationResponse> {
		val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS)
		val id = runCatching { UUID.fromString(operationId) }.getOrNull() ?: throw DashboardException(ErrorCode.NOT_FOUND)
		val operation = operations.find(organization.id, id) ?: throw DashboardException(ErrorCode.NOT_FOUND)
		// 삭제 실행 기록은 다른 스키마에 있다. 그 조직의 기록일 때만 싣는다.
		val run = operation.retentionOperationId?.let(retention::find)?.takeIf { it.tenantId == organization.id }
		val response = ResponseEntity.ok()
		if (operation.status == OperationStatus.PENDING || operation.status == OperationStatus.RUNNING) {
			response.header(HttpHeaders.RETRY_AFTER, properties.retryAfter.toSeconds().toString())
		}
		return response.body(OperationResponse.of(operation, run, clock.instant()))
	}
}

data class OperationResponse(
	val operationId: String,
	val kind: String,
	val status: String,
	val createdAt: String,
	val completedAt: String?,
	val results: List<Result>,
	val canRestore: Boolean,
	val restoreUntil: String?,
	/** 보존 정리 작업의 가장 최근 삭제 실행. 아직 실행된 적이 없거나 다른 종류의 작업이면 null 이다. */
	val retention: Retention?,
) {
	/** [reason] 은 실패 분류 코드, [action] 은 관리자가 시스템 밖에서 해야 하는(했던) 조치의 코드다. */
	data class Result(val targetId: String, val status: String, val reason: String?, val action: String?)

	/** `logically_deleted` 는 논리 삭제 완료다 — 물리 제거 완료가 아니다. */
	data class Retention(val status: String, val requestedBefore: String, val deletedBefore: String?, val startedAt: String, val finishedAt: String?)

	companion object {
		fun of(operation: Operation, run: RetentionOperation?, now: Instant) = OperationResponse(
			operationId = operation.id.toString(),
			kind = operation.kind.wire,
			status = operation.status.wire,
			createdAt = operation.createdAt.toString(),
			completedAt = operation.completedAt?.toString(),
			results = operation.targets.map { Result(it.targetId, it.status.wire, it.reason, it.action) },
			canRestore = operation.canRestore(now),
			restoreUntil = operation.restoreUntil?.toString(),
			retention = run?.let {
				Retention(it.status.wire, it.requestedBefore.toString(), it.deletedBefore?.toString(), it.startedAt.toString(), it.finishedAt?.toString())
			},
		)
	}
}
