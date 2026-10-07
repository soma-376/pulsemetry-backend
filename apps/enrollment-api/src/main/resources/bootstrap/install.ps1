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

$workDir = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $workDir | Out-Null
try {
    $cliDownload = Join-Path $workDir 'pulsemetry.exe'
    $installer = Join-Path $workDir 'Pulsemetry-setup.exe'
    Invoke-WebRequest -Uri "$env:PULSEMETRY_SERVER/bin/pulsemetry_windows_$arch.exe" -OutFile $cliDownload
    Invoke-WebRequest -Uri "$env:PULSEMETRY_SERVER/bin/pulsemetry_gui_windows_$arch.exe" -OutFile $installer
    $setup = Start-Process -FilePath $installer -ArgumentList '/S' -Wait -PassThru
    if ($setup.ExitCode -ne 0) { throw "Pulsemetry 설치 실패: $($setup.ExitCode)" }
    $installDir = Join-Path $env:LOCALAPPDATA 'Pulsemetry\bin'
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
    $exe = Join-Path $installDir 'pulsemetry.exe'
    Copy-Item -LiteralPath $cliDownload -Destination $exe -Force
    & $exe register-product
    if ($LASTEXITCODE -ne 0) { throw '제거용 설치 기록 저장에 실패했습니다.' }
    & $exe enroll --invite $env:PULSEMETRY_INVITE_CODE --server $env:PULSEMETRY_SERVER
    if ($LASTEXITCODE -ne 0) { throw 'Pulsemetry 연결에 실패했습니다. 앱 제거 메뉴에서 설치를 해제할 수 있습니다.' }
    $gui = Join-Path $env:LOCALAPPDATA 'Programs\Pulsemetry\Pulsemetry.exe'
    Start-Process -FilePath $gui
    Write-Host 'Pulsemetry 설치가 끝났습니다. 설정 > 앱 > Pulsemetry > 제거에서 제거할 수 있습니다.'
} finally {
    Remove-Item -LiteralPath $workDir -Recurse -Force
}
