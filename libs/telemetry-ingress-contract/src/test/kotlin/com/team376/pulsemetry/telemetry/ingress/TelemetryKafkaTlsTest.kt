package com.team376.pulsemetry.telemetry.ingress

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryKafkaTlsTest {
	private lateinit var directory: Path
	private lateinit var files: KafkaTlsTestFiles

	@BeforeAll
	fun prepare(@TempDir directory: Path) {
		this.directory = directory
		files = KafkaTlsTestFiles.create(directory.resolve("valid"))
	}

	private fun connection(tls: TelemetryKafkaTls = files.tls) = TelemetryKafkaConnection(
		"telemetry-kafka-kafka-bootstrap.pulsemetry-dev.svc:9093", TelemetryKafkaSecurity.MTLS, tls = tls,
	)

	@Test
	fun mtlsUsesPkcs12AndHostnameVerificationWithoutIamOrSasl() {
		val props = connection().clientProperties("gateway")
		assertThat(props).containsAllEntriesOf(mapOf(
			"security.protocol" to "SSL", "ssl.endpoint.identification.algorithm" to "https",
			"ssl.truststore.type" to "PKCS12", "ssl.keystore.type" to "PKCS12",
			"ssl.truststore.location" to files.tls.truststoreLocation,
			"ssl.keystore.location" to files.tls.keystoreLocation,
			"ssl.truststore.password" to KafkaTlsTestFiles.PASSWORD,
			"ssl.keystore.password" to KafkaTlsTestFiles.PASSWORD,
			"ssl.key.password" to KafkaTlsTestFiles.PASSWORD,
		))
		assertThat(props.keys).noneMatch { it.startsWith("sasl.") || it.startsWith("aws.") }
		assertThat(connection().toString()).doesNotContain(KafkaTlsTestFiles.PASSWORD, files.directory.toString())
		assertThat(files.tls.toString()).doesNotContain(KafkaTlsTestFiles.PASSWORD, files.directory.toString())
	}

	@Test
	fun missingPathsAndUnreadableFilesFailSafely() {
		assertThatThrownBy { connection(TelemetryKafkaTls()).clientProperties("c") }.hasMessageContaining("TRUSTSTORE_PASSWORD_FILE")
		for (file in listOf("relative.p12", directory.resolve("missing").toString(), directory.toString())) {
			assertThatThrownBy { connection(files.tls.copy(keystoreLocation = file)).clientProperties("c") }
				.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("KEYSTORE_LOCATION").hasNoCause()
		}
		val empty = Files.createFile(directory.resolve("empty"))
		assertThatThrownBy { connection(files.tls.copy(keystorePasswordFile = empty.toString())).clientProperties("c") }
			.hasMessageContaining("KEYSTORE_PASSWORD_FILE").hasNoCause()
	}

	@Test
	fun wrongPasswordsAndDamagedStoresDoNotLeakContents() {
		val secret = "secret-value-that-must-not-appear"
		val wrong = Files.writeString(directory.resolve("wrong-password"), secret)
		for (tls in listOf(files.tls.copy(keystorePasswordFile = wrong.toString()), files.tls.copy(truststorePasswordFile = wrong.toString()))) {
			assertThatThrownBy { connection(tls).clientProperties("c") }
				.isInstanceOf(IllegalArgumentException::class.java).hasMessageNotContaining(secret).hasNoCause()
		}
		val broken = Files.writeString(directory.resolve("broken.p12"), secret)
		assertThatThrownBy { connection(files.tls.copy(keystoreLocation = broken.toString())).clientProperties("c") }
			.hasMessageContaining("KEYSTORE_LOCATION").hasMessageNotContaining(secret).hasNoCause()
	}

	@Test
	fun fileLimitsAndInvalidUtf8AreRejectedAndPasswordsAreNotTrimmed() {
		for ((name, bytes) in listOf(
			"large-password" to ByteArray(4097) { 65 },
			"invalid-utf8" to byteArrayOf(0xC3.toByte(), 0x28),
			"password-newline" to (KafkaTlsTestFiles.PASSWORD + "\n").toByteArray(),
		)) {
			val file = Files.write(directory.resolve(name), bytes)
			assertThatThrownBy { connection(files.tls.copy(keystorePasswordFile = file.toString())).clientProperties("c") }
				.isInstanceOf(IllegalArgumentException::class.java).hasNoCause().hasMessageNotContaining(KafkaTlsTestFiles.PASSWORD)
		}
		val oversizedStore = Files.write(directory.resolve("large-store"), ByteArray(1024 * 1024 + 1))
		assertThatThrownBy { connection(files.tls.copy(truststoreLocation = oversizedStore.toString())).clientProperties("c") }
			.hasMessageContaining("TRUSTSTORE_LOCATION").hasNoCause()
	}

	@Test
	fun certificateOnlyKeystoreAndEmptyTruststoreAreRejected() {
		assertThatThrownBy { connection(files.tls.copy(keystoreLocation = files.tls.truststoreLocation)).clientProperties("c") }
			.hasMessageContaining("개인키")
		val emptyStore = directory.resolve("empty.p12")
		KeyStore.getInstance("PKCS12").apply { load(null, null) }.let { store ->
			Files.newOutputStream(emptyStore).use { store.store(it, KafkaTlsTestFiles.PASSWORD.toCharArray()) }
		}
		assertThatThrownBy { connection(files.tls.copy(truststoreLocation = emptyStore.toString())).clientProperties("c") }
			.hasMessageContaining("신뢰 인증서")
	}

	@Test
	fun expiredClientCertificateIsRejected() {
		val expired = KafkaTlsTestFiles.create(directory.resolve("expired"), expired = true)
		assertThatThrownBy { connection(expired.tls.copy(truststoreLocation = files.tls.truststoreLocation)).clientProperties("c") }
			.hasMessageContaining("유효기간")
	}

	@Test
	fun projectedSecretSymlinksAreAllowedAndUnusedTlsDoesNotReadFiles() {
		val link = directory.resolve("projected.p12")
		Files.createSymbolicLink(link, Path.of(files.tls.keystoreLocation))
		assertThat(connection(files.tls.copy(keystoreLocation = link.toString())).clientProperties("c")["security.protocol"]).isEqualTo("SSL")
		val unused = TelemetryKafkaTls("/missing", "/missing", "/missing", "/missing")
		assertThat(TelemetryKafkaConnection("localhost:9092", TelemetryKafkaSecurity.PLAINTEXT, tls = unused).clientProperties("c"))
			.containsEntry("security.protocol", "PLAINTEXT")
	}
}
