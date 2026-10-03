# 0053. 데몬에 기대는 서버 동작은 telemetryctl 기본 브랜치의 구현만 전제한다

## Status

Accepted — [ADR 0005](0005-설치-부트스트랩-스크립트와-바이너리-서빙.md)의 바이너리 출처와
[ADR 0043](0043-설치-업데이트-안내는-구성원에게-보내는-메일이고-결과는-발송-결과다.md)의 안내 메일 본문을 개정한다.
업데이트 확인의 판 출처는 허브 ADR 0011(Proposed)과 같은 결정이다.

## Context

서버의 데몬 관련 경로와 서술은 telemetryctl 이 무엇을 하는지 전제한다. 그 전제가 telemetryctl 기본 브랜치(develop, `1741b2a`)의 실제 구현과 어긋나 있었다.

telemetryctl 기본 브랜치에 있는 것:

- 등록과 토큰: `internal/enrollment/client.go`의 `Enroll`(`POST /v1/enroll`)과 `RefreshTelemetryToken`(`POST /v1/installations/telemetry-token`),
  `cmd/telemetryctl/main.go`의 `enroll --invite <코드> --server <주소>`·`status`·`daemon`·`autostart` 명령.
- OTLP 전달: `internal/forward`. 4xx 는 폐기, 401·403 은 토큰을 갱신해 한 번 더, 408·425·429·5xx 는 `Retry-After`를 따라 재시도한다.
- 업데이트 확인: `internal/updatecheck/client.go`. 쿼리 셋과 `Accept: application/json`으로 묻고 404 를 미지원으로 본다.
- 릴리스: `.github/workflows/release.yml`과 `scripts/release.mjs`. 태그 `v<버전>`의 GitHub Release 에 데몬 자산
  `pulsemetry_cli_{os}_{arch}`(Windows만 `.exe`) 여섯, GUI 패키지 여섯, 그 모두의 해시를 담은 `SHA256SUMS`를 올린다.

telemetryctl 기본 브랜치에 없는 것:

- 설치 보고(`POST /v1/installations/{id}/heartbeat`)의 송신, 사용자 RT 로 하는 manifest 재조회(`GET /v1/manifest`), CLI 사용자 로그인(`/v1/auth/cli/*`)과 `login` 명령.
- 버전과 해시를 적은 `pulsemetry_release.json`.

서버 쪽의 어긋남:

- 업데이트 확인(`UpdateCheckController`)은 바이너리 디렉터리의 `pulsemetry_release.json`에서 판을 읽었다. 그 파일을 만드는 곳이 없어 모든 설치가 미지원이 된다.
- `/bin/{filename}`(ADR 0005)은 공개 이름 `pulsemetry_{os}_{arch}` 그대로의 파일을 서빙했다. 릴리스는 그 이름을 내지 않는다.
- 설치 업데이트 안내 메일(ADR 0043)은 `pulsemetry login`으로 로그인하면 데몬이 다음 보고 때 새 정책을 받아 적용한다고 안내했다. 그 명령도, 보고도, 재조회도 없다.

정하지 않으면 생기는 일: 서버가 쓸 수 없는 산출물을 기다리고, 사용자는 존재하지 않는 명령을 따라 한다.

## Decision

### 바이너리와 그 판은 릴리스 산출물 그대로에서 읽는다

- 바이너리 디렉터리(`pulsemetry.binaries.dir`) 안의 `v<SemVer>` 디렉터리 하나가 릴리스 하나다. 운영자는 그 태그의 릴리스 자산과
  `SHA256SUMS`를 받은 그대로 둔다. 이름을 바꾸거나 메타데이터를 따로 만들지 않는다.
- 이런 디렉터리가 여럿이면 판이 가장 높은 하나가 이 서버의 릴리스다. 그 릴리스에 없는 대상은 낮은 판으로 내려가지 않는다.
- `SHA256SUMS`는 줄마다 소문자 hex 64자, 공백 둘, 자산 이름이다. 데몬 자산이 아닌 줄은 무시하고, 형식이 틀린 줄이 하나라도 있으면 그 릴리스를 읽지 않는다.
- 공개 이름 `pulsemetry_{os}_{arch}`(ADR 0005의 여섯)는 같은 대상의 `pulsemetry_cli_{os}_{arch}`로 대응한다.
  그 자산이 있고 SHA-256 이 `SHA256SUMS`와 같을 때만 `/bin/{filename}`이 내려주고 업데이트 확인이 그 판을 답한다. 두 경로가 같은 확인을 쓴다.
- 릴리스 디렉터리가 하나도 없으면 `/bin/{filename}`은 공개 이름 그대로의 파일을 내려준다(기존 배치). 그 판은 알 수 없으므로 업데이트 확인은 404 다.

### 없는 명령과 동작을 안내하지 않는다

- 설치 업데이트 안내 메일은 telemetryctl 기본 브랜치에 있는 명령만 쓴다 — `pulsemetry status`로 데몬이 도는지 보라고 한다.
  지금의 데몬은 새 정책을 스스로 받아 오지 않으므로, 새 정책을 적용하려면 관리자에게 설치 안내를 다시 받아 다시 설치하라고 적는다.
- 설치 보고·재조회·CLI 로그인의 서버 경로는 지우지 않는다. 명세와 화면은 그 경로의 데몬 쪽 구현이 없다는 사실을 함께 적는다.
  보고하지 않는 설치의 적용 판은 확인 불가다 — 보고가 없다고 적용이나 미적용으로 추정하지 않는다(ADR 0043의 판정 그대로).

## Alternatives

### A. `pulsemetry_release.json`을 유지하고 배포 때 손으로 만든다
- 장점: 서버 코드를 바꾸지 않는다.
- 단점: 릴리스가 내지 않는 파일이다. 버전과 해시를 사람이 옮겨 적고, 같은 해시가 `SHA256SUMS`와 두 곳에 생긴다.
- 탈락 이유: 릴리스가 이미 태그와 `SHA256SUMS`로 같은 사실을 낸다.

### B. 운영자가 자산 이름을 공개 이름으로 바꿔 놓는다
- 장점: `/bin`의 파일 해석을 바꾸지 않는다.
- 단점: 이름을 바꾸면 `SHA256SUMS`의 이름과 어긋나 해시를 확인할 수 없다. 판을 알려 줄 곳도 없다.
- 탈락 이유: 확인할 수 없는 파일을 그 판이라고 말하게 된다.

### C. 원격에 없는 클라이언트 동작을 서버 쪽 서술에서 그대로 둔다
- 장점: 데몬 쪽 구현이 생기면 고칠 곳이 없다.
- 단점: 지금 받는 사람에게 존재하지 않는 명령을 안내한다.
- 탈락 이유: 안내는 지금 배포된 데몬으로 할 수 있는 일이어야 한다.

## Consequences/Tradeoffs

### Positive
- 릴리스를 받아 두기만 하면 업데이트 확인과 바이너리 서빙이 함께 동작한다. 손으로 만드는 산출물이 없다.
- `/bin`이 해시를 확인한 파일만 내려준다(ADR 0005 Follow-up 의 무결성 항목 중 서버 쪽).
- 메일과 명세가 지금의 데몬으로 할 수 있는 일만 말한다.

### Negative
- 운영자가 릴리스를 태그 이름 디렉터리에 받아 두는 단계가 생긴다. 빠뜨리면 모든 설치가 업데이트 확인에서 미지원이다.
- 릴리스 디렉터리가 있으면 그 릴리스에 없는 대상의 평면 파일은 서빙하지 않는다.
- 이미 설치된 기기에 새 정책을 적용하려면 다시 설치해야 한다. 안내 메일은 그 수고를 줄이지 못한다.

## Follow-up
- infra: 릴리스 자산을 서버의 바이너리 디렉터리로 옮기는 배포 단계.
- telemetryctl: 설치 보고 송신과 RT 재조회가 생기면 안내 메일의 확인 절차와 명세의 "데몬 쪽 구현 없음" 서술을 다시 본다.
