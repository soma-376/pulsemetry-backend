package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * 한 줄 설치 명령이 내려받는 부트스트랩 스크립트를 만든다 (PLAN.md §6.6).
 *
 * 스크립트 본문은 리소스 파일에 그대로 두고 자리표시자 두 개만 치환한다.
 * 문자열을 코드로 조립하지 않는 이유는 셸·PowerShell 문법을 사람이 읽고 고칠 수 있어야 하기 때문이다.
 *
 * 서버 주소는 **설정값에서만** 온다. `Host` 헤더에서 유도하면 헤더를 조작한 공격자가
 * 사용자를 자기 서버에서 바이너리를 받게 만들 수 있다.
 */
@Component
class BootstrapScripts(
	private val properties: PulsemetryProperties,
) {

	private val windowsTemplate: String = readTemplate("bootstrap/install.ps1")
	private val unixTemplate: String = readTemplate("bootstrap/install.sh")

	fun windows(code: String): String = render(windowsTemplate, code)

	fun unix(code: String): String = render(unixTemplate, code)

	/**
	 * 코드를 스크립트에 끼워 넣는다.
	 *
	 * 컨트롤러가 이미 검증했지만 여기서 한 번 더 확인한다 — 이 함수가 주입 방어선의 마지막 지점이고,
	 * 나중에 다른 호출자가 생겼을 때 검증을 빠뜨리면 그대로 원격 코드 실행이 된다 (A8).
	 * **이스케이프하지 않는다.** 정규식이 허용하는 32자에는 메타문자가 없으므로 이스케이프할 것이 없다.
	 */
	private fun render(template: String, code: String): String {
		require(InvitationCode.matches(code)) { "검증되지 않은 코드를 스크립트에 넣으려 했다" }
		return template
			.replace(CODE_PLACEHOLDER, code)
			.replace(SERVER_PLACEHOLDER, properties.baseUrl())
	}

	private fun readTemplate(path: String): String =
		ClassPathResource(path).inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }

	private companion object {
		const val CODE_PLACEHOLDER = "__PULSEMETRY_INVITE_CODE__"
		const val SERVER_PLACEHOLDER = "__PULSEMETRY_SERVER__"
	}
}
