# Architecture Decision Records

> 레포 전체 구조, 불변 규칙, 권위 문서 목록은 루트의 [AGENTS.md](../../AGENTS.md)를 참고한다.

| 번호 | 제목 | Status |
|---|---|---|
| 0001 | [Kotlin 기반 Spring Framework 채택](0001-Kotlin-기반-Spring-Framework-채택.md) | Accepted |
| 0002 | [멀티모듈 프로젝트 구축](0002-멀티모듈-프로젝트-구축.md) | Accepted |
| 0003 | [enrollment API 계약과 2단 토큰 모델](0003-enrollment-API-계약과-2단-토큰-모델.md) | Accepted |
| 0004 | [Flyway 마이그레이션과 varchar + CHECK 스키마 관리](0004-Flyway-마이그레이션과-varchar-CHECK-스키마-관리.md) | Accepted |
| 0005 | [설치 부트스트랩 스크립트와 바이너리 서빙](0005-설치-부트스트랩-스크립트와-바이너리-서빙.md) | Accepted |
| 0006 | [데이터 파이프라인을 백엔드 프로젝트에 병합](0006-데이터-파이프라인을-백엔드-프로젝트에-병합.md) | Superseded by 허브 ADR 0004 |
| 0007 | [인증 계층으로 spring security 사용](0007-인증-계층으로-spring-security-사용.md) | Accepted |
| 0008 | [모듈 경계와 네임스페이스 규칙 확정](0008-모듈-경계와-네임스페이스-규칙-확정.md) | Accepted |
| 0009 | [enrollment 스키마 native enum 채택](0009-enrollment-스키마-native-enum-채택.md) | Accepted |
| 0010 | [파이프라인 단계를 모듈 경계로 나눈다](0010-파이프라인-단계를-모듈-경계로-나눈다.md) | Accepted |

Status 열은 각 ADR Status 줄의 **첫 토큰**만 싣는다. 부분 대체·부연은 해당 파일에서 확인한다.
Status 첫 토큰이 바뀌는 커밋에서는 이 표도 같은 커밋에서 갱신한다.

새 ADR을 작성할 때는 다음 미사용 번호(`0011-...`)를 사용하고
[`0000-adr-template.md`](0000-adr-template.md)의 구조를 따른다. 파일명은 **한국어 슬러그**다.

섹션 순서는 다음과 같다.

```
# NNNN. 결정을 서술하는 평서문 제목

## Status
## Context
## Decision
## Alternatives
## Consequences/Tradeoffs
### Positive
### Negative
## Follow-up
```

작성일은 문서에 적지 않는다. `git log --follow <파일>`로 확인한다.

다른 레포의 코드·계약에 걸리는 결정은 이 레포가 아니라 **문서 허브(`soma-376/docs`)의 `adr/`**에 쓴다 —
*"이 결정을 뒤집으려면 몇 개 레포의 PR이 필요한가."* 둘 이상이면 허브다(템플릿 작성 규칙).
