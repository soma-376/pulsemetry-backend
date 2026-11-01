# PROJ-306 구현 검증

## 기준과 변경 관리

- Backend: 원격 fetch로 확인한 `origin/develop 52689f9`에서 `refactor/PROJ-306-feature-packages`를 생성했다. 작업 체크아웃은 `pulsemetry-backend-soma-dev`다.
- 기본 `pulsemetry-backend`의 오래된 브랜치·미커밋 변경을 보존했다. 참고 프로젝트 `gdsc-server df95475`와 미추적 PLAN.md를 수정하지 않았다.
- 티켓의 설명·완료 기준을 직접 조회했다. 연결 이슈·원격 링크·댓글·하위 티켓은 없었다. 새 하위 티켓을 만들지 않았다.
- 코드보다 먼저 ADR 0075·부분 대체 상태·인덱스·모듈 지도를 커밋했다.
- 문서 허브의 형제 체크아웃과 origin/main에도 오래된 Dashboard 서술이 남아 있다. 외부 계약을 변경하지 않으며 Backend의 현재 API 명세·ADR을 회귀 기준으로 사용한다.
- 계약 테스트 원본은 원격 telemetryctl 기본 브랜치 `develop a0efa33`의 contracts를 `/tmp/proj306-contracts/contracts`에 git archive로 읽었다. 로컬 수정본을 오라클로 사용하지 않는다.

## 1. Dashboard

기능별 API·서비스·응답을 overview·team·member·settings·alert에 배치했다. 카탈로그·작업 상태·수집 상태도 해당 기능에서 찾는다. 여러 기능의 값·비교·팀 참조·계약 값·정렬 규칙은 analytics에 두고 기능 내부 참조를 없앴다. 공통 예외 매핑은 api, 오류 본문은 error, 설정 주입은 config가 담당한다.

검증: JDK 25, 실제 PostgreSQL·ClickHouse Testcontainers로 `:apps:dashboard-api:test` 성공. 총 409개, 실패·오류·skip 0. 기존 HTTP·snapshot·ETag·권한·집계 회귀와 신규 JSON 공유 값 2개·ArchUnit 4개가 포함된다. ArchUnit은 기능 간 참조·공유 집계 역참조·전체 Dashboard 패키지 순환·기능 진입점 소재를 검사한다. 첫 검사에서 발견한 오류 처리/설정 역참조는 무시하지 않고 제거했다.

## 2. Enrollment

`ManagementController`의 다중 경로 분기와 `ManagementStore.command`를 제거했다. 기능별 API가 명시적 application 명령을 호출하고, `ManagementHttp`는 HTTP 상태·헤더·JSON과 기존 재시도 식별자, `ManagementExecutor`는 조직/역할 잠금·트랜잭션·암호화 재응답을 맡는다. Directory·Invitation·Installation·Vendor 저장 연산과 CommandJournal은 SQL만 수행한다. 정책 판 생성·설정 변경·보존 정리 요청 조정은 `PolicyCommands`로 옮겼다. 팀 이름·역할 규칙은 순수 domain에서 검사하며 기존 좌석 원장·상태 전이는 재사용한다.

검증: `:apps:enrollment-api:test :libs:enrollment-persistence:test` 성공, 각각 512개·133개, 실패·오류·skip 0. PostgreSQL·실제 HTTP·메일 outbox·낙관 버전·동시성·정책 정리·좌석 회귀를 포함한다. 신규 ArchUnit 4개, 도메인 규칙 2개, 이전 AES-GCM/AAD 저장 형식 재생·동시 멱등 요청·응답 생성 실패 롤백 3개를 추가했다. 초기 전체 실행은 기존 부트스트랩 테스트의 `pwsh` PATH 누락 한 건만 실패했으며, `/tmp/proj268-powershell/runtime`의 PowerShell 7.4.6을 PATH에 포함해 전체 재실행했다.

## 3. 공유 의존성과 앱 조립

`telemetry-model`은 관측·어휘·집계·보강 결과를 단독으로 소유한다. adapter·enricher·persistence는 공개 시그니처의 값만 이 모듈에서 받으며 서로의 실행 구현을 가져오지 않는다. 정규화 ID·해시·토큰 의미·가격 계산과 보강 실행은 원래 단계에 남겼다. 사용량 계약 판정도 adapter에서 실행하고 결과 값만 집계에 전달한다.

`enrollment-domain`은 오류·좌석 상태/전이·계약 기간·좌석 제어 판정을 소유한다. Dashboard는 좌석 명령 실행기 `SeatControl`에 의존하지 않고, 연결 조회 사실을 순수 규칙으로 넘기는 `SeatControlChoice`를 사용한다. 설치 안내의 활성 상태·적용 판 판정도 기능 domain으로 옮겼다. enrollment-persistence의 main starter·드라이버를 제거하고 앱의 JPA/JDBC/Flyway 선택을 명시했다. 테스트에만 필요한 정규화 구현·DB fixture 자동설정은 test 의존으로 한정한다.

구조 검사:

- Dashboard·Enrollment의 기능 내부 참조·순환, application의 HTTP/SQL 참조, domain의 프레임워크 참조를 ArchUnit으로 검사한다.
- 모든 라이브러리는 공유 검사 소스로 자신의 Kotlin·Java main 바이트코드만 읽어 앱 참조·Spring 스테레오타입·Bean 조립을 금지한다. 두 순수 모듈은 프레임워크 의존도 금지한다.
- `verifyModuleBoundaries`가 20개 모듈의 main 방향·순환·단계 간선, compile/runtimeClasspath의 starter 및 적재의 JPA/Flyway 유입을 검사한다. 각 `check`에 연결되어 기존 CI의 `build`로 실행된다. 최종 정상 그래프의 독립 실행도 통과했다.
- 임시 Gradle init script로 `compileOnlyApi` 앱 참조, runtime Boot starter, compileOnly Boot starter, 모듈 순환을 각각 주입했다. 네 번 모두 해당 규칙의 오류로 실패했다. 검사 후 저장소의 의존 선언은 변경되지 않았다.

실행 jar의 Main-Class/Start-Class·시작 클래스와 테스트 fixture 미포함을 검사했다. Enrollment jar의 manifest 스키마는 원격 telemetryctl에서 읽은 원본과 바이트 단위로 동일하다. Docker build-base의 COPY 목록이 settings.gradle.kts의 20개 모듈을 빠짐없이 포함하는 것도 검사했다.

| 앱 | 배포 jar의 관련 구성 |
|---|---|
| enrollment-api | JPA/Hibernate·Flyway 자동설정, PostgreSQL, enrollment-domain |
| dashboard-api | JPA/Hibernate, PostgreSQL, 수동 cache Flyway, enrollment-domain |
| telemetry-ingest | 각각 선택한 adapter·enricher·persistence·model, JPA/Hibernate, PostgreSQL. Flyway 자동설정 없음 |
| telemetry-gateway | 기존 JWT·Kafka·OTLP 구성. DB·Hibernate·공유 업무 모듈 없음 |
| retention-worker | JDBC·PostgreSQL·telemetry-model·enrollment-domain. adapter·enricher·Hibernate·JPA/Flyway 자동설정 없음 |

최종 `./gradlew build` 성공. 20개 모듈에서 **2,754개 테스트, 실패·오류·skip 0**이다. PostgreSQL·ClickHouse·Kafka·메일 컨테이너를 사용하는 회귀와 다섯 실행 앱의 컨텍스트 기동·bootJar 조립을 포함한다. 아래 수치는 최종 실행 결과이며 앞 단계의 수치를 더한 값이 아니다.

| 모듈 | 테스트 |
|---|---:|
| `apps/dashboard-api` | 409 |
| `apps/enrollment-api` | 514 |
| `apps/retention-worker` | 28 |
| `apps/telemetry-gateway` | 41 |
| `apps/telemetry-ingest` | 145 |
| `libs/enrollment-domain` | 10 |
| `libs/enrollment-persistence` | 134 |
| `libs/security` | 84 |
| `libs/telemetry-adapter` | 777 |
| `libs/telemetry-auth` | 37 |
| `libs/telemetry-collector` | 171 |
| `libs/telemetry-enricher` | 27 |
| `libs/telemetry-ingress-contract` | 30 |
| `libs/telemetry-model` | 17 |
| `libs/telemetry-observability` | 15 |
| `libs/telemetry-ops-persistence` | 47 |
| `libs/telemetry-otlp-http` | 49 |
| `libs/telemetry-persistence` | 102 |
| `libs/vendor-connector` | 24 |
| `tools/dev-seed` | 93 |

재현 명령(로컬 JDK 25·Docker·PowerShell 필요):

```sh
PULSEMETRY_CONTRACTS_DIR=/tmp/proj306-contracts/contracts ./gradlew build --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m'
python3 .github/tests/test_deploy_dev.py
python3 -m unittest discover -s tools/dev-seed -p 'test_*.py'
```

Python 검사는 배포 이미지 발행 19개·시드/과거 데이터 도구 76개가 통과했다. 로컬 전체 컴파일 도중 장시간 재사용된 Kotlin 데몬의 512MB 힙이 소진되어, 위 명령처럼 독립 빌드 JVM의 힙 2GB·worker 2개로 실행했다. 저장소/CI의 메모리 설정은 바꾸지 않았다.

## 4. 전체 컨테이너 이미지

2026-11-01, 코드 커밋 `403f245`의 Dockerfile로 다섯 최종 target을 모두 빌드하고 로컬 Docker에 적재했다. CI와 같은 `linux/arm64`이며 OrbStack의 Docker 29.8.2·BuildKit 0.33.1을 사용했다. 각 target의 컨테이너 내부 `bootJar` 작업도 실제 실행되어 성공했다. 의존성 다운로드 캐시는 재사용했으며 호스트의 `build` 산출물은 `.dockerignore`로 제외된다. 추가 Dockerfile·소스 수정은 필요하지 않았다.

로컬 태그는 `pulsemetry-proj306/<target>:403f245`다. 아래 값은 `docker image inspect`의 로컬 이미지 ID이며 원격 저장소에 게시하지 않았다.

| target | 전체 빌드 | 포함된 새 모듈 | 로컬 이미지 ID (`sha256:`) |
|---|---|---|---|
| enrollment-api | 성공 | enrollment-domain | `01e7654333c13d3c050fb5443676f2d20ac58d0d98a5cd70cf020affabe476d2` |
| telemetry-ingest | 성공 | enrollment-domain·telemetry-model | `6367fc258634495c1cae976ce51c80f242fb27fc242f35eb0ffea68d6a351fe8` |
| telemetry-gateway | 성공 | 없음 — 기존 JWT·Kafka·OTLP 경계 유지 | `4683cf2f77bcbdd28b12812c3a6cc7917c42398d45f3db02e157779390978cc8` |
| dashboard-api | 성공 | enrollment-domain | `5da33834238fea52b111319e1dbf2254c06a1e95d26dc47a5b0d549b8390becf` |
| retention-worker | 성공 | enrollment-domain·telemetry-model | `37607d26b651c67af09d286f75869837d43c4be3eadef8c7ccc258f0c96a9903` |

Enrollment의 named context는 원격 telemetryctl `develop a0efa33`의 계약 원본과 `config/telemetryctl-release.json`에 고정된 게시 릴리스 `v0.1.0-rc.3`을 사용했다. 공급 스크립트가 원격 게시 메타데이터·CLI 6종·GUI 6종·SHA256SUMS를 확인했고, Docker 빌드의 `telemetry-binaries-verified` 단계도 통과했다. 최종 이미지에서 UID 10001로 읽은 13개 파일의 SHA-256이 lock과 모두 일치했다. 이미지에 내장된 manifest 스키마도 계약 원본과 바이트 단위로 같았다(SHA-256 `b0a150a4bbee7329662d32e25644bf94b56685503f2369e4878ae8a5eca895d1`).

다섯 최종 이미지에서 네트워크를 차단한 임시 컨테이너로 다음을 검사했다.

- `linux/arm64`, 작업 경로 `/app`, 사용자 `pulsemetry`·실제 UID 10001, 기존 entrypoint·노출 포트 유지. Java 25.0.5 실행 성공.
- Spring Boot 런처와 Start-Class 파일 존재, manifest와 내부 프로젝트 jar 목록이 전체 회귀 테스트를 통과한 호스트 bootJar와 일치. 테스트 fixture jar 없음.
- 포함된 두 새 모듈의 jar 무결성과 클래스 목록 일치. Gateway에는 두 모듈·JDBC·PostgreSQL·JPA·Hibernate·Flyway가 유입되지 않음.
- Retention에는 adapter·enricher·Hibernate 실행체·JPA/Flyway 자동설정이 없음. `enrollment-persistence`가 공개하는 Spring Data JPA·Jakarta Persistence API 타입은 포함되며, ORM 자동설정을 활성화하는 의존성과 구분한다.

재현 명령(계약 원본과 비어 있는 새 릴리스 공급 경로를 준비한다):

```sh
python3 scripts/releases/telemetryctl_release.py download /tmp/proj306-image-binaries
docker buildx build --platform linux/arm64 --target enrollment-api \
  --build-context telemetry-contracts=/tmp/proj306-contracts/contracts \
  --build-context telemetry-binaries=/tmp/proj306-image-binaries \
  --progress plain -t pulsemetry-proj306/enrollment-api:403f245 --load .
for app in telemetry-ingest telemetry-gateway dashboard-api retention-worker; do
  docker buildx build --platform linux/arm64 --target "$app" \
    --progress plain -t "pulsemetry-proj306/$app:403f245" --load . || exit "$?"
done
```

로컬 상세 증거는 `/tmp/proj306-image-<target>.log`, `/tmp/proj306-image-release.log`, `/tmp/proj306-image-inspection.json`에 남겼다. 이미지 빌드는 기존 Dockerfile대로 `-x test`를 사용하며, 앞 절의 전체 회귀 테스트 결과와 별도로 확인했다. 이 후속 작업에서는 검증 문서만 변경했다.

## Infra·배포 영향

**Infra 저장소와 배포 워크플로우는 수정하지 않았다.** Backend Dockerfile의 build-base에 `enrollment-domain`·`telemetry-model` 빌드 파일 COPY 두 줄을 추가했다. 기존 Docker 빌드가 새 Gradle 모듈을 구성하기 위해 필요한 변경이다. 이미지 target·포트·설정 키/값·스키마·배포 순서·HTTP/OTLP/Kafka 계약은 유지한다. retention-worker의 application.yaml 변경은 설명 주석뿐이다.

검증 범위는 로컬 Testcontainers·실행 jar 조립과 다섯 `linux/arm64` 전체 이미지 빌드·이미지 내부 산출물/JVM 검사다. 전체 서비스를 의존 인프라와 함께 띄우는 컨테이너 smoke·원격 CI 실행·실제 벤더/메일 공급자·운영 환경 배포 smoke는 실행하지 않았다. 문서 허브의 오래된 서술은 이 Backend 범위에서 수정하지 않았다. push·PR·Jira 하위 티켓 생성·운영 배포는 수행하지 않았다.
