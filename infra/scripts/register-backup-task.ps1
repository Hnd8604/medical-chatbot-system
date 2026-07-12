<#
.SYNOPSIS
  Đăng ký (hoặc gỡ) Windows Scheduled Task chạy backup tự động lúc 02:00 hằng ngày.

.DESCRIPTION
  Tạo task "MedicalChatbotBackup" gọi infra\scripts\backup.ps1 -Trigger auto.
  Cần chạy PowerShell với quyền Administrator.

.PARAMETER Time
  Giờ chạy hằng ngày, mặc định "02:00".

.PARAMETER Unregister
  Gỡ bỏ task thay vì tạo mới.

.EXAMPLE
  # Chạy PowerShell (Admin):
  powershell -ExecutionPolicy Bypass -File infra\scripts\register-backup-task.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File infra\scripts\register-backup-task.ps1 -Unregister
#>
param(
    [string]$Time = "02:00",
    [switch]$Unregister
)

$ErrorActionPreference = "Stop"
$taskName = "MedicalChatbotBackup"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$backupPs1 = Join-Path $scriptDir "backup.ps1"

if ($Unregister) {
    if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Host "Đã gỡ task '$taskName'."
    } else {
        Write-Host "Không tìm thấy task '$taskName'."
    }
    exit 0
}

if (-not (Test-Path $backupPs1)) {
    Write-Error "Không tìm thấy backup.ps1 tại: $backupPs1"
    exit 1
}

$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NonInteractive -ExecutionPolicy Bypass -File `"$backupPs1`" -Trigger auto"

$trigger = New-ScheduledTaskTrigger -Daily -At $Time

# Chạy dưới tài khoản hiện tại, quyền cao nhất, kể cả khi không đăng nhập.
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" `
    -LogonType S4U -RunLevel Highest

$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
    -DontStopOnIdleEnd -ExecutionTimeLimit (New-TimeSpan -Hours 1)

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force `
    -Description "Sao lưu tự động app DB + HAPI DB lên Google Drive (Medical Chatbot)." | Out-Null

Write-Host "Đã đăng ký task '$taskName' chạy hằng ngày lúc $Time."
Write-Host "Kiểm tra: Get-ScheduledTask -TaskName $taskName"
Write-Host "Chạy thử ngay: Start-ScheduledTask -TaskName $taskName"
