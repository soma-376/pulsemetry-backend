# 0033. 로그인은 온보딩과 분리하고 최초 정책에서 manifest를 생성한다

## Status

Accepted — ADR 0018의 미설정 세션 표현을 보완하고 ADR 0029의 초기 manifest 사전 준비 조건을 대체한다. 기존 세션 회전·인가·온보딩 완료 조건은 유지한다.

수집 요약 미생성 결정은 [ADR 0034](0034-조직-생성과-빈-수집-요약을-원자적으로-초기화한다.md)가 대체한다.

## Context

첫 온보딩 조직은 계정만 있어도 로그인해야 한다. 기존 `UserAuthService.newSession`은 활성 manifest가 없으면 409를 반환하여 온보딩을 시작할 수 없었다. B 시드에 manifest를 미리 넣는 방식은 이 문제를 숨긴다.

## Decision

- 사용자 로그인과 인증 코드 교환은 계정·조직의 활성 상태 및 자격증명으로 판단한다. 계약·벤더·manifest·온보딩 완료 여부는 로그인 조건이 아니다.
- 활성 manifest가 없는 새 세션의 `manifest_revision`은 0이다. JWT 필드와 DB 정수 컬럼은 유지한다. 0은 실제 manifest 판번호나 설치 허가가 아니다. 일반 RT 갱신은 0을 포함한 세션의 기존 revision을 보존한다.
- 온보딩 조회는 manifest가 없으면 정책 version 0, 미확인 상태를 반환한다. 최초 정책 저장은 `expectedVersion=0`일 때만 허용한다. 기존 조직 잠금 안에서 manifest와 정책 확인 기록을 함께 저장한다. 경합·재전송으로 낡은 version을 보내면 409다.
- 최초 manifest는 enrollment 앱이 기존 `ManifestPayload` 계약 타입으로 만든다. 수집 주소는 `pulsemetry.management.onboarding-otlp-endpoint` 서버 설정에서 받으며 운영 기본값은 없다. 주소가 없거나 계약에 맞지 않으면 최초 정책 저장만 `409 manifest_not_configured`로 거부하고 로그인은 허용한다.
- 초기 전송 프로토콜은 `http/protobuf`, logs·metrics·traces는 활성화한다. privacy는 전부 false에서 시작하고 사용자가 고른 원문 수집 여부만 두 플래그에 반영한다. 기존 활성 manifest의 변경은 기존 내용을 복사하여 다른 필드를 보존한다.
- local 프로필은 수집 주소를 `http://localhost:4316`으로 제공한다. 운영은 `PULSEMETRY_ONBOARDING_OTLP_ENDPOINT`로 명시한다.
- B 시드는 조직과 오너 한 명만 명시적으로 생성한다. manifest·팀·벤더·계약·초대·설치·완료 기록은 만들지 않는다. 수집 이력 요약 미생성은 ADR 0034로 대체되었으며 조직 생성 트리거가 빈 요약을 초기화한다.
- 설치 등록과 telemetry token 발급 경로의 manifest 검증은 그대로 유지한다. DDL 및 외부 설치 응답 구조는 변경하지 않는다.

## Alternatives Considered

시드에서 기본 설정을 사전 생성하면 실제 신규 조직의 로그인 문제를 감춘다. JWT에서 revision 필드를 없애거나 null로 바꾸면 기존 검증기와 DB 구조를 함께 바꿔야 한다. 운영 수집 주소를 추측하면 클라이언트를 잘못된 서버로 연결할 수 있다.

## Consequences/Tradeoffs

### Positive

조직과 오너만 있는 상태에서 로그인하고 정책·벤더를 저장해 온보딩을 완료할 수 있다. 계약 상세 입력은 필수가 아니다.

### Negative

운영자는 최초 정책 저장 전에 수집 주소를 설정해야 한다. 온보딩 전에 발급한 세션은 정책을 저장한 뒤에도 revision 0을 유지하므로 이 값을 온보딩 진행 상태로 해석하면 안 된다. 진행 상태는 온보딩 API에서 조회한다.

ADR 0034 적용 후 새로 생성한 B는 빈 요약이 있으므로 정상적인 빈 대시보드를 반환한다. 적용 전 조직에서 요약·백필 완료 근거가 모두 없으면 기존 규칙대로 503을 반환한다. 이는 사용자 인증이나 온보딩 API의 실패와 구분한다.

## Acceptance Criteria

manifest 없는 로그인·코드 교환·갱신·검증, 잘못된 비밀번호·정지 계정 거부, 최초 정책 생성과 version 경합, 기존 manifest 보존, 계약 없는 온보딩 완료, B 시드의 두 테이블만 생성 여부를 검증한다.
