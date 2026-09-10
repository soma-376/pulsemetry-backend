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
| META-FILTERS, META-MODELS | 기간·tenant·팀 범위를 적용한 실제 ClickHouse 관측 조회 |
| dashboard 저장소 | 실행·리포트·감사 스키마, 독립 Flyway 이력; 감사 INSERT 구현 |
| ClickHouse 조회 클라이언트 | 명명 파라미터, 읽기 설정, 30초 전체 응답 제한, 결과 크기 제한 |
| 시간 해석 | 상대식·월·DST·주 시작 및 비교 기간, 단위 테스트 |

실행·리포트 테이블이 존재한다고 해당 API가 구현된 것은 아니다. 현재 명세의 8개 operation만 구현되었다.

## 검증

- 기존 security/enrollment 테스트 및 전체 `./gradlew build` 통과: 현재 테스트 결과 815건, 실패·오류·skip 0건.
- 웹 로그인·권한 변경·폐기·만료·잠금·CLI 격리 및 감사 사유 테스트.
- 실제 ClickHouse에서 `FINAL` 중복 제거, 파라미터 SQL 분리, 관리자 팀 밖 모델 배제 테스트.
- 실제 frontend의 owner/admin 로그인과 P5 팀·구성원 조회를 Playwright로 검증하는 `scripts/e2e/dashboard-auth-settings.mjs`를 추가했다.
- owner/admin smoke가 실제 PostgreSQL·ClickHouse·frontend 조합에서 통과했다. 미구현 `/v1/query` 404는 기록했고 통과 범위에 포함하지 않았다.
- 이 smoke는 전체 시나리오·지표 수용 테스트가 아니다. 미구현 API 응답은 `build/e2e/auth-settings/result.json`에 기록한다.

## 다음 구현 순서

1. QRY와 META-METRICS: 53개 레지스트리, 원천별 집계, SQL 결과 DataFrame, CSV, 비교, 비율 분모, n<5 억제.
2. 계약 비용: 배정·적용 기간과 모델 할인, token_type=all만 적용, 중복 계약 오류 처리.
3. INSTALL-LIST, SESSION-EVENTS: owner·감사, 키셋 페이지네이션, 설치 상태와 실제 이벤트 조합.
4. 46개 시나리오: 카탈로그·파라미터 검증·조회 시 가용성·실측 기반 findings.
5. 실행 워커와 이력: tenant별 queued+running 3개 제한, lease, 취소 및 종료 상태 경쟁 제어.
6. 저장 리포트: 접근 범위 재검증, fixed/relative, active·linked 삭제 409.
7. 실제 ingest → ClickHouse → dashboard → frontend 전체 E2E 및 operation/metric/scenario 추적표.

## 알려진 한계

- QRY·META-METRICS·설치·세션·시나리오·실행·저장 API는 미구현이다. 지표 결과나 가용성을 임의로 만들어 반환하지 않는다.
- frontend의 coverage 및 약정 소진율 위젯은 QRY 구현 전에는 데이터를 표시하지 못한다.
- 전체 계획 완료나 운영 배포 가능 상태로 판정하지 않는다.
