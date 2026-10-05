# 0045. 분석 snapshot 은 관측 제품 매핑을 복제하고 제품별 사용을 가산 필드로 낸다

## Status

Proposed — [ADR 0044](0044-관측-제품과-등록-제품은-카탈로그의-명시-매핑으로만-잇고-벤더-관측은-조회-기준-시각에-고정한다.md)(관측 제품과 등록 제품의 명시 매핑)가 Accepted가 되면 Accepted.

## Context

개요와 팀 분석 화면이 "제품별 사용 관측 인원", "상위 팀별 사용 제품", "팀별 제품 비중(비용·토큰·세션)"을 보여 주려 한다. 화면 요청서의 응답 타입에는 이 필드가 없다.

- 분석 응답은 snapshot 하나에서 계산한다(ADR 0023). 같은 snapshot 의 개요·팀 목록·팀 상세·사용자가 같은 입력을 읽는다. snapshot 의 해석 규칙 판(`QUERY_CONTRACT`)이 지금 판과 다르면 409다.
- 사용량 행(`dashboard_cache.snapshot_usage`)은 관측 제품(`product` — `claude_code`·`codex`·`unknown`)을 담는다. 조회 서버의 공통 계산기(`UsageAggregator`)가 축별로 센다.
- 관측 제품은 카탈로그의 명시 매핑으로만 카탈로그 제품에 잇는다(ADR 0044). 매핑은 enrollment 의 표이고 Flyway가 바꾼다.
- 축별 값은 `UsageTotals`의 null 규칙을 따른다: 세션 없는 행이 있으면 세션 수 없음, 의미 프로파일이 섞이면 토큰 없음, 단가 없는 행이 있으면 금액 없음. 부분합을 총액으로 올리지 않는다.
- 요청서에 없는 응답 필드는 가산으로 더한다. 기존 필드의 이름·의미는 바꾸지 않는다. 요청서의 예시 JSON 사본은 고치지 않는다.

정하지 않으면 생기는 일: 제품 축을 endpoint마다 따로 세면 개요와 팀 화면의 제품 값이 갈라진다. 매핑을 조회 때마다 원천에서 읽으면 한 snapshot 안에서 매핑이 바뀔 수 있다.

## Decision

- **snapshot build 때 관측 제품 매핑을 복제한다**(`dashboard_cache.snapshot_products` — 관측 제품, 카탈로그 제품 ID, 카탈로그 표시 이름·순서, RDS 캐시 `V4`).
  제품 축은 이 복제본으로만 관측을 잇는다. snapshot 의 내용이 바뀌므로 `QUERY_CONTRACT`를 `dashboard-v3`로 올린다 — 이전 판의 snapshot 은 409다.
- **제품 축은 공통 계산기의 축이다**(`PRODUCT`·`TEAM_PRODUCT`). 키는 매핑한 카탈로그 제품 ID, 매핑 없는 관측은 빈 키다. 한 카탈로그 제품에 여러 관측 제품이 이어져도 구성원·세션을 한 번만 센다(키를 SQL 안에서 만든다).
  endpoint마다 새 집계 SQL을 쓰지 않는다.
- **가산 필드**(모두 현재 기간):
  - 개요 `productUsage`: `{availability, reason, products: ProductUsage[]}`. 제품이 없으면 `unavailable`, 금액·토큰이 모두 있으면 `available`, 아니면 `partial`.
  - 개요 `teamUsage.topTeams[].products`·`teamUsage.unassigned.products`: 그 팀(미배정)에서 관측된 제품 `ProductRef[]`.
  - 팀 목록·팀 상세 `TeamAnalytics.products`: 그 팀의 `ProductUsage[]`.
  - `ProductUsage = {kind, displayName, activeUsers, sessionCount, totalTokens, equivalentCostUsd}`, `ProductRef = {kind, displayName}`. `kind`는 카탈로그 제품 ID이고, **매핑 없는 관측은 `kind`·`displayName`이 null**인 항목 하나로 끝에 둔다.
  - 순서는 카탈로그 순서, 매핑 없는 관측은 끝. 사용량 행이 없는 제품은 넣지 않는다. 값은 null 규칙 그대로다.
- 제품 금액이 모두 있으면 제품 금액의 합은 그 범위(조직·팀)의 금액과 같다. 토큰은 제품 안에서만 더한다 — 제품마다 의미 프로파일이 달라 조직·팀 합계의 토큰이 없어도 제품별 토큰은 있을 수 있다.
- 좌석과 관련된 값은 넣지 않는다. 관측 인원은 좌석 수가 아니다(ADR 0044).
- **키 구조 검사**: 요청서 예시 사본은 그대로 두고, 가산 키를 테스트 코드에 예시 파일·경로 단위로 선언한다. 응답의 키는 "예시의 키 + 선언한 가산 키"와 정확히 같아야 한다.

## Alternatives Considered

### A. 조회 때마다 enrollment 의 매핑을 읽는다
- 장점: 복제 표가 없다.
- 단점: 한 snapshot 의 목록과 상세 사이에 매핑이 바뀌면 값이 갈라진다. 조회 계정이 원천을 또 읽는다.
- 탈락 이유: snapshot 은 같은 입력을 보장하는 단위다.

### B. 매핑을 사용량 행에 적어 두고 복제한다(build 때 `product`를 카탈로그 제품으로 바꿔 쓴다)
- 장점: 조회 SQL이 단순하다.
- 단점: 사용량 행의 관측 제품을 잃는다. 매핑과 무관한 다른 축(세션 키의 `product`)이 바뀐 값을 쓰게 된다.
- 탈락 이유: 관측 원본의 의미를 복제본에서 바꾸지 않는다.

### C. 매핑 없는 관측을 버린다
- 장점: 목록이 등록 제품만이다.
- 단점: 조직 합계와 제품 합이 맞지 않는다. 알아보지 못한 도구의 사용이 보이지 않는다.
- 탈락 이유: 매핑 없는 사용은 `unknown`으로 보존한다(ADR 0044).

## Consequences/Tradeoffs

### Positive
- 개요·팀 목록·팀 상세의 제품 값이 한 snapshot·한 계산기에서 나와 서로 맞는다.
- 매핑 없는 사용이 따로 보이고 합계와 맞는다.
- 가산 키가 한 곳에 선언돼 요청서와의 차이가 드러난다.

### Negative
- snapshot build 가 매핑 표를 한 번 더 읽고 쓴다.
- 판 변경으로 진행 중이던 목록 조회가 한 번 409를 받는다.
- 개요·팀 조회가 제품 축 집계를 한두 번 더 센다.
- 매핑 없는 관측은 이름이 없다(null). 화면이 "미확인"으로 적어야 한다.

## Follow-up
- 화면(개요의 벤더 카드·팀 표, 팀 분석의 제품 비중)이 이 필드를 쓴다.
- 누적 세션처럼 제품 축을 기간 완전성(ADR 0042)과 묶을 때 0 규칙을 따로 정한다.
