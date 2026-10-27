param([string]$BaseUrl = 'http://127.0.0.1:8081', [string]$KeyPrefix = '')

$ErrorActionPreference = 'Stop'
if($KeyPrefix -ne '' -and $KeyPrefix -notmatch '^[A-Za-z0-9][A-Za-z0-9.:-]{0,95}:$'){throw 'Invalid literal smoke acceptance prefix'}
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(10)

function Invoke-TaskApi {
    param([string]$Method, [string]$Path, [object]$Payload, [int]$ExpectedStatus)
    $request = New-Object System.Net.Http.HttpRequestMessage
    $request.Method = New-Object System.Net.Http.HttpMethod($Method)
    $request.RequestUri = [Uri]($BaseUrl.TrimEnd('/') + $Path)
    if ($null -ne $Payload) {
        $json = $Payload | ConvertTo-Json -Compress
        $request.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
    }
    $response = $null
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        if ($status -ne $ExpectedStatus) {
            throw "$Method $Path expected $ExpectedStatus but received $status : $body"
        }
        Write-Host "HTTP $status $Method $Path"
        return ($body | ConvertFrom-Json)
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Wait-TaskTerminal {
    param([string]$TaskId)
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        $state = Invoke-TaskApi -Method GET -Path "/api/tasks/$TaskId" -ExpectedStatus 200
        if ($state.status -eq 'SUCCESS' -or $state.status -eq 'FAILED') { return $state }
        Start-Sleep -Milliseconds 100
    }
    throw "Task $TaskId did not reach a terminal state within the polling limit"
}

try {
    $key = $KeyPrefix + 'smoke-' + [Guid]::NewGuid().ToString('N')
    $payload = @{ idempotencyKey = $key; input = 'hello platform' }
    $submitted = Invoke-TaskApi -Method POST -Path '/api/tasks' -Payload $payload -ExpectedStatus 202
    $duplicate = Invoke-TaskApi -Method POST -Path '/api/tasks' -Payload $payload -ExpectedStatus 200
    if ($submitted.taskId -ne $duplicate.taskId -or $duplicate.created -ne $false) {
        throw 'Duplicate submission did not reuse the original task'
    }
    $state = Wait-TaskTerminal -TaskId $submitted.taskId
    if ($state.status -ne 'SUCCESS') { throw 'Expected SUCCESS' }
    $result = Invoke-TaskApi -Method GET -Path "/api/tasks/$($submitted.taskId)/result" -ExpectedStatus 200
    if ($result.output -ne '[MOCK] hello platform') { throw 'Unexpected mock result' }
    Invoke-TaskApi -Method POST -Path '/api/tasks' -ExpectedStatus 409 -Payload @{
        idempotencyKey = $key; input = 'changed input'
    } | Out-Null
    Invoke-TaskApi -Method POST -Path '/api/tasks' -ExpectedStatus 400 -Payload @{
        idempotencyKey = 'invalid-' + $key; input = ' '
    } | Out-Null
    Invoke-TaskApi -Method POST -Path '/api/tasks' -ExpectedStatus 400 -Payload @{
        idempotencyKey = 'unknown-' + $key; input = 'hello'; unexpectedField = 'value'
    } | Out-Null
    Invoke-TaskApi -Method GET -Path ('/api/tasks/' + [Guid]::NewGuid()) -ExpectedStatus 404 | Out-Null
    Invoke-TaskApi -Method GET -Path '/api/tasks/not-a-uuid' -ExpectedStatus 400 | Out-Null
    $failed = Invoke-TaskApi -Method POST -Path '/api/tasks' -ExpectedStatus 202 -Payload @{
        idempotencyKey = $key + '-fail'; input = '[MOCK_FAIL]'
    }
    $failedState = Wait-TaskTerminal -TaskId $failed.taskId
    if ($failedState.status -ne 'FAILED') { throw 'Expected FAILED' }
    $failedResult = Invoke-TaskApi -Method GET -Path "/api/tasks/$($failed.taskId)/result" -ExpectedStatus 409
    if ($failedResult.errorCode -ne 'MODEL_CALL_FAILED') { throw 'Unexpected failure code' }
    Write-Host 'SMOKE PASS: success, duplicate, conflict, validation, not-found and model failure'
} finally {
    $client.Dispose()
}
