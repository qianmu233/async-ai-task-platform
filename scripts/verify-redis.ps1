param([switch]$StartRedis, [switch]$UseP1Container, [switch]$NonInteractive, [switch]$Offline)

# Explicit real Redis + two child JVMs. No MySQL/MQ regression, cleanup or other project operations.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$names = @('REDIS_HOST','REDIS_PORT','REDIS_USERNAME','REDIS_PASSWORD')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$scriptExit = 2
$stage = 'environment gate'
$secret = $null

function Read-Counts([string]$Directory) {
    $counts = [ordered]@{ tests=0; failures=0; errors=0; skipped=0 }
    if (Test-Path -LiteralPath $Directory) {
        foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml') {
            [xml]$report = [IO.File]::ReadAllText($file.FullName)
            foreach ($field in @('tests','failures','errors','skipped')) {
                $counts[$field] += [int]$report.testsuite.GetAttribute($field)
            }
        }
    }
    return [pscustomobject]$counts
}

try {
    if ($StartRedis -and $UseP1Container) { throw 'Select one infrastructure option' }
    if ($StartRedis -or $UseP1Container) {
        $stage = 'Docker ownership gate'
        $engine = & docker version --format '{{.Server.Version}}' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Docker engine unavailable' }
        $containers = @(& docker ps -a --filter 'name=^/ai-task-platform-redis$' --format '{{.Names}}')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect containers' }
        if ($UseP1Container) {
            if ($containers.Count -ne 1) { throw 'Owned P1 Redis container missing' }
            # Capture locally; NEVER print Docker Config.Env or the decoded password.
            $raw = & docker inspect ai-task-platform-redis
            if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect P1 Redis' }
            $container = ($raw | ConvertFrom-Json)[0]
            if ($container.Config.Labels.'com.ai-task-platform.owner' -ne 'p1' -or
                $container.Config.Labels.'com.docker.compose.project' -ne 'p1-ai-task-redis' -or
                $container.State.Running -ne $true -or
                @($container.Mounts | Where-Object { $_.Name -eq 'ai-task-platform-redis-data' -and $_.Destination -eq '/data' }).Count -ne 1 -or
                @($container.NetworkSettings.Ports.'6379/tcp' | Where-Object { $_.HostIp -eq '127.0.0.1' -and $_.HostPort -eq '6380' }).Count -ne 1) {
                throw 'Container ownership, state, volume or endpoint mismatch'
            }
            $passwordEntry = @($container.Config.Env | Where-Object { $_.StartsWith('REDIS_PASSWORD=') })
            if ($passwordEntry.Count -ne 1) { throw 'Owned container password environment missing' }
            $env:REDIS_PASSWORD = $passwordEntry[0].Substring('REDIS_PASSWORD='.Length)
            $raw=$null; $container=$null; $passwordEntry=$null
        } else {
            $volumes = @(& docker volume ls --filter 'name=^ai-task-platform-redis-data$' --format '{{.Name}}')
            if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect volumes' }
            $networks = @(& docker network ls --filter 'name=^p1-ai-task-redis_default$' --format '{{.Name}}')
            if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect networks' }
            if ($containers.Count -ne 0 -or $volumes.Count -ne 0 -or $networks.Count -ne 0) {
                throw 'P1 resource exists; no recreation or reuse with new credentials. Use -UseP1Container after review'
            }
            $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,6380)
            try { $portProbe.Start() } finally { $portProbe.Stop() }
            # New dedicated container: random secret kept only in process/container environment.
            $env:REDIS_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
        }
        $env:REDIS_HOST='127.0.0.1'; $env:REDIS_PORT='6380'; $env:REDIS_USERNAME='default'
    }
    foreach ($name in @('REDIS_HOST','REDIS_PORT','REDIS_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            if ($name -eq 'REDIS_PASSWORD' -and !$NonInteractive) {
                $secret = Read-Host 'Dedicated P1 Redis password' -AsSecureString
                $env:REDIS_PASSWORD = [Net.NetworkCredential]::new('', $secret).Password
                if ([string]::IsNullOrWhiteSpace($env:REDIS_PASSWORD)) { throw 'Empty password' }
            } else { Write-Host "REFUSED: missing external $name; no integration executed."; throw 'Missing environment' }
        }
    }
    if ($env:REDIS_HOST -ne '127.0.0.1' -or $env:REDIS_PORT -ne '6380') { throw 'Only dedicated P1 local endpoint allowed' }
    $runId = [Guid]::NewGuid().ToString('N')
    $runDirectory = Join-Path $projectRoot "target/phase3b-runs/$runId"
    $buildDirectory = Join-Path $runDirectory 'build'
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    if ($StartRedis) {
        $stage = 'P1 Redis start'
        $ErrorActionPreference='Continue'
        & docker compose -p p1-ai-task-redis -f (Join-Path $projectRoot 'infra/redis-compose.yml') up -d --wait --wait-timeout 120 *> (Join-Path $runDirectory 'redis-start.log')
        $dockerExit = $LASTEXITCODE
        $ErrorActionPreference='Stop'
        if ($dockerExit -ne 0) { throw 'Redis startup failed; inspect local redis-start.log' }
        Write-Host 'Started dedicated ai-task-platform-redis on localhost:6380; container and volume retained.'
    }
    $stage = 'full Maven verify'
    $logPath = Join-Path $runDirectory 'verify.log'
    $maven = (Get-Command mvn -ErrorAction Stop).Source
    $arguments = @('-B','-ntp','-Predis-it',"-Dplatform.build.directory=$buildDirectory",'verify')
    if ($Offline) { $arguments = @('-o') + $arguments }
    Push-Location $projectRoot
    try {
        Write-Host "Running full verify + real Redis cross-JVM acceptance. Log: $logPath"
        $ErrorActionPreference='Continue'
        & $maven @arguments *> $logPath
        $mavenExit = $LASTEXITCODE
    } finally { $ErrorActionPreference='Stop'; Pop-Location }
    $stage = 'report collection'
    $unit = Read-Counts (Join-Path $buildDirectory 'surefire-reports')
    $integration = Read-Counts (Join-Path $buildDirectory 'failsafe-reports')
    $evidence = @([IO.File]::ReadAllLines($logPath) | Where-Object { $_ -match '^REDIS_IT RESULT ' })
    $passed = $mavenExit -eq 0 -and $unit.tests -ge 119 -and $unit.failures -eq 0 -and $unit.errors -eq 0 -and
        $unit.skipped -eq 0 -and $integration.tests -eq 1 -and $integration.failures -eq 0 -and
        $integration.errors -eq 0 -and $integration.skipped -eq 0 -and $evidence.Count -eq 1
    $result = [ordered]@{ phase='3B'; runId=$runId; recordedAtUtc=[DateTime]::UtcNow.ToString('o');
        passed=$passed; mavenExitCode=$mavenExit; offline=$unit; redis=$integration; evidence=$evidence;
        logPath=$logPath; buildDirectory=$buildDirectory }
    $resultJson = $result | ConvertTo-Json -Depth 5
    $resultJson | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json') -Encoding UTF8
    $resultJson | Set-Content -LiteralPath (Join-Path $projectRoot 'target/phase3b-latest.json') -Encoding UTF8
    Write-Host "Offline: $($unit.tests), failures=$($unit.failures), errors=$($unit.errors)"
    Write-Host "Real Redis: $($integration.tests), failures=$($integration.failures), errors=$($integration.errors)"
    foreach ($line in $evidence) { Write-Host $line }
    if ($passed) { Write-Host 'PASS: real Redis and independent JVMs; test JVMs stopped, Redis retained.'; $scriptExit=0 }
    else { Write-Host "FAIL: Maven exit=$mavenExit. See local verify.log and result.json."; $scriptExit=1 }
} catch {
    Write-Host "REFUSED/FAILED during $stage ($($_.Exception.GetType().Name)); no local limiter fallback."
    $scriptExit=2
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    if ($null -ne $secret) { $secret.Dispose() }
    $raw=$null; $container=$null; $passwordEntry=$null
}
exit $scriptExit
