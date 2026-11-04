# 77 GET `/api/v1/organizations/{organizationId}/ingest-status/loss-installations`

최근 168시간 보고된 손실의 영향 기기를 조회한다.

[전체 API](../README.md) · [수집 상태](../ingest-status.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/ingest-status/loss-installations -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/ingest/IngestStatusController.kt)

## Request

### Path

```ts
{ organizationId: string }
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Query

```ts
{
  windowEnd: string; // 필수, 손실 요약이 반환한 UTC ISO 8601 기준 시각
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 후속 페이지: 이전 nextCursor
  snapshotId?: string; // 후속 페이지: 이전 meta.snapshotId
}
```

창은 `[windowEnd − 168시간, windowEnd)`이며 분석 날짜·팀·벤더 필터를 받지 않는다.
windowEnd는 헤더가 반환한 값이어야 한다. 최초 기기별 집계와 요약을 10분간 고정하며 다음 페이지는
동일한 창·snapshotId의 cache payload를 읽는다. 늦게 commit된 증가분은 이 결과를 변경하지 않는다.
해당 cache가 없거나 만료됐으면 409 `snapshot_expired`로 새 헤더 조회를 요구한다.
미래 windowEnd·잘못된 시각·페이지 범위·cursor는 400이다. 기존 cursor의 조직·필터 격리를 유지한다.

## Response

```ts
{
  meta: { organizationId: string; asOf: string; snapshotId: string };
  windowStart: string; windowEnd: string;
  installations: {
    items: {
      installationId: string; deviceName: string | null;
      memberId: string | null; account: string | null;
      payloadCount: number; lastObservedAt: string; status: "active" | "revoked";
    }[];
    totalCount: number; nextCursor: string | null;
  };
}
```

양수 손실을 기록한 설치만 포함하며 설치가 폐기되었어도 `revoked`로 남긴다.
기기명·구성원·설치 상태는 `windowEnd`의 최초 조회 시점 기준이며 최대 10분간 고정된다.
그 뒤의 폐기·기기 정보 변경은 새 헤더 조회에서 반영한다.

해당 snapshot의 `availability=unavailable`이면 상세는 503 `unavailable` 오류로 거부한다.
`query_failed`뿐 아니라 `no_observation` 등 수량이 null인 결과를 빈 목록 0건으로 바꾸지 않는다.
`complete` 또는 `partial`이고 확인된 손실 수량이 0이면 200의 빈 목록(`items=[]`, `totalCount=0`)을 반환한다.
`lastObservedAt`은 마지막 증가분의 **서버 확인 시각**이며 `meta.asOf`는 `windowEnd`다. [전체 규칙·스키마](../reference/ingest-status.md)를 따른다.
