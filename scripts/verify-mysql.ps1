param(
    [string]$MySqlHost = '127.0.0.1',
    [ValidateRange(1, 65535)][int]$MySqlPort = 3306,
    [switch]$NonInteractive,
    [switch]$Offline
)

# Explicit opt-in: environment credentials or one secure prompt, then one full verify.
# No server start/stop, schema changes, data cleanup, or credential files.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$environmentNames = @('MYSQL_IT_HOST', 'MYSQL_IT_PORT', 'MYSQL_IT_USERNAME', 'MYSQL_IT_PASSWORD')
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$scriptExit = 2
$stage = 'environment configuration'
$passwordPrompt = $null

function Read-TestCounts([string]$Directory) {
    $counts = [ordered]@{ reports = 0; tests = 0; failures = 0; errors = 0; skipped = 0 }
    if (Test-Path -LiteralPath $Directory) {
        foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml') {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Encoding UTF8
            $counts.reports++
            foreach ($name in @('tests', 'failures', 'errors', 'skipped')) {
                $counts[$name] += [int]$xml.testsuite.GetAttribute($name)
            }
        }
    }
    return [pscustomobject]$counts
}

try {
    if ([string]::IsNullOrWhiteSpace($env:MYSQL_IT_HOST)) { $env:MYSQL_IT_HOST = $MySqlHost }
    if ([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PORT)) { $env:MYSQL_IT_PORT = [string]$MySqlPort }
    if ([string]::IsNullOrWhiteSpace($env:MYSQL_IT_USERNAME)) { $env:MYSQL_IT_USERNAME = 'ai_task_app' }
    if ($env:MYSQL_IT_USERNAME -ne 'ai_task_app') {
        Write-Host 'REFUSED: MYSQL_IT_USERNAME must be ai_task_app.'
        exit 2
    }
    if ([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PASSWORD)) {
        if ($NonInteractive) {
            Write-Host 'REFUSED: missing MYSQL_IT_PASSWORD; no database connection attempted.'
            exit 2
        }
        $passwordPrompt = Read-Host 'MySQL ai_task_app password' -AsSecureString
        $env:MYSQL_IT_PASSWORD = [Net.NetworkCredential]::new('', $passwordPrompt).Password
        if ([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PASSWORD)) {
            Write-Host 'REFUSED: empty password; no database connection attempted.'
            exit 2
        }
    }
    $stage = 'Maven execution'
    $maven = (Get-Command mvn -ErrorAction Stop).Source
    $runId = [Guid]::NewGuid().ToString('N')
    $runDirectory = Join-Path $projectRoot "target/phase2b-runs/$runId"
    $buildDirectory = Join-Path $runDirectory 'build'
    $logPath = Join-Path $runDirectory 'verify.log'
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    $mavenArguments = @('-B', '-ntp', '-Pmysql-it', "-Dplatform.build.directory=$buildDirectory", 'verify')
    if ($Offline) { $mavenArguments = @('-o') + $mavenArguments }
    Push-Location $projectRoot
    try {
        Write-Host "Running full offline suite and real MySQL acceptance. Log: $logPath"
        # Windows PowerShell may otherwise promote native stderr warnings to terminating errors.
        $ErrorActionPreference = 'Continue'
        & $maven @mavenArguments *> $logPath
        $mavenExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    $stage = 'report collection'
    $offlineCounts = Read-TestCounts (Join-Path $buildDirectory 'surefire-reports')
    $mysqlCounts = Read-TestCounts (Join-Path $buildDirectory 'failsafe-reports')
    # Plain .NET strings avoid serializing Get-Content's PSPath/PSDrive metadata into JSON.
    $evidence = @([IO.File]::ReadAllLines($logPath) | Where-Object { $_ -match '^MYSQL_IT ' })
    $preflightCount = @($evidence | Where-Object { $_ -match '^MYSQL_IT PREFLIGHT ' }).Count
    $summary = @($evidence | Where-Object { $_ -match '^MYSQL_IT SUMMARY ' } | Select-Object -Last 1)
    $passed = ($mavenExit -eq 0 -and $offlineCounts.tests -ge 87 -and
        $offlineCounts.failures -eq 0 -and $offlineCounts.errors -eq 0 -and $offlineCounts.skipped -eq 0 -and
        $mysqlCounts.tests -eq 22 -and $mysqlCounts.failures -eq 0 -and $mysqlCounts.errors -eq 0 -and
        $mysqlCounts.skipped -eq 0 -and $preflightCount -eq 2)
    $report = [ordered]@{
        recordedAtUtc = [DateTime]::UtcNow.ToString('o')
        phase = '2B'
        runId = $runId
        passed = $passed
        mavenExitCode = $mavenExit
        offline = $offlineCounts
        mysql = $mysqlCounts
        preflightConnections = $preflightCount
        logPath = $logPath
        buildDirectory = $buildDirectory
        evidence = $evidence
    }
    $reportJson = $report | ConvertTo-Json -Depth 5
    $reportJson | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json') -Encoding UTF8
    $reportJson | Set-Content -LiteralPath (Join-Path $projectRoot 'target/phase2b-latest.json') -Encoding UTF8
    Write-Host "Offline: tests=$($offlineCounts.tests) failures=$($offlineCounts.failures) errors=$($offlineCounts.errors)"
    Write-Host "MySQL: tests=$($mysqlCounts.tests) failures=$($mysqlCounts.failures) errors=$($mysqlCounts.errors) skipped=$($mysqlCounts.skipped)"
    foreach ($line in $summary) { Write-Host $line }
    if ($passed) {
        Write-Host 'PASS: Phase 2B acceptance; test rows retained, no cleanup performed.'
        $scriptExit = 0
    } else {
        Write-Host "FAIL: Phase 2B acceptance. Maven exit=$mavenExit. See the log and result.json."
        $scriptExit = 1
    }
} catch {
    # No original exception message/cause: an external component may include credentials.
    Write-Host "FAIL: $stage ($($_.Exception.GetType().Name)); no fallback or automatic retry."
    $scriptExit = 1
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if ($null -ne $passwordPrompt) { $passwordPrompt.Dispose() }
}
exit $scriptExit
