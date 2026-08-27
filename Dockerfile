# syntax=docker/dockerfile:1.7
#
# enrollment-api 컨테이너 이미지.
#
# 빌드 스테이지는 $BUILDPLATFORM 에 고정한다. 산출물이 JVM 바이트코드라 아키텍처를 타지 않으므로,
# arm64 이미지를 만들 때도 Gradle 빌드는 러너의 네이티브 아키텍처에서 그대로 돌면 된다.
# QEMU 에뮬레이션 위에서 Gradle 을 돌리면 빌드가 몇 배로 느려진다.
#
#   docker buildx build --platform linux/arm64 -t <repo>:<tag> --push .

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# 빌드 스크립트를 먼저 넣어 의존성 해석 결과를 캐시에 남긴다.
# 소스만 바뀌었을 때 의존성을 다시 받지 않게 하기 위해서다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY apps/enrollment-api/build.gradle.kts apps/enrollment-api/
COPY libs/enrollment-persistence/build.gradle.kts libs/enrollment-persistence/

RUN --mount=type=cache,target=/root/.gradle \
	./gradlew --no-daemon :apps:enrollment-api:dependencies --configuration runtimeClasspath > /dev/null

COPY libs libs
COPY apps apps

# 테스트는 여기서 돌리지 않는다. 통합 테스트가 Testcontainers 로 실제 PostgreSQL 을 띄우고
# 계약 테스트가 형제 저장소 telemetryctl 의 스키마 파일을 읽으므로 이미지 빌드 안에서는 성립하지 않는다.
# 테스트는 CI 의 build 잡이 이미 돌린다.
RUN --mount=type=cache,target=/root/.gradle \
	./gradlew --no-daemon :apps:enrollment-api:bootJar -x test

# 레이어별로 풀어 둔다. 의존성과 애플리케이션 클래스가 다른 레이어에 들어가야
# 코드만 고쳤을 때 57MB 짜리 fat jar 를 통째로 다시 올리지 않는다.
# plain jar 는 apps/enrollment-api/build.gradle.kts 에서 껐다. build/libs 에는 bootJar 산출물 하나뿐이다.
RUN JAR="$(find apps/enrollment-api/build/libs -name '*.jar' | head -1)" \
	&& java -Djarmode=tools -jar "$JAR" extract --layers --launcher --destination /extracted


FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

# root 로 돌리지 않는다. 홈 디렉터리를 /app 으로 두어 JVM 이 쓰는 임시 파일도 이 안에 남게 한다.
RUN groupadd --system --gid 10001 pulsemetry \
	&& useradd --system --uid 10001 --gid pulsemetry --home-dir /app --shell /usr/sbin/nologin pulsemetry

COPY --from=build --chown=pulsemetry:pulsemetry /extracted/dependencies/ ./
COPY --from=build --chown=pulsemetry:pulsemetry /extracted/spring-boot-loader/ ./
COPY --from=build --chown=pulsemetry:pulsemetry /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=pulsemetry:pulsemetry /extracted/application/ ./

# GET /bin/{filename} 이 읽는 디렉터리. 기본값 './binaries' 는 상대경로라 작업 디렉터리에 따라 흔들리므로
# 절대경로로 고정한다. 비어 있으면 /bin/* 요청이 404 가 된다 — 바이너리를 채우는 것은 배포 쪽 몫이다.
RUN mkdir -p /app/binaries && chown pulsemetry:pulsemetry /app/binaries
ENV PULSEMETRY_BINARIES_DIR=/app/binaries

USER pulsemetry
EXPOSE 8080

# 컨테이너 메모리 한도를 JVM 이 그대로 따르게 둔다(UseContainerSupport 기본값).
# MaxRAMPercentage 만 올려 Fargate 태스크 메모리를 놀리지 않는다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
