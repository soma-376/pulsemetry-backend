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
 * 세 시그널 모두 마스킹을 거친 뒤 여기 온다 — metrics 가 마스킹 없이 오던 결함(허브 계약 §5 의 M6)은
 * 해소했다(ADR 0012 Follow-up).
 *
 * ## 구현이 둘인 이유
 *
 * 배포는 [S3ArchiveWriter], 로컬 dev·테스트는 [FileArchiveWriter] 다. 어느 쪽을 쓸지는
 * **조립 앱이 정한다**(ADR 0011 — 라이브러리는 빈을 등록하지 않는다).
 */
public interface ArchiveWriter {

	/**
	 * 한 번의 수신을 아카이브 한 건으로 쓰고, **실제로 쓴 위치**를 돌려준다.
	 *
	 * 쓰기에 실패하면 예외를 던진다 — 위치를 추정해 돌려주지 않는다. 호출자는 그 예외로 503 을 낸다
	 * (ADR 0020 §6).
	 *
	 * @param product 제품 구간. [ProductRouter] 가 resource 의 `service.name` 으로 골랐다.
	 * @param signal 시그널 구간.
	 * @param body OTLP/JSON 로 직렬화한 문서 하나. 현행 file exporter 의 `format: json` 과 같다.
	 */
	public fun write(product: Product, signal: Signal, body: ByteArray): ArchivedObject
}
