# 0001. Kotlin 기반 Spring Framework를 채택한다.

## Status
Accepted

## Context
백엔드 서비스 구현에 착수하기 전에 사용할 언어와 프레임워크를 결정해야 한다.

## Decision
- Kotlin 기반 Spring Framework를 채택한다.
- 신규 프로젝트이므로 현재 가장 먼 시점까지 지원되는 Spring Boot 4.1(Spring Framework 7 기반)을 채택한다.
- Kotlin 버전은 Spring Boot 4.1 BOM이 관리하는 대응 버전인 2.3을 채택한다. ([spring-boot-dependencies 4.1.0 POM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.0/spring-boot-dependencies-4.1.0.pom))

## Alternatives
### A. 타 백엔드 프레임워크 (Node.js/NestJS, Go, Python/FastAPI 등 비 JVM 계열)
- 장점: 런타임이 가볍고 기동이 빠르며 메모리 사용량이 적다. 프레임워크가 요구하는 사전 지식이 적어 초기 개발 속도를 내기 쉽다.
- 단점: 팀에 축적된 경험이 없어 러닝커브가 존재한다.
- 탈락 이유: AI를 활용해 개발하더라도, 세부적인 부분에서 무엇이 잘못되었는지 판단하고 능동적으로 수정을 요청하려면 해당 생태계에 대한 이해가 필요하다. 현재 팀에는 그 이해가 부족하다고 판단했다.

### B. Java
- 장점: Java + Spring 생태계의 자료가 압도적으로 많고, 익숙한 언어이므로 러닝커브가 없다.
- 단점: 코드가 장황하다.
- 탈락 이유: AI와 함께 작업하는 비중이 크므로, 토큰 사용량을 조금이라도 줄이기 위해 Java를 포기했다.

## Consequences/Tradeoffs
### Positive
- Kotlin을 채택하여 Java 대비 코드가 간결해지고, 그만큼 AI 사용 비용을 절약할 수 있다.
  - Meta는 Android 코드베이스를 Kotlin으로 마이그레이션하며 코드 라인 수가 11% 감소했다고 밝혔다. ([Meta Engineering](https://engineering.fb.com/2022/10/24/android/android-java-kotlin-migration/))
  - Java → Kotlin 마이그레이션을 조사한 연구 자료에서도 측정값의 편차는 있으나 코드량이 감소한다는 추세는 공통적으로 나타난다. ([arXiv:2003.12730](https://arxiv.org/pdf/2003.12730))
- Kotlin은 Java와 완전히 상호운용되므로, 기존 Spring/Java 생태계의 라이브러리와 레퍼런스를 그대로 활용할 수 있다. 대안 A와 달리 생태계를 새로 학습할 필요가 없다.
- 언어 차원의 null 안전성 덕분에 NPE 계열 런타임 오류를 상당 부분 컴파일 타임에 차단할 수 있다.

### Negative
- Kotlin 언어 자체에 대한 이해도가 상대적으로 낮다.
  - Java 계열 언어이므로 어느 정도 감수할 수 있다고 판단했다.
- 빌드 시간이 Java 대비 느리다.
- Kotlin과 JPA(Hibernate)의 궁합이 좋지 않다.
  - Kotlin이 지향하는 불변·final·null 안전 모델과 JPA가 요구하는 가변·상속 가능·리플렉션 기반 모델이 서로 반대 방향이다.
  - Kotlin의 철학에 어긋나는 방식으로 코드를 작성해야 한다.
- Spring Boot 4.1과 Kotlin 2.3은 출시된 지 얼마 되지 않아, 서드파티 호환성 정보와 레퍼런스가 상대적으로 부족하다. 문제가 발생했을 때 참고할 선례가 적다.

## Follow-up
- 러닝커브를 고려하여 ORM은 우선 JPA를 사용하되, 이후 Exposed 같은 대안을 검토한다.
- JPA를 도입하는 시점에 `kotlin("plugin.jpa")`(all-open / no-arg)를 빌드에 추가한다.
