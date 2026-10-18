# 현재 공시 Standard/global 사용 환산 단가

> 아래 v1~v4는 보존된 공시 단가 프로파일이다. 기본 배선은 [Codeburn 참조 가격판](codeburn-reference.md)으로 개정됐다. v4의 단가·장문 정책과 미지원 범위 자체는 변경하지 않는다.

이 문서의 공시 프로파일은 `public-standard-global-reference-2026-10-16-v4`, 공식 문서 조회 기준 시각은
`2026-10-16T08:03:52Z`, 정규화 판은 7이다. Claude 캐시 생성은 **5분 공시 단가를 평가 기준으로 사용**한다.
실제 요청의 TTL이 5분이었다고 추정하거나 원천을 고치는 것이 아니다. Codex의 검증된 토큰 의미에
`gpt-6-astra`와 두 Luna 모델의 요청별 공시 단가도 연결한다. 허브 ADR 0010과 backend ADR 0062가 v4의 기준이며 캐시 평가 기준은 ADR 0009·0061을 유지한다.

이전 프로파일은 그대로 선택 가능하다. v1(`public-standard-global-reference-2026-10-16-v1`)의 조회 기준은
`2026-10-15T23:02:00Z`, v2(`public-standard-global-reference-2026-10-16-v2`)는 `2026-10-15T23:29:51Z`다.
v3(`public-standard-global-reference-2026-10-16-v3`)의 기준은 `2026-10-16T06:59:11Z`다.
v1의 네 모델과 v2의 추가 두 모델, 기존 단가·시각·양수 cache-create null 정책은 변경하지 않는다. v3의 일곱 모델·캐시 정책·장문 경계도 보존한다.
모든 판은 현재 기준 평가이므로 과거 실제 청구 단가를 뜻하지 않는다.

공식 가격과 모델 문서를 대조해 다음 USD / 1,000,000 토큰 값을 고정했다.

| 정확한 모델 ID | uncached input | output | cache read | cache write 5분 / 1시간 |
|---|---:|---:|---:|---:|
| claude-opus-5-5 | 4 | 20 | 0.20 | 5 / 8 |
| claude-opus-5 | 5 | 25 | 0.50 | 6.25 / 10 |
| claude-fable-5-1 | 10 | 50 | 0.25 | 12.50 / 20 |
| claude-haiku-4-5-20251001 | 1 | 5 | 0.10 | 1.25 / 2 |
| claude-sonnet-5-5 (v2 추가) | 2 | 10 | 0.10 | 2.50 / 4 |
| claude-opus-4-8 (v2 추가) | 5 | 25 | 0.50 | 6.25 / 10 |
| gpt-6-astra (v3 추가, 입력 ≤272,000) | 10 | 50 | 1 | 12.50 (TTL 기준 없음) |
| gpt-6-astra (입력 >272,000) | 20 | 75 | 2 | 25 (TTL 기준 없음) |
| gpt-5.6-luna (v4 추가, 입력 ≤272,000) | 0.20 | 1.20 | 0.02 | 0.25 (TTL 기준 없음) |
| gpt-5.6-luna (입력 >272,000) | 0.40 | 1.80 | 0.04 | 0.50 (TTL 기준 없음) |
| gpt-6-luna (v4 추가, 입력 ≤272,000) | 0.10 | 0.50 | 0.01 | 0.125 (TTL 기준 없음) |
| gpt-6-luna (입력 >272,000) | 0.20 | 0.75 | 0.02 | 0.25 (TTL 기준 없음) |

출처는 [공식 가격](https://platform.claude.com/docs/en/about-claude/pricing),
[Opus 5.5](https://platform.claude.com/docs/en/models/opus-5-5/overview),
[Opus 5](https://platform.claude.com/docs/en/models/opus-5/overview),
[Fable 5.1](https://platform.claude.com/docs/en/models/fable-5-1/overview),
[Haiku 4.5](https://platform.claude.com/docs/en/models/haiku-4-5/overview),
[Sonnet 5.5](https://platform.claude.com/docs/en/models/sonnet-5-5/overview),
[Opus 4.8](https://platform.claude.com/docs/en/models/opus-4-8/overview),
[cache](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)다.

Sonnet 5.5와 Opus 4.8의 문서상 출시일은 각각 2026-09-28과 2026-05-28이다. 검증 파생 신호는 출시 이후에 둔다.

`claude-code-exclusive-v1`의 input/output/read/create는 서로 겹치지 않는 성분이다. v3는
`(input × input요금 + output × output요금 + read × read요금 + create × 5분write요금) / 1,000,000`을
정확한 Decimal로 계산한다. 카탈로그의 `cacheCreateReferenceTtlSeconds=300`은 평가 조건만 기록한다.
v1/v2는 여전히 명시 create 0에서만 계산하고 양수·미보고에서는 null이다. v3에서도 미보고 성분은
0으로 채우지 않는다. 중간 이벤트를 반올림하지 않고 저장 스케일 안에서 정확히 유지한다.

Codex는 `codex-responses-inclusive-v1`로 검증된 정확한 생산자·완료 이벤트에 한정한다. input에는 cache read와
cache create가 포함되므로 `uncached = input − read − create`, 전체 토큰은 `input + output`이다.
reasoning과 원천의 tool 필드를 output에 다시 더하지 않는다. 가격은 비중복 uncached/read/create/output에
각각 곱한다. cache-create 키가 없는 로그는 null을 유지하며 이 프로파일로도 가격을 계산하지 않는다.
생산자 근거·지원 범위는 `otlp-v2/codex/PROFILE-EVIDENCE.md`가 소유한다.

[GPT-6 Astra 공식 모델 문서](https://developers.openai.com/api/docs/models/gpt-6-astra)와
[공식 가격표](https://developers.openai.com/api/docs/pricing)의 Standard 단가를 고정했다. 단일 응답의
전체 입력(uncached + read + create)이 **272,000 초과**이면 그 요청 전체의 입력·캐시 단가는 2배,
출력 단가는 1.5배다. 272,000과 그 미만은 기본 단가다. 세션 누적 입력으로 경계를 판정하지 않는다.
미지원 모델·미검증 의미·필수 성분 누락에는 기본 단가를 대입하지 않는다.

Standard/global은 평가 조건이다. 실제 사용의 batch/priority/fast/지역 조건을 알아냈다는 뜻이 아니다.
구독·할인·세금·환율·서버 도구 요금은 포함하지 않는다. `cost_reported_usd`를 추정값에 더하지 않는다.

배선은 `PULSEMETRY_PRICING_PROFILE`로 선택하며 v4 ID를 지정하면 이 공시 판을 사용한다. `none`은 명시적으로 계산하지 않는다.
오타는 기동 오류다. 요금 개정에는 새 ID·고정 시각·단가 근거·정규화 판이 필요하다. 기존 데이터는 조회만으로
재계산되지 않는다. 승인된 범위를 원래 아카이브에서 Adapter부터 재처리하고 관측 식별자·원천을 보존하는
절차는 backend ADR 0061을 따른다. 재평가 전후 결과와 과거 검증 증거는 별도로 보존한다.

## v4의 Luna와 자동 검토 모델

[GPT-5.6 Luna 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-luna)에서 input0.20/read0.02/output1.20 USD/M, write=input×1.25, 272K 초과 배율을 확인했다.
[GPT-6 Luna 모델 문서](https://developers.openai.com/api/docs/models/gpt-6-luna)와 [가격표](https://developers.openai.com/api/docs/pricing)의 Standard 표에서 input0.10/read0.01/write0.125/output0.50 USD/M과 같은 장문 배율을 확인했다.
두 모델은 v3에서 미지원이며 v4에서만 계산한다. Standard/global 평가이므로 Batch/Flex/Fast·지역 할증·구독 크레딧을 적용한 실제 청구액이 아니다.

`codex-auto-review`의 정확 ID에 대한 공개 토큰 단가나 공개 모델과의 동일성은 확인하지 못했다. 이 모델은 v4에서도 null이다.
로컬 Codex commit `cec8c6fb876e90b20250ec740e970e3f33b7396f`의 `codex-rs/models-manager/models.json`은 이 ID를 자동 승인 검토용 별도 숨김 모델로 정의한다.
`codex-rs/model-provider/src/provider.rs`의 `approval_review_preferred_model`은 일반 인증의 기본 `codex-auto-review`와 API-key 인증의 기본 `gpt-5.6-luna`를 구분한다.
같은 역할을 다른 경로에서 수행한다는 사실은 동일 모델·단가의 증거가 아니므로 대체 가격을 적용하지 않는다. 모델 파일의 다른 원문 내용과 사용자 수집 로그는 이 근거에 복사하지 않는다.
