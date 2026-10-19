# 직접 확인한 모델 단가

기본 가격판 `codeburn-0.9.24-reference-2026-10-16-v1`에서 아래 9개 모델은 공급자 공식 문서로 직접 확인한 단가와
장문 요율이 Codeburn 값보다 우선한다([ADR 0060](../adr/0060-사용-환산액은-고정된-참조-가격판으로-계산한다.md)).
나머지 모델은 [Codeburn 참조 단가](codeburn-reference.md)를 따른다. 모두 현재 기준 평가이며 과거 실제 청구 단가가 아니다.

## 단가

USD / 1,000,000 토큰. 공식 문서 조회 기준 시각은 `2026-10-16T08:03:52Z`다.

| 정확한 모델 ID | uncached input | output | cache read | cache write 5분 / 1시간 |
|---|---:|---:|---:|---:|
| claude-opus-5-5 | 4 | 20 | 0.20 | 5 / 8 |
| claude-opus-5 | 5 | 25 | 0.50 | 6.25 / 10 |
| claude-fable-5-1 | 10 | 50 | 0.25 | 12.50 / 20 |
| claude-haiku-4-5-20251001 | 1 | 5 | 0.10 | 1.25 / 2 |
| claude-sonnet-5-5 | 2 | 10 | 0.10 | 2.50 / 4 |
| claude-opus-4-8 | 5 | 25 | 0.50 | 6.25 / 10 |
| gpt-6-astra (입력 ≤272,000) | 10 | 50 | 1 | 12.50 (TTL 기준 없음) |
| gpt-6-astra (입력 >272,000) | 20 | 75 | 2 | 25 (TTL 기준 없음) |
| gpt-5.6-luna (입력 ≤272,000) | 0.20 | 1.20 | 0.02 | 0.25 (TTL 기준 없음) |
| gpt-5.6-luna (입력 >272,000) | 0.40 | 1.80 | 0.04 | 0.50 (TTL 기준 없음) |
| gpt-6-luna (입력 ≤272,000) | 0.10 | 0.50 | 0.01 | 0.125 (TTL 기준 없음) |
| gpt-6-luna (입력 >272,000) | 0.20 | 0.75 | 0.02 | 0.25 (TTL 기준 없음) |

출처: [Anthropic 공식 가격](https://platform.claude.com/docs/en/about-claude/pricing),
[Opus 5.5](https://platform.claude.com/docs/en/models/opus-5-5/overview),
[Opus 5](https://platform.claude.com/docs/en/models/opus-5/overview),
[Fable 5.1](https://platform.claude.com/docs/en/models/fable-5-1/overview),
[Haiku 4.5](https://platform.claude.com/docs/en/models/haiku-4-5/overview),
[Sonnet 5.5](https://platform.claude.com/docs/en/models/sonnet-5-5/overview),
[Opus 4.8](https://platform.claude.com/docs/en/models/opus-4-8/overview),
[prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching),
[GPT-6 Astra](https://developers.openai.com/api/docs/models/gpt-6-astra),
[GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna),
[GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna),
[OpenAI 가격표](https://developers.openai.com/api/docs/pricing).

Sonnet 5.5와 Opus 4.8의 문서상 출시일은 각각 2026-09-28과 2026-05-28이다. 검증 파생 신호는 출시 이후에 둔다.

## 산식

- `claude-code-exclusive-v1`의 input·output·read·create는 서로 겹치지 않는다.
  `(input × input요금 + output × output요금 + read × read요금 + create × 5분write요금) / 1,000,000`을 정확한 Decimal로 계산한다.
  5분 쓰기 단가는 평가 기준이며 실제 TTL 판정이 아니다. 명시 0은 0, 미보고는 null이다.
- Codex는 `codex-responses-inclusive-v1`([ADR 0061](../adr/0061-Codex-완료-응답은-포함형-토큰-의미로-정규화하고-검증된-두-프로파일만-합산한다.md))의
  비중복 uncached·read·create·output에 각각 곱한다. reasoning과 tool 필드를 output에 다시 더하지 않는다.
  cache-create 키가 없는 로그는 null이다.
- 장문 요율은 위 표의 세 OpenAI 모델에만 적용한다. 단일 응답의 전체 입력(uncached + read + create)이
  **272,000 초과**이면 그 요청 전체의 입력·캐시 단가는 2배, 출력 단가는 1.5배다. 272,000 이하는 기본 단가다.
  세션 누적 입력으로 판정하지 않는다.
- 중간 이벤트를 반올림하지 않고 저장 스케일 안에서 정확히 유지한다.

Standard/global은 평가 조건이다. 실제 사용의 batch·priority·fast·지역 조건을 알아냈다는 뜻이 아니다.
구독·할인·세금·환율·서버 도구 요금은 포함하지 않는다. `cost_reported_usd`를 추정값에 더하지 않는다.

## 자동 검토 모델

`codex-auto-review`의 정확한 ID에 대한 공식 토큰 단가나 공개 모델과의 동일성은 확인하지 못했다. 로컬 Codex commit
`cec8c6fb876e90b20250ec740e970e3f33b7396f`의 `models-manager/models.json`은 이 ID를 자동 승인 검토용 별도 숨김 모델로
정의하고, `model-provider/src/provider.rs`의 `approval_review_preferred_model`은 일반 인증에서 `codex-auto-review`,
API-key 인증에서 `gpt-5.6-luna`를 고른다. 같은 역할을 한다는 사실은 같은 모델·단가의 근거가 아니므로 Luna 단가를
적용하지 않는다. 기본 가격판은 Codeburn의 명시적 별칭(`gpt-5.5`)으로 계산한다([Codeburn 참조 단가](codeburn-reference.md)).

## 배선과 이전 가격판

`PULSEMETRY_PRICING_PROFILE`로 가격판을 고른다. 기본값은 Codeburn 판이고 `none`은 계산하지 않음을 명시한다.
오타는 기동 오류다. 요금을 바꾸려면 새 ID·고정 시각·단가 근거·정규화 판이 필요하다.

개발 중 만든 공시 가격판 v1~v4(`public-standard-global-reference-2026-10-16-v1`~`v4`)는 같은 이름으로 선택할 수 있는
불변 값으로 남는다. 로컬 검증 데이터가 이 판들을 참조한다. 배포 기본값이 아니며 단가·시각·정책을 바꾸지 않는다.

저장된 행은 가격판을 바꿔도 다시 계산되지 않는다. 재평가는 [운영 절차](stored-usage-repricing.md)와
[ADR 0062](../adr/0062-저장된-사용량은-토큰-의미가-확인된-행만-재평가하고-나머지는-사유로-남긴다.md)를 따른다.
