# Dashboard API 실행

현재 제공 범위와 미구현 항목은 [진행 기록](dashboard-api-progress.md)을 따른다.

## 설정

`./gradlew :apps:dashboard-api:bootRun`은 기본 8081 포트를 사용한다.

| 설정 | 의미 |
|---|---|
| `PULSEMETRY_DB_URL`, `PULSEMETRY_DB_USERNAME`, `PULSEMETRY_DB_PASSWORD` | PostgreSQL 연결 |
| `pulsemetry.dashboard.tenant-id` | 필수 고정 tenant UUID |
| `pulsemetry.dashboard.issuer` | JWT issuer |
| `pulsemetry.dashboard.active-kid` | 활성 키 ID |
| `pulsemetry.dashboard.private-key-file` | RSA 2048비트 이상 PKCS8 PEM 경로 |
| `pulsemetry.dashboard.public-key-files.<kid>` | 허용된 X509 PEM 공개키 경로 |
| `pulsemetry.dashboard.allowed-origins` | 실제 frontend origin 목록 |
| `pulsemetry.dashboard.clickhouse-url` | 기본 `http://127.0.0.1:8123` |

Spring 설정 파일이나 `SPRING_APPLICATION_JSON`으로 전달한다. 키 본문을 저장소에 넣지 않는다.
웹 audience는 `pulsemetry-dashboard`이며 CLI와 세션 종류까지 구분한다. 웹 세션은 8시간, RT는 발급하지 않는다.
키 교체 시 기존 공개키는 최소 8시간 30초 유지한다. CLI의 기존 330초 겹침만으로는 웹 토큰을 보존할 수 없다.

## 스키마

enrollment의 기존 Flyway 이력과 dashboard 이력은 분리된다. 앱이 enrollment 적용을 완료한 뒤
`db/dashboard`를 `dashboard.flyway_schema_history`에 적용한다. ClickHouse 스키마 생성은 ingest의 소유이며 dashboard는 수행하지 않는다.

## 실제 브라우저 smoke

형제 frontend의 의존성과 Playwright Chromium, JDK 25, Docker가 준비된 환경에서 실행한다.

```sh
./gradlew :apps:dashboard-api:bootJar
node scripts/e2e/dashboard-auth-settings.mjs
```

스크립트는 격리된 PostgreSQL·ClickHouse를 생성하고 실제 frontend를 real 모드로 실행한다.
테스트 전용 계정을 사용하고 종료 시 컨테이너·임시 키를 제거한다. 포트는 API 18081, frontend 15173이다.
로그·스크린샷·판정은 `build/e2e/auth-settings`에 남는다. 현재 범위는 인증·P5 메타, 실제 frontend API 클라이언트의 53개 지표 카탈로그 및 합계 5개·활성 사용자·도입률·커버리지 총 8개 지표다. 정규화 테스트 포인트를 ClickHouse에 직접 적재하며 ingest 경로·전체 화면 및 PROJ-156 수용 E2E를 대체하지 않는다. 미구현 지표의 쿼리 단위 오류도 결과에 기록한다.
