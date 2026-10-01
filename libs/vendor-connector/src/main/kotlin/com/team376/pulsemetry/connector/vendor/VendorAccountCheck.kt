package com.team376.pulsemetry.connector.vendor

import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

/**
 * 벤더 실계정 검증 — **읽기만 한다**(연결 확인 + 좌석 목록 + 청구를 구현한 커넥터는 이번 기간 청구 누계). 절차는 `docs/vendor-connector-verification.md`.
 * 빌드·테스트에 들어가지 않는 별도 태스크(`:libs:vendor-connector:verifyVendorAccount -Pvendor=<커넥터 ID>`)가 이것을 실행한다.
 *
 * 입력은 환경 변수다 — 자격증명 `PULSEMETRY_VERIFY_CREDENTIAL`(또는 파일 경로 `PULSEMETRY_VERIFY_CREDENTIAL_FILE`),
 * 비밀 아닌 설정 `PULSEMETRY_VERIFY_SETTING_<설정 키 대문자>`, 기준 주소 `PULSEMETRY_VERIFY_BASE_URL`(선택 — 비우면 공식 주소).
 * 출력은 좌석 수와 필드 채움 수, 청구 누계(금액·종류·기간)뿐이다. 자격증명·이메일·로그인을 찍지 않는다.
 *
 * 종료 코드: 0 통과, 1 벤더가 거절·실패, 2 입력이 없거나 틀림.
 */
class VendorAccountCheck(private val env: Map<String, String>, private val out: PrintStream, private val http: VendorHttp = VendorHttp(POLICY),
	private val clock: java.time.Clock = java.time.Clock.systemUTC()) {

	fun run(vendor: String): Int {
		val descriptor = ConnectorDescriptors.byId(vendor) ?: return usage("커넥터 ID 는 ${ConnectorDescriptors.ALL.joinToString { it.id }} 중 하나다")
		val credential = env["PULSEMETRY_VERIFY_CREDENTIAL"]?.takeIf { it.isNotBlank() }
			?: env["PULSEMETRY_VERIFY_CREDENTIAL_FILE"]?.takeIf { it.isNotBlank() }?.let { path -> runCatching { Files.readString(Path.of(path)) }.getOrNull() }
			?: return usage("PULSEMETRY_VERIFY_CREDENTIAL(또는 _FILE)이 없다")
		val settings = descriptor.settingKeys.associateWith { key -> env["PULSEMETRY_VERIFY_SETTING_" + key.uppercase()].orEmpty() }
		settings.filterValues { it.isBlank() }.keys.takeIf { it.isNotEmpty() }?.let { missing ->
			return usage("설정이 없다: " + missing.joinToString { "PULSEMETRY_VERIFY_SETTING_" + it.uppercase() })
		}
		val base = env["PULSEMETRY_VERIFY_BASE_URL"]?.takeIf { it.isNotBlank() }?.let(::URI)
		val connector = connector(descriptor, base)
		val target = ConnectionTarget(settings, ConnectorCredential(credential.trim()))
		return try {
			connector.verify(target)
			out.println("[${descriptor.id}] 연결 확인: 통과")
			val seats = connector.listSeats(target)
			out.println("[${descriptor.id}] 좌석 ${seats.size}개 — " + VendorSeatState.entries.joinToString(", ") { state -> "${state.name.lowercase()} ${seats.count { it.state == state }}" })
			out.println("[${descriptor.id}] 이메일 있음 ${seats.count { it.email != null }} · 벤더 내부 ID 있음 ${seats.count { it.vendorAccountRef != null }} · " +
				"배정 시각 있음 ${seats.count { it.assignedAt != null }} · 마지막 활동 있음 ${seats.count { it.lastActivityAt != null }} · 등급 있음 ${seats.count { it.tier != null }}")
			connector.billing?.let { reader ->
				// 서비스와 같은 기간 — 조직 달력(서울)의 이번 달 시작(벤더가 주기를 정하면 그 주기).
				val now = clock.instant()
				val seoul = java.time.ZoneId.of("Asia/Seoul")
				val billed = reader.currentPeriod(target, now, now.atZone(seoul).toLocalDate().withDayOfMonth(1).atStartOfDay(seoul).toInstant())
				out.println("[${descriptor.id}] 청구 누계 ${billed.amount.stripTrailingZeros().toPlainString()} ${billed.currency} — ${billed.kind.wire}, ${billed.from} ~ ${billed.to}, 확정 ${billed.finalized}")
			}
			0
		} catch (failure: ConnectorFailure) {
			out.println("[${descriptor.id}] 실패: ${failure.kind.wire}" + (failure.retryAfter?.let { " (재시도까지 ${it.seconds}초)" } ?: ""))
			1
		}
	}

	private fun connector(descriptor: ConnectorDescriptor, base: URI?): SeatConnector = when (descriptor) {
		ConnectorDescriptors.CLAUDE_ENTERPRISE -> base?.let { ClaudeEnterpriseConnector(http, it) } ?: ClaudeEnterpriseConnector(http)
		ConnectorDescriptors.CURSOR_ENTERPRISE -> base?.let { CursorEnterpriseConnector(http, it) } ?: CursorEnterpriseConnector(http)
		ConnectorDescriptors.COPILOT -> base?.let { CopilotConnector(http, it) } ?: CopilotConnector(http)
		ConnectorDescriptors.GEMINI -> base?.let { GeminiConnector(http, it, it.resolve("/token")) } ?: GeminiConnector(http)
		else -> error("모르는 커넥터 ${descriptor.id}")
	}

	private fun usage(message: String): Int {
		out.println("입력 오류: $message (절차: docs/vendor-connector-verification.md)")
		return 2
	}

	companion object {
		/** 사람이 한 번 돌리는 검증 도구의 호출 수치다(배포 설정이 아니다). 한도 초과는 60초까지만 기다린다. */
		val POLICY = HttpPolicy(requestTimeout = Duration.ofSeconds(30), maxAttempts = 3, retryBackoff = Duration.ofSeconds(5), maxRetryWait = Duration.ofSeconds(60))
	}
}

fun main(args: Array<String>) {
	exitProcess(VendorAccountCheck(System.getenv(), System.out).run(args.firstOrNull().orEmpty()))
}
