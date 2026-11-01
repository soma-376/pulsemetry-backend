package com.team376.pulsemetry.enrollment.seat.application

import com.team376.pulsemetry.persistence.enrollment.operation.Operation
import com.team376.pulsemetry.persistence.enrollment.seat.SeatControl
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger
import com.team376.pulsemetry.persistence.enrollment.seat.SeatSource
import com.team376.pulsemetry.persistence.enrollment.seat.SeatView
import java.time.Clock
import java.util.UUID

import com.team376.pulsemetry.persistence.enrollment.management.*
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.CommandRules.fail

/** 기능의 명시적 관리 명령. 공통 실행 관문의 트랜잭션 안에서 호출한다. */
class SeatCommands(private val seats: SeatLedger, private val seatControl: SeatControl, private val clock: Clock) {
    fun executeReclaim(tenant: UUID, actor: UUID, previewId: UUID) = operationNode(seatControl.execute(tenant, actor, previewId))
    fun restore(tenant: UUID, actor: UUID, id: String) = operationNode(seatControl.restore(tenant, actor, uuid(id)))
    fun confirm(tenant: UUID, actor: UUID, id: String, target: String) = operationNode(seatControl.confirm(tenant, actor, uuid(id), target))
    fun cancel(tenant: UUID, actor: UUID, id: String, target: String) = operationNode(seatControl.cancel(tenant, actor, uuid(id), target))
    fun sync(tenant: UUID, actor: UUID, vendor: String) = operationNode(seats.requestSync(tenant, actor, vendor))
    fun assignSeat(tenant: UUID, actor: UUID, vendorId: String, command: AssignSeat): Map<String, Any?> {
        val seat = seats.assign(tenant, vendorId, actor, SeatSource.MANUAL, command.account, command.member ?: SeatLedger.MemberChoice.Automatic,
            command.tier, command.note, command.expectedVersion)
        return seatResponse(tenant, seat)
    }

    fun releaseSeat(tenant: UUID, actor: UUID, vendorId: String, rawSeat: String, expectedVersion: Long): Map<String, Any?> {
        val id = seatOf(tenant, vendorId, rawSeat)
        return seatResponse(tenant, seats.release(tenant, id, actor, SeatSource.MANUAL, expectedVersion))
    }

    fun correctSeat(tenant: UUID, actor: UUID, vendorId: String, rawSeat: String, command: CorrectSeat): Map<String, Any?> {
        val id = seatOf(tenant, vendorId, rawSeat)
        return seatResponse(tenant, seats.correct(tenant, id, actor, command.expectedVersion, command.member, command.tier, command.note))
    }

    fun importSeats(tenant: UUID, actor: UUID, vendorId: String, csv: String, apply: Boolean): Map<String, Any?> {
        val result = seats.importCsv(tenant, vendorId, actor, csv, apply)
        return mapOf("import" to result, "provisional" to seats.authority(tenant, vendorId).provisional)
    }

    private fun seatOf(tenant: UUID, vendorId: String, raw: String): UUID {
        val id = uuid(raw)
        val seat = seats.seat(tenant, id)
        if (seat == null || seat.vendorId != vendorId) fail("not_found", 404, "seatAssignmentId")
        return id
    }

    private fun seatResponse(tenant: UUID, seat: SeatLedger.Seat): Map<String, Any?> = mapOf(
        "seat" to SeatView.of(seat), "warnings" to seats.warnings(tenant, seat.vendorId), "provisional" to seats.authority(tenant, seat.vendorId).provisional)

    fun previewReclaim(tenant: UUID, actor: UUID, requested: List<Pair<String, Long>>): Map<String, Any?> {
        val preview = seatControl.preview(tenant, actor, requested)
        return mapOf(
            "previewId" to preview.id, "expiresAt" to preview.expiresAt.toString(),
            "eligibleSeatAssignmentIds" to preview.targets.map { it.seatAssignmentId.toString() },
            "rejected" to preview.rejected.map { mapOf("seatAssignmentId" to it.seatAssignmentId, "reason" to it.reason) },
            "estimatedMonthlySavingsUsd" to preview.estimatedMonthlySavingsUsd?.stripTrailingZeros()?.toPlainString(),
            "savingsEffectiveAt" to null, "resultingUnallocatedSeats" to preview.resultingUnallocatedSeats,
            "savingsBasis" to if (preview.estimatedMonthlySavingsUsd != null) "contract_unit_price" else null,
            "targets" to preview.targets.map { mapOf("seatAssignmentId" to it.seatAssignmentId.toString(), "vendorId" to it.vendorId, "method" to it.method.wire) },
        )
    }


    private fun operationNode(operation: Operation): Map<String, Any?> = mapOf(
        "operationId" to operation.id, "kind" to operation.kind.wire, "status" to operation.status.wire,
        "createdAt" to operation.createdAt.toString(), "completedAt" to operation.completedAt?.toString(),
        "results" to operation.targets.map { mapOf("targetId" to it.targetId, "status" to it.status.wire, "reason" to it.reason, "action" to it.action) },
        "canRestore" to operation.canRestore(clock.instant()), "restoreUntil" to operation.restoreUntil?.toString(), "retention" to null,
    )
}

data class AssignSeat(val account: String, val member: SeatLedger.MemberChoice?, val tier: String?, val note: String?, val expectedVersion: Long?)
data class CorrectSeat(val expectedVersion: Long, val member: SeatLedger.MemberChoice?, val tier: SeatLedger.Change<String?>?, val note: SeatLedger.Change<String?>?)
