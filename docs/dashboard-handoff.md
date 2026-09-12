# PROJ-156 구현 인계

관리자 대시보드의 인증·조회·메타데이터·시나리오 실행·저장 리포트를 구현하고 실제 pulsemetry-frontend와 연동 검증했다. 현재 구현은 검토 가능하며, 티켓 전체 수용은 distribution 지원 범위 결정이 남아 있다.

## 검토 대상

| 저장소 | 로컬 브랜치 | 코드 기준 |
|---|---|---|
| pulsemetry-backend | feature/PROJ-156-dashboard-api | 런타임86a2a29, E2E 보완6fd1772 |
| pulsemetry-frontend | feature/PROJ-156-dashboard-integration | 런타임acdc626, 검증 문서42ae5c1 |

두 저장소의 변경을 함께 검토한다. backend에는22개 operation,53개 기본 지표,42개 실행 가능 시나리오가 있다. 나머지4개 시나리오는 unavailable로409를 반환한다. frontend 변경은 CSV/시나리오 감사 전달, 개인 조회 사유 입력과 범위 변경 시 재입력, 비교·잔존율 결과 라벨이다.

상세 구현 위치는 [수용 검증 추적표](dashboard-acceptance-matrix.md), 지표별 지원은 [계약 추적표](dashboard-api-contract-matrix.md), 시나리오별 의미는 [시나리오 의미](dashboard-scenario-semantics.md)를 참조한다.

## 검증 근거

| 검증 | 결과와 대상 |
|---|---|
| backend dashboard 테스트·bootJar | 런타임86a2a29에서266건 통과, 실패·오류·skip0. 이후 apps/libs 변경 없음 |
| frontend 타입 검사·단위·빌드 | 런타임acdc626에서91건 단위 테스트 및 타입 검사·빌드 통과 |
| 최신 실제 E2E | backend c28823e + frontend42ae5c1에서 통과. 실행 당시 추가한 기간 변경 검증은 이후6fd1772에 커밋 |
| 과거 전체 Gradle build | 7e122fa에서1,072건·58개 작업 통과. 이후 런타임의 전체 빌드 결과로 간주하지 않음 |

최신 E2E는 `passed=true`, `knownUiGaps=[]`, `unexpectedOrUnimplementedResponses=[]`다. 실제 수집→조회→42개 시나리오, owner/admin 범위, 개인 주소 감사·CSV, P3 최초/재실행/저장 상대 리포트 열기, 비교/코호트 라벨, 프로세스 재기동·ClickHouse 무응답/복구를 검사한다. 모든 데이터 조합·테마·장애 유형을 검증한 것은 아니다.

로컬 결과는 `build/e2e/auth-settings/result.json`, 실행 로그는 `/tmp/proj156-audit-scope-e2e.log`다. 산출물은 버전 관리 대상이 아니며 다음 실행에서 갱신된다. 검증한 dashboard JAR의 SHA-256은 `a27b7884cdf780be61923f59051b187a0e13566d7d6e5ae51b03740db149670e`다.

## 재실행

두 저장소를 형제 디렉터리에 둔다. JDK25, Node.js, Docker, frontend 의존성과 Playwright Chromium이 필요하다. E2E는 격리된 PostgreSQL/ClickHouse 컨테이너를 만들고 종료 시 정리하며 dashboard18081·frontend15173·ingest14316 포트를 사용한다.

backend에서 실행:

```sh
./gradlew :apps:dashboard-api:test :apps:dashboard-api:bootJar :apps:telemetry-ingest:bootJar
node scripts/e2e/dashboard-auth-settings.mjs
```

frontend에서 실행:

```sh
npm run typecheck
npm test
npm run build
```

Java 실행 경로와 JAVA_HOME은 모두 JDK25를 가리켜야 한다. 테스트 스크립트는 빌드된 JAR를 실행하므로 코드 변경 후 bootJar를 먼저 수행한다.

## 남은 결정

현재 distribution은8개 지표를 지원하며45개는 개별 결과501 `metric_not_implemented`다. [범위 결정안](dashboard-distribution-scope.md)에 목록·표본·필드가 있다. 현재8개를 티켓 수용 범위로 확정할지, 추가 지표의 표본·버킷 정의를 정할지는 응답 대기다. 반복적인 진행 요청을 특정 선택의 승인으로 기록하지 않는다.

개인 CSV 전용 선택 화면 추가, 모든 테마·다중 코호트 조합, DB 데이터 손실·네트워크 분단 검증은 현재 통과 근거에 포함하지 않는다. 구체적 필요가 정해지면 별도 범위를 산정한다.

로컬 커밋까지 완료했다. 원격 push·PR 생성·병합·배포·Jira 상태 변경은 수행하지 않았다.
