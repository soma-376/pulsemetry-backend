# syntax=docker/dockerfile:1.7
#
# enrollment-api · telemetry-ingest · telemetry-gateway · dashboard-api · retention-worker 컨테이너 이미지. target을 생략하면 enrollment-api를 만든다.
#
# 빌드 스테이지는 $BUILDPLATFORM 에 고정한다. 산출물이 JVM 바이트코드라 아키텍처를 타지 않으므로,
# arm64 이미지를 만들 때도 Gradle 빌드는 러너의 네이티브 아키텍처에서 그대로 돌면 된다.
# QEMU 에뮬레이션 위에서 Gradle 을 돌리면 빌드가 몇 배로 느려진다.
#
#   python3 scripts/releases/telemetryctl_release.py download /tmp/telemetryctl-binaries
#   docker buildx build --platform linux/arm64 --target enrollment-api --build-context telemetry-contracts=../telemetryctl/contracts --build-context telemetry-binaries=/tmp/telemetryctl-binaries -t <repo>:<tag> --load .
#   docker buildx build --platform linux/arm64 --target telemetry-ingest -t <repo>:<tag> --load .
#   docker buildx build --platform linux/arm64 --target telemetry-gateway -t <repo>:<tag> --load .
#   docker buildx build --platform linux/arm64 --target dashboard-api -t <repo>:<tag> --load .
#   docker buildx build --platform linux/arm64 --target retention-worker -t <repo>:<tag> --load .
#
# enrollment-api 만 named context `telemetry-contracts`(계약 원본)와 `telemetry-binaries`(검증한 게시 릴리스)를 요구한다.
# 다른 target 은 두 context를 사용하지 않는다. CLI는 재빌드하지 않는다(ADR 0053, PROJ-146).

FROM --platform=$BUILDPLATFORM python:3.13-slim AS telemetry-binaries-verified

COPY config/telemetryctl-release.json /supply/config/telemetryctl-release.json
COPY scripts/releases/telemetryctl_release.py /supply/scripts/releases/telemetryctl_release.py
COPY --from=telemetry-binaries / /binaries/
# CI 다운로드 검증에 더해 실제 이미지 입력도 검증한다. 누락·변조·다른 릴리스는 빌드를 실패시킨다.
RUN python /supply/scripts/releases/telemetryctl_release.py verify /binaries \
    && chmod 755 /binaries /binaries/* \
    && chmod 644 /binaries/*/*

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build-base

WORKDIR /workspace

# 빌드 스크립트를 먼저 넣어 의존성 해석 결과를 캐시에 남긴다.
# Gradle이 구성하는 모든 모듈을 포함한다. 하나라도 빠지면 의존성 변경이 캐시에 반영되지 않는다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY apps/enrollment-api/build.gradle.kts apps/enrollment-api/
COPY apps/telemetry-ingest/build.gradle.kts apps/telemetry-ingest/
COPY apps/telemetry-gateway/build.gradle.kts apps/telemetry-gateway/
COPY apps/dashboard-api/build.gradle.kts apps/dashboard-api/
COPY apps/retention-worker/build.gradle.kts apps/retention-worker/
COPY libs/enrollment-persistence/build.gradle.kts libs/enrollment-persistence/
COPY libs/security/build.gradle.kts libs/security/
COPY libs/telemetry-collector/build.gradle.kts libs/telemetry-collector/
COPY libs/telemetry-auth/build.gradle.kts libs/telemetry-auth/
COPY libs/telemetry-observability/build.gradle.kts libs/telemetry-observability/
COPY libs/telemetry-otlp-http/build.gradle.kts libs/telemetry-otlp-http/
COPY libs/telemetry-ingress-contract/build.gradle.kts libs/telemetry-ingress-contract/
COPY libs/telemetry-adapter/build.gradle.kts libs/telemetry-adapter/
COPY libs/telemetry-enricher/build.gradle.kts libs/telemetry-enricher/
COPY libs/telemetry-persistence/build.gradle.kts libs/telemetry-persistence/
COPY libs/telemetry-ops-persistence/build.gradle.kts libs/telemetry-ops-persistence/
COPY libs/vendor-connector/build.gradle.kts libs/vendor-connector/
COPY tools/dev-seed/build.gradle.kts tools/dev-seed/


FROM build-base AS dev-seed-build

COPY libs libs
COPY apps apps
COPY tools tools

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon :tools:dev-seed:installDist -x test


# 서버가 아닌 개발 DB 초기화 프로그램이다. 운영 이미지와 별도 target으로만 빌드한다.
FROM eclipse-temurin:25-jre AS dev-seed

WORKDIR /app
RUN groupadd --system --gid 10001 pulsemetry \
    && useradd --system --uid 10001 --gid pulsemetry --home-dir /app --shell /usr/sbin/nologin pulsemetry
COPY --from=dev-seed-build --chown=pulsemetry:pulsemetry /workspace/tools/dev-seed/build/install/dev-seed/lib/ ./lib/
USER pulsemetry
ENTRYPOINT ["java", "-cp", "/app/lib/*", "com.team376.pulsemetry.devseed.DevSeedKt"]


FROM build-base AS enrollment-api-build

# 원본 계약을 별도 named context로 받는다. 인증 정책 검증에 필요한 스키마만 jar에 들어간다.
COPY --from=telemetry-contracts /enrollment-manifest.schema.json /workspace/contracts/enrollment-manifest.schema.json
ENV PULSEMETRY_CONTRACTS_DIR=/workspace/contracts

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:enrollment-api:dependencies --configuration runtimeClasspath > /dev/null

COPY libs libs
COPY apps apps

# 테스트는 여기서 돌리지 않는다. 통합 테스트가 Testcontainers 로 실제 PostgreSQL 을 띄우고
# 계약 테스트가 형제 저장소 telemetryctl 의 스키마 파일을 읽으므로 이미지 빌드 안에서는 성립하지 않는다.
# 테스트는 CI 의 build 잡이 이미 돌린다.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:enrollment-api:bootJar -x test

# 레이어별로 풀어 둔다. 의존성과 애플리케이션 클래스가 다른 레이어에 들어가야
# 코드만 고쳤을 때 fat jar를 통째로 다시 올리지 않는다.
# 두 앱 모두 plain jar를 껐으므로 각 build/libs에는 bootJar 산출물 하나뿐이다.
RUN java -Djarmode=tools -jar apps/enrollment-api/build/libs/*.jar \
	extract --layers --launcher --destination /extracted


FROM build-base AS telemetry-ingest-build

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:telemetry-ingest:dependencies --configuration runtimeClasspath > /dev/null

COPY libs libs
COPY apps apps

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:telemetry-ingest:bootJar -x test

RUN java -Djarmode=tools -jar apps/telemetry-ingest/build/libs/*.jar \
	extract --layers --launcher --destination /extracted


# OTLP 접수 Gateway (ADR 0066). DB·S3 의존이 없다 — runtimeClasspath 에 JPA·JDBC 드라이버가 오르지 않는다.
FROM build-base AS telemetry-gateway-build

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:telemetry-gateway:dependencies --configuration runtimeClasspath > /dev/null

COPY libs libs
COPY apps apps

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:telemetry-gateway:bootJar -x test

RUN java -Djarmode=tools -jar apps/telemetry-gateway/build/libs/*.jar \
	extract --layers --launcher --destination /extracted


FROM build-base AS dashboard-api-build

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:dashboard-api:dependencies --configuration runtimeClasspath > /dev/null

COPY libs libs
COPY apps apps

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:dashboard-api:bootJar -x test

RUN java -Djarmode=tools -jar apps/dashboard-api/build/libs/*.jar \
	extract --layers --launcher --destination /extracted


FROM build-base AS retention-worker-build

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:retention-worker:dependencies --configuration runtimeClasspath > /dev/null

COPY libs libs
COPY apps apps

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon :apps:retention-worker:bootJar -x test

RUN java -Djarmode=tools -jar apps/retention-worker/build/libs/*.jar \
	extract --layers --launcher --destination /extracted


FROM eclipse-temurin:25-jre AS runtime-base

WORKDIR /app

# root 로 돌리지 않는다. 홈 디렉터리를 /app 으로 두어 JVM 이 쓰는 임시 파일도 이 안에 남게 한다.
RUN groupadd --system --gid 10001 pulsemetry \
	&& useradd --system --uid 10001 --gid pulsemetry --home-dir /app --shell /usr/sbin/nologin pulsemetry

# 컨테이너 메모리 한도를 JVM이 따르게 하고 heap에 한도의 75%를 배정한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]


FROM runtime-base AS telemetry-ingest

COPY --from=telemetry-ingest-build --chown=pulsemetry:pulsemetry /extracted/dependencies/ ./
COPY --from=telemetry-ingest-build --chown=pulsemetry:pulsemetry /extracted/spring-boot-loader/ ./
COPY --from=telemetry-ingest-build --chown=pulsemetry:pulsemetry /extracted/snapshot-dependencies/ ./
COPY --from=telemetry-ingest-build --chown=pulsemetry:pulsemetry /extracted/application/ ./

USER pulsemetry
EXPOSE 4316


# OTLP 접수 Gateway (ADR 0066). telemetry JWT 공개키(mount)와 Kafka 접속 환경변수만 받는다. EKS workload 다 — ECS 배포 목록에는 없다.
FROM runtime-base AS telemetry-gateway

COPY --from=telemetry-gateway-build --chown=pulsemetry:pulsemetry /extracted/dependencies/ ./
COPY --from=telemetry-gateway-build --chown=pulsemetry:pulsemetry /extracted/spring-boot-loader/ ./
COPY --from=telemetry-gateway-build --chown=pulsemetry:pulsemetry /extracted/snapshot-dependencies/ ./
COPY --from=telemetry-gateway-build --chown=pulsemetry:pulsemetry /extracted/application/ ./

USER pulsemetry
EXPOSE 8082


# 분석 조회 API (ADR 0022). 배포는 허브의 소재 결정과 사용자 인증 연결 뒤다 — 그 전에는 조직 경로가 전부 401 이다.
FROM runtime-base AS dashboard-api

COPY --from=dashboard-api-build --chown=pulsemetry:pulsemetry /extracted/dependencies/ ./
COPY --from=dashboard-api-build --chown=pulsemetry:pulsemetry /extracted/spring-boot-loader/ ./
COPY --from=dashboard-api-build --chown=pulsemetry:pulsemetry /extracted/snapshot-dependencies/ ./
COPY --from=dashboard-api-build --chown=pulsemetry:pulsemetry /extracted/application/ ./

USER pulsemetry
EXPOSE 8081


# 조직별 보존 삭제 (ADR 0024). 서버가 아니다 — 컨테이너 인자로 명령 하나를 받고 종료 코드로 끝난다:
#   docker run <image> --tenant=<uuid> --retention-months=<N> --as-of=<ISO-8601>
FROM runtime-base AS retention-worker

COPY --from=retention-worker-build --chown=pulsemetry:pulsemetry /extracted/dependencies/ ./
COPY --from=retention-worker-build --chown=pulsemetry:pulsemetry /extracted/spring-boot-loader/ ./
COPY --from=retention-worker-build --chown=pulsemetry:pulsemetry /extracted/snapshot-dependencies/ ./
COPY --from=retention-worker-build --chown=pulsemetry:pulsemetry /extracted/application/ ./

USER pulsemetry


# 기존 target 없는 빌드도 enrollment-api를 만들도록 마지막에 둔다.
FROM runtime-base AS enrollment-api

COPY --from=enrollment-api-build --chown=pulsemetry:pulsemetry /extracted/dependencies/ ./
COPY --from=enrollment-api-build --chown=pulsemetry:pulsemetry /extracted/spring-boot-loader/ ./
COPY --from=enrollment-api-build --chown=pulsemetry:pulsemetry /extracted/snapshot-dependencies/ ./
COPY --from=enrollment-api-build --chown=pulsemetry:pulsemetry /extracted/application/ ./

# 원격 릴리스의 원래 파일명·SHA256SUMS를 유지한다. 기존 versioned 매핑이 공개 /bin 경로를 제공한다.
COPY --from=telemetry-binaries-verified --chown=pulsemetry:pulsemetry /binaries/ /app/binaries/
ENV PULSEMETRY_BINARIES_DIR=/app/binaries

USER pulsemetry
EXPOSE 8080
