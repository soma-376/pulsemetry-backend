# 0064. 확인된 Desktop 완료 분기만 사용량 프로파일에 추가한다.

## Status

Accepted — ADR 0020 부록 C와 ADR 0061의 Codex 적용 범위를 정확한 Desktop 두 버전의 완료 로그에 한해 확장한다. 가격판은 바꾸지 않고 정규화 판을 올린다. 나머지 원천 보존·식별·재처리·의미·가격 규칙은 유효하다.

## Context

실제 9월 원천에는 `Codex Desktop`의 `0.153.4` 2건과 `0.154.0-alpha.6.2` 3건이 있다. 모두 기존 검증 태그의 `sse_event_completed` emitter와 필드·wire 타입이 일치하지만 표면 제한 때문에 generic으로 보존되고 사용량 합계에는 포함되지 않았다. 해당 두 버전의 익명화된 저장소 실캡처 fixture도 이미 있다.

## Decision

1. 별도 `CodexDesktopCompletedProfile`은 제품 Codex, 표면 Desktop, 정확한 두 버전, `event.name=codex.sse_event`, `event.kind=response.completed`, `eventName=event otel/src/events/session_telemetry.rs:1012`만 지원한다. 기존 전체 Codex 프로파일의 표면·버전 범위는 넓히지 않는다. 스팬·메트릭·다른 이벤트는 generic을 유지한다.
2. 기존 완료 분기의 wire 타입·유효 성분·오류 충돌 검사를 재사용하고 `codex-responses-inclusive-v1`을 연결한다. 누락 성분은 0으로 채우지 않는다. input에서 cache read/write를 뺀 일반 입력과 output을 각각 가격 평가하며 reasoning·보고 total을 다시 더하지 않는다.
3. 근거는 두 exact 태그의 Responses usage 변환·응답별 완료 호출·emitter, 공식 사용량 정의, 저장소 실캡처 fixture다. `service.name`은 OTel resource 설정이며 토큰 변환과 독립이다. Desktop 배포 바이너리 전체가 그 공개 커밋과 동일하다고 주장하지 않는다. 실제 캡처와 일치하는 좁은 분기만 지원하며 근거와 한계를 `PROFILE-EVIDENCE.md` §12에 기록한다.
4. `RowVersioning.NORMALIZER_REV`를 올리고 `mapping_version=codex-desktop-completed-v1`을 사용한다. 가격판의 단가·기준 시각은 바꾸지 않는다. 기존 observation ID와 원천 시각·토큰·신원을 보존하여 재처리한 generic 행만 높은 row_version으로 교체한다. 저장된 열을 직접 수정하지 않는다.

## Alternatives Considered

- 모든 Desktop 버전·이벤트를 기존 전체 프로파일에 추가하면 검증하지 않은 진단·스팬·메트릭까지 의미를 부여하므로 채택하지 않는다.
- 배포 바이너리 소스 전체가 공개될 때까지 이 다섯 관측을 영구 제외하면 확인된 응답 사용량을 누락하므로 좁은 캡처 대응 범위를 채택한다.

## Consequences/Tradeoffs

### Positive

- 기존 원본과 관측 ID를 유지하며 실제 사용량 누락을 복구한다.
- 미지원 producer·emitter와 미보고 성분을 확대 추정하지 않는다.

### Negative

- Desktop 배포 바이너리 전체의 소스 대응을 검증한 것은 아니다. 새 버전이나 emitter는 별도 근거와 회귀 테스트가 필요하다.
- 기존 generic 관측은 재처리하기 전까지 합계에 포함되지 않는다.

## Acceptance Criteria

- 두 버전의 기존 익명화 실캡처 2건·3건에서 완료 로그만 primary로 연결된다.
- 독립 정수 예제로 비중복 토큰과 금액을 검증한다. 미지원 Desktop 버전·잘못된 emitter·다른 이벤트·오류 충돌과 미보고 cache write를 검증한다.
- 올린 정규화 판과 불변 가격판, 같은 원본의 관측 ID 보존을 검증한다.

## References

- [프로파일 근거](../../libs/telemetry-adapter/src/test/resources/otlp-v2/codex/PROFILE-EVIDENCE.md#12-desktop-두-버전의-완료-분기)
- [ADR 0061](0061-Codex-완료-응답은-포함형-토큰-의미로-정규화하고-검증된-두-프로파일만-합산한다.md)
- [ADR 0060](0060-사용-환산액은-고정된-참조-가격판으로-계산한다.md)
