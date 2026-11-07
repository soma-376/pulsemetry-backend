# 0080. snapshot 복사 읽기 상한은 0 으로 해제할 수 있다.

## Status

Proposed — 채택되면 [ADR 0023](0023-대시보드-snapshot-은-dashboard-cache-의-불변-복사본과-manifest-이고-대시보드-앱이-그-DDL-을-적용한다.md) §4 '한도'의 복사 행·바이트 상한을 부분 대체한다. 나머지 한도 규칙은 유지한다.

## Context

ADR 0023 §4 는 snapshot 복사 문장이 원본에서 읽는 행·바이트에 상한(`max_rows_to_read`·`max_bytes_to_read`, `read_overflow_mode = throw`)을 걸게 한다. 두 값은 기본값 없는 설정이고, 넘으면 build 는 `failed` 이며 부분 결과를 공개하지 않는다.

현재 구현은 이 규칙을 두 곳에서 강제한다.

- `DashboardApiProperties.Snapshot` 이 `max-copy-rows`·`max-copy-bytes` 를 1 이상으로 검증한다.
- infra 의 배포 입력 검증 `lib/dev/runtime-input.cjs` 가 두 환경변수를 `^[1-9]\d*$` 로 검증한다.

그래서 상한을 끄는 값이 없다. 끄려면 의미 없는 큰 수를 넣어야 한다.

복사 문장의 `read_rows` 에는 원천 스캔과 materialized view 처리량이 누적된다. 그래서 이 상한은 결과 크기가 아니라 뷰 처리량에 비례한다. dev 에서는 다음 일이 있었다.

- PROJ-264 가 로컬 실측의 1.5배로 910,000행 / 384,827,392바이트를 정했다.
- 2026-11-01 구성원 페이지의 최근 7일 조회가 976,660행에서 `TOO_MANY_ROWS` 로 실패했다(PROJ-308).
- 2026-11-06 dev 에 두 값을 `9007199254740991` 로 임시 적용했다. 배포 입력 원본(`config/dev/runtime.yaml`)은 그대로라서, 정식 절차로 재배포하면 이전 값으로 돌아간다.

ClickHouse 에서 두 설정의 0 은 "제한 없음"이다.

결정하지 않으면 상한 해제가 큰 수로 표현된다. 읽는 쪽은 그 값이 측정으로 정한 한도인지 해제인지 구분할 수 없다.

## Decision

1. `pulsemetry.dashboard.snapshot.max-copy-rows`·`max-copy-bytes` 는 0 이상의 정수다. 0 은 그 상한을 걸지 않는다는 뜻이다. 두 값은 각각 정한다.
2. 앱은 0 도 그대로 복사 문장의 `max_rows_to_read`·`max_bytes_to_read` 로 넘긴다. 설정을 빼서 서버나 사용자 프로필의 기본값에 맡기지 않는다.
3. 기본값 없음은 유지한다. 값이 비었거나 없거나 음수면 기동이 실패한다.
4. ADR 0023 §4 의 나머지는 그대로 유효하다.
   - tenant 별 동시 build 한도
   - 실행 시간(`max_execution_time` = build 제한 시간)
   - 상한을 넘은 build 는 `failed` 이고 부분 결과를 공개하지 않으며 `503` + `Retry-After` 다.
5. 배포 입력 검증도 같은 규칙을 따른다. 복사 두 키만 0 을 허용하고, 다른 정수 설정은 1 이상을 유지한다.

## Alternatives Considered

### A. 큰 수로 사실상 해제한다
- 장점: 코드 변경이 없다.
- 단점: 해제 의도가 값에 드러나지 않는다. 측정으로 정한 한도와 구분되지 않는다.
- 탈락 이유: 운영 입력을 읽고 상한이 켜져 있는지 판단할 수 없다.

### B. 값이 없으면 상한을 걸지 않는다
- 장점: 입력이 짧다.
- 단점: 설정 누락과 의도된 해제를 구분할 수 없다. ADR 0022·0023 의 "기본값 없는 설정" 원칙을 깬다.
- 탈락 이유: 누락은 계속 기동 실패여야 한다.

### C. 상한 설정을 제거한다
- 장점: 설정 항목이 준다.
- 단점: 측정한 한도를 다시 걸 수단이 사라진다.
- 탈락 이유: 환경마다 한도를 켤지 정해야 한다.

## Consequences/Tradeoffs

### Positive
- 상한 해제가 `0` 으로 명시된다. 정식 배포 입력에 그대로 남길 수 있다.
- 측정으로 정한 한도를 다시 걸 때 값만 바꾸면 된다.

### Negative
- 0 이면 큰 조회의 실패 지점이 build 제한 시간과 ClickHouse 메모리로 옮겨 간다. dev ClickHouse 는 1 CPU / 2Gi 이고 수집 적재와 같은 서버다.
  - 동시 build 한도와 build 제한 시간이 남은 방어선이다.
- PROJ-308 의 완료 기준은 "상한을 무제한으로 바꾸어 성공 처리하지 않는다"이다. dev 에 0 을 두는 것은 이 기준과 충돌한다. 이 ADR 리뷰에서 팀 동의를 받는다.

## Follow-up

- infra `lib/dev/runtime-input.cjs`·`config/dev/runtime.yaml` 에 같은 규칙을 반영한다(PROJ-325 infra PR).
  - 이 이미지가 dev 에 배포된 뒤에 머지한다. 그 전에 0 을 넣으면 dashboard-api 가 기동하지 못한다.
- 복사량 자체를 줄이는 근본 대응은 PROJ-308 재설계에서 다룬다.

## Acceptance Criteria

- `DashboardApiPropertiesTest`: 두 값 각각 0 으로 기동하고, 음수·빈 값이면 실패한다.
- `SnapshotLifecycleTest`: 두 값이 0 이면 실제 ClickHouse 에서 복사가 끝나 `ready` 가 된다. 1 이면 기존처럼 `limit_exceeded` 로 실패한다.
- dev 배포 후 `system.query_log` 의 복사 문장 `Settings` 에 `max_rows_to_read = 0` 이 남는다.

## References

- [ADR 0023](0023-대시보드-snapshot-은-dashboard-cache-의-불변-복사본과-manifest-이고-대시보드-앱이-그-DDL-을-적용한다.md) §4
- `DashboardApiProperties.Snapshot`, `SnapshotBuilder.copySettings`
- [Dashboard 서버 명세](../dashboard-server-spec.md)의 운영 수치 표
- infra `lib/dev/runtime-input.cjs`, `docs/runbooks/dev-clickhouse-seed-capacity.md`
