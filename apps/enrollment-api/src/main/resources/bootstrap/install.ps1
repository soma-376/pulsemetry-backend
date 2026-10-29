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

if ($LASTEXITCODE -ne 0) { throw 'Pulsemetry 등록에 실패했습니다.' }

# GUI 실패는 이미 완료한 등록과 데몬 기동을 취소하지 않는다.
try {
    & $exe gui install
    if ($LASTEXITCODE -ne 0) { Write-Warning 'GUI 설치·실행을 완료하지 못했습니다. pulsemetry gui install로 다시 시도하세요.' }
} catch {
    Write-Warning 'GUI 설치·실행을 완료하지 못했습니다. 수집 데몬 상태는 pulsemetry status로 확인하세요.'
}
Write-Host 'Pulsemetry 설치가 끝났습니다.'
Write-Host 'daemon 자동 실행 등록은 enroll이 처리했습니다. 상태 확인: pulsemetry status'
