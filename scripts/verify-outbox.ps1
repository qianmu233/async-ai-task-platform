param([switch]$UseP1Broker, [switch]$NonInteractive, [switch]$Offline, [switch]$LocalModelHttp)

# Only AFTER reviewed manual migration. NEVER performs DDL, backfill, cleanup or broker startup.
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$names=@('MYSQL_IT_HOST','MYSQL_IT_PORT','MYSQL_IT_USERNAME','MYSQL_IT_PASSWORD',
    'RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_USERNAME','RABBITMQ_PASSWORD')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$secrets=[Collections.Generic.List[Security.SecureString]]::new()
$scriptExit=2
$stage='migration approval gate'

function Password([string]$Name,[string]$Prompt){
    if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name,'Process'))){
        if($NonInteractive){Write-Host "REFUSED: missing external $Name";throw 'Missing password'}
        $secret=Read-Host $Prompt -AsSecureString
        $secrets.Add($secret)
        $value=[Net.NetworkCredential]::new('', $secret).Password
        if([string]::IsNullOrWhiteSpace($value)){throw 'Empty password'}
        [Environment]::SetEnvironmentVariable($Name,$value,'Process')
        $value=$null
    }
}
function Counts([string]$Path){
    $counts=[ordered]@{tests=0;failures=0;errors=0;skipped=0}
    if(Test-Path -LiteralPath $Path){
        foreach($file in Get-ChildItem -LiteralPath $Path -Filter 'TEST-*.xml'){
            [xml]$xml=[IO.File]::ReadAllText($file.FullName)
            foreach($field in @('tests','failures','errors','skipped')){$counts[$field]+=[int]$xml.testsuite.GetAttribute($field)}
        }
    }
    return [pscustomobject]$counts
}
try{
    if($env:OUTBOX_IT_SCHEMA_APPROVED -cne 'true'){
        Write-Host 'REFUSED: human review and manual 002_outbox.sql migration required. No connection, credential prompt or database write executed.'
        throw 'Migration approval required'
    }
    if($LocalModelHttp -and $env:WORKER_LEASE_IT_SCHEMA_APPROVED -cne 'true'){
        throw 'Local model HTTP pipeline requires reviewed manual 003 migration approval'
    }
    $stage='external credentials'
    if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_HOST)){$env:MYSQL_IT_HOST='127.0.0.1'}
    if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PORT)){$env:MYSQL_IT_PORT='3306'}
    if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_USERNAME)){$env:MYSQL_IT_USERNAME='ai_task_app'}
    if($UseP1Broker){
        $raw=& docker inspect ai-task-platform-rabbitmq 2>$null
        if($LASTEXITCODE -ne 0){throw 'Owned broker inspection failed'}
        $container=($raw | ConvertFrom-Json)[0]
        if($container.Config.Labels.'com.ai-task-platform.owner' -ne 'p1' -or
            $container.Config.Labels.'com.docker.compose.project' -ne 'p1-ai-task' -or
            $container.State.Running -ne $true -or
            @($container.NetworkSettings.Ports.'5672/tcp' | Where-Object { $_.HostIp -eq '127.0.0.1' -and $_.HostPort -eq '5673' }).Count -ne 1){
            throw 'P1 broker owner/state/endpoint mismatch'
        }
        $entry=@($container.Config.Env | Where-Object {$_.StartsWith('RABBITMQ_DEFAULT_PASS=')})
        $user=@($container.Config.Env | Where-Object {$_.StartsWith('RABBITMQ_DEFAULT_USER=')})
        if($entry.Count -ne 1 -or $user.Count -ne 1){throw 'P1 broker environment missing'}
        $env:RABBITMQ_PASSWORD=$entry[0].Substring('RABBITMQ_DEFAULT_PASS='.Length)
        $env:RABBITMQ_USERNAME=$user[0].Substring('RABBITMQ_DEFAULT_USER='.Length)
        $env:RABBITMQ_HOST='127.0.0.1';$env:RABBITMQ_PORT='5673'
        $raw=$null;$container=$null;$entry=$null;$user=$null
    }
    if([string]::IsNullOrWhiteSpace($env:RABBITMQ_HOST)){$env:RABBITMQ_HOST='127.0.0.1'}
    if([string]::IsNullOrWhiteSpace($env:RABBITMQ_PORT)){$env:RABBITMQ_PORT='5673'}
    if([string]::IsNullOrWhiteSpace($env:RABBITMQ_USERNAME)){$env:RABBITMQ_USERNAME='p1_task_app'}
    Password 'MYSQL_IT_PASSWORD' 'MySQL business account password'
    Password 'RABBITMQ_PASSWORD' 'P1 RabbitMQ password'
    $runId=[Guid]::NewGuid().ToString('N')
    $phase=if($LocalModelHttp){'4C'}else{'4A'}
    $runFolder=if($LocalModelHttp){'phase4c-runs'}else{'phase4a-runs'}
    $profile=if($LocalModelHttp){'model-http-it'}else{'outbox-it'}
    $tag=if($LocalModelHttp){'MODEL_HTTP_IT'}else{'OUTBOX_IT'}
    $minimumUnitTests=if($LocalModelHttp){146}else{130}
    $directory=Join-Path $projectRoot "target/$runFolder/$runId"
    $build=Join-Path $directory 'build'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $log=Join-Path $directory 'verify.log'
    $maven=(Get-Command mvn -ErrorAction Stop).Source
    $arguments=@('-B','-ntp',"-P$profile","-Dplatform.build.directory=$build",'verify')
    if($Offline){$arguments=@('-o')+$arguments}
    $stage='full Maven verify and real Outbox acceptance'
    Push-Location $projectRoot
    try{
        Write-Host "Running Phase $phase real MySQL/RabbitMQ acceptance; model HTTP mode=$LocalModelHttp (loopback simulation only); log: $log"
        $ErrorActionPreference='Continue'
        & $maven @arguments *> $log
        $mavenExit=$LASTEXITCODE
    }finally{$ErrorActionPreference='Stop';Pop-Location}
    $unit=Counts (Join-Path $build 'surefire-reports')
    $integration=Counts (Join-Path $build 'failsafe-reports')
    $evidence=@([IO.File]::ReadAllLines($log) | Where-Object {$_ -match "^$tag RESULT "})
    $passed=$mavenExit -eq 0 -and $unit.tests -ge $minimumUnitTests -and $unit.failures -eq 0 -and $unit.errors -eq 0 -and
        $unit.skipped -eq 0 -and $integration.tests -eq 1 -and $integration.failures -eq 0 -and
        $integration.errors -eq 0 -and $integration.skipped -eq 0 -and $evidence.Count -eq 1
    $result=[ordered]@{phase=$phase;runId=$runId;recordedAtUtc=[DateTime]::UtcNow.ToString('o');passed=$passed;
        mavenExitCode=$mavenExit;offline=$unit;integration=$integration;localSimulatedModel=[bool]$LocalModelHttp;
        evidence=$evidence;logPath=$log;buildDirectory=$build}
    # Preserve Phase 4A's existing result shape for consumers of its reports.
    if(!$LocalModelHttp){$result['outbox']=$integration}
    $resultJson=$result | ConvertTo-Json -Depth 5
    $resultJson | Set-Content -LiteralPath (Join-Path $directory 'result.json') -Encoding UTF8
    $latest=if($LocalModelHttp){'phase4c-latest.json'}else{'phase4a-latest.json'}
    $resultJson | Set-Content -LiteralPath (Join-Path $projectRoot "target/$latest") -Encoding UTF8
    Write-Host "Offline=$($unit.tests) failures=$($unit.failures) errors=$($unit.errors); real Outbox=$($integration.tests) failures=$($integration.failures) errors=$($integration.errors)"
    foreach($line in $evidence){Write-Host $line}
    if($passed){Write-Host 'PASS: real Outbox pipeline; own rows/queues retained';$scriptExit=0}
    else{Write-Host "FAIL: Maven exit=$mavenExit; see local reports";$scriptExit=1}
}catch{
    Write-Host "REFUSED/FAILED during $stage ($($_.Exception.GetType().Name)); no direct-publish or memory fallback."
    $scriptExit=2
}finally{
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    foreach($secret in $secrets){$secret.Dispose()}
    $raw=$null;$container=$null;$entry=$null;$user=$null
}
exit $scriptExit
