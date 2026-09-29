# 개발용 시드

`:tools:dev-seed` 하나에 생성·적재·검증을 모으고 Docker에서만 실행한다(ADR 0031).
서버는 local 프로필에서도 시드를 실행하지 않는다. Spring 서버는 호스트에서 실행한다.
Compose가 기존 소유 모듈의 마이그레이션 → A/B/C → 대시보드 DB 계정·인증 키를 준비한다.
데이터는 화면·집계 확인용이며 ETL 성능 측정용이 아니다.

## DB와 시드 준비

저장소 루트의 PowerShell에서 실행한다.

```powershell
docker compose up -d --build
docker compose logs -f dev-seed
docker compose ps -a dev-seed
```

`up -d`는 적재 완료 전에 반환한다. 로그의 `개발 Compose 스키마·시드 준비 완료`와 `Exited (0)`을 확인한다.
실패 원인을 해결한 뒤 `docker compose run --rm dev-seed`로 다시 실행한다.
완료된 A/B/C 데이터는 날짜가 바뀌거나 개발 중 수정해도 자동 덮어쓰기·초기화하지 않는다.
미완료 적재는 실패로 알리며 해당 시나리오를 명시적으로 초기화해야 한다.
Compose 서비스 이름과 고정 개발 계정만 사용하고 Docker 소켓은 사용하지 않는다.

최초 적재 기준일은 서울 기준 실행일이며 시나리오 기본값은 `A,B,C`다. 선택적으로 지정할 수 있다.

```powershell
$env:PULSEMETRY_LOCAL_SEED_DATE = '2026-09-28'
$env:PULSEMETRY_LOCAL_SEED_SCENARIOS = 'A,B,C'
docker compose up -d --build
```

시드 조직은 A/B/C 세 개다. A는 정책 확인·활성 벤더 선택·온보딩 완료 상태이며 B/C는 미완료다.
B는 조직과 `owner@seed-b.example.test` 한 명만 생성한다. manifest·수집 이력·팀·벤더도 없다.
조직 생성 트리거가 필수 초기 상태인 빈 수집 요약을 함께 만든다(ADR 0034). 수신·관측 시각은 모두 NULL이며
신규 B의 개요 응답은 `200`, `meta.dataState=never_observed`, `ingest.status=empty`다.
A/C는 자동 생성된 요약에 합성 이력을 채운다. 기존 조직의 누락된 요약은 자동 보충하지 않는다.
오너 로그인 후 첫 정책 저장 시 서버 설정으로 manifest를 생성한다(ADR 0033).
`tenants.onboarding_completed`는 완료 시각에서 계산하므로 시각과 완료 여부가 어긋나지 않는다.
기존 기본 로컬 개발 조직과 `local-owner@example.com`은 더 이상 생성하지 않는다(ADR 0032).
CLI 설치는 A의 `plan`에 나오는 대기 초대 또는 관리자 API로 발급한 새 초대를 사용한다.
시드 manifest의 수신 주소는 `http://localhost:4316`이다.
기존 ready 데이터는 자동 갱신하지 않는다. 새 시나리오 정의와 엄격히 비교하려면 해당 조직을 명시적으로 reset 후 재적재한다.

## 서버 실행

시드 완료 후 각 터미널에서 실행한다.

```powershell
.\gradlew.bat :apps:enrollment-api:bootRun --args="--spring.profiles.active=local"
.\gradlew.bat :apps:dashboard-api:bootRun --args="--spring.profiles.active=local"
```

각 앱의 `application-local.yaml`이 로컬 인증·관리 기능과 DB 계정 설정을 제공한다.
Compose가 `build/dev-auth`에 인증 키와 응답 암호화 키를 만들며 기존 키는 덮어쓰지 않는다.
서버는 키를 읽기만 한다. 키가 없으면 먼저 Compose 초기화를 실행한다.
키 경로는 Gradle bootRun의 앱 디렉터리 기준이며 다른 위치에서 실행할 때는
`PULSEMETRY_DEV_AUTH_DIR`에 키 디렉터리의 절대 경로를 지정한다.
`PULSEMETRY_ADMIN_API_TOKEN`·`PULSEMETRY_TOKEN_HASH_SECRET` 환경변수는 로컬 기본값보다 우선한다.
서버 포트를 바꾸려면 같은 `--args`에 `--server.port=18080`을 추가한다.
키 디렉터리는 호스트 바인드이므로 `docker compose down -v`로 지워지지 않는다.

## 수동 관리

같은 컨테이너 진입점에 명령을 전달한다. `plan`·`apply`·`verify`는 적재 기준일을 명시한다.
`plan`은 DB에 접속하지 않고 생성할 데이터 요약만 출력한다.

```powershell
docker compose run --rm --no-deps dev-seed plan 2026-09-28
docker compose run --rm dev-seed apply 2026-09-28
docker compose run --rm dev-seed verify 2026-09-28
```

`apply`·`verify`·`reset`은 기본 초기화로 스키마가 준비된 DB에서 사용한다.
같은 기준일·버전의 `apply` 재실행은 실제 DB를 검증하고 추가 INSERT를 하지 않는다.
기준일 변경·미완료 적재·시드 데이터 수정 후 원본으로 되돌릴 때는 선택한 시나리오만 초기화한다.
초기화는 그 시드 조직에 API로 추가한 데이터도 지운다. 다른 조직·전역 백필 상태는 보존한다.

```powershell
docker compose run --rm dev-seed reset C
docker compose run --rm dev-seed apply 2026-09-28 C
docker compose run --rm dev-seed verify 2026-09-28 C
```

여러 시나리오는 PowerShell에서 `'A,B'`처럼 따옴표로 감싼다. 생략 시 A/B/C 전체가 대상이다.
Gradle은 이미지 빌드와 단위 테스트에 사용하며 호스트 시드 실행 작업은 제공하지 않는다.

```powershell
.\gradlew.bat :tools:dev-seed:test
```

`docker compose down`은 DB 볼륨을 보존한다. `docker compose down -v`는 이 프로젝트의
PostgreSQL·ClickHouse 볼륨을 삭제하므로 시드 외에 직접 추가한 데이터도 모두 사라진다.

## 시나리오

| 시나리오 | 조직 ID | 내용 |
| --- | --- | --- |
| A | `1b59ab21-1788-35e0-bfd7-23baa88a35b4` | 온보딩 완료·선택 제품 4개. 관리자 2명·일반 구성원 10명·초대 대기 2명. 4팀+미배정, 팀 이동, 설치 11대. 두 도구·6모델의 56일 사용 이력 |
| B | `db1c8c6b-6970-38c6-821a-eb5e61b7a180` | 조직과 오너 1명만 존재. 첫 로그인·최초 온보딩용 |
| C | `4769355c-a20e-327f-89fc-fef69e94dfb6` | 8명, 이벤트 14건. 미확인 모델, 토큰·비용 누락, 과거 사용, 계약 없음 |

기준일 직전 28일이 현재 조회 구간이고 그 앞 28일은 이전 구간이다. 기준일 `2026-09-29`라면
현재 `2026-09-01`~`2026-09-28`, 이전 `2026-08-04`~`2026-08-31`이다(서울 시간).
A의 현재 활성 사용자는 8명, 이전 구간은 과거 전용 사용자 1명을 포함해 9명이다.
정확한 이벤트·수신·세션 수와 환산 비용은 기준일을 지정한 `plan` 출력으로 확인한다.
가상 요율을 사용하며 실제 공급자 요금·청구 금액을 뜻하지 않는다.
서로 다른 도구의 토큰 의미를 억지로 통일하지 않는다. API의 혼합 토큰 합계·비교 등은 null/unavailable일 수 있다.

계정은 `owner@seed-a.example.test` 또는 `admin@seed-a.example.test`다. C는 `seed-c`로 바꾼다.
B 계정은 `owner@seed-b.example.test` 하나뿐이다.
개발용 비밀번호는 `Pulsemetry-local-2026!`이며 DB에는 BCrypt 해시로 저장한다.
실제 로그인에는 해당 조직 ID도 함께 보낸다. 초대 코드는 `plan` 출력에서 확인한다.

### A의 PostgreSQL 기준 데이터

팀·구성원·설치 ID는 이름 기반 UUID로 재현한다. 날짜를 바꿔도 ID는 유지된다.
ClickHouse 이벤트·수신 기록은 이 ID를 참조한다. 기본 설치는 기준일 58일 전에 생성해 사용 기록보다 앞선다.

| 항목 | 구성 |
| --- | --- |
| 현재 팀 소속 | 플랫폼 4명, 제품 4명, 데이터 2명, 디자인 1명, 미배정 1명. 초대 대기 2명은 소속 없음 |
| 팀 이동 | `member2`가 기준일 14일 전 플랫폼 → 제품으로 이동. 이전 소속 종료 시각과 새 소속 시작 시각이 같음 |
| 설치 | 일반 구성원 10명에게 1대씩, `member2`에게 두 번째 설치 1대. Windows·macOS·Linux 포함 |
| 정책 적용 | 기본 설치 10대는 v1 적용 기록 있음. 두 번째 설치는 적용 확인·수신 이력 없음 |
| Claude 계약 | Team, 표준 8석 × $20 + 프리미엄 2석 × $100 = 월 $360 |
| OpenAI 계약 | Business, Standard 6석 × $25 + Premium 2석 × $125 = 월 $400. 최초 계약 미입력(v1) → 14일 전 관리자 입력(v2) 이력 |
| Copilot 계약 | Business 5석 × $19 = 월 $95, 기준일 전날 만료 |
| Cursor 등록 | 제품만 등록, 계약 없음 |

모든 단가는 합성 테스트 입력이며 공시 가격을 자동 적용한 것이 아니다. 계약 좌석 수는 실제 구성원 배정이나
관측 인원과 별개다. 유효 계약 두 개의 월 금액 합은 $760이지만, 만료·미입력 제품도 있어 전체 계약 합계는
API 정책에 따라 미확정(null)이다. 사용량을 좌석에 임의 배정해 이 값을 채우지 않는다.
Cursor·Copilot은 등록·계약 화면 확인용이며 수집 도구 지원을 뜻하지 않는다.

초대는 사용 완료 11개, 미사용 2개(대기·만료 각 1개)다. 오너·관리자는 로그인용 계정이며 설치는 없다.
두 번째 설치 ID의 생성 키는 `A/installation/2/secondary`이고, 기존 설치 키 `A/installation/2`와
동일한 구성원 `A/member/2`를 참조한다. 두 번째 설치에는 관측을 만들지 않아 미수신 상태를 확인할 수 있다.

이 변경은 시드 생성 코드에만 반영한다. 이미 ready인 A는 Compose 재시작이나 이미지 재빌드만으로 갱신되지 않는다.
새 구성을 적재하려면 위 수동 관리 절차에 따라 A를 명시적으로 reset/apply해야 하며, A에 직접 추가한 데이터와
기존 A의 ClickHouse 시드도 함께 초기화된다. PostgreSQL만 따로 적재하는 새 명령은 추가하지 않는다.

### A의 ClickHouse 사용 기록

`telemetry_events`에는 요청별 사용량을, `telemetry_ingest_ledger`에는 세션 단위로 묶은 수신을 적재한다.
`tenant_ingest_summary`와 설치의 마지막 수신 시각도 같은 기록에서 계산한다.
메트릭 사용량을 추가로 만들지 않아 대표 로그와 이중 합산하지 않는다.

| 확인 사례 | 데이터 구성 |
| --- | --- |
| 일별 추이 | 평일 중심, 토요일 소량, 대부분의 일요일은 사용 없음. 최근 28일 사용량 증가와 특정일 집중 사용 |
| 구성원별 차이 | 플랫폼의 고사용자, 데이터 팀의 특정 요일 집중 사용, 디자인의 저빈도 사용, 팀 미배정 사용자 |
| 팀 이동 | `member2`의 14일 전 이동 이력에 따라 귀속. 경계 직전 1초와 정각의 요청도 포함 |
| 사용자 중복 | `member2`·`member6`은 Claude Code와 Codex를 함께 사용하지만 각각 한 구성원으로 집계 |
| 미사용 구분 | `member10`은 최근 빈 수신만, `member11`은 이전 구간의 사용만, 두 번째 설치는 수신 자체가 없음 |
| 모델 | Claude Sonnet·Opus·Haiku, GPT-5·o3·GPT-5 mini. 가상 요율과 결정적인 분포로 모델별 차이를 재현 |
| 요청 세부 정보 | 세션당 3~8개 요청, 캐시 읽기·쓰기, 입력·출력, 요청 시간·첫 토큰 시간. 이동 경계용 세션은 요청 1개 |

날짜·구성원·세션·요청 키를 사용하며 난수나 실행 시각에 의존하지 않는다. 같은 기준일은 동일한 내용을 만든다.
합성 관측은 `seed-v2`, 가격은 `seed-synthetic-v2`로 구분하고 실제 원본·청구서·아카이브 경로를 꾸미지 않는다.
Codex의 cache write는 해당 없음(null)이며, Claude의 input은 캐시를 제외하고 Codex의 input은 캐시 읽기를 포함한다.
이 시드는 화면·집계 검증용이다. 운영 수집의 정상화·가격 검증 완료를 뜻하지 않는다.

`2026-09-29` 기준 검증값은 다음과 같다. 다른 기준일은 요일과 요청 분포에 따라 건수가 달라진다.

| 항목 | 값 |
| --- | ---: |
| 전체 요청 / 세션 / 수신 | 7,181 / 1,303 / 1,304 |
| 이전 28일 요청 / 활성 사용자 | 3,107 / 9 |
| 최근 28일 요청 / 활성 사용자 | 4,074 / 8 |
| 최근 28일 가상 환산 비용 | $283.87701946 |

## 기존 데이터를 유지하며 검증하기

시드 생성 코드, 컨테이너 이미지, 이미 적재된 DB, 프론트 fixture는 별개의 상태다.
이미지를 다시 빌드하거나 fixture를 내보내도 ready 상태의 A/B/C를 새 값으로 덮어쓰지 않는다.
카탈로그 마이그레이션(V10·V11)은 전역 기준 데이터를 변경하며 조직별 시드 재적재와 구분한다.
V9의 활성 제품 중복 검사가 실패하면 해당 중복을 먼저 검토한다. init이나 apply가 자동으로 정리하지 않는다.

기존 시드를 보존하는 실제 API 검증은 조회와 정리 가능한 쓰기부터 수행한다.
등록 제품을 만들 때는 `/vendors`와 카탈로그를 대조해 아직 등록하지 않은 kind를 선택한다.
표시 이름만 달리해도 같은 kind를 두 번 등록할 수 없다. 테스트가 만든 UUID를 기록하고 마지막에 그 자원만 정리한다.
계약 정정·삭제 검증을 위해 A의 기본 Claude 계약을 덮어쓰거나 테스트 후 A/B/C 전체를 초기화하지 않는다.

온보딩 완료 플래그, 정책 판, 계약 변경 이력은 삭제/재등록만으로 원래 상태가 되지 않는다.
B 최초 온보딩처럼 원상 복원이 안 되는 시험은 별도로 승인된 테스트 DB 또는 초기화 범위가 있을 때 수행한다.
정리 가능한 쓰기도 보관·변경 이력은 남는다. `verify`의 원본 일치 검증과 API 동작 검증을 같은 것으로 취급하지 않는다.

## 확인 범위

단위 테스트는 시나리오 재현성, 조직 격리, 팀 as-of 이력, 사용자 중복, 비용 누락, 초기화 SQL 범위를 확인한다.
`verify`는 PostgreSQL의 모든 시드 필드, ClickHouse의 원본 필드·물리 행 수와 조직/일자/모델/팀/구성원/도구 비용 합계를 확인한다.
DB 간 적재는 하나의 트랜잭션이 아니다. 실패하면 실행 기록이 loading으로 남고 자동 재적재하지 않는다.

## A회사 프론트 기준 데이터

A의 관리 벤더는 Claude(Team), ChatGPT/Codex, GitHub Copilot, Cursor다. Claude는 합성 테스트용 표준 8석 × $20 +
프리미엄 2석 × $100 = $360/월, OpenAI는 Standard 6석 × $25 + Premium 2석 × $125 = $400/월 계약이다.
Cursor는 계약 미입력이다. 계약의 상세 구성과 이력은 위 PostgreSQL 기준 데이터와 같다.
실제 가격표도, 사용 인원으로 계산한 좌석 배정도 아니다.
Copilot Business는 합성 테스트용 5석 × $19 = $95/월이며, 기준일 -60일 시작·기준일 -1일 종료로 만료된 계약이다.
프론트 fixture의 contractStatus는 A 기준일로 계산한다. DB의 기존 시드는 자동 덮어쓰지 않는다.
기존 Anthropic $1,200 / OpenAI $0 기간 약정은 호환성·0/null 회귀 검증용으로 남긴다.
기간 약정과 좌석 계약을 연결·합산하지 않는다. 관리 벤더의 관측 인원은 연결 근거가 없어 null이다.

아래 명령은 DB에 접속하지 않고 공개 fixture JSON만 출력한다.
카탈로그는 `src/main/resources/fixtures/vendor-catalog.json`의 고정 테스트용 목록이다.
운영 API와 계약 검증은 DB 카탈로그만 읽으며 이 fixture를 사용하지 않는다(ADR 0035).
비밀번호·해시·초대 코드·토큰은 출력하지 않는다. 서버/DB 시작이나 기존 시드 변경도 수행하지 않는다.

```bash
docker compose run --rm --no-deps -T dev-seed fixture 2026-09-28 A
```

시드나 고정 카탈로그 fixture 변경 후 `docker compose build dev-seed`로 이미지를 갱신하고 프론트의
`npm run fixtures:sync`를 실행한다. 기준일은 고정해 재현한다. UI의 오류/빈 목록 등은 이 기준 데이터의
별도 변형으로 만들며, 완료된 A의 원본 상태를 온보딩 미완료로 바꾸지 않는다.
기존 DB는 이 코드 변경이나 fixture 생성으로 갱신되지 않는다. 자동 초기화/덮어쓰기는 하지 않는다.
