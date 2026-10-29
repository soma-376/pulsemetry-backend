# Desktop 0.165.0 완료 로그의 합성 회귀 사례

[ADR 0072](../../../../../../../../docs/adr/0072-Desktop-0.165.0의-확인된-완료-로그만-사용량으로-매핑한다.md)와 [PROFILE-EVIDENCE §13](../PROFILE-EVIDENCE.md#13-desktop-01650의-완료-분기)의 정확한 완료 emitter·여섯 필드 wire 타입을 재현한다. 실제 원문 추출본이 아니다. 서비스·버전·emitter·분기·wire 구조만 확인한 사실이며 토큰 숫자·시각·세션은 독립적인 합성 값이다. 실제 세션·프롬프트·응답·메일·토큰을 복사하지 않았다.

input1000/read400/write100/output200/reasoning150/total1200에서 uncached500, derived total1200을 손계산했다. reasoning과 reported total을 다시 더하지 않는다. 현재 단일 카탈로그의 gpt-6-astra 요율로 `(500×10 + 400×1 + 100×12.5 + 200×50) / 1,000,000 = 0.016650 USD`다. 기대값은 구현 실행 결과로 생성하지 않았다.

`CodexDesktopCompletedProfileTest`가 이 파일의 기대값·신원·해시·결정성 불변식을 검증하고, 다른 surface/버전/emitter/분기·오류 충돌·누락/잘못된 wire·명시0 경계를 별도로 검증한다. 스팬·메트릭·다른 이벤트에 대한 지원 근거가 아니다.
