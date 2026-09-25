package com.team376.pulsemetry.telemetry.collector.archive

import com.team376.pulsemetry.telemetry.collector.Signal
import java.time.Instant

/**
 * 아카이브에 **실제로 쓴** 객체 하나의 위치. [ArchiveWriter.write] 가 쓰기에 성공한 뒤에만 돌려준다.
 *
 * 쓰기에 실패하면 예외이고 위치는 없다 — 추정한 key 나 가상의 참조를 발급하지 않는다(ADR 0020 §6).
 */
public class ArchivedObject(
	/** 객체의 URI. S3 는 `s3://<bucket>/<key>`, 파일은 `file:` URI 다. */
	public val uri: String,
	/** 객체 안에서 이 문서가 시작하는 바이트. 객체 전체가 문서 하나면 null 이다. */
	public val byteOffset: Long? = null,
	/** 문서의 바이트 길이(줄바꿈 제외). [byteOffset] 이 null 이면 null 이다. */
	public val byteLength: Long? = null,
)

/**
 * 아카이브 문서 안에서 레코드 하나를 다시 찾는 규칙.
 *
 * [path] 는 아카이브된 **문서 안의** 0 기반 인덱스다 — logs 는 `resource/scope/logRecord`, traces 는
 * `resource/scope/span`, metrics 는 `resource/scope/metric/dataPoint`. 문서가 객체의 일부이면
 * (파일 아카이브의 한 줄) [byteOffset]·[byteLength] 가 그 문서를 가리킨다.
 *
 * 문자열 표기는 `bytes=<offset>+<length>;path=<i>/<j>/...` 이고, 객체 전체가 문서면 `bytes=` 부분이 없다.
 * 분석 행의 `archive_selector` 가 이 표기다.
 */
public data class ArchiveSelector(
	public val byteOffset: Long?,
	public val byteLength: Long?,
	public val path: List<Int>,
) {
	init {
		require((byteOffset == null) == (byteLength == null)) { "byteOffset 과 byteLength 는 함께 있거나 함께 없다" }
		require(path.isNotEmpty() && path.all { it >= 0 }) { "path 는 비어 있지 않은 0 이상 인덱스다: $path" }
	}

	public fun format(): String {
		val bytes = if (byteOffset == null) "" else "bytes=$byteOffset+$byteLength;"
		return bytes + "path=" + path.joinToString("/")
	}

	override fun toString(): String = format()

	public companion object {
		private val FORMAT = Regex("""^(?:bytes=(\d+)\+(\d+);)?path=(\d+(?:/\d+)*)$""")

		/** [format] 의 역. 형식이 아니면 예외다. */
		public fun parse(text: String): ArchiveSelector {
			val match = FORMAT.matchEntire(text) ?: throw IllegalArgumentException("selector 형식이 아니다: $text")
			val (offset, length, path) = match.destructured
			return ArchiveSelector(
				byteOffset = offset.takeIf { it.isNotEmpty() }?.toLong(),
				byteLength = length.takeIf { it.isNotEmpty() }?.toLong(),
				path = path.split('/').map { it.toInt() },
			)
		}
	}
}

/**
 * 제품 구간 하나로 갈라 쓴 아카이브 문서.
 *
 * 다음 단계가 받는 요청은 **가르기 전의 전체** 요청이다. [resourceIndexes] 는 이 문서에 담긴 resource 가
 * 전체 요청에서 몇 번째였는지를 문서 안 순서대로 적은 것이라, 전체 요청 기준 경로를 문서 기준 selector 로
 * 옮길 수 있다([selectorOf]).
 */
public class ArchivedDocument(
	public val product: Product,
	public val location: ArchivedObject,
	public val resourceIndexes: List<Int>,
) {
	/**
	 * 전체 요청 기준 경로(첫 원소가 resource 인덱스)의 레코드가 이 문서에 있으면 그 selector, 아니면 null.
	 */
	public fun selectorOf(requestPath: List<Int>): ArchiveSelector? {
		val position = resourceIndexes.indexOf(requestPath.first())
		if (position < 0) return null
		return ArchiveSelector(location.byteOffset, location.byteLength, listOf(position) + requestPath.drop(1))
	}
}

/**
 * 한 번의 수신(push)에 대한 아카이브 영수증. 수집 단계가 아카이브를 마친 뒤 다음 단계에 넘긴다(ADR 0020 §6).
 *
 * 분석 행의 `archive_ref`·`archive_selector` 와 수신 ledger 행은 이 값에서만 나온다(ADR 0021).
 * 아카이브가 실패하면 영수증은 없고 다음 단계도 불리지 않는다 — 503 이다.
 */
public class ArchiveReceipt(
	/** 서버가 수신마다 만드는 식별자(UUID). HTTP 재전송은 새 영수증이다. */
	public val receiptId: String,
	/** 서버 시계의 수신 시각. 요청이 진입점에 닿은 때다. */
	public val receivedAt: Instant,
	public val signal: Signal,
	/** 아카이브된 원본에 심은 검증된 신원. 인증을 세우지 않은 경로면 null 이다. */
	public val tenantId: String?,
	public val installationId: String?,
	/** 이 push 에 적용한 마스킹 정책의 버전(`MaskingPolicy.VERSION`). */
	public val maskingVersion: String,
	/**
	 * 검증된 신원을 원본에 심은 규칙(ADR 0016)의 버전. 재처리가 원래 신원 문맥을 재현하는 데 쓴다.
	 * 관측 ID 의 `identity_version`(ADR 0020 §2 — 정규화 단계가 정한다)과는 다른 값이다.
	 */
	public val identityVersion: String,
	/** 제품 구간별로 쓴 문서. 요청의 모든 resource 가 정확히 한 문서에 들어 있다. */
	public val documents: List<ArchivedDocument>,
) {
	/** 전체 요청 기준 경로의 레코드를 담은 문서와 그 안의 selector. 어느 문서에도 없으면 null. */
	public fun selectorOf(requestPath: List<Int>): Pair<ArchivedDocument, ArchiveSelector>? =
		documents.firstNotNullOfOrNull { document -> document.selectorOf(requestPath)?.let { document to it } }
}
