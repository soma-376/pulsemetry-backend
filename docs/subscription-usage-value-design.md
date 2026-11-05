# 구독료 대비 사용 환산액의 백엔드 구현 인계

## 현재 상태

이 문서는 [ADR 0078](adr/0078-세션-집계는-보존하고-계약액-비율은-효력-이력에-근거해-별도로-설계한다.md)의 후속 설계다. 제품·상태·API 원안은 [문서 허브의 카드 계약](https://github.com/soma-376/docs/blob/main/contracts/subscription-usage-value.md)에 있다. PROJ-318은 명세만 작성하며 `subscriptionUsageValue`, 계약 이력 명령, 스키마를 구현하지 않는다. 현행 API 응답에 이 필드를 기대해서는 안 된다.

## 1. 기존 구현과 재사용 한계

| 근거 | 현재 동작 | 후속 설계 제약 |
|---|---|---|
| [VendorCommands](../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/vendor/application/VendorCommands.kt) | 최초 계약은 과거 시작을 거부하고 기존 계약 정정은 이전 effectiveFrom을 유지한다. tiers의 fee × seats 합을 monthlySeatFeeUsd로 저장한다 | 과거 확인·실제 변경 효력 입력에는 별도 명령이 필요하다. 월 총액에 좌석 수를 다시 곱하지 않는다 |
| [V6 조직 관리 스키마](../libs/enrollment-persistence/src/main/resources/db/migration/V6__organization_management.sql)·[VendorCommandStore](../libs/enrollment-persistence/src/main/kotlin/com/team376/pulsemetry/persistence/enrollment/vendor/VendorCommandStore.kt) | contract JSON·version·archived·recorded_at/by를 append하고 최신 판을 조회한다 | 계약 JSON의 기간은 존재하지만 판별 금액·좌석 효력과 정정 종류는 없다. recorded_at으로 효력 구간을 자동 복원하지 않는다 |
| [ContractStatus](../libs/enrollment-domain/src/main/kotlin/com/team376/pulsemetry/domain/enrollment/vendor/ContractStatus.kt) | 계약 effectiveTo 날짜까지 유효하다 | 새 exclusive 구간으로 옮길 때 포함 종료일 다음 날로 명시 변환한다 |
| [RegisteredProducts](../libs/enrollment-persistence/src/main/kotlin/com/team376/pulsemetry/persistence/enrollment/seat/RegisteredProducts.kt) | 현재 등록·보관 상태와 최신 계약을 조회한다 | 과거 기간 분모의 계약 조회로 그대로 쓰지 않는다 |
| [SettingsValues](../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/settings/SettingsValues.kt) | 현재 active 계약의 이미 합산된 monthlySeatFeeUsd를 더한다 | 현재 월 합계의 기존 의미를 유지한다. 날짜 선택 카드의 분모로 사용하지 않는다 |
| [명시 제품 매핑](../libs/enrollment-persistence/src/main/resources/db/migration/V18__vendor_catalog_observed_products.sql) | claude_code→claude_team, codex→openai_biz | provider·모델 이름 추정 없이 같은 매핑 판을 snapshot에 고정한다 |
| [Money](../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/Money.kt) | format은 최소 6자리이며 더 정밀한 BigDecimal을 보존한다. share는 DECIMAL64 계산이다 | format을 일할 나눗셈 도구로 쓰지 않는다. 반복소수·비율에는 정확 분수 계산이 필요하다 |

`monthlySeatFeeUsd` 입력은 최대 소수 12자리이고 월 총액 상한은 9,007,199,254,740,991달러다. 이 범위에서도 하루 배분액은 12자리보다 작을 수 있다. 기존 금액 문자열 포맷터 전체를 바꾸지 않고 새 카드의 계산과 최종 직렬화를 분리한다.

`meteredMonthToDate`는 벤더 청구 누계다. 새 카드의 저장된 관측 환산액 또는 배분 계약액으로 채우거나 의미를 바꾸지 않는다. 수동 계약·좌석 원장·관측 사용·벤더 청구의 원천 구분을 유지한다.

## 2. 효력 이력 저장·명령 인계

허브 계약의 제품별 immutable timeline revision을 enrollment가 소유한다. revision은 전체 효력 구간과 직전 판, 실제 변경/정정/과거 확인, 기록 시각과 확인 근거를 가진다. 물리 DDL과 endpoint 경로는 후속 구현에서 정하며 이 티켓에서 migration 번호나 API 번호를 예약하지 않는다.

순수 domain 규칙은 날짜 순서, 구간 중복, tiers 합계, 실제 변경의 효력일, 정정의 대상 구간, 근거의 존재를 검증한다. 영속 어댑터는 조직·제품의 현재 revision을 잠그거나 expectedRevisionId로 비교하여 append를 원자적으로 수행한다. 같은 제품의 여러 구매분은 확인된 총액으로 하나의 timeline에 정규화하며 최신 버전 여러 개를 중복 합산하지 않는다.

실제 변경 예시는 11월 1~15일 월 300달러, 16일부터 월 600달러다. 새 revision은 `[11/01,11/16)`과 `[11/16,12/01)`을 가진다. 11월 20일에 입력해도 20일부터로 바꾸지 않는다. 반대로 원래 월 300달러라고 잘못 입력한 것을 600달러로 정정하면 대상 효력 구간을 600달러로 고친 새 revision을 만들고 이전 판을 보존한다.

계약이 없었다는 사실도 `verified_no_purchase` 구간과 근거로 확인한다. 이력이 없는 기간은 무료로 취급하지 않는다. 기존 JSON의 포함 종료일은 확인 후 하루를 더하여 exclusive 끝으로 바꾼다. 기존 수정 시각을 효력일로 사용하는 자동 백필은 금지한다.

후속 계약 UI는 새 명령을 사용한다. 기존 PUT/삭제와 다른 쓰기가 새 이력을 우회할 때의 보호도 함께 구현해야 한다. 효력 정보 없는 금액·좌석·기간·계약 삭제는 같은 트랜잭션에서 `needs_confirmation`을 남긴다. 새 snapshot은 확인되지 않은 timeline을 정확한 분모로 채택하지 않는다. 표시 이름만 바꾸는 쓰기는 금액 이력을 무효화하지 않는다. 제품을 현재 보관했다는 이유만으로 과거 확인 구간을 제거하지 않는다.

dashboard는 원천 이력을 읽으며 자기 snapshot/cache만 쓴다. 이력 수정·확인 명령, 원천 마이그레이션과 감사 기록은 enrollment 소유다. 원천 SELECT allowlist 변경이 필요하면 후속 infra 작업으로 인계한다. PROJ-318은 DB 권한을 늘리지 않는다.

## 3. 불변 입력과 계산 순서

1. 기존 조직 인가와 KST 1~366일 요청을 검증한다. 요청 날짜를 exclusive instant 범위로 바꾸고 동일 snapshot asOf로 실제 끝을 자른다. 전체 미래면 값·행 수 null/upcoming이다.
2. 원본 사용 행을 한 snapshot으로 고정하고 `source_time >= start AND source_time < min(end, asOf)`를 적용한다. 단가가 있는 행은 저장 USD를 합산한다. 세션 식별자 결손을 비용 결손으로 전파하지 않는다.
3. 같은 원천 읽기에서 제품 매핑·timeline revision·구간·확인 상태를 선택하고 해당 ID와 내용을 snapshot에 보존한다. 현재 active 계약만 조회하지 않는다. 카드 상세와 export도 그 snapshot을 사용한다.
4. 지원 제품의 관측·구매·미확인 등록 대상을 합쳐 scope를 만든다. 대상 계약이 누락되어도 제품을 제거하지 않는다. 관측 미지원 제품만 양쪽에서 제외 사유와 함께 뺀다. 제품 열거 또는 매핑 실패로 집합이 미확정이면 scope_incomplete로 조직 금액·행 수·비율을 모두 null로 제한하고 확인된 제품 근거만 보존한다.
5. 각 대상 제품이 실제 계산 구간 전체를 확인된 구매 또는 미구매 구간으로 덮는지 검사한다. 공백·중복·needs_confirmation은 전체 분모 null이다. 확인된 조각은 근거에 남기되 부분 분모로 조직 비율을 만들지 않는다.
6. KST 월 경계와 효력 경계로 자른 조각마다 `monthlyUsd × elapsedNanoseconds / (daysInMonth × 86400 × 10^9)`를 계산한다. 제품별·전체 합은 정확한 분수로 더한다.
7. PROJ-315 공통 정책 adapter의 snapshot 고정 입력으로 정상 빈 관측과 근거 없음을 구분한다. 현재 heartbeat로 독자 판정하지 않는다. 확인된 사용은 보고 공백 annotation과 별도로 보존한다.
8. 정확 분모가 양수이고 분자가 계산 가능하면 정확한 비율을 구한다. 모든 제품의 관측 상태가 확인된 경우 전체 priced/unpriced 행 수로 부분 미환산과 전부 미환산을 구분한다. 한 제품의 no_evidence/query_failed/pending은 조직 분자·행 수·비율을 null로 제한하며 정상 제품의 값은 제품별로 남긴다. 사용 원천·계약 원천 실패 시 반대편 조직 금액 보존은 scope가 확정된 경우에만 허용한다.
9. exact 분수·magnitude·표시 문자열·Value 상태를 함께 직렬화한다. 직렬화한 금액이나 조각을 다시 계산 입력으로 쓰지 않는다.

PROJ-317 auto snapshot의 query_contract와 asOf 고정은 참조할 수 있으나 카드 출시에는 day/auto 모두의 계약 근거가 필요하다. 현재 day 계약이나 snapshot manifest를 이 설계 문서만으로 변경하지 않는다. 후속 배선은 새 계산 판과 원천 선택을 캐시 키에 포함하고 과거 호환 snapshot을 재사용하지 않아야 한다.

## 4. 정확 수와 API 표현

분수는 부호 있는 임의 정밀도 정수 numerator와 양수 denominator로 정규화한다. USD 입력은 decimal 계수/10^scale이다. duration은 정수 나노초다. 전체·제품·월 조각의 값은 각각 정확 분수에서 직렬화하며 중간 BigDecimal.divide의 scale 제한으로 손실시키지 않는다.

후속 API의 USD 대표값은 최종 scale 12·HALF_EVEN이다. 두 자리 표시 금액은 정확 분수에서 별도로 반올림한다. exactAmounts는 정수 문자열 쌍, amountMagnitude는 정확값에 근거한 zero/positive_below_cent/at_least_cent다. 금액 대표값이 0이어도 exact numerator가 양수이면 무료·zero_denominator가 아니다.

비율은 반올림 전 분모로 계산하고 최종 소수 한 자리다. 최종 반올림 비율의 절댓값이 `2^49 = 562,949,953,421,312` 미만일 때만 JSON number를 반환한다. 이 범위는 binary64 오차를 0.05 미만으로 제한하여 한 자리 표시를 보존한다. 경계 이상이면 ratio_out_of_range와 null을 반환하며 정확 비율 근거를 보존한다. 10배 값의 MAX_SAFE_INTEGER 검사만으로는 충분하지 않다. HTTP JSON에 NaN·Infinity나 포화 상한값을 보내지 않는다.

예: 월액 `0.000000000001`을 31일 중 하루에 배분하면 `1/31000000000000` USD다. 사용액 `0.000000000001`이면 3100.0%다. 계약액 대표 문자열 `0.000000000000`은 계산 결과의 반올림 표현일 뿐이며 화면은 ‘$0.01 미만 (양수)’와 정확 분수 근거를 사용한다.

scope가 확정됐으면 관측 금액을 못 구해도 계약액은 별도 Value로 보존하고, 계약액을 못 구해도 관측 금액은 보존한다. `ratioPercent`만 null이라는 이유로 두 금액을 함께 지우지 않는다. scope가 미확정이면 성공한 제품의 금액을 조직 전체 금액으로 승격하지 않고 제품별 근거로 남긴다. priced 행 금액 0과 unpriced 행이 섞이면 partial 0%이며 ‘관측된 AI 사용 없음’은 아니다.

## 5. 후속 구현 인수 기준

| 범위 | 필요한 검증 |
|---|---|
| 이력 명령 | 조직 격리, 관리자 인가, expectedRevisionId 충돌, 중복 구간 거부, 정정/실제 변경/과거 확인, legacy 우회 쓰기의 재확인 표시 |
| 과거 사실 | recorded_at 자동 효력 변환 없음, 확인된 미구매와 공백 구분, 현재 보관·만료 제품의 과거 계약 보존, 동일 제품 중복 합산 없음 |
| 시간 | KST 포함 요청일·exclusive 끝, asOf와 같은 사용 행 제외, 정오·소수 초 배분, 월중 변경·월 경계·윤년, 전체 미래 |
| 수치 | 계약액 100·사용액 250→250%, 11월 300/30→10, 월중 300→600의 분모 450, 윤년 경계 분모 30, 정오 분모 5, 작은 양수 3100%, 합산 후 반올림·비율 안전 범위 |
| 제품 | 사용 없는 구매 제품 분모 포함, 지원 제품 계약 누락 시 null, 미지원 제품 양쪽 제외, 세션·회원 미식별 관측 포함 |
| 상태 | 부분/전체 미환산, 부분합 0, 정상 관측 빈 기간, no_evidence/pending/query_failed, 정확 0 분모, 한쪽 조회 실패 시 다른 금액 유지 |
| snapshot | 생성 중 정정·카탈로그 변경·늦은 적재가 기존 응답에 섞이지 않음, 상세/CSV와 같은 판, 조직별 캐시·페이지 격리 |

숫자 사례는 설계 검산이며 API·DB 구현 테스트 통과를 뜻하지 않는다. 기존 세션 집계/API와 현재 계약 API를 바꾸지 않은 채 후속 구현을 시작할 수 있어야 한다.
