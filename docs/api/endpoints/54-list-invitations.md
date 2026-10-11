# 54 GET `/api/v1/organizations/{organizationId}/invitations`

초대 목록

[전체 API](../README.md) · [초대·설치 코드](../invitations.md)

<!-- endpoint: enrollment-api GET /api/v1/organizations/{organizationId}/invitations -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

## Request

### Path

```ts
{
  organizationId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Query

```ts
{
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  status?: "pending" | "expired" | "used" | "revoked"; // 생략: 전체
  memberStatus?: "invited" | "active" | "suspended";
}
```

## Response

```ts
// InvitationPage
{
  items: {
    invitationId: string; email: string; role: string;
    createdAt: string; expiresAt: string;
    installationUsedAt: string | null; signupUsedAt: string | null;
    revokedAt: string | null;
    status: "pending" | "expired" | "used" | "revoked";
    memberId: string;
    memberStatus: "invited" | "active" | "suspended";
    team: { teamId: string; teamName: string } | null;
    memberVersion: number;
    plannedVendorIds: string[];
    delivery: Delivery;
  }[];
  nextCursor: string | null;
}
```

[InvitationPage 전체 스키마·중첩 타입](../reference/invitations.md#schema-InvitationPage)

cursor는 UUID. Dashboard snapshot 목록과 다르게 실시간 조회한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/invitations.md)를 함께 적용한다.
