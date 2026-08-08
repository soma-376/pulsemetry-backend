#!/bin/sh
# Pulsemetry 설치 부트스트랩 (macOS / Linux).
#
# 사용자는 `curl -fsSL ... | sh` 로 실행한다.
# 서버가 __PULSEMETRY_INVITE_CODE__ 와 __PULSEMETRY_SERVER__ 자리를 채워 내려보낸다.
# 초대 코드는 서버에서 정규식 화이트리스트를 통과한 값만 들어오므로
# 셸 메타문자가 섞일 수 없다 — 이스케이프가 아니라 화이트리스트가 방어선이다.

set -eu

PULSEMETRY_INVITE_CODE='__PULSEMETRY_INVITE_CODE__'
PULSEMETRY_SERVER='__PULSEMETRY_SERVER__'

case "$(uname -s)" in
	Darwin) os='darwin' ;;
	Linux) os='linux' ;;
	*) echo "지원하지 않는 운영체제입니다: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
	x86_64) arch='amd64' ;;
	arm64 | aarch64) arch='arm64' ;;
	*) echo "지원하지 않는 아키텍처입니다: $(uname -m)" >&2; exit 1 ;;
esac

install_dir="$HOME/.pulsemetry/bin"
mkdir -p "$install_dir"
exe="$install_dir/pulsemetry"

echo "Pulsemetry 를 내려받는 중입니다... (${os}_${arch})"
curl -fsSL "$PULSEMETRY_SERVER/bin/pulsemetry_${os}_${arch}" -o "$exe"
chmod +x "$exe"

"$exe" enroll --invite "$PULSEMETRY_INVITE_CODE" --server "$PULSEMETRY_SERVER"

if [ "$os" = 'darwin' ]; then
	plist="$HOME/Library/LaunchAgents/com.pulsemetry.daemon.plist"
	mkdir -p "$(dirname "$plist")"
	cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>com.pulsemetry.daemon</string>
	<key>ProgramArguments</key>
	<array>
		<string>$exe</string>
		<string>daemon</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
</dict>
</plist>
PLIST
	launchctl bootstrap "gui/$(id -u)" "$plist"
else
	unit="$HOME/.config/systemd/user/pulsemetry.service"
	mkdir -p "$(dirname "$unit")"
	cat > "$unit" <<UNIT
[Unit]
Description=Pulsemetry daemon

[Service]
ExecStart=$exe daemon
Restart=always

[Install]
WantedBy=default.target
UNIT
	systemctl --user enable --now pulsemetry
fi

echo 'Pulsemetry 설치가 끝났습니다.'
