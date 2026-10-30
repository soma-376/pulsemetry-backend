# Claude Code 2.1.311 — 이벤트 계약 확인 자료

2026-10-30 로컬 SQLite의 `claude_code`·`2.1.311`·`api_request` 26건을 읽기 전용으로 확인했다.
resource의 서비스·버전, 본문 이벤트 이름, 사용량과 시간 필드의 wire만 조사했다. 대화·프롬프트·도구 본문·개인 식별자는 추출하지 않았다.

- 26건 모두 `service.name=claude-code`, `service.version=2.1.311`, 본문 `claude_code.api_request`, 속성 `event.name=api_request`였다.
- 네 토큰 성분·`cost_usd_micros`·`duration_ms`·`ttft_ms`는 모두 `intValue`, `cost_usd`는 `doubleValue`였다.
- 서비스·버전·이름과 이 여덟 필드만 canonical JSON으로 계산한 SHA-256은 `13c53ed25fa5a94a07561f276f354263b1527a14fff562be4f16a6fb25036dd5`다. 전체 원문 해시가 아니다.
- 같은 날 확인한 [공식 모니터링 문서](https://code.claude.com/docs/en/monitoring-usage#map-input-tokens-to-opentelemetry-genai-semantic-conventions)는 OTel 입력이 API usage와 같고 캐시 읽기·쓰기를 제외한다고 정의한다. [공식 캐시 성분 정의](https://platform.claude.com/docs/en/build-with-claude/prompt-caching#tracking-cache-performance)와 일치한다.

`logs-api-synthetic`는 이 wire 구조를 독립 숫자와 가짜 식별자로 다시 작성한 합성 fixture다. 실제 캡처·실제 사용량이라고 주장하지 않는다.
input 100 + read 400 + create 10 + output 20 = total 530이며 미캐시 입력은 100이다. 기존 단일 카탈로그의 Opus 5.5 단가로 환산액은 0.000930 USD다. 보고 비용 0.002 USD와는 별도 값이다.

ADR 0074에 따라 이 자료는 wire의 근거이며 버전 허용 목록이 아니다. 단일 `ClaudeCodeProfile`은 알려진 이름·필드 계약을 producer 버전과 무관하게 적용한다. `api_request`만 primary 사용량이며 등록된 스팬·메트릭은 대조용이다. 추가 캐시 TTL 성분은 전체 cache creation의 부분집합이므로 더하지 않으며, 검증되지 않은 TTL별 가격 계산도 추가하지 않는다. 공급자 신원과 실제 청구액을 추정하지 않는다.

`ClaudeEventContractTest`는 독립 산식·이전 generic과의 관측 ID 보존·명시 0·잘못된 wire·누락·음수·본문 충돌·미래/누락 버전·다른 이벤트 경계를 검증한다. 공유 normalizer 판 증가는 이번 계약 변경 전체에서 한 번 적용한다.
