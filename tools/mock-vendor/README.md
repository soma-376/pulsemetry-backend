# 모의 벤더 서버

벤더 연결(ADR 0048)과 좌석 회수·복원(ADR 0049)을 **실제 벤더 없이** 서버·화면 끝까지 돌려 보는 로컬 서버다.
GitHub Copilot 좌석 API의 요청·응답([근거](../../docs/vendor-connector-evidence.md) §4)을 흉내 내고, 받은 요청을 기록해 E2E가
"벤더가 실제로 호출을 받았는가"를 확인할 수 있게 한다. 커넥터 단위 검증은 `VendorConnectorsTest`·`SeatSyncApiTest`의 모의 서버가 맡는다 —
이 서버는 기동한 enrollment-api와 프론트 E2E(`tests/e2e/seats.spec.ts`)용이다.

- 상태는 메모리뿐이다. 기동할 때마다 조직 `seed-org`의 좌석 `seed-dev-7`·`seed-dev-11`·`seed-bot`(시드 A의 Copilot 좌석과 같은 로그인)으로 돌아간다.
- `127.0.0.1`에만 묶는다. 자격증명은 검사하지 않고 `Authorization` 헤더를 그대로 기록한다 — **실제 토큰을 넣지 않는다**(`fake-vendor-credential-…` 같은 값).
- Copilot 밖의 경로는 404다. 다른 커넥터의 기준 주소도 이 서버를 가리키게 두면 실제 벤더로 나가는 요청이 없다(Claude Enterprise·Cursor·Gemini 연결은 404로 실패한다).

## 실행

```bash
python3 tools/mock-vendor/mock_vendor.py 8090
```

| 경로 | 동작 |
| --- | --- |
| `GET /orgs/seed-org/copilot/billing/seats` | 좌석 목록 한 페이지(`total_seats`·`seats`). 취소한 좌석은 `pending_cancellation_date`가 차 있다 |
| `DELETE /orgs/seed-org/copilot/billing/selected_users` | 본문 `selected_usernames`의 좌석을 취소 예정으로 → `{"seats_cancelled": n}` |
| `POST /orgs/seed-org/copilot/billing/selected_users` | 취소 예정을 푼다 → `201 {"seats_created": n}` |
| `GET /__calls` | 받은 요청(메서드·경로·본문·`Authorization`) |
| `POST /__reset` | 좌석·기록을 처음으로 |

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
 --pulsemetry.vendor-connections.base-urls.copilot=http://127.0.0.1:8090 \
 --pulsemetry.vendor-connections.base-urls.claude_enterprise=http://127.0.0.1:8090 \
 --pulsemetry.vendor-connections.base-urls.cursor_enterprise=http://127.0.0.1:8090 \
 --pulsemetry.vendor-connections.base-urls.gemini=http://127.0.0.1:8090 \
 --pulsemetry.vendor-connections.gemini-token-url=http://127.0.0.1:8090/token"
```

시드의 벤더 연결(A의 Copilot, C의 Cursor)은 어떤 서버 키로도 풀 수 없는 자리표시자 암호문이다(`credential_key_unavailable`로 동기화 실패).
설정 화면에서 연결을 지우고 `organization=seed-org`와 가짜 자격증명으로 다시 만들면 동기화가 이 서버의 좌석을 읽는다.
프론트 E2E는 `E2E_MOCK_VENDOR_URL`에 이 서버 주소를 받아 같은 절차를 브라우저로 실행한다 — 연결을 바꾸므로 개발·격리 DB에서만 돌린다.
