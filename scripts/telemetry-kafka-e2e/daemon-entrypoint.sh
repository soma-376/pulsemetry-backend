#!/usr/bin/env bash
# D-Bus 세션 버스와 gnome-keyring(Secret Service)을 띄우고, 데몬이 localhost 로 백엔드에 닿게 하는 프록시를 둔다.
# telemetryctl 은 OTLP endpoint 로 https 또는 http://localhost 만 받으므로 호스트의 앱을 localhost 로 비춘다.
set -euo pipefail

export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/e2e-dbus.sock
dbus-daemon --session --fork --address="$DBUS_SESSION_BUS_ADDRESS" --nopidfile
# 빈 암호로 login 키링을 만들고 연다. 이 컨테이너 안에서만 산다.
printf 'e2e-keyring' | gnome-keyring-daemon --unlock --components=secrets --daemonize > /tmp/e2e-keyring.env
echo "export DBUS_SESSION_BUS_ADDRESS=$DBUS_SESSION_BUS_ADDRESS" > /tmp/e2e-env.sh

# localhost:38080 → enrollment, localhost:38090 → 교체 가능한 OTLP 대상(ALB 의 대상 그룹 역할).
exec python3 /opt/e2e/proxy.py \
	"38080=${E2E_ENROLLMENT_TARGET:-host.docker.internal:38080}" \
	"38090=@/tmp/e2e-otlp-target"
