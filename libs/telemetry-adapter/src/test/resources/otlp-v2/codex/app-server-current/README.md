# 현재 APP_SERVER 완료 로그의 합성 회귀 사례

[ADR 0074](../../../../../../../../docs/adr/0074-producer-버전-대신-이벤트-계약으로-정규화한다.md)와
[PROFILE-EVIDENCE §14](../PROFILE-EVIDENCE.md#14-app_server-01650과-0166의-확인된-완료-분기)의 관측 예시와 완료 분기·wire 구조를 재현한다.
실제 캡처 파일이 아니다. 실제 프롬프트·응답·식별자·사용량을 복사하지 않았으며 시각·신원·숫자는 독립 합성 값이다.

세 파일은 `codex-app-server`의 0.165.0·0.166.0·0.166.0-alpha.5.1을 예시로 사용한다. 버전은 허용 조건이 아니다.
input1000/read400/write100/output200/reasoning150/total1200에서 uncached500, derived total1200을 손계산했다.
reasoning과 reported total은 다시 더하지 않는다. 기존 단일 카탈로그의 gpt-6-astra 요율로
`(500×10 + 400×1 + 100×12.5 + 200×50) / 1,000,000 = 0.016650 USD`다.
기대값은 구현 출력을 생성해 복사하지 않았다.

`CodexEventContractTest`는 위 값과 observation ID 보존을 검증하고, CLI·Desktop·임의 버전·변경/누락 emitter에서도 같은 계약이 매핑됨을 확인한다. 다른 분기·오류 충돌·누락/잘못된 wire·명시0·미등록 모델의 경계를 따로 검증한다.
