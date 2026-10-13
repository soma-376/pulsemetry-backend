# 설정 화면 — 기능 규칙·공유 스키마

[API 목록](../settings.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### SettingsCapabilities

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-SettingsCapabilities"></a>

```ts
type SettingsCapabilities = {
  editContracts: boolean;
  editCollectionPolicy: boolean;
  editAlertRules: boolean;
  notifyInstallations: boolean;
};
```

### SettingsSummary · SettingsVendorRow

<a id="schema-SettingsSummary"></a>
<a id="schema-SettingsVendorRow"></a>

```ts
type Value<T> = { value: T | null; status: "complete" | "partial" | "unavailable"; reasons: string[] };
type SettingsVendorRow = {
  vendorId: string;
  displayName: string;
  kind: string;
  source: string; // 온보딩 목록의 수동 등록 필터
  version: number; // 삭제 명령의 충돌 확인
  contractStatus: "missing" | "scheduled" | "active" | "expired";
  contractInput: { status: "required" | "complete"; missingFields: string[] };
  contractSeats: Value<number>;
  monthlySeatFeeUsd: Value<string>;
  assignedSeats: Value<number>;
};
type SettingsSummary = {
  unconfiguredVendors: Value<number>;
  monthlySeatFeeUsd: Value<string>;
  contractedSeats: Value<number>;
  activeSeats7d: Value<number>;
  assignedSeats: Value<number>;
  meteredMonthToDate: Value<string>;
};
```

- 값이 없으면 `unavailable`과 사유를 보낸다. 정상 0은 `complete`, 근거가 일부인 숫자는 `partial`이다.
- 월 좌석료·계약 좌석은 유효 계약만 더한다. 미입력·만료·예정 계약 제외 사유는 `contract_missing`·`contract_expired`·`contract_scheduled`이다.
- 배정 좌석은 쓸 수 있는 원장만 더하고 누락·동기화 실패·낡음의 원천 사유를 보존한다. 활성 좌석은 최근 7일의 수집 불완전, 미연결 좌석, 관측 불가 제품을 구분한다.
- 종량 지출은 벤더 청구 누계만 센다. 일부 금액 누락이나 기간 불일치면 합계 값은 null이다. 낡은 청구 누계가 남아 있으면 숫자를 유지하며 `partial`이다.
- 목록 행의 만료·예정 계약 수량과 금액은 마지막·예정 계약 값이며 `contractStatus`로 구분한다. 활성 계약 합계와 섞지 않는다.

### SettingsResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-SettingsResponse"></a>

```ts
type SettingsResponse = {
  meta: CurrentMeta;
  capabilities: SettingsCapabilities;
  summary: SettingsSummary;
  vendors: Page<SettingsVendorRow>;
  collectionPolicy: CollectionPolicy;
  policyRollout: PolicyRollout;
  alertRules: Array<AlertRule>;
};
```


## 화면에서 이어지는 기능

설정 첫 조회는 카드 값·등록 제품 목록 첫 페이지·정책·적용 현황·알림 규칙을 함께 반환한다.
`GET /vendors`도 같은 경량 행을 이어 받는다. `GET /vendors/{id}`의 계약 등급·관측·좌석 원천·청구 상세 및 쓰기 응답은 유지한다.
헤더와 중복되는 ingest, 별도 카탈로그, 화면에 없는 감지 제품·미분류 관측은 설정 응답에서 제거했다.
수집 정책의 마지막 수신 시각은 프론트가 공통 `ingest-status` 조회를 공유한다.
CSV도 카드·목록 값과 상태/사유만 포함하며 상세 계약 등급은 내보내지 않는다.
기존 조회 실패는 페이지 공통 새로고침으로 재시도한다. 원천의 부분 가용성과 HTTP 조회 실패를 같은 상태로 취급하지 않는다.
추가 조회·변경의 정식 위치는 [제품·계약](../vendors.md), [벤더 연결](../vendor-connections.md), [좌석](../seats.md),
[수집 정책](../collection-policy.md), [설치 현황](../installations.md), [알림](../alerts.md)이다.
조회 기간 필터는 설정 첫 조회에 적용되지 않는다. 저장 응답만으로 화면 전체를 대체하지 말고 관련 조회를 갱신한다.
계약 만료 안내는 공통 알림함의 [현재 계약 입력·만료 상태](contract-input-status.md) 조회로 제공한다. ‘계약 갱신’은 해당 벤더의 계약 편집 폼으로 연결하며, 저장 후 이 조회를 포함한 조직 캐시를 무효화한다. 설정 상단에 별도 만료 안내 섹션은 두지 않는다.


## 연관 스키마

- [AlertRule](alerts.md#schema-AlertRule)
- [CollectionPolicy](collection-policy.md#schema-CollectionPolicy)
- [CurrentMeta](../common-schemas.md#schema-CurrentMeta)
- [Page](../common-schemas.md#schema-Page)
- [PolicyRollout](installations.md#schema-PolicyRollout)
- [Section](../common-schemas.md#schema-Section)
- [Vendor](vendors.md#schema-Vendor)

## 전체 JSON 응답 예시

[SettingsResponse 예시](../examples/settings-response.example.json)는 가상 데이터이며 현재 DTO의 전체 키를 포함한다. 실서버 응답 캡처나 운영 검증 결과가 아니다.
