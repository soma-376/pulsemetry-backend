# 0011. 라이브러리 모듈은 Spring 조립을 애플리케이션에 위임한다.

## Status

Accepted

## Context

[ADR 0008](0008-모듈-경계와-네임스페이스-규칙-확정.md) 규칙 2 는 모든 모듈을 `com.team376.pulsemetry`
아래에 두고, 애플리케이션의 메인 클래스만 예외로 그 루트에 두기로 했다. 컴포넌트 스캔이
`@SpringBootApplication` 의 패키지부터 훑기 때문이며, 영속성 모듈이 `@EntityScan` 없이 동작하는 것이
그 결과다. 규칙은 이 동작을 "우연이 아니라 전제"로 만들었다.

**그 전제에는 이면이 있다.** 스캔 범위가 저장소 전체이므로, 라이브러리 모듈에 붙인
`@Component` · `@Configuration` 은 **그 라이브러리를 클래스패스에 올린 모든 애플리케이션에서
자동으로 빈이 된다.** 그 앱이 그 기능을 쓰든 쓰지 않든 상관없다. 컴파일러가 잡지 않고 기동도 되며,
증상은 런타임에 다른 얼굴로 나타난다.

ADR 0008 규칙 3 이 이웃한 문제를 이미 적어 두었다 — *"Spring Boot 의 자동설정은 클래스패스에 무엇이
있는지만 보고 발동한다. 한 모듈이 JPA 와 메시징 클라이언트를 함께 끌고 오면, 메시징을 쓰지 않는
앱에도 그 자동설정이 걸린다."* 그 서술은 **의존성**을 다뤘고 스테레오타입은 다루지 않았다.
두 경로는 원인이 다르지만 결과가 같다.

`:libs:security` 를 만들면서 이것이 가정이 아니라 실물이 됐다.

- 이 모듈은 Spring Security 의 필터와 `AuthenticationProvider` 를 담는다. 의존에
  `spring-boot-starter-security` 를 쓰면 `spring-boot-security-autoconfigure` 가 딸려 오고,
  그 자동설정은 모든 요청을 잠그는 기본 `SecurityFilterChain` 을 만든다.
- `:apps:enrollment-api` 는 **해시 함수 하나 때문에** 이 모듈에 의존한다
  (`TelemetryTokenHasher` — 발급과 검증이 같은 연산을 써야 한다). 그 의존만으로
  `/v1/enroll` · `/v1/healthz` · 부트스트랩 스크립트 서빙이 전부 401 이 된다.
  **이 앱은 인증 계층을 켠 적이 없는데도** 그렇게 된다.

결정하지 않으면 이 형태를 세 번째 모듈이 우연히 정하게 된다. `:libs:security` 는 이 저장소의 첫
`:libs:` 신규 모듈이고, [모듈 지도](../module-map.md) 5 절이 예고한 단계 모듈 넷
(`telemetry-collector` · `-adapter` · `-enricher` · `-persistence`)과 인증 코어가 그 빌드 스크립트를
그대로 복제한다.

## Decision

- **`:libs:*` 는 Spring 스테레오타입을 두지 않는다.** `@Component` 와 그것을 메타 애너테이션으로 갖는
  `@Configuration` · `@Service` · `@Repository`, 그리고 `@ConfigurationProperties` 가 대상이다.
  라이브러리는 평범한 클래스를 내보내고 필요한 값은 **생성자로 받는다.**
- **`:libs:*` 는 Boot starter 를 의존하지 않는다.** 필요한 Spring 모듈만 직접 의존한다 —
  `spring-security-web` · `spring-security-core` 는 쓰고 `spring-boot-starter-security` 는 쓰지 않는다.
  starter 의 값어치는 자동설정인데, 라이브러리에게 그것은 값어치가 아니라 부작용이다.
- **빈 등록 · 필터 체인 배선 · 설정 바인딩은 `:apps:*` 가 한다.** 어떤 라이브러리를 켤지는 조립하는
  애플리케이션이 정한다.
- **예외는 둘이다.**
  1. **JPA 엔티티와 Spring Data 리포지토리 인터페이스.** 스캔으로 구현체를 만드는 것이 그 기술의
     동작 방식이라 대안이 없다. 현행 `:libs:enrollment-persistence` 가 여기 해당한다.
  2. **`testFixtures` 의 `@TestConfiguration`.** 테스트 소스셋은 애플리케이션 클래스패스에 오르지 않는다.

## Alternatives

### A. `@Configuration` 을 두되 `@ConditionalOnProperty` 로 잠근다

- 장점: 라이브러리가 자기 배선을 갖는다. 앱은 프로퍼티 하나로 켠다. Boot 관용에 가깝다.
- 단점: 안전장치가 **조건 애너테이션 하나**에 걸린다. 새 설정 클래스에 조건을 빠뜨리면 그 순간
  전역으로 살아나고, 그 실수는 컴파일도 기동도 막지 않는다. 지키는 비용이 모듈이 아니라 클래스마다 든다.
- 탈락 이유: 막으려는 사고의 원인이 "조용히 켜지는 것"인데, 해법이 다시 조용히 깨질 수 있는 형태다.
  ADR 0008 Alternative A 가 "컴파일러가 잡아주지 않는 종류의 실수"를 규칙으로 막기로 한 것과 같은 자리다.

### B. Boot 자동설정으로 제공한다 (`AutoConfiguration.imports`)

- 장점: 가장 관용적이다. 조건 평가와 순서 제어가 프레임워크의 몫이 된다. 라이브러리를 외부에
  공개한다면 이 형태여야 한다.
- 단점: **이 결정이 막으려는 그 동작을 정식 채널로 들여오는 것이다.** 조건을 아무리 잘 걸어도
  판단 주체가 클래스패스이지 앱이 아니다. 소비자가 전부 이 저장소 안에 있어서 자동설정이 해결하는
  문제(모르는 소비자를 위한 관용적 기본값)가 애초에 없다.
- 탈락 이유: ADR 0008 규칙 3 이 경계한 바로 그 성질이다. 얻는 것은 앱의 `@Bean` 몇 줄이고,
  잃는 것은 "무엇이 켜져 있는지 앱 코드만 보면 안다"는 성질이다.

### C. 규칙을 두지 않고 모듈마다 판단한다

- 장점: 지금 비용이 0 이다. 모듈이 둘일 때는 실제 충돌도 없었다.
- 탈락 이유: 판단할 사람이 다섯 모듈에 걸쳐 나뉜다. 게다가 이 규칙을 어긴 결과는 **그 모듈이 아니라
  그 모듈을 의존한 다른 앱에서** 드러난다 — `:libs:security` 의 starter 하나가 `enrollment-api` 를
  잠그는 것처럼. 사고와 원인이 다른 모듈에 있으면 모듈별 판단으로는 닿지 않는다.

## Consequences/Tradeoffs

### Positive

- 무엇이 켜져 있는지 **앱의 `@Bean` 목록만 보면 안다.** 클래스패스를 역추적할 필요가 없다.
- 라이브러리를 의존하는 것과 그 기능을 켜는 것이 분리된다. `enrollment-api` 가 해시만 쓰고
  인증 필터는 켜지 않는 것이 지금 그 예다.
- 규칙 준수를 **실측할 수 있다.** 런타임 클래스패스에 자동설정 아티팩트가 있는지 보면 된다
  (Acceptance Criteria).
- 라이브러리 테스트가 곧 조립의 예행이 된다 — 빈을 손으로 엮어야 하므로, 앱이 무엇을 해야 하는지가
  테스트에 드러난다.

### Negative

- **조립 앱이 `@Bean` 을 직접 쓴다.** `:apps:telemetry-ingest`(PROJ-105)는 필터 · 프로바이더 ·
  진입점 · 해셔를 손으로 엮게 된다. 자동설정이라면 없었을 코드다.
- Boot 생태계의 관용과 다르다. 이 저장소 밖에서 온 사람은 라이브러리에 배선이 없는 것을 결함으로 읽을 수 있다.
  ADR 로 남기는 이유가 그것이다.
- **강제할 자동 수단이 없다.** 스테레오타입이 하나 섞여 들어가도 컴파일과 기동은 통과하고,
  증상은 다른 모듈에서 나타난다. 리뷰가 유일한 방어선이다 —
  ADR 0008 이 "이 ADR 의 실효성은 리뷰 규율에 달려 있다"고 적은 것과 같은 한계다.
  기계 검사(ArchUnit) 도입은 [ADR 0008](0008-모듈-경계와-네임스페이스-규칙-확정.md) Follow-up 이
  `:apps:telemetry-ingest` 조립 시점으로 미뤘다.
- 설정 값을 생성자로 넘기므로, 같은 프로퍼티를 여러 앱이 각자 바인딩하게 된다.
  `pulsemetry.token-hash-secret` 가 그렇다 — 갈라지면 안 되는 것은 값이 아니라 **연산**이고,
  그쪽은 라이브러리가 한 벌로 갖는다.

## Follow-up

- `:apps:telemetry-ingest` 조립(PROJ-105) 시점에 이 규칙을 ArchUnit 으로 기계 검사할지
  ADR 0008 Follow-up 의 규칙 2 항목과 **함께** 본다. 두 검사 모두 여러 모듈이 한 클래스패스에
  오르는 그 자리에서 가장 싸다.
- 예외 ①(Spring Data 리포지토리)은 그 기술의 제약에서 나온 것이다. 리포지토리를 스캔 없이 등록하는
  경로를 쓰게 되면 예외를 다시 본다.
- 애플리케이션 모듈 안에서 `@Configuration` 을 어떻게 나눌지는 이 결정의 범위 밖이다.

## Acceptance Criteria

- `:libs:` 아래 main 소스에 Spring 스테레오타입이 없다.

  ```bash
  grep -rEn '^@(Component|Configuration|Service|Repository|ConfigurationProperties)' \
    libs/*/src/main/kotlin
  ```

- 라이브러리를 의존하는 앱의 런타임 클래스패스에 그 라이브러리가 끌어온 자동설정 아티팩트가 없다.

  ```bash
  ./gradlew :apps:enrollment-api:dependencies --configuration runtimeClasspath \
    | grep -Ei 'security'
  # spring-security-core · -crypto · -web 만 나온다.
  # spring-boot-security-autoconfigure · spring-security-config 가 보이면 규칙이 깨진 것이다.
  ```

## References

- [ADR 0008](0008-모듈-경계와-네임스페이스-규칙-확정.md) 규칙 2(패키지 유일성·스캔 전제)·규칙 3(자동설정과 클래스패스)
- [ADR 0007](0007-인증-계층으로-spring-security-사용.md) · [ADR 0010](0010-파이프라인-단계를-모듈-경계로-나눈다.md)
- [모듈 지도](../module-map.md)
