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

# 양쪽 파일을 먼저 내려받아 GUI가 없는 릴리스에서는 설치 상태를 바꾸지 않는다.
work_dir=$(mktemp -d)
mount_dir=""
cleanup() {
    if [ -n "$mount_dir" ]; then hdiutil detach "$mount_dir" >/dev/null 2>&1 || true; fi
    rm -rf "$work_dir"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
case "$os" in darwin) gui_ext=dmg ;; linux) gui_ext=AppImage ;; esac
curl -fsSL "$PULSEMETRY_SERVER/bin/pulsemetry_${os}_${arch}" -o "$work_dir/pulsemetry"
curl -fsSL "$PULSEMETRY_SERVER/bin/pulsemetry_gui_${os}_${arch}.$gui_ext" -o "$work_dir/gui.$gui_ext"

install_dir="$HOME/.pulsemetry/bin"
mkdir -p "$install_dir"
exe="$install_dir/pulsemetry"
chmod +x "$work_dir/pulsemetry"
# 같은 파일 시스템 안에서 rename해 실행 중인 바이너리를 덮어쓰지 않는다.
cp "$work_dir/pulsemetry" "$install_dir/.pulsemetry-install"
chmod 700 "$install_dir/.pulsemetry-install"
mv -f "$install_dir/.pulsemetry-install" "$exe"

if [ "$os" = darwin ]; then
    mount_dir="$work_dir/mount"
    mkdir -p "$mount_dir" "$HOME/Applications"
    hdiutil attach -readonly -nobrowse -mountpoint "$mount_dir" "$work_dir/gui.dmg" >/dev/null
    test ! -L "$HOME/Applications/Pulsemetry.app"
    ditto "$mount_dir/Pulsemetry.app" "$HOME/Applications/Pulsemetry.app"
    gui="$HOME/Applications/Pulsemetry.app/Contents/MacOS/Pulsemetry"
else
    gui_dir="$HOME/.local/share/pulsemetry"
    mkdir -p "$gui_dir"
    gui="$gui_dir/Pulsemetry.AppImage"
    cp "$work_dir/gui.AppImage" "$gui_dir/.Pulsemetry-install.AppImage"
    chmod 700 "$gui_dir/.Pulsemetry-install.AppImage"
    mv -f "$gui_dir/.Pulsemetry-install.AppImage" "$gui"
fi

# 등록에 실패하면 연결 설정을 만들기 전에 중단한다.
"$exe" register-product
"$gui" --register-product
"$exe" enroll --invite "$PULSEMETRY_INVITE_CODE" --server "$PULSEMETRY_SERVER"
nohup "$gui" >/dev/null 2>&1 </dev/null &
echo 'Pulsemetry 설치가 끝났습니다. 앱 설정 또는 Uninstall Pulsemetry에서 제거할 수 있습니다.'
