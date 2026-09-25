package com.team376.pulsemetry.retention

import org.springframework.boot.ApplicationArguments
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 보존 작업 한 번의 입력(ADR 0024 §5) — `--tenant=<uuid> --retention-months=<N> --as-of=<ISO-8601 시각>`.
 *
 * asOf 를 생략하지 않는다. 같은 입력의 재실행이 같은 경계를 계산해야 미완료 실행을 이어서 끝낼 수 있다.
 */
data class RetentionCommand(val tenantId: UUID, val retentionMonths: Int, val asOf: Instant) {

	init {
		require(retentionMonths >= 1) { "--retention-months 는 1 이상이어야 한다: $retentionMonths" }
	}

	/** 이 입력의 삭제 경계 — [RetentionBoundaryRule]. */
	val requestedBefore: Instant get() = RetentionBoundaryRule.deletedBefore(asOf, retentionMonths)

	companion object {
		const val TENANT = "tenant"
		const val RETENTION_MONTHS = "retention-months"
		const val AS_OF = "as-of"

		/** 인자가 빠졌거나 형식이 틀리면 [IllegalArgumentException]. 위치 인자는 받지 않는다 — 잘못 붙인 값이 조용히 무시되지 않게. */
		fun parse(args: ApplicationArguments): RetentionCommand {
			require(args.nonOptionArgs.isEmpty()) { "위치 인자는 받지 않는다: ${args.nonOptionArgs}" }
			val tenant = single(args, TENANT)
			val months = single(args, RETENTION_MONTHS)
			val asOf = single(args, AS_OF)
			return RetentionCommand(
				tenantId = runCatching { UUID.fromString(tenant) }.getOrElse { throw IllegalArgumentException("--$TENANT 가 UUID 가 아니다: $tenant") },
				retentionMonths = months.toIntOrNull() ?: throw IllegalArgumentException("--$RETENTION_MONTHS 가 정수가 아니다: $months"),
				asOf = try {
					OffsetDateTime.parse(asOf).toInstant()
				} catch (exception: DateTimeParseException) {
					throw IllegalArgumentException("--$AS_OF 가 오프셋이 있는 ISO-8601 시각이 아니다: $asOf", exception)
				},
			)
		}

		private fun single(args: ApplicationArguments, name: String): String {
			val values = args.getOptionValues(name)
			require(values != null && values.size == 1 && values.single().isNotBlank()) { "--$name=<값> 이 정확히 하나 있어야 한다" }
			return values.single()
		}
	}
}

/**
 * 조직 보존 설정의 삭제 경계 — asOf 가 속한 KST 날짜에서 N 개월 전의 같은 날(그 날이 없는 달이면 말일)의 KST 00:00 이다(ADR 0024 §1).
 * 대시보드 v1 이 조회 기간을 해석하는 시간대와 같다. `LocalDate.minusMonths` 가 말일로 맞춘다.
 */
object RetentionBoundaryRule {
	val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

	fun deletedBefore(asOf: Instant, retentionMonths: Int): Instant {
		require(retentionMonths >= 1) { "보존 개월 수는 1 이상이다: $retentionMonths" }
		return asOf.atZone(ZONE).toLocalDate().minusMonths(retentionMonths.toLong()).atStartOfDay(ZONE).toInstant()
	}
}
