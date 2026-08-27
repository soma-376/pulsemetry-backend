# Pulsemetry 설치 부트스트랩 (Windows / PowerShell).
#
# 사용자는 이 스크립트를 직접 열어 보지 않고 `irm ... | iex` 로 실행한다.
# 서버가 __PULSEMETRY_INVITE_CODE__ 와 __PULSEMETRY_SERVER__ 자리를 채워 내려보낸다.
# 초대 코드는 서버에서 정규식 화이트리스트를 통과한 값만 들어오므로
# PowerShell 메타문자가 섞일 수 없다 — 이스케이프가 아니라 화이트리스트가 방어선이다.

$ErrorActionPreference = 'Stop'

$env:PULSEMETRY_INVITE_CODE = '__PULSEMETRY_INVITE_CODE__'
$env:PULSEMETRY_SERVER = '__PULSEMETRY_SERVER__'

if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { $arch = 'arm64' } else { $arch = 'amd64' }

$installDir = Join-Path $env:LOCALAPPDATA 'Pulsemetry\bin'
New-Item -ItemType Directory -Force -Path $installDir | Out-Null
$exe = Join-Path $installDir 'pulsemetry.exe'

Write-Host "Pulsemetry 를 내려받는 중입니다... ($arch)"
Invoke-WebRequest -Uri "$env:PULSEMETRY_SERVER/bin/pulsemetry_windows_$arch.exe" -OutFile $exe

& $exe enroll --invite $env:PULSEMETRY_INVITE_CODE --server $env:PULSEMETRY_SERVER

Write-Host 'Pulsemetry 설치가 끝났습니다.'
Write-Host 'Windows 는 daemon 자동 실행 등록이 아직 지원되지 않습니다.'
Write-Host "필요할 때마다 `"$exe`" daemon 을 직접 실행하세요."
