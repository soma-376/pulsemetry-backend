package com.team376.pulsemetry.persistence.enrollment.entity

/**
 * enrollment 스키마의 enum 컬럼들.
 *
 * DB 는 varchar + CHECK 로 enum 을 표현하고 JPA 는 `@Enumerated(EnumType.STRING)` 으로 매핑한다 (PLAN.md L5).
 * `@Enumerated(STRING)` 은 상수의 `name()` 을 그대로 저장하므로 **상수 이름이 곧 DB 값**이다.
 * dbdiagram.dbml 의 값이 소문자이고 CHECK 제약도 소문자를 강제하므로 상수도 소문자로 둔다.
 * 대문자로 바꾸면 INSERT 가 CHECK 제약에 걸려 실패한다.
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
