# 0072. Desktop 0.165.0의 확인된 완료 로그만 사용량으로 매핑한다.

## Status

Accepted — ADR 0064의 Desktop 버전 제한과 ADR 0061의 완료 토큰 의미 적용 범위만 0.165.0에 한해 확장한다. 나머지 가드·원천 보존·단일 카탈로그 규칙은 유효하다.

## Context

단일 가격표를 배포한 뒤 기존 사용량의 저장 환산액은 표시되지만, `Codex Desktop` 0.165.0의 새 완료 로그는
미등록 producer라 generic으로 저장됐다. Kafka와 정규화·적재 처리는 성공했으며 가격표나 전송 경로의 장애가 아니다.
관측한 구조는 완료 emitter, `response.completed`, 여섯 토큰 필드의 wire 타입을 확인할 수 있다.
기존 ADR 0064는 Desktop 두 버전만 허용하므로 새로운 버전의 의미를 부여하려면 좁은 범위를 명시해야 한다.

## Decision

1. `CodexDesktopCompletedProfile`에 정확한 버전 `0.165.0`을 추가한다. `service.name=Codex Desktop`,
   `event.name=codex.sse_event`, `event.kind=response.completed`,
   `eventName=event otel/src/events/session_telemetry.rs:1132`가 모두 맞는 로그만 지원한다.
   기존 두 Desktop 버전의 완료 emitter는 그대로 유지한다. CLI·APP_SERVER·인접 버전·다른 이벤트·스팬·메트릭은 확대하지 않는다.
2. 공식 `openai/codex`의 정확한 `rust-v0.165.0` 커밋 `360319d8dee62d87e2d59fc0c21805d79519cfed`에서 Responses usage 변환, 응답별 완료 호출,
   `sse_event_completed` emitter와 실제 비민감 구조를 대조한다. Desktop 바이너리 전체가 공개 커밋과 같다고 주장하지 않는다.
   producer가 미보고 API 성분을 0으로 기록하는 한계와 중단 응답도 내부 Completed로 변환할 수 있는 한계를 보존한다.
   근거와 범위 한계는 `PROFILE-EVIDENCE.md`에 기록하고 fixture는 확인한 구조를 독립적인 합성 값으로 재현한다.
3. 기존 완료 분기의 wire·오류 충돌 검사와 `codex-responses-inclusive-v1`을 재사용한다.
   input은 cache read/write를 포함하고 output은 reasoning을 포함한다. `tool_token_count`는 보고 total이며 별도 tool 토큰이 아니다.
   누락 성분을 0으로 채우지 않고 실제 provider를 추정하지 않는다. ADR 0071의 단일 카탈로그와 기존 단가는 유지한다.
4. `normalizer_rev`를 10에서 11로 올리고 Desktop 매핑 판은 `codex-desktop-completed-v2`로 한다.
   같은 원본의 observation ID·시각·원시 토큰을 유지한다. 기존 행은 자동 재작성하지 않으며 원본 재정규화는 명시적인 운영 절차로만 한다.
5. 이미 배포된 rev10의 가격판 없는 저장 행은 해시를 그대로 검증하고 수동 재가격 평가의 신뢰 가능한 원천 판으로 유지한다.
   rev10 이상은 가격판 없는 해시, 이전 판은 기존 호환 해시라는 경계를 유지한다. 동일 금액은 정규화 번호가 달라도 다시 쓰지 않는다.

## Alternatives Considered

- 전체 `CodexProfile`의 버전에 0.165.0을 추가하면 확인하지 않은 다른 로그·스팬·메트릭까지 의미를 부여하므로 채택하지 않는다.
- 모든 surface를 기존 완료 프로파일에 넣으면 실제로 확인하지 않은 CLI·APP_SERVER까지 범위를 넓히므로 채택하지 않는다.
- generic 행의 토큰·금액을 직접 수정하면 원본·해시·교체 규칙을 우회하므로 채택하지 않는다.

## Consequences/Tradeoffs

### Positive

- 정확히 확인한 최신 Desktop 완료 로그가 기존 단일 카탈로그로 평가된다.
- 기존 관측과 비용을 보존하며 미확인 producer 범위와 누락 성분을 추정하지 않는다.

### Negative

- producer 버전이나 emitter가 바뀌면 별도의 근거와 회귀 검사가 필요하다.
- 이미 generic으로 저장된 행은 명시적으로 재정규화하지 않는 한 사용량에 포함되지 않는다.
- Desktop 배포 바이너리 전체와 공개 소스의 동일성을 검증한 것은 아니다.

## Follow-up

- 필수 CI·리뷰 후 immutable 이미지로 dev에 배포하고 새 실제 Codex 사용의 서버 토큰·환산액과 대시보드가 일치하는지 확인한다. 이 작업의 완료 증거는 PROJ-291에 기록한다.

## Acceptance Criteria

- 정확한 버전·surface·emitter·완료 분기에서 비중복 토큰과 독립 산식의 금액이 일치한다.
- 다른 emitter·버전·surface·분기와 오류 충돌에는 의미나 금액을 부여하지 않는다. 누락·잘못된 성분은 채우지 않으며 가격 필수 성분이 없으면 금액은 null이다.
- 같은 원본의 observation ID 보존, rev11 교체 순서, rev10 해시와 재가격 평가 멱등성을 검증한다.
- dev에서 새 실제 사용의 완료 토큰·환산액과 대시보드가 일치함을 별도로 확인한다. 테스트 통과는 실제 배포 증거가 아니다.

## References

- [PROJ-291](https://team376.atlassian.net/browse/PROJ-291)
- [ADR 0064](0064-확인된-Desktop-완료-분기만-사용량-프로파일에-추가한다.md)
- [ADR 0061](0061-Codex-완료-응답은-포함형-토큰-의미로-정규화하고-검증된-두-프로파일만-합산한다.md)
- [ADR 0071](0071-서버-환산은-단일-카탈로그를-쓰고-가격판-기록을-제거한다.md)
- [프로파일 근거](../../libs/telemetry-adapter/src/test/resources/otlp-v2/codex/PROFILE-EVIDENCE.md)
