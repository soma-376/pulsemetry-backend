package com.team376.pulsemetry.persistence.telemetry

/**
 * ClickHouse 가 요청 자체를 거부했다 — **영구 오류**다. 조립 앱이 **400** 으로 매핑한다.
 *
 * 구문 오류(400) · 인증 실패(401·403) · 없는 테이블(404) 처럼 같은 배치를 다시 보내도 같은 답이
 * 오는 응답이 여기 온다. 과부하 계열(`429`)과 타이밍 계열(`408`)은 여기가 아니라
 * [TelemetrySinkUnavailableException] 이다.
 *
 * ## 전에는 이것도 503 이었다
 *
 * 이식 원본은 ClickHouse 의 모든 HTTP 오류를 일시 장애로 올려 503 을 돌려줬다. 유실보다
 * 재시도를 택한 편향이었고, **업스트림 collector 가 5분 예산으로 재시도한다**는 전제 위에 섰다.
 * 단일 앱 토폴로지(허브 ADR 0005)가 그 collector 를 걷어내면서 전제가 사라졌다 —
 * 이제 상태 코드를 읽는 것은 telemetryctl 데몬이고, 그쪽은 5xx 를 세 번만 재시도한다.
 * 세탁해서 사는 것이 재시도 두 번뿐이고 잃는 것은 "스키마 오류가 영원히 드러나지 않는 것"이라
 * 거래가 성립하지 않는다.
 *
 * **분류를 다시 넓히지 마라.** 근거는 허브 ADR 0006 이고, 그 결정 없이 되돌리면 스키마 불일치가
 * 다시 재시도로 맴돈다.
 */
public class TelemetrySinkRejectedException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)
