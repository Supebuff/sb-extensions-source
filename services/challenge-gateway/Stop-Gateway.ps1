param([Parameter(Mandatory = $true)][string]$StateDirectory, [switch]$Force)
$ErrorActionPreference = 'Stop'
$pidPath = Join-Path $StateDirectory 'gateway.pid'
if (-not (Test-Path -LiteralPath $pidPath)) { Write-Output 'No managed gateway PID.'; exit 0 }
$gatewayPid = [int](Get-Content -LiteralPath $pidPath)
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$gatewayPid"
$configPath = [IO.Path]::GetFullPath((Join-Path $StateDirectory 'config.json'))
if ($process -and $process.CommandLine.Contains('run.py') -and $process.CommandLine.Contains($configPath)) {
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $address = if ($config.bind -eq '0.0.0.0') { '127.0.0.1' } else { $config.bind }
    try {
        Invoke-RestMethod -Method Post -Uri "http://${address}:$($config.port)/v1/shutdown" `
            -Headers @{ Authorization = "Bearer $($config.api_key)" } -TimeoutSec 5 | Out-Null
        Wait-Process -Id $gatewayPid -Timeout 25 -ErrorAction Stop
    } catch {
        if (-not $Force) { throw 'Graceful stop failed; use -Force only to terminate this managed gateway and its browser children.' }
        & taskkill.exe /PID $gatewayPid /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the managed process tree.' }
    }
    Remove-Item -LiteralPath $pidPath
    Write-Output 'Gateway stopped.'
} elseif ($process) {
    throw 'PID belongs to another process; refusing to stop it.'
} else {
    Remove-Item -LiteralPath $pidPath
    Write-Output 'Gateway is no longer running.'
}
