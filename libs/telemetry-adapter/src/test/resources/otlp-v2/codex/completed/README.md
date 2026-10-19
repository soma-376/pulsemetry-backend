# Codex 0.162.0-alpha.17.2 — 확인된 완료 로그 범위

이 디렉터리의 `logs-*.otlp.jsonl`은 실제 로컬 OTLP file exporter에서 선택한 **1개 완료 레코드의 익명화본**이다. `synthetic-*`는 그 사본의 버전·표면·위치·필드·wire 타입만 바꾼 경계 사례다. 기대값은 원본 성분과 ADR 0020 §4·부록 A.3에서 썼으며 구현 출력으로 생성하지 않았다.

## 원천과 범위

- 실제 producer: `codex-app-server`, `service.version=0.162.0-alpha.17.2`, scope `codex_otel.log_only`.
- 원본 export SHA-256: `7c9173a06e87d86b64f85f4c59c994fb828970ac55ff0fc805a6bf2b2f457f50`.
- 선택 레코드 사본 SHA-256: `94282488f7a6b01df525219d19c3519ef51a63be96cacfd0c1bcc9ff863ee5aa`.
- 익명화 fixture SHA-256: `cfadedeae51a8c83141748429283f888f46fbcdc1de1dcb73933ecdecb524126`.
- 저장소 `scripts/otlp-fixtures/sanitize.py` 적용 후 `gate.py --source <선택 원본 사본> <이 디렉터리>` 통과. ID·이메일·자유 텍스트·시각만 기존 익명화 규칙으로 바꿨다. 모델·버전·분기·wire 타입·토큰 값은 보존했다. 원본은 저장소에 넣지 않았다.
- 원본에 `timeUnixNano`는 없고 `observedTimeUnixNano`만 있다. ADR 0020 §2의 observed fallback을 그대로 검증한다.

## 소스 대조

공개 openai/codex 소스 커밋 `cec8c6fb876e90b20250ec740e970e3f33b7396f`와 로컬 태그 `rust-v0.162.0-alpha.18`의 `codex-rs/otel/src/events/session_telemetry.rs:1131–1149`에서 `sse_event_completed`를 확인했다. 이 공개 커밋이 배포 빌드 전체와 동일하다고 주장하지 않는다. 실제 캡처의 emitter 문자열 `event otel/src/events/session_telemetry.rs:1132`와 완료 분기·아래 필드·wire 타입이 일치하는 **그 로그만** 지원한다. `CodexCompletedProfile`은 정확한 버전·APP_SERVER·event.name·event.kind·emitter를 모두 제한한다. 다른 로그, CLI, Desktop, 스팬, 메트릭을 검증 완료로 확장하지 않는다.

| 필드 | 확인한 emitter 값 | 실제 wire / 저장 필드 |
|---|---|---|
| event.name / event.kind | codex.sse_event / response.completed | string / model.response.usage |
| input_token_count | %usage.input_tokens | string / tokens_input |
| output_token_count | %usage.output_tokens | string / tokens_output |
| cached_token_count | usage.cached_input_tokens | int / tokens_cache_read |
| cache_write_token_count | usage.cache_write_input_tokens | int / tokens_cache_create |
| reasoning_token_count | usage.reasoning_output_tokens | int / tokens_reasoning |
| tool_token_count | %usage.total_tokens | string / tokens_total_reported |
| ttft_ms | Option<i64> | int / ttft_ns = ms × 1,000,000 |
| conversation.id / model | shared.rs:14 log_event 공통 속성 | string / session_id 및 model |

ADR 0061과 [PROFILE-EVIDENCE.md 10절](../PROFILE-EVIDENCE.md#10-2026-10-16-responses-토큰-의미-검증)에 따라 이 완료 분기의 토큰 포함관계는 `codex-responses-inclusive-v1`으로 연결한다. input은 cache read/write를 포함하고 output은 reasoning을 포함한다. 실캡처의 uncached는 `200705−199936−0=769`, 총계는 `200705+267=200972`다. provider는 계속 unknown이다. 이 fixture의 가격 단계는 NONE이고, 실제 현재 참조 환산은 별도 가격 프로파일이 적용한다. eventName 자체와 계정·이메일·본문은 새 metadata 허용 목록에 넣지 않는다.

## 회귀 경계

실캡처 1개에 더해 미래/다른 버전, Desktop, CLI, 다른 로그, 다른 emitter, 완료 아닌 이벤트는 generic으로 남는다. int로 바뀐 input 및 음수는 해당 성분만 null + invalid_measurement, 충돌 alias는 primary가 아니며, 토큰+오류는 ambiguous diagnostic, 명시적 0은 유효한 보고, 토큰 없는 완료는 stream event다. 스팬·메트릭 프로파일은 null이다. 과거 프로파일 테스트는 그대로 유지한다.

토큰 의미는 ADR 0061, 가격은 ADR 0060을 따른다. mapping_version은 `codex-completed-v2`다. observation ID 버전은 바꾸지 않아 같은 입력 재처리는 더 높은 row_version으로 교체된다.
