# 벤더·플랜 카탈로그 — 기능 규칙·공유 스키마

[API 목록](../vendor-catalog.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

## 벤더와 플랜 카탈로그

로그인한 owner/admin에게 제공하는 공통 선택지다. 조직 경로 접두를 붙이지 않는다.
목록과 계약 저장 검증의 원천은 `enrollment.vendor_catalog_vendors`·`vendor_catalog_products`·`vendor_catalog_plans`다.
enrollment-persistence의 `VendorCatalog`가 매 요청 DB에서 활성 제품과 플랜을 읽는다(ADR 0035).
DB 편집은 서버 재시작 없이 반영되며 조직별 등록과 계약 이력은 바뀌지 않는다.
카탈로그 ID는 `claude_team`처럼 제품 종류를 나타내며, 조직에 등록한 vendorId UUID와 다르다.

### 벤더 검색·페이지

`GET /api/v1/vendor-catalog`

쿼리 파라미터:


q는 선택, 최대 200자다. 앞뒤 공백을 제거하고 대소문자를 구분하지 않는 부분 문자열 검색으로
id·provider·displayName·product를 찾는다. limit 기본 20, 범위 1~100이다.
id 오름차순이며 다음 페이지는 같은 q와 응답 nextCursor를 보낸다.
cursor는 검색어·카탈로그 버전·마지막 ID를 담은 불투명 값이다.
`catalogVersion`은 공개 목록 내용의 SHA-256 문자열이다. 클라이언트는 숫자로 해석하지 않는다.
해석 불가·다른 검색어·다른 카탈로그 버전은 400 invalid_request다. 첫 페이지부터 다시 조회한다.

```json
{
  "catalogVersion": "<SHA-256>",
  "items": [{
    "id": "claude_team",
    "provider": "anthropic",
    "displayName": "Claude (Anthropic)",
    "product": "Claude Code · claude.ai",
    "allowsSeatTiers": true
  }],
  "totalCount": 1,
  "nextCursor": null
}
```

items에 플랜 전체나 단가를 포함하지 않는다. totalCount는 검색 결과 전체 개수다.

### 선택한 벤더의 플랜

`GET /api/v1/vendor-catalog/claude_team/plans`


```json
{
  "catalogVersion": "<SHA-256>",
  "vendor": {
    "id": "claude_team", "provider": "anthropic", "displayName": "Claude (Anthropic)",
    "product": "Claude Code · claude.ai", "allowsSeatTiers": true
  },
  "plans": [
    {"id":"team","displayName":"Team","billing":"seat","separateUsageBilling":true},
    {"id":"enterprise","displayName":"Enterprise","billing":"seat","separateUsageBilling":true}
  ]
}
```

없는 제품 ID는 404다. 플랜 ID는 해당 제품 안에서 해석한다(enterprise는 여러 제품에 존재).
카탈로그는 현재 앱에서 지원하는 입력 선택지이며 공급자의 실시간 가격표가 아니다.
제품별 `allowsSeatTiers`의 공식 근거와 플랜·계약별 예외는 [카탈로그 좌석 등급 근거](../../vendor-catalog-evidence.md)에 기록한다. V11부터 OpenAI도 복수 좌석 입력을 허용한다.
벤더별 좌석 조회·회수·복원과 실제 청구액 API의 공식 근거는 [벤더 좌석·청구 API 근거](../../vendor-connector-evidence.md)에 기록한다. 그 문서에 근거가 있는 벤더·기능만 커넥터로 구현한다.
프론트는 벤더 선택 시 조회하고 제품 ID별로 캐싱할 수 있다. 벤더 변경 시 이전 플랜 선택은 해제한다.
단가·좌석 수는 조직이 입력한다. 플랜 선택 없이 벤더만 등록하는 것도 허용한다.
기존 `/settings.catalog`는 호환을 위해 유지하되 같은 정의로 생성한다.


<a id="schema-CatalogVendor"></a>
<a id="schema-VendorCatalogResponse"></a>
<a id="schema-VendorPlansResponse"></a>

```ts
type CatalogVendor = { id: string; provider: string; displayName: string; product: string; allowsSeatTiers: boolean };
type VendorCatalogResponse = { catalogVersion: string; items: CatalogVendor[]; totalCount: number; nextCursor: string | null };
type VendorPlansResponse = {
  catalogVersion: string; vendor: CatalogVendor;
  plans: Array<{ id: string; displayName: string; billing: string; separateUsageBilling: boolean }>;
};
```
