package com.team376.pulsemetry.telemetry.config

import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.Product
import com.team376.pulsemetry.telemetry.collector.archive.S3ArchiveWriter
import com.team376.pulsemetry.telemetry.consumer.batch.HandoffListWriter
import com.team376.pulsemetry.telemetry.consumer.batch.S3HandoffListWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.HttpMetric
import software.amazon.awssdk.metrics.MetricCollection
import software.amazon.awssdk.metrics.MetricPublisher
import software.amazon.awssdk.metrics.SdkMetric
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `type=s3` 의 S3 클라이언트 조립(PROJ-347). 실제 AWS 에 닿지 않는다 — 리전·접속 주소·자격 증명을 SDK 시스템 속성으로 루프백 서버에
 * 돌리고, 끝나면 되돌린다. 연결 풀의 실제 상한은 Apache5 가 요청마다 내는 SDK 지표(`MaxConcurrency`)로 읽는다.
 */
@ExtendWith(OutputCaptureExtension::class)
// JVM 전역 시스템 속성(aws.*)을 바꾸므로 병렬 실행을 켜도 다른 시험과 겹치지 않게 잠근다.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ArchiveConfigTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(TelemetryIngestProperties::class)
	class Binding

	private val runner = ApplicationContextRunner()
		.withUserConfiguration(Binding::class.java, ClockConfig::class.java, ArchiveConfig::class.java)
		.withPropertyValues(
			"pulsemetry.token-hash-secret=test-token-hash-secret",
			"pulsemetry.telemetry.archive.type=s3",
			"pulsemetry.telemetry.archive.bucket=$BUCKET",
		)

	@BeforeEach
	fun clear() {
		requests.clear()
	}

	@Test
	@DisplayName("type=s3 면 S3 클라이언트 빈 하나를 원본 아카이브와 넘김 목록이 함께 쓰고, 배치 6 × (16 + 1) = 102 가 Apache5 연결 풀 상한이 된다")
	fun batchModeSizesTheSharedApache5Pool(output: CapturedOutput) {
		runner.withPropertyValues(
			"pulsemetry.telemetry.kafka.consumer.concurrency=6",
			"pulsemetry.telemetry.kafka.consumer.batch.enabled=true",
			"pulsemetry.telemetry.kafka.consumer.batch.archive-concurrency=16",
		).run { context ->
			assertThat(context).hasNotFailed().hasSingleBean(S3Client::class.java)
			assertThat(context.getBean(ArchiveWriter::class.java)).isInstanceOf(S3ArchiveWriter::class.java)
			assertThat(context.getBean(HandoffListWriter::class.java)).isInstanceOf(S3HandoffListWriter::class.java)

			context.getBean(ArchiveWriter::class.java).write(Product.CODEX, Signal.LOGS, """{"resourceLogs":[]}""".toByteArray())
			context.getBean(HandoffListWriter::class.java).write("handoff/test.json", "{}".toByteArray())
			assertThat(requests).hasSize(2).allSatisfy { assertThat(it).startsWith("PUT /$BUCKET/") }
			assertThat(requests.last()).isEqualTo("PUT /$BUCKET/handoff/test.json")

			assertThat(poolOf(context.getBean(S3Client::class.java))).isEqualTo("Apache5" to 102)
		}
		assertThat(output).contains("telemetry archive s3 client: http=apache5 maxConnections=102 configured=false batch=true consumers=6 archiveConcurrency=16")
	}

	@Test
	@DisplayName("배치가 꺼져 있으면 SDK 기본값과 같은 50 이다")
	fun withoutBatchTheSdkDefaultStays() {
		runner.run { context ->
			assertThat(poolOf(context.getBean(S3Client::class.java))).isEqualTo("Apache5" to 50)
		}
	}

	@Test
	@DisplayName("명시한 최대 연결 수가 계산값(6 × 9 = 54)보다 우선한다")
	fun configuredValueWins(output: CapturedOutput) {
		runner.withPropertyValues(
			"pulsemetry.telemetry.archive.s3-max-connections=80",
			"pulsemetry.telemetry.kafka.consumer.concurrency=6",
			"pulsemetry.telemetry.kafka.consumer.batch.enabled=true",
		).run { context ->
			assertThat(poolOf(context.getBean(S3Client::class.java))).isEqualTo("Apache5" to 80)
		}
		assertThat(output).contains("maxConnections=80 configured=true")
	}

	@Test
	@DisplayName("배치 모드에서 명시값이 소비자 수 × (동시 수 + 1) 보다 작으면 컨텍스트가 뜨지 않는다")
	fun insufficientConfiguredValueFailsStartup() {
		runner.withPropertyValues(
			"pulsemetry.telemetry.archive.s3-max-connections=101",
			"pulsemetry.telemetry.kafka.consumer.concurrency=6",
			"pulsemetry.telemetry.kafka.consumer.batch.enabled=true",
			"pulsemetry.telemetry.kafka.consumer.batch.archive-concurrency=16",
		).run { context ->
			assertThat(context).hasFailed()
			assertThat(context.startupFailure).rootCause().hasMessageContaining("s3-max-connections")
		}
		assertThat(requests).isEmpty()
	}

	/** PUT 한 번을 보내고 그 요청의 HTTP 지표에서 구현 이름과 연결 풀 상한을 읽는다. */
	private fun poolOf(s3: S3Client): Pair<String, Int> {
		val publisher = CapturingPublisher()
		val request = PutObjectRequest.builder().bucket(BUCKET).key("probe.json")
			.overrideConfiguration { it.addMetricPublisher(publisher) }
			.build()
		s3.putObject(request, RequestBody.fromString("{}"))
		val metrics = publisher.collections.single()
		return metrics.all(HttpMetric.HTTP_CLIENT_NAME).single() to metrics.all(HttpMetric.MAX_CONCURRENCY).single()
	}

	private class CapturingPublisher : MetricPublisher {
		val collections = CopyOnWriteArrayList<MetricCollection>()

		override fun publish(metricCollection: MetricCollection) {
			collections += metricCollection
		}

		override fun close() = Unit
	}

	companion object {
		private const val BUCKET = "pulsemetry-test-archive"

		/** SDK 가 읽는 시스템 속성. 리전은 클라이언트를 만들 때, 접속 주소·자격 증명은 요청 때 해석한다. */
		private val SDK_PROPERTIES = listOf("aws.region", "aws.endpointUrlS3", "aws.accessKeyId", "aws.secretAccessKey")

		private val saved = mutableMapOf<String, String?>()
		private val requests = CopyOnWriteArrayList<String>()
		private lateinit var server: HttpServer

		@BeforeAll
		@JvmStatic
		fun start() {
			server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			server.createContext("/") { exchange ->
				exchange.requestBody.use { it.readAllBytes() }
				requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
				exchange.responseHeaders.add("ETag", "\"test-etag\"")
				exchange.sendResponseHeaders(200, -1)
				exchange.close()
			}
			server.start()
			SDK_PROPERTIES.forEach { saved[it] = System.getProperty(it) }
			System.setProperty("aws.region", "us-east-1")
			System.setProperty("aws.endpointUrlS3", "http://127.0.0.1:${server.address.port}")
			System.setProperty("aws.accessKeyId", "AKIDTESTONLY")
			System.setProperty("aws.secretAccessKey", "test-secret-not-real")
		}

		@AfterAll
		@JvmStatic
		fun stop() {
			saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
			server.stop(0)
		}

		private fun <T> MetricCollection.all(metric: SdkMetric<T>): List<T> = metricValues(metric) + children().flatMap { it.all(metric) }
	}
}
