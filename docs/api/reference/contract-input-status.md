# 현재 계약 입력·만료 상태

`GET /api/v1/organizations/{organizationId}/contract-input-status`

설정과 같은 조직 권한을 적용한다. 기간 파라미터·snapshot은 사용하지 않는다. 조직에 등록됐고 보관되지 않은 벤더의 최신 저장 계약 원천을 한 번 조회해 미입력과 만료 목록을 만든다. 관측·좌석·청구·활성 manifest를 조회하지 않는다.

```json
{
  "organizationId": "org-id",
  "asOf": "2026-10-12T00:00:00Z",
  "requiredCount": 1,
  "vendors": [{
    "vendorId": "vendor-id",
    "displayName": "벤더 이름",
    "missingFields": ["planId", "seats", "monthlyFeePerSeatUsd"]
  }],
  "expiredCount": 1,
  "expiredContracts": [{
    "vendorId": "expired-vendor-id",
    "displayName": "만료된 벤더 이름",
    "endDate": "2026-09-30"
  }]
}
```

- 계약은 필수 항목을 모두 검증해 저장하므로 계약 null이면 세 항목 모두 미입력이다. 초안은 저장하지 않는다.
- 만료·시작 예정·0달러 계약도 입력 완료이며, 미등록 관측을 계약 대상에 추측해 넣지 않는다.
- `requiredCount/vendors`는 계약 미입력, `expiredCount/expiredContracts`는 만료만 나타낸다. 같은 계약을 두 목록에 중복하지 않는다.
- 만료 판정은 KST 현재 날짜로 한다. 종료일(`endDate`) 당일까지 유효하고 다음 날부터 만료다. 계약 null·시작 예정·종료일 없음은 만료가 아니다.
- `expiredContracts`는 벤더마다 최신 저장 계약 한 건만 포함하며 종료일 → 표시 이름 → 벤더 ID 오름차순이다. 최신 계약을 갱신·삭제하거나 벤더를 보관하면 다음 조회에 즉시 반영한다. 만료 이력이나 사용자 확인 기록은 만들지 않는다.
- 0건은 각각 `vendors: []`, `expiredContracts: []`다. 읽기 실패는 HTTP 오류이며 0건 응답으로 바꾸지 않는다.
- 설정의 목록·상세 Vendor에는 `contractInput: {status: "required" | "complete", missingFields: string[]}`를 추가한다.
- 헤더의 계약 입력 안내는 `/settings?vendor={첫 미입력 vendorId}`로 연결한다. 만료 계약은 공통 알림함에 벤더별로 표시하며 ‘계약 갱신’은 `/settings?vendor={vendorId}`의 해당 계약 폼을 연다. 저장 성공 후 조직 범위 캐시를 무효화한다.
- 알림 벨은 미확인 규칙 알림 수에 `expiredCount`를 더한다. 규칙 알림과 계약 상태 중 하나라도 조회하지 못했다면 전체 0건으로 표시하지 않는다. 기존 `/alerts`·확인 명령·운영 CSV에는 만료 계약을 섞지 않는다.

근거: 허브 ADR 0018·0020. 기간 완전성·사용 환산액과 독립적이다. `expiredCount/expiredContracts`는 기존 응답에 추가한 필드이며 프론트와 백엔드를 함께 반영한다.
