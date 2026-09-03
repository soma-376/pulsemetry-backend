package com.team376.pulsemetry.persistence.telemetry

/**
 * ClickHouse 적재가 실패했다. 조립 앱이 **503** 으로 매핑한다.
 *
 * ## 4xx 까지 이 타입이다 — 의도된 비대칭이다
 *
 * 400·401·403 처럼 보통은 영구 오류인 응답까지 전부 여기로 묶는다. 영구 오류(400)로
 * 돌려보내면 업스트림이 배치를 **폐기**하므로, 유실 없는 쪽으로 치우친 것이다. 이식 원본의
 * 오류 분류 테스트가 *"고치지 말고 그대로 고정한다"* 로 못박은 동작이다.
 *
 * **대가도 같이 온다** — 진짜 스키마 불일치(ClickHouse 400)가 빠르게 죽지 않고 재시도로
 * 맴돈다. 보강 단계의 `EnrichmentUnavailableException` 이 정반대로 좁은 것과 대비된다.
 *
 * ⚠️ 이 편향은 **업스트림이 재시도한다**는 전제 위에 있는데, 단일 앱 토폴로지(허브 ADR 0005)가
 * OTel Collector 를 걷어내면서 그 전제가 사라진다. 재시도·백프레셔 정책을 다시 정하는 것은
 * 허브 ADR 0005 Follow-up 이 별도 결정으로 지정했다 — 그때 이 분류도 함께 본다.
 */
public class TelemetrySinkUnavailableException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)
