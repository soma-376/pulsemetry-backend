# 메일 발송 운영 — SES 전환과 인프라 인계

메일 발송의 설정 계약, 배포 환경(SES)을 준비하는 인프라 작업에 넘길 조건, 실제 발송 확인 절차, 전환·복구 절차를 담는다.
결정은 [ADR 0037](adr/0037-메일은-outbox에-적재하고-enrollment-api의-발송-작업이-SMTP로-보낸다.md)(outbox)과
[ADR 0057](adr/0057-배포-환경의-메일은-SES-API로-보내고-발송-구현은-설정으로-고른다.md)(발송 구현 선택·SES)이 정한다. API 의 발송 상태는 `enrollment-server-spec.md` §12 가 정한다.

> **검증 상태.** 이 저장소의 코드는 로컬·CI 에서 AWS 계정 없이 검증했다 — SMTP 는 메일 수신 컨테이너(Mailpit)로, SES 는 실제 SDK 를 로컬 HTTP 모의 서버에 붙여서다.
> **실제 SES 로 보낸 적은 없다.** 리전·발신 주소·identity·IAM·DNS 는 아직 정해지지 않았고 인프라 코드도 바뀌지 않았다. CI 성공을 실제 발송 확인으로 읽지 않는다 — 실제 확인은 §5 다.

## 1. 보장하는 것과 보장하지 않는 것

메일을 만드는 업무는 넷이다 — 초대 발급·재발급, 활성 구성원 설치 코드, 문의 통지, 설치 업데이트 안내.
업무 쓰기와 같은 트랜잭션에서 `enrollment.mail_outbox`에 적재하고, enrollment-api 의 발송 작업이 하나씩 선점해 `pulsemetry.mail.provider`가 고른 구현(`smtp`·`ses`)으로 보낸다.

| 보장한다 | 보장하지 않는다 |
|---|---|
| 업무 쓰기가 롤백되면 메일도 없다 | 정확히 한 번 발송 — 공급자가 받은 뒤 결과 기록 전에 죽거나 응답이 유실되면 같은 메일이 다시 나갈 수 있다 |
| 같은 업무(중복 방지 키)를 다시 적재해도 메일은 하나다 | 수신함 도착·읽음 — `sent`는 공급자가 받았다는 뜻뿐이다 |
| 유효한 임대 안에서 한 메일을 두 작업이 동시에 잡지 않는다 | 반송·신고·SES 수신 거부 목록(suppression) 반영 — 앱은 이 이벤트를 받지 않는다 |
| 끝난 메일(`sent`·`failed`·`cancelled`)의 본문은 지운다 | 끝난 메일의 자동 재발송 — 본문이 없다. 업무 절차(초대 재발급 등)로 새 메일을 만든다 |

설치 업데이트 안내의 `succeeded`도 메일이 공급자에게 접수됐다는 뜻이고 설치가 새 정책을 적용했다는 뜻이 아니다.
`POST /v1/invitations`(관리자 API 키 경로)는 메일을 적재하지 않는다.

## 2. 설정 계약

운영 수치에는 배포 기본값이 없다. 메일을 켜면 공통 값과 **고른 구현의 값**이 모두 필요하고 하나라도 비면 기동하지 않는다. 고르지 않은 구현의 값은 보지 않는다.

| 환경 변수 | SES 모드 | SMTP 모드 | 뜻 |
|---|---|---|---|
| `PULSEMETRY_MAIL_ENABLED` | `true` | `true` | 메일 생산자·outbox·발송 작업 전체. 기본 `false` |
| `PULSEMETRY_MAIL_PROVIDER` | `ses` 필수 | `smtp` 필수 | 발송 구현. 기본값 없음. 자동 fallback 없음 |
| `PULSEMETRY_MAIL_FROM` | 필수 | 필수 | 발신 주소. SES 에서는 검증된 identity 의 주소여야 한다 |
| `PULSEMETRY_MAIL_ENCRYPTION_KEY` | 필수 | 필수 | 대기 중인 본문의 AES-256-GCM 키(Base64 32바이트). **SES 자격 증명이 아니다** — 역할로 보내도 필요하다 |
| `PULSEMETRY_MAIL_DISPATCH_INTERVAL` | 필수 | 필수 | 발송 작업이 outbox 를 보는 주기(ISO-8601) |
| `PULSEMETRY_MAIL_RETRY_INTERVAL` | 필수 | 필수 | 일시 실패 뒤 다시 시도하기까지의 간격 |
| `PULSEMETRY_MAIL_MAX_ATTEMPTS` | 필수 | 필수 | 한 메일의 최대 시도. outbox 시도 하나 = 공급자 요청 하나 |
| `PULSEMETRY_MAIL_SEND_TIMEOUT` | 필수 | 필수 | SES: **자격 증명 조회와 API 호출을 합친 발송 한 번의 상한**. SMTP: 연결·읽기·쓰기 각각의 제한. 선점 임대는 이 값의 네 배 |
| `PULSEMETRY_MAIL_SES_REGION` | 필수 | 불필요 | SES 리전(예: `ap-northeast-2` 형식). 다른 AWS 설정에서 추론하지 않는다 |
| `PULSEMETRY_MAIL_SES_CONFIGURATION_SET` | 선택 | 불필요 | 비우면 요청에서 뺀다. 영문·숫자·`_`·`-` 64자 이하 |
| `PULSEMETRY_MAIL_SMTP_HOST` · `_PORT` · `_USERNAME` · `_PASSWORD` · `_STARTTLS` | 불필요 | 필수 | SMTP 접속 |

- SES 자격 증명은 SDK 기본 공급자 체인에서 찾는다. 배포에서는 **enrollment-api 태스크 역할**이다. 정적 AWS 키 설정은 없다. 앱은 기동할 때 AWS 에 말하지 않는다 — 리전·권한·identity 오류는 첫 발송에서 드러난다(§4).
- SDK 의 호출 제한은 자격 증명 조회를 포함하지 않는다(ECS 자격 증명 엔드포인트는 연결·읽기 1초에 다섯 번까지 재시도한다). 그래서 발송 구현이 조회를 전용 스레드에서 `send-timeout` 기한까지만 기다리고
  남은 시간만 SES 호출에 준다. 기한을 넘기면 SES 에 묻지 않고 `ses_credentials_unavailable`로 실패하고, 조회는 뒤에서 이어져 다음 시도가 그 결과를 쓴다.
  SES 가 메일을 받을 수 있는 시각은 선점 뒤 `send-timeout` 안이므로 임대(네 배)를 넘긴 중복 발송은 생기지 않는다. `send-timeout`은 자격 증명 첫 조회(태스크 시작 직후)에 걸리는 시간도 감안해 정한다.
- 업무별로 함께 켜야 하는 설정이 있다 — 초대·설치 안내는 `pulsemetry.management.enabled`와 `PULSEMETRY_INVITATION_ACCEPT_URL`, 문의 통지는 `pulsemetry.inquiries.enabled`와 `PULSEMETRY_INQUIRIES_NOTIFICATION_RECIPIENT`(명세 §8).
- `PULSEMETRY_MAIL_ENABLED=false`는 **일시 정지가 아니다.** 꺼진 동안 생긴 업무 메일은 적재되지 않는다(발송 상태 `not_sent`/`mail_disabled`).
- local 프로필은 `smtp`(Compose 의 Mailpit `localhost:1025`, 화면 `localhost:8025`)로 켠다. local 값(5초·1분·3회·10초)은 운영 권장값이 아니다.
- SDK·HTTP 클라이언트 로거(`software.amazon.awssdk`, `org.apache.http`)를 DEBUG·wire 로 올리지 않는다. 요청 본문 — 초대 코드가 든 메일 본문 — 이 로그에 남는다.

## 3. 배포 준비 조건 — 인프라 작업에 넘기는 것

이 절의 실행은 인프라 작업의 몫이다. 이 저장소는 인프라 코드를 바꾸지 않았다.
조사 시점(인프라 저장소 `fb128f0`)의 dev `enrollment-api` 태스크에는 메일·관리·문의 활성화 설정과 메일 암호화 키 주입이 없고, SES 리소스·발송 권한도 없다.
prod 는 다른 앱 구성이므로 dev 설정을 옮긴 것으로 prod 준비를 끝냈다고 하지 않는다.

### 3.1 계정·리전·발신 identity

- 대상 리전에 **도메인 identity** 를 만들고 Easy DKIM 레코드를 DNS 에 넣는다. DNS 가 Route 53 인지 외부 서비스인지 먼저 확인한다.
- sandbox 여부와 production access 는 **리전마다** 따로다. sandbox 에서는 검증된 수신자와 mailbox simulator 로만 확인할 수 있다.
  [SES 리전](https://docs.aws.amazon.com/ses/latest/dg/regions.html) · [production access](https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html)
- 발신 주소(`PULSEMETRY_MAIL_FROM`)는 그 identity 의 주소다. **identity 미검증은 `MessageRejected`로 올 수 있고 그 메일은 영구 실패로 닫힌다** — 배포 전에 검증을 끝낸다.

### 3.2 도메인 인증

- DKIM 서명과 DMARC 정렬을 확인한다. custom MAIL FROM 이 필요하면 별도 하위 도메인에 MX·SPF 를 둔다. 기존 루트 SPF 를 임의로 바꾸지 않는다.
- DMARC 정책은 도메인 소유자와 정한다. [SES DMARC](https://docs.aws.amazon.com/ses/latest/dg/send-email-authentication-dmarc.html)

### 3.3 최소 권한

- **enrollment-api 태스크 역할에만** `ses:SendEmail`을 준다. 태스크 실행 역할·EC2 호스트 역할·다른 서비스 역할에 메일 권한을 주지 않는다. `ses:*`와 정적 AWS 키를 쓰지 않는다.
- 리소스는 발신 identity 와(쓰면) configuration set 의 ARN 으로 좁히고 발신 주소를 조건으로 묶는다. 예시 — 값은 확정 뒤 채운다.

```json
{
  "Effect": "Allow",
  "Action": "ses:SendEmail",
  "Resource": [
    "arn:aws:ses:<region>:<account>:identity/<domain>",
    "arn:aws:ses:<region>:<account>:configuration-set/<configuration-set>"
  ],
  "Condition": { "StringEquals": { "ses:FromAddress": "<PULSEMETRY_MAIL_FROM>" } }
}
```

### 3.4 설정과 비밀

- 공통·SES 설정(§2)은 ECS `environment`로, 기존 outbox 키(`PULSEMETRY_MAIL_ENCRYPTION_KEY`)는 Secrets Manager → ECS `secrets`로 넣는다. 값을 `environment`·output 에 두지 않는다.
- token hash·관리 응답·벤더 자격증명 키를 재사용하지 않는다 — 용도가 다른 비밀은 키를 나눈다(ADR 0037 대안 E).
- 인프라 저장소의 dev 규칙은 "실제 Secret 3개만 둔다"이다. 메일 키 Secret 을 더하려면 그 규칙을 먼저 개정한다.
- `PULSEMETRY_MAIL_PROVIDER`·`PULSEMETRY_MAIL_SES_REGION`은 새 필수값이다. 메일을 켠 배포 설정에 함께 넣는다.

### 3.5 업무 기능 활성화

메일 설정만 넣는다고 초대·문의 E2E 가 되지 않는다. 관리 기능(`pulsemetry.management.*`)·사용자 인증·OIDC·문의 접수(`pulsemetry.inquiries.*`)의 배포 준비를 따로 확인하고,
`/api/*` 관리 경로·프론트 주소·인증 배포와의 선후를 기록한다. 메일 전환을 명목으로 다른 API 를 통째로 공개하지 않는다.

### 3.6 네트워크

- 실행 중인 태스크에서 SES API 의 HTTPS(443) egress, DNS, ECS 태스크 자격 증명 엔드포인트 접근을 확인한다.
- SMTP 포트 개방이나 Mailpit 은 SES 모드의 조건이 아니다.

### 3.7 관측과 피드백

- configuration set 을 쓰면 이름을 `PULSEMETRY_MAIL_SES_CONFIGURATION_SET`으로 넘긴다. 발송·반송·신고 지표와 알림 경로, 피드백을 받을 책임자를 운영 작업에서 정한다.
- 앱이 이벤트를 받지 않아도 반송·신고 통지(이메일 또는 SNS) 경로는 운영에 필요하다.
  [SES 피드백](https://docs.aws.amazon.com/ses/latest/dg/monitor-sending-activity-using-notifications.html) · [CloudWatch destination](https://docs.aws.amazon.com/ses/latest/dg/event-publishing-add-event-destination-cloudwatch.html)

### 3.8 수신 거부 목록(suppression)

- 계정 수준 BOUNCE·COMPLAINT 수신 거부 설정과 같은 계정의 다른 발송 시스템에 미치는 영향을 확인한 뒤 적용 범위를 정한다.
- 수신 거부 목록에 걸린 수신자의 메일도 outbox 는 `sent`일 수 있다. [SES 전달 의미](https://docs.aws.amazon.com/ses/latest/dg/send-email-concepts-deliverability.html)

## 4. 로그와 실패 진단

### 4.1 로그

모든 필드는 메시지 안의 `key=value`다. 수신자·제목·본문·AWS 오류 메시지 원문은 어느 로그에도 없다.

| 로그 | 언제 | 필드 |
|---|---|---|
| `event=mail_provider_accepted` | SES 가 `SendEmail`을 받았다 | `provider=ses` `mail_id`(outbox ID) `kind` `attempt` `provider_message_id`(SES 접수 ID) |
| `event=mail_provider_failed` | SES 호출이 실패했다 | `provider=ses` `mail_id` `kind` `attempt` `detail`(아래 토큰) `status`(HTTP) `request_id`(AWS 요청 ID) `error`(예외 종류) |
| `메일을 보냈다 id=… kind=… attempt=…` | outbox 에 `sent`를 기록했다 | 두 구현 공통 |
| `메일을 보내지 못했다 id=… code=… permanent=…` | outbox 에 실패를 기록했다 | 두 구현 공통 |

**수락 로그는 SES 수락의 증거이고 DB `sent`가 아니다.** 수락 로그 뒤에 `메일을 보냈다`가 없으면 결과 기록이 실패한 것이다 — 메일은 `sending`에 남고 임대가 끝나면 다시 나간다(같은 `mail_id`에 접수 ID 가 둘).
응답이 유실된 시도는 접수 ID 가 없다. 접수 ID 는 로그 보존 기간이 지나면 사라진다 — 영속 발송 이력이 아니다.

### 4.2 실패 토큰

API 와 화면에는 공개 코드(`message_rejected`·`invalid_address`·`send_error`·`outcome_unknown` 등 ADR 0037 의 어휘)만 나간다. SES 세부는 `mail_outbox.failure_detail`과 로그에 있다.

| `failure_detail` | 공개 코드 | 처리 | 운영 조치 |
|---|---|---|---|
| `ses_invalid_address` | `invalid_address` | 영구 | 수신자 주소를 고친 뒤 업무 절차로 새 메일 |
| `ses_credentials_unavailable` | `send_error` | 재시도 | 기한 안에 자격 증명을 얻지 못했다(조회 지연·실패). **SES 에 묻지 않았다.** 태스크 역할·자격 증명 엔드포인트 접근·`send-timeout`을 본다. 로그의 `error`가 `TimeoutException`이면 지연이다 |
| `ses_message_rejected` | `message_rejected` | 영구 | identity 검증·sandbox 수신자·내용 확인 |
| `ses_bad_request` | `message_rejected` | 영구 | 요청 형식 문제. 발신 주소·리전·configuration set 이름 확인 |
| `ses_throttled` | `send_error` | 재시도 | 발송 속도 한도. 인스턴스 수와 발송 주기를 본다 |
| `ses_limit_exceeded` | `send_error` | 재시도 | 계정 한도 오류. 속도 한도와 같은 것으로 보지 않는다 |
| `ses_configuration_error` | `send_error` | 재시도 | MAIL FROM 도메인 미검증, 없는 configuration set 등 |
| `ses_sending_disabled` | `send_error` | 재시도 | 계정·configuration set 의 발송 중지 또는 계정 정지 |
| `ses_auth_failed` | `send_error` | 재시도 | 태스크 역할·권한·서명·토큰 만료 |
| `ses_unavailable` | `send_error` | 재시도 | SES 5xx 또는 연결 실패(DNS·egress 포함) |
| `ses_timeout` | `send_error` | 재시도 | 제한 시간 초과. **SES 가 받았는지 알 수 없다** — 재시도가 중복일 수 있다 |
| `ses_unknown_error` | `send_error` | 재시도 | 분류하지 못한 SDK 오류 |
| (없음) | `outcome_unknown` | 종료 | 최대 시도를 채운 채 임대가 끝났다. 발송 여부 불명 |

설정·권한 오류도 재시도로 분류된다. 고치지 않고 두면 최대 시도에서 `failed`로 닫히고 본문이 지워진다 — 오래 방치하지 않는다.
SMTP 모드의 `failure_detail`은 SMTP 응답 코드(예: `550`)다.

### 4.3 outbox 확인

수신자 주소는 평문 열이다. 넓게 공유할 결과에는 수신자를 고르지 않는다.

```sql
SELECT status, failure_code, failure_detail, count(*), min(queued_at)
FROM enrollment.mail_outbox GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;
```

## 5. 실제 SES 확인(smoke) — 외부 준비 이후

§3 의 준비가 끝난 뒤 별도 단계로 한다. 이 저장소의 테스트로 대신하지 않는다.

1. 대상 리전에 identity 와(쓰면) configuration set 이 있고, 같은 리전으로 `PULSEMETRY_MAIL_SES_REGION`을 줬는지 확인한다. sandbox 면 수신자를 검증했거나 mailbox simulator(`success@simulator.amazonses.com`)를 쓴다.
2. 지정한 테스트 수신자에게 초대를 발급한다. `delivery.status`가 `queued`인지 본다.
3. 발송 주기 뒤 `event=mail_provider_accepted`의 `mail_id`·`provider_message_id`를 확인하고, 같은 `mail_id`에 `메일을 보냈다`가 있는지 본다.
4. outbox 의 그 메일이 `sent`이고 `encrypted_body`가 NULL 인지, 목록(§12)의 `delivery.status`가 `sent`인지 본다.
5. 받은 메일에서 한글 제목·본문, SSO 로그인 주소(코드 없음), 설치 명령, `X-Pulsemetry-Mail-Id` 헤더를 확인한다. 헤더의 DKIM·DMARC 결과가 pass 인지 본다.
6. 재발급·문의 통지·설치 업데이트 안내도 같은 방법으로 한 번씩 확인한다. 설치 안내 작업의 `succeeded`가 정책 적용과 다르다는 것을 확인한다.
7. 실패 경로 하나 — sandbox 의 미검증 수신자 등 — 가 `message_rejected`/`ses_message_rejected`로 영구 실패하는지 본다.
8. 반송 simulator(`bounce@simulator.amazonses.com`)로 보낸 메일이 outbox 에서는 `sent`이고 반송은 피드백 경로(§3.7)로만 보이는지 확인한다.

## 6. 전환과 복구

이 전환에 DB 마이그레이션은 없다. DB 는 전환 전후 같다.

1. 대상 배포의 `queued`·`sending` 수와 가장 오래된 대기 시각, 발송 설정·암호화 키 참조를 확인한다. 이미 `sent`인 메일은 다시 보내지 않는다.
2. SES identity·production access 필요 여부·IAM·DNS·피드백·한도를 먼저 준비하고 테스트 수신자로 확인한다(§5).
3. `provider`를 고를 수 있는 버전을 배포한 뒤 provider 를 바꾼다. **발신 주소와 outbox 암호화 키는 유지한다.** 키 교체를 공급자 전환에 섞지 않는다 — 키를 바꾸면 대기 중인 메일은 본문을 읽지 못해 실패로 끝난다.
4. 전환 중에는 옛 설정의 프로세스와 새 설정의 프로세스가 큐를 나눠 보낼 수 있다. 공급자를 엄격히 나눠야 하면 승인된 짧은 중단 구간에 옛 프로세스를 모두 내리고, 보내던 메일이 끝났거나 임대가 끝난 것을 확인한 뒤 SES 프로세스를 올린다.
   무중단 전환이 반드시 필요하면 업무 적재와 발송 선점을 따로 멈추는 제어를 먼저 설계한다(ADR 0057 Follow-up).
5. `PULSEMETRY_MAIL_ENABLED=false`를 "메일을 보존한 채 잠시 멈춤"으로 쓰지 않는다 — 그동안의 업무 메일이 적재되지 않는다.
6. 새 초대·재발급·문의·설치 안내를 지정한 계정으로 확인하고 대기열 누적·실패 코드·반송·신고를 지켜본다.
7. 장애가 나면 설정·권한 문제(`ses_auth_failed`·`ses_configuration_error`·`ses_sending_disabled`)인지 공급자 장애(`ses_unavailable`·`ses_timeout`·`ses_throttled`)인지 먼저 가른다.
   실제로 쓸 수 있는 SMTP 가 있을 때만 provider 를 명시적으로 되돌린다. 없으면 발송 작업을 멈추고(프로세스 중지) 큐를 보존한 채 복구한다. 자동 fallback·`sent` 재큐잉·새 키로 끝난 메일 복제는 하지 않는다.
8. 최대 시도 실패·취소로 본문이 지워진 메일은 자동으로 되살릴 수 없다. 업무 절차(초대 재발급 등)로 새 메일을 만든다.

## 7. 이 저장소의 검증

| 테스트 | 확인하는 것 |
|---|---|
| `MailDispatchTest` | 실제 PostgreSQL·SMTP — 업무 롤백, 중복 방지, 동시 선점, 재시도·최대 시도, 취소, 만료 임대, 본문 삭제, 결과 기록 실패, 로그 비노출, 필수 설정·provider 검사 |
| `SesMailTransportTest` | 실제 SDK + 로컬 HTTP 모의 서버(가짜 자격 증명·고정 테스트 리전) — 요청 직렬화, 오류 분류, SDK 단일 시도, 제한 시간, 주소 검사, 클라이언트 닫기, 실제 PostgreSQL outbox 와의 상태 전이·접수 로그. 늦은 ECS 자격 증명 엔드포인트로 SDK 만으로는 임대를 넘긴다는 재현 조건과, 발송 한 번이 기한 안에 SES 에 묻지 않고 끝나며 늦은 조회가 하나만 돌아 다음 발송에 쓰이는지 |
| `SesMailConfigTest` | SES 설정만으로 앱이 뜨고 SES 구현을 쓴다(SMTP 설정 없음, AWS 호출 없음) |
| `InvitationMailApiTest` · `InstallationInvitationApiTest` · `InstallationNotificationApiTest` | 업무 트리거·발송 상태·설치 안내 작업 결과(SMTP) |

```bash
./gradlew :apps:enrollment-api:test --tests '*MailDispatchTest' --tests '*SesMail*' --tests '*InvitationMailApiTest' --tests '*InstallationInvitationApiTest' --tests '*InstallationNotificationApiTest'
./gradlew :apps:enrollment-api:test :libs:enrollment-persistence:test
```
