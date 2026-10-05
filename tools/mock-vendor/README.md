# 모의 벤더 서버

벤더 연결(ADR 0048)·좌석 회수(ADR 0049)·청구 누계(ADR 0050)를 **실제 벤더 없이** 서버·화면 끝까지 돌려 보는 로컬 서버다.
구현한 두 커넥터(Claude Enterprise·Cursor Enterprise, ADR 0054)가 부르는 경로를 [근거 문서](../../docs/vendor-connector-evidence.md)의
공식 요청·응답 예시대로 흉내 내고, 받은 요청을 기록해 E2E가 "벤더가 실제로 호출을 받았는가"를 확인할 수 있게 한다.
커넥터 단위 검증은 `VendorConnectorsTest`·`SeatSyncApiTest`의 모의 서버가 맡는다 — 이 서버는 기동한 enrollment-api와 프론트 E2E
(`tests/e2e/vendor-connectors.spec.ts`)용이다.

- 상태는 메모리뿐이다. 기동할 때와 `POST /__reset` 때 처음으로 돌아간다.
- 계정은 fresh 조직 E(`tools/dev-seed` — 시나리오 E)의 것이다. E2E가 이 서버로 연결·동기화·회수를 해도 시드 A·B·C의 연결·원장·청구는 바뀌지 않는다.
- `127.0.0.1`에만 묶는다. 자격증명은 `fake-vendor-credential-`으로 시작하는 값만 받는다(Claude 는 `x-api-key`, Cursor 는 Basic 사용자 이름).
  그 밖은 401 이다 — **실제 키를 넣지 않는다**. 잘못된 자격증명 시험은 다른 값을 넣으면 된다.
- 복원 경로는 없다. 두 커넥터 모두 복원을 구현하지 않아 복원은 관리자 조치 확인으로 끝난다(ADR 0049 §2·0054). Claude 의 재초대 API 근거는
  근거 문서 §1.2 에 있지만 커넥터가 부르지 않는다.

## 데이터

| 벤더 | 계정 | 비고 |
| --- | --- | --- |
| Claude 구성원 | `owner@seed-e.example.test`(`primary_owner`) · `member1@seed-e.example.test`(`user`) · `contractor@partner.example.test`(`user`, 조직 구성원 아님) | 최근 추가 순. 관리 역할은 제거가 400 |
| Claude 초대 | `newhire@partner.example.test`(`pending` — 좌석을 잡는다) · `member1@seed-e.example.test`(`accepted`) | |
| Claude 비용 보고서 | 두 쪽, `amount` `"41280.000000"` + `"1720.000000"`(fractional cents) | 합 $430.00, USD |
| Cursor 구성원 | `owner@seed-e.example.test`(owner) · `member1@seed-e.example.test` · `contractor@partner.example.test` | |
| Cursor 지출 | `spendCents` 0 · 2450.125487 · 1049.874513 | 합 $35.00, 주기 시작 = 이번 달 1일 0시 UTC. 제거한 구성원의 지출도 남는다 |

## 실행

```bash
python3 tools/mock-vendor/mock_vendor.py 8090
```

| 경로 | 동작 |
| --- | --- |
| `GET /v1/organizations/users` | Claude 구성원. `limit`(기본 20·최대 1000)·`after_id` 페이지 → `data`·`has_more`·`first_id`·`last_id`. `anthropic-version` 이 없으면 400 |
| `GET /v1/organizations/invites` | Claude 초대(상태 거르기 없음). 같은 페이지 |
| `DELETE /v1/organizations/users/{user_id}` | `{"type": "user_deleted", "id": …}`. 관리 역할 400, 모르는 ID 404 |
| `GET /v1/organizations/analytics/cost_report` | `starting_at` 필수. `page` 가 없으면 첫 쪽(`has_more: true`, `next_page: "cost_page_2"`), `page=cost_page_2` 면 끝 쪽 |
| `GET /teams/members` | Cursor 구성원(`teamMembers`의 `id`·`name`·`email`·`role`·`isRemoved`). 제거한 구성원은 `isRemoved: true` |
| `POST /teams/remove-member` | 본문 `userId` 또는 `email` 하나 → `{"success": true, "userId": …, "hasBillingCycleUsage": …}`. 둘 다·둘 다 없음·모르는 구성원·이미 제거됨·마지막 owner 는 `{"success": false}` |
| `POST /teams/spend` | `page`·`pageSize` → `teamMemberSpend`·`subscriptionCycleStart`·`totalMembers`·`totalPages` |
| `GET /__calls` | 받은 요청(메서드·경로·질의·본문·`Authorization`·`x-api-key`·`anthropic-version`) |
| `POST /__fault` | `{"path": "/teams/members", "status": 429, "times": 1, "retryAfter": 60}` — 그 경로(접두사)의 다음 `times` 번 요청에 그 상태(401·403·429·5xx). `{"clear": true}`는 모두 끈다 |
| `POST /__reset` | 계정·기록·오류 주입을 처음으로 |

## enrollment-api를 붙이기

벤더 연결 설정(`docs/enrollment-server-spec.md` §8)에서 기준 주소를 모두 이 서버로 둔다. 자격증명 키는 새로 만들어 쓴다.
시도 횟수를 1로 두면 429·5xx 주입이 재시도 없이 동기화 실패로 보인다.

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

시드 C의 Cursor 연결은 어떤 서버 키로도 풀 수 없는 자리표시자 암호문이다(`credential_key_unavailable`로 동기화 실패) — 이 서버를 부르지 않는다.
프론트 E2E는 `E2E_MOCK_VENDOR_URL`에 이 서버 주소를 받아 조직 E에 Claude Enterprise·Cursor Enterprise 계약을 등록하고 연결한다
— 조직 E를 바꾸므로 시나리오 E를 적재한 개발·격리 DB에서만 돌린다.
