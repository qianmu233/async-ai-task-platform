param([switch]$UseP1Broker,[switch]$NonInteractive,[switch]$Offline)

# Reuse existing safe credentials, owned-broker inspection, counts and evidence pipeline.
# This always selects a test-owned loopback model HTTP endpoint, never a third-party API.
& (Join-Path $PSScriptRoot 'verify-outbox.ps1') -LocalModelHttp -UseP1Broker:$UseP1Broker -NonInteractive:$NonInteractive -Offline:$Offline
exit $LASTEXITCODE
