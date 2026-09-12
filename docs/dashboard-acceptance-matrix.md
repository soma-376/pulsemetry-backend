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

## 범위 결정과 검증 한계

| 항목 | 현재 근거와 제한 | 다음 완료 조건 |
|---|---|---|
| distribution | 런타임/53개 지원표 대조 완료: 8개 지원·45개 미지원. dashboard-distribution-scope.md에 표본·필드·결정안 작성 | 현재 8개로 범위 확정 또는 추가 지표의 표본 정의에 대한 사용자 응답 대기 |
| CSV 내보내기 | queryCsv 감사 인자 구현·주소 CSV 실제 감사 기록·일반 개요 CSV 버튼 다운로드 통과 | 현재 개요에는 개인 지표 선택지가 없음. 개인 CSV 전용 버튼 추가는 별도 UI 기능 |
| P3 시나리오 시작 감사 | 최초 실행·다시 실행·저장된 상대 기간 열기의 새 사유 입력과 기록 확인 | 공통 start로 실패 재시도도 연결; 일반 UI의 모든 조합을 검증했다는 뜻은 아님 |
| 새 표·시계열 화면 | 주소 감사 후 도메인 표, 현재/이전 비교 헤더, 잔존율 코호트·주차·진행 주 미관측 및 768px 표시 확인 | 다중 코호트·주간 시계열·소집단의 모든 화면 조합은 미검증 |
| 시나리오 의미 | 46개 ID 분류 및 실행 가능 42개의 관측·판정 의미 대조 완료 | 제목 수준의 인과 효과·공격 탐지 추가는 별도 데이터/요구 필요; UI 수용은 위 항목에서 추적 |
| 운영 장애 | 프로세스 강제 종료 뒤 준비한 queued/expired 상태 복구 및 ClickHouse pause 무응답→query_timeout→새 실행 성공 통과 | 필요 운영 범위에 맞춰 DB 재시작·데이터 손실·네트워크 분단·실제 조회 중단 검증 |

후속 진행 요청에 따라 frontend 소스 수정까지 포함했다. 위 결함은 frontend에서 수정했으며 backend 인증을 완화하지 않았다.

세부 지표 지원은 dashboard-api-contract-matrix.md, 복구 조건은 dashboard-recovery-verification.md, 커밋별 결과는 dashboard-api-progress.md를 참조한다.

최신 전체 빌드: `7e122fa`, 테스트 1,072건(실패·오류·skip 0), 58개 작업 재실행. 이후 S4-8 수정 `86a2a29`는 dashboard 테스트 266건·bootJar·전체 E2E를 통과했다. 전체 Gradle build는 이번 수정 후 재실행하지 않았다.

## 현재 프론트엔드 연동 상태

frontend `acdc626`에서 주소 조회 감사 전달, 비교 결과 헤더, 잔존율 코호트·주차 라벨을 수정했다. 실제 E2E의 `knownUiGaps=[]`이며, 과거 주소403·라벨 누락 재현 기록은 [진행 기록](dashboard-api-progress.md)에 보존한다. 프론트엔드 수정 포함 여부는 더 이상 응답 대기 항목이 아니다.

- 주소 표: 사유 길이 검증·취소·정상 조회·도메인 마스킹·감사 저장. 기간을24h로 변경하면 기존 행이 제거되고 새 사유 제출 전 개인 요청0건, 제출 후 새 기간/감사 헤더·상태200·감사 기록1건을 검증했다.
- 시나리오: P3 최초 실행·다시 실행·저장된 상대 기간 열기에서 각각 새 사유와 새 실행 ID를 확인했다.
- CSV: 일반 개요 다운로드 버튼과 개인 주소 CSV 공통 클라이언트를 구분하여 검증했다. 일반 refusals 집계는 owner 권한이 필요하지만 감사 사유는 필수가 아니다.
- 결과: S4-4 현재p50=2/이전 미관측과 현재/이전 헤더, S8-2 코호트 날짜·주차0·분모5·미관측 및768px 화면을 확인했다.

전후 비교5개 의미는 [비교 검증](dashboard-comparison-acceptance.md), 전체46개 분류는 [시나리오 의미](dashboard-scenario-semantics.md), 프론트엔드 변경은 [구현 계획과 결과](dashboard-frontend-completion-plan.md)를 참조한다.

검토 대상 브랜치·검증 버전·재실행 방법·미결 범위는 [인계 문서](dashboard-handoff.md)에 모았다.
