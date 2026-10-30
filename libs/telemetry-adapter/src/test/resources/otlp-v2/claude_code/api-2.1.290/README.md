# Claude Code 2.1.290 — api_request 확인 범위

**현재 적용은 ADR 0074를 따른다.** 아래 버전 제한 설명은 당시 검증 범위의 기록이다. 단일 `ClaudeCodeProfile`은 producer 버전과 무관하게 알려진 이벤트 계약을 적용한다. fixture 원문은 보존하고, 버전만 다른 사례는 같은 사용량으로, 등록된 다른 이벤트는 해당 타입·사용량 제외 역할로 기대값을 개정했다.

`logs-api-01`부터 `08`은 로컬 OTLP file exporter에서 제한 복사한 실제 `api_request` 8건의 익명화본이다. 원본은 저장소에 넣지 않았다. `scripts/otlp-fixtures/sanitize.py`를 적용하고 `gate.py --source <선택한 원본 사본> <이 디렉터리>`로 개인 식별자·자격 증명·자유 텍스트가 남지 않았음을 검사한다. 모델·버전·토큰·비용·wire 타입·요청 간 세션 관계는 보존했다. 기대값은 구현 출력이 아니라 입력의 성분과 아래 정의로 계산했다.

- 제한 원본 8행 사본 SHA-256: `40911bf3d60d624f1d142afe7c3ed928e218fa581cd20caf3ab32e80e7a7510c`.
- 8행 익명화 사본 SHA-256: `3f22d4bb36f04d018b150fa8b91ec23d9bbc968240aca893fee58b56edfda872`.

## 적용 범위와 근거

- producer `service.name=claude-code`, 정확한 `service.version=2.1.290`의 `api_request`만 등록한다. 광범위한 기존 `ClaudeCodeProfile`의 버전 목록에는 추가하지 않는다. 다른 로그, 스팬, 메트릭은 generic으로 남는다.
- 2026-10-16에 확인한 [공식 Claude Code 모니터링 문서](https://code.claude.com/docs/en/monitoring-usage)는 `api_request`의 `input_tokens`가 캐시 읽기·쓰기를 제외한 입력이며, `cache_read_tokens`와 `cache_creation_tokens`는 각 캐시 성분이라고 명시한다. 같은 문서의 입력 토큰 변환 절도 API usage 블록과 OTel 이벤트의 이 대응을 설명한다.
- [공식 Prompt caching 문서](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)의 usage 성분 정의도 입력 전체를 미캐시 입력·캐시 읽기·캐시 쓰기의 합으로 정한다. 실제 8건의 네 성분은 모두 `intValue`이고 기존 확인 버전과 같다. 기존 `claude-code-exclusive-v1` 의미를 이 이벤트·정확한 버전에 한정해 적용한다.
- 결과: `tokens_input_uncached = input_tokens`, `tokens_total_derived = input_tokens + cache_read_tokens + cache_creation_tokens + output_tokens`. 네 성분 중 유효한 값이 하나라도 없으면 완전한 총계를 만들지 않는다. 실제 8건의 독립 합계는 **463,246** 토큰이다.
- `cost_usd`는 `doubleValue`, `cost_usd_micros`는 `intValue`다. 기존 1 마이크로달러 충돌 허용 규칙을 그대로 적용하며, 정확한 micros 합계는 **0.621688 USD**다. 이는 producer가 보고한 추정 비용이다. 가격 카탈로그·공급자 근거가 생긴 것이 아니므로 `cost_estimated_usd`는 null이며 대시보드 비용을 이 값으로 대체하지 않는다.
- Claude Code 소스는 공개되지 않았다. 공식 정의와 실제 캡처가 일치하는 이 이벤트만 확인했으며, 다른 이벤트나 미래 버전의 포함관계까지 입증했다고 주장하지 않는다.

## 회귀 사례

실제 8건에 더해 13개 파생 경계를 고정한다. 이전·미래 미확인 버전, 다른 이벤트 세 종류는 generic이다. 본문 이름 충돌은 ambiguous, 본문 없음은 속성 기준이다. 문자열로 바뀐 토큰과 음수는 해당 성분만 null이며 invalid_measurement가 남는다. 비용 충돌은 보고 비용만 null이고 토큰은 유지한다. 캐시 성분 누락은 총계 null, 사용량 필드 없음은 primary가 아니며 명시적 0은 유효한 사용량이다. 파생 사례는 원본을 수정하지 않고 그 익명화 사본의 해당 필드만 바꾼다.

원본 8건의 서로 다른 모델은 2개, 익명화 후 세션은 1개다. 최초 실수집 시각과 별도의 E2E 고정 시각은 fixture 생성 보고서에서 구분한다. 이 디렉터리의 실제 fixture는 익명화 시각만 사용한다.

정규화 변경에 따라 `NORMALIZER_REV`를 2→3으로 올린다. observation ID는 유지하므로 이전 generic 행을 재처리하면 더 높은 row_version의 매핑 행으로 교체할 수 있다. E2E에서는 최초 실패를 보존하기 위해 별도 새 조직에 최종 입력을 제출한다.
