# 0028. local 시드 전에 기존 ClickHouse 마이그레이션을 실행한다

## Status
Accepted — ADR 0027의 ClickHouse 스키마 사전 준비 요구를 대체한다. local 제한과 스키마 소유권은 유지한다.
개발 Compose의 PostgreSQL·ClickHouse 초기화 경로는 [ADR 0030](0030-개발-Compose가-스키마와-시드를-준비한다.md)이 추가한다.

현재 실행 경로는 [ADR 0031](0031-개발-시드는-Docker에서만-실행한다.md)이 부분 대체한다. 서버 자동 실행·공유 코어·호스트 수동 시드는 제거하며, 로컬 제한·완료 데이터 보존·스키마 소유권은 유지한다.

## Context
빈 로컬 DB에서 enrollment-api를 실행하면 PostgreSQL은 Flyway로 준비되지만 ClickHouse는 비어 있어
자동 시드의 테이블 확인에서 기동이 실패했다. local 서버 한 번 실행으로 개발 데이터를 준비하는 요구를 충족하지 못했다.

## Decision
로컬 DB 검증·시드 잠금을 얻은 뒤, 자동 시드 경로에서 telemetry-persistence의
ClickHouseSchemaMigrator를 호출하고 테이블을 확인한 다음 적재한다.
DDL을 복사하거나 시드 모듈에 새로 작성하지 않는다. 기존의 순서 있는 멱등 DDL을 그대로 실행한다.
수동 seedApply·seedVerify는 기존대로 준비된 스키마를 사용한다.
기존 데이터 삭제·시드 초기화·운영 기동 경로의 변경은 없다.

## Alternatives Considered
사용자에게 telemetry-ingest를 먼저 실행하도록 요구하면 local 자동 준비가 여러 수동 단계에 의존한다.
테이블 부재를 무시하면 정상 기동처럼 보이면서 대시보드 시드가 없는 상태를 숨긴다.

## Consequences
### Positive
빈 ClickHouse에서도 local 서버 기동으로 스키마와 시드가 함께 준비된다.
### Negative
개발 시드 코어에 telemetry-persistence 의존성이 추가된다. DB 접근·DDL 실패 시 기동 실패는 유지한다.
