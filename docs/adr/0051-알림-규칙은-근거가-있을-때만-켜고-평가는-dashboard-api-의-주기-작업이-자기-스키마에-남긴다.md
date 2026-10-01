# 0051. 알림 규칙은 근거가 있을 때만 켜고 평가는 dashboard-api 의 주기 작업이 자기 스키마에 남긴다

## Status

Proposed — 허브 PRD 개정 제안([§6-1 #7·§6-3](../../../docs/product/prd.md))이 채택되면 Accepted.
§5 의 "평가 기록을 RDS `dashboard_cache` 스키마에 쓴다"는 [ADR 0022](0022-대시보드-API-는-별도-앱이고-인증은-포트-뒤에서-기본-거부한다.md) §2 를 부분 개정하므로,
ADR 0022 의 Status 조건인 허브 ADR 0007(대시보드 자기 캐시의 쓰기 예외) 리뷰에 함께 올린다.
§1–§4(규칙·목록 저장, 켜기 판정, 설정 조회)는 이 저장소에 구현이 있다. §5–§6(평가·알림·확인)은 결정만 있고 구현이 없다.

## Context

설정 화면에는 알림 규칙 네 개(비용 급증·한도 초과·비허용 모델·미승인 도구)의 토글이 있고, 개요에는 "보안 경보 및 알림" 카드가 있다.

- 서버는 네 규칙을 고정값으로 낸다(`SettingsService.ALERT_RULES` — 모두 꺼짐·`unavailable`). 저장소도 평가도 없다.
  개요 `alerts` 는 늘 `evaluation_not_configured` 다. 임계값과 창은 화면 요청서가 제안한 초기 기준이다
  (급증 0.4 — 직전 완전한 7일 대 그 앞 7일, 한도 초과 24시간 차단 사용자 5명, 모델·도구 24시간 1회).
- 허브 PRD §6-3 은 "폭주 세션 조기 경보 / 예산 알림"을 Non-goal 로 두었다. 개정 제안은 **사후 규칙 네 가지만** 범위로 옮기고
  실시간 경보·차단·예산 편성과 섀도우 AI 탐지는 Non-goal 로 남긴다.
- 규칙마다 쓸 수 있는 원천이 다르다(분석 행의 컬럼 — ADR 0020).
  - **비용**: 비용 컬럼과 기간 완전성(ADR 0042). 비교는 두 기간이 모두 완전할 때만 성립한다. 완전성은 설치가 보고한 수집 구간
    (`enrollment.installation_collection_segments`, ADR 0040)이 있어야 생긴다 — 보고가 없는 조직에는 완전한 날이 나오지 않는다.
  - **한도·쿼터**: Claude Code 의 `api_error` 는 `model.request.error`(`http_status`)로 매핑하지만 **실캡처가 없다**. 정규화 fixture 는
    합성 사례만 고정한다(`libs/telemetry-adapter/src/test/resources/otlp-v2/claude_code/PROFILE-EVIDENCE.md` §7, `synthetic/errors`).
    `429` 가 쿼터 소진인지 속도 제한인지, 사용자가 실제로 막혔는지를 관측으로 확인한 적이 없다. Codex 실캡처에도 한도 초과를 가리키는 관측이 없다.
  - **모델**: `model` 컬럼. Claude Code `api_request` 와 Codex 공통 세션 속성 `model` 이 실캡처로 채운다.
  - **도구**: `tool_name`·`tool_origin`. 실캡처로 채운다. 단 MCP 도구 이름은 producer 가 가린다 — Claude Code 로그는 `mcp_tool`,
    도구 스팬의 컬럼 값은 `mcp_other` 다(같은 PROFILE-EVIDENCE §5·§8).
- 평가는 ClickHouse 읽기와 RDS 쓰기가 같이 필요하다. ADR 0022 §2 로 dashboard-api 는 원천을 읽기만 하고 자기 캐시만 쓴다.
  enrollment-api 는 ClickHouse 를 읽지 않는다. 기간 완전성 판정(`dashboard/snapshot/Completeness`)과 비용 집계는 dashboard-api 에만 있다.

정하지 않으면 생기는 일: 근거가 없는 규칙이 켜진 것처럼 보이거나, 비용 이벤트만으로 한도 초과·미승인 도구를 지어낸다.
평가를 아무 앱에나 두면 같은 "비용"과 "완전한 기간"의 구현이 두 벌이 된다.

## Decision

### 1. 규칙과 근거 — 켤 수 있는 조건

| 규칙 | 분류 | 임계값·창(초기 기준) | 근거 원천 | 켤 수 있는 조건 | 아니면 사유 |
| --- | --- | --- | --- | --- | --- |
| `spend_spike` | cost | 0.4 ratio · `last_complete_7_calendar_days` 대 `preceding_7_calendar_days` | 분석 행 비용 + 기간 완전성(ADR 0042) | 조직의 설치가 수집 구간을 보고한 적이 있다 | `completeness_not_available` |
| `quota_exceeded` | cost | 5 users · `rolling_24_hours` | 없음 — 검증된 관측이 없다 | 켤 수 없다 | `source_not_available` |
| `model_not_allowed` | security | 1 events · `rolling_24_hours` | `model` + 조직의 모델 허용 목록 | 허용 목록이 비어 있지 않다 | `allowed_models_not_configured` |
| `tool_unapproved` | security | 1 events · `rolling_24_hours` | `tool_name` + 조직의 승인 도구 목록 | 승인 목록이 비어 있지 않다 | `approved_tools_not_configured` |

- **켤 수 있는 조건은 "평가가 결과를 낼 근거가 있는가"다.** 창마다의 완전성은 평가할 때 다시 본다(§6).
- 비용 이벤트·응답 상태만으로 한도 초과·미승인 도구를 만들지 않는다. 한도 초과는 실캡처와 fixture 가 생기면 이 ADR 을 개정해 근거를 더한다.
- "미승인 도구"는 **관측된 도구 이름을 승인 목록과 대조**하는 것이다. 관측되지 않은 도구는 알 수 없다 — 섀도우 AI 탐지가 아니다.

### 2. 저장

- **규칙 정의는 기준 데이터다.** 네 규칙의 분류·임계값·창은 `enrollment.alert_rule_definitions` 에 마이그레이션이 넣는다.
  화면은 토글만 제공한다 — 임계값·창을 바꾸는 API 를 만들지 않는다.
- **조직별 상태** `enrollment.organization_alert_rules`: 조직·규칙마다 켜짐, 판, 마지막 변경자·시각. 행이 없으면 꺼짐·판 0 이다.
- **목록** `enrollment.organization_alert_lists`(조직·목록마다 판·변경자·시각)와 `enrollment.organization_alert_list_entries`(항목).
  목록은 `allowed_models`(모델 허용 목록)와 `approved_tools`(승인 도구 목록) 둘이다. 행이 없으면 빈 목록·판 0 이다.
- 네 표의 쓰기 소유는 `:libs:enrollment-persistence`, 진입은 enrollment-api 다. dashboard-api 는 읽기만 한다.
- **항목 규칙**: 앞뒤 공백 없는 1–200자, 제어 문자 없음, 목록당 200개 이하, 중복 없음. 대소문자를 구분하는 정확 일치다.
  끝의 `*` 하나는 접두사 일치다(`claude-opus-*`). 그 밖의 자리의 `*` 는 거부한다. producer 가 가린 이름(`mcp_tool`)은 그 이름 그대로
  대조된다 — 개별 MCP 도구는 구분하지 못한다.

### 3. 명령 (enrollment-api)

- `PATCH O/settings/alert-rules/{ruleId}` — `{expectedVersion, enabled}` → 200, 설정 조회의 `alertRules` 항목과 같은 모양.
  - 값이 바뀔 때만 판이 1 오른다. 같은 값이면 판 그대로 현재 상태를 돌려준다.
  - 켤 수 없는 규칙을 켜면 422 `alert_rule_unavailable`(필드 `enabled`, `details.reason` = §1 의 사유). 끄기는 언제나 된다.
  - 없는 규칙 404, 판 충돌 409 `version_conflict`.
- `PUT O/settings/alert-lists/{listId}` — `{expectedVersion, entries}` 전체 교체 → 200 `{list, alertRules}`.
  - 내용이 같으면 판 그대로다. 응답의 `alertRules` 는 네 규칙의 현재 상태다(목록을 채우면 기대는 규칙이 켤 수 있게 된다).
  - 켜진 규칙이 기대는 목록을 비우면 422 `alert_list_in_use` — 규칙을 먼저 끈다.
- 두 명령은 같은 조직 행을 잠가 직렬화한다. 그래서 **켜진 규칙은 언제나 켤 수 있는 규칙이다.** 수집 구간 근거는 지우지 않으므로 사라지지 않는다.
- 행위자는 owner·admin 이고 DB 에서 다시 확인한다. 토큰의 조직이 경로와 다르면 404 다.
- PATCH·PUT 이고 판이 재시도를 막으므로 `Idempotency-Key` 를 받지 않는다.

### 4. 조회 (dashboard-api)

- `GET O/settings` 의 `alertRules` 는 저장된 켜짐·판, 가용성(`available`·`unavailable`)과 §1 의 사유, 정의 표의 임계값·창이다.
- 목록은 가산 필드 `alertLists` 로 낸다 — `{allowedModels, approvedTools}`, 각각 `{listId, version, entries, updatedAt}`.
- `capabilities.editAlertRules` 는 관리 기능이 켜진 배포에서 참이다. 규칙마다 켤 수 있는지는 그 규칙의 가용성이 말한다.
- 켜기 판정 코드는 하나다(`persistence.enrollment.alert.AlertRules`). 명령의 422 와 조회의 가용성이 같은 코드에서 나온다.

### 5. 평가 위치 — dashboard-api 의 주기 작업

| 후보 | 판단 |
| --- | --- |
| **A. dashboard-api 의 주기 작업** | **채택.** 기간 완전성 판정·비용 집계·ClickHouse 원천 계정이 이미 여기 있다. 개요·팀 화면이 보는 비용과 같은 값·같은 완전성으로 급증을 판정한다 |
| B. enrollment-api 의 주기 작업 | ClickHouse 읽기 계정과 완전성·비용 규칙의 두 번째 구현이 필요하다. 두 화면이 다른 "비용"을 말할 수 있다 |
| C. 보존 작업 같은 일회성 실행 앱 | 새 배포 단위와 스케줄이 필요하다(infra) |
| D. 수집 시점(telemetry-ingest) | push 하나로는 창과 완전성을 판정할 수 없다 |

- 평가 기록은 dashboard-api 가 RDS `dashboard_cache` 스키마(기존 캐시 계정)에 쓴다. **ADR 0022 §2 의 "자기 캐시뿐"을 "자기 캐시와 알림 평가 기록"으로 개정한다.**
  분석 원본·ledger·`enrollment`·`telemetry_ops` 는 여전히 읽기 전용이다.
- 평가 기록은 원천과 규칙 설정에서 다시 계산할 수 있는 파생값이다. 같은 창을 다시 평가하면 같은 키가 나온다. 단 snapshot 정리 작업은 이 표를 지우지 않는다.
- 여러 인스턴스는 조직 단위 선점(`FOR UPDATE SKIP LOCKED`)으로 나눈다. 평가 주기와 확정 대기는 기본값 없는 필수 설정이다(ADR 0042 의 `settle-after` 관례).

### 6. 알림과 확인

- 알림 하나 = (조직, 규칙, 대상, 창의 키). 대상은 급증이면 조직, 모델이면 모델 이름, 도구면 도구 이름이다.
- **급증**: 하루 D 마다, D 로 끝나는 7일과 그 앞 7일이 모두 완전하고(ADR 0042) 앞 7일 비용이 0 보다 크며 증가율이 임계값 이상이면 (`spend_spike`, D) 알림이다.
  완전하지 않거나 앞 7일이 0 이면 평가하지 않는다 — "알림 없음"과 구분해 마지막 평가 상태를 남긴다.
- **24시간 규칙**: 대상마다 위반 관측을 시간순으로 묶어 24시간 안에 임계값만큼 모이면 알림을 연다. 24시간 동안 위반이 없으면 닫는다. 키는 그 묶음의 첫 위반 시각이다.
  모델이 null 인 행, 도구 이름이 null 인 행은 위반이 아니다.
- **확인은 enrollment-api 명령이다.** 확인 기록은 `enrollment` 스키마에 둔다(사람의 행위). 알림이 그 조직의 것인지는 평가 기록을 읽어 확인한다 —
  enrollment 계정에 그 표의 SELECT 권한이 필요하다.
- 개요 `alerts` 는 미확인 수(전체, 보안 = 모델·도구, 비용 = 급증·한도)와 마지막 평가 시각을 낸다. 켜진 규칙이 없거나 평가 기록이 없으면 `unavailable` 과 사유다.

## Alternatives Considered

### A. 응답 상태(429)나 비용 이벤트로 한도 초과를 추정한다

실캡처가 없는 이벤트이고 429 의 뜻(쿼터 소진·속도 제한·재시도 중)을 관측으로 가르지 못한다. "차단된 사용자"를 지어내게 된다. 기각했다.

### B. 임계값·창을 편집하는 API 를 같이 만든다

화면은 토글만 있고 기준값의 운영 합의가 없다. 초기 기준을 데이터로 저장해 두면 나중에 편집을 더해도 저장 모양이 바뀌지 않는다. 기각했다.

### C. 목록 없이 "처음 보는 모델·도구"를 위반으로 본다

기준선이 조직의 정책이 아니라 첫 관측 시점이 된다. 도입 초기에는 모든 것이 위반이고, 한 번 본 것은 영원히 허용된다. 기각했다.

### D. 평가 기록을 `enrollment` 스키마에 둔다

dashboard-api 의 원천 계정은 읽기 전용이다(ADR 0022 §4). 쓰려면 평가를 enrollment-api 로 옮겨야 하고(§5 의 B), 그러면 비용·완전성 구현이 두 벌이 된다.

### E. 켜기를 막지 않고 평가 때 근거가 없으면 건너뛴다

켜진 토글이 아무것도 하지 않는다. 화면이 근거 없는 규칙을 켜진 것으로 보여 준다. 기각했다.

## Consequences/Tradeoffs

### Positive

- 근거가 없는 규칙은 켜지지 않고, 규칙마다 사유가 화면에 그대로 나온다.
- 급증 판정이 개요·팀 화면의 비용·완전성과 같은 규칙을 쓴다.
- 임계값은 데이터라 편집 기능을 더해도 저장 모양이 바뀌지 않는다.

### Negative

- dashboard-api 가 처음으로 snapshot 이 아닌 기록(파생값이지만 사람이 확인하는 알림)을 쓴다. 캐시 정리 작업의 대상에서 빼야 한다.
- 확인 명령을 위해 enrollment 계정에 `dashboard_cache` 알림 표의 SELECT 권한이 필요하다(운영 주입).
- 한도 초과는 근거가 생기기 전까지 켤 수 없다. MCP 도구는 producer 가 이름을 가려 개별로 승인할 수 없다.
- 목록이 길어지면 관리 부담이 늘고, 모델 이름이 바뀌면(날짜 접미사) 목록을 고쳐야 한다 — 끝의 `*` 접두사 일치로 줄인다.

## Follow-up

- 평가 주기 작업·알림 표·확인 명령·개요 `alerts` 구현(§5·§6).
- 한도 초과의 근거: Claude Code `api_error` 실캡처와 fixture.
- 허브 PRD 개정 제안의 제품 문서 소유자 검토, 허브 ADR 0007 리뷰에 알림 평가 기록 포함.
- 운영: enrollment 계정의 알림 표 SELECT 권한, 평가 주기·확정 대기 설정 주입.
