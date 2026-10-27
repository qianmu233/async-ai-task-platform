param([string]$JavaHome=$env:P1_JAVA_HOME,[switch]$NonInteractive,[switch]$Offline)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if([string]::IsNullOrWhiteSpace($JavaHome)){$JavaHome=$env:JAVA_HOME}
$java=Join-Path $JavaHome 'bin/java.exe'
if(!(Test-Path -LiteralPath $java)){throw 'Provide existing JDK 17 through -JavaHome or P1_JAVA_HOME; no installation is performed'}
$ErrorActionPreference='Continue'
$version=(& $java -version 2>&1 | ForEach-Object {$_.ToString()} | Out-String)
$javaExit=$LASTEXITCODE
$ErrorActionPreference='Stop'
if($javaExit -ne 0){throw 'Java runtime check failed'}
if($version -notmatch 'version "17\.'){throw 'Phase 5 requires actual JDK 17'}
$names=@('JAVA_HOME','MYSQL_IT_HOST','MYSQL_IT_PORT','MYSQL_IT_USERNAME','MYSQL_IT_PASSWORD','RABBITMQ_USERNAME','RABBITMQ_PASSWORD')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$secret=$null
try{
    $env:JAVA_HOME=$JavaHome;$env:MYSQL_IT_HOST='127.0.0.1';$env:MYSQL_IT_PORT='3306';$env:MYSQL_IT_USERNAME='ai_task_app'
    if([string]::IsNullOrWhiteSpace($env:MYSQL_IT_PASSWORD)){
        if($NonInteractive){throw 'Missing external MYSQL_IT_PASSWORD'}
        $secret=Read-Host 'MySQL business account password' -AsSecureString
        $env:MYSQL_IT_PASSWORD=[Net.NetworkCredential]::new('', $secret).Password
    }
    # Inspect only the existing owned P1 broker. No recreation/cleanup/Compose mutation.
    $raw=& docker inspect ai-task-platform-rabbitmq 2>$null
    if($LASTEXITCODE -ne 0){throw 'Existing P1 broker unavailable'}
    $container=($raw|ConvertFrom-Json)[0]
    if($container.Config.Labels.'com.ai-task-platform.owner' -ne 'p1' -or $container.Config.Labels.'com.docker.compose.project' -ne 'p1-ai-task' -or !$container.State.Running -or
        @($container.NetworkSettings.Ports.'5672/tcp'|Where-Object{$_.HostIp -eq '127.0.0.1' -and $_.HostPort -eq '5673'}).Count -ne 1){throw 'P1 broker ownership/endpoint mismatch'}
    $env:RABBITMQ_USERNAME=(@($container.Config.Env|Where-Object{$_.StartsWith('RABBITMQ_DEFAULT_USER=')})[0]).Substring('RABBITMQ_DEFAULT_USER='.Length)
    $env:RABBITMQ_PASSWORD=(@($container.Config.Env|Where-Object{$_.StartsWith('RABBITMQ_DEFAULT_PASS=')})[0]).Substring('RABBITMQ_DEFAULT_PASS='.Length)
    $brokerVersion=$container.Config.Image
    $raw=$null;$container=$null
    $run=Join-Path $root ('target/phase5-runs/'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $run -Force|Out-Null
    $build=Join-Path $run 'build';$log=Join-Path $run 'verify.log'
    $arguments=@('-B','-ntp',"-Dplatform.build.directory=$build",'verify');if($Offline){$arguments=@('-o')+$arguments}
    Push-Location $root
    try{$ErrorActionPreference='Continue';& mvn @arguments *> $log;$code=$LASTEXITCODE}finally{$ErrorActionPreference='Stop';Pop-Location}
    if($code -ne 0){throw 'Full JDK 17 verify failed; see local log'}
    $counts=@{tests=0;failures=0;errors=0;skipped=0}
    foreach($file in Get-ChildItem (Join-Path $build 'surefire-reports') -Filter 'TEST-*.xml'){
        [xml]$xml=[IO.File]::ReadAllText($file.FullName);foreach($field in @('tests','failures','errors','skipped')){$counts[$field]+=[int]$xml.testsuite.GetAttribute($field)}
    }
    if($counts.tests -lt 146 -or $counts.failures -ne 0 -or $counts.errors -ne 0 -or $counts.skipped -ne 0){throw 'Default tests gate failed'}
    $jar=Join-Path $build 'ai-task-platform-0.1.0-SNAPSHOT.jar'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($jar)
    try{$entry=@($zip.Entries|Where-Object{$_.FullName -match '^BOOT-INF/lib/mysql-connector-j-[^/]+\.jar$'});if($entry.Count -ne 1){throw 'Missing JDBC driver'};$driver=Join-Path $run 'mysql-driver.jar';[IO.Compression.ZipFileExtensions]::ExtractToFile($entry[0],$driver)}finally{$zip.Dispose()}
    $ErrorActionPreference='Continue'
    & $java --class-path $driver (Join-Path $PSScriptRoot 'support/DatabasePreflight.java') *> (Join-Path $run 'preflight.log');$preflightExit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($preflightExit -ne 0){throw 'Read-only schema/permission preflight failed'}
    # Namespace filtering is always applied by the production JAR; globalScanSafe=false never permits global polling.
    $ErrorActionPreference='Continue'
    & $java --class-path $driver (Join-Path $PSScriptRoot 'support/ScheduledPipelineAcceptance.java') $jar $run *> (Join-Path $run 'acceptance.log');$acceptanceExit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($acceptanceExit -ne 0 -or !(Test-Path (Join-Path $run 'result.json'))){throw 'Independent JVM acceptance failed; see local log'}
    $result=Get-Content -Raw -Encoding UTF8 (Join-Path $run 'result.json')|ConvertFrom-Json
    if(!$result.passed -or !$result.scheduledDispatch -or !$result.historicalUnchanged){throw 'Real evidence gate failed'}
    $fault=Start-Process $java -ArgumentList @('-jar',('"'+$jar+'"'),'--debug=false','--server.port=0','--platform.storage.mode=memory','--platform.messaging.mode=local','--platform.outbox.enabled=false','--platform.execution.timeout-enabled=false','--platform.rate-limit.enabled=false','--platform.acceptance.key-prefix=','--platform.model.mode=invalid') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $run 'startup-failure.log') -RedirectStandardError (Join-Path $run 'startup-failure-error.log') -PassThru
    if(!$fault.WaitForExit(10000)){Stop-Process -InputObject $fault;throw 'Invalid configuration failed to reject startup'}
    $faultText=[IO.File]::ReadAllText((Join-Path $run 'startup-failure.log'))+[IO.File]::ReadAllText((Join-Path $run 'startup-failure-error.log'))
    if($fault.ExitCode -eq 0 -or !$faultText.Contains('Model mode must be mock or openai-compatible')){throw 'Startup failure reason mismatch'}
    $metadata=@{recordedAtUtc=[DateTime]::UtcNow.ToString('o');offline=$counts;java=$version.Trim();logicalProcessors=[Environment]::ProcessorCount;
        cpu=(Get-CimInstance Win32_Processor|Select-Object -First 1 -ExpandProperty Name);memoryGB=[Math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1);
        os=(Get-CimInstance Win32_OperatingSystem).Caption;brokerImage=$brokerVersion;startupRejected=$true;result=$result;directory=$run}
    $metadata|ConvertTo-Json -Depth 6|Set-Content (Join-Path $run 'summary.json') -Encoding UTF8
    $metadata|ConvertTo-Json -Depth 6|Set-Content (Join-Path $root 'target/phase5-latest.json') -Encoding UTF8
    Write-Host ('PASS JDK17 scheduled pipeline; result='+$run+'; success='+$result.success+' failed='+$result.failed+' timeout='+$result.timeout)
}finally{
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    if($null -ne $secret){$secret.Dispose()}
}
