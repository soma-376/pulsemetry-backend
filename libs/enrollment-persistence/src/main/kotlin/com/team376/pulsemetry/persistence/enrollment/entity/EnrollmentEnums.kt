package com.team376.pulsemetry.persistence.enrollment.entity

/**
 * enrollment 스키마의 enum 컬럼들.
 *
 * DB 는 Postgres native enum 타입으로 표현하고 (ADR 0009 — 공유 RDS 의 수동 부트스트랩 DDL 과
 * 형태를 맞추기 위해 ADR 0004 의 varchar+CHECK 를 대체), JPA 는
 * `@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 으로 매핑한다.
 * 상수의 `name()` 이 곧 DB enum 라벨이다 — dbdiagram.dbml 의 라벨이 소문자이므로 상수도
 * 소문자로 둔다. 대문자로 바꾸면 INSERT 가 enum 라벨 불일치로 실패한다.
 */

enum class TenantStatus { active, suspended, terminated }

enum class MemberRole { owner, admin, member }

enum class MemberStatus { invited, active, suspended }

/**
 * 클라이언트는 Go 의 `runtime.GOOS` 를 그대로 보낸다(macOS 는 `darwin`).
 * 서버가 `macos` 로 정규화한 뒤에야 이 타입이 된다 (PLAN.md §6.2 4단계).
 */
enum class Platform { windows, macos, linux }

enum class InstallationStatus { active, revoked }
