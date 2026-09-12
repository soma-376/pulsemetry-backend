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
| SCN-LIST, SCN-GET | 46개 정적 정의·8개 카테고리·교집합 필터·파라미터 폼 스키마·실제 지표 메타 연결 |
| SESSION-EVENTS | owner·감사 필수, 세션/요청/도구/설치 검색, 기간 고정 키셋 페이지, 실제 이벤트 투영 |
| INSTALL-LIST | owner·감사 필수, 현재 팀·플랫폼·상태·무활동 필터, UUID 키셋 페이지, 실제 마지막 관측·제품 버전 |
| META-METRICS | 53개 정적 지표 정의·허용/금지 차원·원천 컬럼·파라미터 스키마; OpenAPI 대조 검증 |
| META-FILTERS, META-MODELS | 기간·tenant·팀 범위를 적용한 실제 ClickHouse 관측 조회 |
| SCN-RUN, RUN-GET, RUN-CANCEL | S1-1·S1-2·S1-3·S1-4·S1-5·S1-6·S1-7·S2-1·S2-2·S2-3·S3-1·S3-2·S3-3·S3-4·S3-5·S4-1·S4-2·S4-3·S4-4·S4-5·S4-6·S4-8·S5-2·S5-4·S5-5·S5-6·S5-7·S6-1·S6-3·S6-4·S6-5·S7-1·S7-2·S7-3·S7-4·S8-1·S8-2·S8-3·S8-4·S8-5·S8-6·S8-7 비동기 실행·실측 결과·현재 권한 재검증·취소; 다른 시나리오 실행 계획은 미지원 |
| RUN-LIST | 최신순 요약·필터·키셋 페이지, admin 본인 실행과 현재 팀 범위 적용 |
| RUN-SAVE, SAVED-LIST, SAVED-DELETE, RUN-DELETE | 완료 실행 저장·범위별 목록·생성자/owner 삭제, active·linked 실행 삭제 409 |
| dashboard 저장소 | 실행·리포트·감사 스키마, tenant별 admission 잠금·claim token·lease·종료 상태 조건부 갱신 |
| ClickHouse 조회 클라이언트 | 명명 파라미터, 읽기 설정, 30초 전체 응답 제한, 결과 크기 제한 |
| 시간 해석 | 상대식·월·DST·주 시작 및 비교 기간, 단위 테스트 |

실행·리포트 테이블이 존재한다고 해당 API가 구현된 것은 아니다. 현재 명세의 20개 operation이 구현됐고 QRY와 SCN-RUN은 부분 구현이다. 53개 지표 정의 중 합계 5개와 활성 사용자·도입률·커버리지 3개 및 자동화·개발 연동·명령 프롬프트 비율 3개와 프롬프트 분포 1개 및 도구 호출 관련 3개와 API 재시도·429 지표 2개 및 도구 결정 지표 2개 및 API 오류율·히트맵 2개와 압축 지표 2개 및 MCP 연결 지표 2개 및 LLM 종료 사유 1개 및 소요 시간 2개 및 첫 토큰 지연 1개 및 승인 대기·즉시 승인 2개 및 편집 수락률 1개 및 서브에이전트 활동 1개 및 훅 차단 수 1개 및 훅 실행 1개 및 모델 거부 1개 및 모델 사용자 1개 및 토큰 비율 2개 및 토큰 사용량 1개 및 무산출 세션 1개 및 마지막 이벤트 1개 및 사용 집중도 1개 및 첫 사용 시간 1개 및 잔존율 1개 및 벤더 불일치 1개 및 비용 1개 및 서브에이전트 비용 비율 1개 및 사용자·시간당 비용 2개 및 모델 단가 1개, 비용 이상 1개 및 약정 소진율 1개를 더해 총 53개 집계가 연결되었다.

## 검증

- 최신 dashboard 검증: 비용 이상 상위 N 보완 후 256건 통과(실패·오류·skip 0건), dashboard JAR 생성 완료. 상위 N 기타 재집계는 49개 지표에 연결되었다. 일반 그룹 상위 N 미연결은 2개이며 group_by 금지 지표 2개는 별도다. 전체 빌드의 최신 기록은 아래 `708d5d7` 기준 1,043건이며 이번 변경의 전체 빌드를 의미하지 않는다.

- 2026-09-12 `./gradlew build --rerun-tasks` 통과: 전체 1,008건(dashboard 201건 포함), 실패·오류·skip 0건. 58개 task를 캐시 재사용 없이 실행했다.
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
4. 46개 시나리오: 42개 실행 경로가 연결되었다. 명세상 unavailable 4개는 409 거부를 유지한다. 연결된 실행도 관측 한계를 유지하며 시나리오 제목이 암시하는 인과 분석 전체를 구현한 것은 아니다.
5. 실행 워커·조회·취소·목록·삭제는 연결했다. 추가 운영·복구 검증을 보완한다.
6. 저장 리포트: 저장·목록·삭제, 접근 범위 재검증, fixed/relative와 active·linked 삭제 409를 연결했다.
7. 실제 ingest → ClickHouse → dashboard → frontend 전체 E2E 및 operation/metric/scenario 추적표.

## 알려진 한계

- QRY의 일부 frame 형식·상위 N 처리가 남아 있다. available/partial 시나리오 42개의 실행·조회·취소·목록·저장·삭제 경로를 연결했으며, 명세상 unavailable 4개는 409로 거부한다.
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
- 실제 frontend owner/admin 클라이언트와 위젯 변환에서 연결 20건, 실패 15/20=0.75 및 서버 속성 라벨을 검증했다. 전체 테스트 912건 통과, 연동 지표 53개이며 ingest 경로 검증은 남아 있다.


## LLM 종료 사유 (S6-3)

- llm_call·llm_response의 stop_reason별 관측 이벤트 수를 연결했다. 모델과 종료 사유 차원을 지원하며 사유가 누락되면 빈 라벨로 보존한다. group_by가 없으면 전체 이벤트 수를 반환한다.
- FINAL로 동일 이벤트의 재적재를 제거하며 llm_request는 제외한다. 서로 다른 호출·응답 이벤트를 동일 호출로 추정해 병합하지 않으며 이 의미를 품질 캡션으로 표시한다.
- 실제 ClickHouse에서 사유별 집계, 다른 이벤트 제외, 중복 제거, 미관측 구간 null, 작은 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin의 API 클라이언트와 위젯 변환에서 end_turn 5건·refusal 5건·사유 누락 10건 및 모델 라벨을 확인했다. 전체 테스트 912건과 총 53개 지표 연동 검증이 통과했다. ingest 경로와 전체 수용 검증은 남아 있다.


## 턴·LLM 소요 시간 (F-3)

- turn_duration_ms는 span turn의 문자열 attrs.duration_ms로 정확 p50/p90을, llm_duration_ms는 error_type이 없는 llm_call의 숫자 duration_ms로 정확 p50/p95/p99를 계산한다.
- 누락·음수는 제외하고 0ms는 포함한다. 유효 값이 없으면 빈 프레임이며 시계열 미관측 구간은 null이다. 기본 distribution은 백분위수 프레임 하나로 반환하고 단위는 ms다.
- 실제 ClickHouse에서 중복 제거, 오류 호출 제외, 정확 백분위수, 누락·음수·0ms와 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin API·위젯 변환에서 LLM p50/p95/p99=900ms, 턴 p50/p90=1500ms를 확인했다. 전체 테스트 912건과 지표 53개 연동 검증 통과. 첫 토큰 지연은 아래 후속 작업으로 연결했고 전체 ingest E2E는 아직 남아 있다.


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
- 실제 frontend owner/admin에서 사용자 수락 25 / 수락·거절 50 = 0.5 및 kotlin/Edit 라벨을 확인했다. 전체 테스트 912건과 총 53개 지표 연동 검증 통과. 전체 ingest E2E는 별도로 남아 있다.


## 서브에이전트 활동 (E-5)

- tool_call에서 비어 있지 않은 agent_id의 고유 수(value), 해당 ID가 있는 호출의 비율(ratio), 분자(numerator)와 전체 도구 호출 수(denominator)를 반환한다. count와 ratio 필드의 단위를 구분한다.
- Q14대로 agent_id 문자열 자체의 고유 수를 계산한다. 같은 ID가 여러 설치에서 관측돼도 한 식별자로 센다. 식별자의 전역 유일성이나 완료·성공 여부를 추정하지 않는다. 카탈로그 partial과 한계 설명을 유지한다.
- 동일 이벤트 재적재는 FINAL로 제거한다. ID 누락·빈 문자열 호출도 분모에는 포함하고 parent_agent_id를 agent_id 대신 쓰지 않는다. 도구 호출이 없으면 빈 프레임이다.
- 실제 ClickHouse에서 반복 ID·중복 이벤트, 다른 이벤트 제외, 누락·빈 ID, 비교 집단의 고유 수·비율·분자·분모 마스킹을 검증했다.
- 실제 frontend owner/admin의 팀별 위젯 변환에서 식별자 2개와 호출 비율 10/15=2/3을 확인했다. 전체 테스트 912건과 총 53개 지표 연동 검증 통과. 전체 ingest E2E는 별도로 남아 있다.


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
- 실제 frontend owner/admin에서 실행 10회·훅 세션 5개·전체 세션 5개·비율 1을 확인했다. 카탈로그 partial을 유지하며 전체 테스트 912건 및 총 53개 지표 연동 검증이 통과했다. 전체 ingest E2E는 별도로 남아 있다.


## 모델 거부 (G-2)

- refusals는 llm_response의 stop_reason=refusal 이벤트 수다. llm_call 및 다른 종료 사유는 제외하고 분류 누락·빈 문자열은 unspecified로 제공한다. category·model·product 차원만 허용한다.
- 기존 owner 전용 인가와 team 분해 금지를 유지한다. admin 조회는 403, owner의 team 분해 요청은 400이며 조회 실행 전에 거부한다.
- 동일 이벤트 재적재는 FINAL로 제거하지만 서로 다른 홉 이벤트를 동일 거부로 추정해 합치지 않는다. server_fallback_hop 부재의 한계와 partial 상태를 유지한다.
- 실제 ClickHouse에서 응답 유형·종료 사유 필터, 중복 제거, 분류 누락, 권한·차원 제한과 비교 집단 마스킹을 검증했다.
- 실제 frontend owner에서 policy 거부 5건을 확인했다. 공통 49개는 owner/admin 양쪽에서, 비용 이상 1개는 admin에서, 모델 거부·벤더 불일치·약정 소진율 3개는 owner에서 검증해 총 53개다. 전체 테스트 912건 통과. 전체 ingest E2E는 별도로 남아 있다.


## 모델 사용자 (E-1)

- model_users는 모델이 관측된 이벤트의 설치를 구성원에 연결해 고유 사람 수를 계산한다. 여러 설치의 동일 구성원과 여러 모델을 사용하는 구성원은 해당 집계 그룹 내 한 번만 센다. 알 수 없는 설치는 사용자 수에서 제외한다.
- 모델은 payload.model 우선·point.attrs.model 대체다. 모델이 비어 있는 관측은 제외하며 누적 메트릭 포인트는 사용자 수에 포함하지 않는다. 누적 포인트만 있으면 값 null과 제외 설명을 반환한다.
- 실제 ClickHouse에서 설치 중복, 미연결 설치, 로그·메트릭 모델, 누적 제외, 모델 필터 및 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin에서 claude-e2e의 사용자 5명을 확인했다. 공통 49개와 admin 비용 이상 1개 및 owner 전용 3개로 총 53개 지표 연동 검증 및 전체 테스트 912건이 통과했다. 이번 frontend 검증 HEAD는 52f7cb10017c6ba6120f51f2e158ff329d14bff0이다. 전체 ingest E2E는 별도로 남아 있다.


## 캐시·입출력 토큰 비율 (C-2, S1-5)

- cache_read_ratio와 input_output_ratio는 llm_call 이벤트의 토큰 합계 비율을 제공한다. 각각 cache_read/(input+cache_read+cache_create), input/output이며 평균 비율이 아니다.
- 각 비율에 필요한 값이 모두 존재하고 음수가 아닌 호출만 합산한다. 누락 값을 0으로 추정하지 않기 위한 처리이며 응답 품질 설명에 명시한다. 완전한 호출이 없으면 비율·분자·분모가 null이고, 유효한 0 분모는 숫자 0과 비율 null이다.
- 값의 단위는 ratio, 분자·분모는 token이다. 팀·모델 차원과 현재 권한·필터·비교 마스킹을 적용한다. 이벤트 원천만 연결했으며 두 비율의 메트릭 원천 확장은 후속 작업이다. tokens의 메트릭 집계는 아래 후속 작업에서 연결했다. source 지정은 현 계약대로 cost/tokens에만 허용한다.
- 실제 ClickHouse에서 합계 비율, 누락·음수 제외, 중복 제거, 분모 0과 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin에서 캐시 3000/6000=0.5, 입출력 1500/750=2를 확인했다. 공통 49개와 admin 비용 이상 1개 및 owner 전용 3개로 총 53개 지표 및 전체 테스트 912건 통과. 전체 ingest E2E는 별도로 남아 있다.


## 토큰 사용량 (C-2)

- tokens는 기본 events(llm_call) 또는 metrics(Claude Code token.usage) 원천 하나를 선택한다. 원천을 합치거나 서로 대신 사용하지 않는다.
- types 파라미터는 input/output/cache_read/cache_create의 중복 없는 배열이다. 생략·빈 배열은 네 종류 전체다. 메트릭 cacheRead/cacheCreation은 API의 cache_read/cache_create로 변환한다. reasoning·total_reported 등을 추가 합산하지 않는다.
- 종류·팀·제품·모델·query_source 차원을 지원한다. agent_name은 메트릭에서만 허용하고 이벤트 요청이면 400이다. 관측된 비음수 값만 합산하며 전부 누락이면 null, 유효한 0은 숫자 0이다. 누적 메트릭은 제외 설명과 함께 합산에서 제외한다.
- 실제 ClickHouse에서 두 원천 분리, 종류 선택·정규화, 잘못된 파라미터 거부, 귀속 차원, 누락·0 및 비교 집단 마스킹을 검증했다.
- 실제 frontend owner/admin에서 이벤트 토큰 input=1500/output=750/cache_read=3000/cache_create=1500, 메트릭의 output+cache_read=250 및 worker 귀속을 확인했다. 공통 49개와 admin 비용 이상 1개 및 owner 전용 3개로 총 53개 지표 및 전체 테스트 912건 통과. 전체 ingest E2E는 별도로 남아 있다.


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
- 전체 빌드 테스트 912건과 frontend 연동 지표 53개가 통과했다. 정규화 fixture를 직접 적재한 검증이며 실제 ingest와 전체 시나리오 E2E는 남아 있다. 약정 소진율은 아래 후속 작업으로 연결했다.


## 계약 약정 소진율 (H-2)

- contract_commitment_burn는 frontend P5에 맞춰 owner 전사 범위만 허용한다. 팀·구성원·제품·모델 필터는 403이다. contract_id는 선택 UUID이며 생략하면 tenant 내 비초안 term_commitment 계약을 개별 프레임으로 반환한다. 계약 ID는 라벨이며 비용 관측이 없으면 빈 프레임이다.
- 조회 기간과 계약 시작·종료·해지 및 구성원 배정·해제 기간의 교집합에서 제품 벤더가 일치하는 이벤트 비용만 사용한다. 날짜 경계는 tenant 시간대이며 종료일 다음 자정·해지·해제 시각은 제외한다. 멤버십의 중복 구간은 이벤트를 복제하지 않는다.
- 항상 계약 비용으로 계산한다. 기존 token_discount 계약의 유효 all 배율을 적용하고, USD 약정액 전체를 분모로 삼는다. source는 이벤트만 사용한다. 누락·0 약정액은 비율 null이며, 음수 약정액과 USD 외 통화는 각각 422로 거부한다. 임의 환산·청구액 추정은 하지 않는다.
- frontend 호환 필드는 burn_ratio(ratio), cost_usd(USD), commitment_amount(USD)다. scalar/table은 조회 기간 합계, timeseries는 버킷별 비용/전체 약정액이다. 누적 소진율은 계약 시작부터 scalar로 조회한다. 현재·비교 소집단이면 약정액도 숨긴다.
- 실제 frontend의 계약 starts_at 날짜 입력을 위해 YYYY-MM-DD를 요청 시간대 자정으로 해석한다. DST 전환 전후 자정과 잘못된 날짜 거부를 단위 테스트로 검증했다. 기존 366일 조회 제한은 유지하므로 더 긴 계약 전체 기간은 후속 보완이 필요하다.
- 실제 PostgreSQL·ClickHouse에서 배정·해제·해지 경계, 벤더·원천 제외, 할인 비용 25/1000, FINAL 중복 제거, 누락·0·음수 약정액, 비USD 통화, owner 인가, tenant 격리, 비교·CSV 마스킹을 검증했다.
- 전체 빌드 912건, 실제 frontend 연동 지표 53개와 P5 약정 게이지 1.5%(15/1000)를 검증했다. 53개 지표의 기본 계산 경로가 연결됐지만, 46개 시나리오·실행·저장 API 및 ingest부터의 전체 E2E는 미완료다.


## 설치 목록 (INSTALL-LIST)

- GET /v1/installations는 ADR 0019에 따라 owner와 10~500자 감사 사유를 요구한다. 감사 INSERT가 실패하면 503 + Retry-After로 종료하고 설치 정보를 반환하지 않는다. admin 및 감사 사유 없는 요청은 403이다.
- 현재 팀 소속, platform(windows/macos/linux), status(active/revoked), inactive_days(1 이상)를 지원한다. 설치 UUID 오름차순 키셋으로 limit 1~500(기본 50)을 적용하고 빈 cursor는 첫 페이지로 해석한다. total은 계산하지 않아 null이다.
- PostgreSQL의 설치·구성원·현재 팀 정보를 읽고, 같은 tenant·설치의 ClickHouse FINAL 관측을 결합한다. 미래 이벤트는 제외한다. last_seen_at은 RDB 값, last_event_at은 제품 전체 마지막 이벤트다. product_versions는 제품별 마지막 이벤트의 envelope.client.version이며 마지막 이벤트에 버전이 없으면 해당 키를 생략한다. 같은 시각은 event_id로 결정한다.
- 무활동은 마지막 이벤트가 기준일 이하인 설치를 선택한다. 이벤트 미관측 설치는 생성일이 기준일 이하인 경우만 포함한다. 최근 생성된 설치를 즉시 장기 무활동으로 분류하지 않으며, last_seen_at으로 이벤트 관측을 대체하지 않는다. 이메일은 감사 후에도 ***@도메인 형식으로만 반환한다.
- 무활동 필터를 페이지 절단 전에 적용한다. 요청당 RDB 후보는 5,000개로 제한하고 초과 시 422 query_too_wide를 반환한다. 30초 조회 예산과 기존 ClickHouse 응답 크기 제한을 사용한다. 페이지 사이의 동시 변경을 고정하는 스냅샷은 제공하지 않는다.
- 실제 DB에서 제품 버전·마지막 시각·FINAL 중복 제거·미래 이벤트 제외·미관측·키셋 페이지·무활동·현재 팀·tenant 격리·감사 저장 실패 차단을 검증했다. 전체 테스트 912건이 통과했다.
- 실제 frontend 클라이언트로 감사 사유를 전달해 설치 5개를 2/2/1의 3페이지로 조회하고 중복 없는 ID, 제품 버전, 마지막 이벤트와 감사 로그 3건을 확인했다. 기존 53개 지표와 P5 게이지 연동도 통과했다.
- 기존 frontend opsApi.installations/설치 카드는 감사 사유를 전달하지 않아 403이 된다. frontend를 수정하지 않는 확정 범위와 ADR의 감사 요구를 유지했으며, 설치 카드 전체 UI 검증으로 표시하지 않는다. SESSION-EVENTS는 아래 후속 작업으로 연결했다.


## 세션 상관 조회 (SESSION-EVENTS)

- GET /v1/sessions/{session_id}/events는 ADR 0019에 따라 owner와 감사 사유를 매 페이지 재검증한다. 관리자 허용이라는 첨부 초안보다 확정 ADR을 우선한다. 감사 실패는 503 + Retry-After이며 이벤트를 반환하지 않는다.
- lookup=session_id(기본)/request_id/call_id/installation_id를 지원한다. 요청·도구·설치 키가 일치하는 이벤트에서 세션을 찾고, 선택 기간 내 해당 설치·제품·세션의 전체 이벤트로 확장한다. 여러 세션이 일치하면 422 ambiguous_session, 없으면 404다. 빈/unknown 세션을 하나의 세션으로 합치지 않는다.
- 기본 기간은 now-7d~now, 최대 366일이며 limit은 1~500(기본 50)이다. ts, sequence(누락은 정렬에서 0), event_id 순서의 키셋 페이지를 반환한다. 커서는 검색 키·tenant·기간 식과 연결하고 상대 기간의 해석 결과를 유지한다. 다른 검색의 커서는 400이다. 저장 데이터의 동시 변경까지 고정하는 스냅샷은 아니다.
- ClickHouse FINAL을 사용한다. 세션 메타는 선택 기간 내 전체 세션의 최소/최대 시각·이벤트 수 및 첫 이벤트의 팀·클라이언트 버전이며 페이지가 바뀌어도 같은 조회 범위를 집계한다. ended_at은 마지막 관측이며 실제 종료를 보장하지 않는다.
- 이벤트에는 스키마의 ID·시각·신호·유형·상관 ID·추론 여부·순번과 정규화 payload 또는 point만 투영한다. envelope와 enrichment_json 전체를 내보내지 않는다. 메트릭 type은 point.name이며 payload에는 point를 사용한다. 정상 파이프라인의 원문 미보존 전제는 ADR 0017을 따른다.
- 실제 ClickHouse에서 신호 결합·FINAL 중복 제거·키셋 순서·상대 기간 고정·세 검색 키 확장·모호한 세션 오류·tenant/기간 격리·감사 실패·owner 인가를 검증했다. 전체 빌드 테스트 912건이 통과했다.
- 실제 frontend opsApi.events로 페이지 전체를 읽고 원본 fixture의 event_id 순서·세션 이벤트 수와 대조했다. eventDetails의 토큰·비용 문자열 변환과 페이지별 감사 로그도 검증했다. 기존 53개 지표·설치 페이지·P5 게이지 smoke도 통과했다. SessionSearch 화면 전체 및 ingest부터의 전체 E2E로 표시하지 않는다.
- 다음 구현 대상은 46개 시나리오 카탈로그·상세·실행 입력 검증이다. 실행 워커·이력·저장 리포트와 미지원 조회 형식은 계속 남아 있다.

## 시나리오 카탈로그·상세 (SCN-LIST, SCN-GET)

- `dashboard/scenarios.json`은 첨부 개요 §6-2의 46개 ID·제목·분류·위젯·지표 연결을 옮긴 정적 데이터다. S5-1은 OpenAPI 예시대로 지표 목록을 비워 원문 분석을 제공한다는 오해를 피한다.
- 목록은 category·availability·target_page를 검증하고 교집합으로 필터링한다. q는 제목·상황의 대소문자 무시 부분 검색이며 최대 500자다. 결과가 없어도 8개 카테고리를 반환한다. 없는 상세는 404다.
- 정의 조회는 owner/admin 공통이다. 개인 정보·실행 결과를 읽지 않으므로 감사 사유를 요구하지 않는다. P3 실행 인가는 별도 구현에서 ADR 0019를 적용해야 한다.
- 상세 지표의 정의·가용성·주의사항은 META-METRICS와 같은 객체에서 생성한다. 시나리오 availability는 데이터 산출 가능성이며 실행 구현 완료를 뜻하지 않는다.
- 개요의 ‘주요 params’와 필수 입력은 구분한다. 기간 from/to 및 기본값이 없는 분석 입력은 required에 넣고, 팀·모델·언어·명령 필터는 선택 입력으로 둔다. 폼 스키마는 지원 타입만 쓰며 추가 속성을 허용하지 않는다. 날짜 기준일은 고정 예시 날짜를 기본값으로 넣지 않는다.
- S1-3 이동평균 7일(3–28일), 임계 200%, 28일 조회와 판정식 3개·액션 2개는 OpenAPI 예시를 따른다. 다른 수치 입력의 초기값·범위는 폼 계약으로 정했으며, 실제 실행 시 의미·범위를 서버에서 다시 검증해야 한다.
- 제공 문서에 없는 상황·카테고리 질문·페르소나는 생략한다. S1-3 이외 판정식·액션은 빈 배열이다. 실행 워커·나머지 판정 정책·실측 결과·저장 리포트는 이 작업에 포함되지 않았다.
- 검증: 전체 테스트 912건(실패·오류·skip 0), 비인증 401·admin 정의 조회·46개 지표 메타 일치·필터 교집합·잘못된 필터 400·없는 ID 404를 확인했다.
- 실제 frontend `scenarioApi.catalog/detail`로 owner/admin 각각 46개 상세를 읽고, S1-3 폼 기본값과 범위 오류를 실제 `defaults/validateParams`로 검증했다. 기존 53개 지표·P5·설치·세션 smoke도 통과했고 오류 응답은 없었다. 카탈로그 화면 렌더·시나리오 실행 E2E는 아니다.

## 시나리오 실행 입력 검증 기반

- `DashboardScenarioInputs.prepare`를 추가했다. 이 기반 단계에서는 HTTP 실행 API와 큐·워커에 연결하지 않았다. 이후 아래 S1-3 비동기 실행 단계에서 연결했다.
- 카탈로그를 HTTP 컨트롤러와 분리해 동일한 파라미터 정의를 조회 API와 실행 준비에서 사용한다. 서버가 지원하는 object/array/string/integer/number만 검사하며 알려지지 않은 속성·타입 강제 변환·null·비유한 숫자·중복 배열·잘못된 날짜를 거부한다.
- 기본값은 입력 사본에만 적용한다. from/to·cohort 기간은 순서와 366일 상한을 확인하고 모든 시간은 요청 시점 now 및 명시 tz(없으면 tenant timezone)로 해석한다. pivot/as_of에서 실제 쿼리 기간을 만드는 정책은 실행 계획 단계에서 정의해야 한다.
- 입력 크기는 객체/배열 100개, 문자열 최대 500 코드포인트(스키마 상한이 작으면 그 값), 깊이 8로 제한한다. 예산은 canonical UUID 팀 키와 양수인 USD/토큰 중 한 단위만 허용하며 선택한 팀 밖 예산을 거부한다. 모델 A/B는 서로 달라야 한다.
- 실행 준비는 현재 tenant·팀 소속을 확인하고 scope를 별도로 반환한다. admin은 팀 필터가 없는 시나리오에도 현재 팀 범위를 적용하며, 빈 팀 범위와 owner 전사 범위는 `organizationScope`로 구분한다. 워커는 이 플래그를 보존하고 빈 admin 범위를 전체 조회로 해석하면 안 된다.
- P3 및 refusals 포함 시나리오는 owner·감사 INSERT 성공이 필요하다. 불가 시나리오는 `scenario_unavailable`(409), 없는 ID는 404 예외로 구분한다. 아직 큐 삽입은 하지 않으며, 향후 admission 트랜잭션과 워커 실행 시 현재 계정·팀 권한을 다시 검증해야 한다.
- 검증: 타입·범위·기본값 비변형·DST·기간·예산·모델 비교 및 46개 스키마 입력 단위 테스트 6건과 실제 DB 인가 테스트 3건을 추가했다. 전체 빌드 921건, 실패·오류·skip 0건.
- 실제 frontend 회귀 smoke도 통과했다(backend `b91f980`, frontend `52f7cb1`). 46개 목록·상세와 기존 53개 지표·P5·설치·세션을 확인했고 오류 응답은 없었다. 실행 준비 컴포넌트는 직접 단위/DB 테스트로 검증했으며 HTTP 실행 E2E로 검증한 것은 아니다.

## S1-3 비동기 실행 경로

- `POST /scenarios/S1-3/runs` → PostgreSQL queued → 워커 → `GET /scenario-runs/{id}`의 succeeded/failed까지 연결했다. `POST /scenario-runs/{id}/cancel`도 제공한다. 기본 202·Location·Retry-After 2초이며, wait=true는 최대 20초 대기 후 종료 시 200, 진행 중이면 202다.
- 현재 실행 계획은 S1-3 하나다. 불가 시나리오는 409, 다른 카탈로그의 미구현 실행은 501, 없는 ID는 404다. 다른 시나리오를 단순 지표 합계만으로 성공시키지 않는다. 46개 전체 실행 완료가 아니다.
- 실행 파라미터 검증을 실제 HTTP 요청에 연결했다. from/to·tz·가격 기준과 admin 팀 범위를 접수 시 고정한다. owner의 전사 범위와 admin의 빈 팀 범위는 구분하며 빈 admin 범위는 403이다.
- tenant 행 잠금 안에서 queued+running 개수를 세고 3개 이상이면 429다. 워커는 1초 주기로 DB에서 `SKIP LOCKED` claim하며 프로세스 메모리에 큐를 두지 않는다. 단일 인스턴스는 순차 처리하고 다중 인스턴스는 claim을 분담할 수 있다.
- V2 마이그레이션은 execution 스냅샷·claim token·진행 단계를 추가한다. 각 지표 조회 전 진행 갱신이 2분 lease를 연장한다. 재시작 후 queued는 처리할 수 있고 만료된 running은 worker_lease_expired로 실패한다. 자동 재시도는 하지 않는다.
- 결과/실패 저장은 running·동일 claim·유효 lease 조건에서만 허용한다. 취소는 queued/running에서만 허용하며 이미 진행 중인 ClickHouse HTTP 조회를 즉시 중단하지는 않지만 이후 지표 조회와 결과 저장은 차단한다.
- 워커는 각 지표 조회 전과 최종 저장 전에 생성자의 현재 계정·tenant 상태·역할·팀 소속을 확인한다. 원래 역할이 바뀌거나 범위를 잃으면 failed로 끝내고 부분 결과를 저장하지 않는다. GET/CANCEL도 tenant·현재 조회 권한을 확인하며 admin은 자신의 실행 중 현재 팀 범위에 속하는 것만 접근한다.
- S1-3은 cost(모델별 일 비용)·cost_anomaly(전체 일 비용 이상)·api_retry_attempts(일별 재시도 비율)를 기존 QRY로 순서대로 계산한다. 지표 하나라도 실패하면 실행 전체가 failed다. 현재 실행은 지표마다 최대 30초 조회 예산을 가지며 결과는 동일 접수 기간·필터를 사용하지만 DB 스냅샷 격리는 아니다.
- spike_day는 지정 임계, retry_cost는 5%, top_mover는 모델 비용 비중의 전일 대비 +30%p로 판정한다. top_mover의 그룹·비교 기준을 모델·전일로 구체화했다. 전체 모델 프레임이 100개 미만이고 두 날 모두 누락·마스킹 없이 관측됐을 때만 비중을 평가한다. 모든 판정은 마스킹/누락 수치를 사용하지 않으며 원본 판정식 문자열을 실행하지 않는다. 조회 경계의 부분 일자는 기존 QRY처럼 부분 관측이다.
- 실행 목록·저장 리포트·다른 45개 시나리오 계획과 전체 화면·ingest E2E는 남아 있다.
- 검증: 실제 DB/ClickHouse의 실행 완료·입력 오류·조회 오류·마스킹·동시 admission·취소와 lease·권한 변경·wait 완료 테스트 9건 및 판정 규칙 테스트 3건을 추가했다. 전체 빌드 933건, 실패·오류·skip 0건.
- 실제 frontend 클라이언트 E2E는 backend `d59037c` / frontend `52f7cb1`에서 통과했다. owner/admin 실행·폴링·프레임 변환·취소와 admin의 실측 스파이크(ratio=1, 임계 50%)를 검증했고 기존 지표 smoke에도 오류가 없었다.
- 이후 결과 화면용 findings_count 응답을 추가한 `41faa97`에서 전체 빌드 933건이 다시 통과했다. 결과 화면 렌더 검증도 스크립트에 추가했지만 Docker의 run/ps가 모두 응답하지 않아 실제 화면 E2E는 실행하지 못했다. backend 로그 생성 전의 환경 기동 문제이며 UI 검증 통과로 기록하지 않는다.
- 2026-09-11 사용자 승인으로 Docker Desktop을 재시작한 뒤 backend `cd057dc` / frontend `52f7cb1`에서 E2E를 다시 실행해 통과했다. owner/admin의 S1-3 실행·폴링·취소·결과 화면 판정 표시와 기존 53개 지표·P5·설치·세션 검증에 오류 응답이 없었다. `build/e2e/auth-settings/result.json`은 passed이며 `owner-scenario.png`와 `admin-scenario.png`에 결과 화면을 보관한다.
- 결과 화면의 판정 영역과 심각도별 개수는 확인했으나 W1.3·W2.5는 결과 미연결 안내가 남아 있다. 모든 위젯의 시각화 완료나 ingest 수용 E2E 통과로 해석하지 않는다. 스크립트는 명령별 60초 제한과 실패 상태 기록을 적용하고 이전 성공 결과는 `last-success.json`에 보관한다.


## S1-3 팀별 비용 결과 위젯

- `7c224bb`에서 cost 결과에 팀별 기간 합계 table 프레임을 추가해 실제 frontend의 W1.3에 연결했다. 기존 모델별 일 시계열로 판정을 끝낸 뒤 표를 합치므로 top_mover의 모델 비중 계산에 팀 합계를 섞지 않는다.
- 실행은 기존 3회 조회와 팀별 비용 조회까지 4단계다. 추가 조회도 접수 시 기간·가격·팀 범위를 사용하고 현재 권한·취소·lease 조건을 재검증한다. 실패 시 부분 결과를 저장하지 않는다. 팀 집계는 기존 QRY의 as-of 소속과 소집단 마스킹을 따른다.
- dashboard 테스트 126건이 통과했다(실패·오류·skip 0). 팀별 비용 200 USD 및 4명 소집단 null을 확인했다. 실제 frontend owner/admin에서 W1.3 막대그래프·데이터 표 30 USD와 기존 S1-3 실행·판정·취소, 53개 지표 E2E가 통과했다.
- E2E는 커밋 전 작업 트리에서 실행해 result.json의 backend 필드는 부모 `039fa59`다. 검증한 앱 jar SHA-256은 `df18bedd6901ccdc6a6261d8382236f3956c2fbd95d7d247f2c04f5268f89d74`, frontend는 `52f7cb1`이다. 코드 변경은 `7c224bb`에 보관했다.
- W2.5는 남아 있다. 현재 frontend가 P1의 cost 프레임을 형식에 따라 W1.1~W1.3으로만 연결하므로 backend에서 cost 프레임을 추가하는 것만으로 해결되지 않는다. frontend 무수정 범위에서 다른 지표 ID로 위장하지 않는다. ingest 전체 E2E와 실행 목록·저장 리포트·다른 시나리오 계획도 후속 대상이다.


## 실행 이력 목록 (RUN-LIST)

- `GET /v1/scenario-runs`는 scenario_id·status·created_by 필터와 limit 1~500(기본 50), cursor를 지원한다. created_at DESC, id DESC 키셋으로 같은 시각의 실행도 중복·누락 없이 페이지 처리한다. total은 계산하지 않아 null이다.
- owner는 tenant 내 실행을 읽고 admin은 본인이 admin으로 생성한 비전사 실행 중 저장된 팀 범위가 현재 소속에 전부 포함되는 것만 읽는다. 인가·필터를 LIMIT 전에 적용해 숨겨진 행 때문에 빈 페이지나 잘못된 다음 커서가 생기지 않게 했다. 다른 tenant의 실행은 노출하지 않는다.
- 커서는 tenant·조회자·역할·현재 팀·필터의 해시와 마지막 시각/ID를 담는다. 다른 범위에서 재사용하거나 잘못된 인코딩이면 400이다. 권한은 커서와 별도로 매 요청 SQL에서 재검증한다. limit 변경은 허용한다. 상태 변경·동시 삽입을 고정하는 스냅샷은 제공하지 않는다.
- 요약에는 실행/시나리오 ID·상태·해석된 기간 문자열·생성자 표시 이름·생성/종료 시각·심각도별 판정 수·최신 저장 ID만 담는다. 큰 결과 프레임과 입력 params는 조회·응답에 포함하지 않는다.
- 실제 DB 테스트 3건을 추가해 같은 시각의 5개 실행 페이지·필터·tenant 격리·admin 역할/팀 변경·잘못된 커서/UUID/limit을 검증했다. dashboard 테스트 129건, 실패·오류·skip 0건이다.
- frontend 이력 화면은 저장 리포트 API도 동시에 호출한다. 저장 API가 미구현이므로 이번 범위는 실제 scenarioApi.list로 실행 완료·취소 항목, 요약 투영 및 admin 본인 실행만 노출되는지를 확인하는 클라이언트 연동이다. 이력 화면 전체 완료로 표시하지 않는다.
- backend `a8f6aa1` / frontend `52f7cb1`의 실제 frontend E2E가 통과했다. 실행 목록과 기존 53개 지표·S1-3 결과/취소·W1.3·P5·설치·세션 클라이언트를 확인했고 오류 응답은 없었다. result.json의 actualHistoryClient는 true이며 이력 화면 전체 렌더를 뜻하지 않는다.


## 저장 리포트와 실행 삭제

- RUN-SAVE, SAVED-LIST, SAVED-DELETE, RUN-DELETE를 연결했다. 22개 operation 경로가 모두 존재하지만 QRY와 SCN-RUN은 여전히 부분 구현이며 전체 PROJ-156 완료가 아니다.
- 저장은 조회 권한이 있는 succeeded 실행만 허용한다. name은 공백만 있는 값 제외 1~100 코드포인트, note는 최대 2,000 코드포인트, time_mode는 fixed(기본)/relative다. 알려지지 않은 키·강제 타입 변환·명시적 null·제어 문자를 거부하고 메모의 줄바꿈/탭은 허용한다. relative는 실행 파라미터에 now 상대식이 있어야 한다.
- 고정 모드는 기존 실행의 결과와 해석된 절대 기간을 그대로 참조한다. 상대 모드는 기존 frontend가 원래 실행을 GET한 뒤 원래 params로 새 실행을 요청한다. 서버 목록 조회가 재실행을 일으키지는 않는다. share_path는 인증이 필요한 /runs/{id}이며 공개 공유 토큰이 아니다.
- 저장 목록은 created_at DESC, id DESC 키셋 페이지(limit 1~500, 기본 50)와 scope에 묶인 커서를 사용한다. owner는 tenant 내 리포트, admin은 현재 팀 권한으로 조회 가능한 본인 실행의 리포트를 본다. 페이지 절단 전에 인가하며 total은 null이다.
- 저장 항목 삭제는 현재 원본 실행 조회 권한을 확인하고 저장자 또는 owner만 허용한다. 원본 실행 삭제는 진행 중이거나 저장 항목이 하나라도 연결돼 있으면 409다. 저장 항목을 삭제해도 실행 결과는 유지한다.
- 저장·두 삭제 경로는 같은 원본 실행 행의 FOR UPDATE 잠금을 먼저 획득한다. 동시 저장/실행 삭제는 저장 201 + 삭제 409 또는 삭제 204 + 저장 404로 종료해 참조가 끊기지 않는다. 스키마 변경 없이 기존 dashboard 테이블을 사용한다.
- 실제 DB 테스트 4건을 추가했다. 고정/상대 저장·페이지·요약 saved_id·원본 보존·입력/상태 오류·현재 팀 권한·타인 저장 항목 삭제 403·동시 저장/삭제를 검증했고 dashboard 테스트 133건이 통과했다(실패·오류·skip 0).
- 구현 커밋은 `7fe5c3a`다. 커밋 전 작업 트리의 실제 frontend E2E가 통과했다(부모 backend `7b214e0`, jar SHA-256 `c17ac8c13ca1c346043d022071fab7ebd0d86517150f12b228d5ef91726eb7ce`, frontend `52f7cb1`). owner/admin 저장 대화상자·이력 화면·고정 열기·상대 모드 새 실행과 실제 클라이언트 삭제를 확인했다. 기존 53개 지표·P5·설치·세션·실행 결과 검증에도 오류 응답이 없었다. `owner-history.png`, `admin-history.png`에 화면을 보관한다. 삭제 확인 대화상자 자체의 클릭 검증과 ingest부터의 전체 E2E는 별도다.


## 저장 리포트·실행 삭제 화면 검증

- owner/admin 각각 실제 이력 화면의 저장 리포트 삭제 및 실행 이력 삭제 대화상자를 클릭했다. 각 대화상자에서 취소 시 DELETE 요청이 없고 행이 유지되며, 확인 시 지정한 URL로 DELETE가 정확히 한 번 발생하고 목록 행이 사라지는 것을 검증한다.
- 고정·상대 저장 항목을 삭제한 뒤 원본 실행이 succeeded이며 결과 전체가 저장 전과 동일한지 확인했다. 이어 상대 재실행과 원본 실행을 화면에서 삭제하고, 별도의 취소된 실행은 목록에 남는지 실제 API로 확인했다.
- 2026-09-11 실제 frontend E2E 통과: backend `c0702a3`, jar SHA-256 `c17ac8c13ca1c346043d022071fab7ebd0d86517150f12b228d5ef91726eb7ce`, frontend `52f7cb1`. 기존 53개 지표·설정·설치·세션·S1-3 결과·저장 흐름도 통과했으며 오류 응답은 없다. frontend 및 서버 구현은 변경하지 않았다.
- `build/e2e/auth-settings/{owner,admin}-history-deleted.png`에 삭제 후 목록을 보관한다. `result.json`의 actualDeleteDialogs, deleteCancelledWithoutRequest, savedDeletionPreservesResult로 검증 범위를 기록한다. 이 검증은 삭제 화면 공백을 해소하며, 나머지 시나리오 실행 계획·조회 명세 보완·ingest부터의 전체 E2E는 여전히 남아 있다.


## 비용 상위 N과 나머지 그룹

- `cost`의 그룹 수가 limit(기본 100)을 넘으면 상위 N과 `__other__`를 반환한다. scalar/table/timeseries의 현재 기간 합계로 선택하고 동률은 그룹 키 JSON 문자열 순서로 결정한다. 현재·비교 기간은 동일한 선택을 사용한다. 비교에만 있는 그룹은 현재 비용 0으로 취급한다.
- 현재 또는 비교 시계열에 5명 미만 집단이 있는 그룹은 숨겨진 비용을 순위에 쓰지 않고 점수 0으로 처리한다. `__other__`는 예약 그룹 키이며 복수 차원에서는 제외된 조합의 모든 차원 값을 이 키로 바꾼다.
- 나머지는 집계 결과의 인원수를 더하지 않고 동일 필터·가격 기준·원본 데이터에서 다시 집계한다. 기존 사용자 중복 제거, 활동 시간 기반 인원 정의, 비교 마스킹과 null 처리를 유지한다. 팀별 비용은 기존 팀 소속별 귀속 의미를 유지한다. 재조회도 동일 요청 시간 예산과 ClickHouse 결과 크기 제한을 사용한다.
- DB 테스트 2건으로 현재 상위 50/나머지 25, 동일 그룹의 비교 5/100, 나머지 중복 사용자 5명, 소집단의 순위 영향 배제와 마스킹을 검증했다. dashboard 테스트 135건 통과(실패·오류·skip 0). 구현 커밋 `122dd5a`.
- 실제 frontend E2E도 통과했다. admin 클라이언트의 `series`가 상위 비용 50과 나머지 25를 해석하며, 기존 지표·설정·설치·세션·S1-3·저장/삭제 화면에 오류 응답이 없다. 커밋 전 부모 `42e113d`, jar SHA-256 `7aeb68c9ed9f3b0a8650f4fed31d053928d9318478b6099449be2b1fc9539ae6`, frontend `52f7cb1`로 검증했다. `result.json.verifiedCostTopN`에 결과를 남긴다.
- 이번 범위는 비용 지표다. 다른 지표의 상위 N·나머지, 나머지 시나리오 실행 계획, 조회 명세 보완과 ingest부터의 전체 E2E는 남아 있다.


## 합계·건수 상위 그룹 확장

- `sessions`, `active_time`, `lines_of_code`, `commits`, `pull_requests`, `tool_calls`, `rate_limit_events`, `tool_rejections`, `usage_heatmap`, `compactions`, `mcp_connections`, `llm_stop_reasons`, `hook_blocking` 13개 지표에 상위 N·`__other__` 처리를 확장했다. 비용을 포함한 지원 지표는 14개다. 비율·백분위·고유 인원 지표 등은 별도 집계가 필요하며 아직 확장하지 않았다.
- 비용과 일반 합계 쿼리의 차원 재그룹화를 공통화했다. 현재 합계·동률 키 정렬·마스킹 그룹 점수 0·비교 기간 동일 그룹·원본 인원 재집계 규칙을 유지한다. 따옴표가 있는 그룹 키를 테스트하면서 ClickHouse String 파라미터 역이스케이프로 JSON이 깨지는 문제를 수정했다. 값은 명명 파라미터로 전달한다.
- DB 테스트 2건을 추가하고 히트맵 제한 테스트를 새 명세 동작으로 갱신했다. 누적 9,999 포인트 제외, 세션 상위 50/나머지 25와 비교 100, 빈 버킷 null, 도구 성공 필터·한글/따옴표 키·복수 차원, 히트맵 100칸+나머지 340, 중복 제거 인원 5를 확인했다. dashboard 137건 통과(실패·오류·skip 0). 구현 커밋 `e3d4e4d`.
- 실제 frontend E2E에서 owner/admin의 종료 사유 상위 10/나머지 10을 `series`로 해석했다. 기존 53개 지표·P5·설치·세션·S1-3·저장/삭제 및 비용 상위 그룹도 통과했고 오류 응답은 없다. `result.json.verifiedCountTopN`에 범위를 기록한다. 쿼리를 추가할 때 테스트 요청이 12개 상한을 넘긴 실패는 요청을 분리하여 해결했다.
- 검증한 작업 트리 부모는 `edc7507`, jar SHA-256은 `796b023f6dbfb79b535abd4fe329e64ea3bf7f4a424f02b80cef6d3be528e2b3`, frontend는 `52f7cb1`이다. 이후 서버 소스 변경은 주석 교정뿐이다. frontend 수정은 없다. 나머지 조회 명세·시나리오 실행 계획·ingest부터의 전체 E2E는 계속 남아 있다.


## 비율 지표 상위 그룹 확장

- `automation_ratio`, `integration_depth`, `command_prompt_ratio`, `tool_failure_rate`, `api_retry_attempts`, `auto_approval_ratio`, `api_error_rate`, `compaction_reduction`, `mcp_failure_ratio`, `rubber_stamp_ratio`, `edit_acceptance_rate`, `cache_read_ratio`, `input_output_ratio` 13개에 상위 N·`__other__`를 확장했다. 비용·합계·건수를 포함하면 현재 27개 지표가 이 처리를 지원한다.
- 현재 기간의 분자 합계/분모 합계로 순위를 계산한다. 마스킹 대상·분모 0의 선택 점수는 0이며 결과의 null 의미는 유지한다. 나머지는 원본에서 분자·분모·인원을 다시 집계한다. 기존 비교 그룹 고정·시계열 정렬·권한 범위는 유지한다.
- 실제 DB 테스트 2건을 추가했다. 도구 a의 일별 실패율 1/0(전체 0.1)과 b의 0.4/0.4(전체 0.4)를 비교해 b가 table/timeseries에서 모두 선택되는지 검증했다. 나머지 5/50=0.1, 미판정 호출의 분모 제외, 중복 제거 5명, 영 분모 null, 숨겨진 높은 비율의 순위 영향 배제와 나머지 전체 수치 마스킹도 확인했다. 테스트의 최초 action 차원 요청은 명세상 불가하여 tool_name으로 수정했다.
- dashboard 테스트 139건 통과(실패·오류·skip 0). 구현 커밋 `75a7a1c`. 실제 frontend E2E에서 owner/admin의 API 오류율 상위 1과 나머지 0(분자 0/분모 10)이 정상 해석됐다. 기존 비용·건수 상위 그룹과 53개 지표·설정·설치·세션·S1-3·저장/삭제 검증도 오류 응답 없이 통과했다.
- `result.json.verifiedRatioTopN`에 실제 클라이언트 검증을 기록한다. 검증 작업 트리 부모 `ca5be37`, jar SHA-256 `0da535fa5d831655154285eadc42640bfe1cf745a1f7e735b6901b400a34c0bb`, frontend `52f7cb1`. frontend 변경은 없다. 별도 비용/인원 계산·백분위·코호트 등 나머지 조회 명세, 시나리오 실행 확장과 ingest부터의 전체 E2E는 남아 있다.


## 비용 파생 지표 상위 그룹 확장

- `cost_per_active_user`, `cost_per_user_hour`, `model_unit_price`, `subagent_cost_ratio` 4개에 상위 N·`__other__`를 확장했다. 지원 지표는 총 31개다. 현재 기간 전체에서 계산한 비율로 순위를 선택하고 나머지는 원본에서 재집계한다.
- 시계열은 기간 전체 scalar 조회로 순위 분모를 구한다. 사용자 수를 일별로 합산하면 중복 인원이 생겨 순위가 바뀌므로 전체 기간에서 중복 제거한다. 시간·비용·토큰도 기간 전체 분모를 사용한다. 기존 현재/비교 마스킹 대상의 점수 0, 같은 비교 그룹, 요청 시간 예산과 권한 범위를 유지한다.
- DB 테스트 3건을 추가했다. 사용자당 비용에서 같은 5명이 이틀 활동한 팀(기간 값 10)과 날짜마다 다른 5명이 활동한 팀(기간 값 6)을 table/timeseries 모두 정확히 선택한다. 나머지 비용 80/중복 제거 사용자 10=8, 일별 나머지 8/4를 확인했다. 시간당 비용·서브에이전트 비율의 나머지 10/50=0.2와 모델 단가 나머지 50/500=0.1도 확인했다. dashboard 테스트 142건 통과(실패·오류·skip 0). 구현 커밋 `ce5ea13`.
- 실제 frontend E2E에서 admin 클라이언트의 모델 단가 상위 10, 나머지 2.5(비용 25/토큰 10)가 정상 해석됐다. 기존 owner/admin 지표·설정·설치·세션·S1-3·저장/삭제 흐름과 다른 상위 그룹 검증도 오류 응답 없이 통과했다. `result.json.verifiedUnitPriceTopN`에 범위를 기록한다.
- 검증 작업 트리 부모 `0a52ea7`, jar SHA-256 `d6449dd5e475e8e6c4bb10c8652601826b0e8a8549fff9e6cfea475f55fab97c`, frontend `52f7cb1`. frontend는 수정하지 않았다. 고유 인원·백분위·코호트 등 나머지 조회 명세, 시나리오 실행 확장과 ingest부터의 전체 E2E는 남아 있다.


## 모델 사용자·소요 시간 상위 그룹 확장

- `model_users`, `llm_duration_ms`, `turn_duration_ms`, `llm_ttft_ms`, `gate_wait_ms` 5개에 상위 N·`__other__`를 확장했다. 지원 지표는 총 36개다. 모델 사용자는 기간 전체 distinct 사용자 수, 시간은 기간 전체 p50으로 순위를 선택한다. 시계열에서도 기간 집계를 사용한다.
- 나머지는 원본에서 사용자 중복 제거와 정확 백분위수를 다시 계산한다. TTFT는 기존 요청별 로그 우선 선택 이후 그룹을 묶으며 오류·음수·누락 제외와 최소 5명 마스킹을 유지한다. onboarding_ttfu·세션 분포 등 별도 집계는 아직 범위 밖이다.
- DB 테스트 2건을 추가했다. 일별 중앙값 합산 시 잘못 선택될 분포에서 table/timeseries 모두 기간 p50=20인 모델을 선택하고, 나머지 p50/p95/p99=1/100/100 및 일별 p50=100/1을 검증했다. 날짜마다 다른 사용자가 등장하는 모델은 전체 10명으로 선택되며 두 모델에 겹치는 나머지는 5명으로 중복 제거된다. dashboard 144건 통과(실패·오류·skip 0). 구현 커밋 `4fc48ba`.
- 실제 frontend E2E에서 admin 클라이언트가 상위 중앙값 10ms/나머지 3ms, 상위/나머지 사용자 각각 5명을 해석했다. 기존 owner/admin 지표·설정·설치·세션·S1-3·저장/삭제와 이전 상위 그룹 검증도 오류 응답 없이 통과했다. `result.json.verifiedDurationAndUsersTopN`에 검증 범위를 기록한다.
- 검증 작업 트리 부모 `899e997`, jar SHA-256 `c55b5ef497547d85e05aa9c484d6ab0699418a4c4df818954adc48eb161b0c81`, frontend `52f7cb1`. frontend는 수정하지 않았다. 나머지 조회 명세·시나리오 실행 확장·ingest부터의 전체 E2E는 남아 있다.


## 실제 OTLP 로그 → dashboard → frontend E2E

- 실제 telemetry-ingest 앱을 기존 격리 PostgreSQL·ClickHouse에 연결하고 관리자 frontend 클라이언트까지 이어지는 검증을 추가했다. 대상 행은 DB에 직접 넣지 않고 `/v1/logs`에 전송한다.
- 5개 installation이 각 1개 도구 호출을 동일 바이트로 두 번씩 전송한다. 잘못된 토큰 401, 인증 신원으로 자기신고 tenant/installation 대체, 실제 팀 as-of 보강, FINAL 조회 5행과 frontend 집계 5를 검증했다. 4명까지는 frontend cell이 masked/null이고 5명부터 공개된다.
- 기존 owner/admin smoke도 함께 통과했다. dashboard/ingest bootJar 빌드 성공. frontend는 수정하지 않았다. 이번 작업은 E2E 스크립트 변경이며 dashboard 단위 테스트 수를 늘리지 않았다.
- 남은 큰 범위는 시나리오 실행 확장(S1-3 외), 조회 명세 잔여 항목, metrics/traces와 시나리오·화면까지 확대한 수집 E2E다. 이번 검증은 로그 한 경로의 완료이며 전체 PROJ-156 완료를 뜻하지 않는다.


## 실제 metrics·traces 수집 E2E 확장

- 기존 로그 경로에 `/v1/metrics`의 비용 sum과 `/v1/traces`의 LLM 요청 스팬을 추가했다. 각 신호는 5개 installation에서 동일 바이트로 두 번씩 전송한다. 테스트 대상 행을 직접 DB에 넣지 않는다.
- metrics는 delta 1~5 USD만 합산해 15 USD이며 cumulative 999는 제외된다. traces는 TTFT 100~500ms의 p50=300/p90=500을 실제 frontend 클라이언트로 확인한다. TTFT의 허용 그룹(product/model)과 응답 백분위수(p50/p90)를 따른다.
- 세 신호 모두 잘못된 토큰 401, 인증 tenant/installation 스탬핑, 팀 as-of 보강, 4명 이하 비공개를 검증한다. 총 30회 전송 후 FINAL 저장 행은 logs 5개·metrics 10개·spans 5개이며 중복이 집계되지 않는다.
- 잔여 범위는 시나리오 실행 확장, 조회 명세 보완, 수집 데이터로 실행하는 시나리오·화면 E2E다. 이번 작업은 Claude Code의 대표 이벤트 세 종류를 검증하며 모든 벤더·이벤트 종류나 enroll/토큰 발급까지 검증한 것은 아니다.
- 최종 실제 frontend E2E 통과, 예상하지 않은 API 오류 응답 0건. 기존 owner/admin 검증도 함께 통과했다. 테스트 스크립트 변경으로 앱 JAR 변경은 없으며 frontend 작업 트리는 깨끗하다.


## 수집 데이터 기반 S1-3 결과 화면 E2E

- 기존 smoke 이후 이번 실행의 격리 ClickHouse를 비우고 OTLP 데이터만 다시 수집한다. 직접 주입 fixture가 시나리오의 이동평균 기준 기간에 섞이지 않도록 분리했다. 인증·팀 소속용 PostgreSQL fixture는 유지한다.
- 5개 installation의 api_request 비용 로그 1~5 USD와 attempt=2 한 건을 전송했다. 같은 요청 재전송 후 전체 저장 행은 25개이며 logs/metrics/spans의 신원·팀 소속도 검증한다.
- 실제 frontend 클라이언트로 S1-3 비동기 실행, 모델·팀 비용 15 USD, 재시도 비율 0.2, retry_cost 판정과 실제 결과 화면의 W1.3 $15.00을 검증한다. 증거는 `verifiedIngest.scenario` 및 `admin-ingest-scenario.png`다.
- 시나리오 실행 확장(S1-3 외), 잔여 조회 명세, 실제 수집 시계열의 급증·모델 비중 변화 및 다른 화면 흐름 검증은 남아 있다.
- 최종 E2E 통과(예상하지 않은 API 오류 0건), 스크린샷에서 비용 추세 점·팀 비용 막대/$15.00·재시도 20% 판정을 확인했다. 기존 W2.5 frontend 매핑 누락으로 재시도 프레임은 별도 표에 표시되며 해당 위젯은 미연결 상태다. frontend 코드는 변경하지 않았다.


## S1-5 실행 확장

- 실행 가능한 시나리오를 S1-3과 S1-5 두 개로 확장했다. S1-5는 input_output_ratio→compactions→compaction_reduction의 3단계이며 P2/W2.6/W2.9와 고정 실행 필터를 반환한다. 기존 lease·취소·현재 권한 재검증·결과 저장을 사용한다.
- 상세 판정식이 없는 첨부 명세를 보완해 `input_output_ratio > io_ratio_threshold`를 정보성 검토 규칙으로 명시했다. 기본 임계값 10, 동률·미달·마스킹·분모 0은 판정하지 않는다. partial 상태와 컨텍스트 첨부 여부를 관측하지 못한다는 한계를 유지한다.
- DB 통합 테스트 2건을 추가했다. 토큰 비율 20과 임계값 10/20/30, 압축 5회/감소율 0.75, 4명 마스킹·분모 0과 기본값을 검증했다. dashboard 테스트 146건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 OTLP 데이터의 S1-5 비동기 완료·비율 20·임계값 10·판정 화면 E2E와 기존 smoke가 통과했다(API 오류 0건). 화면의 W2.6 매핑 누락 및 비율 20을 표에서 2,000으로 표시하는 frontend 포맷 문제는 남아 있다. 판정 근거는 올바르게 20으로 표시된다. frontend 코드는 변경하지 않았다.


## S7-1 실행 확장

- 실행 가능한 시나리오는 S1-3·S1-5·S7-1 세 개다. S7-1은 도구 실패율·호출 수·서브에이전트 활동·비용의 일별 시계열 4단계를 제공한다. S1-5와 임계값 실행·판정 코드를 공유하며 시나리오 허용 목록을 유지한다.
- 상세 판정식이 없는 명세를 보완해 `tool_failure_rate > failure_threshold`를 정보 판정으로 채택했다. 기본 임계값 0.05, 동률·미달·마스킹·미관측은 제외한다. 태스크 완료 여부를 측정하지 못한다는 한계를 판정 근거에 포함한다.
- DB 테스트 3건 추가: 실패율 0.5와 임계값 0.1/0.5/0.9, 성공 여부 누락의 분모 제외, 소집단·미관측, 관리자 권한 회수를 검증했다. dashboard 149건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 OTLP 수집 E2E에서 S7-1 완료·실패율 0.2·호출 5건·비용 15 USD와 판정 화면을 확인했다. 기존 smoke도 통과(API 오류 0건)했고 frontend 코드는 변경하지 않았다. 스크린샷에서 비용·호출 차트, 실패율·서브에이전트 표와 관측 한계가 표시됨을 확인했다.


## S1-1 팀별 예산 실행 확장

- 실행 가능한 시나리오는 S1-1·S1-3·S1-5·S7-1 네 개다. S1-1은 예산 입력 팀만 범위로 고정하고 tokens·cost·adoption_rate 기간 table을 조회하는 3단계다. 기존 권한·lease·취소·저장 경로를 공유한다.
- 입력 예산은 조회 기간 전체의 USD 또는 백만 토큰이다. 관측량 초과 시 warning과 단위·예산·관측량·비율을 반환한다. 미관측·마스킹을 사용량 0으로 취급하지 않고 동률·미달도 경고하지 않는다. 예산 배분 적정성은 판정하지 않는다는 운영 규칙을 명시했다.
- DB 통합 테스트 2건 추가: 두 단위의 초과/동률/미달, 예산 미입력 팀 제외, 소집단·미관측, 관리자 타 팀 예산 거부·0 예산 검증. dashboard 151건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E에서 USD 15/7.5=2배, 토큰 1,050/500≈2.1배 판정과 결과 화면을 확인했다. 기존 smoke 포함 API 오류 0건. frontend 수정은 없으며 원시 비율 값 표시에 따른 2.099999… 소수 표기와 UUID/단위 원문 표시는 남아 있다.


## S4-2 실행 확장

- 실행 가능한 시나리오는 S1-1·S1-3·S1-5·S4-2·S7-1 다섯 개다. S4-2는 무산출 세션 비율·마지막 이벤트·API 오류율의 일별 시계열 3단계로 실행한다. 기존 현재 권한·lease·취소·결과 저장 경로를 공유한다.
- 상세 판정식 공백을 보완해 무산출 비율 >0을 정보성 관측 안내로 제공한다. 조회 기간의 산출만 비교하고 세션 종료·실제 대화 포기로 단정하지 않는다. 0·미관측·마스킹은 판정하지 않는다.
- DB 통합 테스트 2건 추가: 혼합 세션의 비율 0.5와 마지막 이벤트 10건, 전부 산출 있음·4명 소집단·빈 데이터의 판정 제외. dashboard 153건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): 무산출 비율 1·판정 제목과 관측 한계를 화면에서 확인했다. 테스트 로그에 같은 시각/명시 sequence를 사용해 마지막 이벤트 분류가 우연한 UUID 순서에 좌우되지 않게 했다. 연속 예산 결과도 실행 ID 렌더를 기다리도록 보강했다. frontend W2.2/W2.10 매핑 누락으로 지표는 별도 표·차트이며 frontend 코드는 변경하지 않았다.


## S1-4 캐시 사용 실행 확장

- 실행 가능한 시나리오는 S1-1·S1-3·S1-4·S1-5·S4-2·S7-1 여섯 개다. S1-4는 토큰·캐시 비율·세션별 프롬프트 수를 일별 시계열 3단계로 제공한다.
- 유효 캐시 읽기 비율 0을 정보성 안내로 제공한다. 분모 0·불완전 토큰·마스킹을 캐시 미사용으로 추정하지 않고 partial을 유지한다. 프롬프트 유사도·반복·캐싱 효과를 단정하지 않는다.
- DB 통합 테스트 2건 추가: 캐시 0/양수 안내, 세션 프롬프트 p50=2, 소집단·분모 0·불완전 토큰 제외. dashboard 155건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): 토큰 1,050·캐시 비율 0·정보 판정과 관측 한계를 실제 화면에서 확인했다. 수집 fixture에 user_prompt가 없어 프롬프트 분포는 미관측으로 표시되며 해당 값은 DB 테스트에서 검증했다. frontend 변경은 없다.


## S4-1 실행 확장

- 실행 가능한 시나리오는 S1-1·S1-3·S1-4·S1-5·S4-1·S4-2·S7-1 일곱 개다. S4-1은 프롬프트 수 분포와 토큰의 일별 시계열 2단계로 실행한다.
- 세션별 프롬프트 p50>1을 정보성 관측 안내로 제공한다. p50=1·미관측·마스킹은 제외하며 정상 다중 대화일 수 있다는 한계를 명시한다. 반복 문장이나 재시도율로 단정하지 않는다.
- DB 통합 테스트 2건 추가: p50 1/2 경계·토큰 750·소집단·미관측. dashboard 157건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 OTLP user_prompt 10건(5개 세션×2)을 추가한 E2E가 통과했다(API 오류 0건). 최종 저장 행 35개, S4-1 p50=2·토큰 1,050과 판정·W2.2 표를 화면에서 확인했다. S1-4도 프롬프트 분포가 포함된 수집 경로로 검증된다. frontend 코드는 변경하지 않았다.


## S4-8 승인 대기 실행 확장

- 여덟 번째 실행 시나리오 S4-8을 추가했다. gate_wait_ms·tool_rejections·usage_heatmap을 일별 시계열 3단계로 읽어 P2/W2.7 결과에 제공한다.
- 첨부의 상세 판정식은 비어 있다. 운영 규칙으로 일별 p90/60,000이 입력 wait_thresholds_min을 엄격히 초과하면 임계값마다 정보성 안내를 생성한다. 임계값은 정렬·중복 제거하며 빈 배열이면 조회만 수행한다. 숫자 0은 허용하고 음수는 입력 검증으로 거부한다.
- 관측 tool_gate 전체의 대기를 사용하며 Plan 전용 대기나 생산성 손실로 단정하지 않는다. p90 미관측·소집단 마스킹은 판정에서 제외한다.
- DB 통합 테스트 2건 추가: 분 단위 변환·동일 임계값 제외·3단계 결과·음수 거부·소집단·빈 데이터·빈 임계값. dashboard 159건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): OTLP 승인 대기 스팬 5건을 추가하고 최종 저장 40행·p90=120,000ms·1분 초과 판정과 W2.7 표를 확인했다. 기존 7개 실행 시나리오도 통과했다. frontend 변경은 없다. 거부 이벤트가 없어 tool_rejections는 미관측이며 usage_heatmap은 일별 사용량 차트로 제공한다.


## S4-6 action 분포 실행 확장

- 아홉 번째 실행 시나리오 S4-6을 추가했다. tool_calls는 action별 기간 합계 표, lines_of_code는 기간 합계 표로 조회하는 2단계이며 P2/W2.8을 반환한다. partial(주제 분류 불가)을 유지한다.
- 상세 판정식 공백은 공개 가능한 양수 action별 관측 건수의 정보성 안내로 보완한다. 비중·업무 주제·생산성은 추정하지 않는다. 빈 action·topN 잔여 그룹·소집단 마스킹·미관측은 안내에서 제외하며 어댑터 미분류 값 other는 그대로 표시한다.
- DB 통합 테스트 2건 추가: 읽기 10건/쓰기 5건 분리·2단계 결과·action별 소집단·빈 데이터. dashboard 161건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): 미분류 도구 action other의 호출 5건·정보성 안내·결과 화면을 확인했다. 기존 8개 시나리오도 통과했다. 코드 변경량 fixture는 없어 미관측이다. frontend 차트 축은 action 대신 value로 표시하지만 판정에는 action이 보존된다. frontend 코드는 변경하지 않았다.


## S3-4 팀별 활용 실행 확장

- 열 번째 실행 시나리오 S3-4를 추가했다. sessions·active_time·lines_of_code·adoption_rate를 팀별 기간 합계 표로 제공하는 4단계이며 P1/W1.6·W1.5를 반환한다. admin은 실행 당시 본인 팀 범위로 고정하고 워커에서 현재 권한을 재검증한다.
- 명세의 상세 격차 판정식은 비어 있다. 팀별 양수 공개 세션 수를 정보성 관측 안내로 제공한다. 팀 규모·수집 범위를 보정하지 않은 값으로 활용 우열이나 생산성 격차를 판정하지 않는다. 소집단·빈 팀·잔여 그룹·미관측·0은 안내에서 제외한다.
- DB 통합 테스트 2건 추가: 두 팀의 세션 5/10·활동 시간 300초씩·코드 변경 50줄씩·4단계 결과, 소집단 판정 제외·admin 팀 제한. dashboard 163건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): OTLP session.count 5건 추가·최종 45행 저장, 팀별 세션 5건과 채택률 5/6 및 결과 안내를 화면에서 확인했다. 기존 9개 시나리오도 통과했다. 활동 시간·코드 변경량은 수집 fixture에 없어 미관측이며 DB 통합 테스트에서 검증했다. frontend 코드는 변경하지 않았다.


## S8-4 제품별 사용 현황 실행 확장

- 열한 번째 실행 시나리오 S8-4를 추가했다. tokens는 product/model별, cost는 product별 기간 합계 표로 조회하는 2단계이며 P1/W1.4를 반환한다. partial 상태를 유지한다.
- 상세 판정식 공백은 제품별 양수 공개 비용의 정보성 안내로 보완한다. 제품 이름에서 모델 공급자를 추론하지 않으며 계약·전환 비용·벤더 종속 위험·숨겨진 그룹의 비중을 판정하지 않는다. 소집단·미관측·빈 제품·잔여 그룹·0 비용은 안내에서 제외한다.
- DB 통합 테스트 2건 추가: 제품별 비용 10/15달러·모델 토큰 750/150·2단계 결과·비용만 관측된 모델의 토큰 null 보존·소집단·빈 데이터·0 비용. dashboard 165건 통과(실패·오류·skip 0), bootJar 빌드 성공. 최초 테스트의 비용 전용 모델 누락 기대를 조회 계약에 맞춰 수정했다.
- 실제 수집 E2E 통과(API 오류 0건): claude_code 비용 15달러·모델 토큰 1,050·정보성 안내와 결과 화면을 확인했다. 기존 10개 시나리오도 통과했다. frontend는 제품별 비용에도 기존 ‘팀별 비용’ 제목과 value 축을 사용하고 모델명을 축에서 축약한다. 원본 product/model 라벨과 판정의 제품 이름은 보존되며 frontend 코드는 변경하지 않았다.


## S3-1 팀별 채택 실행 확장

- 열두 번째 실행 시나리오 S3-1을 추가했다. active_users·adoption_rate·prompts_per_session·tool_calls·mcp_connections를 팀별 기간 표로 조회하는 5단계이며 P1/W1.6·W1.1을 반환한다. partial(직군 없음→팀)을 유지한다.
- 상세 판정식 공백은 팀별 공개 양수 채택률의 정보성 관측 안내로 보완한다. 직군 정보가 없어 팀으로 집계한다는 한계를 명시하며 직군 격차·팀 우열을 판정하지 않는다. 소집단·미관측·0·빈 팀·잔여 그룹은 안내에서 제외한다.
- DB 통합 테스트 2건 추가: 활성 사용자·도구 호출·MCP 연결 각 5건, 프롬프트 p50=2, 채택률 1·5단계 결과·소집단·미관측. dashboard 167건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): 활성 사용자 5명·채택률 5/6·도구 호출 5건·프롬프트 p50=2와 정보성 안내를 화면에서 확인했다. 기존 11개 시나리오도 통과했다. MCP 연결은 수집 fixture에 없어 미관측이며 DB 통합 테스트에서 검증했다. frontend는 S3-1 채택률을 W1.1에 연결하고 W1.6 강조는 빈 안내를 표시한다. 원시 비율의 긴 소수 표기도 남아 있으며 frontend 코드는 변경하지 않았다.


## S3-5 고급 기능 관측 실행 확장

- 열세 번째 실행 시나리오 S3-5를 추가했다. mcp_connections·subagent_cost_ratio·command_prompt_ratio·tool_failure_rate를 일별 시계열로 조회하는 4단계이며 P2/W2.8·W2.10을 반환한다. partial(스킬·플러그인 없음)을 유지한다.
- 상세 판정식 공백은 양수 서브에이전트 비용 비율의 정보성 관측 안내로 보완한다. query_source 메트릭의 비용 비율이며 스킬·플러그인 사용률이나 숙련도로 해석하지 않는다. 기존 고정 임계값 판정기를 사용해 소집단·미관측·분모 0·비율 0을 안내에서 제외한다.
- DB 통합 테스트 2건 추가: 서브에이전트 비용 비율 0.25·MCP 연결 5건·도구 실패율 0.5·4단계 결과, 소집단·빈 데이터·분모 0·main 전용 데이터. dashboard 169건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): cost.usage의 query_source=subagent를 수집해 delta 비용 15/15달러·비율 1과 도구 실패율 0.2·정보성 안내를 화면에서 확인했다. 기존 12개 시나리오도 통과했다. MCP는 수집 fixture에 없어 미관측이며 DB 통합 테스트로 검증했다. frontend는 도구 실패율을 W2.8에 연결해 W2.10 강조는 빈 안내로 남는다. frontend 코드는 변경하지 않았다.


## S2-1 시간대별 사용 실행 확장

- 열네 번째 실행 시나리오 S2-1을 추가했다. usage_heatmap은 현지 weekday/hour별 기간 합계 표, rate_limit_events·session_last_event는 일별 시계열로 조회하는 3단계이며 P2/W2.3을 반환한다.
- 히트맵은 limit을 생략해 기존 조회의 기본 168구간을 사용한다. 명시 limit은 최대 100이므로 최초 실행 실패 후 계약에 맞춰 수정했다. 빈 시간 구간을 0으로 생성하지 않는다.
- 상세 판정식 공백은 양수 Rate Limit 이벤트 건수의 정보성 안내로 보완한다. 오전·오후 변동의 원인이나 작업 중단을 확정하지 않는다. 소집단·미관측·0은 안내에서 제외한다.
- DB 통합 테스트 2건 추가: 168개 구간 각 5건, 현지 요일·시간, 서울 날짜 경계의 제한 이벤트 5건·3단계 결과, 소집단·빈 데이터·제한 없음. dashboard 171건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): 서울 시간대 weekday/hour 라벨·프롬프트 총 10건·Rate Limit 판정 0건과 결과 화면을 확인했다. 기존 13개 시나리오도 통과했다. 429 양수 판정은 DB 통합 테스트로 검증했다. 현재 frontend는 시간대 표를 일반 막대 차트/value 축으로 표시하며 히트맵 렌더링은 하지 않는다. backend 라벨은 보존하고 frontend 코드는 변경하지 않았다.


## S8-1 경영 보고 실행 확장

- 열다섯 번째 실행 시나리오 S8-1을 추가했다. sessions·tokens·active_time·lines_of_code·commits·pull_requests·cost_per_active_user를 일별 시계열로 조회하는 7단계이며 P1/W1.1·W1.5·W1.2를 반환한다. partial(재무 조인 없음)을 유지한다.
- 상세 판정식 공백은 양수 세션 수의 정보성 관측 안내로 보완한다. 재무 데이터가 연결되지 않은 사용량이며 ROI·절감액·생산성 향상을 계산하거나 보장하지 않는다. 소집단·미관측·0은 안내에서 제외한다.
- DB 통합 테스트 2건 추가: 세션 5건·토큰 750·활동 시간 300초·코드 50줄·커밋 10건·PR 5건·활성 사용자당 비용 3달러·7단계 결과, 소집단·미관측·0. dashboard 173건 통과(실패·오류·skip 0), bootJar 빌드 성공.
- 실제 수집 E2E 통과(API 오류 0건): 세션 5건·토큰 1,050·활성 사용자당 비용 3달러·정보성 안내를 화면에서 확인했다. 기존 14개 시나리오도 통과했다. 최초 E2E의 시계열 key 기대를 시간값 계약에 맞춰 USD 단위·값 검증으로 수정했다. 활동 시간·코드·커밋·PR은 수집 fixture에 없어 미관측이며 DB 통합 테스트로 검증했다. frontend의 W1.2 강조 연결은 누락되어 있고 frontend 코드는 변경하지 않았다.

## S3-2 익명 사용 집중도 실행

- `usage_concentration`은 기간 분포로 조회하여 상위 10% 점유율과 익명 로렌츠 곡선을 함께 반환한다. `tool_calls`·`tokens`는 일 시계열로 제공한다.
- 명세에 위험 임계값이 없어 공개 가능한 양수 점유율에 정보 판정만 제공한다. 개인 식별, 생산성 및 의존 위험을 추정하지 않는다.
- 실제 DB 테스트에서 비균등 분포(상위 점유율 1/3), 익명 곡선, 설치 ID 비노출, 4인 소집단의 좌표·길이 마스킹, 미관측·영 분모의 판정 제외를 확인했다. dashboard 테스트 175건 통과.
- 실제 인증 OTLP 수집→admin S3-2 실행→frontend 결과 화면 E2E 통과. 5인 균등 사용에서 상위 점유율 0.2, 곡선 인구·사용 비율 0~1, 설치 ID 비노출을 검증했다. 실행 가능한 시나리오는 16개다.
- frontend 소스는 변경하지 않았다. 현재 로렌츠 좌표는 일반 표로 표시되며 `usage_concentration`의 W2.5 매핑이 없어 해당 위젯은 빈 안내로 남는다. 정보 판정과 요약·곡선 표는 표시된다.
- 증거: `build/e2e/auth-settings/result.json`, `admin-ingest-concentration-scenario.png`. 전체 PROJ-156 수용 완료를 뜻하지 않는다.

## S1-6 모델·effort 비용 실행

- 비용은 `source=metrics`로 고정해 모델·effort와 모델·speed 두 표를 반환한다. 최대 2개 그룹 차원 계약을 지키며 두 표는 같은 비용의 다른 분류이므로 합산하지 않는다. 토큰·세션 일 시계열까지 총 4단계다.
- 공개 가능한 양수 비용에는 정보 판정을 제공한다. 명세의 판정 수식이 비어 있어 작업 난이도·품질·대체 비용 없이 낭비나 절감액을 추정하지 않는다.
- dashboard 테스트 177건 통과. 실제 DB에서 두 분류, delta 원천 선택과 cumulative·logs 비용 제외, 소집단·미관측·영 비용의 판정 제외를 검증했다.
- 실제 OTLP 비용 메트릭에 effort=high·speed=fast를 넣어 수집→admin 실행→frontend 결과 UI E2E를 통과했다. 모델·effort와 모델·speed 각각 $15이고 누적값 999는 제외됐다. 실행 가능한 시나리오는 17개다.
- frontend 소스는 변경하지 않았다. 비용 차트 제목은 `팀별 비용`이며 두 분류가 같은 모델 이름의 막대로 표시되어 차트에서 effort/speed 축을 구분하기 어렵다. 결과 필드의 라벨과 정보 판정은 두 축 및 중복 합산 금지를 제공한다.
- 증거: `build/e2e/auth-settings/result.json`, `admin-ingest-effort-scenario.png`. 전체 PROJ-156 수용 완료를 뜻하지 않는다.

## S2-2 Rate Limit 실행

- P3 시나리오로 owner와 10~500자 감사 사유를 요구한다. 시작 시 `scenario_run` 감사를 기록하며 worker는 각 조회 전 현재 역할·팀 범위를 재검증한다.
- `rate_limit_events`·`tokens`·`api_retry_attempts`를 일 시계열로 제공한다. 공개 가능한 양수 제한 이벤트에 정보 판정을 제공하되 retries_exhausted가 없어 상시 도달·작업 중단·한도 소진을 확정하지 않는다.
- dashboard 테스트 179건 통과. 실제 DB에서 429 이벤트 집계, 토큰 값, 감사 누락·admin 거부, 실행 전 역할 변경 실패, 소집단·미관측·정상 응답의 판정 제외를 검증했다.
- frontend의 일반 시나리오 실행 폼/API 래퍼에는 감사 사유 전달이 없어 P3 실행 버튼만으로는 시작할 수 없다. E2E는 실제 owner 로그인 후 frontend 공통 `request` 클라이언트의 감사 사유 인자를 사용한다. backend의 감사 요구를 완화하지 않으며 frontend 소스도 변경하지 않았다.
- 실제 OTLP 수집→owner 웹 로그인→감사 사유 포함 실행→P3 결과 UI E2E 통과. fixture의 제한 이벤트는 0건이며 판정도 0건, 토큰 1050·재시도 비율 0.2와 감사 행 1건을 확인했다. 양수 429 판정은 실제 DB 테스트로 검증했다. 실행 가능한 시나리오는 18개다.
- 현재 frontend는 `rate_limit_events`를 W3.3에 매핑하지 않아 빈 위젯 안내가 남는다. 일반 차트의 제한 이벤트 0과 토큰·재시도 결과 표시는 확인했다.
- 증거: `build/e2e/auth-settings/result.json`, `owner-ingest-pressure-scenario.png`. 전체 PROJ-156 수용 완료를 뜻하지 않는다.

## S6-5 리트라이 스톰 관측 실행

- owner와 감사 사유를 요구하는 P3 실행에 `api_retry_attempts`·`cost` 일 시계열을 연결했다. 양수 재시도 비율에 정보 판정을 제공한다.
- Q12와 같이 전체 관측 llm_call을 분모로 삼으므로 attempt 미수집 호출도 분모에 포함한다. retries_exhausted가 없어 스톰·고갈을 확정하지 않으며 전체 비용을 재시도 추가 비용으로 해석하지 않는다.
- dashboard 테스트 181건 통과. 실제 DB에서 1/3 재시도 비율과 비용 $15, owner 감사, admin·감사 누락 거부, 소집단·미관측·첫 시도의 판정 제외를 검증했다.
- 실제 OTLP 수집→owner 로그인→감사 실행→P3 결과 화면 E2E 통과. 재시도 비율 0.2(1/5), 비용 $15, 정보 판정 1건과 감사 행 1건을 확인했다. 실행 가능한 시나리오는 19개다.
- frontend 소스는 변경하지 않았다. S2-2와 같이 일반 실행 폼은 감사 사유를 전달하지 못하므로 공통 `request` 클라이언트로 시작했다. 결과 UI의 W3.1 재시도 표·비용 차트·해석 한계 안내를 확인했다.
- 증거: `build/e2e/auth-settings/result.json`, `owner-ingest-retry-scenario.png`. 전체 PROJ-156 수용 완료를 뜻하지 않는다.

## S7-3 정책·에스컬레이션 관측 실행

- owner·감사 사유 필수 P3 실행에 `tool_rejections`·`hook_blocking`·`gate_wait_ms` 일 시계열을 연결했다. 양수 도구 거절 수에 정보 판정을 제공하되 실제 정책 위반·악의적 사용·에스컬레이션을 단정하지 않는다.
- 거절은 `tool_decision`, 대기는 `tool_gate`, 훅 차단은 span의 `num_blocking` 원천을 사용한다. 서로 다른 이벤트를 같은 것으로 취급하지 않는다.
- dashboard 테스트 183건 통과. 실제 DB에서 거절 5건·훅 차단 10건·대기 120초, 감사 기록, admin·감사 누락 거부, 소집단·미관측·승인의 판정 제외를 검증했다.
- 실제 OTLP 수집→owner 로그인→감사 실행→P3 결과 UI E2E 통과. gate_wait p50/p90 120000ms, 판정 0건 및 감사 행 1건을 확인했다. fixture에는 tool_decision·hook span이 없어 거절·차단 UI는 미관측 안내로 표시된다. 양수 결과는 실제 DB 테스트에서 검증했다. 실행 가능한 시나리오는 20개다.
- frontend 소스는 변경하지 않았다. 일반 실행 폼의 감사 사유 전달은 여전히 없어 공통 `request` 클라이언트로 시작했다. 결과 UI의 W3.3 도구 거절 카드와 대기 시간 표를 확인했다.
- 증거: `build/e2e/auth-settings/result.json`, `owner-ingest-policy-scenario.png`. 전체 PROJ-156 수용 완료를 뜻하지 않는다.

## S5-2 외부 접근 관측 실행

- owner·감사 사유 필수 P3 실행에 `mcp_connections`·`read_tool_density` 일 시계열을 연결했다. 양수 MCP 연결 상태 이벤트에는 정보 판정을 제공하며 실제 외부 전송·목적지·승인을 확인하지 못하므로 유출 탐지로 해석하지 않는다.
- dashboard 테스트 185건 통과. 실제 DB에서 MCP 이벤트 5건·읽기 밀도 p50=2, 감사 기록, admin·감사 누락 거부, 소집단·미관측 및 읽기만 관측된 경우의 판정 제외를 검증했다.
- 실제 OTLP 수집→owner 로그인→감사 실행→P3 결과 UI E2E 통과. 읽기 밀도 p50/p90=0, MCP 미관측, 판정 0건 및 감사 행 1건을 확인했다. MCP 양수 결과는 실제 DB 테스트로 검증했다. 실행 가능한 시나리오는 21개다.
- frontend 소스는 변경하지 않았다. 일반 실행 폼의 감사 사유 전달은 없어 공통 `request` 클라이언트로 시작했다. W3.2 MCP 카드와 일반 읽기 밀도 표를 확인했다.
- 증거: `build/e2e/auth-settings/result.json`, `owner-ingest-external-scenario.png`. 전체 PROJ-156 수용 완료를 뜻하지 않는다.

## S5-7 즉시 승인 관측 실행

- `rubber_stamp_ratio`, `auto_approval_ratio`, `pull_requests`를 3단계로 조회한다. 카탈로그의 `threshold_ms`(기본 2000, 1~60000)를 rubber_stamp_ratio 쿼리에 전달한다. 유효 대기 시간이 있는 사용자 accept만 분모이며 임계값과 같은 시간은 분자에서 제외한다.
- 비율이 0보다 클 때 info 판정과 실제 `threshold_ms`를 제공한다. 검증 생략·실제 반출·PR과의 인과관계는 확정하지 않는다. owner와 감사 사유를 요구하고 실행 중 현재 권한을 재검증한다.
- dashboard 테스트 187건 통과. 기본 2000ms에서 1/3, 2001ms에서 2/3, 잘못된 범위 거부, 감사 누락·admin 거부, 소집단·결측·임계값 이상·거절의 판정 제외를 검증했다.
- 실제 OTLP 수집→owner 로그인→감사 실행→P3 결과 UI E2E 통과. `source=user_temporary`인 승인 대기 120000ms 5건에 threshold_ms=60000을 적용해 비율 0(분자 0/분모 5), 판정 0건, 감사 행 1건을 확인했다. 실행 가능한 시나리오는 22개다.
- 초기 E2E 입력은 승인 주체가 빠져 분모가 없었으므로 0 기대값 검증이 실패했다. 어댑터 계약대로 source를 명시해 수정했다. 양수 및 임계값 변경 결과는 실제 DB 테스트로 검증했다.
- frontend 소스 변경 없음. 일반 P3 실행 폼의 감사 사유 전달은 아직 없어 owner 로그인 후 공통 API 클라이언트로 감사 헤더를 전달했다. 결과 화면의 rubber_stamp_ratio는 일반 표이며 자동 승인·PR은 이번 입력에서 미관측이다.

## S6-4 선택 모델 레이턴시 관측 실행

- llm_duration_ms·llm_ttft_ms·api_error_rate·active_users를 4단계로 조회한다. 선택 models를 모든 쿼리와 applied_filters에 전달한다. 생략·빈 배열은 전체 모델이며 쿼리 계약에 맞게 최대 100개·모델명 200자를 큐에 넣기 전에 검증한다.
- 첫 토큰 지연 p90이 양수일 때 info 관측과 p90_ms를 제공한다. SLA나 장애 기준이 없어 장애·품질 저하·원인을 확정하지 않는다. P3 owner·감사 사유와 실행 중 권한 재검증을 유지한다.
- dashboard 테스트 189건 통과. 모델 제외, 필터 전달, 지연 p90=100ms·활성 사용자 5, 잘못된 모델 범위·감사 누락·admin 거부, 소집단·미관측 모델·빈 데이터·0ms에서 판정 제외를 검증했다.
- 실제 OTLP 수집→owner 로그인→감사 실행→P3 결과 UI E2E 통과. 선택한 ingest 모델의 첫 토큰 지연 p50=300ms/p90=500ms·활성 사용자 5, 모델 필터 UI 및 감사 행 1건을 확인했다. 실행 가능한 시나리오는 23개다.
- frontend 소스 변경 없음. 일반 P3 폼의 감사 사유 전달은 여전히 없어 공통 API 클라이언트로 감사 헤더를 전달했다. 첫 토큰 지연은 일반 표이며, 선택 모델의 llm_call 원본이 없는 이번 입력에서는 전체 응답 시간·에러율이 미관측으로 표시된다.

## S7-2 읽기 밀도 임계값 실행

- read_tool_density·mcp_connections·auto_approval_ratio를 3단계로 조회한다. 세션별 read·search·fetch 호출 수의 p90이 density_threshold(기본 10, 최소 0)를 초과하면 info 판정을 제공한다. 같은 값은 제외하며 근거에 p90_calls_per_session과 threshold를 기록한다.
- 접근 데이터의 내용·권한·외부 전송을 확인하지 않으므로 부적절한 접근이나 유출을 확정하지 않는다. owner·감사 사유와 실행 중 현재 권한 검사를 유지한다.
- dashboard 테스트 191건 통과. 세션당 읽기 호출 3건에서 임계값 2.5 초과 및 3·10 미초과, 음수 거부, 감사 누락·admin 거부, 소집단·빈 데이터·쓰기 전용 세션 제외를 검증했다.
- 실제 OTLP 수집→owner 로그인→감사 실행→P3 결과 UI E2E 통과. 정규화 action=other인 기존 입력에서 읽기 밀도 p50/p90=0, threshold=0, 판정 0건 및 감사 행 1건을 확인했다. 양수 밀도는 DB 통합 테스트로 검증했다. 실행 가능한 시나리오는 24개다.
- frontend 소스 변경 없음. 일반 P3 폼의 감사 사유 전달은 여전히 없어 공통 API 클라이언트를 사용했다. 읽기 밀도는 일반 표로 표시되며 W3.3 매핑과 MCP·자동 승인 미관측 상태가 남는다.

## S7-4 감사 거버넌스 수집 관측 실행

- telemetry_coverage·hook_executions·mcp_connections를 3단계로 조회한다. 커버리지가 양수이면 info 관측을 제공한다. 조회 기간의 관측 설치 수와 현재 활성 설치 수를 비교하며 인증 감사·리텐션 삭제 실행은 관측하지 않는다. 카탈로그의 partial 상태를 유지한다.
- owner·감사 사유와 실행 중 현재 권한 검사를 유지한다. 감사 완전성이나 보존 정책 준수를 확정하지 않는다.
- dashboard 테스트 193건 통과. 활성 설치 6개 중 관측 5개(5/6), 훅·MCP 각 5건, 감사 누락·admin 거부, 소집단·빈 데이터·활성 분모 0의 판정 제외를 검증했다.
- 실제 OTLP 수집→owner 로그인→감사 실행→P3 결과 UI E2E 통과. 관측 설치 5/활성 설치 5로 커버리지 1, info 1건, 감사 행 1건을 확인했다. 실행 가능한 시나리오는 25개다.
- frontend 소스 변경 없음. 일반 P3 폼의 감사 사유 전달은 여전히 없어 공통 API 클라이언트를 사용했다. 커버리지는 일반 표의 100으로 표시되며 W3.4 매핑은 비어 있다. 이번 입력의 훅·MCP는 미관측이며 양수는 DB 통합 테스트로 검증했다.

## S4-5 명령 필터·템플릿 활용 관측 실행

- command_prompt_ratio·prompts_per_session을 2단계로 조회한다. command_names를 명령 프롬프트 비율 쿼리에 전달하며 정확한 명령명 일치만 분자에 포함하고 전체 유효 프롬프트 분모는 유지한다. 생략·빈 배열은 모든 명령이다.
- QRY의 command_prompt_ratio에 command_names 선택 파라미터를 추가하고 지표 카탈로그에도 공개한다. 최대 100개·이름 1~200자이며 ClickHouse 바인딩 배열을 사용한다. 다른 지표에는 전달하지 않는다.
- 양수 비율은 info 관측이며 스킬·템플릿 내용·생산성 향상을 추정하지 않는다. 카탈로그 partial 상태를 유지한다.
- dashboard 테스트 195건 통과. 선택 비율 1/3·전체 비율 2/3·분모 15 유지, 따옴표와 역슬래시 이름, 대소문자 불일치, 파라미터 타입·범위 거부, 소집단·미관측 제외를 검증했다.
- 실제 OTLP user_prompt에 /review·/plan을 각각 5건 수집한 뒤 admin 프런트엔드 클라이언트로 /review를 선택해 E2E 통과. 비율 0.5(5/10), 세션당 프롬프트 p50/p90=2와 팀 범위 결과 UI를 확인했다. 실행 가능한 시나리오는 26개다.
- frontend 소스 변경 없음. 일반 표에서 명령 비율이 50으로 표시되고 좁은 표 헤더가 잘리며 W2.8 매핑은 비어 있다. 스킬 데이터 미수집과 기존 P3 폼 감사 사유 전달 문제는 별도 잔여다.

## S8-5 도구 통합 검토 지표 실행

- active_users를 제품별 기간 표로, cost_per_active_user·tool_calls를 조회 범위 전체의 일 시계열로 제공한다. 후자의 제품 차원은 현재 지표 계약에서 허용하지 않으므로 제품별 수치로 표시하지 않는다.
- 제품별 활성 사용자가 양수이면 info 관측을 제공한다. 제품 간 동일 사용자 중복 가능성을 명시하며 사용자 수를 합산하거나 통합 절감액을 추정하지 않는다. partial 상태를 유지한다.
- dashboard 테스트 197건 통과. 동일 사용자 5명이 두 제품을 사용한 경우 각 5명·전체 사용자당 비용 3달러·도구 호출 5건, 소집단 제품과 미관측 제외를 검증했다.
- 실제 OTLP 수집→admin 실행→P1 결과 UI E2E 통과. claude_code 활성 사용자 5, 전체 사용자당 비용 3달러·도구 호출 5건 및 팀 범위를 확인했다. 실행 가능한 시나리오는 27개다.
- frontend 소스 변경 없음. 활성 사용자 차트 축이 product 대신 value로 표시되고 W1.4 매핑이 비어 있으며 제품명은 판정 근거에서 확인된다. 중복 사용자 수·통합 절감액은 미구현이다.

## S6-1 품질 피드백 관측 실행

- refusals·edit_acceptance_rate·prompts_per_session을 3단계로 조회한다. models를 모든 지표와 applied_filters에 적용하며 생략·빈 배열은 전체 모델이다. 모델명이 없는 원본은 모델 선택 시 제외된다.
- P2이지만 refusals를 포함하므로 owner·감사 사유를 요구하며 실행 중 현재 권한을 재검증한다. 거부 응답이 양수일 때 info 관측을 제공하고 설문·품질 점수·거부 적절성·만족도를 추정하지 않는다.
- dashboard 테스트 199건 통과. 선택 모델 거부 5건, 모델명 없는 프롬프트 제외, 감사 누락·admin·너무 긴 모델 거부, 소집단·다른 모델·정상 종료·미관측 판정 제외를 검증했다.
- 실제 OTLP 수집→owner 감사 실행→P2 결과 UI E2E 통과. 전체 모델에서 세션당 프롬프트 p50/p90=2, 거부 판정 0건, 감사 행 1건을 확인했다. 양수 거부는 DB 통합 테스트로 검증했다. 실행 가능한 시나리오는 28개다.
- frontend 소스 변경 없음. 일반 폼의 감사 사유 전달 부족은 P3뿐 아니라 owner 감사가 필요한 S6-1에도 적용된다. E2E는 공통 API 클라이언트로 감사 헤더를 전달했다. 이번 입력의 거부·편집 수락은 미관측이며 설문·품질 점수는 미지원이다.

## S2-3 요일·스프린트 날짜 관측 실행

- usage_heatmap을 weekday·hour 표로, sessions·llm_duration_ms·rate_limit_events를 일 시계열로 조회한다. sprint_dates에 포함된 현지 날짜에 세션이 양수이면 info 관측을 제공한다.
- 날짜 연결은 실행 tz로 일 버킷을 변환해 비교한다. 스프린트 구간·반복 주기·업무 강도·인과 효과는 추정하지 않는다. 빈 날짜 목록이나 관측 없는 날짜에는 판정을 만들지 않는다.
- dashboard 테스트 201건 통과. 동일 이벤트의 서울 9월 2일·UTC 9월 1일 연결, 세션 5건, 잘못된 날짜 거부, 다른 날짜·소집단·빈 선택·미관측 제외를 검증했다.
- 실제 OTLP 수집→admin 실행→P2 결과 UI E2E 통과. 서울 현지 sprint_date=2026-09-12의 세션 5건과 날짜·시간대 근거를 확인했다. 실행 가능한 시나리오는 29개다.
- frontend 소스 변경 없음. usage_heatmap은 전용 히트맵 대신 일반 막대 차트이며 W2.4 매핑은 비어 있다. 이번 입력은 전체 응답 시간이 미관측이고 스프린트 구간·주기성 분석은 제공하지 않는다.


## 2026-09-12 남은 실행 계획 대조

카탈로그의 availability는 데이터 가용성이고 실행 구현 여부와 별개다. 현재 46개 중 실행 경로 29개, 추가 실행 구현 대상 13개, 명세상 실행 불가 4개다. 실행 경로 수를 기능 완성률로 해석하지 않는다.

| 추가 구현 대상 | 필요한 처리 |
|---|---|
| S1-2 모델 티어 미스매치 | premium_model_patterns의 분류 의미와 분류별 집계·판정 |
| S1-7 유휴 라이선스·좀비 시트 | as_of·inactive_days 기준 기간 고정 및 유휴 설치 연결 |
| S3-3 온보딩 정착 | 코호트 기간과 관측 기간 분리, 잔존율 파라미터 연결 |
| S4-3 코드 수용률·revert | language 필터 연결; revert 미관측 한계 유지 |
| S4-4 교육 효과 전후 비교 | pivot_date·window_weeks로 전후 기간 분리 및 비교 |
| S5-4 섀도우 AI | vendor_account_mismatch의 워커 감사 경로 및 불일치 관측 |
| S5-5 인젝션·탈옥 시도 | probe_window_min·probe_count를 실제 시간 창 집계에 적용 |
| S5-6 정책 위반 용도 | from/to 안의 pivot_date를 기준으로 관측 비교 |
| S6-3 모델 버전 드리프트 | 모델 조건과 pivot_date 전후 관측 기간 연결 |
| S8-2 예산 수립·비용 예측 | growth_model별 예측·입력 데이터 충분성 검증 |
| S8-3 신규 모델 A/B | model_a·model_b의 독립 집계와 비교 |
| S8-6 정책 실효성 | pivot_date·window_weeks 전후 비교; 각 조회의 기간 제한 유지 |
| S8-7 챔피언 프로그램 | pivot_date·팀 범위 적용 및 관측 비교 |

명세상 실행 불가 항목은 S4-7(외부 DORA), S5-1(원문 미취급), S5-3(원문), S6-2(외부 신고 채널)다. 해당 원천 없이 성공 결과를 생성하지 않는다.

다음 기능 구현은 S4-4·S8-6에서 재사용할 현지 날짜 기준 전후 기간 처리부터 진행한다. window_weeks=52일 때 전체 범위는 728일이므로 기존 366일 단일 조회에 합치지 않고 두 기간을 독립 조회해야 한다. DST 경계·pivot 중복 제외·소집단 마스킹·미관측 비교를 검증한다.


## 2026-09-12 전체 회귀 재검증

- 검증 코드: backend `855b46885644e52229ae0e57f3bc32f2a6449bc3`, frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`. 이번 변경은 진행 문서만 수정한다.
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew build --rerun-tasks`: 58개 task 실제 실행, 전체 테스트 1,008건, 실패·오류·skip 0건.
- 모듈별 테스트: dashboard 201, enrollment 앱 233, ingest 앱 39, enrollment 저장소 67, security 67, adapter 145, collector 166, enricher 39, telemetry 저장소 51.
- 새로 빌드한 JAR로 `node scripts/e2e/dashboard-auth-settings.mjs` 통과. 53개 지표 frontend 클라이언트 검증 및 실제 OTLP 수집과 연결된 29개 시나리오 검증을 다시 수행했다. `unexpectedOrUnimplementedResponses=[]`이며 frontend 워킹 트리는 깨끗하다.
- dashboard JAR SHA-256: `ef5df03c3270a2b7e9f865b296b342162176b4aa647364bae379eee40d1830cc`; ingest JAR SHA-256: `579ab0706ef54f91d25616999b25adbb3c03cf8e91620bd76a4b45be058f47b3`.
- 실행 증거는 `build/e2e/auth-settings/result.json`, 전체 빌드 로그는 `/tmp/proj156-full-build.log`, E2E 로그는 `/tmp/proj156-full-e2e.log`에 있다. 로컬 생성물은 다음 실행으로 덮어쓸 수 있다.
- 이 통과는 현재 구현 경로의 회귀 검증이다. 추가 실행 대상 13개·QRY 보완·frontend 감사 사유 폼과 전용 위젯의 미완료 범위는 유지한다. owner 감사 실행은 기존 frontend 공통 클라이언트 경로로 검증했으며 일반 실행 버튼의 감사 사유 전달까지 통과한 것은 아니다.


## S4-4·S8-6 현지 날짜 전후 비교

- S4-4 교육 효과 전후 비교(4단계)와 S8-6 정책 실효성(3단계)의 실행을 연결했다. 각 단계는 한 지표의 전후 기간 독립 집계이며, 기존 QRY 비교 프레임과 소집단 마스킹을 재사용한다.
- pivot_date 현지 자정을 경계로 이전 `[pivot-window_weeks, pivot)`와 이후 `[pivot, pivot+window_weeks)`를 고정한다. 기본 4주·범위 1~52주이며 DST를 보존한다. 52주 입력의 728일 전체를 단일 조회하지 않고 각 364일 기간을 조회한다.
- resolved_from/to는 이후 기간이다. applied_filters.compare_from/compare_to는 이전 기간이며 observation_complete는 실행 시 이후 기간 종료 여부다. 입력 원문과 워커 실행 범위는 큐에 저장되어 재시작에도 유지된다.
- 지표별 기간 전체 표의 일반 필드는 이후 값, `_compare` 필드는 이전 값이다. 기존 양쪽 합동 마스킹을 적용하고, 한쪽 소집단은 두 값 모두 숨긴다. 미관측은 null이며 0으로 대체하지 않는다.
- 양쪽 값이 유효하고 기간이 종료된 경우에만 차이를 observed_period_change info로 제공한다. 기본 value, 프롬프트·승인 대기는 p50 차이다. 동일 값은 판정하지 않고 0 기준값도 차이만 반환한다. 교육·정책 인과 효과나 개선 여부는 판단하지 않는다.
- S4-4는 owner 또는 현재 팀 범위 admin, S8-6은 owner + 감사 사유가 필요하다. 각 지표 조회와 결과 저장 직전에 현재 권한을 재검증한다.
- 실행 경로는 31개이며 추가 실행 대상은 11개, 명세상 unavailable 4개는 유지한다. 앞 절의 29/13 대조는 이번 구현 전 기록이다.


### S4-4·S8-6 검증 결과

- dashboard 테스트 206건, 실패·오류·skip 0건. DST 현지 자정, 52주 독립 조회, 기준일·종료 경계, 증가·감소·0 기준값·동일 값, 소집단 합동 마스킹, 미관측·미완료 기간, owner 감사와 admin 거부를 검증했다.
- 구현 커밋 `97637c9`의 JAR와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`로 실제 OTLP 수집 E2E가 통과했다. `unexpectedOrUnimplementedResponses=[]`이며 총 31개 연결 시나리오를 검증한다.
- `verifiedIngest.trainingComparisonScenario`: S4-4 run `8c5eeb15-b8cc-4ce0-ae34-5479db71d352`, admin 현재 팀 범위, 프롬프트 p50=2·이전 값 null. `admin-ingest-training-comparison.png`에서 표와 결과 패널을 확인했다.
- `verifiedIngest.policyComparisonScenario`: S8-6 run `409f3caf-cec7-47db-a3b1-5ad0424cc3a2`, owner 감사 기록 1건, 승인 대기 p50=120000ms·이전 값 null. `owner-ingest-policy-comparison.png`에서 표와 결과 패널을 확인했다.
- 실제 수집 fixture는 당일 관측이므로 이전 기간은 미관측, 이후 기간은 미완료다. 변화 판정이 없는 것을 검증하며 양쪽 양수·증감 판정은 DB 통합 테스트가 담당한다. 정책 거절·훅 및 교육 편집·MCP 원천도 이번 ingest fixture에는 없다.
- frontend 비교 선택기는 현재 '없음'으로 표시되지만 표에는 `_compare` 필드가 나온다. 비교 기간·observation_complete의 전용 안내와 S4-4 W2.0 강조 연결은 후속 frontend 작업이다. S8-6 시작은 owner 로그인 후 공통 API 클라이언트의 감사 사유 경로이며 일반 실행 폼은 아직 사유를 보내지 않는다.
- 로그: `/tmp/proj156-comparison-test.log`, `/tmp/proj156-comparison-e2e.log`. 전체 백엔드 빌드 1,008건 기록은 이번 구현 이전 검증이며 이번 변경의 검증 범위는 dashboard 206건과 E2E다.


## S5-6 정책 용도 전후 관측

- S5-6 실행을 연결했다. from/to 범위 내부의 pivot_date 현지 자정으로 `[from,pivot)`와 `[pivot,to)`를 나눈다. 기준일이 범위 밖이거나 양끝과 같으면 400이다. 기존 전체 기간 366일 제한은 유지한다.
- tool_rejections의 config·hook 결정만 전후 기간 전체 표로 조회한다. 한 단계 실행이며 이후 value와 이전 value_compare를 반환한다. 원래 params.from/to는 보존하고 resolved_from/to는 이후 기간, applied_filters.compare_from/compare_to는 이전 기간이다.
- QRY tool_rejections의 decided_by 배열 파라미터를 추가했다. config/hook/user 중 중복 없이 최대 3개이며 생략·빈 배열은 기존 전체 주체 집계다. SQL 명명 파라미터로 전달하며 잘못된 타입·주체·중복을 거부한다.
- owner와 감사 사유가 필수다. 양쪽 소집단은 기존 비교 마스킹으로 처리한다. 미관측·미완료 기간은 변화 판정을 만들지 않는다. 양쪽 유효 값의 차이만 W3.3 info로 제공하며 기간 길이 차이와 인과 효과·발생률 해석의 한계를 명시한다.
- 실행 경로 32개, 추가 실행 대상 10개, 명세상 unavailable 4개다.


### S5-6 검증 결과

- dashboard 테스트 210건, 실패·오류·skip 0건. 기준일 내부 조건과 원본 범위 보존, config/hook 전후 5→10건, user·주체 누락 제외, 감사 사유·owner 인가, 소집단·미관측, QRY 빈 선택 호환성과 잘못된 주체·타입·중복 거부를 검증했다.
- 구현 `886ff22`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E가 통과했다. `unexpectedOrUnimplementedResponses=[]`, 연결 시나리오 32개다.
- `verifiedIngest.purposeScenario`: run `c33ee76e-64a7-4be9-bf59-c041841484d2`, owner 감사 기록 1건, 진행 1/1 완료. `owner-ingest-purpose-scenario.png`에서 W3.3 미관측 카드와 판정 없음 패널을 확인했다.
- 실제 ingest fixture에는 tool_decision 거절 원천이 없어 빈 프레임을 검증한다. 전후 양수 건수와 결정 주체 필터의 긍정 사례는 DB 통합 테스트가 담당한다. 이벤트 부재를 거절 0건이나 정책 준수로 판정하지 않는다.
- 일반 frontend 실행 폼은 여전히 감사 사유를 보내지 않는다. 이번 검증은 owner 로그인 후 공통 클라이언트의 감사 사유 전달 경로다. 상단 비교 선택기와 이전 기간의 전용 안내도 미연결 상태다.
- 로그: `/tmp/proj156-purpose-test.log`, `/tmp/proj156-purpose-e2e.log`. frontend 소스는 변경하지 않았다.


## S8-3 모델 A/B 관측 비교

- S8-3 실행을 연결했다. owner와 감사 사유가 필수이고 from/to의 같은 기간에 model_a·model_b를 각각 필터링한다. 두 모델은 달라야 하며 각각 1~200자다. 선택하지 않은 모델과 모델명 없는 원천은 제외한다.
- 프롬프트 분포·비용·LLM 응답 시간·API 오류율을 4단계로 조회한다. 각 단계는 독립 모델 쿼리 두 개이며 동일한 기간·가격 기준을 적용한다. 결과의 각 숫자 필드 labels.model로 모델을 구분한다. 두 결과를 합산하지 않는다.
- 모델별 QRY 마스킹을 유지한다. 한쪽 소집단·미관측 또는 동일 값은 차이 판정을 생성하지 않는다. 완료 기간의 유효한 양쪽 값만 observed_model_difference info로 제공한다. 비용·오류율은 value, 프롬프트·응답 시간은 p50을 비교하며 delta_b_minus_a는 B-A다.
- 비용은 기간 합계이며 이용자·요청량·업무·배정을 통제하지 않는다. 무작위 A/B 실험, 모델 우열·유의성·인과 효과를 판정하지 않는다. 같은 이용자가 두 모델에 포함될 수 있다.
- 실행 경로는 33개, 추가 실행 대상 9개, 명세상 unavailable 4개다.


### S8-3 검증 결과

- dashboard 테스트 212건, 실패·오류·skip 0건. 선택 모델 격리, 모델 라벨 순서, 동일 모델·과도한 모델명 거부, owner 감사 및 admin 거부, 소집단·미관측·동일 값 제외를 검증했다. 실제 DB의 비용 5→10, 정상 응답 p50 100→200ms, 오류율 0→0.5 차이를 확인했다.
- 오류율은 HTTP 상태만으로 계산하지 않고 기존 QRY의 error_type 정의를 따른다. 실패 호출은 정상 응답 시간 집계에서 제외한다. 테스트 fixture도 두 원천을 구분했다.
- 구현 `2e757ca`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`, 연결 시나리오 33개다.
- `verifiedIngest.modelComparisonScenario`: run `5e9412d6-afee-4a7d-aaa8-d644e11d93fc`, owner 감사 1건, 진행 4/4 완료. 모델 A 이벤트 비용 15달러·B 비용 미관측과 모델 라벨 보존, 판정 없음 및 결과 화면을 검증했다. `owner-ingest-model-comparison.png`를 확인했다.
- 수집 fixture의 두 모델에는 같은 종류의 양수 이벤트 비용이 모두 존재하지 않는다. 양쪽 비용·시간·오류율 차이의 긍정 사례는 DB 통합 테스트가 담당한다. 모델명 없는 프롬프트와 현행 duration 원천 조건에 맞지 않는 이벤트는 미관측이다.
- frontend는 여전히 비용 위젯 제목을 '팀별 비용'으로 표시하고 긴 모델 라벨을 잘라 보여준다. 모델 비교 전용 제목·라벨 및 일반 실행 폼의 감사 사유 전달은 후속 작업이다. 이번 E2E는 owner 로그인 후 공통 클라이언트 감사 경로를 사용했다.
- 로그: `/tmp/proj156-model-ab-test.log`, `/tmp/proj156-model-ab-e2e.log`. frontend 소스는 변경하지 않았다.


## S5-4 벤더 계정 불일치 관측

- S5-4의 제한 이벤트·벤더 불일치·MCP 연결·활성 사용자 4단계 실행을 연결했다. owner + 감사 사유가 필수이며 매 조회와 결과 저장 전 현재 권한을 재검증한다.
- S5-4는 시작 시 검증·정규화한 감사 사유를 scenario_runs.execution.audit_reason에 보존한다. 워커는 vendor_account_mismatch 조회에 같은 사유를 다시 전달해 query 감사도 기록한다. 이미 디코딩된 사유는 URL 인코딩하여 공개 QRY 경계로 전달하므로 %·+가 이중 디코딩되지 않는다. 다른 시나리오는 실행 정보에 사유를 추가 저장하지 않는다.
- 저장 사유가 누락되면 감사 검사에서 실패한다. 실행 응답·판정에는 저장 사유와 벤더·등록 이메일을 포함하지 않는다. QRY의 owner·감사 경계를 우회하지 않는다.
- 일별·설치별 마지막 비어 있지 않은 로그/스팬 이메일과 현재 등록 이메일을 대소문자 구분 없이 비교한다. 양수 불일치 설치 수를 observed_vendor_mismatch info로 제공한다. 소집단·일치·미관측은 불일치 판정하지 않는다.
- 실제 비인가 사용·개인 계정·섀도우 AI 여부를 확정하지 않는다. 카탈로그 partial 상태는 유지한다. 실행 경로 34개, 추가 실행 대상 8개, 명세상 unavailable 4개다.


### S5-4 검증 결과

- dashboard 테스트 215건, 실패·오류·skip 0건. 등록 이메일의 대소문자 무시와 마지막 값 선택, 불일치 2건, 주소·사유 응답 비노출, 일치·미관측·소집단 판정 제외를 검증했다.
- 감사 사유의 퍼센트·더하기·%2F 문자가 시작/조회 로그에서 정확히 보존된다. 내부 사유 누락 시 audit_reason_required로 실패하고, 큐 등록 후 owner→admin 변경 시 조회 감사 없이 실패한다.
- 구현 `cd1de45`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`, 연결 시나리오 34개다.
- `verifiedIngest.shadowScenario`: run `c5502d47-f757-4383-a2c5-de3cf5821111`, owner 시작 감사 1건·같은 사유의 vendor_account_mismatch query 감사 1건, 진행 4/4 완료. 활성 사용자 5명과 벤더 이메일 미관측 및 판정 없음을 확인했다. `owner-ingest-shadow-scenario.png`를 확인했다.
- 실제 ingest fixture에 벤더 이메일은 없으므로 빈 프레임 경로를 검증한다. 불일치 양수·일치·소집단은 DB 통합 테스트가 담당한다. 일반 frontend 실행 폼의 감사 사유 입력은 여전히 후속 대상이며 이번 검증은 owner 로그인 후 공통 클라이언트 감사 경로다.
- 로그: `/tmp/proj156-shadow-test.log`, `/tmp/proj156-shadow-e2e.log`. frontend 소스는 변경하지 않았다.


## S4-3 언어별 코드 수용 관측

- S4-3 실행을 연결했다. owner/admin의 현재 범위에서 편집 수락률·코드량·커밋·PR을 4단계로 조회한다. language는 편집 결정의 point.attrs.language에만 대소문자를 구분한 정확 일치로 적용한다. 다른 세 지표는 동일 기간·팀 범위의 보조 집계다.
- QRY edit_acceptance_rate에도 language 문자열 파라미터(공백이 아닌 1~256자, 제어 문자 불가)를 추가했다. SQL 명명 파라미터로 전달하며 생략하면 기존 전체 언어 집계를 유지한다. 언어별 조회임을 프레임 품질 설명과 판정 evidence.language에 보존한다.
- 사용자 편집 accept/(accept+reject) 양수 비율을 observed_edit_acceptance info로 제공한다. 소집단·미관측·거절만 있는 경우는 수락 관측 판정이 없다. revert·코드 품질·생산성은 측정하지 않으며 카탈로그 partial을 유지한다.
- 실행 경로 35개, 추가 실행 대상 7개, 명세상 unavailable 4개다.

- S4-3 언어 선택 시에는 해당 언어의 유효 편집 관측 인원으로 마스킹한다. 다른 언어·보조 지표 사용자가 소집단을 해제하지 않는다.


### S4-3 검증 결과

- dashboard 테스트 217건, 실패·오류·skip 0건. 선택 언어 25%와 전체 언어 10/13 비율, 보조 코드량 50 유지, 따옴표 포함 언어명의 정확 일치, 잘못된 입력 거부를 검증했다. 대소문자 불일치·거절만·미관측·선택 언어 4명과 다른 언어 1명의 혼합에서도 수락 판정을 생성하지 않는다.
- 구현 `ecaae70`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`, 연결 시나리오 35개다.
- `verifiedIngest.acceptanceScenario`: run `4b8c46b6-7683-4451-a0cd-ec32fd11be29`, admin 현재 팀 범위, language=kotlin, 진행 4/4 완료. `admin-ingest-acceptance-scenario.png`에서 네 지표의 미관측과 판정 없음 패널을 확인했다.
- 실제 ingest fixture에 편집 결정·코드량·커밋·PR 원천이 없어 이번 화면은 미관측 실행 경로 검증이다. 양수 수락률과 언어 필터 및 보조 코드량은 DB 통합 테스트가 담당한다. 미관측을 0% 수락이나 revert로 판정하지 않는다.
- frontend 상단에는 선택 언어 전용 표시가 없다. 언어는 실행 params 및 관측 프레임의 품질 설명·양수 판정 evidence에 보존한다. frontend 소스는 변경하지 않았다.
- 로그: `/tmp/proj156-acceptance-test.log`, `/tmp/proj156-acceptance-e2e.log`.


## S6-3·S8-7 고정 4주 전후 관측

- 명세에 기간 길이가 없는 S6-3·S8-7은 사용자 확인(2026-09-12: '전후 4주 사용')에 따라 기준일 현지 자정 전후 각각 4주로 고정한다. 요청 스키마에 새 입력을 추가하지 않으며 실제 범위를 큐에 저장하고 applied_filters.window_weeks=4와 전후 시간을 반환한다.
- S6-3은 owner + 감사 사유로 실행한다. models(생략·빈 배열은 전체, 최대 100개·각 1~200자)를 네 지표에 동일하게 적용한다. API 오류율·도구 실패율·프롬프트·종료 사유를 4단계로 비교하며 종료 사유는 stop_reason별로 분리한다. 사유 누락은 빈 라벨이며 임의의 정상 종료로 치환하지 않는다.
- S8-7은 owner/admin의 현재 팀 범위에서 도입률·프롬프트·사용 집중도를 3단계로 비교한다. 집계와 분포만 반환하며 개인 챔피언 명단은 제공하지 않는다. 카탈로그 partial 상태를 유지한다.
- 기존 QRY 합동 마스킹과 미관측 null을 유지한다. 완료 기간의 유효한 값 차이만 info이며, 종료 사유별 판정 evidence.dimensions에 사유 라벨을 보존한다. 모델 드리프트·챔피언 프로그램의 인과 효과나 개선 여부는 판정하지 않는다.
- 실행 경로 37개, 추가 실행 대상 5개(S1-2·S1-7·S3-3·S5-5·S8-2), 명세상 unavailable 4개다.


### S6-3·S8-7 검증 결과

- dashboard 테스트 221건, 실패·오류·skip 0건. 확정된 4주 현지 자정과 DST, 모델 선택·종료 사유별 전후 5→10건, 프롬프트 p50 1→2, 사용 집중도 변화, 개인 식별자 비노출, owner 감사·admin 거부 및 소집단·미관측·미완료 기간을 검증했다.
- 구현 `a6e0b95`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`, 연결 시나리오 37개다.
- `verifiedIngest.championScenario`: run `02ad1c1f-f2dc-423c-90bc-ec213dfaae68`, admin 팀 범위·3/3 완료, 현재 프롬프트 p50=2와 이전 null. `admin-ingest-champion-scenario.png`에서 결과 표와 판정 없음 패널을 확인했다.
- `verifiedIngest.driftScenario`: run `70752d9b-bc45-45bb-a431-ebd39eb6e9f3`, owner 감사 1건·선택 모델·4/4 완료, 종료 사유가 없는 이벤트 5건을 빈 라벨로 보존했다. `owner-ingest-drift-scenario.png`를 확인했다.
- 당일 데이터의 이후 4주는 미완료여서 두 시나리오 모두 변화 판정이 없다. 양쪽 유효 관측 차이의 긍정 사례는 DB 통합 테스트가 담당한다. 수집 fixture의 모델명 없는 도구·프롬프트는 S6-3 선택 모델 결과에서 제외된다.
- frontend의 상단 비교 선택기는 '없음'이며 이전 기간·완료 여부 전용 안내는 미연결이다. 종료 사유 미기록은 일반 막대의 value 축으로 나타나고 S8-7 W2.0 강조 매핑도 누락돼 있다. 일반 P3 실행 폼의 감사 사유 입력은 후속 대상이며 이번 S6-3 검증은 owner 로그인 후 공통 클라이언트 감사 경로다.
- 로그: `/tmp/proj156-fixed-comparison-test.log`, `/tmp/proj156-fixed-comparison-e2e.log`. frontend 소스는 변경하지 않았다.


## S1-7 기준 시점의 무활동 설치 관측

- S1-7 실행을 연결했다. owner + 감사 사유가 필수다. as_of(기본 now)는 요청 시점 이하이고 inactive_days는 1~365(기본 30)다. 보조 지표의 조회 범위는 `[as_of-inactive_days*24h, as_of)`로 고정한다.
- 커버리지·활성 사용자·사용자당 비용 조회 후 네 번째 단계에서 현재 활성 구성원·활성 설치의 보존 이력을 조회한다. 기준 시점 이전에 생성된 설치만 포함하며 기준 시점 이상의 이벤트는 제외한다. 후보 5,000개 초과는 query_too_wide로 실패한다.
- 마지막 관측이 cutoff 이하인 설치, 또는 보존된 관측이 없고 생성 시점이 cutoff 이하인 설치를 집계한다. 신규·최근 활동·폐기 설치는 제외한다. 무활동 집계가 실제 구성원 5명 이상일 때만 observed_inactive_installations info를 제공하며 설치·구성원 식별자를 응답하지 않는다.
- 현재 상태를 사용하므로 과거 활성 상태·라이선스를 복원하지 않는다. 수집 누락·이력 삭제·실제 사용·좌석 회수·절감액은 판단하지 않는다. 보조 커버리지 등은 기존 QRY의 현재 분모 정의를 유지하고, 무활동 후보의 생성 시점 제한과 구별한다.
- 실행 경로 38개, 추가 실행 대상 4개(S1-2·S3-3·S5-5·S8-2), 명세상 unavailable 4개다.


### S1-7 검증 결과

- dashboard 테스트 225건, 실패·오류·skip 0건. 기준 시점 이후 이벤트 제외, 정확한 경과일 경계, 미래 기준 거부, 미관측 생성일, 신규·최근 활동·폐기 설치 제외, owner 감사 및 실제 구성원 수 마스킹을 검증했다. 한 사람의 설치 5개로는 판정을 생성하지 않는다.
- 구현 `3aa6b15`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`, 연결 시나리오 38개다.
- `verifiedIngest.inactivityScenario`: run `c1e248b0-4e89-4f26-9994-507c21018c79`, owner 감사 1건·4/4 완료, inactive_days=30과 고정 as_of, 최근 활성 사용자 5명에 무활동 판정 0건. `owner-ingest-inactivity-scenario.png`를 확인했다.
- 실제 ingest fixture는 최근 생성·활동한 설치이므로 무활동 양수 판정은 DB 통합 테스트가 담당한다. 화면에서는 당일 커버리지 100%·사용자당 비용 3달러와 이전 날들의 미관측을 확인했다.
- frontend는 커버리지·비용의 일별 프레임을 긴 일반 표로 렌더링하고 W3.2 전용 강조 위젯은 미연결이다. 일반 P3 실행 폼의 감사 사유 입력도 후속 대상이며 이번 검증은 owner 로그인 후 공통 클라이언트 감사 경로다.
- 로그: `/tmp/proj156-inactivity-test.log`, `/tmp/proj156-inactivity-e2e.log`. frontend 소스는 변경하지 않았다.

## S5-5 반복 거부 시간 창 관측

- `probe_window_min`(1~1440분), `probe_count`(1~10000회)를 전사 고정 창 집계에 연결했다. UTC epoch 정렬이며 횟수가 기준 이상일 때 관측 판정을 생성한다. 이동 창이나 개인별 공격 판정이 아니다.
- `[from,to)` 안의 `llm_response` 거부 응답만 FINAL 조회한다. 경계에서 잘린 창은 `observed_from/to`를 따로 제공한다. 창마다 실제 구성원 5명 이상을 요구하며 설치 여러 개로 마스킹을 해제하지 않는다.
- owner·감사 사유와 실행 중 현재 권한 검증을 유지한다. tenant 설치 5000개·판정 창 1000개 초과는 잘라서 성공시키지 않고 422 실패한다.
- 실행 경로 39개, 추가 실행 대상 3개(S1-2·S3-3·S8-2), 명세상 unavailable 4개다. 공격 의도·탈옥 성공 여부를 추론하지 않는다.

### S5-5 검증 결과

- dashboard 테스트 227건, 실패·오류·skip 0건. 횟수 정확한 경계·미달, 시간 창 변경, 조회 기간 밖 이벤트와 다른 종료 사유·이벤트 유형 제외, 구성원 소집단과 한 사람의 여러 설치 마스킹, owner·감사 사유를 검증했다.
- 구현 `ae5094e`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`. S5-5 실행 `9d58d54a-9b16-4905-983f-a8318c1a749a`는 4단계 완료, 감사 1건이며 결과 화면을 확인했다.
- 현재 OTLP fixture에는 거부 응답이 없으므로 E2E는 3개 지표의 미관측·판정 없음 경로를 검증했다. 반복 거부 양성 판정은 실제 PostgreSQL·ClickHouse 테스트에서 검증했다.
- frontend의 정상 P3 시작 폼은 감사 사유를 보내지 않는 기존 제한이 남는다. E2E는 실제 owner 로그인 후 frontend 공통 request 클라이언트에 감사 사유를 전달했다. 화면에는 3개 미관측 카드와 빈 판정이 나타나며 시간 창 파라미터 전용 표시는 없다. frontend 소스는 변경하지 않았다.

## S1-2 지정 모델 패턴 관측

- 사용자가 확정한 `premium_model_patterns` 문법: 모델명 전체 매칭, 대소문자 구분, `*`만 임의 문자열. `%`·`_`·정규식 문자는 문자 그대로 처리한다. 1~100개, 각 1~200자로 제한한다.
- 비용·토큰은 SQL 바인딩 패턴 필터를 집계 전에 적용하고 모델별 표로 반환한다. 상위 100개 밖은 기존 QRY의 기타 합계·마스킹을 유지하며 개별 판정하지 않는다. 공개 QRY 입력 계약은 변경하지 않는다.
- 프롬프트·도구 호출은 모델 귀속 정보가 없어 같은 기간·팀 참고값이다. 적용 결과에 `model_filtered_metrics`와 `context_metrics`를 구분해 제공한다.
- info 판정은 선택한 모델의 양수 비용 관측이다. 모델 티어·작업 난이도·품질·대체 모델 비용 근거가 없어 미스매치나 절감액을 판정하지 않는다. admin의 현재 팀 범위와 기존 소집단 마스킹을 유지한다.
- 실행 경로 40개, 추가 실행 대상 S3-3·S8-2의 2개, 명세상 unavailable 4개다. 전체 API 계약·frontend 연동 검증은 계속 필요하다.

### S1-2 검증 결과

- dashboard 테스트 229건, 실패·오류·skip 0건. 전체 이름·대소문자·별표·중복 일치·미일치, `%`·`_`·따옴표의 문자 처리, 빈 패턴·과도한 길이 거부, 소집단과 모델별 미관측 토큰을 검증했다.
- 구현 `59408f4`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`. admin 실행 `11b05baa-7f83-4fcc-b2a4-817b2bff0364`에서 모델 비용 15달러·토큰 1050개·info 판정 1건과 실제 결과 화면을 확인했다.
- 같은 기간·팀 참고값인 도구 호출 5개와 프롬프트 p50/p90 2가 표시된다. frontend는 모델별 비용도 ‘팀별 비용’ 제목으로 표시하고 긴 모델명 축을 줄이며 모델 필터·참고 지표 구분의 전용 표시가 없다. 판정 근거에 관측 한계가 표시되지만 frontend 소스 변경과 전체 연동 완료는 별도다.

## S3-3 온보딩 코호트 추적

- `[cohort_from,cohort_to)`는 선택 팀 범위에서 보존 이력의 첫 이벤트가 발생한 설치의 선정 기간이다. 현지 날짜를 해석하며 종료일은 제외한다. 관측 종료는 실행 요청 시점으로 고정한다.
- 한 번 선정한 설치 집단에 대해 첫 사용 시간·주별 잔존율·활성 시간을 조회한다. 선정 기간 이후 활동을 포함하고 이전 사용자·종료일 이후 첫 사용자는 제외한다. 진행 중 주의 잔존율은 기존 QRY처럼 null이다.
- owner·감사 사유와 실행 중 권한 재검증을 유지한다. 공개 QRY 입력을 확장하지 않으며 설치 목록·원문을 반환하지 않는다. 구성원 5명 미만 마스킹을 코호트에 적용한다.
- 코호트 종료일은 요청 시점 이하여야 한다. 시작일부터 요청 시점까지 366일을 넘으면 입력을 거부한다. 설치 대응표 5000개와 조회 30초 한도를 유지한다.
- 보존·팀 범위 이전 사용 여부와 실제 신규 가입을 확정하지 않는다. 객관적 성공 임계값이 없으므로 별도 정착 성공·실패 판정 없이 관측 프레임과 한계를 제공한다.
- 실행 경로 41개, 추가 실행 대상은 S8-2 하나, 명세상 unavailable 4개다. 전체 API 계약·frontend 연동 검증은 남아 있다.

### S3-3 검증 결과

- dashboard 테스트 232건, 실패·오류·skip 0건. 코호트 시작 포함·종료 제외, 이전 사용자 제외, 선정 후 활성 시간 600초와 잔존율, 코호트 4명 마스킹, owner·감사, 요청 시점 고정·미래 종료·366일 초과 거부를 검증했다.
- 구현 `76a3cb4`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`. 실행 `8c302548-9203-4fe8-b388-8f724e02b86e`는 3단계 성공, 감사 1건이며 오늘 첫 관측 설치가 과거 코호트의 수치에 섞이지 않았다.
- E2E는 비어 있는 과거 코호트 경로이며 실제 코호트의 후속 활동은 PostgreSQL·ClickHouse 테스트에서 검증했다. frontend에서 3개 미관측 카드·빈 판정·W3.2 대응 결과 없음 안내를 확인했다. 코호트 선정 기간과 관측 기간의 전용 구분 표시도 없다. P3 실행은 실제 owner 로그인 후 frontend 공통 request에 감사 사유를 전달했으며 일반 시작 폼의 기존 감사 사유 누락은 남아 있다.

## S8-2 단순 비용 예측

- 입력 기간은 최소 90일·최대 366일이며 종료 시점은 미래일 수 없다. 명세의 90일 기본값을 사용한다. 예측 기간은 명세에 없어 우선 30일 기본값으로 구현했으며 사용자에게 기간을 질의했다.
- 조회 범위에 완전히 포함된 현지 날짜의 일별 비용을 사용한다. 일부만 포함된 첫날·마지막 날은 제외하고, 남은 날짜 중 유효한 관측 비용이 하나라도 없거나 마스킹되면 `forecast.status=insufficient_data`를 반환한다. 미관측을 0으로 대체하지 않는다.
- constant는 일평균 유지, linear는 일별 최소제곱 직선 외삽이다. 예측은 조회 종료 이후 첫 온전한 현지 날짜부터 30일이며 음수 일별 예측은 0으로 제한하고 그 사실을 표시한다. 과거 기간 조회는 그 종료 시점 기준 예측이며 실제 이후 값과의 비교를 의미하지 않는다.
- `forecast`에 훈련·예측 기간, 유효 날짜 수, 일별 예측과 합계를 제공한다. 실측 비용·토큰·모델 단가·캐시 비율·잔존율 프레임과 분리한다. 나머지 지표는 참고값이며 예측 회귀의 설명변수가 아니다.
- info 판정은 예측 또는 예측 불가 사유를 제공한다. 청구액·채택 변화·계절성·계약 변동·신뢰구간을 추정하지 않는다. 기존 팀 범위·공시/계약 기준·소집단 보호를 유지한다.
- 46개 중 실행 가능한 42개 경로가 연결됐고 unavailable 4개는 409를 유지한다. 실행 경로 연결은 전체 분석 의미·API 계약·frontend 연동 완료를 뜻하지 않는다.

### S8-2 및 전체 회귀 검증

- dashboard 테스트 236건, 실패·오류·skip 0건. 90일 실측의 상수 예측 6825달러와 선형 예측 15825달러, 미관측·마스킹·관측 영 구분, 부분 날짜·DST·음수 예측 제한·입력 기간을 검증했다.
- 구현 `708d5d7`에서 `./gradlew build --rerun-tasks` 통과. 58개 작업 모두 실행했으며 전체 테스트 1043건, 실패·오류·skip 0건이다.
- 같은 구현과 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 실제 수집 E2E 통과. `unexpectedOrUnimplementedResponses=[]`. S8-2 실행 `94d5f718-d888-4187-b738-b258c9606e53`은 6단계 완료, `insufficient_data`, 예측 금액 생략을 확인했다.
- 실제 fixture는 당일 이력뿐이므로 미래 금액 양성 경로는 DB 테스트에서 검증했다. frontend 판정에는 이력 부족·필요 89일·관측 0일·예측 기간을 표시한다. 캐시 비율 일별 표가 길고 별도 예측 차트는 없어 frontend 표시 개선은 남는다. frontend 소스는 변경하지 않았다.
- 실행 가능한 42개 시나리오 연결 이후의 다음 작업은 QRY 세부 계약·operation/metric/scenario 추적표, frontend 감사 사유·위젯 매핑, 운영·복구 검증이다. 전체 PROJ-156 완료 또는 운영 배포 가능 판정은 아직 하지 않는다.

## QRY 토큰 상위 N 계약 보완

- Query.limit은 초과 그룹을 `__other__`로 요구하지만 토큰 조회는 422로 거부하던 불일치를 수정했다. token source별 원본에서 기타를 재집계하고 현재 기간 합계로 선택한 그룹을 비교 기간에도 고정한다.
- 토큰 종류 정규화·선택 필터, 누적 메트릭 제외, 모델/type 두 차원, 실제 구성원 중복 제거와 소집단 보호를 유지한다. 토큰 상위 N이 연결되며 지원 지표는 37개다.
- [QRY 계약 추적표](dashboard-api-contract-matrix.md)에 53개 지표의 기본 형식·추가 프레임·상위 N 구현 분기를 정리했다. 조합 전체의 수용 검증 완료를 의미하지 않는다.

### 토큰 상위 N 검증 결과

- dashboard 테스트 239건, 실패·오류·skip 0건. 토큰 table/scalar/timeseries의 현재·비교 그룹 고정, 모델/type 기타 재집계, 종류 필터·누적 메트릭 제외, 소집단 순위·기타 마스킹을 검증했다.
- 구현 `e49c8c4`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. 실제 admin API 클라이언트와 위젯 `series` 변환에서 동률 모델 `top-e2e-a` 선택, 상위 5·기타 10 토큰을 확인했다. 이는 새 화면의 시각 검증이 아닌 실제 클라이언트·위젯 데이터 계약 검증이다.
- 기존 실제 ingest·42개 시나리오 경로도 함께 통과했으며 `unexpectedOrUnimplementedResponses=[]`다. frontend 소스는 변경하지 않았다. 전체 빌드 1043건 통과 기록은 이전 `708d5d7` 기준이며 이번 변경은 dashboard 테스트·JAR·E2E를 검증했다.

## 거부·훅 실행 상위 N 계약 보완

- `refusals`와 `hook_executions`에 현재 기간 실행/거부 횟수 기준 상위 N과 기타 재집계를 연결했다. 비교 기간은 같은 선택을 사용하며 숨겨진 수치는 순위에 반영하지 않는다. 지원 지표는 39개다.
- 훅 기타의 실행 수는 합산하지만 세션 수는 설치·제품·세션 키로 중복을 제거한다. 분모는 그룹 분류 이전의 전체 유효 세션이며 비율을 단순 합산하지 않는다.
- 거부 응답은 owner 전용이고 팀별 분해 금지를 유지한다. 기타의 여러 범주·모델도 원본에서 합산하며 실제 구성원 수로 마스킹한다.

- 기타 그룹에 들어간 일반 이벤트의 구성원이 거부·훅 소집단을 해제하지 않도록, 해당 지표의 유효 이벤트를 남긴 구성원만 마스킹 인원으로 계산한다. 4명의 거부·훅에 일반 프롬프트 5명을 추가한 회귀 조건을 포함한다.

### 거부·훅 상위 N 검증 결과

- dashboard 테스트 242건, 실패·오류·skip 0건. 훅 table/scalar/timeseries 비교·세션 중복 제거, 거부 두 차원·owner 인가, 기타 소집단을 검증했다. 일반 이벤트 5명으로 거부·훅 4명의 마스킹이 해제되지 않는다.
- 구현 `a637fb5`, 관측자 마스킹 보강 `adb8010`. 최종 `adb8010`과 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. `unexpectedOrUnimplementedResponses=[]`.
- 실제 admin 클라이언트에서 훅 기타 실행 10회·고유 세션 5개·비율 1, 별도 실제 owner 로그인 후 거부 기타 10회를 확인했다. 이는 API 클라이언트와 위젯 데이터 변환 검증이며 새 위젯 화면의 시각 검증은 아니다. 기존 42개 시나리오 수집 E2E도 함께 통과했다.

## 세션 분포 상위 N 계약 보완

- `prompts_per_session`과 `read_tool_density`의 table/scalar/timeseries/distribution 상위 N을 연결했다. 기간 전체 p50으로 상위 그룹을 정하고 비교 기간에 같은 선택을 적용한다. 일별 분위수를 더하지 않는다. 지원 지표는 41개다.
- 기타의 p50/p90과 프롬프트 히스토그램은 설치·제품·세션 원본에서 재계산한다. 읽기 밀도 distribution은 기존처럼 분위수만 제공한다. 여러 팀에 걸친 같은 이벤트가 기타로 합쳐지면 중복을 제거한다. 세션 유효 이벤트 관측 인원을 기존 소집단 기준과 함께 적용해 무관한 이벤트로 마스킹이 풀리지 않게 한다.
- 입력·권한·조회 제한과 빈 날짜/미관측 처리, 읽기 0회 도구 세션 포함 정책을 유지한다.

### 세션 분포 상위 N 검증 결과

- dashboard 테스트 245건, 실패·오류·skip 0건. table/scalar/timeseries/distribution, 일별 분위수 합산과 다른 기간 전체 p50 순위, 기타 p50/p90·프롬프트 히스토그램, 비교 기간, 다중 팀 중복, 소집단을 검증했다.
- 구현 `c645254`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. `unexpectedOrUnimplementedResponses=[]`. 실제 owner 로그인 후 두 지표의 위젯 변환에서 상위 p50 5, 기타 p50/p90 3을 확인했다.
- 기존 실제 수집·42개 시나리오 E2E도 통과했다. 이번 추가 검증은 프런트엔드 API 클라이언트·위젯 데이터 계약이며 새 화면 시각 검증은 아니다. frontend 소스는 변경하지 않았다.

## 활성 사용자·커버리지 상위 N 계약 보완

- `active_users`와 `telemetry_coverage`의 table/scalar/timeseries 상위 N을 연결했다. 순위는 기간 전체의 고유 인원·설치로 계산하며 일별 수를 더하지 않는다. 비교 기간에는 현재 기간에서 정한 그룹을 유지한다. 기타 재집계 지원 지표는 43개다.
- 기타의 인원·설치는 원본에서 중복 제거한다. 팀 확장으로 같은 활동 시간 이벤트가 두 번 합산되지 않도록 그룹 변환 뒤 관측을 중복 제거하고, 인원별 활동 시간 합계와 기존 소집단 마스킹을 적용한다. 커버리지 분모는 현재 조회 범위의 활성 설치 수를 유지한다.
- 도입률 상위 N은 남아 있다. 기타 팀 분모는 구성원 수를 단순 합산하지 않고 현재 구성원 합집합으로 계산해야 한다.
- 구현 `1a39804`. dashboard 테스트 248건·JAR 생성 통과, 실패·오류·skip 0건. 기간 순위·기타 중복 제거·비교·여러 팀의 활동 시간 상쇄·소집단 조건을 검증했다.

### 활성 사용자·커버리지 상위 N E2E 결과

- 구현 `1a3980465c1186731ba89134efa047d5a078bc93`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. `unexpectedOrUnimplementedResponses=[]`.
- 실제 owner 로그인과 frontend 공통 API 클라이언트·위젯 변환에서 기타 활성 사용자 5명, 기타 관측 설치 5개·분모 5개·커버리지 1을 확인했다. 여러 팀에 속한 동일 인원을 더하지 않는다. 기존 실제 수집·42개 시나리오 실행 경로도 함께 통과했다.
- 이는 API·위젯 데이터 계약 검증이며 새 위젯 화면의 시각 검증은 아니다. frontend 소스는 변경하지 않았다. 전체 QRY 형식·남은 상위 N·장기 계약·운영 복구 수용 조건은 계속 보완해야 한다.

## 도입률 상위 N 계약 보완

- `adoption_rate` table/scalar/timeseries에 상위 N을 연결했다. 기간 전체 고유 활성 사용자 기반 도입률로 순위를 정하며 일별 도입률을 더하지 않는다. 기타 재집계 지원 지표는 44개다.
- 기타 분모는 현재·비교 기간에서 실제 기타로 합쳐지는 팀들의 현재 활성 구성원 합집합이다. 여러 팀에 소속된 사람은 한 번만 세고 활동이 없는 현재 구성원도 포함한다. 이 분모를 두 기간에 공통 적용한다. 관측 팀 밖의 팀은 추가하지 않는다.
- 구현 `1a4f6ed`. dashboard 테스트 249건·JAR 검증 통과, 실패·오류·skip 0건. 현재 기간에 없는 비교 팀, 중복 구성원, 미관측 구성원, 세 가지 프레임과 비교 분모를 검증했다.
- 기존 활성 사용자 소집단 테스트의 동률을 제거했다. 같은 5명인 두 상위 후보의 UUID 정렬에 따라 선택이 달라지는 조건을 6명 대 5명으로 바꿔 활동 시간 상쇄와 마스킹만 검증한다.

### 도입률 상위 N E2E 결과

- 구현 `1a4f6ed`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. 실제 owner 로그인 후 공통 API 클라이언트·위젯 변환에서 기타 활성 사용자 5명, 현재 구성원 합집합 5명, 도입률 1을 확인했다.
- 기존 실제 수집·42개 시나리오 실행 경로도 통과했으며 `unexpectedOrUnimplementedResponses=[]`다. frontend 소스는 변경하지 않았다. 이번 추가 검증은 위젯 데이터 계약이며 새 화면의 시각 검증은 아니다.
- 남은 상위 N은 9개 지표다. 미지원 형식·장기 계약 조회·개인정보 감사 UI·운영 복구 등 전체 수용 조건은 아직 완료되지 않았다.

## 서브에이전트 상위 N 계약 보완

- `subagent_activity`의 table/scalar/timeseries에 기간 전체 고유 agent_id 기준 상위 N을 연결했다. 일별 고유 수를 합산하지 않고 비교 기간에 같은 그룹을 사용한다.
- 기타의 식별자를 중복 제거하며 호출 분자·분모도 같은 이벤트의 다중 팀 확장을 중복 제거한다. 기존 식별자 기준을 유지하며 설치별 agent_id 네임스페이스를 새로 도입하지 않는다.
- 일반 프롬프트가 아닌 유효 도구 호출 구성원으로 소집단을 판정한다. 4명의 도구 호출에 5명의 일반 프롬프트를 추가해도 기타 수·비율·분자·분모를 모두 숨긴다.
- 구현 `745e1ee`, dashboard 테스트 251건·JAR 검증 통과, 실패·오류·skip 0건. 세 프레임·기간 순위·비교·다중 팀 중복·소집단을 검증했다.
- 추적표 분류를 정정했다. 일반 group_by를 금지하는 vendor_account_mismatch와 contract_commitment_burn은 미구현 그룹 집계로 세지 않는다. 일반 그룹 상위 N은 45개 연결·6개 미연결이다. 약정 소진율의 내부 계약별 결과와 장기 계약 제한 검토는 여전히 남아 있다.

### 서브에이전트 상위 N E2E 결과

- 구현 `745e1ee`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. 실제 owner 로그인 후 API 클라이언트·위젯 변환에서 기타 고유 agent_id 2개, agent 호출 10회·전체 도구 호출 15회·비율 2/3을 확인했다. 같은 이벤트가 두 팀에 있어도 호출 수를 두 배로 세지 않는다.
- 실제 수집·기존 42개 시나리오 경로도 통과했고 `unexpectedOrUnimplementedResponses=[]`다. frontend 소스는 변경하지 않았다. 이번 추가 검증은 API·위젯 데이터 계약이며 새 화면의 시각 검증은 아니다.

## 무산출 세션 상위 N 계약 보완

- `abandoned_session_ratio` table/scalar/timeseries의 상위 N을 연결했다. 기간 전체의 고유 세션 비율로 순위를 정하고 비교 기간에 같은 선택을 적용한다.
- 기타 팀의 원본 관측을 중복 제거한 뒤 설치·제품·세션으로 합쳐 산출 여부를 다시 판정한다. 한 팀의 로그와 다른 팀의 산출이 같은 세션이면 함께 판단하며 비율을 합산하지 않는다. 기존 마지막 로그 날짜 배치·누적 산출 제외 정책을 유지한다.
- 유효 로그 세션의 구성원 수를 기존 마스킹 인원과 함께 적용한다. 세션 없는 로그가 4명 소집단을 해제하지 않는다.
- 구현 `6fa416c`, dashboard 테스트 253건·JAR 검증 통과, 실패·오류·skip 0건. 세 프레임과 비교, 팀 간 세션 병합·산출 재판정·소집단을 검증했다.

### 무산출 세션 상위 N E2E 결과

- 구현 `6fa416c`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. 실제 owner 로그인 후 API 클라이언트·위젯 변환에서 기타 무산출 세션 10개·전체 세션 10개·비율 1을 확인했다. 팀 간 산출 병합 양수 조건은 DB 통합 테스트가 담당한다.
- 실제 수집·기존 42개 시나리오 경로도 통과했고 `unexpectedOrUnimplementedResponses=[]`다. frontend 소스는 변경하지 않았다. 이번 검증은 API·위젯 데이터 계약이며 새 화면의 시각 검증은 아니다.
- 일반 그룹 상위 N은 46개 연결·5개 미연결이다. 남은 지표는 비용 이상, 사용 집중도, 첫 사용 시간, 잔존율, 마지막 세션 이벤트다. 전체 형식·장기 계약·운영 복구 수용 조건은 별도 남아 있다.

## 첫 사용 시간 상위 N 계약 보완

- `onboarding_ttfu`의 table/scalar/timeseries/distribution에 기간 전체 p50 기준 상위 N을 연결했다. 일별 분위수를 더하지 않는다. 비교 기간은 같은 선택을 적용하는 공통 경로를 사용한다.
- 기타는 설치별 최초 관측을 다시 구하고 p50/p90·히스토그램을 재계산한다. 여러 팀의 같은 설치는 하나로 합쳐 더 이른 관측을 사용한다. 기존 하한 이전 이력 조회·생성 이전 관측 제외·실제 코호트 인원 마스킹을 유지한다.
- 구현 `c8755d0`, dashboard 테스트 254건·JAR 검증 통과, 실패·오류·skip 0건. 새 회귀 테스트는 네 형식에서 기타 p50/p90 3600초, 설치 5개 히스토그램을 검증한다. 기존 과거 이력·비교 소집단 테스트도 통과했다.

### 첫 사용 시간 상위 N E2E 결과

- 구현 `c8755d0`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. 실제 owner 로그인 후 API 클라이언트·위젯 변환에서 기타 p50/p90 3600초를 확인했다. 설치 중복 제거·히스토그램은 DB 통합 테스트가 담당한다.
- 실제 수집·기존 42개 시나리오 경로도 통과했고 `unexpectedOrUnimplementedResponses=[]`다. frontend 소스는 변경하지 않았다. 이번 추가 검증은 API·위젯 데이터 계약이며 새 화면의 시각 검증은 아니다.
- 일반 그룹 상위 N은 47개 연결·4개 미연결이다. 남은 지표는 비용 이상, 사용 집중도, 잔존율, 마지막 세션 이벤트다. 전체 형식·장기 계약·운영 복구 수용 조건은 별도 남아 있다.

## 사용 집중도 상위 N 계약 보완

- `usage_concentration` table/scalar/timeseries/distribution의 상위 N을 연결했다. 기간 전체의 사용자별 사용량에서 상위 10% 점유율로 순위를 정한다. 비교 기간에 같은 그룹을 적용한다.
- 기타 팀의 중복 관측을 제거하고 같은 구성원의 설치별 사용량을 합친 뒤 점유율·로렌츠 곡선을 재계산한다. 기존 익명 설치 대체 키, 유효 토큰 조건, 소집단·영 분모 처리를 유지한다.
- 구현 `2cc1935`, dashboard 테스트 255건·JAR 검증 통과, 실패·오류·skip 0건. 네 형식에서 기타 20/60=1/3, 이전 기간 1/5, 로렌츠 누적값 [0,10,20,30,40,60]을 검증했다. 기존 비교 소집단의 곡선 길이·CSV 마스킹 테스트도 통과했다.

### 사용 집중도 상위 N E2E 결과

- 구현 `2cc1935`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. 실제 owner 로그인 후 API 클라이언트·위젯 변환에서 기타 상위 사용량 20토큰·전체 60토큰·집중도 1/3을 확인했다. 로렌츠 곡선의 재계산은 DB 통합 테스트가 담당한다.
- 실제 수집·기존 42개 시나리오 경로도 통과했고 `unexpectedOrUnimplementedResponses=[]`다. frontend 소스는 변경하지 않았다. 이번 추가 검증은 API·위젯 데이터 계약이며 새 화면의 시각 검증은 아니다.
- 일반 그룹 상위 N은 48개 연결·3개 미연결이다. 비용 이상, 잔존율, 마지막 세션 이벤트가 남아 있다. 전체 형식·장기 계약·운영 복구 수용 조건은 별도 남아 있다.

## 비용 이상 상위 N 계약 보완

- `cost_anomaly` table/scalar/timeseries의 상위 N을 연결했다. scalar와 동일하게 마지막 관측일의 증가율로 순위를 정하며 일별 증가율을 더하지 않는다. 비교 기간은 같은 그룹 선택을 사용한다.
- 기타의 일별 비용을 원본에서 다시 집계하고 직전 달력일 이동평균·증가율을 계산한다. 기존 events/agent metrics 원천 선택·계약 할인·누락일·영 평균·기준 소집단 처리를 유지한다.
- 구현 `7a7aef7`, dashboard 테스트 256건·JAR 검증 통과, 실패·오류·skip 0건. 세 형식에서 기타 비용 250·평균 200·증가율 0.25와 비교 값을 검증했다. 기존 누락일·시간대·계약 할인·비교 마스킹 테스트도 통과했다.

### 비용 이상 상위 N E2E 결과

- 구현 `7a7aef7`와 frontend `52f7cb10017c6ba6120f51f2e158ff329d14bff0`의 E2E 통과. 실제 owner 로그인 후 API 클라이언트·위젯 변환에서 기타 비용 250·기준 평균 200·증가율 0.25를 확인했다.
- 실제 수집·기존 42개 시나리오 경로도 통과했고 `unexpectedOrUnimplementedResponses=[]`다. frontend 소스는 변경하지 않았다. 이번 추가 검증은 API·위젯 데이터 계약이며 새 화면의 시각 검증은 아니다.
- 일반 그룹 상위 N은 49개 연결·2개 미연결이다. 잔존율과 마지막 세션 이벤트가 남아 있다. 전체 형식·장기 계약·운영 복구 수용 조건은 별도 남아 있다.
