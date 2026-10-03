# 0019. manifest 재동기화는 세션과 정책 행을 잠그고 회전한다

## Status
Accepted — 허브 ADR 0008의 서버 구현 결정.

## Context
RT 회전은 이미 세션 단위로 직렬화된다. 활성 manifest 조회와 토큰 서명이 따로 수행되면 revision이 갈릴 수 있다.

## Decision
- UserAuthService.rotate의 트랜잭션 안에서 활성 정책 FOR SHARE, DTO 검증, session revision 갱신과 토큰 생성을 실행한다.
- telemetryctl 원본 JSON Schema를 빌드 시 jar에 포함해 저장된 JSON을 먼저 검증한다. DTO 기본값으로 누락을 숨기지 않는다.
- DTO를 토큰 생성 전에 검증하며 SQL·JSON·서명 실패는 이전 RT 소비까지 롤백한다.
- DTO 생성은 서버 상태만 증명한다. installation_manifest_assignments.applied_at을 갱신하지 않는다.
- 기존 관리자 경로에는 revision 필터를 연결하지 않는다(PROJ-109). 라이브러리 검증 코어만 제공한다.

## Alternatives Considered
- 조회와 회전 분리: 중간 실패와 정책 변경 시 불일치가 생겨 배제한다.
- 전 tenant 직렬화: 세션과 선택한 정책 행 잠금으로 충분하므로 쓰기 범위를 넓히지 않는다.

## Consequences/Tradeoffs
### Positive
실패 주입과 동시성 테스트로 rollback 및 동일 revision을 확인할 수 있다.
### Negative
트랜잭션 동안 정책 변경이 기다린다. 응답 유실은 서버 트랜잭션으로 해결하지 못한다.

## Acceptance Criteria
ManifestResyncApiTest가 정상·잘못된 정책·동시 활성화·SQL/서명 실패·중복 RT를 검증한다.
