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
| 0011 | [라이브러리 모듈은 Spring 조립을 앱에 위임한다](0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md) | Accepted |
| 0012 | [원본 아카이브를 S3 에 쓰고 파일 구현은 로컬에만 남긴다](0012-원본-아카이브를-S3에-쓰고-파일-구현은-로컬에만-남긴다.md) | Accepted |
| 0013 | [정규화 입력은 protobuf 이고 원본 해시는 정규 JSON 으로 되살린다](0013-정규화-입력은-protobuf-이고-원본-해시는-정규-json-으로-되살린다.md) | Accepted |
| 0014 | [단계 모듈 사이에 데이터 타입 간선을 둔다](0014-단계-모듈-사이에-데이터-타입-간선을-둔다.md) | Accepted |
| 0015 | [ClickHouse DDL 은 번호 붙은 멱등 파일이고 기동 시 전량 적용한다](0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md) | Accepted |
| 0016 | [조립 앱은 인증 체인과 단계 호출을 배선하고 스키마 적용 실패를 견디며 뜬다](0016-조립-앱은-인증-체인과-단계-호출을-배선하고-스키마-적용-실패를-견딘다.md) | Accepted |
| 0017 | [정규화 불변 규칙과 enrichment_json 승격 금지는 이 저장소가 정한다](0017-정규화-불변-규칙과-enrichment-json-승격-금지는-이-저장소가-정한다.md) | Accepted |
| 0018 | [사용자 인증은 회전 세션과 명시적 키링으로 운영한다](0018-사용자-인증은-회전-세션과-명시적-키링으로-운영한다.md) | Accepted |
| 0019 | [manifest 재동기화는 세션과 정책 행을 잠그고 회전한다](0019-manifest-재동기화는-세션과-정책-행을-잠그고-회전한다.md) | Accepted |
| 0020 | [정규화 계약 2판은 관측을 식별하고 의미 컬럼으로 교체 저장한다](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) | Accepted |
| 0021 | [수집 운영 기록은 ClickHouse ledger 와 RDS telemetry_ops 스키마에 두고 그 RDS DDL 은 enrollment-api 가 적용한다](0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md) | Proposed |
| 0022 | [대시보드 API 는 이 저장소의 별도 앱이고 인증은 포트 뒤에서 기본 거부하며 쓰기는 자기 캐시에 한정한다](0022-대시보드-API-는-별도-앱이고-인증은-포트-뒤에서-기본-거부한다.md) | Proposed |
| 0023 | [대시보드 snapshot 은 dashboard_cache 의 불변 복사본과 RDS manifest 이고 대시보드 앱이 그 DDL 을 적용한다](0023-대시보드-snapshot-은-dashboard-cache-의-불변-복사본과-manifest-이고-대시보드-앱이-그-DDL-을-적용한다.md) | Proposed |
| 0024 | [조직별 보존 삭제 경계는 RDS 가 진실원이고 분석 INSERT 는 ClickHouse fence 를 서버에서 다시 검사한다](0024-조직별-보존-삭제-경계는-RDS-가-진실원이고-분석-INSERT-는-ClickHouse-fence-를-서버에서-다시-검사한다.md) | Proposed |
| 0025 | [개발용 시드를 독립 Kotlin 도구 모듈로 관리한다](0025-개발용-시드를-독립-Kotlin-도구-모듈로-관리한다.md) | Accepted |
| 0026 | [사용자 인증으로 프론트 관리 요청을 연결한다](0026-사용자-인증으로-프론트-관리-요청을-연결한다.md) | Accepted |
| 0027 | [local 프로필에서 개발 시드를 초기화한다](0027-local-프로필에서-개발-시드를-초기화한다.md) | Accepted |
| 0028 | [local 시드 전에 기존 ClickHouse 마이그레이션을 실행한다](0028-local-시드-전에-기존-클릭하우스-마이그레이션을-실행한다.md) | Accepted |
| 0029 | [온보딩은 저장된 정책과 벤더 선택으로 완료한다](0029-온보딩은-저장된-정책과-벤더-선택으로-완료한다.md) | Accepted |
| 0030 | [개발 Compose가 스키마와 시드를 준비한다](0030-개발-Compose가-스키마와-시드를-준비한다.md) | Accepted |
| 0031 | [개발 시드는 Docker에서만 실행한다](0031-개발-시드는-Docker에서만-실행한다.md) | Accepted |
| 0032 | [온보딩 완료는 조직에 저장하고 시드는 A/B/C만 유지한다](0032-온보딩-완료는-조직에-저장하고-시드는-ABC만-유지한다.md) | Accepted |
| 0033 | [로그인은 온보딩과 분리하고 최초 정책에서 manifest를 생성한다](0033-로그인은-온보딩과-분리하고-최초-정책에서-manifest를-생성한다.md) | Accepted |
| 0034 | [조직 생성과 빈 수집 요약을 원자적으로 초기화한다](0034-조직-생성과-빈-수집-요약을-원자적으로-초기화한다.md) | Accepted |
| 0035 | [공통 벤더와 제품과 플랜은 데이터베이스에서 관리한다](0035-공통-벤더와-제품과-플랜은-데이터베이스에서-관리한다.md) | Accepted |
| 0036 | [구성원 역할은 admin과 member 사이에서만 바꾸고 owner와 자기 역할은 바꾸지 않는다](0036-구성원-역할은-admin과-member-사이에서만-바꾸고-owner와-자기-역할은-바꾸지-않는다.md) | Accepted |
| 0037 | [메일은 outbox에 적재하고 enrollment-api의 발송 작업이 SMTP로 보낸다](0037-메일은-outbox에-적재하고-enrollment-api의-발송-작업이-SMTP로-보낸다.md) | Accepted |
| 0038 | [초대 메일은 코드를 본문과 링크의 fragment에만 싣고 발송 상태를 발급과 따로 낸다](0038-초대-메일은-코드를-본문과-링크의-fragment에만-싣고-발송-상태를-발급과-따로-낸다.md) | Accepted |
| 0039 | [비동기 작업은 공통 작업 기록에 남기고 상태 조회는 dashboard-api가 한다](0039-비동기-작업은-공통-작업-기록에-남기고-상태-조회는-dashboard-api가-한다.md) | Proposed |
| 0040 | [설치 보고는 최신 상태 한 행과 수집 구간 이력으로 저장한다](0040-설치-보고는-최신-상태-한-행과-수집-구간-이력으로-저장한다.md) | Proposed |

Status 열은 각 ADR Status 줄의 **첫 토큰**만 싣는다. 부분 대체·부연은 해당 파일에서 확인한다.
Status 첫 토큰이 바뀌는 커밋에서는 이 표도 같은 커밋에서 갱신한다.

새 ADR을 작성할 때는 다음 미사용 번호(`0041-...`)를 사용하고
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

ADR 0016의 계약 밖 경로는 허브 OTLP 계약 §8에 맞춰 `404 text/plain`으로 거부한다.
내부 ERROR 디스패치는 원래 서버 오류 응답을 보존한다.
OTLP 배치 재전송과 스키마 적용 재시도는 별개이며, RDS 장애는 기동 전·후를 구분한다.
