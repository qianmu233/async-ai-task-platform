param([switch]$StartBroker, [switch]$NonInteractive, [switch]$Offline)

# Explicit real MySQL + Rabbit opt-in. Only this project's resources; no schema/data/container cleanup.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$environmentNames = @('MYSQL_IT_HOST','MYSQL_IT_PORT','MYSQL_IT_USERNAME','MYSQL_IT_PASSWORD',
    'RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_USERNAME','RABBITMQ_PASSWORD')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$promptedSecrets = [Collections.Generic.List[Security.SecureString]]::new()
$scriptExit = 2
$stage = 'environment gate'

function Require-Password([string]$Name, [string]$Prompt) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, 'Process'))) {
        if ($NonInteractive) { Write-Host "REFUSED: missing external $Name; no integration executed."; throw 'Missing external password' }
        $secret = Read-Host $Prompt -AsSecureString
        $promptedSecrets.Add($secret)
        $password = [Net.NetworkCredential]::new('', $secret).Password
        if ([string]::IsNullOrWhiteSpace($password)) { throw 'Empty password' }
        [Environment]::SetEnvironmentVariable($Name, $password, 'Process')
    }
}
function Read-Counts([string]$Directory) {
    $result = [ordered]@{ tests=0; failures=0; errors=0; skipped=0 }
    if (Test-Path -LiteralPath $Directory) {
        foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml') {
            [xml]$report = [IO.File]::ReadAllText($file.FullName)
            foreach ($field in @('tests','failures','errors','skipped')) {
                $result[$field] += [int]$report.testsuite.GetAttribute($field)
            }
        }
    }
    return [pscustomobject]$result
}

try {
    if ([string]::IsNullOrWhiteSpace($env:MYSQL_IT_HOST)) { $env:MYSQL_IT_HOST='127.0.0.1' }
    if ([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PORT)) { $env:MYSQL_IT_PORT='3306' }
    if ([string]::IsNullOrWhiteSpace($env:MYSQL_IT_USERNAME)) { $env:MYSQL_IT_USERNAME='ai_task_app' }
    if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_HOST)) { $env:RABBITMQ_HOST='127.0.0.1' }
    if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_PORT)) { $env:RABBITMQ_PORT='5673' }
    if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_USERNAME)) { $env:RABBITMQ_USERNAME='p1_task_app' }
    if ($StartBroker) {
        $stage = 'Docker readiness'
        $ErrorActionPreference='Continue'
        $engineVersion = & docker version --format '{{.Server.Version}}' 2>$null
        $engineExit = $LASTEXITCODE
        $ErrorActionPreference='Stop'
        if ($engineExit -ne 0) { Write-Host 'REFUSED: Docker engine unavailable; start Docker Desktop once.'; throw 'Docker engine unavailable' }
        $existing = @(& docker ps -a --filter 'name=^/ai-task-platform-rabbitmq$' --format '{{.Names}}')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker containers' }
        if ($existing.Count -ne 0) {
            # No recreation, restart, or credential changes for an existing container.
            Write-Host 'REFUSED: P1 container already exists. Supply its existing environment credentials and run without -StartBroker.'
            exit 2
        }
        $ownedVolume = @(& docker volume ls --filter 'name=^ai-task-platform-rabbitmq-data$' --format '{{.Name}}')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker volumes' }
        if ($ownedVolume.Count -ne 0) {
            Write-Host 'REFUSED: P1 data volume already exists; no reuse with new credentials or cleanup is performed.'
            exit 2
        }
    }
    # Configure credentials before any new container is created, avoiding a half-configured setup.
    Require-Password 'MYSQL_IT_PASSWORD' 'MySQL ai_task_app password'
    Require-Password 'RABBITMQ_PASSWORD' 'RabbitMQ P1 password (new container: choose once; existing broker: use existing password)'
    $stage = 'integration run'
    $runId = [Guid]::NewGuid().ToString('N')
    $runDirectory = Join-Path $projectRoot "target/phase3a-runs/$runId"
    $buildDirectory = Join-Path $runDirectory 'build'
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    if ($StartBroker) {
        $stage = 'P1 broker start'
        $ErrorActionPreference='Continue'
        & docker compose -f (Join-Path $projectRoot 'infra/rabbitmq-compose.yml') up -d --wait --wait-timeout 120 *> (Join-Path $runDirectory 'broker-start.log')
        $brokerExit = $LASTEXITCODE
        $ErrorActionPreference='Stop'
        if ($brokerExit -ne 0) { throw 'P1 broker startup failed; see broker-start.log' }
        Write-Host 'Started dedicated P1 broker on localhost:5673; P2 resources were not modified.'
    }
    $stage = 'Maven verify'
    $logPath = Join-Path $runDirectory 'verify.log'
    $maven = (Get-Command mvn -ErrorAction Stop).Source
    $arguments = @('-B','-ntp','-Prabbit-it',"-Dplatform.build.directory=$buildDirectory",'verify')
    if ($Offline) { $arguments = @('-o') + $arguments }
    Push-Location $projectRoot
    try {
        Write-Host "Running full verify + real Rabbit/MySQL/HTTP. Log: $logPath"
        $ErrorActionPreference='Continue'
        & $maven @arguments *> $logPath
        $mavenExit = $LASTEXITCODE
    } finally { $ErrorActionPreference='Stop'; Pop-Location }
    $stage = 'report collection'
    $offlineCounts = Read-Counts (Join-Path $buildDirectory 'surefire-reports')
    $rabbitCounts = Read-Counts (Join-Path $buildDirectory 'failsafe-reports')
    $evidence = @([IO.File]::ReadAllLines($logPath) | Where-Object { $_ -match '^RABBIT_IT ' })
    $passed = $mavenExit -eq 0 -and $offlineCounts.tests -ge 99 -and $offlineCounts.failures -eq 0 -and
        $offlineCounts.errors -eq 0 -and $offlineCounts.skipped -eq 0 -and $rabbitCounts.tests -eq 1 -and
        $rabbitCounts.failures -eq 0 -and $rabbitCounts.errors -eq 0 -and $rabbitCounts.skipped -eq 0 -and
        @($evidence | Where-Object { $_ -match '^RABBIT_IT RESULT ' }).Count -eq 1
    $result = [ordered]@{ phase='3A'; runId=$runId; recordedAtUtc=[DateTime]::UtcNow.ToString('o');
        passed=$passed; mavenExitCode=$mavenExit; offline=$offlineCounts; rabbit=$rabbitCounts;
        logPath=$logPath; buildDirectory=$buildDirectory; evidence=$evidence }
    $resultJson = $result | ConvertTo-Json -Depth 5
    $resultJson | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json') -Encoding UTF8
    $resultJson | Set-Content -LiteralPath (Join-Path $projectRoot 'target/phase3a-latest.json') -Encoding UTF8
    Write-Host "Offline: $($offlineCounts.tests), failures=$($offlineCounts.failures), errors=$($offlineCounts.errors)"
    Write-Host "Real pipeline: $($rabbitCounts.tests), failures=$($rabbitCounts.failures), errors=$($rabbitCounts.errors)"
    foreach ($line in $evidence) { Write-Host $line }
    if ($passed) { Write-Host 'PASS: real Rabbit/MySQL/HTTP pipeline. Own rows and queues retained.'; $scriptExit=0 }
    else { Write-Host "FAIL: pipeline; Maven exit=$mavenExit. See verify.log and result.json."; $scriptExit=1 }
} catch {
    # No original messages/causes containing external connection credentials.
    Write-Host "REFUSED/FAILED during $stage ($($_.Exception.GetType().Name)); no in-memory fallback."
    $scriptExit=2
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') }
    foreach ($secret in $promptedSecrets) { $secret.Dispose() }
}
exit $scriptExit
