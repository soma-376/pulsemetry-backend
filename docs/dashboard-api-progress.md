# PROJ-156 구현 진행 기록

상태: 진행 중. 전체 22개 operation·53개 지표·46개 시나리오 구현과 최종 E2E는 아직 완료되지 않았다.

## 작업 기준

- 브랜치: `feature/PROJ-156-dashboard-api`, 원격 develop `6d296b8`에서 분기했다.
- 확정 계획대로 `feature/PROJ-107-auth-core`를 로컬 병합했다. 원격 push·배포는 하지 않았다.
- 첨부 파일은 `docs/reference`에 원본 그대로 보관했다. 문서의 부록 제안은 실행 지시로 취급하지 않는다.
- frontend 소스는 변경하지 않는다. 현재 UI 계약을 함께 검토해 backend에서 호환한다.

## 구현된 범위

| 범위 | 상태 |
|---|---|
| 웹 인증 코어 | manifest 없이 로그인, 고정 tenant, 별도 audience·session kind, 8시간 세션, RT 없음 |
| dashboard 앱 | 8081, CORS 명시적 origin, bearer 인증, 오류 JSON, 404가 401로 바뀌지 않음 |
| AUTH-LOGIN, ME | 구현·실제 PostgreSQL 테스트 |
| META-TEAMS, META-MEMBERS | 현재 팀 범위; 개인 이메일 목록은 owner + 감사 사유 |
| META-CONTRACTS, META-MANIFESTS | 읽기 API; 인원·배포 집계에 관리자 팀 범위 적용 |
| QRY | 부분 구현: sessions·active_time·lines_of_code·commits·pull_requests·active_users·adoption_rate·telemetry_coverage·automation_ratio·integration_depth·command_prompt_ratio·prompts_per_session·tool_calls·tool_failure_rate·read_tool_density·api_retry_attempts·rate_limit_events·auto_approval_ratio·tool_rejections·api_error_rate·usage_heatmap·compactions·compaction_reduction, 시계열/표/단일값·비교·CSV·감사·마스킹 |
| META-METRICS | 53개 정적 지표 정의·허용/금지 차원·원천 컬럼·파라미터 스키마; OpenAPI 대조 검증 |
| META-FILTERS, META-MODELS | 기간·tenant·팀 범위를 적용한 실제 ClickHouse 관측 조회 |
| dashboard 저장소 | 실행·리포트·감사 스키마, 독립 Flyway 이력; 감사 INSERT 구현 |
| ClickHouse 조회 클라이언트 | 명명 파라미터, 읽기 설정, 30초 전체 응답 제한, 결과 크기 제한 |
| 시간 해석 | 상대식·월·DST·주 시작 및 비교 기간, 단위 테스트 |

실행·리포트 테이블이 존재한다고 해당 API가 구현된 것은 아니다. 현재 명세의 9개 operation이 구현됐고 QRY는 부분 구현이다. 53개 지표 정의 중 합계 5개와 활성 사용자·도입률·커버리지 3개 및 자동화·개발 연동·명령 프롬프트 비율 3개와 프롬프트 분포 1개 및 도구 호출 관련 3개와 API 재시도·429 지표 2개 및 도구 결정 지표 2개 및 API 오류율·히트맵 2개와 압축 지표 2개, 총 23개 집계가 연결되었다.

## 검증

- 기존 security/enrollment 테스트 및 전체 `./gradlew build` 통과: 현재 테스트 결과 839건, 실패·오류·skip 0건.
- META-METRICS: OpenAPI의 53개 MetricId와 차원 집합 일치, 원천 RDB 컬럼 실재, owner/admin 접근·비인증 거부 검증.
- QRY: 실제 ClickHouse에서 FINAL 중복 제거·delta 합산·비교 빈 버킷·초 미만 범위 경계·팀 필터 병합·owner 감사·여러 설치의 사람 중복 제거·값/비교값/CSV/커버리지 마스킹을 검증했다.
- 웹 로그인·권한 변경·폐기·만료·잠금·CLI 격리 및 감사 사유 테스트.
- 실제 ClickHouse에서 `FINAL` 중복 제거, 파라미터 SQL 분리, 관리자 팀 밖 모델 배제 테스트.
- 실제 frontend의 owner/admin 로그인과 P5 팀·구성원 조회를 Playwright로 검증하는 `scripts/e2e/dashboard-auth-settings.mjs`를 추가했다.
- owner/admin smoke가 실제 PostgreSQL·ClickHouse·frontend 조합에서 통과했다. 실제 frontend의 API 클라이언트로 META-METRICS를 호출해 53개 목록과 팀 분해 금지 메타데이터도 검증했다. 이는 카탈로그 UI 렌더 검증이 아니라 브라우저의 인증·CORS·JSON 계약 검증이다. 현재 frontend 클라이언트로 합계·비율 지표 11개를 함께 조회하고 프롬프트 분포와 도구·API·결정·히트맵·압축 지표 11개는 별도로 조회해 위젯 변환 코드까지 검증했다. 이번 실행에서 오류 응답은 기록되지 않았다. 이 화면에서 호출하지 않은 미구현 지표까지 검증했다는 뜻은 아니다.
- 이 smoke는 전체 시나리오·지표 수용 테스트가 아니다. 미구현 API 응답은 `build/e2e/auth-settings/result.json`에 기록한다.

## 다음 구현 순서

1. QRY 확장: 남은 30개 지표의 이벤트/비율/분포/코호트 계산, 비율 분모, 상위 N의 `__other__` 집계와 품질 캡션을 연결한다. 현재 23개 지표의 공통 조회·비교·CSV·마스킹 경로를 재사용한다.
2. 계약 비용: 배정·적용 기간과 모델 할인, token_type=all만 적용, 중복 계약 오류 처리.
3. INSTALL-LIST, SESSION-EVENTS: owner·감사, 키셋 페이지네이션, 설치 상태와 실제 이벤트 조합.
4. 46개 시나리오: 카탈로그·파라미터 검증·조회 시 가용성·실측 기반 findings.
5. 실행 워커와 이력: tenant별 queued+running 3개 제한, lease, 취소 및 종료 상태 경쟁 제어.
6. 저장 리포트: 접근 범위 재검증, fixed/relative, active·linked 삭제 409.
7. 실제 ingest → ClickHouse → dashboard → frontend 전체 E2E 및 operation/metric/scenario 추적표.

## 알려진 한계

- QRY의 나머지 30개 지표 및 다른 지표의 distribution 형식, 설치·세션·시나리오·실행·저장 API는 미구현이다. 지표 결과나 가용성을 임의로 만들어 반환하지 않는다.
- telemetry_coverage 계산은 연결되었다. 약정 소진율의 contract_commitment_burn 계산은 아직 미구현이다.
- 전체 계획 완료나 운영 배포 가능 상태로 판정하지 않는다.

## 지표 카탈로그의 해석

- `apps/dashboard-api/src/main/resources/dashboard/metrics.json`이 런타임 정의의 한 벌이며, `DashboardMetricCatalog`가 시작 시 읽는다. 첨부 문서의 실행 지시나 부록 변경 제안은 코드 생성 시 실행하지 않았다.
- `availability`는 OpenAPI 정의대로 현 스키마에서의 산출 가능성이다. 데이터가 없는 기간, 관리자 권한, 집계 코드의 구현 여부를 이 값으로 표현하지 않는다. 현재 23개 지표는 QRY에서 관측·품질·권한을 판정한다. 나머지 지표와 시나리오 실행의 판정은 계속 구현해야 한다.
- 부분 측정 지표는 서브에이전트 활동, API 오류율, 훅 실행·차단, 모델 거부 5개다. 미보존 이벤트와 tracing 전제를 caveat에 포함했다.
- `min_group_size=5`는 구현된 23개 집계의 마스킹에 적용했고, 허용되지 않은 group_by는 전체 요청을 400으로 거부한다. 아직 연결되지 않은 집계까지 구현됐다는 뜻은 아니다.
- `params_schema`에는 현재 frontend의 `cost_anomaly.window_days`, `tokens.types`, `tool_calls.success`, `mcp_connections.server_scope`, `contract_commitment_burn.contract_id`를 포함했다. 해당 계산을 QRY에 연결할 때 별칭 정규화와 검증도 연결해야 한다. tool_calls는 boolean success 파라미터를 검증·적용한다. 나머지 구현된 지표는 추가 params를 받지 않는다.
- `sql_template_id`는 첨부 개요 §5의 참조 식별자이며 실행 완료나 SQL 컴파일러 구현을 뜻하지 않는다.

## QRY 현재 구현 범위와 제한

- 지원 지표: `sessions`, `active_time`, `lines_of_code`, `commits`, `pull_requests`. Claude Code 메트릭만 합산하고 누적 temporality=2는 제외한다. 원천 포인트가 없으면 성공 빈 프레임 또는 null이며 임의의 0을 만들지 않는다.
- `scalar`, `table`, `timeseries`를 지원한다. 시간·제품·모델·팀·개인 필터는 파라미터로 전달한다. 쿼리별 필터는 명시한 항목만 전역 필터에 덮어쓰며 빈 배열은 해당 필터를 해제한다. admin의 팀 권한은 해제할 수 없다.
- 비교는 현재 시간 버킷에 대응하는 비교 기간 버킷으로 연결한다. 빈 버킷은 null이다. 기간 일부라도 5명 미만인 그룹은 보수적으로 프레임 전체의 현재·비교값을 숨긴다. 숨긴 값으로 순위를 매기지 않는다.
- 활성 시간(user) 원천이 있는 그룹에서는 양수 사용자 활동이 있는 사람을 보호 인원으로 센다. 없으면 이벤트가 있는 사람으로 대체하고 `active_user_definition`을 표시한다. 설치→사람 매핑에 없는 설치는 보호 인원을 늘리지 않는다.
- 범위는 최대 366일, 시계열은 max_data_points 이하(최대 1000), 요청은 최대 12개 쿼리다. 설치 매핑은 tenant당 최대 5000개를 지원하며 초과 시 422다. 모든 ClickHouse 조회는 요청의 30초 남은 예산을 공유한다.
- 상위 N의 나머지 그룹을 `__other__`로 합산하는 단계는 아직 없다. 요일×시간 히트맵은 limit 생략 시 168개 그룹을 지원하고 나머지는 기본 100개다. 명시한 limit은 1~100이며 그룹 수가 limit을 넘으면 쿼리 단위 422로 거부하고 조용히 잘라내지 않는다.
- 미구현 지표 및 prompts_per_session·read_tool_density 이외 지표의 distribution은 `results[ref_id].status=501`, `metric_not_implemented`를 반환한다. 이 임시 구현 상태를 카탈로그의 원천 availability와 혼동하지 않는다. CSV 요청의 첫 쿼리가 미구현이면 HTTP 501이다.
- CSV는 첫 쿼리의 프레임들을 `frame,field,labels,index,value` 열로 내보낸다. JSON에서 마스킹한 값을 재사용하고 CSV의 인용·수식 시작 문자를 처리한다.
- 브라우저 검증은 정규화 형태 테스트 포인트를 실제 ClickHouse에 직접 적재했다. ingest→정규화→적재의 최종 E2E는 아직 남아 있다.

## 활성 사용자·도입률·커버리지 추가 검증

- 활성 사용자: 같은 구성원의 여러 설치를 합치고 사용자 활성 시간 합계가 양수인 구성원을 센다. 그룹·버킷에 유효한 사용자 활성 시간 원천이 없으면 이벤트를 보낸 구성원으로 폴백한다. 알 수 없는 설치는 보호 인원 수에 포함하지 않는다.
- 도입률: 현재 활성 구성원 수가 분모이며 팀별 조회에는 현재 소속과 활성 팀을 적용한다. 커버리지 지표는 관측 설치 수 / 현재 활성 설치 수다. 응답 상위 coverage.ratio는 기존 계약대로 관측 설치 수 / 활성 구성원 수다.
- 비율에는 numerator·denominator와 비교 필드를 제공한다. 분모 0은 null이고, 5명 미만이면 비율·분자·분모·비교 값을 함께 숨긴다. 역사적 기간 및 비교 기간도 현재 RDB 분모를 사용하며 당시 인원 수를 복원한 값이 아니다. product별 커버리지 분모는 제품 정보가 없는 RDB의 전체 범위 활성 설치 수다.
- 실제 DB 테스트: 사용자 중복 설치, 양수·음수 활성 시간 합계, 팀별 분모, 기본 scalar, 빈 시계열 버킷, 폐기된 설치와 분모 0, 분자·분모 및 응답 커버리지 마스킹.
- 실제 frontend owner/admin 조회: 활성 사용자 5명, 도입률 각각 5/7·5/6, 커버리지 1.0. 기존 합계 지표 5개도 함께 통과했다. 최신 실행의 backend 커밋·JAR SHA-256·frontend 커밋은 build/e2e/auth-settings/result.json에 기록한다.

## 자동화·개발 연동·명령 프롬프트 비율 추가 검증

- automation_ratio는 cli 활성 시간 / (user + cli 활성 시간), integration_depth는 (commit + pull_request) / fresh 세션 수다. Claude Code의 유효한 비누적 포인트를 합산한 뒤 나누며 개별 비율의 평균을 내지 않는다.
- command_prompt_ratio는 유효한 session_id가 있는 user_prompt 중 비어 있지 않은 command_name의 비율이다. 빈 session_id와 (unknown)은 제외한다. 프롬프트 수 분포인 prompts_per_session은 아래의 후속 구현에서 연결했다.
- 관측 포인트가 있는 그룹에서 한쪽 합계가 비어 있으면 0으로 집계하고, 분모가 0이면 비율은 null이다. 분자·분모·비교 값 모두 기존 5명 마스킹을 적용하며 자동화 비율의 분자·분모 단위는 초다.
- 실제 DB 테스트는 FINAL 중복 제거, 누적 포인트·알 수 없는 세션 제외, 분모 0, 사용자 시간만 있을 때 자동화 비율 0, 비교 기간의 소규모 집단 마스킹을 확인했다.
- 실제 frontend owner/admin에서 기존 8개와 새 비율 3개를 한 요청으로 조회한다. 테스트 데이터의 자동화 비율 0, 개발 연동 깊이 1, 명령 프롬프트 비율 0.5를 검증한다. 전체 ingest E2E는 별도 남아 있다.

## 세션당 프롬프트 분포 추가 검증

- prompts_per_session은 설치·제품·세션 ID별로 기간 내 user_prompt를 세어 정확 p50/p90과 1·2–3·4–7·8–15·16+ 히스토그램을 반환한다. 빈 세션 ID와 (unknown)은 제외하며 FINAL로 재적재 중복을 제거한다. 기간 경계를 넘는 세션은 조회 기간 안의 프롬프트만 센다.
- 기본 distribution은 bucket/count 프레임과 p50/p90 프레임 두 개다. scalar/table은 분위수를, timeseries는 시간 버킷별 분위수를 반환한다. 팀·제품 그룹과 비교 기간을 지원한다.
- 비교 기간이 비어 있으면 비교 숫자는 null이다. 보호 인원은 현재·비교 그룹의 최소 인원으로 표시하여 frontend가 빈 비교 데이터를 비공개로 오인하지 않게 한다. 어느 쪽이든 5명 미만인 관측 그룹이면 히스토그램·분위수·비교 값·CSV 전체 숫자를 숨긴다.
- 실제 DB 테스트: 세션 ID 충돌, 제품 분리, 재적재 중복, 알 수 없는 세션 제외, 정확 분위수와 버킷 경계, 빈 시계열·비교 기간, 필터 후 마스킹, CSV 마스킹, 빈 관측.
- 실제 frontend의 API 클라이언트 및 widgets/model.ts 변환으로 owner/admin의 팀·제품별 분포를 검증한다. 테스트 데이터는 p50=2, p90=2, 버킷 빈도=[0,5,0,0,0]이며 빈 비교 기간은 missing 상태여야 한다. 기존 11개 지표도 함께 검증한다.

## 도구 호출·실패율·읽기 밀도 추가 검증

- tool_calls는 log/span의 type=tool_call 수다. params.success는 boolean만 허용하고 true/false가 명시된 호출만 필터링한다. 호출이 관측됐으나 필터에 일치하는 호출이 없으면 0이며, 호출 자체가 없는 그룹은 빈 프레임이다.
- tool_failure_rate는 success=false 호출 / success 판정이 있는 호출이다. null 판정은 분자·분모에서 제외한다. 분모 0이면 비율은 null이며 numerator·denominator 및 비교 값도 제공한다.
- tool_name·tool_kind·action·error_type·mcp_server는 정규화 payload에서 읽는다. 지표별 허용 차원은 기존 카탈로그를 따른다. FINAL 중복 제거와 tenant·팀·개인 조회 범위 및 5명 마스킹을 적용한다.
- read_tool_density는 도구 호출이 관측된 설치·제품·세션별로 read/search/fetch 수의 정확 p50/p90을 반환한다. 읽기 0회인 도구 호출 세션을 포함하며 도구 호출이 없는 세션은 모집단에서 제외한다. 빈 세션 ID와 (unknown)은 제외한다. 기본 distribution은 분위수 프레임 하나이며 임의의 히스토그램 경계를 추가하지 않는다.
- 실제 DB 테스트: nullable 성공 판정, 분모 0, 필터 결과 0, true/false 필터, 잘못된 파라미터 거부, 도구 차원, 세션당 읽기 0 포함 분위수, 비교 구간 마스킹.
- 실제 frontend owner/admin의 API·위젯 변환 검증 데이터: 전체 호출 15, 실패 호출 5, 실패율 5/10=0.5, 읽기 밀도 p50=p90=3. 기존 지표도 함께 검증하며 전체 ingest E2E를 대체하지 않는다.

## API 재시도·429 지표 추가 검증

- api_retry_attempts는 전체 llm_call 중 attempt>=2인 비율이며 numerator는 재시도 호출 수, denominator는 전체 호출 수다. attempt 누락 호출도 명세 Q12대로 분모에 포함하며 누락 개수와 미판정 품질 설명을 제공한다. 소규모 집단에서는 누락 개수를 숨긴다.
- rate_limit_events는 llm_call의 명시된 status_code=429 수다. 호출은 있지만 429가 없으면 0, 호출 자체가 없는 버킷은 null이다. llm_request·llm_response는 호출 수에 합치지 않는다.
- 모델 차원은 payload.model을 우선하고 point.attrs.model로 폴백한다. hour 차원은 요청 시간대로 계산한 0~23 문자열 라벨이다.
- 실제 DB 테스트: 두 제품의 모델 분리, FINAL 중복 제거, llm_request 제외, nullable attempt/status_code, 시간대별 시계열과 빈 버킷, 비교 기간의 값·분모·품질 수치 마스킹. 기존 세션 만료 테스트도 앱과 같은 Clock으로 만료 시각을 설정하도록 바꿔 DB와 앱 시계 차이 의존성을 제거했다. 인증 구현은 변경하지 않았다.
- 실제 frontend owner/admin 위젯 변환 검증 데이터: 재시도 5/15=1/3, 429 호출 5건. 기존 지표를 포함해 23개를 검증하며 전체 ingest E2E는 별도 남아 있다.

## 도구 결정 지표 추가 검증

- tool_rejections는 type=tool_decision 중 decision=reject 수다. abort·accept·null은 거절 수에 포함하지 않는다. 결정은 있지만 거절이 없는 그룹은 0, 결정 자체가 없는 그룹은 빈 프레임이다.
- auto_approval_ratio는 명세 Q11대로 decided_by가 config 또는 hook인 결정 수 / 전체 tool_decision 수다. 명칭과 별개로 config·hook이 내린 거절도 분자에 포함하므로 승인 성공률로 해석하지 않는다. 주체가 없거나 unknown인 결정도 분모에 포함한다.
- 두 제품의 log/span 결정 이벤트를 집계하고 decided_by·tool_name·team 차원을 지원한다. FINAL 중복 제거, 필터, 현재·비교 값과 비율 분자·분모의 5명 마스킹을 적용한다.
- 실제 DB 테스트: config 거절·hook 승인·user 취소·누락 값 구분, 도구 호출 제외, 두 제품 및 신호 집계, 중복 제거, 결정 주체별 값, 비교 비율과 분모, 0과 빈 관측, 작은 비교 집단 마스킹.
- 실제 frontend owner/admin의 팀·도구별 검증 데이터: 자동 결정 비율 10/20=0.5, 거절 수 5. 기존 지표를 포함해 23개를 검증하며 전체 ingest E2E는 별도 남아 있다.

## API 오류율·사용량 히트맵 추가 검증

- api_error_rate는 비어 있지 않은 error_type이 있는 llm_call 수 / 전체 llm_call 수다. 상태 코드만으로 오류 여부를 추정하지 않는다. 네트워크 오류처럼 상태 코드가 없는 오류도 분자에 포함하며 status_code 그룹의 0 라벨은 미보존 상태 코드를 뜻한다(Q12).
- 최종 재시도 소진은 현재 원천에서 판정할 수 없다. 카탈로그 availability=partial과 caveat를 유지하고 응답 품질 설명에 시도 단위 오류율임을 표시한다. 분자·분모와 비교 값에도 기존 마스킹을 적용한다.
- usage_heatmap은 user_prompt 수를 요청 시간대의 weekday(월=1~일=7)·hour(0~23)로 집계한다. 현재 경로는 명세 Q19의 프롬프트 수이며 토큰 기반 히트맵 옵션은 제공하지 않는다. 미관측 셀을 임의로 0으로 채우지 않는다.
- limit을 생략한 weekday×hour 조합은 전체 168개 그룹을 지원한다. 명시한 limit은 계약의 1~100을 그대로 검증한다. 상위 N 나머지 집계는 아직 미구현이므로 명시한 제한을 초과하면 쿼리 단위 422다.
- 실제 DB 테스트: HTTP 500과 error_type 분리, 상태 코드 없는 오류, 중복 제거, 오류율 비교 마스킹, 전체 168칸, Asia/Seoul 날짜 경계, 명시한 limit 검증, 개별 셀 4명 마스킹.
- 실제 frontend owner/admin의 API·위젯 변환 검증 데이터: 오류율 5/15=1/3, 해당 요일·시간대 프롬프트 10건. 고정된 fixture 시각으로 현지 시간 라벨도 대조한다. 기존 지표를 포함해 23개를 검증하며 전체 ingest E2E는 별도 남아 있다.

## 컨텍스트 압축 지표 추가 검증

- compactions는 lifecycle kind=compaction 이벤트 수다. 토큰 수 누락이나 attrs.success 값으로 횟수를 줄이지 않으며 trigger는 payload.attrs에서 읽는다.
- compaction_reduction은 전후 토큰 값이 모두 있는 이벤트의 합계로 계산한다. numerator=Σ(before-after), denominator=Σbefore이며 단위는 token이다. 개별 감소율을 평균내지 않는다. 토큰이 증가하면 음수 감소율을 그대로 반환한다.
- 한쪽 토큰 값이 누락된 이벤트는 감소율에서 제외하고 품질 설명에 개수를 기록한다. 유효한 쌍이 없으면 값·분자·분모 모두 null, 유효한 쌍의 before 합계가 0이면 비율만 null이다. 작은 집단은 비교 값·토큰 수·품질 개수를 함께 숨긴다.
- 실제 DB 테스트: FINAL 중복 제거, 다른 lifecycle 제외, trigger 차원, 불균등 크기 압축의 합계 비율, 한쪽 토큰 누락, 전부 미관측, 분모 0, 압축 후 토큰 증가, 비교 기간 마스킹.
- 실제 frontend owner/admin의 팀·trigger 검증 데이터: 압축 15건, 감소율 4050/5000=0.81. 토큰 쌍이 불완전한 압축 5건은 횟수에 포함하되 감소율에서 제외한다. 기존 지표를 포함해 23개를 검증하며 전체 ingest E2E는 별도 남아 있다.
