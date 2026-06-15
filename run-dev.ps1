param(
    [switch]$Stop,
    [switch]$SkipInstall,
    [switch]$SkipSeed,
    [switch]$SkipFlowCheck,
    [string]$JavaHome = "C:\Program Files\Java\jdk-21.0.11"
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Fix for double-quoted JAVA_HOME which breaks mvnw.cmd on Windows
if ($env:JAVA_HOME -match '^"(.*)"$') {
    $env:JAVA_HOME = $matches[1]
}

$RunDir = Join-Path $RootDir ".run"
$LogDir = Join-Path $RootDir "logs"
$HapiCompose = Join-Path $RootDir "infra\hapi-fhir\docker-compose.yml"
$AppPostgresCompose = Join-Path $RootDir "infra\app-postgres\docker-compose.yml"
$RedisCompose = Join-Path $RootDir "infra\redis\docker-compose.yml"
$QdrantCompose = Join-Path $RootDir "infra\qdrant\docker-compose.yml"
$FrontendDir = Join-Path $RootDir "frontend"
$ChatbotDir = Join-Path $RootDir "chatbot-service"
$SpringDir = Join-Path $RootDir "spring-backend"

New-Item -ItemType Directory -Force -Path $RunDir, $LogDir | Out-Null

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing required command: $Name"
    }
}

function Test-ListeningPort {
    param([int]$Port)
    $connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    return $connections.Count -gt 0
}

function Wait-Http {
    param(
        [string]$Url,
        [int]$Retries = 60,
        [int]$DelaySeconds = 2
    )

    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        } catch {
            if ($attempt -eq $Retries) {
                throw "Timed out waiting for $Url. Last error: $($_.Exception.Message)"
            }
        }
        Start-Sleep -Seconds $DelaySeconds
    }
}

function Start-ManagedProcess {
    param(
        [string]$Name,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory,
        [int]$Port
    )

    if (Test-ListeningPort -Port $Port) {
        Write-Host "$Name already appears to be running on port $Port. Keeping existing process."
        return
    }

    $stdout = Join-Path $LogDir "$Name.out.log"
    $stderr = Join-Path $LogDir "$Name.err.log"
    $pidFile = Join-Path $RunDir "$Name.pid"

    if (Test-Path $stdout) { Remove-Item -LiteralPath $stdout -Force }
    if (Test-Path $stderr) { Remove-Item -LiteralPath $stderr -Force }

    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    Set-Content -Path $pidFile -Value $process.Id
    Write-Host "Started $Name with PID $($process.Id). Logs: $stdout, $stderr"
}

function Stop-ManagedProcess {
    param(
        [string]$Name,
        [int]$Port = 0
    )

    $pidFile = Join-Path $RunDir "$Name.pid"

    # Try stopping by PID first (using tree kill)
    if (Test-Path $pidFile) {
        $processId = Get-Content -Path $pidFile | Select-Object -First 1
        if ($processId) {
            $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($process) {
                # Use taskkill /T /F to kill process trees (essential for batch/spawned JVMs)
                taskkill /F /T /PID $processId 2>&1 | Out-Null
                Write-Host "Stopped $Name process tree (PID $processId)."
            }
        }
        Remove-Item -LiteralPath $pidFile -Force
    }

    # Also clean up by port if specified, to catch orphaned JVMs or other processes
    if ($Port -gt 0) {
        $connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        foreach ($conn in $connections) {
            if ($conn.OwningProcess) {
                $pid = $conn.OwningProcess
                $proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
                if ($proc) {
                    taskkill /F /T /PID $pid 2>&1 | Out-Null
                    Write-Host "Stopped orphaned process on port $Port (PID $pid)."
                }
            }
        }
    }
}

function Invoke-StepCommand {
    param(
        [string]$CommandName,
        [scriptblock]$Command
    )

    & $Command
    if ($LASTEXITCODE -ne $null -and $LASTEXITCODE -ne 0) {
        throw "$CommandName failed with exit code $LASTEXITCODE"
    }
}

if ($Stop) {
    Write-Step "Stopping dev services"
    Stop-ManagedProcess -Name "frontend" -Port 5173
    Stop-ManagedProcess -Name "spring-backend" -Port 8081
    Stop-ManagedProcess -Name "chatbot-service" -Port 8000
    docker compose -f $HapiCompose down
    docker compose -f $AppPostgresCompose down
    docker compose -f $RedisCompose down
    docker compose -f $QdrantCompose down
    Write-Host "Stopped dev stack. Docker volumes were preserved."
    exit 0
}

Write-Step "Checking required commands"
Require-Command "docker"
Require-Command "python"
Require-Command "java"

Write-Step "Starting Docker infrastructure"
docker compose -f $HapiCompose up -d
docker compose -f $AppPostgresCompose up -d
docker compose -f $RedisCompose up -d
docker compose -f $QdrantCompose up -d

Write-Step "Waiting for HAPI FHIR"
Invoke-StepCommand "wait_for_hapi.py" {
    python (Join-Path $RootDir "infra\hapi-fhir\scripts\wait_for_hapi.py")
}

Write-Step "Checking FHIR demo data"
python (Join-Path $RootDir "infra\hapi-fhir\scripts\check_connection.py")
if ($LASTEXITCODE -ne 0) {
    if ($SkipSeed) {
        throw "FHIR demo data check failed and -SkipSeed was provided."
    }

    Write-Step "Seeding FHIR demo data because check failed"
    Invoke-StepCommand "seed_fhir_data.py" {
        python (Join-Path $RootDir "infra\hapi-fhir\scripts\seed_fhir_data.py")
    }

    Invoke-StepCommand "check_connection.py" {
        python (Join-Path $RootDir "infra\hapi-fhir\scripts\check_connection.py")
    }
}

if (-not $SkipInstall) {
    Write-Step "Installing chatbot-service Python dependencies"
    Invoke-StepCommand "pip install" {
        python -m pip install -r (Join-Path $ChatbotDir "requirements.txt")
    }
}

Write-Step "Configuring Java"
if (Test-Path $JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    java -version
} else {
    Write-Warning "Configured JavaHome was not found: $JavaHome. Using current java on PATH."
    java -version
}

Write-Step "Starting chatbot-service"
Start-ManagedProcess `
    -Name "chatbot-service" `
    -FilePath "python" `
    -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000") `
    -WorkingDirectory $ChatbotDir `
    -Port 8000

Wait-Http -Url "http://localhost:8000/health" -Retries 60 -DelaySeconds 2

Write-Step "Starting spring-backend"
Start-ManagedProcess `
    -Name "spring-backend" `
    -FilePath (Join-Path $SpringDir "mvnw.cmd") `
    -ArgumentList @("spring-boot:run") `
    -WorkingDirectory $SpringDir `
    -Port 8081

Wait-Http -Url "http://localhost:8081/api/health" -Retries 90 -DelaySeconds 2

Write-Step "Starting frontend"
Start-ManagedProcess `
    -Name "frontend" `
    -FilePath "python" `
    -ArgumentList @("-m", "http.server", "5173", "--bind", "127.0.0.1") `
    -WorkingDirectory $FrontendDir `
    -Port 5173

Wait-Http -Url "http://localhost:5173" -Retries 30 -DelaySeconds 2

if (-not $SkipFlowCheck) {
    Write-Step "Checking full chat flow through Spring"
    # Keep the smoke-test payload ASCII to avoid Windows PowerShell source encoding issues.
    $body = @{
        message = "Benh nhan demo-patient-001 dang dung thuoc gi?"
        patient_id = "demo-patient-001"
    } | ConvertTo-Json
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)

    Add-Type -AssemblyName System.Net.Http
    $httpClient = [System.Net.Http.HttpClient]::new()
    $content = [System.Net.Http.ByteArrayContent]::new($bodyBytes)
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/json; charset=utf-8")
    $httpResponse = $httpClient.PostAsync("http://localhost:8081/api/chat", $content).GetAwaiter().GetResult()
    $responseBytes = $httpResponse.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $responseText = [System.Text.Encoding]::UTF8.GetString($responseBytes)
    if (-not $httpResponse.IsSuccessStatusCode) {
        throw "Full chat flow check failed with HTTP $([int]$httpResponse.StatusCode): $responseText"
    }
    $response = $responseText | ConvertFrom-Json

    Write-Host "Intent: $($response.intent)"
    Write-Host "Tool: $($response.tool_name)"
    Write-Host "Answer: $($response.answer)"
}

Write-Step "Dev stack is ready"
Write-Host "HAPI FHIR:        http://localhost:8080/fhir"
Write-Host "chatbot-service: http://localhost:8000"
Write-Host "spring-backend:  http://localhost:8081"
Write-Host "frontend:        http://localhost:5173"
Write-Host "HAPI DB:         localhost:5434 / hapi / admin / admin"
Write-Host "App DB:          localhost:5433 / medical_chatbot_app / app_user / app_password"
Write-Host "Logs directory:  $LogDir"
Write-Host ""
Write-Host "Stop with:"
Write-Host ".\run-dev.ps1 -Stop"
