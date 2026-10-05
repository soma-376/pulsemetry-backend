# 도입 문의 — 기능 규칙·공유 스키마

[API 목록](../inquiries.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### `POST /api/v1/inquiries` 도입 문의 접수

로그인 전의 문의 폼이 부르는 공개 경로다. 인증이 없고 **조직·계정·초대를 만들지 않는다** — 접수만 저장한다.
담당자가 확인한 뒤 첫 관리자를 초대하는 절차는 이 API 밖이다.
`pulsemetry.inquiries.enabled=true`일 때만 있다. 꺼져 있으면 404 `not_found`다.

```json
{"company": "코드웍스", "email": "lead@example.test"}
```

| 필드 | 규칙 |
|---|---|
| `company` | 필수. 앞뒤 공백을 뗀 1~100자. 제어 문자(줄바꿈 포함)를 받지 않는다 |
| `email` | 필수. 앞뒤 공백을 떼고 소문자로 바꾼 320자 이하의 주소. 형식은 `local@domain.tld` |

계약에 없는 필드는 400이다. 응답은 201이고 `Cache-Control: no-store`다.

```json
{"inquiryId": "3f6c…", "status": "received", "receivedAt": "2026-09-09T12:00:00Z"}
```

- `status`는 `received` 하나다. 접수했다는 뜻이며 담당자 확인이나 초대 발급을 뜻하지 않는다.
- **담당자 통지**: 메일 기능(`pulsemetry.mail.enabled`)을 함께 켠 배포에서는 저장과 같은 트랜잭션에서 담당자(`pulsemetry.inquiries.notification-recipient`)에게 통지 메일을 적재한다(ADR 0038).
  통지에는 접수 번호·접수 시각·회사명·회사 이메일이 담긴다. 재전송은 새로 저장하지 않으므로 통지도 한 번이다.
  문의 응답에는 통지의 발송 상태를 싣지 않는다. 메일이 꺼져 있으면 문의는 저장만 된다.
- **재전송**: 같은 회사명·이메일이 `duplicate-window` 안에 다시 오면 저장하지 않고 앞선 접수의 본문을 그대로 돌려준다(201, 같은 `inquiryId`·`receivedAt`).
  같은지는 정규화한 값으로 본다 — 회사명은 NFKC·소문자·연속 공백 하나, 이메일은 소문자. 판정은 가장 최근 접수가 기준이고,
  그 시간이 지난 뒤의 같은 입력은 새 문의다. 같은 입력의 동시 요청도 한 번만 저장한다.
- **남용 제한**: 출처 주소(서블릿 `remoteAddr`)별로 `rate-limit.window` 안에 `rate-limit.requests`회까지 받는다. 검증에 실패한 요청과 재전송도 센다.
  넘으면 429 `rate_limited`와 `Retry-After`(창이 끝날 때까지의 초)다. preflight(`OPTIONS`)는 세지 않는다.
  제한 상태와 문의 행에는 주소의 SHA-256만 남긴다. forwarded 헤더를 믿지 않으므로 프록시 뒤에서는 프록시 주소 단위의 제한이 된다(사용자 인증의 진입 요청 제한과 같다 — ADR 0018·0052).
- **CORS**: `/api/v1/inquiries`는 `pulsemetry.inquiries.allowed-origins`의 출처에만 `POST`·`Content-Type`을 허용하고 `Retry-After`를 노출한다. 사용자 인증의 출처 목록과 따로 둔다.

오류 본문은 [기존 §7](../../enrollment-server-spec.md#7-에러-계약)의 두 필드 형태다. 문장은 CLI 가 아니라 문의 폼의 사용자에게 보인다.
요청 수 초과와 제한 상태의 저장소 장애는 컨트롤러 앞의 필터가 쓰며 `Content-Type: application/json;charset=UTF-8`로 문자셋을 명시한다.

| 상황 | HTTP | error |
|---|---|---|
| 필드 누락·형식 오류·계약에 없는 필드·JSON 아님 | 400 | `invalid_request` |
| 출처의 요청 수 초과 | 429 | `rate_limited` + `Retry-After` |
| 저장소 장애 | 503 | `inquiry_unavailable` + `Retry-After: 1` |


<a id="schema-InquiryReceipt"></a>

```ts
type InquiryReceipt = { inquiryId: string; status: "received"; receivedAt: string };
```
