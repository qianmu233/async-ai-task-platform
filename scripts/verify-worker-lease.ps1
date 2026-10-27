param([switch]$NonInteractive,[switch]$Offline)

# Explicit post-migration real MySQL acceptance. No DDL, legacy data scan, cleanup, MQ or model retry.
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$names=@('MYSQL_IT_HOST','MYSQL_IT_PORT','MYSQL_IT_USERNAME','MYSQL_IT_PASSWORD')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$secret=$null
$exitCode=2
$stage='003 migration approval gate'
function Counts([string]$Path){
    $counts=[ordered]@{tests=0;failures=0;errors=0;skipped=0}
    if(Test-Path -LiteralPath $Path){foreach($file in Get-ChildItem -LiteralPath $Path -Filter 'TEST-*.xml'){
        [xml]$xml=[IO.File]::ReadAllText($file.FullName)
        foreach($field in @('tests','failures','errors','skipped')){$counts[$field]+=[int]$xml.testsuite.GetAttribute($field)}
    }}
    return [pscustomobject]$counts
}
try{
    if($env:WORKER_LEASE_IT_SCHEMA_APPROVED -cne 'true'){
        Write-Host 'REFUSED: human review and manual 003_worker_lease.sql migration required. No connection or write executed.'
        throw 'Migration approval required'
    }
    $stage='external MySQL credentials'
    if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_HOST)){$env:MYSQL_IT_HOST='127.0.0.1'}
    if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PORT)){$env:MYSQL_IT_PORT='3306'}
    if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_USERNAME)){$env:MYSQL_IT_USERNAME='ai_task_app'}
    if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PASSWORD)){
        if($NonInteractive){Write-Host 'REFUSED: missing external MYSQL_IT_PASSWORD';throw 'Missing password'}
        $secret=Read-Host 'MySQL business account password' -AsSecureString
        $env:MYSQL_IT_PASSWORD=[Net.NetworkCredential]::new('', $secret).Password
        if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PASSWORD)){throw 'Empty password'}
    }
    $runId=[Guid]::NewGuid().ToString('N')
    $directory=Join-Path $root "target/phase4b-runs/$runId"
    $build=Join-Path $directory 'build'
    New-Item -ItemType Directory -Path $directory -Force|Out-Null
    $log=Join-Path $directory 'verify.log'
    $maven=(Get-Command mvn -ErrorAction Stop).Source
    $arguments=@('-B','-ntp','-Pworker-lease-it',"-Dplatform.build.directory=$build",'verify')
    if($Offline){$arguments=@('-o')+$arguments}
    $stage='full verify and real worker deadline acceptance'
    Push-Location $root
    try{
        Write-Host "Running real MySQL execution deadline acceptance; log: $log"
        $ErrorActionPreference='Continue'
        & $maven @arguments *> $log
        $mavenExit=$LASTEXITCODE
    }finally{$ErrorActionPreference='Stop';Pop-Location}
    $unit=Counts (Join-Path $build 'surefire-reports')
    $integration=Counts (Join-Path $build 'failsafe-reports')
    $evidence=@([IO.File]::ReadAllLines($log)|Where-Object{$_ -match '^WORKER_LEASE_IT RESULT '})
    $preflight=@([IO.File]::ReadAllLines($log)|Where-Object{$_ -match '^WORKER_LEASE_IT PREFLIGHT '})
    $passed=$mavenExit -eq 0 -and $unit.tests -ge 138 -and $unit.failures -eq 0 -and $unit.errors -eq 0 -and
        $unit.skipped -eq 0 -and $integration.tests -eq 1 -and $integration.failures -eq 0 -and
        $integration.errors -eq 0 -and $integration.skipped -eq 0 -and $evidence.Count -eq 1 -and $preflight.Count -eq 2
    $result=[ordered]@{phase='4B';runId=$runId;recordedAtUtc=[DateTime]::UtcNow.ToString('o');passed=$passed;
        mavenExitCode=$mavenExit;offline=$unit;workerLease=$integration;preflight=$preflight;evidence=$evidence;logPath=$log;buildDirectory=$build}
    $json=$result|ConvertTo-Json -Depth 5
    $json|Set-Content -LiteralPath (Join-Path $directory 'result.json') -Encoding UTF8
    $json|Set-Content -LiteralPath (Join-Path $root 'target/phase4b-latest.json') -Encoding UTF8
    Write-Host "Offline=$($unit.tests) failures=$($unit.failures) errors=$($unit.errors); real Lease=$($integration.tests) failures=$($integration.failures) errors=$($integration.errors)"
    foreach($line in $preflight){Write-Host $line}
    foreach($line in $evidence){Write-Host $line}
    if($passed){Write-Host 'PASS: real execution deadline/ownership; only this run rows retained';$exitCode=0}
    else{Write-Host "FAIL: Maven exit=$mavenExit; see local reports";$exitCode=1}
}catch{
    Write-Host "REFUSED/FAILED during $stage ($($_.Exception.GetType().Name)); no legacy or memory fallback."
    $exitCode=2
}finally{
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    if($null -ne $secret){$secret.Dispose()}
}
exit $exitCode
