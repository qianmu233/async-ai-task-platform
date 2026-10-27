param([string]$JavaHome=$env:JAVA_HOME,[string]$JarPath,[int]$Port=8081,[ValidateSet('memory','mysql-outbox')][string]$Mode='memory',[string]$AcceptancePrefix='')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if([string]::IsNullOrWhiteSpace($JarPath)){$JarPath=Join-Path $root 'target/ai-task-platform-0.1.0-SNAPSHOT.jar'}
$JarPath=[IO.Path]::GetFullPath($JarPath)
if(!$JarPath.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or !(Test-Path -LiteralPath $JarPath)){throw 'Provide a built P1 JAR within this project'}
if([string]::IsNullOrWhiteSpace($JavaHome)){throw 'Provide -JavaHome or JAVA_HOME'}
$java=Join-Path $JavaHome 'bin/java.exe'
if(!(Test-Path -LiteralPath $java)){throw 'Java executable unavailable; no software installation is performed'}
if($Port -lt 1024 -or $Port -gt 65535){throw 'Invalid local port'}
if(@(([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties()).GetActiveTcpListeners()|Where-Object{$_.Port -eq $Port}).Count -ne 0){throw 'Port is already in use; no other process is stopped'}
$state=Join-Path $root 'target/platform-process.json'
if(Test-Path -LiteralPath $state){$old=Get-Content -Raw $state|ConvertFrom-Json;if(Get-Process -Id $old.pid -ErrorAction SilentlyContinue){throw 'Tracked process exists; review and stop it first'}}
$names=@('TASK_STORAGE_MODE','TASK_MESSAGING_MODE','TASK_QUEUE_PREFIX','TASK_OUTBOX_ENABLED','TASK_EXECUTION_TIMEOUT_ENABLED','TASK_ACCEPTANCE_KEY_PREFIX','MODEL_MODE','SERVER_ADDRESS','SERVER_PORT','TASK_RATE_LIMIT_ENABLED')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    $env:SERVER_ADDRESS='127.0.0.1';$env:SERVER_PORT="$Port";$env:MODEL_MODE='mock';$env:TASK_RATE_LIMIT_ENABLED='false'
    if($Mode -eq 'memory'){
        if(![string]::IsNullOrEmpty($AcceptancePrefix)){throw 'Acceptance prefix requires mysql-outbox'}
        $env:TASK_STORAGE_MODE='memory';$env:TASK_MESSAGING_MODE='local';$env:TASK_OUTBOX_ENABLED='false';$env:TASK_EXECUTION_TIMEOUT_ENABLED='false';$env:TASK_ACCEPTANCE_KEY_PREFIX=''
    }else{
        if($AcceptancePrefix -notmatch '^[A-Za-z0-9][A-Za-z0-9.:-]{0,95}:$'){throw 'This shared database launcher requires an explicit literal acceptance prefix ending in colon'}
        foreach($name in @('AI_TASK_DB_USERNAME','AI_TASK_DB_PASSWORD','RABBITMQ_USERNAME','RABBITMQ_PASSWORD')){if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name,'Process'))){throw "Missing external $name"}}
        $env:TASK_STORAGE_MODE='mysql';$env:TASK_MESSAGING_MODE='rabbit';$env:TASK_OUTBOX_ENABLED='true';$env:TASK_EXECUTION_TIMEOUT_ENABLED='true';$env:TASK_ACCEPTANCE_KEY_PREFIX=$AcceptancePrefix
        $env:TASK_QUEUE_PREFIX='p1.demo.'+[Guid]::NewGuid().ToString('N')
    }
    $logs=Join-Path $root ('target/platform-starts/'+[Guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $logs -Force|Out-Null
    # Credentials only in inherited environment; no credential command-line arguments or persisted configuration.
    $argumentList=@('-jar',('"'+$JarPath+'"'),'--debug=false')
    $process=Start-Process $java -ArgumentList $argumentList -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'app.log') -RedirectStandardError (Join-Path $logs 'app-error.log') -PassThru
    @{owner='p1-ai-task-platform';pid=$process.Id;startedTicks=$process.StartTime.ToUniversalTime().Ticks;jar=$JarPath;port=$Port;mode=$Mode;acceptancePrefix=$AcceptancePrefix;logs=$logs}|ConvertTo-Json|Set-Content -LiteralPath $state -Encoding UTF8
    Add-Type -AssemblyName System.Net.Http
    $client=New-Object Net.Http.HttpClient;$client.Timeout=[TimeSpan]::FromSeconds(2)
    try{
        $deadline=[DateTime]::UtcNow.AddSeconds(20)
        do{
            if($process.HasExited){throw 'P1 process exited during startup; review local logs, no fallback'}
            try{$response=$client.GetAsync("http://127.0.0.1:$Port/api/tasks/$([Guid]::NewGuid())").GetAwaiter().GetResult();$ready=[int]$response.StatusCode -eq 404;$response.Dispose()}catch{$ready=$false}
            if($ready){Write-Host "P1 started PID=$($process.Id) mode=$Mode port=$Port; logs=$logs";break}
            if([DateTime]::UtcNow -ge $deadline){throw 'P1 startup deadline exceeded'}
            Start-Sleep -Milliseconds 200
        }while($true)
    }catch{if(!$process.HasExited){Stop-Process -InputObject $process};throw}finally{$client.Dispose()}
}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
