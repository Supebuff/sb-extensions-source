param(
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$StateDirectory,
    [string]$BindAddress = '127.0.0.1',
    [int]$Port = 8787
)
$ErrorActionPreference = 'Stop'
$pythonPath = (Resolve-Path -LiteralPath $Python).Path
$statePath = [IO.Path]::GetFullPath($StateDirectory)
$sourcePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ($statePath.StartsWith($sourcePath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep gateway state and API keys outside the source checkout.'
}
New-Item -ItemType Directory -Path $statePath -Force | Out-Null
$configPath = Join-Path $statePath 'config.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    $keyBytes = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($keyBytes) } finally { $generator.Dispose() }
    @{ api_key = [Convert]::ToBase64String($keyBytes); bind = $BindAddress; port = $Port; real_chrome = $true; x1hub_media_hosts = @() } |
        ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding utf8
}
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$healthAddress = if ($config.bind -eq '0.0.0.0') { '127.0.0.1' } else { $config.bind }
$health = "http://${healthAddress}:$($config.port)/healthz"
try {
    $existing = Invoke-RestMethod -Uri $health -TimeoutSec 2
    if ($existing.api -eq 1) { Write-Output "Gateway already responds on port $($config.port)."; exit 0 }
} catch { }
$runner = Join-Path $PSScriptRoot 'run.py'
$arguments = '"{0}" --config "{1}"' -f $runner, $configPath
$process = Start-Process -FilePath $pythonPath -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $statePath 'stdout.log') -RedirectStandardError (Join-Path $statePath 'stderr.log')
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Milliseconds 300
    $process.Refresh()
    if ($process.HasExited) { throw 'Gateway exited during startup; inspect the private stderr.log.' }
    try {
        $response = Invoke-RestMethod -Uri $health -TimeoutSec 1
        if ($response.api -eq 1) {
            $process.Id | Set-Content -LiteralPath (Join-Path $statePath 'gateway.pid')
            Write-Output "Gateway started in background on $($config.bind):$($config.port)."
            Write-Output "Private configuration: $configPath"
            exit 0
        }
    } catch { }
}
Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
throw 'Gateway did not become ready; inspect the private logs.'
