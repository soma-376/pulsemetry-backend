# 0061. Codex 완료 응답은 포함형 토큰 의미로 정규화하고 검증된 두 프로파일만 합산한다.

## Status

Accepted — 부분 대체: [ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) §7의 "의미 프로파일이 다르면 합산하지 않는다"를 아래 canonical 호환 집합에 한해 대체한다. 원천·관측 식별·행 교체·보존 경계는 유효하다.

Desktop 두 버전의 완료 분기는 [ADR 0064](0064-확인된-Desktop-완료-분기만-사용량-프로파일에-추가한다.md)가 더한다.

## Context

- ADR 0020 부록 C는 Codex의 cache write가 input에 포함되는지 확인하지 못해 의미 프로파일을 등록하지 않았다.
  그래서 Codex 사용량은 토큰 합계와 환산액에서 빠졌다.
- §7은 의미 프로파일이 다르면 성분을 합산하지 않는다. Claude Code(exclusive)와 Codex(inclusive)를 함께 쓰는 조직은
  전체 토큰을 볼 수 없다.

## Decision

1. `codex-responses-inclusive-v1`은 input이 cache read/write를 포함하고 output이 reasoning을 포함하는 의미다.
   `input_uncached = input − cache_read − cache_create`이고 `total = input + output`이다. emitter의 `tool_token_count`는
   total의 별도 보고이므로 더하지 않는다.
2. 버전·표면·완료 emitter 위치별 원천 구현과 fixture가 증명하는 범위에만 이 의미를 연결한다. 범위의 목록은
   `CodexTokenSemantics`와 `otlp-v2/codex/PROFILE-EVIDENCE.md`가 소유한다. fixture 이름이나 모델명으로 미검증 의미를
   우회하지 않는다.
3. cache-write 키가 없으면 null이다. producer가 보낸 0은 보고값으로 유지하고, 키 없는 값을 backend가 0으로 만들지
   않는다. 보고되지 않은 provider는 추정하지 않는다.
4. 대시보드는 `claude-code-exclusive-v1`과 `codex-responses-inclusive-v1`을 같은 비중복 canonical 성분
   (`input_uncached`·`output`·`cache_read`·`cache_create`)으로 합산한다. `semantics_profile`의 개수만으로 차단하지 않고
   명시적인 호환 집합과 미검증·누락 행 수를 본다. 다른 프로파일과 미검증 행은 자동 호환으로 보지 않는다.
   가격판 혼재 차단은 그대로다.

### Codex 근거

- 원천 구현은 `codex-rs/codex-api/src/sse/responses.rs`의 `From<ResponseCompletedUsage>`와
  `codex-rs/otel/src/events/session_telemetry.rs`의 `sse_event_completed`다. 정확한 commit·fixture 대응은
  `PROFILE-EVIDENCE.md` §10·§11이 관리한다.
- 0.162.0-alpha.17.2는 제한된 `response.completed` 캡처와 emitter 일치 근거의 범위만 지원한다. 다른 로그로 넓히지 않는다.
- producer가 보고한 0은 보고값이다. upstream의 실제 측정 0과 내부 기본값 0을 구분했다는 뜻은 아니다.

## Alternatives Considered

### A. Codex 행을 계속 의미 미검증으로 둔다
- 장점: ADR 0020의 최초 판정을 그대로 유지한다.
- 단점: Codex 사용량이 토큰·환산 합계에서 빠진다.
- 탈락 이유: 원천 구현으로 포함관계를 확인한 범위까지 제외할 이유가 없다.

### B. 의미 프로파일이 달라도 모두 합산한다
- 장점: 구현이 단순하다.
- 단점: 캐시·reasoning이 이중 계산된다.
- 탈락 이유: 같은 비중복 성분으로 검증한 집합만 합산해야 한다.

## Consequences/Tradeoffs

### Positive

- Codex의 포함관계를 정규화해 이중 계산 없이 Claude Code와 함께 합산한다.

### Negative

- 범위 밖 버전·이벤트와 cache-write 미보고 행은 계속 의미 미검증이다.
- 새 Codex 버전마다 원천 근거와 fixture를 확인해야 범위에 넣을 수 있다.

## References

- [ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) §4·§7·부록 C
- [ADR 0060](0060-사용-환산액은-고정된-참조-가격판으로-계산한다.md) — 가격
- [OpenAI prompt caching 공식 산식](https://developers.openai.com/api/docs/guides/prompt-caching)
