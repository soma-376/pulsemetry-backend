# 0057. 배포 환경의 메일은 SES API로 보내고 발송 구현은 설정으로 고른다

## Status

Accepted — [ADR 0037](0037-메일은-outbox에-적재하고-enrollment-api의-발송-작업이-SMTP로-보낸다.md)의 "발송 작업이 SMTP로 보낸다"에 딸린 결정 — 발송 구현이 SMTP 하나라는 것, 메일 설정 목록, `send-timeout`의 뜻, `failure_detail`이 SMTP 응답 코드뿐이라는 것, `sent`가 SMTP 서버의 수락이라는 것 — 을 부분 대체한다.
outbox 적재·하나씩 선점·임대·상태 전이·중복 방지 키·본문 암호화·공개 실패 코드 어휘·모듈 배치는 ADR 0037 그대로 유효하다.
[ADR 0038](0038-초대-메일은-코드를-본문과-링크의-fragment에만-싣고-발송-상태를-발급과-따로-낸다.md)·[ADR 0043](0043-설치-업데이트-안내는-구성원에게-보내는-메일이고-결과는-발송-결과다.md)의 "SMTP 서버가 받았다"는 "설정된 메일 공급자가 받았다"로 읽는다.

## Context

메일은 업무 쓰기와 같은 트랜잭션에서 `enrollment.mail_outbox`에 적재하고, enrollment-api의 발송 작업(`MailDispatchJob` → `MailDispatcher.runOnce`)이
하나씩 선점해 `MailTransport.send`로 보낸다(ADR 0037). 지금 `MailTransport`의 구현은 `SmtpMailTransport` 하나다.

이미 정해진 사실은 다음과 같다.

- 메일을 만드는 업무는 넷이다 — 초대 발급·재발급(`InvitationMailer`), 활성 구성원의 설치 코드(ADR 0055), 문의 통지(`InquiryNotifier`), 설치 업데이트 안내(`InstallationNotifier`, ADR 0043).
  본문은 앱이 만드는 UTF-8 일반 텍스트이고 HTML·저장 템플릿·첨부가 없다.
- 켜면 SMTP 호스트·포트·계정·암호·STARTTLS 여부가 모두 필수다(`MailConfig.mailTransport`). 배포 환경에서는 SMTP 계정과 암호를 정적 비밀로 주입해야 한다.
- 로컬 프로필과 테스트는 Compose의 메일 수신 컨테이너(Mailpit)로 실제 SMTP 수신을 확인한다(`application-local.yaml`, `MailDispatchTest`, `InvitationMailApiTest`).
- 이 저장소는 이미 AWS SDK for Java v2를 BOM(`awsSdk`, `gradle/libs.versions.toml`)으로 쓴다 — 원본 아카이브의 S3 적재(ADR 0012).
- 라이브러리 모듈은 Spring 조립과 starter를 갖지 않는다(ADR 0011). 발송 포트와 outbox 는 `:libs:enrollment-persistence`, 발송 구현과 설정은 `:apps:enrollment-api`다(ADR 0037).
- 공개 발송 상태(`delivery.failureCode`, 설치 안내 작업 대상의 실패 코드)는 ADR 0037의 분류 어휘를 그대로 낸다. 화면은 그 어휘마다 사용자 문구를 둔다. `failure_detail`은 API에 나가지 않는다(`MailDeliveryView`, `InstallationNotifier.reconcile`).
- 발송 작업의 재시도는 outbox 가 정한다 — 일시 실패는 재시도 간격 뒤 `queued`, 최대 시도에 닿으면 `failed`(`MailOutbox.failed`). 선점 임대는 `send-timeout`의 네 배다.
- 발송 리전·발신 도메인과 주소·SES 계정의 sandbox 여부·IAM 권한·DNS 는 이 저장소 밖에서 준비하며 아직 정해지지 않았다. enrollment schema 는 바꾸지 않는다.

정하지 않으면 생기는 일: 배포에서도 SMTP 계정·암호라는 정적 비밀을 하나 더 운영해야 하고, 메일 공급자가 바뀔 때마다 설정 계약과 실패 분류를 다시 정해야 한다.
SDK가 스스로 재시도하면 outbox 의 `attempts`와 실제 요청 횟수가 어긋나고 임대 계산의 전제(한 시도 ≤ `send-timeout`)가 깨진다.
새 공급자의 오류를 새 공개 코드로 내면 화면 문구가 없는 코드가 사용자에게 그대로 보인다.

## Decision

- **발송 구현은 설정 `pulsemetry.mail.provider`로 고른다 — `smtp` 또는 `ses`.** 기본값이 없다. 메일을 켰으면 반드시 고르고, 비었거나 다른 값이면 기동하지 않는다.
  고른 구현의 설정만 검사한다 — `ses`는 SMTP 계정을, `smtp`는 SES 리전을 요구하지 않는다. 한 구현이 실패해도 다른 구현으로 넘어가지 않는다(자동 fallback 없음).
- **로컬·테스트는 `smtp`(Mailpit)를 유지하고, AWS 배포 환경은 `ses`를 쓴다.** `application-local.yaml`은 `smtp`를 명시한다. 배포 환경은 `PULSEMETRY_MAIL_PROVIDER=ses`를 명시한다.
- **SES 구현(`SesMailTransport`)은 AWS SDK v2의 SES API `SendEmail`을 부른다.** 단순(Simple) 내용에 발신 주소, 수신자 한 명(`ToAddresses`만), UTF-8 제목과 텍스트 본문을 싣는다.
  `X-Pulsemetry-Mail-Id` 헤더에 outbox 메일 ID 를 싣는다 — 받은 쪽이 재전송을 알아보는 용도이고 SES 의 중복 방지 키가 아니다.
  configuration set 이름은 설정(`pulsemetry.mail.ses.configuration-set`)으로 받는다. 비어 있으면 요청에서 뺀다. 이름을 지어내거나 SES 리소스를 만들지 않는다. 태그는 싣지 않는다.
- **자격 증명은 SDK 기본 공급자 체인이다.** 배포에서는 enrollment-api 태스크 역할의 임시 자격 증명이다. 정적 AWS 키 설정을 두지 않는다.
  리전은 필수 설정 `pulsemetry.mail.ses.region`이고 다른 AWS 설정에서 추론하지 않는다. 클라이언트는 발송 구현 하나가 갖고 컨텍스트가 닫힐 때 함께 닫는다.
  만들 때 AWS 에 말하지 않는다 — 설정 검증은 형식만 본다. 메일이 꺼져 있으면 클라이언트를 만들지 않는다.
- **SDK는 한 번만 시도한다. 재시도는 outbox 가 갖는다.** SES 모드의 `send-timeout`은 API 호출 전체의 제한이고, 한 시도·연결·읽기의 제한도 같은 값이다.
  SMTP 모드의 `send-timeout`은 지금처럼 연결·읽기·쓰기 각각의 제한이다. 선점 임대는 두 구현 모두 `send-timeout`의 네 배다.
- **실패 분류는 SDK 예외 타입과 오류 코드·HTTP 상태로만 한다. 공개 실패 코드는 ADR 0037 의 어휘를 그대로 쓰고 SES 세부는 `failure_detail`의 정해진 토큰에만 담는다.**

  | 상황 | 공개 코드 | `failure_detail` | 처리 |
  |---|---|---|---|
  | 주소 형식 오류·수신자가 한 명이 아님(호출 전 검사) | `invalid_address` | `ses_invalid_address` | 영구 |
  | `MessageRejected` | `message_rejected` | `ses_message_rejected` | 영구 |
  | `BadRequestException` | `message_rejected` | `ses_bad_request` | 영구 |
  | `TooManyRequestsException`, 그 밖의 throttling 오류 코드·429 | `send_error` | `ses_throttled` | 재시도 |
  | `LimitExceededException` | `send_error` | `ses_limit_exceeded` | 재시도 |
  | `MailFromDomainNotVerifiedException`, `NotFoundException` | `send_error` | `ses_configuration_error` | 재시도 |
  | `SendingPausedException`, `AccountSuspendedException` | `send_error` | `ses_sending_disabled` | 재시도 |
  | 401·403, 인증 오류 코드(접근 거부·알 수 없는 자격 증명·서명·만료 토큰) | `send_error` | `ses_auth_failed` | 재시도 |
  | 5xx | `send_error` | `ses_unavailable` | 재시도 |
  | 호출·시도 제한 시간 초과, 읽기 시간 초과 | `send_error` | `ses_timeout` | 재시도 — SES 가 받았는지 알 수 없다 |
  | 연결 실패 등 입출력 오류 | `send_error` | `ses_unavailable` | 재시도 |
  | 분류하지 못한 SDK 오류 | `send_error` | `ses_unknown_error` | 재시도 |

  AWS 오류 메시지 원문은 저장하지도 로그에 남기지도 않는다. 메시지 문자열에서 주소나 사유를 뽑지 않는다.
  `failure_detail`은 SMTP 응답 코드나 위 토큰이다. 이 열의 V14 주석("SMTP 응답 코드")은 바꾸지 않는다 — 적용된 마이그레이션이다.
- **SES 접수 ID(`MessageId`)는 운영 로그에만 남긴다.** DB 스키마·마이그레이션과 `MailTransport.send(mail): Unit`은 그대로다.
  수락 직후 `event=mail_provider_accepted provider=ses mail_id kind attempt provider_message_id`를 남긴다. 실패는 `event=mail_provider_failed`에 세부 토큰·HTTP 상태·AWS 요청 ID·예외 종류만 남긴다.
  수신자·제목·본문·요청과 응답 전체는 남기지 않는다. 수락 로그는 SES 수락의 증거이고 뒤이은 outbox `sent` 기록의 성공이 아니다.
- **`sent`는 "설정된 메일 공급자가 받았다"이다.** SES 에서는 `SendEmail`이 성공 응답을 준 것이다. 수신함 도착·읽음·반송 없음·정책 적용을 뜻하지 않는다.
  반송·신고 이벤트를 앱이 받지 않는다. SES 의 수신 거부 목록(suppression)에 걸린 수신자도 `sent`일 수 있다.
- **배포 준비 조건은 `docs/mail-operations.md`가 담는다** — 환경 변수·비밀·태스크 역할 권한(`ses:SendEmail`만)·발신 identity·네트워크·피드백 경로와 전환·복구 절차.
  인프라 코드는 이 결정으로 바뀌지 않는다.

## Alternatives Considered

### A. 배포에서도 SMTP를 쓰고 SES 의 SMTP 인터페이스에 붙는다
- 장점: 코드 변경이 없다. 설정만 바꾼다.
- 단점: SES SMTP 자격 증명(사용자 이름·암호)을 정적 비밀로 만들어 주입·교체해야 한다. SMTP 응답 코드로는 SES 의 계정 정지·발송 중지·configuration set 오류를 구분하지 못한다. 접수 ID 를 받기 어렵다.
- 탈락 이유: 태스크 역할로 정적 비밀 없이 보낼 수 있는데 비밀을 하나 더 운영할 이유가 없다.

### B. SMTP를 걷어 내고 로컬·테스트도 SES 로 보낸다
- 장점: 발송 구현이 하나다.
- 단점: 로컬 개발과 CI가 AWS 계정·자격 증명을 요구하거나, SES 를 흉내 낸 서버로는 실제 수신 내용(본문·헤더)을 사람이 확인할 수 없다. 기존 Mailpit 기반 회귀 테스트가 사라진다.
- 탈락 이유: 로컬·CI 는 AWS 없이 돌아야 한다. 두 구현의 비용은 같은 포트 뒤의 어댑터 하나다.

### C. SES 장애 때 SMTP 로 자동 전환한다
- 장점: 공급자 하나의 장애에도 메일이 나간다.
- 단점: 배포 환경에 쓸 수 있는 SMTP 가 따로 있어야 한다. 한 메일이 두 공급자로 나가 중복될 수 있고 어느 쪽이 받았는지 기록이 갈린다.
- 탈락 이유: 일시 장애는 outbox 재시도가 맡는다. 공급자 교체는 사람이 설정으로 한다.

### D. SDK 기본 재시도를 그대로 쓴다
- 장점: 순간적인 throttling·5xx 를 호출 안에서 넘긴다.
- 단점: outbox `attempts`가 실제 요청 횟수와 다르다. 한 시도가 `send-timeout`을 여러 번 쓸 수 있어 임대(네 배)를 넘길 수 있다 — 임대가 끝나면 다른 작업이 같은 메일을 다시 잡는다.
- 탈락 이유: 재시도 정책은 한 곳(outbox)이 갖는다.

### E. SES 오류마다 새 공개 실패 코드를 낸다
- 장점: 화면이 SES 의 사유를 자세히 보여 줄 수 있다.
- 단점: 화면에 문구가 없는 코드가 그대로 노출된다. 공급자를 바꾸면 공개 어휘도 바뀐다.
- 탈락 이유: 운영자에게 필요한 세부는 `failure_detail`과 로그로 충분하다. 공개 어휘는 공급자와 무관하게 유지한다.

### F. SES 접수 ID 를 outbox 열에 저장한다
- 장점: 메일 행과 SES 이벤트(반송·신고)를 DB 에서 바로 잇는다.
- 단점: 마이그레이션과 포트 시그니처 변경이 필요하다. 응답이 유실된 시도는 ID 가 없고, 재시도마다 ID 가 생겨 "메일 하나 = ID 하나"가 아니다. 반송 이벤트를 받는 쪽이 아직 없다.
- 탈락 이유: 지금은 연결할 소비자가 없다. 반송 처리를 도입할 때 함께 정한다.

## Consequences/Tradeoffs

### Positive
- 배포 환경은 SMTP 계정·암호 없이 태스크 역할로 보낸다. 주입할 비밀은 outbox 본문 암호화 키 하나다.
- 업무 트리거·outbox 의미·공개 발송 상태가 그대로라 화면과 API 소비자는 바뀌지 않는다.
- 로컬·CI 는 AWS 계정 없이 돌고, SES 구현은 실제 SDK 를 로컬 HTTP 모의 서버에 붙여 요청 직렬화·오류 해석·단일 시도·제한 시간을 검증한다(`SesMailTransportTest`).
- 운영자는 `failure_detail`과 로그의 `ses_*` 토큰·요청 ID 로 설정·권한 문제와 공급자 장애를 가른다.

### Negative
- 발신 identity 미검증도 `MessageRejected`로 올 수 있어 영구 실패로 닫힌다. 닫힌 메일은 본문이 지워져 자동으로 다시 보낼 수 없다 — 업무 절차(재발급 등)로 새 메일을 만든다.
  - 완화: 배포 전에 identity 검증과 테스트 수신자 발송을 확인한다(`docs/mail-operations.md`).
- 설정·권한 오류(`ses_auth_failed`·`ses_configuration_error`·`ses_sending_disabled`)는 일시 실패로 재시도하다 최대 시도에서 `failed`가 된다. 오래 방치하면 그 사이의 메일이 모두 실패로 닫힌다.
- 자격 증명을 찾지 못한 오류는 SDK 가 전용 타입을 주지 않아 `ses_unknown_error`나 `ses_unavailable`로 보인다.
- 적어도 한 번은 그대로다 — SES 가 받은 뒤 응답이 유실되거나 결과 기록 전에 죽으면 같은 메일이 다시 나가고 접수 ID 가 하나 더 생긴다. 사용자 정의 헤더는 SES 의 멱등 처리가 아니다.
- 접수 ID 는 로그 보존 기간이 지나면 사라진다. 영속 발송 이력이 아니다.
- `sent` 뒤의 반송·신고·수신 거부는 앱이 모른다.
- 새 필수 설정(`provider`, SES 모드의 `ses.region`)이 생겨 메일을 켠 기존 배포 설정도 함께 바꿔야 한다.

## Follow-up
- 배포 환경 준비 — 리전·발신 도메인과 주소·DKIM·DMARC·sandbox 해제·태스크 역할 권한·환경 변수와 비밀 주입(`docs/mail-operations.md` §2·§3). 인프라 작업이다.
- 실제 SES 로 보내는 smoke 확인(`docs/mail-operations.md` §5). 이 결정의 코드 검증은 실제 발송을 포함하지 않는다.
- 반송·신고 이벤트 소비와 접수 ID 의 영속화 — 필요해지면 `sent`와 비동기 반송의 관계를 제품 계약부터 정한다.
- 발송량과 SES 한도가 정해지면 재시도 간격·최대 시도·발송 주기를 다시 본다. 인스턴스가 여럿이면 초당 합계가 늘어난다 — 한도를 넘으면 속도 제한을 설계한다.
- 무중단 공급자 전환이 필요해지면 업무 적재와 발송 선점을 따로 멈추는 제어를 설계한다(지금 `pulsemetry.mail.enabled=false`는 적재까지 멈춘다).

## References
- [ADR 0011](0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md) · [ADR 0012](0012-원본-아카이브를-S3에-쓰고-파일-구현은-로컬에만-남긴다.md) · [ADR 0037](0037-메일은-outbox에-적재하고-enrollment-api의-발송-작업이-SMTP로-보낸다.md) · [ADR 0038](0038-초대-메일은-코드를-본문과-링크의-fragment에만-싣고-발송-상태를-발급과-따로-낸다.md) · [ADR 0043](0043-설치-업데이트-안내는-구성원에게-보내는-메일이고-결과는-발송-결과다.md) · [ADR 0055](0055-활성-구성원의-설치-코드는-가입-권한을-닫은-새-초대로-발급한다.md)
- `apps/enrollment-api/.../mail/MailConfig.kt` · `SesMailTransport.kt` · `SmtpMailTransport.kt`, `libs/enrollment-persistence/.../mail/MailOutbox.kt`
- `docs/enrollment-server-spec.md` §8(설정) · §12(초대 발송 상태·설치 업데이트 안내), `docs/mail-operations.md`
