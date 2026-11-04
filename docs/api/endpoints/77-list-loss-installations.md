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
다음 페이지는 동일한 창과 snapshotId를 유지한다. windowEnd는 서버 현재 시각보다 미래일 수 없고
요청 시각부터 10분 이전이면 새 요약을 조회해야 한다. 잘못된 시각·페이지 범위·cursor는 400이다.
기존 cursor의 조직·필터 격리와 snapshot 만료 규칙을 유지한다.

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
`lastObservedAt`은 마지막 증가분의 **서버 확인 시각**이다. [전체 규칙·스키마](../reference/ingest-status.md)를 따른다.
