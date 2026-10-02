# 모의 벤더 서버

벤더 연결(ADR 0048)과 좌석 회수(ADR 0049)를 **실제 벤더 없이** 서버·화면 끝까지 돌려 보는 로컬 서버다.
Cursor Enterprise Admin API의 좌석 요청·응답([근거](../../docs/vendor-connector-evidence.md) §3)을 흉내 내고, 받은 요청을 기록해 E2E가
"벤더가 실제로 호출을 받았는가"를 확인할 수 있게 한다. 커넥터 단위 검증은 `VendorConnectorsTest`·`SeatSyncApiTest`의 모의 서버가 맡는다 —
이 서버는 기동한 enrollment-api와 프론트 E2E(`tests/e2e/seats.spec.ts`)용이다.

- 상태는 메모리뿐이다. 기동할 때마다 시드 C의 Cursor 팀 구성원 넷 — `owner@seed-c.example.test`(owner)·`member2@seed-c.example.test`·
  `member3@seed-c.example.test`와 구성원이 없는 외부 계정 `contractor@partner.example.test` — 으로 돌아간다.
- `127.0.0.1`에만 묶는다. 자격증명은 검사하지 않고 `Authorization` 헤더를 그대로 기록한다 — **실제 키를 넣지 않는다**(`fake-vendor-credential-…` 같은 값).
- Cursor 좌석 밖의 경로는 404다. 다른 커넥터의 기준 주소도 이 서버를 가리키게 두면 실제 벤더로 나가는 요청이 없다(Claude Enterprise 연결과
  Cursor 의 청구 누계 읽기는 404로 실패한다 — 좌석 동기화는 청구 실패와 무관하게 반영된다, ADR 0050).
- 복원 경로는 없다. Cursor 는 재추가 API 가 없어 복원은 관리자 조치 확인으로 끝난다(ADR 0049·0054).

## 실행

```bash
python3 tools/mock-vendor/mock_vendor.py 8090
```

| 경로 | 동작 |
| --- | --- |
| `GET /teams/members` | 구성원 목록(`teamMembers`의 `id`·`name`·`email`·`role`·`isRemoved`). 제거한 구성원은 `isRemoved: true` |
| `POST /teams/remove-member` | 본문 `userId` 또는 `email` 하나 → `{"success": true, "userId": …, "hasBillingCycleUsage": false}`. 둘 다·둘 다 없음·모르는 구성원·이미 제거됨·마지막 owner 는 `{"success": false}` |
| `GET /__calls` | 받은 요청(메서드·경로·본문·`Authorization`) |
| `POST /__reset` | 구성원·기록을 처음으로 |

## enrollment-api를 붙이기

벤더 연결 설정(`docs/enrollment-server-spec.md` §8)에서 기준 주소를 모두 이 서버로 둔다. 자격증명 키는 새로 만들어 쓴다.

```bash
KEY="$(head -c 32 /dev/urandom | base64)"
./gradlew :apps:enrollment-api:bootRun --args="\
 --spring.profiles.active=local \
 --pulsemetry.vendor-connections.enabled=true \
 --pulsemetry.vendor-connections.credential-keys.local=$KEY --pulsemetry.vendor-connections.credential-key-id=local \
 --pulsemetry.vendor-connections.sync.interval=PT6H --pulsemetry.vendor-connections.sync.check-interval=PT2S --pulsemetry.vendor-connections.sync.lease=PT1M \
 --pulsemetry.vendor-connections.http.request-timeout=PT5S --pulsemetry.vendor-connections.http.max-attempts=1 \
 --pulsemetry.vendor-connections.http.retry-backoff=PT0S --pulsemetry.vendor-connections.http.max-retry-wait=PT0S \
 --pulsemetry.vendor-connections.base-urls.claude_enterprise=http://127.0.0.1:8090 \
 --pulsemetry.vendor-connections.base-urls.cursor_enterprise=http://127.0.0.1:8090"
```

시드 C의 Cursor 연결은 어떤 서버 키로도 풀 수 없는 자리표시자 암호문이다(`credential_key_unavailable`로 동기화 실패).
설정 화면에서 연결을 지우고 가짜 자격증명으로 다시 만들면 동기화가 이 서버의 구성원을 읽는다.
프론트 E2E는 `E2E_MOCK_VENDOR_URL`에 이 서버 주소를 받아 같은 절차를 브라우저로 실행한다 — 연결을 바꾸므로 개발·격리 DB에서만 돌린다.
