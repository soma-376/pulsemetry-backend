package com.team376.pulsemetry.telemetry.collector.archive

import com.team376.pulsemetry.telemetry.collector.Signal
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * S3 원본 아카이브. 배포 경로다(ADR 0012).
 *
 * 파일시스템을 쓰지 않으므로 infra ADR-0017 이 기록한 두 부채가 함께 사라진다 —
 * collector 컨테이너의 root 실행과 `append: true` 로 인한 무한 증가·재시작 시 소실이다.
 * 버킷 쓰기 권한은 이미 `rawSignalBucket.grantReadWrite(task.taskRole)` 로 부여돼 있어
 * IAM 변경이 필요 없다. 보존은 infra 가 정한다 — prod 30일 · dev 7일(infra ADR-0013).
 *
 * ## 키 배치는 상위 `awss3` exporter 의 기본값을 옮긴 것이다
 *
 * ```
 * <prefix>/<product>/<signal>/year=%Y/month=%m/day=%d/hour=%H/minute=%M/<signal>_<uuid>.json
 * ```
 *
 * 상위 기본 `s3_partition_format` 이 `year=%Y/month=%m/day=%d/hour=%H/minute=%M` 이고
 * 파티션 시각은 UTC 로 고정한다 — 상위 기본값은 로컬 타임존이지만, 태스크의 타임존에 따라 키가
 * 달라지면 재처리 배치가 범위를 잡을 수 없다.
 *
 * 객체 하나가 수신 한 건이다. 현행은 `batch` 프로세서가 앞에 있어 파일 한 줄이 배치 하나였지만,
 * 홉이 사라지면서 `batch` 도 함께 사라졌다(허브 ADR 0005). 재처리는 객체 단위로 읽으므로
 * 경계가 어디든 성립한다.
 */
public class S3ArchiveWriter(
	private val s3: S3Client,
	private val bucket: String,
	/** 모든 키 앞에 붙는 뿌리. 상위 `s3_base_prefix` 에 해당한다. 비워도 된다. */
	private val basePrefix: String = "",
	private val clock: Clock = Clock.systemUTC(),
) : ArchiveWriter {

	override fun write(product: Product, signal: Signal, body: ByteArray) {
		val request = PutObjectRequest.builder()
			.bucket(bucket)
			.key(key(product, signal))
			.contentType("application/json")
			.build()
		s3.putObject(request, RequestBody.fromBytes(body))
	}

	internal fun key(product: Product, signal: Signal): String {
		val partition = PARTITION.format(clock.instant().atOffset(ZoneOffset.UTC))
		val stem = signal.fileStem
		val prefix = basePrefix.trim('/')
		val head = if (prefix.isEmpty()) "" else "$prefix/"
		return "$head${product.archiveSegment}/$stem/$partition/${stem}_${UUID.randomUUID()}.json"
	}

	private companion object {
		/** 상위 `s3_partition_format` 기본값. strftime 을 java.time 형식으로 옮긴 것이다. */
		val PARTITION: DateTimeFormatter =
			DateTimeFormatter.ofPattern("'year='yyyy/'month='MM/'day='dd/'hour='HH/'minute='mm")
	}
}
