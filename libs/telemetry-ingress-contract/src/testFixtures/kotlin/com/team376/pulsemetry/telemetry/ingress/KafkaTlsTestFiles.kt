package com.team376.pulsemetry.telemetry.ingress

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 테스트마다 별도 임시 디렉터리에 JDK keytool로 만든 자격. 배포 키나 사전 저장된 개인키를 사용하지 않는다. */
public class KafkaTlsTestFiles private constructor(
	public val directory: Path,
	public val tls: TelemetryKafkaTls,
) {
	public companion object {
		public const val PASSWORD: String = "local-mtls-test-only"

		public fun create(directory: Path, expired: Boolean = false): KafkaTlsTestFiles {
			Files.createDirectories(directory)
			val key = directory.resolve("user.p12")
			val cert = directory.resolve("user.crt")
			val trust = directory.resolve("ca.p12")
			val passwordFile = directory.resolve("user.password")
			Files.writeString(passwordFile, PASSWORD)
			val timing = if (expired) listOf("-startdate", "-2d", "-validity", "1") else listOf("-validity", "30")
			keytool(listOf("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=test-client", "-ext", "BC=ca:true", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-keystore", key.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD) + timing)
			keytool(listOf("-exportcert", "-rfc", "-alias", "client", "-keystore", key.toString(), "-storepass", PASSWORD, "-file", cert.toString()))
			keytool(listOf("-importcert", "-noprompt", "-alias", "ca", "-keystore", trust.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-file", cert.toString()))
			return KafkaTlsTestFiles(directory, TelemetryKafkaTls(trust.toString(), passwordFile.toString(), key.toString(), passwordFile.toString()))
		}

		private fun keytool(arguments: List<String>) {
			val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()) + arguments)
				.redirectErrorStream(true).start()
			check(process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) { "keytool timeout" }
			val output = process.inputStream.bufferedReader().readText()
			check(process.exitValue() == 0) { "test keytool failed: $output" }
		}
	}
}
