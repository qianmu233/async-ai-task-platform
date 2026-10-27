$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$state=Join-Path $root 'target/platform-process.json'
if(!(Test-Path -LiteralPath $state)){Write-Host 'No tracked P1 process';exit 0}
$record=Get-Content -Raw -LiteralPath $state|ConvertFrom-Json
if($record.owner -ne 'p1-ai-task-platform' -or !$record.jar.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected process record; no process stopped'}
$process=Get-Process -Id $record.pid -ErrorAction SilentlyContinue
if($null -eq $process){Write-Host 'Tracked P1 process already stopped';exit 0}
if($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().Ticks -ne [long]$record.startedTicks){throw 'PID identity mismatch; no process stopped'}
$command=(Get-CimInstance Win32_Process -Filter ('ProcessId='+$record.pid)).CommandLine
if([string]::IsNullOrWhiteSpace($command) -or $command.IndexOf($record.jar,[StringComparison]::OrdinalIgnoreCase) -lt 0 -or $command -notmatch '\s-jar\s'){throw 'P1 JAR command identity mismatch; no process stopped'}
$command=$null
Stop-Process -InputObject $process
Write-Host "Stopped only tracked P1 JVM PID=$($record.pid); infrastructure/data retained"
