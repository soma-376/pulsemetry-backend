# 0065. Codeburn 단가와 명시적 별칭을 참조 카탈로그에 추가한다

## Status

Accepted — 허브 ADR 0012를 구현하며 ADR 0062의 기본 가격판과 자동 검토 미지원 결정을 부분 대체한다. v1~v4의 가격·장문 기준·재현성은 유지한다.

## Context

사용자는 설치된 Codeburn의 Codex·Claude 관련 단가 전체를 적용하도록 요청했다. 기존 가격판 v4는 9개 모델만 지원하고 `codex-auto-review`는 미가격이다.

## Decision

1. `codeburn-0.9.24-reference-2026-10-16-v1`을 새 기본 가격판으로 추가한다. 번들·보완 표의 관련 텍스트 모델과 명시적 별칭을 고정 TSV로 적재하며 추출 스크립트와 해시 명세를 보존한다.
2. 새 `CodeburnReferencePrices`가 정확한 모델 ID로 가격을 선택한다. 별칭도 별도 정확한 행이며 원본 모델명은 바꾸지 않는다. `codex-auto-review`의 reference model은 `gpt-5.5`다.
3. v4의 9개 검증 모델은 기존 단가·장문 정책을 우선한다. 나머지는 Codeburn의 input/output/cache read/cache write 요율을 사용한다. 빠진 cache write/read 요율은 Codeburn의 명시된 파생 규칙(input×1.25/×0.1)을 적용하고 카탈로그에 수치로 고정한다.
4. 기존 의미 프로파일에만 연결하고 미보고 토큰·미검증 의미는 null이다. 미가격은 0원으로 바꾸지 않는다. 별칭·기간 보정은 가격 선택에만 작용한다.
5. 정규화 판을 9로 올린다. 기존 가격 프로파일 선택은 그대로 지원한다. 서버 재시작·저장 데이터 재평가는 코드 적용과 별개로 기록한다.

## Alternatives Considered

Codeburn 전체 CLI를 런타임 의존성으로 사용하면 로컬 세션 탐색·캐시 갱신까지 실행되므로 채택하지 않는다.

## Consequences/Tradeoffs

### Positive

기존 프로파일을 보존하면서 지원 범위와 자동 검토 환산을 확대한다.

### Negative

Codeburn 참조 요율은 실제 요청의 과금 모델·지역·서비스 등급을 입증하지 않는다. 직접 검증한 장문 요금 때문에 일부 모델 금액은 Codeburn과 다를 수 있다.

## Acceptance Criteria

- 카탈로그의 전체 관련 후보가 적용 또는 사유 있는 제외로 분류된다.
- 독립 Codeburn 함수로 계산한 모델별 금액과 새 가격 단계가 일치한다(v4 우선 모델은 별도 검증).
- 자동 검토의 비중복 토큰 산식·원본 모델명 보존·미보고 null·미등록 모델 null을 검증한다.
- v1~v4의 기존 테스트와 새 기본 배선 테스트를 통과한다.
