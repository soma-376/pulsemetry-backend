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
| SESSION-EVENTS | owner·감사 필수, 세션/요청/도구/설치 검색, 기간 고정 키셋 페이지, 실제 이벤트 투영 |
| INSTALL-LIST | owner·감사 필수, 현재 팀·플랫폼·상태·무활동 필터, UUID 키셋 페이지, 실제 마지막 관측·제품 버전 |
| META-METRICS | 53개 정적 지표 정의·허용/금지 차원·원천 컬럼·파라미터 스키마; OpenAPI 대조 검증 |
| META-FILTERS, META-MODELS | 기간·tenant·팀 범위를 적용한 실제 ClickHouse 관측 조회 |
| dashboard 저장소 | 실행·리포트·감사 스키마, 독립 Flyway 이력; 감사 INSERT 구현 |
| ClickHouse 조회 클라이언트 | 명명 파라미터, 읽기 설정, 30초 전체 응답 제한, 결과 크기 제한 |
| 시간 해석 | 상대식·월·DST·주 시작 및 비교 기간, 단위 테스트 |

실행·리포트 테이블이 존재한다고 해당 API가 구현된 것은 아니다. 현재 명세의 11개 operation이 구현됐고 QRY는 부분 구현이다. 53개 지표 정의 중 합계 5개와 활성 사용자·도입률·커버리지 3개 및 자동화·개발 연동·명령 프롬프트 비율 3개와 프롬프트 분포 1개 및 도구 호출 관련 3개와 API 재시도·429 지표 2개 및 도구 결정 지표 2개 및 API 오류율·히트맵 2개와 압축 지표 2개 및 MCP 연결 지표 2개 및 LLM 종료 사유 1개 및 소요 시간 2개 및 첫 토큰 지연 1개 및 승인 대기·즉시 승인 2개 및 편집 수락률 1개 및 서브에이전트 활동 1개 및 훅 차단 수 1개 및 훅 실행 1개 및 모델 거부 1개 및 모델 사용자 1개 및 토큰 비율 2개 및 토큰 사용량 1개 및 무산출 세션 1개 및 마지막 이벤트 1개 및 사용 집중도 1개 및 첫 사용 시간 1개 및 잔존율 1개 및 벤더 불일치 1개 및 비용 1개 및 서브에이전트 비용 비율 1개 및 사용자·시간당 비용 2개 및 모델 단가 1개, 비용 이상 1개 및 약정 소진율 1개를 더해 총 53개 집계가 연결되었다.

## 검증

- 기존 security/enrollment 테스트 및 전체 `./gradlew build` 통과: 현재 테스트 결과 909건, 실패·오류·skip 0건.
- META-METRICS: OpenAPI의 53개 MetricId와 차원 집합 일치, 원천 RDB 컬럼 실재, owner/admin 접근·비인증 거부 검증.
- QRY: 실제 ClickHouse에서 FINAL 중복 제거·delta 합산·비교 빈 버킷·초 미만 범위 경계·팀 필터 병합·owner 감사·여러 설치의 사람 중복 제거·값/비교값/CSV/커버리지 마스킹을 검증했다.
- 웹 로그인·권한 변경·폐기·만료·잠금·CLI 격리 및 감사 사유 테스트.
- 실제 ClickHouse에서 `FINAL` 중복 제거, 파라미터 SQL 분리, 관리자 팀 밖 모델 배제 테스트.
- 실제 frontend의 owner/admin 로그인과 P5 팀·구성원 조회를 Playwright로 검증하는 `scripts/e2e/dashboard-auth-settings.mjs`를 추가했다.
- owner/admin smoke가 실제 PostgreSQL·ClickHouse·frontend 조합에서 통과했다. 실제 frontend의 API 클라이언트로 META-METRICS를 호출해 53개 목록과 팀 분해 금지 메타데이터도 검증했다. 이는 카탈로그 UI 렌더 검증이 아니라 브라우저의 인증·CORS·JSON 계약 검증이다. 현재 frontend 클라이언트로 합계·비율 지표 11개를 함께 조회하고 프롬프트 분포와 도구·API·결정·히트맵·압축 지표 11개는 별도로 조회해 위젯 변환 코드까지 검증했다. 이번 실행에서 오류 응답은 기록되지 않았다. 이 화면에서 호출하지 않은 미구현 지표까지 검증했다는 뜻은 아니다.
- 이 smoke는 전체 시나리오·지표 수용 테스트가 아니다. 미구현 API 응답은 `build/e2e/auth-settings/result.json`에 기록한다.

## 다음 구현 순서

1. QRY 보완: 53개 지표의 기본 계산은 연결했다. 미지원 frame 형식과 상위 N의 `__other__`, W3.3 주소 테이블 등 남은 명세를 보완한다.
2. 계약 기반 파생 지표 검토: 약정 소진율도 연결했다. 현재 366일 조회 제한보다 긴 계약의 전체 기간 조회는 후속 보완 대상이다.
3. 설치·세션 API는 구현했다. 기존 frontend 설치 카드의 감사 사유 전달 및 P3 전체 UI 검증은 후속 보완 대상이다.
4. 46개 시나리오: 카탈로그·파라미터 검증·조회 시 가용성·실측 기반 findings.
5. 실행 워커와 이력: tenant별 queued+running 3개 제한, lease, 취소 및 종료 상태 경쟁 제어.
6. 저장 리포트: 접근 범위 재검증, fixed/relative, active·linked 삭제 409.
7. 실제 ingest → ClickHouse → dashboard → frontend 전체 E2E 및 operation/metric/scenario 추적표.

## 알려진 한계

- QRY의 일부 distribution 형식, 시나리오·실행·저장 API는 미구현이다. 지표 결과나 가용성을 임의로 만들어 반환하지 않는다.
- telemetry_coverage 계산은 연결되었다. 약정 소진율도 owner 전사 범위에서 연결했다. 모든 frame 형식과 전체 수용 조건을 충족한 것은 아니다.
- 전체 계획 완료나 운영 배포 가능 상태로 판정하지 않는다.

## 지표 카탈로그의 해석

- `apps/dashboard-api/src/main/resources/dashboard/metrics.json`이 런타임 정의의 한 벌이며, `DashboardMetricCatalog`가 시작 시 읽는다. 첨부 문서의 실행 지시나 부록 변경 제안은 코드 생성 시 실행하지 않았다.
- `availability`는 OpenAPI 정의대로 현 스키마에서의 산출 가능성이다. 데이터가 없는 기간, 관리자 권한, 집계 코드의 구현 여부를 이 값으로 표현하지 않는다. 현재 53개 지표는 QRY에서 관측·품질·권한을 판정한다. 미지원 frame 형식과 시나리오 실행의 판정은 계속 구현해야 한다.
- 부분 측정 지표는 서브에이전트 활동, API 오류율, 훅 실행·차단, 모델 거부 5개다. 미보존 이벤트와 tracing 전제를 caveat에 포함했다.
- `min_group_size=5`는 구현된 53개 집계의 마스킹에 적용했고, 허용되지 않은 group_by는 전체 요청을 400으로 거부한다. 모든 frame 형식과 전체 수용 조건까지 완료됐다는 뜻은 아니다.
- `params_schema`에는 현재 frontend의 `cost_anomaly.window_days`, `tokens.types`, `tool_calls.success`, `mcp_connections.server_scope`, `contract_commitment_burn.contract_id`를 포함했다. 해당 계산을 QRY에 연결할 때 별칭 정규화와 검증도 연결해야 한다. tool_calls는 boolean success 파라미터를 검증·적용한다. cost_anomaly는 moving_avg_days/window_days의 1~90 정수와 값 일치를 검증한다. 다른 지표의 추가 params는 각 구현 절에 기록한다.
- `sql_template_id`는 첨부 개요 §5의 참조 식별자이며 실행 완료나 SQL 컴파일러 구현을 뜻하지 않는다.

## QRY 현재 구현 범위와 제한

- 지원 지표: `sessions`, `active_time`, `lines_of_code`, `commits`, `pull_requests`. Claude Code 메트릭만 합산하고 누적 temporality=2는 제외한다. 원천 포인트가 없으면 성공 빈 프레임 또는 null이며 임의의 0을 만들지 않는다.
- `scalar`, `table`, `timeseries`를 지원한다. 시간·제품·모델·팀·개인 필터는 파라미터로 전달한다. 쿼리별 필터는 명시한 항목만 전역 필터에 덮어쓰며 빈 배열은 해당 필터를 해제한다. admin의 팀 권한은 해제할 수 없다.
- 비교는 현재 시간 버킷에 대응하는 비교 기간 버킷으로 연결한다. 빈 버킷은 null이다. 기간 일부라도 5명 미만인 그룹은 보수적으로 프레임 전체의 현재·비교값을 숨긴다. 숨긴 값으로 순위를 매기지 않는다.
- 활성 시간(user) 원천이 있는 그룹에서는 양수 사용자 활동이 있는 사람을 보호 인원으로 센다. 없으면 이벤트가 있는 사람으로 대체하고 `active_user_definition`을 표시한다. 설치→사람 매핑에 없는 설치는 보호 인원을 늘리지 않는다.
- 범위는 최대 366일, 시계열은 max_data_points 이하(최대 1000), 요청은 최대 12개 쿼리다. 설치 매핑은 tenant당 최대 5000개를 지원하며 초과 시 422다. 모든 ClickHouse 조회는 요청의 30초 남은 예산을 공유한다.
- 상위 N의 나머지 그룹을 `__other__`로 합산하는 단계는 아직 없다. 요일×시간 히트맵은 limit 생략 시 168개 그룹을 지원하고 나머지는 기본 100개다. 명시한 limit은 1~100이며 그룹 수가 limit을 넘으면 쿼리 단위 422로 거부하고 조용히 잘라내지 않는다.
- 미구현 지표 및 prompts_per_session·read_tool_density·turn_duration_ms·llm_duration_ms·llm_ttft_ms·gate_wait_ms 이외 지표의 distribution은 `results[ref_id].status=501`, `metric_not_implemented`를 반환한다. 이 임시 구현 상태를 카탈로그의 원천 availability와 혼동하지 않는다. CSV 요청의 첫 쿼리가 미구현이면 HTTP 501이다.
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
- 실제 frontend owner/admin 위젯 변환 검증 데이터: 재시도 5/15=1/3, 429 호출 5건. 기존 지표를 포함해 53개를 검증하며 전체 ingest E2E는 별도 남아 있다.

## 도구 결정 지표 추가 검증

- tool_rejections는 type=tool_decision 중 decision=reject 수다. abort·accept·null은 거절 수에 포함하지 않는다. 결정은 있지만 거절이 없는 그룹은 0, 결정 자체가 없는 그룹은 빈 프레임이다.
- auto_approval_ratio는 명세 Q11대로 decided_by가 config 또는 hook인 결정 수 / 전체 tool_decision 수다. 명칭과 별개로 config·hook이 내린 거절도 분자에 포함하므로 승인 성공률로 해석하지 않는다. 주체가 없거나 unknown인 결정도 분모에 포함한다.
- 두 제품의 log/span 결정 이벤트를 집계하고 decided_by·tool_name·team 차원을 지원한다. FINAL 중복 제거, 필터, 현재·비교 값과 비율 분자·분모의 5명 마스킹을 적용한다.
- 실제 DB 테스트: config 거절·hook 승인·user 취소·누락 값 구분, 도구 호출 제외, 두 제품 및 신호 집계, 중복 제거, 결정 주체별 값, 비교 비율과 분모, 0과 빈 관측, 작은 비교 집단 마스킹.
- 실제 frontend owner/admin의 팀·도구별 검증 데이터: 자동 결정 비율 10/20=0.5, 거절 수 5. 기존 지표를 포함해 53개를 검증하며 전체 ingest E2E는 별도 남아 있다.

## API 오류율·사용량 히트맵 추가 검증

- api_error_rate는 비어 있지 않은 error_type이 있는 llm_call 수 / 전체 llm_call 수다. 상태 코드만으로 오류 여부를 추정하지 않는다. 네트워크 오류처럼 상태 코드가 없는 오류도 분자에 포함하며 status_code 그룹의 0 라벨은 미보존 상태 코드를 뜻한다(Q12).
- 최종 재시도 소진은 현재 원천에서 판정할 수 없다. 카탈로그 availability=partial과 caveat를 유지하고 응답 품질 설명에 시도 단위 오류율임을 표시한다. 분자·분모와 비교 값에도 기존 마스킹을 적용한다.
- usage_heatmap은 user_prompt 수를 요청 시간대의 weekday(월=1~일=7)·hour(0~23)로 집계한다. 현재 경로는 명세 Q19의 프롬프트 수이며 토큰 기반 히트맵 옵션은 제공하지 않는다. 미관측 셀을 임의로 0으로 채우지 않는다.
- limit을 생략한 weekday×hour 조합은 전체 168개 그룹을 지원한다. 명시한 limit은 계약의 1~100을 그대로 검증한다. 상위 N 나머지 집계는 아직 미구현이므로 명시한 제한을 초과하면 쿼리 단위 422다.
- 실제 DB 테스트: HTTP 500과 error_type 분리, 상태 코드 없는 오류, 중복 제거, 오류율 비교 마스킹, 전체 168칸, Asia/Seoul 날짜 경계, 명시한 limit 검증, 개별 셀 4명 마스킹.
- 실제 frontend owner/admin의 API·위젯 변환 검증 데이터: 오류율 5/15=1/3, 해당 요일·시간대 프롬프트 10건. 고정된 fixture 시각으로 현지 시간 라벨도 대조한다. 기존 지표를 포함해 53개를 검증하며 전체 ingest E2E는 별도 남아 있다.

## 컨텍스트 압축 지표 추가 검증

- compactions는 lifecycle kind=compaction 이벤트 수다. 토큰 수 누락이나 attrs.success 값으로 횟수를 줄이지 않으며 trigger는 payload.attrs에서 읽는다.
- compaction_reduction은 전후 토큰 값이 모두 있는 이벤트의 합계로 계산한다. numerator=Σ(before-after), denominator=Σbefore이며 단위는 token이다. 개별 감소율을 평균내지 않는다. 토큰이 증가하면 음수 감소율을 그대로 반환한다.
- 한쪽 토큰 값이 누락된 이벤트는 감소율에서 제외하고 품질 설명에 개수를 기록한다. 유효한 쌍이 없으면 값·분자·분모 모두 null, 유효한 쌍의 before 합계가 0이면 비율만 null이다. 작은 집단은 비교 값·토큰 수·품질 개수를 함께 숨긴다.
- 실제 DB 테스트: FINAL 중복 제거, 다른 lifecycle 제외, trigger 차원, 불균등 크기 압축의 합계 비율, 한쪽 토큰 누락, 전부 미관측, 분모 0, 압축 후 토큰 증가, 비교 기간 마스킹.
- 실제 frontend owner/admin의 팀·trigger 검증 데이터: 압축 15건, 감소율 4050/5000=0.81. 토큰 쌍이 불완전한 압축 5건은 횟수에 포함하되 감소율에서 제외한다. 기존 지표를 포함해 53개를 검증하며 전체 ingest E2E는 별도 남아 있다.


## MCP 연결 지표 (E-4)

- MCP lifecycle 이벤트의 연결 수와 status≠connected 비율을 연결했다. disconnected·상태 누락도 명세 Q15에 따라 실패 분자에 포함한다. 활성 연결의 현재 개수를 뜻하지 않는다.
- 연결 수는 server_scope 문자열 파라미터(1~100자)를 바인딩하며 서버명·범위·전송 방식·is_plugin 차원을 지원한다. is_plugin은 정규화 문자열 True/False를 그대로 보존한다.
- 실제 ClickHouse에서 FINAL 중복 제거, 다른 lifecycle 제외, 상태별 분모·분자, 필터 바인딩·잘못된 파라미터 거부, 전부 connected인 0 비율 및 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin 클라이언트와 위젯 변환에서 연결 20건, 실패 15/20=0.75 및 서버 속성 라벨을 검증했다. 전체 테스트 909건 통과, 연동 지표 53개이며 ingest 경로 검증은 남아 있다.


## LLM 종료 사유 (S6-3)

- llm_call·llm_response의 stop_reason별 관측 이벤트 수를 연결했다. 모델과 종료 사유 차원을 지원하며 사유가 누락되면 빈 라벨로 보존한다. group_by가 없으면 전체 이벤트 수를 반환한다.
- FINAL로 동일 이벤트의 재적재를 제거하며 llm_request는 제외한다. 서로 다른 호출·응답 이벤트를 동일 호출로 추정해 병합하지 않으며 이 의미를 품질 캡션으로 표시한다.
- 실제 ClickHouse에서 사유별 집계, 다른 이벤트 제외, 중복 제거, 미관측 구간 null, 작은 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin의 API 클라이언트와 위젯 변환에서 end_turn 5건·refusal 5건·사유 누락 10건 및 모델 라벨을 확인했다. 전체 테스트 909건과 총 53개 지표 연동 검증이 통과했다. ingest 경로와 전체 수용 검증은 남아 있다.


## 턴·LLM 소요 시간 (F-3)

- turn_duration_ms는 span turn의 문자열 attrs.duration_ms로 정확 p50/p90을, llm_duration_ms는 error_type이 없는 llm_call의 숫자 duration_ms로 정확 p50/p95/p99를 계산한다.
- 누락·음수는 제외하고 0ms는 포함한다. 유효 값이 없으면 빈 프레임이며 시계열 미관측 구간은 null이다. 기본 distribution은 백분위수 프레임 하나로 반환하고 단위는 ms다.
- 실제 ClickHouse에서 중복 제거, 오류 호출 제외, 정확 백분위수, 누락·음수·0ms와 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin API·위젯 변환에서 LLM p50/p95/p99=900ms, 턴 p50/p90=1500ms를 확인했다. 전체 테스트 909건과 지표 53개 연동 검증 통과. 첫 토큰 지연은 아래 후속 작업으로 연결했고 전체 ingest E2E는 아직 남아 있다.


## 첫 토큰 지연 (F-3)

- llm_ttft_ms는 유효한 로그 llm_call의 ttft_ms를 우선하고 없으면 스팬 llm_request로 대체하여 정확 p50/p90(ms)을 반환한다. 오류·누락·음수는 제외하며 0ms는 포함한다.
- 소스 선택은 조회 기간 내 설치·제품·request_id별로 수행한다. 시간 버킷을 나누기 전에 선택하므로 같은 요청의 스팬과 로그가 다른 날짜에 있어도 중복 계상하지 않는다. 비교 기간은 독립적으로 선택한다.
- request_id가 없으면 설치·제품·세션·모델별로 유효 로그가 있는지 판단한다. 세션도 누락되면 해당 설치·제품·모델 범위가 된다. 이 대체 기준은 개별 호출을 완전히 식별하지 못하므로 응답 품질 설명에 명시한다.
- 실제 ClickHouse에서 로그 우선, 누락·음수·오류 로그의 스팬 대체, 설치·제품별 요청 ID 분리, 중복 제거, 버킷 경계 및 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin에서 로그 100ms와 별도 요청 스팬 300ms를 집계해 p50/p90=300ms를 확인한다. 같은 요청의 9999ms 스팬은 제외한다. 전체 ingest E2E는 별도로 남아 있다.


## 승인 대기·즉시 승인 (D-4, S5-7)

- gate_wait_ms는 span tool_gate의 blocked_on_user_ms로 정확 p50/p90(ms)을 제공한다. 팀·결정·결정 주체 차원을 지원하며 평균은 반환하지 않는다.
- rubber_stamp_ratio는 Q24대로 decided_by=user이고 유효 대기 시간이 있는 accept만 분모로 삼는다. 임계값 미만 승인 수를 분자로 사용한다. threshold_ms는 정수 0~3600000, 기본 2000이며 임계값과 같은 대기 시간은 분자에서 제외한다.
- 대기 시간 누락·음수는 제외하고 0ms는 포함한다. 거절만 관측된 경우 분모 0과 비율 null을 반환한다. config/hook 승인은 즉시 승인 비율에서 제외한다.
- 실제 ClickHouse에서 분위수, 임계값 경계·변경·잘못된 값 거부, 자동 승인 제외, 중복 제거, 분모 0 및 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin의 팀별 승인 대기 p50=2000ms/p90=3000ms, 기본 비율 10/20=0.5와 threshold_ms=2001의 15/20=0.75를 검증한다. 전체 ingest E2E는 별도로 남아 있다.


## 편집 수락률 (D-1)

- Claude Code의 code_edit_tool.decision 메트릭 값으로 accept/(accept+reject)를 계산한다. Q9에 명시된 user_temporary·user_permanent·user_reject·user_abort 출처만 허용하며 자동 승인과 알 수 없는 출처는 제외한다.
- abort 결정은 수락·거절 분모에 포함하지 않는다. 분모 0은 null이며 누락 값과 누적 temporality=2는 기존 메트릭 조회 규칙대로 제외한다.
- 언어는 point.attrs.language, 도구는 payload.tool_name 우선·point.attrs.tool_name 대체로 조회한다. FINAL 중복 제거와 현재 권한·팀 및 비교·CSV 마스킹을 공통 적용한다.
- 실제 ClickHouse에서 값 합계 기반 비율, 사용자 출처, 자동 승인·Codex·누적 제외, 언어·도구 차원, 분모 0과 작은 비교 집단의 품질 개수 마스킹을 검증했다.
- 실제 frontend owner/admin에서 사용자 수락 25 / 수락·거절 50 = 0.5 및 kotlin/Edit 라벨을 확인했다. 전체 테스트 909건과 총 53개 지표 연동 검증 통과. 전체 ingest E2E는 별도로 남아 있다.


## 서브에이전트 활동 (E-5)

- tool_call에서 비어 있지 않은 agent_id의 고유 수(value), 해당 ID가 있는 호출의 비율(ratio), 분자(numerator)와 전체 도구 호출 수(denominator)를 반환한다. count와 ratio 필드의 단위를 구분한다.
- Q14대로 agent_id 문자열 자체의 고유 수를 계산한다. 같은 ID가 여러 설치에서 관측돼도 한 식별자로 센다. 식별자의 전역 유일성이나 완료·성공 여부를 추정하지 않는다. 카탈로그 partial과 한계 설명을 유지한다.
- 동일 이벤트 재적재는 FINAL로 제거한다. ID 누락·빈 문자열 호출도 분모에는 포함하고 parent_agent_id를 agent_id 대신 쓰지 않는다. 도구 호출이 없으면 빈 프레임이다.
- 실제 ClickHouse에서 반복 ID·중복 이벤트, 다른 이벤트 제외, 누락·빈 ID, 비교 집단의 고유 수·비율·분자·분모 마스킹을 검증했다.
- 실제 frontend owner/admin의 팀별 위젯 변환에서 식별자 2개와 호출 비율 10/15=2/3을 확인했다. 전체 테스트 909건과 총 53개 지표 연동 검증 통과. 전체 ingest E2E는 별도로 남아 있다.


## 훅 차단 수 (G-1)

- hook_blocking은 span hook의 문자열 attrs.num_blocking을 정수로 읽어 합산하며 hook_event별 집계를 지원한다. 이벤트 수나 num_hooks를 차단 수로 대신 사용하지 않는다.
- 누락·변환 불가·음수는 제외하며 유효한 0은 보존한다. 유효 수치가 하나도 없는 집단은 빈 프레임이다. 정규화 파이프라인과 golden fixture는 변경하지 않았다.
- detailed beta tracing이 필요하고 hook_registered가 보존되지 않는 한계 때문에 카탈로그 partial을 유지한다. hook_executions의 실행 수·세션 비율은 아래 후속 작업에서 연결했다.
- 실제 ClickHouse에서 문자열 합산, 동일 이벤트 중복 제거, 로그 제외, 누락·비정상 값·0 처리 및 작은 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin에서 PreToolUse 차단 합계 25와 partial 상태를 검증한다. 전체 ingest E2E는 별도로 남아 있다.


## 훅 실행·세션 비율 (G-1)

- hook_executions는 span hook 수(value), 해당 훅이 있는 세션 비율(ratio), 해당 세션 수(numerator)와 전체 관측 세션 수(denominator)를 반환한다. 훅 수는 attrs.num_hooks의 합계가 아니라 스팬 수다.
- 세션은 설치·제품·session_id 조합으로 구분한다. 비어 있거나 (unknown)인 세션은 분자·분모에서 제외하되 해당 훅은 실행 수에 포함한다. 분모 0이면 비율 null이다.
- hook_event별로 나누더라도 같은 시간 버킷의 전체 관측 세션을 분모로 사용한다. 전체 범위·팀·제품·모델·개인 필터가 적용된 뒤 계산하며, 비교 기간과 n<5 마스킹도 공통 적용한다.
- 실제 ClickHouse에서 실행과 세션 중복의 차이, 훅 없는 세션의 분모 포함, 설치·제품 간 동일 ID 분리, 누락 세션, 분모 0과 비교 마스킹을 검증했다.
- 실제 frontend owner/admin에서 실행 10회·훅 세션 5개·전체 세션 5개·비율 1을 확인했다. 카탈로그 partial을 유지하며 전체 테스트 909건 및 총 53개 지표 연동 검증이 통과했다. 전체 ingest E2E는 별도로 남아 있다.


## 모델 거부 (G-2)

- refusals는 llm_response의 stop_reason=refusal 이벤트 수다. llm_call 및 다른 종료 사유는 제외하고 분류 누락·빈 문자열은 unspecified로 제공한다. category·model·product 차원만 허용한다.
- 기존 owner 전용 인가와 team 분해 금지를 유지한다. admin 조회는 403, owner의 team 분해 요청은 400이며 조회 실행 전에 거부한다.
- 동일 이벤트 재적재는 FINAL로 제거하지만 서로 다른 홉 이벤트를 동일 거부로 추정해 합치지 않는다. server_fallback_hop 부재의 한계와 partial 상태를 유지한다.
- 실제 ClickHouse에서 응답 유형·종료 사유 필터, 중복 제거, 분류 누락, 권한·차원 제한과 비교 집단 마스킹을 검증했다.
- 실제 frontend owner에서 policy 거부 5건을 확인했다. 공통 49개는 owner/admin 양쪽에서, 비용 이상 1개는 admin에서, 모델 거부·벤더 불일치·약정 소진율 3개는 owner에서 검증해 총 53개다. 전체 테스트 909건 통과. 전체 ingest E2E는 별도로 남아 있다.


## 모델 사용자 (E-1)

- model_users는 모델이 관측된 이벤트의 설치를 구성원에 연결해 고유 사람 수를 계산한다. 여러 설치의 동일 구성원과 여러 모델을 사용하는 구성원은 해당 집계 그룹 내 한 번만 센다. 알 수 없는 설치는 사용자 수에서 제외한다.
- 모델은 payload.model 우선·point.attrs.model 대체다. 모델이 비어 있는 관측은 제외하며 누적 메트릭 포인트는 사용자 수에 포함하지 않는다. 누적 포인트만 있으면 값 null과 제외 설명을 반환한다.
- 실제 ClickHouse에서 설치 중복, 미연결 설치, 로그·메트릭 모델, 누적 제외, 모델 필터 및 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin에서 claude-e2e의 사용자 5명을 확인했다. 공통 49개와 admin 비용 이상 1개 및 owner 전용 3개로 총 53개 지표 연동 검증 및 전체 테스트 909건이 통과했다. 이번 frontend 검증 HEAD는 52f7cb10017c6ba6120f51f2e158ff329d14bff0이다. 전체 ingest E2E는 별도로 남아 있다.


## 캐시·입출력 토큰 비율 (C-2, S1-5)

- cache_read_ratio와 input_output_ratio는 llm_call 이벤트의 토큰 합계 비율을 제공한다. 각각 cache_read/(input+cache_read+cache_create), input/output이며 평균 비율이 아니다.
- 각 비율에 필요한 값이 모두 존재하고 음수가 아닌 호출만 합산한다. 누락 값을 0으로 추정하지 않기 위한 처리이며 응답 품질 설명에 명시한다. 완전한 호출이 없으면 비율·분자·분모가 null이고, 유효한 0 분모는 숫자 0과 비율 null이다.
- 값의 단위는 ratio, 분자·분모는 token이다. 팀·모델 차원과 현재 권한·필터·비교 마스킹을 적용한다. 이벤트 원천만 연결했으며 두 비율의 메트릭 원천 확장은 후속 작업이다. tokens의 메트릭 집계는 아래 후속 작업에서 연결했다. source 지정은 현 계약대로 cost/tokens에만 허용한다.
- 실제 ClickHouse에서 합계 비율, 누락·음수 제외, 중복 제거, 분모 0과 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin에서 캐시 3000/6000=0.5, 입출력 1500/750=2를 확인했다. 공통 49개와 admin 비용 이상 1개 및 owner 전용 3개로 총 53개 지표 및 전체 테스트 909건 통과. 전체 ingest E2E는 별도로 남아 있다.


## 토큰 사용량 (C-2)

- tokens는 기본 events(llm_call) 또는 metrics(Claude Code token.usage) 원천 하나를 선택한다. 원천을 합치거나 서로 대신 사용하지 않는다.
- types 파라미터는 input/output/cache_read/cache_create의 중복 없는 배열이다. 생략·빈 배열은 네 종류 전체다. 메트릭 cacheRead/cacheCreation은 API의 cache_read/cache_create로 변환한다. reasoning·total_reported 등을 추가 합산하지 않는다.
- 종류·팀·제품·모델·query_source 차원을 지원한다. agent_name은 메트릭에서만 허용하고 이벤트 요청이면 400이다. 관측된 비음수 값만 합산하며 전부 누락이면 null, 유효한 0은 숫자 0이다. 누적 메트릭은 제외 설명과 함께 합산에서 제외한다.
- 실제 ClickHouse에서 두 원천 분리, 종류 선택·정규화, 잘못된 파라미터 거부, 귀속 차원, 누락·0 및 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin에서 이벤트 토큰 input=1500/output=750/cache_read=3000/cache_create=1500, 메트릭의 output+cache_read=250 및 worker 귀속을 확인했다. 공통 49개와 admin 비용 이상 1개 및 owner 전용 3개로 총 53개 지표 및 전체 테스트 909건 통과. 전체 ingest E2E는 별도로 남아 있다.


## 무산출 세션 비율 (S4-2)

- abandoned_session_ratio는 조회 기간 내 로그가 있는 유효 세션을 분모로 사용한다. 설치·제품·session_id별로 편집 accept·LoC·commit·PR 메트릭 값을 합산해 양수가 아니면 무산출로 센다.
- 누적 포인트는 산출 합계에서 제외한다. 빈 세션 및 (unknown)은 제외하고 메트릭만 있는 세션은 분모에 넣지 않는다. Codex와 Claude Code의 같은 세션 ID도 분리한다.
- 시계열은 기간 내 마지막 로그 시각의 버킷에 세션을 한 번 배치하며 산출은 같은 조회 기간 전체에서 연결한다. 실제 종료를 보장하지 않고 기간 밖 또는 미관측 산출을 판정하지 못한다는 한계를 품질 설명으로 반환한다.
- 실제 ClickHouse에서 네 산출 유형, 중복 제거, 누적·0, 제품 구분, 여러 날짜의 동일 세션과 비교 마스킹을 검증했다.
- 실제 frontend owner/admin에서 산출 메트릭을 연결한 5개 세션의 무산출 0/5=0을 검증한다. 전체 ingest E2E는 별도로 남아 있다.


## 세션 마지막 이벤트 (S4-2)

- session_last_event는 Q25에 따라 조회 기간 내 마지막 로그의 유형을 last_event 라벨로 반환한다. 마지막 행에 error_type이 있으면 api_error로 분류하고, 그 외에는 llm_call을 포함한 원래 유형을 유지한다.
- 설치·제품·세션별로 시각, sequence(누락은 0), event_id 순서로 한 행을 선택한다. 유형과 오류는 같은 행에서 읽는다. event_id는 동률의 안정적인 선택 기준이며 실제 발생 순서를 보장하지 않는다.
- 빈 세션·(unknown) 및 span은 제외한다. 시계열은 마지막 로그 날짜에 한 번 배치한다. 팀 집계와 비교 기간을 지원하며 유형별 관측 구성원도 5명 미만이면 숫자를 숨긴다.
- 설명 문구의 긴 대기는 임계값이 없어 추정하지 않는다. 실제 종료 판정도 하지 않으며 두 한계를 응답 품질 설명에 명시한다. 이 구현은 Q25 로그 분류 범위다.
- 실제 ClickHouse에서 순번·날짜 우선순위, 오류와 유형의 동일 행 선택, 제품 분리, 중복 제거, 무효 세션 제외 및 비교 마스킹을 검증했다. frontend owner/admin에서는 api_error로 끝난 팀 세션 5개를 확인한다.


## 사용 집중도 (S3-2)

- usage_concentration은 Q26 llm_call 로그·스팬의 input/output/cache_read/cache_create 합계를 사람별로 합친다. 네 값이 모두 존재하고 비음수인 호출만 계산한다. 메트릭 원천과는 합치지 않으며 누락·음수 제외를 품질 설명에 명시한다.
- 여러 설치는 현재 구성원 매핑으로 합치고 미매핑 설치는 별도 익명 단위로 센다. 상위 인원은 max(1,ceil(인원×0.1))이며 상위 토큰 합/전체 토큰 합을 반환한다. 유효한 0 분모는 숫자 0과 비율 null을 유지한다.
- scalar·timeseries는 value(상위 10% 점유율), numerator, denominator를 반환한다. 기본 table 및 distribution은 이 요약과 별도 익명 곡선 프레임을 반환한다. 곡선은 원점부터 population_share, lorenz_cumulative(token), usage_share(ratio)를 제공한다.
- 비교 곡선은 각 기간의 독립적인 population_share_compare 좌표를 제공하고 길이가 다르면 null로 맞춘다. 어느 기간이든 소집단이면 요약·좌표·누적 토큰·CSV를 숨기고 곡선은 고정 1행으로 반환해 인원 수를 노출하지 않는다. 식별자는 SQL 집계 결과와 응답에 넣지 않는다.
- 실제 ClickHouse에서 설치 병합, 미매핑 단위, 중복 제거, 상위 인원 올림, 정확 누적 합계, 누락·음수·0·빈 결과 및 비교 마스킹을 검증했다. 실제 frontend owner/admin에서는 동일 사용량 5명의 점유율 0.2, 총 6750토큰과 6개 곡선 좌표를 검증한다. 전체 ingest E2E는 별도 남아 있다.


## 첫 사용까지 소요 시간 (S3-3)

- onboarding_ttfu는 RDB의 설치 생성 시각과 보존된 최초 이벤트를 설치별로 연결한다. 조회 하한 이전의 이력도 확인하고 최초 관측이 조회 기간에 속한 설치만 집계한다. 동일 설치의 재사용 이벤트를 새 첫 사용으로 세지 않는다.
- 이벤트와 생성 시각을 모두 정수 초로 맞춘다. 생성 시각의 초 미만 값은 절삭한다. 미매핑 설치와 생성 이전 이벤트는 제외하며, 여러 설치의 표본은 유지하되 소집단 판정은 구성원을 중복 제거한다.
- 팀·플랫폼 그룹을 지원한다. 첫 관측은 요청 필터·권한·팀 그룹 범위에 따른다. 과거 데이터 유실이나 필터 밖 사용이 있으면 실제 첫 사용과 다를 수 있음을 품질 설명에 표시한다. 과거 조회도 기존 요청의 30초 예산과 읽기 제한을 공유한다.
- scalar·table·timeseries는 p50/p90(초)을 반환한다. 기본 distribution은 분위수와 5개 구간(<1h, 1h–1d, 1d–7d, 7d–30d, 30d+)의 설치 수를 반환한다. 시계열은 최초 이벤트 날짜에 배치하고 비교 소집단은 분위수·분포·CSV 모두 마스킹한다.
- 실제 PostgreSQL·ClickHouse에서 초 미만 생성 시각, 정확 경계와 분위수, 중복 제거, 기간 이전 이력, 생성 이전·미매핑 제외 및 비교 마스킹을 검증했다. 실제 frontend owner/admin에서는 linux 팀 설치 5개의 p50/p90=3600초와 1h–1d 구간 5개를 확인한다. 잔존율은 아래 후속 작업에서 연결했으며 전체 ingest E2E는 별도로 남아 있다.


## 신규 사용자 잔존율 (S3-3)

- onboarding_retention은 Q27대로 첫 사용 주 코호트의 설치 수를 분모로 사용한다. 한 설치의 같은 주 중복 이벤트를 제거하며, 여러 설치를 가진 구성원도 분모에서는 설치별로 세되 소집단 판정은 구성원 중복을 제거한다.
- 요청 시간대의 월요일을 주 시작으로 사용한다. 조회 하한 이전 보존 이력으로 과거 사용자를 제외하고, 필터·권한·팀 그룹 범위에서 첫 사용이 조회 기간에 들어오는 코호트만 반환한다. 보존 이력이 실제 전체 이력이라는 보장은 없다.
- 기본 table과 scalar를 지원한다. 각 프레임은 cohort_week, 상대 cohort_index, week_index 라벨과 value·numerator·denominator를 가진다. timeseries와 distribution은 아직 501이다. 프레임 수는 기존 그룹 상한을 적용한다.
- 완료된 빈 주는 잔존율·분자가 0이다. 조회 상한 또는 현재 시각까지 끝나지 않은 주와 미래 주는 비율·분자를 null로 반환하고 코호트 크기는 유지한다. 미래 시각의 이벤트는 관측에 사용하지 않는다.
- 비교는 조회 시작 주에 대한 상대 코호트 주와 경과 주차로 정렬하며, 실제 날짜는 cohort_week_compare로 별도 제공한다. 작은 현재/비교 코호트는 해당 프레임의 모든 숫자를 숨긴다.
- 실제 ClickHouse에서 설치 6개·구성원 5명의 100%→50%→0% 잔존, 중복 제거, 기간 이전 이력 제외, 진행·미래 주 및 비교 코호트 마스킹을 검증했다. 실제 frontend owner/admin에서는 코호트 설치 5개와 조회 상한에 따른 미완료 주차 표시를 확인한다. 전체 ingest E2E는 별도로 남아 있다.


## 벤더 계정 불일치 (H-3)

- vendor_account_mismatch는 현재 등록 이메일과 조회 기간의 마지막 비어 있지 않은 벤더 이메일이 다른 설치 수를 반환한다. Q23의 로그·스팬 원천을 사용하고, 대소문자는 구분하지 않는다. 시각 동률은 sequence·event_id로 안정화한다.
- 기본 scalar와 table은 기간 전체의 설치별 마지막 주소를 사용하며, timeseries는 각 버킷에서 판정한다. 이메일 없는 설치·미매핑 설치·metric 원천은 비교에서 제외한다. 관측된 비교 대상이 없으면 빈 프레임이다.
- owner와 유효한 X-Audit-Reason이 필요하고 ClickHouse 조회 전에 감사 로그를 저장한다. admin과 감사 사유 없는 요청은 403이다. 주소·도메인·설치 식별자를 응답에 넣지 않으며, 카탈로그의 설치 수 집계만 구현한다. 주소 목록을 보여 주는 W3.3 상세 표는 별도 미구현이다.
- 이메일은 바인딩된 Map 파라미터로 전달하고 따옴표 등을 이스케이프한다. 현재·비교 기간의 작은 구성원 집단은 값과 CSV를 마스킹한다.
- 실제 PostgreSQL·ClickHouse에서 마지막 주소 선택, 대소문자·따옴표 주소, 중복 제거, 원천·미매핑 제외, 주소 비노출, 감사·소유자 권한 및 비교 마스킹을 검증했다. 실제 frontend owner에서 불일치 설치 5개와 query 감사 로그 1개를 확인한다. 공통 49개와 admin 비용 이상 1개 및 owner 전용 3개로 총 53개 지표를 검증하며 전체 ingest E2E는 별도 남아 있다.


## 원천별 비용과 계약 가격 (C-1)

- cost는 기본 events의 llm_call 로그·스팬 cost_usd 또는 metrics의 claude_code.cost.usage 하나를 합산한다. 원천을 합치거나 누락을 새 단가로 추정하지 않는다. 누락·음수·누적 메트릭은 제외하며 유효한 0은 유지한다.
- 팀·제품·모델·query_source 및 원천별 귀속 차원을 지원한다. agent_name/skill_name/plugin_name/speed는 metrics에서만 허용한다. scalar/table/timeseries를 지원하고 distribution은 아직 미구현이다.
- price_basis는 쿼리별 값이 요청 공통 값보다 우선한다. contract는 구성원 배정, 계약·할인 날짜, 종료 시각, 벤더(Claude Code→anthropic, Codex→openai)와 모델 부분 문자열이 모두 맞는 token_type=all 배율만 적용한다. 일치 없으면 1배다.
- 날짜는 테넌트 시간대이며 ends_at/effective_to 당일을 포함한다. assigned_at은 포함하고 released_at/terminated_at은 제외한다. draft는 제외하며 만료·종료 계약의 과거 유효 구간도 조회한다. 현재 저장된 계약 메타데이터 기준의 비교 금액이며 실제 청구액이 아니다.
- 같은 관측에 할인 행이 여러 개 맞으면 422 contract_overlap, 음수·비유한 배율이면 422 invalid_contract_rate다. 토큰 종류별 할인은 적용하지 않는다. 계약 조회는 5000행 상한을 적용하고 이후 ClickHouse 조회는 남은 요청 예산을 사용한다. JDBC 조회 자체의 강제 중단은 아직 지원하지 않는다.
- 비용 원천·가격 기준과 reported/estimated 개수를 품질 설명에 반환한다. 작은 현재·비교 집단은 금액과 개수 설명을 마스킹한다.
- 실제 PostgreSQL·ClickHouse에서 원천 분리, 누락·음수·0·누적, 배정 종료 경계·계약 기간, 쿼리 가격 우선순위, 종류별 할인 제외, 중복 계약과 비교 마스킹을 검증했다. 실제 frontend owner/admin에서는 이벤트 30/15 USD, 메트릭 5/2.5 USD(공시/계약) 및 worker 귀속을 검증한다. 나머지 비용 파생 지표와 전체 ingest E2E는 별도로 남아 있다.


## 서브에이전트 비용 비율 (C-3)

- subagent_cost_ratio는 Q7의 메트릭 원천 claude_code.cost.usage에서 query_source=subagent인 비용을 전체 비용으로 나눈다. 귀속 누락·다른 query_source도 분모에 포함하고 이벤트 비용은 합치지 않는다. source 파라미터는 기존 계약대로 cost/tokens에만 허용한다.
- 누적·음수·누락 비용을 제외한다. 유효한 비용이 있지만 서브에이전트 비용이 없으면 분자와 비율은 0이며, 전체 비용이 0이면 비율만 null이다. 분자·분모 단위는 USD, 비율은 ratio다.
- 팀 그룹과 scalar/table/timeseries를 지원한다. 계약 가격은 기존 비용 경로를 재사용해 각 모델·구성원·기간에 적용한 뒤 분자·분모를 합산한다. 현재·비교 소집단은 비율·분자·분모를 함께 숨긴다.
- 실제 ClickHouse에서 이벤트 배제, 귀속 누락 포함, 누적·음수 제외, 중복 제거, 모델별 할인으로 10/40에서 5/35로 바뀌는 비율, 0 분모와 비교 마스킹을 검증했다. 실제 frontend owner/admin에서는 공시 2/5=0.4와 계약 1/2.5=0.4를 확인한다. 전체 ingest E2E는 별도로 남아 있다.


## 활성 사용자당·사용자 활동 시간당 비용 (C-4)

- cost_per_active_user는 이벤트 비용을 기존 활성 구성원 수로 나눈다. 여러 설치의 구성원을 중복 제거하고, 사용자 활동 시간 원천이 없으면 관측된 구성원 수를 사용한다. 적용한 정의는 active_user_definition에 표시한다.
- cost_per_user_hour는 이벤트 비용을 사용자 활동 시간의 합/3600으로 나눈다. cli·누적·음수 시간은 분모에서 제외한다. 시간 미관측은 분모와 값이 null이며 비용은 유지한다. 유효한 0 분모는 비율을 null로 하고, 소집단이면 분모까지 마스킹한다.
- 두 지표는 팀 그룹과 scalar/table/timeseries, 비교를 지원한다. 분자 단위는 USD이며 분모는 각각 count와 h다. source 선택은 기존 API 계약대로 cost/tokens에만 허용하고 이 지표들은 이벤트 비용을 사용한다.
- 계약 가격은 비용 분자에 적용하며 활동 사용자·시간 분모에는 적용하지 않는다. 현재·비교 소집단은 비율·분자·분모를 함께 숨긴다.
- 실제 ClickHouse에서 구성원 5명·설치 6개의 비용 60과 활동 6시간을 사용자당 12, 시간당 10으로 계산하고 계약 적용 후 6과 5가 되는 것을 검증했다. 활동 원천 폴백·시간 미관측·누적 제외·중복 제거와 비교 마스킹도 확인했다.
- 실제 frontend owner/admin에서는 비용 30/15, 활성 사용자 5명, 사용자 활동 1/6시간으로 사용자당 6/3 및 시간당 180/90(공시/계약)을 검증한다. 약정 소진율도 아래 후속 작업으로 연결했으며 전체 ingest E2E는 별도로 남아 있다.


## 모델별 토큰 단가 (S8-2)

- model_unit_price는 이벤트 원천의 관측 비용/토큰 합계 비율이다. 호출별 단가를 평균하지 않으며 1토큰당 USD를 반환한다. 공개 가격표나 백만 토큰당 가격이 아니다.
- 비용과 input/output/cache_read/cache_create가 모두 존재하고 비음수인 호출만 분자·분모에 함께 포함한다. reasoning·total_reported는 더하지 않고 메트릭 원천과 합치지 않는다. 응답 품질 설명에 완전한 호출 기준을 표시한다.
- 모델 그룹과 scalar/table/timeseries를 지원한다. 분자는 USD, 분모는 token이다. 모든 호출이 불완전하면 전부 null이며, 유효한 토큰 합이 0이면 단가만 null이다. 계약 가격은 비용 분자에 적용하고 비교 소집단은 세 값을 모두 숨긴다.
- 실제 ClickHouse에서 비용 50/토큰 7250의 합계 비율, 계약 비용 25/7250, 네 토큰 종류, 누락·음수·메트릭 제외, 중복 제거, 0 분모와 비교 마스킹을 검증했다. 실제 frontend owner/admin에서는 claude-e2e의 공시 30/6750 및 계약 15/6750 USD/토큰을 확인한다. 전체 ingest E2E는 별도로 남아 있다.


## 비용 이상 징후 (S1-3)

- cost_anomaly는 조회 시간대의 일별 비용을 직전 N개 달력일 평균으로 나눈 뒤 1을 뺀다. 기본 N은 7이며 moving_avg_days와 frontend 별칭 window_days는 1~90 정수다. 두 값이 다르거나 다른 파라미터를 전달하면 400이다.
- 조회 시작일의 자정부터 비용을 집계하며 마지막 날은 조회 종료 시각까지 관측한다. 기준 기간은 조회 시작보다 N일 앞서 읽는다. 누락일을 0원으로 추정하지 않으므로 N일 중 비용 미관측일이 있거나 평균이 0이면 비율은 null이다. 분자는 해당일 USD, 분모는 기준 평균 USD다.
- scalar/table은 마지막 관측일을, timeseries는 1d 버킷을 반환한다. 다른 interval은 400, distribution은 501이다. 이벤트 비용을 사용하고 agent_name 그룹이 있으면 메트릭 비용을 사용한다. 두 원천을 합치지 않으며 계약 배율·누락·음수·누적 포인트 처리는 기존 비용 경로를 재사용한다.
- 현재일과 모든 기준일 및 비교 기간의 소집단 조건을 전파해 값·분자·분모를 함께 마스킹한다. 기준 기간의 작은 집단도 비용 비율로 노출하지 않는다.
- 실제 ClickHouse 테스트에서 150/75−1=1, 현재일만 할인한 75/75−1=0, 별칭·범위 오류, 누락일·0 평균, 비교 기준 소집단, Asia/Seoul 자정 경계 및 에이전트 비누적 원천을 검증했다. 실제 frontend admin 클라이언트와 위젯 변환에서는 30/15−1=1을 검증한다.
- 전체 빌드 테스트 909건과 frontend 연동 지표 53개가 통과했다. 정규화 fixture를 직접 적재한 검증이며 실제 ingest와 전체 시나리오 E2E는 남아 있다. 약정 소진율은 아래 후속 작업으로 연결했다.


## 계약 약정 소진율 (H-2)

- contract_commitment_burn는 frontend P5에 맞춰 owner 전사 범위만 허용한다. 팀·구성원·제품·모델 필터는 403이다. contract_id는 선택 UUID이며 생략하면 tenant 내 비초안 term_commitment 계약을 개별 프레임으로 반환한다. 계약 ID는 라벨이며 비용 관측이 없으면 빈 프레임이다.
- 조회 기간과 계약 시작·종료·해지 및 구성원 배정·해제 기간의 교집합에서 제품 벤더가 일치하는 이벤트 비용만 사용한다. 날짜 경계는 tenant 시간대이며 종료일 다음 자정·해지·해제 시각은 제외한다. 멤버십의 중복 구간은 이벤트를 복제하지 않는다.
- 항상 계약 비용으로 계산한다. 기존 token_discount 계약의 유효 all 배율을 적용하고, USD 약정액 전체를 분모로 삼는다. source는 이벤트만 사용한다. 누락·0 약정액은 비율 null이며, 음수 약정액과 USD 외 통화는 각각 422로 거부한다. 임의 환산·청구액 추정은 하지 않는다.
- frontend 호환 필드는 burn_ratio(ratio), cost_usd(USD), commitment_amount(USD)다. scalar/table은 조회 기간 합계, timeseries는 버킷별 비용/전체 약정액이다. 누적 소진율은 계약 시작부터 scalar로 조회한다. 현재·비교 소집단이면 약정액도 숨긴다.
- 실제 frontend의 계약 starts_at 날짜 입력을 위해 YYYY-MM-DD를 요청 시간대 자정으로 해석한다. DST 전환 전후 자정과 잘못된 날짜 거부를 단위 테스트로 검증했다. 기존 366일 조회 제한은 유지하므로 더 긴 계약 전체 기간은 후속 보완이 필요하다.
- 실제 PostgreSQL·ClickHouse에서 배정·해제·해지 경계, 벤더·원천 제외, 할인 비용 25/1000, FINAL 중복 제거, 누락·0·음수 약정액, 비USD 통화, owner 인가, tenant 격리, 비교·CSV 마스킹을 검증했다.
- 전체 빌드 909건, 실제 frontend 연동 지표 53개와 P5 약정 게이지 1.5%(15/1000)를 검증했다. 53개 지표의 기본 계산 경로가 연결됐지만, 46개 시나리오·실행·저장 API 및 ingest부터의 전체 E2E는 미완료다.


## 설치 목록 (INSTALL-LIST)

- GET /v1/installations는 ADR 0019에 따라 owner와 10~500자 감사 사유를 요구한다. 감사 INSERT가 실패하면 503 + Retry-After로 종료하고 설치 정보를 반환하지 않는다. admin 및 감사 사유 없는 요청은 403이다.
- 현재 팀 소속, platform(windows/macos/linux), status(active/revoked), inactive_days(1 이상)를 지원한다. 설치 UUID 오름차순 키셋으로 limit 1~500(기본 50)을 적용하고 빈 cursor는 첫 페이지로 해석한다. total은 계산하지 않아 null이다.
- PostgreSQL의 설치·구성원·현재 팀 정보를 읽고, 같은 tenant·설치의 ClickHouse FINAL 관측을 결합한다. 미래 이벤트는 제외한다. last_seen_at은 RDB 값, last_event_at은 제품 전체 마지막 이벤트다. product_versions는 제품별 마지막 이벤트의 envelope.client.version이며 마지막 이벤트에 버전이 없으면 해당 키를 생략한다. 같은 시각은 event_id로 결정한다.
- 무활동은 마지막 이벤트가 기준일 이하인 설치를 선택한다. 이벤트 미관측 설치는 생성일이 기준일 이하인 경우만 포함한다. 최근 생성된 설치를 즉시 장기 무활동으로 분류하지 않으며, last_seen_at으로 이벤트 관측을 대체하지 않는다. 이메일은 감사 후에도 ***@도메인 형식으로만 반환한다.
- 무활동 필터를 페이지 절단 전에 적용한다. 요청당 RDB 후보는 5,000개로 제한하고 초과 시 422 query_too_wide를 반환한다. 30초 조회 예산과 기존 ClickHouse 응답 크기 제한을 사용한다. 페이지 사이의 동시 변경을 고정하는 스냅샷은 제공하지 않는다.
- 실제 DB에서 제품 버전·마지막 시각·FINAL 중복 제거·미래 이벤트 제외·미관측·키셋 페이지·무활동·현재 팀·tenant 격리·감사 저장 실패 차단을 검증했다. 전체 테스트 909건이 통과했다.
- 실제 frontend 클라이언트로 감사 사유를 전달해 설치 5개를 2/2/1의 3페이지로 조회하고 중복 없는 ID, 제품 버전, 마지막 이벤트와 감사 로그 3건을 확인했다. 기존 53개 지표와 P5 게이지 연동도 통과했다.
- 기존 frontend opsApi.installations/설치 카드는 감사 사유를 전달하지 않아 403이 된다. frontend를 수정하지 않는 확정 범위와 ADR의 감사 요구를 유지했으며, 설치 카드 전체 UI 검증으로 표시하지 않는다. SESSION-EVENTS는 아래 후속 작업으로 연결했다.


## 세션 상관 조회 (SESSION-EVENTS)

- GET /v1/sessions/{session_id}/events는 ADR 0019에 따라 owner와 감사 사유를 매 페이지 재검증한다. 관리자 허용이라는 첨부 초안보다 확정 ADR을 우선한다. 감사 실패는 503 + Retry-After이며 이벤트를 반환하지 않는다.
- lookup=session_id(기본)/request_id/call_id/installation_id를 지원한다. 요청·도구·설치 키가 일치하는 이벤트에서 세션을 찾고, 선택 기간 내 해당 설치·제품·세션의 전체 이벤트로 확장한다. 여러 세션이 일치하면 422 ambiguous_session, 없으면 404다. 빈/unknown 세션을 하나의 세션으로 합치지 않는다.
- 기본 기간은 now-7d~now, 최대 366일이며 limit은 1~500(기본 50)이다. ts, sequence(누락은 정렬에서 0), event_id 순서의 키셋 페이지를 반환한다. 커서는 검색 키·tenant·기간 식과 연결하고 상대 기간의 해석 결과를 유지한다. 다른 검색의 커서는 400이다. 저장 데이터의 동시 변경까지 고정하는 스냅샷은 아니다.
- ClickHouse FINAL을 사용한다. 세션 메타는 선택 기간 내 전체 세션의 최소/최대 시각·이벤트 수 및 첫 이벤트의 팀·클라이언트 버전이며 페이지가 바뀌어도 같은 조회 범위를 집계한다. ended_at은 마지막 관측이며 실제 종료를 보장하지 않는다.
- 이벤트에는 스키마의 ID·시각·신호·유형·상관 ID·추론 여부·순번과 정규화 payload 또는 point만 투영한다. envelope와 enrichment_json 전체를 내보내지 않는다. 메트릭 type은 point.name이며 payload에는 point를 사용한다. 정상 파이프라인의 원문 미보존 전제는 ADR 0017을 따른다.
- 실제 ClickHouse에서 신호 결합·FINAL 중복 제거·키셋 순서·상대 기간 고정·세 검색 키 확장·모호한 세션 오류·tenant/기간 격리·감사 실패·owner 인가를 검증했다. 전체 빌드 테스트 909건이 통과했다.
- 실제 frontend opsApi.events로 페이지 전체를 읽고 원본 fixture의 event_id 순서·세션 이벤트 수와 대조했다. eventDetails의 토큰·비용 문자열 변환과 페이지별 감사 로그도 검증했다. 기존 53개 지표·설치 페이지·P5 게이지 smoke도 통과했다. SessionSearch 화면 전체 및 ingest부터의 전체 E2E로 표시하지 않는다.
- 다음 구현 대상은 46개 시나리오 카탈로그·상세·실행 입력 검증이다. 실행 워커·이력·저장 리포트와 미지원 조회 형식은 계속 남아 있다.
