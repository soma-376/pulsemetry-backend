# 로컬 E2E 의 telemetryctl 데몬 실행 환경 (docs/telemetry-kafka-e2e.md).
#
# telemetryctl 은 비밀을 OS 키링에 둔다. Linux 키링(Secret Service)을 쓰려면 D-Bus 세션과 gnome-keyring 이 필요하다 —
# 호스트(macOS)의 실제 Keychain 을 건드리지 않으려고 데몬을 컨테이너 안에서 돌린다. 바이너리는 이미지에 넣지 않고
# 실행 때 mount 한다(수정하지 않은 develop 빌드).
FROM debian:bookworm-slim

RUN apt-get update \
	&& apt-get install -y --no-install-recommends dbus dbus-x11 gnome-keyring libsecret-tools python3 ca-certificates curl procps \
	&& rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 e2e
USER e2e
WORKDIR /home/e2e

COPY --chown=e2e:e2e daemon-entrypoint.sh proxy.py /opt/e2e/
ENTRYPOINT ["/bin/bash", "/opt/e2e/daemon-entrypoint.sh"]
