# PROJ-156 구현 진행 기록

상태: 진행 중. 전체 22개 operation·53개 지표·46개 시나리오 구현과 최종 E2E는 아직 완료되지 않았다.

## 작업 기준

- 브랜치: `feature/PROJ-156-dashboard-api`, 원격 develop `6d296b8`에서 분기했다.
- 확정 계획대로 `feature/PROJ-107-auth-core`를 로컬 병합했다. 원격 push·배포는 하지 않았다.
- 첨부 파일은 `docs/reference`에 원본 그대로 보관했다. 문서의 부록 제안은 실행 지시로 취급하지 않는다.
- frontend 소스는 변경하지 않는다. 현재 UI 계약을 함께 검토해 backend에서 호환한다.

## 구현된 범위

| 범위 | 상태 |
|---|---|
| 웹 인증 코어 | manifest 없이 로그인, 고정 tenant, 별도 audience·session kind, 8시간 세션, RT 없음 |
| dashboard 앱 | 8081, CORS 명시적 origin, bearer 인증, 오류 JSON, 404가 401로 바뀌지 않음 |
| AUTH-LOGIN, ME | 구현·실제 PostgreSQL 테스트 |
| META-TEAMS, META-MEMBERS | 현재 팀 범위; 개인 이메일 목록은 owner + 감사 사유 |
| META-CONTRACTS, META-MANIFESTS | 읽기 API; 인원·배포 집계에 관리자 팀 범위 적용 |
| META-METRICS | 53개 정적 지표 정의·허용/금지 차원·원천 컬럼·파라미터 스키마; OpenAPI 대조 검증 |
| META-FILTERS, META-MODELS | 기간·tenant·팀 범위를 적용한 실제 ClickHouse 관측 조회 |
| dashboard 저장소 | 실행·리포트·감사 스키마, 독립 Flyway 이력; 감사 INSERT 구현 |
| ClickHouse 조회 클라이언트 | 명명 파라미터, 읽기 설정, 30초 전체 응답 제한, 결과 크기 제한 |
| 시간 해석 | 상대식·월·DST·주 시작 및 비교 기간, 단위 테스트 |

실행·리포트 테이블이 존재한다고 해당 API가 구현된 것은 아니다. 현재 명세의 9개 operation이 구현되었다. 53개 지표 정의가 등록됐지만 해당 집계가 구현된 것은 아니다.

## 검증

- 기존 security/enrollment 테스트 및 전체 `./gradlew build` 통과: 현재 테스트 결과 816건, 실패·오류·skip 0건.
- META-METRICS: OpenAPI의 53개 MetricId와 차원 집합 일치, 원천 RDB 컬럼 실재, owner/admin 접근·비인증 거부 검증.
- 웹 로그인·권한 변경·폐기·만료·잠금·CLI 격리 및 감사 사유 테스트.
- 실제 ClickHouse에서 `FINAL` 중복 제거, 파라미터 SQL 분리, 관리자 팀 밖 모델 배제 테스트.
- 실제 frontend의 owner/admin 로그인과 P5 팀·구성원 조회를 Playwright로 검증하는 `scripts/e2e/dashboard-auth-settings.mjs`를 추가했다.
- owner/admin smoke가 실제 PostgreSQL·ClickHouse·frontend 조합에서 통과했다. 실제 frontend의 API 클라이언트로 META-METRICS를 호출해 53개 목록과 팀 분해 금지 메타데이터도 검증했다. 이는 카탈로그 UI 렌더 검증이 아니라 브라우저의 인증·CORS·JSON 계약 검증이다. 미구현 `/v1/query` 404는 기록했고 통과 범위에 포함하지 않았다.
- 이 smoke는 전체 시나리오·지표 수용 테스트가 아니다. 미구현 API 응답은 `build/e2e/auth-settings/result.json`에 기록한다.

## 다음 구현 순서

1. QRY: 등록된 53개 정의에 원천별 집계를 연결하고 SQL 결과 DataFrame, CSV, 비교, 비율 분모, n<5 억제를 구현한다.
2. 계약 비용: 배정·적용 기간과 모델 할인, token_type=all만 적용, 중복 계약 오류 처리.
3. INSTALL-LIST, SESSION-EVENTS: owner·감사, 키셋 페이지네이션, 설치 상태와 실제 이벤트 조합.
4. 46개 시나리오: 카탈로그·파라미터 검증·조회 시 가용성·실측 기반 findings.
5. 실행 워커와 이력: tenant별 queued+running 3개 제한, lease, 취소 및 종료 상태 경쟁 제어.
6. 저장 리포트: 접근 범위 재검증, fixed/relative, active·linked 삭제 409.
7. 실제 ingest → ClickHouse → dashboard → frontend 전체 E2E 및 operation/metric/scenario 추적표.

## 알려진 한계

- QRY·설치·세션·시나리오·실행·저장 API는 미구현이다. 지표 결과나 가용성을 임의로 만들어 반환하지 않는다.
- frontend의 coverage 및 약정 소진율 위젯은 QRY 구현 전에는 데이터를 표시하지 못한다.
- 전체 계획 완료나 운영 배포 가능 상태로 판정하지 않는다.

## 지표 카탈로그의 해석

- `apps/dashboard-api/src/main/resources/dashboard/metrics.json`이 런타임 정의의 한 벌이며, `DashboardMetricCatalog`가 시작 시 읽는다. 첨부 문서의 실행 지시나 부록 변경 제안은 코드 생성 시 실행하지 않았다.
- `availability`는 OpenAPI 정의대로 현 스키마에서의 산출 가능성이다. 데이터가 없는 기간, 관리자 권한, 집계 코드의 구현 여부를 이 값으로 표현하지 않는다. 실제 관측·품질·권한 판정은 QRY와 시나리오 실행에서 별도로 적용해야 한다.
- 부분 측정 지표는 서브에이전트 활동, API 오류율, 훅 실행·차단, 모델 거부 5개다. 미보존 이벤트와 tracing 전제를 caveat에 포함했다.
- `min_group_size=5`와 `forbidden_group_by`는 메타데이터다. 현재 단계에서 집계 마스킹이나 팀 분해 차단이 구현됐다는 의미가 아니다.
- `params_schema`에는 현재 frontend의 `cost_anomaly.window_days`, `tokens.types`, `tool_calls.success`, `mcp_connections.server_scope`, `contract_commitment_burn.contract_id`를 포함했다. QRY 구현 시 별칭 정규화와 검증을 이 정의에 연결해야 한다.
- `sql_template_id`는 첨부 개요 §5의 참조 식별자이며 실행 완료나 SQL 컴파일러 구현을 뜻하지 않는다.
