# PROJ-306 구현 검증

## 기준과 변경 관리

- Backend: 원격 fetch로 확인한 `origin/develop 52689f9`에서 `refactor/PROJ-306-feature-packages`를 생성했다. 작업 체크아웃은 `pulsemetry-backend-soma-dev`다.
- 기본 `pulsemetry-backend`의 오래된 브랜치·미커밋 변경을 보존했다. 참고 프로젝트 `gdsc-server df95475`와 미추적 PLAN.md를 수정하지 않았다.
- 티켓의 설명·완료 기준을 직접 조회했다. 연결 이슈·원격 링크·댓글·하위 티켓은 없었다. 새 하위 티켓을 만들지 않았다.
- 코드보다 먼저 ADR 0075·부분 대체 상태·인덱스·모듈 지도를 커밋했다.
- 문서 허브의 형제 체크아웃과 origin/main에도 오래된 Dashboard 서술이 남아 있다. 외부 계약을 변경하지 않으며 Backend의 현재 API 명세·ADR을 회귀 기준으로 사용한다.
- 계약 테스트 원본은 원격 telemetryctl 기본 브랜치 `develop a0efa33`의 contracts를 `/tmp/proj306-contracts/contracts`에 git archive로 읽었다. 로컬 수정본을 오라클로 사용하지 않는다.

## 1. Dashboard

기능별 API·서비스·응답을 overview·team·member·settings·alert에 배치했다. 카탈로그·작업 상태·수집 상태도 해당 기능에서 찾는다. 여러 기능의 값·비교·팀 참조·계약 값·정렬 규칙은 analytics에 두고 기능 내부 참조를 없앴다. 공통 예외 매핑은 api, 오류 본문은 error, 설정 주입은 config가 담당한다.

검증: JDK 25, 실제 PostgreSQL·ClickHouse Testcontainers로 `:apps:dashboard-api:test` 성공. 총 409개, 실패·오류·skip 0. 기존 HTTP·snapshot·ETag·권한·집계 회귀와 신규 JSON 공유 값 2개·ArchUnit 4개가 포함된다. ArchUnit은 기능 간 참조·공유 집계 역참조·전체 Dashboard 패키지 순환·기능 진입점 소재를 검사한다. 첫 검사에서 발견한 오류 처리/설정 역참조는 무시하지 않고 제거했다.

## 2. Enrollment

`ManagementController`의 다중 경로 분기와 `ManagementStore.command`를 제거했다. 기능별 API가 명시적 application 명령을 호출하고, `ManagementHttp`는 HTTP 상태·헤더·JSON과 기존 재시도 식별자, `ManagementExecutor`는 조직/역할 잠금·트랜잭션·암호화 재응답을 맡는다. Directory·Invitation·Installation·Vendor 저장 연산과 CommandJournal은 SQL만 수행한다. 정책 판 생성·설정 변경·보존 정리 요청 조정은 `PolicyCommands`로 옮겼다. 팀 이름·역할 규칙은 순수 domain에서 검사하며 기존 좌석 원장·상태 전이는 재사용한다.

검증: `:apps:enrollment-api:test :libs:enrollment-persistence:test` 성공, 각각 512개·133개, 실패·오류·skip 0. PostgreSQL·실제 HTTP·메일 outbox·낙관 버전·동시성·정책 정리·좌석 회귀를 포함한다. 신규 ArchUnit 4개, 도메인 규칙 2개, 이전 AES-GCM/AAD 저장 형식 재생·동시 멱등 요청·응답 생성 실패 롤백 3개를 추가했다. 초기 전체 실행은 기존 부트스트랩 테스트의 `pwsh` PATH 누락 한 건만 실패했으며, `/tmp/proj268-powershell/runtime`의 PowerShell 7.4.6을 PATH에 포함해 전체 재실행했다.

## 3. 공유 의존성과 앱 조립

구현·검증 진행 중.

## Infra·배포 영향

현재까지 Infra 코드는 변경하지 않았다. 새 공유 모듈은 Backend Dockerfile의 build 입력에 추가해야 한다. 이미지 target·포트·설정 키·스키마·배포 순서는 유지한다. 구조 검사는 기존 PR CI의 Gradle build로 실행하며 원격 배포 검증을 뜻하지 않는다.
