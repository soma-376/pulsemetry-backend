# Codex Responses 토큰 의미 회귀 사례

ADR 0061과 공식 Responses 포함관계의 산식에서 기대값을 쓴 synthetic fixture다. `completed/`의 익명화된 실캡처 형식을 복제해 버전·표면·emitter·토큰 성분만 바꿨다. 실제 nonzero cache write 캡처라고 주장하지 않는다.

기본값은 input 100, output 20, cache read 40, cache write 10, reasoning 7, reported total 120이다. uncached는 50, derived total은 120이다. reasoning과 `tool_token_count`를 다시 더하지 않는다. 정확한 다섯 버전의 APP_SERVER와 기존 CLI 소스 범위, 미보고·명시 0·잘못된 wire·음수·포함관계 위반·Long overflow·미확인 emitter를 확인한다. 다른 최신 버전·Desktop·최신 CLI·토큰과 오류 충돌·완료 아닌 이벤트는 `completed/`의 경계 사례가 담당한다.

`FixtureSuite`의 가격 단계는 NONE이다. 이 fixture에서 비용이 null인 것은 가격표를 주입하지 않았기 때문이다. 가격표의 독립 산식 검증과 실제 API 재평가는 별도 테스트다.
