package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.service.BootstrapScripts
import com.team376.pulsemetry.enrollment.update.DaemonRelease
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class GuiDistributionTest {
    @TempDir lateinit var directory: Path

    private fun properties() = PulsemetryProperties(
        publicBaseUrl = "https://get.example.test/base",
        admin = PulsemetryProperties.Admin("test-only"), tokenHashSecret = "test-only",
        binaries = PulsemetryProperties.Binaries(directory.toString()),
    )

    @Test
    fun `GUI 여섯 자산은 검증된 릴리스에서 Pulsemetry 이름으로 내려간다`() {
        val releaseDir = Files.createDirectory(directory.resolve("v0.2.0"))
        val names = listOf("windows_amd64.exe", "windows_arm64.exe", "darwin_amd64.dmg", "darwin_arm64.dmg", "linux_amd64.AppImage", "linux_arm64.AppImage")
            .map { "pulsemetry_gui_$it" }
        val bytes = "gui-package".toByteArray()
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        names.forEach { Files.write(releaseDir.resolve(it), bytes) }
        Files.writeString(releaseDir.resolve("SHA256SUMS"), names.joinToString("\n", postfix = "\n") { "$digest  $it" })
        val props = properties()
        val controller = BinaryController(props, DaemonRelease(props))
        names.forEach { name ->
            val response = controller.download(name)
            assertThat(response.statusCode.value()).isEqualTo(200)
            assertThat(response.headers.contentDisposition.filename).isEqualTo("Pulsemetry." + name.substringAfterLast('.'))
            assertThat(response.body!!.inputStream.use { it.readBytes() }).isEqualTo(bytes)
        }
        Files.writeString(releaseDir.resolve(names.first()), "tampered-package")
        assertThat(controller.download(names.first()).statusCode.value()).isEqualTo(404)
        assertThat(controller.download("pulsemetry_gui_darwin_arm64.zip").statusCode.value()).isEqualTo(404)
        assertThat(controller.download("../Pulsemetry.dmg").statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `GUI는 평면 개발 디렉터리에서도 제공한다`() {
        val props = properties()
        val controller = BinaryController(props, DaemonRelease(props))
        Files.writeString(directory.resolve("pulsemetry_gui_darwin_arm64.dmg"), "gui")
        assertThat(controller.download("pulsemetry_gui_darwin_arm64.dmg").statusCode.value()).isEqualTo(200)
        assertThat(controller.download("pulsemetry_gui_darwin_amd64.dmg").statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `부트스트랩은 등록 뒤 GUI 설치를 시도하고 GUI 실패를 격리한다`() {
        val scripts = BootstrapScripts(properties())
        val unix = scripts.unix("ABCD-EFGH-JKMN")
        val windows = scripts.windows("ABCD-EFGH-JKMN")
        assertThat(unix.indexOf("enroll --invite")).isLessThan(unix.indexOf("gui install"))
        assertThat(unix).contains("if ! \"\$exe\" gui install; then")
        assertThat(windows.indexOf("enroll --invite")).isLessThan(windows.indexOf("gui install"))
        assertThat(windows).contains("if (\$LASTEXITCODE -ne 0) { throw", "try {", "catch {")
    }
    @Test
    fun `unix 스크립트 실행에서 등록 실패는 중단하고 GUI 실패는 수집 성공을 보존한다`() {
        val fakeBin = Files.createDirectory(directory.resolve("fake-bin"))
        val trace = directory.resolve("trace")
        val fakeCli = directory.resolve("fake-cli")
        Files.writeString(fakeCli, """#!/bin/sh
printf '%s\n' "${'$'}*" >> "${'$'}PULSEMETRY_TEST_TRACE"
case "${'$'}1" in
  enroll) exit "${'$'}PULSEMETRY_TEST_ENROLL_EXIT" ;;
  gui) exit "${'$'}PULSEMETRY_TEST_GUI_EXIT" ;;
esac
""")
        val curl = fakeBin.resolve("curl")
        Files.writeString(curl, """#!/bin/sh
while [ "${'$'}#" -gt 0 ]; do
  if [ "${'$'}1" = '-o' ]; then shift; cp "${'$'}PULSEMETRY_TEST_CLI" "${'$'}1"; exit 0; fi
  shift
done
exit 1
""")
        curl.toFile().setExecutable(true)
        val script = directory.resolve("install.sh")
        Files.writeString(script, BootstrapScripts(properties()).unix("ABCD-EFGH-JKMN")
            .replace("${'$'}HOME/.pulsemetry/bin", directory.resolve("installed-bin").toString()))
        for ((enroll, gui) in listOf(0 to 0, 0 to 1, 1 to 0)) {
            Files.deleteIfExists(trace)
            val process = ProcessBuilder("/bin/sh", script.toString()).redirectErrorStream(true)
            process.environment().putAll(mapOf(
                "PATH" to "$fakeBin:${System.getenv("PATH")}",
                "PULSEMETRY_TEST_CLI" to fakeCli.toString(), "PULSEMETRY_TEST_TRACE" to trace.toString(),
                "PULSEMETRY_TEST_ENROLL_EXIT" to enroll.toString(), "PULSEMETRY_TEST_GUI_EXIT" to gui.toString(),
            ))
            val running = process.start()
            val output = running.inputStream.bufferedReader().readText()
            assertThat(running.waitFor()).describedAs(output).isEqualTo(enroll)
            val calls = Files.readAllLines(trace)
            assertThat(calls.first()).startsWith("enroll --invite")
            if (enroll == 0) assertThat(calls.last()).isEqualTo("gui install") else assertThat(calls).hasSize(1)
            if (gui != 0 && enroll == 0) assertThat(output).contains("수집은 유지됩니다")
        }
    }

}
