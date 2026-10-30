# 0074. producer 버전 대신 이벤트 계약으로 정규화한다.

## Status

Accepted — 사용자의 구조 개선 요청에 따라 ADR 0020 §4·부록 A/C, ADR 0061·0064·0072의 producer 버전·완료 emitter 위치에 따른 허용 제한을 대체한다. 제품 식별·원천 보존·토큰 포함관계·가격·프라이버시·보존 경계는 유지한다.

## Context

같은 사용량 계약을 보내는 새 AI 버전이 허용 목록 밖이라는 이유로 generic 저장되고 대시보드 집계에서 빠졌다.
Desktop과 APP_SERVER를 별도 버전 목록으로 관리해 같은 버전도 실행 형태에 따라 빠졌다.
producer 버전과 소스 파일 행 번호는 이벤트 의미를 보장하는 계약 식별자가 아니며, 대시보드 집계에 필요한 값도 아니다.

## Decision

1. 등록된 제품마다 하나의 프로파일을 사용한다. `service.version`은 프로파일 선택 조건에서 제거한다.
   버전이 없거나 새 버전·prerelease·비정형 문자열이어도 등록된 이벤트 계약을 적용한다.
   producer·SDK 버전과 source emitter는 원천 진단 정보로 보존하며, source emitter의 파일·행 번호로 사용량 의미를 결정하지 않는다.
2. 제품은 기존 `service.name`의 정확한 별칭으로 식별한다. Codex CLI·APP_SERVER·Desktop은 같은 제품의 이벤트 계약을 사용하고 surface는 출처로 보존한다.
   모르는 서비스와 제품 접두사 충돌에는 의미를 추정하지 않는다. 프롬프트·응답·계정·토큰 등 기존 metadata 제외 정책을 유지한다.
3. 로그·스팬·메트릭은 각각 기존에 등록된 의미 이름, 분기, 필드 위치·wire 타입으로 해석한다.
   새로운 이름이나 모르는 필드는 자동 승격하지 않는다. 스팬 이름 허용 범위, metric family·형식·temporality 검사와 보조 사용량 역할을 유지한다.
   버전별 일부 이벤트만 지원하던 중복 프로파일은 통합해 등록 순서에 따른 가림을 없앤다.
4. Codex의 `codex.sse_event` + `event.kind=response.completed`에서 기존 필드 타입과 오류·alias 충돌 검사를 거친 사용량만 primary다.
   Responses input은 cache read/write를 포함하고 output은 reasoning을 포함한다. `tool_token_count`는 보고 total이다.
   Claude Code의 일관된 `api_request`는 기존 exclusive input/cache 성분 계약을 사용한다.
   버전·source emitter만 바뀐 동일 계약에는 같은 의미를 부여한다. 누락 성분은 null, 보고된 0은 0으로 구별한다.
5. 필드의 잘못된 타입·음수·중복 충돌·토큰 산식 위반은 기존 품질 플래그와 의미 검증으로 표시하고 유효하지 않은 합계·가격을 생성하지 않는다.
   계산 가능한 total과 보고 total이 다르거나, 포함형 output보다 reasoning/tool 성분이 크면 계약 모순이다.
   산식 모순·오버플로에는 `derived_value_invalid`를 남기고 파생 합계와 환산액을 만들지 않는다. 유효한 원천 성분은 진단용으로 보존한다.
   사용량 후보가 호환되지 않는 경우에는 유한한 제품·결과별 운영 카운터를 남긴다. 버전·모델·세션·테넌트·본문을 metric label에 넣지 않는다.
   generic 저장과 전송 실패·폐기는 서로 다른 상태다.
6. 토큰 의미 ID와 단일 가격표·단가는 유지한다. 매핑 판은 제품별 이벤트 계약을 식별한다.
   아직 배포되지 않은 정규화 판12를 이 변경에 사용한다. 원본의 observation ID·시각·신원을 보존하고, 배포된 rev11 재가격 평가 호환성도 유지한다.
   이미 저장된 generic 행은 이 코드 변경만으로 자동 수정되지 않는다. 재처리는 기존 내부 운영 절차와 보존 경계에 따라 별도 수행한다.

## Alternatives Considered

- 매 릴리스의 정확한 버전·emitter 행을 추가하면 같은 데이터 형식에도 반복해서 코드·배포가 필요하므로 폐기한다.
- 버전 구간을 허용하면 구간 경계에서 같은 문제가 반복되고 필드 검증을 대신하지 못한다.
- 사용량 전용 fallback과 버전별 전체 프로파일을 함께 유지하면 프로파일 순서와 부분 지원의 복잡성이 남는다. 제품별 계약의 기존 이름·타입 검증을 단일 경로로 사용한다.
- 필드를 무조건 수치로 변환하거나 누락 토큰을0으로 채우면 의미 변경·유실을 정상 사용량처럼 계산하므로 채택하지 않는다.

## Consequences/Tradeoffs

### Positive

- 버전·prerelease·실행 형태·소스 행 번호만 바뀌는 업데이트에는 서버 수정 없이 같은 이벤트가 계속 집계된다.
- 이전 실캡처와 미래 버전 변형을 같은 계약으로 검증하고, 형식 불일치의 운영 근거를 남긴다.

### Negative

- 벤더가 필드명·타입을 유지하면서 의미만 바꾸면 형식 검사만으로 식별할 수 없다. 공식 계약·실제 자료와 산식 검증을 계속 유지한다.
- 새 이벤트·서비스 별칭·실제 wire 계약 변경은 명시적 계약 개정이 필요하다.
- 배포·과거 데이터 재처리 완료는 코드와 테스트 완료와 별도로 확인해야 한다.

## Acceptance Criteria

- 임의의 미래 버전·prerelease·누락 버전과 변경·누락 emitter에서도 같은 계약의 세션·토큰·환산액이 동일하다.
- 알려진 Codex 세 surface와 Claude의 같은 계약에 producer 버전별 목록이 필요하지 않다.
- unknown product, 제품 충돌, 오류/사용량 충돌, 잘못된 wire, 누락·음수·중복·오버플로·산식 위반에는 유효하지 않은 값이나 가격을 만들지 않는다.
- 로그 primary·스팬 supplementary·metric auxiliary 구분과 중복 방지를 유지한다.
- 기존 실캡처 fixture, 관측 ID·row_version·rev11 해시 호환성, 수집→저장 입력→대시보드 사용량 경로를 검증한다.
- 운영 카운터는 제한된 label 집합만 사용하고 데이터 본문·식별자·임의 버전을 내보내지 않는다.

## References

- [ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md)
- [ADR 0061](0061-Codex-완료-응답은-포함형-토큰-의미로-정규화하고-검증된-두-프로파일만-합산한다.md)
- [Codex 계약 근거](../../libs/telemetry-adapter/src/test/resources/otlp-v2/codex/PROFILE-EVIDENCE.md)
- [Claude Code 계약 근거](../../libs/telemetry-adapter/src/test/resources/otlp-v2/claude_code/PROFILE-EVIDENCE.md)
