package com.team376.pulsemetry.telemetry.collector.archive

import com.team376.pulsemetry.telemetry.collector.Signal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 로컬 파일 아카이브. 현행 `file` exporter 여섯의 동작을 그대로 옮긴 것이다.
 *
 * ```yaml
 * file/claude_code_logs:
 *   path: /data/claude_code/logs.jsonl
 *   append: true
 *   create_directory: true
 * ```
 *
 * `format` 을 지정하지 않으면 상위 기본값이 `json` 이고 `compression` 이 없으므로
 * **한 줄에 export 문서 하나**(JSONL)다. 실제 아카이브 파일에서도 한 줄이 완결된
 * `{"resourceLogs":[...]}` 문서임을 확인했다.
 *
 * ## 배포에서 쓰지 마라 — 로컬 dev 와 테스트용이다
 *
 * infra ADR-0017 이 이 exporter 를 쓰는 대가를 기록했다. 이미지에 비root 가 쓸 수 있는 경로가
 * 없어 **collector 컨테이너를 root 로 돌려야 했고**, `append: true` 는 `rotation` 과 함께 쓸 수
 * 없어 **파일이 무한히 증가**하며(임시 스토리지 20 GiB 를 채우면 태스크가 죽는다) Fargate 임시
 * 스토리지라 **재시작하면 사라진다**. 배포 경로는 [S3ArchiveWriter] 다(ADR 0012).
 */
public class FileArchiveWriter(
	/** 아카이브 루트. 아래에 `<product>/<signal>.jsonl` 이 생긴다. */
	private val root: Path,
) : ArchiveWriter {

	override fun write(product: Product, signal: Signal, body: ByteArray) {
		val file = root.resolve(product.archiveSegment).resolve("${signal.fileStem}.jsonl")
		// 현행 설정의 create_directory: true 에 해당한다. 없으면 기동 직후 죽는다.
		Files.createDirectories(file.parent)
		Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { out ->
			out.write(body)
			out.write('\n'.code)
		}
	}
}

/** 현행 파일명과 같은 시그널 구간 — `logs.jsonl` · `traces.jsonl` · `metrics.jsonl`. */
internal val Signal.fileStem: String
	get() = path.removePrefix("/v1/")
