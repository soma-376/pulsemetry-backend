# 0030. 개발 Compose가 스키마와 시드를 준비한다

## Status
Accepted — ADR 0027·0028의 서버 기동 전제에 Compose 전용 초기화 경로를 추가한다. 운영 스키마 소유권과 기존 로컬 실행 경로는 유지한다.

현재 실행 경로는 [ADR 0031](0031-개발-시드는-Docker에서만-실행한다.md)이 부분 대체한다. 서버 자동 실행·공유 코어·호스트 수동 시드는 제거하며, 로컬 제한·완료 데이터 보존·스키마 소유권은 유지한다.

## Context
Spring 서버는 호스트에서 Gradle로 실행하면서 docker compose up만으로 개발 DB와 시드를 준비하려 한다.
기존 수동 시드는 스키마가 준비돼 있어야 하고 호스트의 Docker 검증과 루프백 주소에 의존한다.

## Decision
개발용 docker-compose.yml에 DB healthcheck 이후 실행하고 종료하는 dev-seed 서비스를 추가한다.
Spring 서버는 Compose에 추가하지 않는다. 시드 컨테이너에는 Docker 소켓을 마운트하지 않는다.
전용 명령은 명시적인 개발 Compose 표식을 요구하고 postgres·clickhouse 서비스의 고정 주소와 개발 계정만 사용한다.
기존 enrollment Flyway 리소스와 telemetry_ops 마이그레이터, ClickHouse 마이그레이터를 재사용한다.
마이그레이션 SQL을 복사하거나 시더가 새로운 업무 DDL을 소유하지 않는다. 운영 마이그레이션 실행 주체는 바꾸지 않는다.
기존 Kotlin 시드 생성과 ready 기록을 재사용한다. 완료 데이터는 보존하고 미완료 적재는 실패로 드러낸다.
Compose 날짜 미지정은 서울 기준 실행일이다. 수동 seedApply·seedVerify의 날짜는 계속 명시한다.
개발 대시보드 DB 계정 준비도 재사용한다. 로그인 키·Spring 설정은 이 데이터 준비 작업의 범위 밖이다.

## Alternatives Considered
DB 초기화 SQL에 시나리오를 복제하면 Kotlin과 두 벌의 데이터 정의가 생긴다.
Spring 서버 전체를 Compose에 넣는 방식은 호스트에서 Gradle로 실행한다는 요청과 맞지 않는다.

## Consequences/Tradeoffs
### Positive
빈 개발 DB에서도 Spring 서버 기동 없이 시나리오를 준비한다.
### Negative
최초 실행에 시드 이미지 빌드가 필요하다. up -d는 적재 완료를 보장하지 않으므로 종료 코드와 로그를 확인한다.
down -v는 이 Compose 프로젝트의 DB 볼륨 전체를 지우며 시드 외에 직접 추가한 데이터도 삭제한다.
서버의 기존 local 시더는 남지만 완료된 A/B/C는 재적재하지 않는다.
