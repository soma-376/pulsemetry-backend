# 저장된 사용량의 단일 참조 단가 재평가

`repriceStoredUsage`는 저장된 정규화 관측을 분류하고, 토큰 의미를 믿을 수 있는 행의 **가격 단계만** 재실행하는
내부 운영 명령이다. 이미 적재된 토큰·날짜·모델·소속·원본 아카이브·수신 시각을 유지한다. 원본 재정규화나
새 수집 요청을 대신하지 않으며 관리자 API를 열지 않는다. 현재 단일 카탈로그를 사용하며 가격판 선택 인자는 없다. 결과 정규화 규칙 번호는 11이다.
[ADR 0071](../adr/0071-서버-환산은-단일-카탈로그를-쓰고-가격판-기록을-제거한다.md)과 [ADR 0062](../adr/0062-저장된-사용량은-토큰-의미가-확인된-행만-재평가하고-나머지는-사유로-남긴다.md)가 정한다.
배포나 가격표 변경만으로 이 명령을 자동 실행하지 않는다.

## 지원 범위

각 행은 다음 넷 중 하나로 분류된다. 의미나 원본이 없는 행을 추정으로 채우지 않는다.

| 분류 | 조건 | 처리 |
|---|---|---|
| `reprice` | 정규화 규칙 1·7·8·9·10이고 의미 프로파일이 확인되며 현재 계산 금액이 저장 금액과 다르다 | 가격만 다시 계산해 규칙 11로 교체한다. 단가가 없는 모델은 금액 null로 쓴다(`price_unavailable`) |
| `current` | 지원하는 규칙의 행이며 저장 금액이 현재 계산과 같다 | 쓰지 않는다. 현재 규칙 11인데 결과가 다르면 같은 행 버전으로 덮어쓰지 않고 중단한다 |
| `renormalize` | 토큰 의미가 없거나(`semantics_missing`) 배포되지 않은 개발 판 2~6이다(`unsupported_normalizer_rev`). 원본 참조(`archive_ref`)는 있다 | 쓰지 않는다. 원본이 남아 있을 때만 원본 재정규화로 고칠 수 있다 |
| `unrecoverable` | `renormalize`와 같은 사유인데 원본 참조도 없다 | 쓰지 않는다. 사유와 건수만 보고한다 |

규칙 1·7·8·9·10은 기존에 사용한 규칙이다. 이 규칙들의 의미 프로파일 ID와 토큰 유도 규칙이
현재 판과 같다. develop 판의 Codex 행과 Claude Code 2.1.283 이후 행은 의미 프로파일이 없어 `renormalize` 또는
`unrecoverable`이다. 이 행들은 대시보드에서 환산 미확인 건수로 남는다.

## 입력 준비

1. 작업 대상 tenant와 기간을 고정한다. `telemetry_events FINAL`에서 해당 범위의
   `event_type='model.response.usage' AND usage_role='primary'`를 `SELECT *`로 내보낸다.
   `ORDER BY observation_id SETTINGS output_format_json_quote_64bit_integers=1,
   output_format_json_quote_decimals=1 FORMAT JSONEachRow`를 사용한다.
2. 파일은 원문을 포함하므로 소유자만 읽을 수 있는 디렉터리에 0600으로 저장하고 SHA-256을 계산한다.
   날짜·토큰·모델·측정값을 편집하지 않는다. 원본 데이터나 이 파일을 모델 입력·콘솔·보고서에 싣지 않는다.
3. 대상 행 수와 불변 필드 해시, 대상 외 행·원본 파일·수집 운영 상태의 해시를 별도 체크포인트에 기록한다.

## 검산

다음 명령은 DB에 접속하지 않고 파일 전체를 검산·분류한다.

```bash
./gradlew :apps:telemetry-ingest:repriceStoredUsage \
  --args='/protected/before.jsonl <sha256> <tenant-uuid> --archive-refs=/protected/archive-refs.txt'
```

열 누락·추가, 저장된 `analysis_hash` 불일치, 중복 ID, 다른 tenant, 현재보다 높은 정규화 판이 있으면 한 행도 쓰기 전에
중단한다. 구 규칙 1~9의 입력에는 `pricing_version` 호환 열이 있어야 하며 해당 값을 포함한 과거 해시로 검증한다.
새 규칙의 입력은 이 열이 없거나 NULL이어야 한다. 중단 사유는 고정 문장으로 출력하고 원문은 출력하지 않는다.

출력은 분류별 건수와 사유, 환산 합계(`costUsd`), 단가가 없는 모델별 건수(`unpricedModels`),
재정규화 후보의 원본 참조 수와 수신 시각 범위(`renormalizeArchive`)다. `--archive-refs`를 주면
재정규화 후보의 원본 참조 목록을 새 파일로 쓴다. 이미 있는 파일은 덮어쓰지 않는다.
원본이 실제로 남아 있는지는 이 목록으로 객체 메타데이터만 조회해 확인한다. 원본 내용을 내려받지 않는다.

## 적재와 재개

적재에는 다음 환경변수가 필요하다. 비밀은 셸 이력이나 명령 인자에 넣지 않고 실행 환경에 주입한다.

- `PULSEMETRY_REPRICE_CLICKHOUSE_URL`
- `PULSEMETRY_REPRICE_JDBC_URL`
- `PULSEMETRY_REPRICE_JDBC_USER`
- `PULSEMETRY_REPRICE_JDBC_PASSWORD`

같은 명령 끝에 `--apply`를 붙이면 전체 검산 이후에만 `reprice` 행을 적재한다. 배치마다 현재 저장된
버전·해시와 대조하고 RDS 보존 경계를 읽은 뒤 기존 typed sink와 ClickHouse fence를 거친다. 관측 ID와 원래
`ingest_seq`를 유지해 규칙 11 행으로 교체한다. heartbeat·수집 ledger·원본 아카이브는 쓰지 않는다.

배치 전체의 원자적 트랜잭션은 아니다. 중간에 실패하면 같은 입력으로 다시 실행한다. 다시 실행하면 다음처럼 처리한다.

| 현재 저장 상태 | 처리 | 보고 |
|---|---|---|
| 이미 목표 버전·해시와 같다 | 건너뛴다 | `alreadyApplied` |
| 내보낸 뒤 지워졌다(보존 삭제 등) | 되살리지 않는다 | `removed` |
| 내보낸 뒤 더 높은 버전이 적재됐다 | 보내지 않는다. 그 쓰기는 교체에서 진다 | `superseded` |
| 보존 경계 밖으로 밀렸다 | 쓰지 않는다 | `outsideBoundary` |
| 내보낸 버전이 아니고 목표보다 낮은 다른 값이다 | 오래된 체크포인트로 덮어쓰지 않고 중단한다 | 오류 |

다른 수집·정규화 작업과 동시에 같은 대상 행을 수정하지 않는 운영 시간대에서 실행한다.

## 완료 판정

`written`은 sink에 제출한 행 수이므로, 서버에서 움직인 보존 fence가 제외한 행까지 보장하지 않는다.
반드시 `FINAL`로 다시 읽어 대상 수·관측 ID·날짜·모든 토큰·모델·소속·수신 시각·원본 링크가 같은지 확인한다.
업무 값에서 변경을 허용하는 열은 `cost_estimated_usd`뿐이고 `row_version`, `normalizer_rev`, `analysis_hash`를 함께 갱신한다.
호환용 `pricing_version`은 새 행에서 NULL이 된다. 이 물리 열은 삭제하지 않으며 구 체크포인트의 해시 검증에만 읽는다. 가격판 차이만으로 재평가하지 않는다.
`renormalize`·`unrecoverable` 행은 바뀌지 않아야 한다. 대상 외 행과 원본 파일·수집 상태 해시도 비교한다.
같은 입력으로 한 번 더 실행해 `written=0`과 동일 최종 결과를 확인하고, 새로 내보낸 파일을 검산하면
`reprice`가 0건이어야 한다. 대시보드는 이전 snapshot을 재사용하지 않고 새 snapshot으로 확인한다.

이 명령의 완료는 `reprice` 행의 완료다. **기존 데이터 재처리 완료**는 다음이 모두 충족됐을 때만 판정한다.

1. `reprice`가 0건이다.
2. `renormalize` 행마다 원본 존재를 메타데이터로 확인했다. 원본이 있는 행은 원본 재정규화로 처리했고,
   원본이 없는 행은 `unrecoverable`로 옮겨 사유와 건수를 기록했다.
3. `unrecoverable` 행을 추정값으로 채우지 않았다. 이 행들은 환산 미확인 건수로 남는다.

원본 재정규화 경로는 이 명령에 없다. 원본 확인 전에는 `renormalize` 건수와 수신 시각 범위만 보고한다.
