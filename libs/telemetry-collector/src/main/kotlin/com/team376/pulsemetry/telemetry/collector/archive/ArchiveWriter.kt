package com.team376.pulsemetry.telemetry.collector.archive

import com.team376.pulsemetry.telemetry.collector.Signal

/**
 * 마스킹을 마친 원본을 외부 저장소에 쓴다.
 *
 * 허브 [`architecture/overview.md`] 3절이 Raw Signal Object Storage 의 쓰기를 Masker 에게 주었고,
 * 이 저장소에서는 그 자리가 `telemetry-collector` 의 `archive` 패키지다(`docs/module-map.md` 5절).
 * **적재 모듈로 미룰 수 없다** — 변환이 실패해도 원본이 남아 있어야 재처리(흐름 D)의 복구 원천이
 * 성립한다.
 *
 * ## 여기 오는 것은 이미 마스킹을 마친 데이터다
 *
 * 허브 `glossary.md` 가 못박은 대로 "raw" 는 **가공 전**이지 마스킹 전이 아니다.
 * 단 **metrics 는 예외다** — 현행 설정의 metrics 파이프라인에 `redaction/secrets` 가 없어서
 * 마스킹을 거치지 않은 채 여기로 온다(허브 계약 §5 의 M6, `Signal.METRICS.masked = false`).
 * 이식은 동작 동일성이 기준이라 고치지 않았다. 보존 기간이 있는 저장소에 쓰는 구현이라면
 * 그 사실이 곧 위험이므로 ADR 0012 의 Negative 가 이것을 적어 두고 있다.
 *
 * ## 구현이 둘인 이유
 *
 * 배포는 [S3ArchiveWriter], 로컬 dev·테스트는 [FileArchiveWriter] 다. 어느 쪽을 쓸지는
 * **조립 앱이 정한다**(ADR 0011 — 라이브러리는 빈을 등록하지 않는다).
 */
public interface ArchiveWriter {

	/**
	 * 한 번의 수신을 아카이브 한 건으로 쓴다.
	 *
	 * @param product 제품 구간. [ProductRouter] 가 resource 의 `service.name` 으로 골랐다.
	 * @param signal 시그널 구간.
	 * @param body OTLP/JSON 로 직렬화한 문서 하나. 현행 file exporter 의 `format: json` 과 같다.
	 */
	public fun write(product: Product, signal: Signal, body: ByteArray)
}
