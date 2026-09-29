# 카탈로그 좌석 등급 근거

확인일: 2026-09-29. 공식 문서에 공개된 좌석 구성과 현재 입력 모델의 대응을 기록한다.
가격과 계약 조건은 변경될 수 있으며, 조직이 실제 체결한 계약의 좌석 수·단가를 입력한다.

## 현재 필드의 범위

`allowsSeatTiers`는 제품별로 계약에 여러 좌석 유형을 입력할 수 있는지 정한다.
`true`는 1~3종, `false`는 1종을 허용한다. 3종 제한은 Pulsemetry의 입력 제한이며 벤더의 공식 제한이 아니다.
이 값만으로 모든 플랜·계약이 여러 등급을 제공한다고 판단하지 않는다.
`false`도 좌석 수·단가 입력을 허용한다. 사용자 역할, 추가 사용량 한도, 결제 주기는 좌석 등급과 구분한다.

## 제품별 판정

| 제품 ID | 값 | 근거와 적용 범위 |
|---|---|---|
| `claude_team` | `true` 유지 | Team은 Standard/Premium 혼합을 지원한다. 신규 일반 Enterprise는 단일 Enterprise 좌석이며, 기존 계약의 Standard/Premium 또는 Chat/Chat + Claude Code와 HIPAA 예외를 구분해야 한다. |
| `openai_biz` | `false` → `true` | Business는 Standard/Premium 혼합을 지원한다. Enterprise는 계약에 따라 좌석 유형이 다르며, 크레딧 기반 계약에는 표준 ChatGPT와 Codex 전용 좌석이 있다. |
| `cursor` | `true` 유지 | Teams는 Standard/Premium 유료 좌석과 무료 관리 전용 좌석을 구분한다. Enterprise는 별도 협의 계약이므로 Teams의 등급·단가를 그대로 적용한다고 단정하지 않는다. |
| `copilot` | `false` 유지 | 현재 선택지인 Business/Enterprise는 각각 플랜이다. 기업 내 조직별로 서로 다른 플랜을 쓸 수 있지만, 이를 선택한 단일 플랜 내부의 좌석 등급으로 취급하지 않는다. |
| `gemini` | `false` 유지 | Code Assist Standard/Enterprise는 별도 에디션·구독이다. 공식 문서에서 해당 에디션 내부의 복수 좌석 등급은 확인되지 않았다. Google Workspace·Google AI 개인 구독과 혼동하지 않는다. |
| `other` | `true` 유지 | 특정 공급사에 대한 사실 판정이 아니라, 조직이 직접 입력하는 계약을 위한 선택지다. |

### Claude

- [Team 좌석 관리](https://support.claude.com/en/articles/12004354-purchase-and-manage-seats-on-team-plans): Standard/Premium을 조직 안에서 혼합 배정한다.
- [Enterprise 좌석 관리](https://support.claude.com/en/articles/13393991-purchase-and-manage-seats-on-enterprise-plans): 신규 단일 좌석과 기존 계약·HIPAA 예외를 설명한다. `claude_team=true`를 신규 Enterprise의 복수 등급 지원 보장으로 사용하지 않는다.

### OpenAI

- [Business 청구와 좌석](https://help.openai.com/en/articles/8792536-managing-billing-and-seats-in-chatgpt-business): Standard/Premium 좌석의 혼합 구매와 등급 변경을 설명한다.
- [Enterprise 좌석 유형](https://help.openai.com/en/articles/8265053-what-is-chatgpt-enterprise): 좌석과 청구 조건은 계약에 따른다. 크레딧 기반 계약의 Codex 전용 좌석은 월 고정 좌석료 없이 사용량으로 청구된다. 이를 입력할 때 고정 좌석료는 0이며 실제 사용량 비용은 이 합계에 포함되지 않는다.

### Cursor

- [Teams 가격과 좌석](https://prod.cursor.com/docs/account/teams/pricing): Standard/Premium은 좌석 유형이며 Member/Admin 역할과 별개다. 무료 관리 전용 좌석은 도구 사용 권한이 없다. Enterprise는 맞춤 계약으로 안내한다.

### GitHub Copilot

- [기업의 Copilot 접근 배정](https://docs.github.com/en/copilot/how-tos/administer-copilot/manage-for-enterprise/manage-access/grant-access): 조직별로 Business 또는 Enterprise를 선택한다. 복수 조직의 플랜을 하나의 계약 `tiers`로 합치는 모델은 현재 지원하지 않는다.

### Gemini Code Assist

- [공식 가격표](https://cloud.google.com/products/gemini/pricing): Standard/Enterprise를 각각 에디션으로 구분한다.
- [라이선스 관리](https://docs.cloud.google.com/gemini/docs/codeassist/manage-licenses): 선택한 구독의 라이선스 수와 배정을 관리한다. 이를 근거로 현재는 에디션별 단일 좌석 입력을 유지한다.

## 반영과 한계

V11은 OpenAI 제품의 입력 허용값만 수정한다. V10을 다시 작성하지 않으며 조직별 계약 이력·플랜·단가·`separateUsageBilling`은 변경하지 않는다.
오프라인 테스트용 `tools/dev-seed/src/main/resources/fixtures/vendor-catalog.json`도 같은 값으로 맞춘다.
실제 조회와 저장 검증은 DB를 읽으므로 V11 적용 뒤 새 카탈로그 조회부터 반영된다. 프론트의 카탈로그 캐시가 남아 있으면 재조회해야 한다.

현재 API의 제품 단위 boolean은 신규/기존 계약, 협의 계약, 플랜별 조건을 구별하지 못한다.
이번 보정은 확인된 복수 좌석 입력을 막지 않도록 하는 기준 데이터 수정이며, 공식 좌석 유형을 자동 선택하거나 계약 적합성을 판정하는 기능은 아니다.
플랜·계약별로 정확히 제한하려면 별도의 계약 모델과 API 변경이 필요하다.
