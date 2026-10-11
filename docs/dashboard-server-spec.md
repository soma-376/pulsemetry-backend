<a id="dashboard-서버-명세"></a>

# Dashboard 서버 운영·실행 안내

HTTP 명세는 [페이지·기능별 API 문서](api/README.md)로 이전했다. 이 문서는 서버 책임·설정·운영·검증 절차와 기존 링크의 진입점을 유지한다.


`:apps:dashboard-api`가 제공하는 조회 API의 서버 명세다. 기본 포트는 8081이다.
사용자 로그인·온보딩 상태·정책 저장·팀/벤더/초대 명령은 [페이지·기능별 API 문서](api/README.md)를 따른다.
레포 간 확정 계약은 [문서 허브](../../docs/contracts/README.md)가 우선한다.
현재 허브 dashboard 계약은 골격이며 이 문서는 구현된 HTTP와 제한을 기록한다.

## API 목록

| 번호 | API | 기능 |
| --- | --- | --- |
| 01 | [GET `/api/v1/organizations/{organizationId}/analytics/overview`](api/endpoints/01-get-overview.md) | 개요 조회 |
| 02 | [GET `/api/v1/organizations/{organizationId}/analytics/teams`](api/endpoints/02-get-team-analytics.md) | 팀 분석 목록 |
| 03 | [GET `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}`](api/endpoints/03-get-team-detail.md) | 팀 분석 상세 |
| 04 | [GET `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}/users`](api/endpoints/04-get-team-users.md) | 팀 사용자 |
| 05 | [GET `/api/v1/organizations/{organizationId}/teams`](api/endpoints/05-list-teams.md) | 현재 팀 선택지 |
| 06 | [GET `/api/v1/organizations/{organizationId}/members/dashboard`](api/endpoints/06-get-members-dashboard.md) | 구성원 첫 화면 |
| 07 | [GET `/api/v1/organizations/{organizationId}/members`](api/endpoints/07-list-members.md) | 구성원 목록 |
| 08 | [GET `/api/v1/organizations/{organizationId}/members/unassigned`](api/endpoints/08-list-unassigned-members.md) | 미배정 구성원 |
| 09 | [GET `/api/v1/organizations/{organizationId}/seat-reclaim-candidates`](api/endpoints/09-list-reclaim-candidates.md) | 회수 후보 |
| 10 | [GET `/api/v1/organizations/{organizationId}/members/{memberId}/seats`](api/endpoints/10-get-member-seats.md) | 구성원 좌석 |
| 11 | [GET `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats`](api/endpoints/11-list-vendor-seats.md) | 제품 좌석 |
| 12 | [GET `/api/v1/organizations/{organizationId}/settings`](api/endpoints/12-get-settings.md) | 설정 첫 화면 |
| 13 | [GET `/api/v1/organizations/{organizationId}/vendors`](api/endpoints/13-list-vendors.md) | 등록 제품 목록 |
| 14 | [GET `/api/v1/organizations/{organizationId}/vendors/{vendorId}`](api/endpoints/14-get-vendor.md) | 등록 제품 상세 |
| 15 | [GET `/api/v1/organizations/{organizationId}/installations`](api/endpoints/15-list-installations.md) | 설치 현황 |
| 16 | [GET `/api/v1/organizations/{organizationId}/alerts`](api/endpoints/16-list-alerts.md) | 알림 목록 |
| 17 | [GET `/api/v1/organizations/{organizationId}/alerts/{alertId}`](api/endpoints/17-get-alert.md) | 알림 상세 |
| 18 | [GET `/api/v1/organizations/{organizationId}/operations/{operationId}`](api/endpoints/18-get-operation.md) | 작업 상태 |
| 19 | [GET `/api/v1/organizations/{organizationId}/ingest-status`](api/endpoints/19-get-ingest-status.md) | 현재 수집 상태 |
| 20 | [GET `/api/v1/vendor-catalog`](api/endpoints/20-list-vendor-catalog.md) | 카탈로그 검색 |
| 21 | [GET `/api/v1/vendor-catalog/{vendorId}/plans`](api/endpoints/21-list-vendor-plans.md) | 제품 플랜 조회 |
| 76 | [GET `/api/v1/healthz`](api/endpoints/76-get-dashboard-health.md) | 생존 확인 |

## 5. 운영과 로컬 실행

개발 Compose의 `dev-seed`는 Spring 서버 없이 기존 원천 마이그레이션·A/B/C 시드와
조회용/캐시용 DB 계정을 준비한다. `docker compose up -d --build` 후 시드 종료 코드 0을 확인한다.
Compose가 인증 키를 `build/dev-auth`에 준비하며 서버 설정은 앱의 local 프로필이 제공한다.

```powershell
# DB와 enrollment 마이그레이션이 준비된 뒤 별도 터미널에서 실행
.\gradlew.bat :apps:dashboard-api:bootRun --args="--spring.profiles.active=local"
```

조회용/캐시용 DB 계정과 인증 키는 Compose가 준비한다. local 프로필이 공개키와 개발 계정 설정을 읽는다.
서버는 enrollment-api의 private key를 사용하지 않는다. issuer·audience·public key는 발급 서버와 일치해야 한다.
`pulsemetry.user-auth.enabled` 기본 false, 허용 origin은 user-auth.allowed-origins에 지정한다.
`pulsemetry.management.enabled`는 관리 가능한 UI capability를 나타내며 실제 쓰기는 enrollment-api에 보낸다.
`pulsemetry.mail.enabled`(`PULSEMETRY_MAIL_ENABLED`, 기본 false)는 이 앱에서 메일을 보내지 않는다 — 설치 업데이트 안내를 보낼 수 있는지의 표시에만 쓴다.
enrollment-api와 같은 값을 준다(local 프로필은 둘 다 true).

일반 실행은 `:apps:dashboard-api:bootRun`이며 필요한 설정은 다음 그룹이다.

| 설정 그룹 | 내용 |
| --- | --- |
| `pulsemetry.user-auth` | enabled, issuer, audience, public-key-files, allowed-origins |
| `pulsemetry.dashboard.rds.source` | 원천 읽기 JDBC URL·계정 |
| `pulsemetry.dashboard.rds.cache` | dashboard_cache JDBC URL·쓰기 계정 |
| `pulsemetry.dashboard.clickhouse.source` | 원천 URL·database·계정·query-timeout·max-result-rows/bytes |
| `pulsemetry.dashboard.clickhouse.cache` | 캐시 URL·database·계정·query-timeout |
| `pulsemetry.dashboard.snapshot` | build-timeout·purge-grace·max-concurrent-builds·max-copy-rows/bytes·cleanup-interval |
| `pulsemetry.dashboard.members.idle-days` | 조직이 회수 기준을 저장하지 않았을 때의 회수 후보 기준 기간(ADR 0046) |
| `pulsemetry.dashboard.ingest` | 수집 상태 판정의 임계값 — window·delayed-after·down-after. 셋 다 기본값이 없다(아래 "공통 헤더 수집 현황") |
| `pulsemetry.dashboard.completeness.settle-after` | 기간 완전성의 확정 대기(`PULSEMETRY_DASHBOARD_COMPLETENESS_SETTLE_AFTER`). 기본값 없음, 0보다 크다. 데몬의 재시도 전체와 적재가 끝나는 시간보다 길게. local 1시간 |
| `pulsemetry.dashboard.seats.stale-after` | 연결의 마지막 성공 동기화가 이보다 오래되면 그 제품의 좌석을 낡았다고 표시(`PULSEMETRY_DASHBOARD_SEATS_STALE_AFTER`, ADR 0048). 기본값 없음, 0보다 크다. enrollment-api 의 동기화 간격보다 길게. local 26시간 |
| `pulsemetry.dashboard.alerts.evaluation-interval` · `.lease` | 알림 평가 주기와 한 조직의 평가 선점 기한(`PULSEMETRY_DASHBOARD_ALERTS_EVALUATION_INTERVAL`·`_LEASE`, ADR 0051). 기본값 없음, 0보다 크다. 선점 기한은 한 회차(급증의 snapshot 들)보다 길게. local 1분·10분 |
| `pulsemetry.dashboard.retry-after` | 일시 장애 재시도 간격 |

정확한 환경변수 이름은 [application.yaml](../apps/dashboard-api/src/main/resources/application.yaml)에 매핑돼 있다.
캐시 DB/스키마와 권한은 기동 전에 준비하고 캐시 테이블은 앱이 자기 마이그레이션으로 생성한다.
enrollment Flyway는 이 앱에서 실행하지 않는다. 새 원천 테이블은 enrollment-api가 먼저 적용해야 한다.
개발 환경에서는 Compose의 일회성 시드 도구도 동일한 마이그레이션을 실행한다(ADR 0030).
시드 계정과 데이터 준비는 [개발용 시드 가이드](../tools/dev-seed/README.md)를 따른다.

## 6. 검증과 구현 한계

`:apps:dashboard-api:test`가 카탈로그 검색·페이지·인증, 개요·팀·구성원·설정·snapshot을 검증한다.
카탈로그는 DB 기준 데이터이며 V10이 기존 목록을 한 번 초기화한다. 관리자 편집 API는 없으며 권한 있는 DB 작업으로 관리한다.
계약 없는 수동 벤더는 조회에 남으며 state=needs_review, contract=null이다.
관리 기능이 켜져 있으면 editContracts·editCollectionPolicy·editAlertRules는 true이고 저장은 enrollment-api에 요청한다.
collectionPolicy.collectRawContent는 프롬프트·응답 중 하나라도 허용됐는지다. 도구 내용·API 원문은 이 선택과 별개다.
두 플래그가 다를 때 온보딩 조회는 null로 표현해 명시적 재선택을 받는다.
설정의 계약 좌석·월 요금은 계약의 값이고, 보유·활성 좌석과 개요의 좌석 집계는 좌석 원장의 값이다(위 "좌석 원장 조회").
알림 발송(메일 등 — 알림은 저장·조회·확인뿐, 위 "알림")·원격 정책 갱신 완료·좌석 구독료의 실제 청구액은 구현하지 않는다(종량 지출은 §7.2 — ADR 0050). 좌석 회수·복원은 enrollment-api 명령이고 이 앱은 가능 여부와 작업 결과를 읽는다(ADR 0049).



## 이전 API 절 안내

기존 절 번호·앵커로 들어온 경우 아래 링크에서 상세 명세를 확인한다.

<a id="1-인증책임응답"></a>

## 1. 인증·책임·응답

→ [공통 HTTP 규칙](api/common.md)

<a id="2-페이지별-조직-조회-api"></a>

## 2. 페이지별 조직 조회 API

→ [API 길잡이](api/README.md)

<a id="21-개요-페이지"></a>

### 2.1 개요 페이지

→ [개요](api/overview.md)

<a id="22-팀-분석-페이지"></a>

### 2.2 팀 분석 페이지

→ [API 길잡이](api/README.md)

<a id="팀-목록"></a>

#### 팀 목록

→ [API 길잡이](api/README.md)

<a id="제품별-사용-adr-0045"></a>

### 제품별 사용 (ADR 0045)

→ [공통 응답 스키마](api/common-schemas.md)

<a id="현재-데이터의-해석"></a>

### 현재 데이터의 해석

→ [공통 응답 스키마](api/common-schemas.md)

<a id="정책-적용-현황과-업데이트-안내-adr-0043"></a>

### 정책 적용 현황과 업데이트 안내 (ADR 0043)

→ [설치 현황·업데이트 안내](api/installations.md)

<a id="조직-정책-설정-adr-0046"></a>

### 조직 정책 설정 (ADR 0046)

→ [수집 정책·조직 정책 설정](api/collection-policy.md)

<a id="알림-규칙-허브-adr-0008"></a>

### 알림 규칙 (허브 ADR 0008)

→ [알림·알림 규칙](api/alerts.md)

<a id="알림-adr-0051-56"></a>

### 알림 (ADR 0051 §5·§6)

→ [알림·알림 규칙](api/alerts.md)

<a id="기간-완전성과-비교-adr-0042"></a>

### 기간 완전성과 비교 (ADR 0042)

→ [공통 응답 스키마](api/common-schemas.md)

<a id="팀-누적-세션-adr-0042"></a>

### 팀 누적 세션 (ADR 0042)

→ [팀 분석·관리](api/teams.md)

<a id="좌석-원장-조회-adr-0048"></a>

### 좌석 원장 조회 (ADR 0048)

→ [좌석 원장·회수·복원](api/seats.md)

<a id="작업-상태-조회"></a>

### 작업 상태 조회

→ [비동기 작업](api/operations.md)

<a id="3-벤더와-플랜-카탈로그"></a>

## 3. 벤더와 플랜 카탈로그

→ [벤더·플랜 카탈로그](api/vendor-catalog.md)

<a id="31-벤더-검색페이지"></a>

### 3.1 벤더 검색·페이지

→ [벤더·플랜 카탈로그](api/vendor-catalog.md)

<a id="32-선택한-벤더의-플랜"></a>

### 3.2 선택한 벤더의 플랜

→ [벤더·플랜 카탈로그](api/vendor-catalog.md)

<a id="4-오류와-데이터-일관성"></a>

## 4. 오류와 데이터 일관성

→ [공통 HTTP 규칙](api/common.md)

<a id="7-등록-제품과-계약-정정"></a>

## 7. 등록 제품과 계약 정정

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="71-식별자와-화면별-조회"></a>

### 7.1 식별자와 화면별 조회

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="72-상태합계미제공-값"></a>

### 7.2 상태·합계·미제공 값

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="73-관측-지표-adr-0044"></a>

### 7.3 관측 지표 (ADR 0044)

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="74-좌석-원천-adr-0048"></a>

### 7.4 좌석 원천 (ADR 0048)

→ [좌석 원장·회수·복원](api/seats.md)

<a id="계약-기간-상태-contractstatus"></a>

### 계약 기간 상태 (`contractStatus`)

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="공통-헤더-수집-현황"></a>

## 공통 헤더 수집 현황

→ [수집 상태](api/ingest-status.md)

<a id="판정-adr-0041"></a>

### 판정 (ADR 0041)

→ [수집 상태](api/ingest-status.md)

<a id="세는-값과-null"></a>

### 세는 값과 null

→ [수집 상태](api/ingest-status.md)

<a id="임계값-설정"></a>

### 임계값 설정

→ [수집 상태](api/ingest-status.md)

<a id="구성원의-사용-예정-제품"></a>

### 구성원의 사용 예정 제품

→ [구성원 조회·편집](api/members.md)
