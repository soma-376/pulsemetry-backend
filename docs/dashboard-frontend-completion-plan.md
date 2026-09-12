# PROJ-156 프론트엔드 잔여 수정안

상태: 프론트엔드 소스 수정 범위 확인 대기. 현재 frontend HEAD는 52f7cb10017c6ba6120f51f2e158ff329d14bff0이고 워킹트리는 깨끗하다. 이 문서는 수정할 코드 경로와 수용 조건을 정리한 계획이며 구현 완료 보고가 아니다.

## 1. 감사 사유 API 전달

- src/api/client.ts의 api.queryCsv에 선택적 auditReason 인자를 추가하고 기존 request의 감사 검증·URI 인코딩·text 응답 처리를 사용한다. 일반 CSV 호출의 기존 인자 순서는 보존한다.
- src/api/scenarios.ts의 runRequest/start에 선택적 auditReason을 전달한다. Retry-After 및 AbortSignal 처리는 유지한다. GET·취소 요청에 시작용 감사 사유를 무조건 첨부하지 않는다.
- 클라이언트 테스트에서 한글 사유의 인코딩, 10–500자 경계, 취소, CSV 오류 JSON, 시작 응답 Retry-After를 확인한다.

## 2. 실제 시작·재실행·CSV UI

- src/pages/scenarios/RunProvider.tsx의 start까지 사유를 연결한다. Scenarios.tsx의 최초 실행·실패 후 재시도, Reports.tsx의 현재 결과 재실행·저장 결과 재실행 모두 대상이다. 최초 실행 폼만 고치면 재실행에서 다시 403이 발생한다.
- 감사가 필요한 조건은 P3 또는 refusals 포함이다. P2인 S6-1도 포함한다. 기존 admin 접근 제한은 유지하고, 접근 가능한 owner에게 기존 감사 입력 UI를 재사용한다.
- 사유를 시나리오 params·재실행 저장 입력·URL·공유 링크·영구 저장소에 넣지 않는다. 재실행은 새 감사 입력을 받고 취소 시 요청하지 않는다. 상세 정보가 필요한 재실행 경로는 scenarioApi.detail로 감사 대상 여부를 확인한다.
- src/pages/overview/OverviewCsv.tsx에서 감사 대상 CSV 선택 시 사유를 입력받아 queryCsv에 전달한다. 현재 선택지에 있는 전사 안전 거부(refusals)가 실제 감사 대상이다. 일반 집계 CSV는 불필요한 사유 입력 없이 기존 경로를 유지한다.

## 3. 주소 표 감사 입력

- src/pages/operations/Operations.tsx의 ops-mismatch가 src/widgets/useWidget.ts를 통해 사유 없는 자동 조회를 한다. 사유 제출 전에는 이 개인 조회를 활성화하지 않도록 바꾼다.
- src/pages/operations/shared.tsx의 AuditDialog를 재사용하고 전용 조회 상태 또는 명시적인 조회 허용 인자를 사용한다. 일반 위젯에 개인 조회용 기본 사유를 삽입하지 않는다.
- 필터 범위·로그인 사용자 변경 시 이전 조회 승인을 재사용하지 않도록 초기화한다. 사유 자체를 query key나 URL로 노출하지 않는다. 재시도·새로고침의 사유 적용 범위도 테스트한다.
- 성공 시 기존 FrameTable로 도메인만 표시한다. 403을 빈 성공 화면으로 바꾸거나 backend owner/audit 제약을 완화하지 않는다.

## 4. 결과 표 라벨

- src/pages/scenarios/Reports.tsx의 다중 수치 table 렌더러는 schema.fields.labels를 표시하지 않는다. onboarding_retention의 cohort_week·week_index를 표의 식별 정보로 표시한다. 여러 프레임을 단순히 같은 헤더의 무명 표로 반복하지 않는다.
- 현재/이전 필드를 구분하는 사용자용 헤더를 제공한다. 예: 현재 p50, 이전 p50. 통계 종류와 단위는 유지하며, 비교 값과 현재 값을 같은 숫자로 합치지 않는다.
- 미관측·비공개·분모 0을 구분한다. 마스킹된 결과에서 숨겨진 값을 labels로 복원하지 않으며, 허용된 코호트/주차 메타데이터만 표시한다. 좁은 화면의 스크롤과 헤더 가독성도 확인한다.

## 검증과 커밋 단위

| 작업 단위 | 검증 |
|---|---|
| API 감사 인자 전달 | 관련 Vitest·타입/빌드 검증 |
| 시작/재실행/CSV 감사 UI | 실제 owner 입력·취소·유효하지 않은 사유·감사 기록; admin 거부; 기존 비감사 실행 |
| 주소 표 감사 UI | 실제 운영·보안 → 보안 → 사유 입력 → 도메인 표; 필터 변경·재시도; CSV/개인 식별 누출 없음 |
| 비교·잔존율 결과 라벨 | 실제 DOM과 스크린샷에서 현재/이전·코호트/주차·미관측·마스킹·좁은 화면 |

backend scripts/e2e/dashboard-auth-settings.mjs의 knownUiGaps 주소 403 재현은 정상 감사 입력/성공 검증으로 교체한다. dashboard-ingest.mjs의 retentionResultUi.knownGap 라벨 부재 기대값과 p50_compare 원시 헤더 기대값도 새 성공 조건으로 교체한다. 실패를 기대하는 검증만 지워서 통과시키지 않는다. 최종 실제 backend/frontend E2E 후 각 저장소에 로컬 커밋을 나누며 push·배포는 포함하지 않는다.

## 별도 결정

distribution의 현재 8개 지원/45개 미지원 범위 확정 질문은 별도로 남아 있다. 프론트엔드 수정 허용만으로 분포 범위가 확정되었다고 간주하지 않는다. 프론트엔드 수정 제외가 유지되면 위 UI 문제는 backend의 인증 완화나 응답 왜곡으로 우회하지 않고 인수 미완료로 남긴다.
