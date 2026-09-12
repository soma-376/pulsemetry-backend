# PROJ-156 수용 검증 추적표

이 문서는 명세 22개 operation의 구현 위치와 검증 범위를 찾기 위한 인덱스다. 각 행의 모든 가능한 조합이나 전체 제품 수용 완료를 선언하지 않는다.

- 명세: docs/reference/pulsemetry_api_spec.yaml 및 pulsemetry_api_overview.md.
- 컨트롤러: apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/ 아래 같은 이름의 .kt 파일.
- DB 검증: DashboardAuthTest, 시간·입력 검증은 DashboardTimeTest와 DashboardScenarioInputTest 등.
- 연동 검증: scripts/e2e/dashboard-auth-settings.mjs와 dashboard-ingest.mjs. 실제 frontend 공통 클라이언트 사용과 일반 사용자 UI 경로를 구분한다.

| operation | 구현 파일 | 대조·검증 범위 |
|---|---|---|
| AUTH-LOGIN | DashboardController.kt | 웹 세션 발급·잘못된 자격 증명·만료·tenant 상태 |
| ME | DashboardController.kt | 현재 사용자·역할·팀 범위 |
| QRY | DashboardQuery.kt | 53개 기본 집계·51개 일반 상위 N·비교·CSV·마스킹; 미지원 distribution 등은 별도 |
| META-METRICS | DashboardMetrics.kt | 명세의 53개 ID·차원·원천 컬럼 대조 |
| META-FILTERS | DashboardObservedMeta.kt | 기간·tenant·현재 접근 범위의 관측 필터 |
| META-TEAMS | DashboardMeta.kt | 현재 팀 접근 범위 |
| META-MEMBERS | DashboardMeta.kt | owner 감사·개인 목록·범위 |
| META-MODELS | DashboardObservedMeta.kt | 관측 모델·권한 밖 모델 제외 |
| META-CONTRACTS | DashboardMeta.kt | 계약 메타·인원 범위 |
| META-MANIFESTS | DashboardMeta.kt | 배포 메타·인원 범위 |
| INSTALL-LIST | DashboardInstallations.kt | owner 감사·상태/무활동·키셋 페이지 |
| SESSION-EVENTS | DashboardSessions.kt | owner 감사·식별자 검색·기간 고정 페이지 |
| SCN-LIST | DashboardScenarios.kt | 46개 정의·카테고리·필터 |
| SCN-GET | DashboardScenarios.kt | 상세 파라미터·가용성·지표 정의 |
| SCN-RUN | DashboardScenarioRuns.kt | 42개 실행 경로·4개 unavailable 거부·현재 권한·동시 상한 |
| RUN-LIST | DashboardScenarioRuns.kt | 동시각 키셋·필터 커서·admin 현재 범위 |
| RUN-GET | DashboardScenarioRuns.kt | 실측 프레임·현재 접근 권한·실패 상태 |
| RUN-DELETE | DashboardReports.kt | active/linked 거부·저장 경쟁의 참조 보존 |
| RUN-CANCEL | DashboardScenarioRuns.kt | queued/running 취소·늦은 결과 차단 |
| RUN-SAVE | DashboardReports.kt | 완료 실행 저장·입력·권한·삭제 경쟁 |
| SAVED-LIST | DashboardReports.kt | 현재 실행 권한·페이지 |
| SAVED-DELETE | DashboardReports.kt | 생성자/owner·현재 실행 권한 |

## 완료된 주요 보완

- 일반 group_by 허용 51개 지표의 기타 재집계. 금지 2개는 적용 대상과 구분한다.
- 계약 ID 지정 약정 소진율의 366일 초과 조회, 잔존율 1w 시계열.
- S4-8 대기 p90의 팀별 판정·판정 팀 라벨·팀별 소집단 보호.
- S8-5 사용자당 비용의 제품별 분리: 동일 사용자의 두 제품 비용 3/7달러 DB 검증 및 제품 라벨 E2E.
- W3.3 벤더 주소 도메인 표·비교·CSV·소집단과 감사, CSV 오류의 JSON 상태 코드.
- 실제 수집 → 조회 → 42개 시나리오 실행 경로와 프로세스 재기동 복구. 실행 경로 통과는 모든 fixture에 양수 판정이 있음을 뜻하지 않는다.

## 남은 수용 조건

| 항목 | 현재 근거와 제한 | 다음 완료 조건 |
|---|---|---|
| distribution | 런타임/53개 지원표 대조 완료: 8개 지원·45개 미지원. dashboard-distribution-scope.md에 표본·필드·결정안 작성 | 현재 8개로 범위 확정 또는 추가 지표의 표본 정의에 대한 사용자 응답 대기 |
| 일반 CSV 내보내기 감사 | frontend src/api/client.ts의 request는 지원, api.queryCsv는 감사 사유 인자가 없음 | 래퍼와 호출 UI가 사유를 전달하고 실제 버튼 흐름으로 403/성공 검증 |
| P3 시나리오 시작 감사 | frontend src/api/scenarios.ts의 start는 감사 사유를 받지 않음 | 실제 시작 폼→API에서 사유 전달·검증·실행 확인 |
| 새 표·시계열 화면 | 주소 표 일반 UI는 감사 헤더 누락으로 403·오류 표시 재현. 잔존율은 공통 API 연동 검증 | 주소 표 감사 입력 연결 후 라벨·빈 값·비교·마스킹, 잔존율 실제 화면 확인 |
| 시나리오 의미 | 46개 ID 분류 및 실행 가능 42개의 관측·판정 의미 대조 완료 | 제목 수준의 인과 효과·공격 탐지 추가는 별도 데이터/요구 필요; UI 수용은 위 항목에서 추적 |
| 운영 장애 | 프로세스 강제 종료 뒤 준비한 queued/expired 상태 복구 및 ClickHouse pause 무응답→query_timeout→새 실행 성공 통과 | 필요 운영 범위에 맞춰 DB 재시작·데이터 손실·네트워크 분단·실제 조회 중단 검증 |

현재 작업 범위에서는 frontend 소스를 변경하지 않는다. 위 프런트엔드 항목을 backend 인증 완화로 우회하지 않는다.

세부 지표 지원은 dashboard-api-contract-matrix.md, 복구 조건은 dashboard-recovery-verification.md, 커밋별 결과는 dashboard-api-progress.md를 참조한다.

최신 전체 빌드: `7e122fa`, 테스트 1,072건(실패·오류·skip 0), 58개 작업 재실행. 이후 S4-8 수정 `86a2a29`는 dashboard 테스트 266건·bootJar·전체 E2E를 통과했다. 전체 Gradle build는 이번 수정 후 재실행하지 않았다.

## 주소 표 일반 UI 재현

- frontend `52f7cb1`, backend `5282ed5`에서 owner 로그인 → 운영 · 보안 → 보안 → 벤더 계정 불일치 위젯을 실제 브라우저로 검증했다.
- `Operations.tsx`의 `ops-mismatch`는 `useWidget.ts` → `api.query`를 호출하면서 감사 사유를 전달하지 않는다. 요청에 X-Audit-Reason이 없고 403으로 거부되어 표 행은 0개다.
- 화면은 “쿼리에 실패했습니다”와 “기간을 줄이거나 잠시 후 다시 시도해 주세요.”를 표시한다. 기간 변경이나 재시도로 해결되지 않는 감사 입력 문제다. 기존 세션 조회의 AuditDialog와 연결하는 프런트엔드 후속 작업이 필요하다.
- `scripts/e2e/dashboard-auth-settings.mjs`가 이 경로를 재현하고 result.json의 knownUiGaps에 별도 기록한다. passed=true는 알려진 차단 재현과 기존 검증 통과이며 UI 수용 완료가 아니다. 프런트엔드 수정 후에는 이 기대값을 감사 입력·성공 조건으로 교체해야 한다.
- 실행 산출물: build/e2e/auth-settings/owner-address-ui-audit-blocked.png. 이미지를 직접 확인했다. 주소 데이터가 렌더링되지 않아 정상 표의 라벨·비교·마스킹 시각 검증은 아직 남아 있다.

전후 비교 5개 시나리오의 의미 대조와 기존 테스트 10건의 근거는 [dashboard-comparison-acceptance.md](dashboard-comparison-acceptance.md)에 정리했다. 런타임 수정 없이 최신 테스트 결과와 소스의 일치를 확인했다.

전체 시나리오 의미 목록은 [dashboard-scenario-semantics.md](dashboard-scenario-semantics.md)다. 나머지 37개 대표 DB 테스트의 최신 통과를 확인했으며, 시나리오 의미의 소스 대조 자체는 더 이상 미착수 항목이 아니다. UI·미지원 distribution·확대 분석 요구와 구분한다.

S4-4 비교 결과 표의 현재 p50=2/이전 미관측은 실제 DOM 검사와 스크린샷으로 확인했다. 원시 필드명 라벨 개선·다른 비교 지표의 마스킹·잔존율 화면은 남아 있다. 근거는 dashboard-comparison-acceptance.md의 실제 결과 표 검증 절을 참조한다.

## 잔존율 결과 표 라벨 누락 재현

- backend 993bc19(런타임 86a2a29), frontend 52f7cb1에서 실제 수집 후 S8-2 실행 결과의 onboarding_retention 표를 검증했다. API의 value 필드에는 cohort_week·week_index=0이 있고, 진행 중인 주의 value는 null, denominator는 5다.
- 실제 표에는 value/numerator/denominator와 미관측/미관측/5가 표시되지만 코호트 날짜·주차는 표시되지 않는다. src/pages/scenarios/Reports.tsx의 다중 수치 table 경로는 필드 이름과 셀만 렌더링하고 labels를 표시하지 않는다. 여러 코호트가 있으면 어떤 코호트/주차의 행인지 식별하기 어렵다.
- scripts/e2e/dashboard-ingest.mjs가 API 라벨 존재·분모·null, 실제 표 개수·미관측 표시·라벨 부재를 단언하고 retentionResultUi.knownGap에 기록한다. build/e2e/auth-settings/admin-retention-table-missing-labels.png도 직접 확인했다.
- 이 테스트는 알려진 UI 결함 재현이며 잔존율 UI 수용 통과가 아니다. frontend 수정 후에는 라벨 표시를 기대하는 검증으로 교체해야 한다. 주간 시계열·다중 코호트·소집단의 시각 검증은 별도로 남아 있다. S3-3은 기존 빈 코호트 실행 경로만 재실행했으며 이번에 정상 코호트 화면 검증을 추가한 것은 아니다.

프론트엔드 결함의 구체적 수정·검증·커밋 계획은 [dashboard-frontend-completion-plan.md](dashboard-frontend-completion-plan.md)다. 기존 frontend 소스 수정 제외 범위를 변경할지 사용자에게 질문했으며 응답 대기다.
