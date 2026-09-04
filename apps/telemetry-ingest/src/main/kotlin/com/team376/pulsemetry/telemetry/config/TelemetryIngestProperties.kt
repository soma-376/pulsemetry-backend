package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.telemetry.collector.OtlpIngestHandler
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 이 앱의 설정 표면.
 *
 * 접두사가 `pulsemetry` 인 것은 [tokenHashSecret] 때문이다 — `:apps:enrollment-api` 가 발급할 때
 * 쓰는 키와 **같은 값**이어야 하고, 배포는 환경변수 하나(`PULSEMETRY_TOKEN_HASH_SECRET`)를
 * 두 앱에 똑같이 넣는다. 두 앱이 같은 접두사를 각자 바인딩하는 대가는 ADR 0011 Negative 가
 * 이미 받아들였다 — 갈리면 안 되는 것은 값이 아니라 연산이고, 그쪽은 `TelemetryTokenHasher`
 * 한 벌이 소유한다. 바인딩하지 않은 키는 무시되므로 필요한 것만 담는다.
 *
 * 라이브러리는 `@ConfigurationProperties` 를 두지 않는다(ADR 0011). 값을 읽어 생성자로 넘기는
 * 것이 이 클래스의 일이다.
 */
@ConfigurationProperties(prefix = "pulsemetry")
data class TelemetryIngestProperties(

	/**
	 * telemetry token HMAC-SHA256 키. 비어 있으면 `TelemetryTokenHasher` 생성자가 막아
	 * 애플리케이션이 뜨지 않는다 — **여기서 다시 검증하지 않는다.** 규약은 라이브러리가 소유한다.
	 */
	val tokenHashSecret: String,

	val telemetry: Telemetry = Telemetry(),
) {

	data class Telemetry(
		val archive: Archive = Archive(),
		val clickhouse: ClickHouse = ClickHouse(),
		val ingest: Ingest = Ingest(),
	)

	/**
	 * 마스킹 이후 원본을 어디에 쓰는가 (ADR 0012). 배포는 `s3`, 로컬 dev 와 테스트는 `file` 이다.
	 */
	data class Archive(
		val type: Type = Type.file,
		/** `file` 일 때 쓰는 루트. `<root>/<제품>/<시그널>.jsonl` 로 append 한다. */
		val dir: String = "./archive",
		val bucket: String = "",
		val basePrefix: String = "",
	) {
		enum class Type { file, s3 }

		init {
			// 빈 버킷으로 조용히 쓰면 복구 원천이 사라진다. 기동에서 끊는다.
			require(type != Type.s3 || bucket.isNotBlank()) {
				"pulsemetry.telemetry.archive.bucket 이 비어 있다. type=s3 에는 버킷이 필요하다."
			}
		}
	}

	data class ClickHouse(
		val url: String = "http://localhost:8123",
		val database: String = ClickHouseHttpClient.DEFAULT_DATABASE,
		val timeout: Duration = ClickHouseHttpClient.DEFAULT_TIMEOUT,
		val schema: Schema = Schema(),
	) {
		init {
			require(url.isNotBlank()) { "pulsemetry.telemetry.clickhouse.url 이 비어 있다." }
		}

		/**
		 * 기동 시 스키마 적용 정책 (ADR 0015 · 0016). 실패해도 애플리케이션은 뜬다 —
		 * 아카이브가 다음 단계보다 앞이라, 앱이 살아 있으면 원본은 외부 저장소에 남는다.
		 */
		data class Schema(
			val startupAttempts: Int = 5,
			val startupBackoff: Duration = Duration.ofSeconds(2),
		)
	}

	data class Ingest(
		/** 압축을 푼 뒤의 상한. 넘으면 수집 모듈이 400 을 낸다. */
		val maxDecompressedBytes: Long = OtlpIngestHandler.DEFAULT_MAX_DECOMPRESSED_BYTES,
		/**
		 * 압축을 풀기 **전** 원본 바디의 상한. 넘으면 413 이다 — 구 auth-proxy 의
		 * `MAX_OTLP_BODY_SIZE` 를 물려받은 값이다.
		 */
		val maxRequestBytes: Long = 10L * 1024 * 1024,
		/**
		 * 503 에 실어 보낼 값. 데몬은 이것을 **하한**으로 쓰고(자기 백오프와 max 를 취한다)
		 * 15초에서 자른다. 크게 잡으면 재시도 예산 셋을 기다림으로 태우므로 짧게 둔다.
		 */
		val retryAfter: Duration = Duration.ofSeconds(1),
	) {
		init {
			// 컨트롤러가 상한 + 1 바이트를 Int 로 읽는다. 넘으면 첫 요청에서 500 이 나므로 기동에서 끊는다.
			require(maxRequestBytes in 1 until Int.MAX_VALUE) {
				"pulsemetry.telemetry.ingest.max-request-bytes 는 1 이상 ${Int.MAX_VALUE - 1} 이하여야 한다."
			}
		}
	}
}
