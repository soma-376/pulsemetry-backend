# Codeburn 참조 단가

기본 가격판은 `codeburn-0.9.24-reference-2026-10-16-v1`, 정규화 판은 9다. 사용자가 지정한 Codeburn 기준의 토큰 환산이며 실제 사용 모델의 과금 명세나 구독 청구액이 아니다. 기존 공시 v1~v4는 그대로 선택할 수 있다.

## 반영 범위

설치된 Codeburn 0.9.24의 번들 LiteLLM 표·보완 표·내장 별칭에서 Claude와 OpenAI/Codex 관련 후보 400개를 조사했다. 텍스트 참조 가격 315개(Claude 138개, Codex/OpenAI 177개)를 반영했다. 이 중 56개는 명시적 별칭이다. 기존 카탈로그에서 보존한 3개 ID까지 최종 지원은 318개 ID다. 같은 모델의 다른 표기와 namespace가 포함되므로 318개의 서로 다른 기반 모델을 뜻하지 않는다.

- 입력·출력·cache read·cache write USD/M을 고정 TSV에 저장한다.
- Codeburn에서 cache write가 비어 있으면 input×1.25, cache read가 비어 있으면 input×0.1을 적용하는 규칙을 가져왔다.
- 이미지·음성·실시간 등 별도 모달리티 43개와 Batch 전용 42개는 Standard 텍스트 평가에서 제외한다. 모든 후보는 적용 또는 사유 있는 제외로 분류했다.
- v4에서 직접 검증한 9개 모델은 기존 정확한 단가와 장문 요금을 우선한다. Codeburn에 없더라도 기존 지원을 잃지 않는다. 이전 가격판 내용은 수정하지 않는다.
- 임의 모델 prefix 매칭, 사용자 로컬 alias/override, 실행 중 가격 갱신, 미가격을 0원으로 만드는 처리는 사용하지 않는다. 원천 모델명·날짜·토큰은 그대로 둔다.

카탈로그: [`codeburn-0.9.24.tsv`](../../libs/telemetry-adapter/src/main/resources/pricing/codeburn-0.9.24.tsv).
해시·버전·후보 수·제외 목록: [`codeburn-import-manifest.json`](codeburn-import-manifest.json).

## 자동 검토

`codex-auto-review → gpt-5.5`는 Codeburn의 명시적 별칭이다. input $5/M, output $30/M, cache read $0.50/M, cache create $6.25/M으로 계산한다. 원본 `model=codex-auto-review`를 유지하고 카탈로그 `reference_model=gpt-5.5`로 대체 기준을 남긴다. 실제 모델의 동일성을 주장하지 않는다.

Codex input은 cache read/create를 포함하므로 비중복 input, output, read, create를 각각 곱한다. reasoning/tool 토큰을 output에 다시 더하지 않는다. Claude cache create는 기존 사용자 결정대로 5분 참조 평가다. 필요한 토큰이 누락되거나 의미 프로파일이 검증되지 않으면 여전히 null이다.

## 갱신과 재현

```sh
node scripts/import-codeburn-pricing.mjs /path/to/codeburn/package
./gradlew :libs:telemetry-adapter:test :apps:telemetry-ingest:test
```

스크립트는 Codeburn 0.9.24 패키지의 가격 모듈만 격리 실행한다. CLI·사용자 세션·로그·설정·네트워크에 접근하지 않는다. 번들/보완 표/소스맵/출력 파일의 SHA-256을 명세에 기록한다. Codeburn 자체 `calculateCost`의 고정 실행 결과를 별도 테스트 fixture로 만들어 Kotlin 구현과 전체 행을 대조한다. 직접 검증한 v4 우선 모델은 기존 테스트와 프로파일 불변성 검증을 유지한다.

기존 서버 프로세스는 새 바이너리로 재시작해야 새 기본 가격판을 사용하며 기존 저장 관측은 자동으로 재계산되지 않는다. 기존 원천 재평가는 아카이브에서 Adapter를 다시 거치는 별도 실행이다.

관련 결정: backend [ADR 0065](../adr/0065-Codeburn-단가와-명시적-별칭을-참조-카탈로그에-추가한다.md), 허브 ADR 0012.
