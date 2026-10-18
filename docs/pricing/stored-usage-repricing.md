# 저장된 사용량의 Codeburn 참조 단가 재평가

`repriceStoredUsage`는 검증된 정규화 관측의 **가격 단계만** 재실행하는 내부 운영 명령이다.
이미 적재된 토큰 의미·날짜·모델·소속·원본 아카이브·수신 시각을 유지한다. 전체 원본 정규화나
새 수집 요청을 대신하지 않으며 관리자 API를 열지 않는다. 가격판은 ADR 0065의
`codeburn-0.9.24-reference-2026-10-16-v1`, 결과 정규화 판은 9다.

## 입력 준비

1. 작업 대상 tenant와 기간을 고정한다. `telemetry_events FINAL`에서 해당 범위의
   `event_type='model.response.usage' AND usage_role='primary'`를 `SELECT *`로 내보낸다.
   `ORDER BY observation_id SETTINGS output_format_json_quote_64bit_integers=1,
   output_format_json_quote_decimals=1 FORMAT JSONEachRow`를 사용한다.
2. 파일은 원문을 포함하므로 소유자만 읽을 수 있는 디렉터리에 0600으로 저장하고 SHA-256을 계산한다.
   날짜·토큰·모델·측정값을 편집하지 않는다. 원본 데이터나 이 파일을 모델 입력·콘솔·보고서에 싣지 않는다.
3. 대상 행 수와 불변 필드 해시, 대상 외 행·원본 파일·수집 운영 상태의 해시를 별도 체크포인트에 기록한다.

## 검산과 실행

다음 명령은 기본적으로 DB에 접속하지 않고 파일 전체를 검산한다.

```bash
./gradlew :apps:telemetry-ingest:repriceStoredUsage \
  --args='/protected/before.jsonl <sha256> <tenant-uuid>'
```

열 누락·추가, 저장된 `analysis_hash` 불일치, 중복 ID, 다른 tenant, 미지원 정규화 판,
가격 미확인 행이 있으면 실행을 중단한다. 기존 판 7·8과 동일한 목표 판 9만 지원한다.
이전 매핑이나 토큰 의미를 바꿔야 하는 경우에는 원본부터 별도 정규화해야 한다.
출력은 행 수·환산 합계·가격판뿐이며 원문을 출력하지 않는다.

적재에는 다음 환경변수가 필요하다. 비밀은 셸 이력이나 명령 인자에 넣지 않고 실행 환경에 주입한다.

- `PULSEMETRY_REPRICE_CLICKHOUSE_URL`
- `PULSEMETRY_REPRICE_JDBC_URL`
- `PULSEMETRY_REPRICE_JDBC_USER`
- `PULSEMETRY_REPRICE_JDBC_PASSWORD`

동일 체크포인트 명령 끝에 `--apply`를 붙이면 전체 검산 이후에만 적재한다.
각 배치는 현재 저장된 버전·해시와 대조하고 RDS 보존 경계를 읽은 뒤 기존 typed sink와
ClickHouse fence를 거친다. 관측 ID와 원래 `ingest_seq`를 유지해 판 9 행으로 교체하며
`PricingStage`와 `AnalysisHash.seal`이 가격·봉인을 계산한다. heartbeat·수집 ledger·원본 아카이브는 쓰지 않는다.

배치 전체의 원자적 트랜잭션은 아니다. 중간 실패 시 체크포인트를 보존하고 같은 입력을 재실행한다.
이미 목표 버전과 해시가 같으면 쓰기를 건너뛰며 다른 수정이 발견되면 중단한다.
다른 수집·정규화 작업과 동시에 같은 대상 행을 수정하지 않는 운영 시간대에서 실행한다.

## 완료 판정

`written`은 sink에 제출한 행 수이므로, 서버에서 움직인 보존 fence가 제외한 행까지 보장하지 않는다.
반드시 `FINAL`로 다시 읽어 대상 수·관측 ID·날짜·모든 토큰·모델·소속·수신 시각·원본 링크가
같고 단일 가격판·환산 완료 상태인지 확인한다. 변경을 허용하는 열은 `row_version`,
`normalizer_rev`, `analysis_hash`, `cost_estimated_usd`, `pricing_version` 다섯뿐이다.
대상 외 행과 원본 파일·수집 상태 해시도 비교한다. 같은 명령을 한 번 더 실행해 `written=0`과
동일 최종 결과를 검증한다. 대시보드는 이전 snapshot을 재사용하지 않고 새 snapshot으로 확인한다.
