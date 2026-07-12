<#
.SYNOPSIS
  Wrapper Windows cho infra/scripts/backup.sh — dùng cho Windows Task Scheduler.

.DESCRIPTION
  Định vị Git Bash rồi chạy backup.sh. Backup cả app DB + HAPI DB, nén gzip,
  upload lên Google Drive (rclone) và dọn dẹp bản cũ. Dùng cho lịch 2h sáng.

.PARAMETER Trigger
  "auto" (mặc định, chạy theo lịch) hoặc "manual".

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File infra\scripts\backup.ps1 -Trigger manual
#>
param(
    [ValidateSet("auto", "manual")]
    [string]$Trigger = "auto"
)

$ErrorActionPreference = "Stop"

# backup.sh nằm cùng thư mục với file này.
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$bashScript = Join-Path $scriptDir "backup.sh"

if (-not (Test-Path $bashScript)) {
    Write-Error "Không tìm thấy backup.sh tại: $bashScript"
    exit 1
}

# Tìm Git Bash: ưu tiên bash trong PATH, sau đó các vị trí cài đặt phổ biến.
$bashExe = $null
$cmd = Get-Command bash -ErrorAction SilentlyContinue
if ($cmd) {
    $bashExe = $cmd.Source
} else {
    $candidates = @(
        "C:\Program Files\Git\bin\bash.exe",
        "C:\Program Files (x86)\Git\bin\bash.exe",
        "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $bashExe = $c; break }
    }
}

if (-not $bashExe) {
    Write-Error "Không tìm thấy Git Bash (bash.exe). Cài Git for Windows hoặc thêm bash vào PATH."
    exit 1
}

# Chuyển đường dẫn Windows sang dạng bash chấp nhận được (bash tự hiểu 'C:\...').
& $bashExe $bashScript $Trigger
exit $LASTEXITCODE
