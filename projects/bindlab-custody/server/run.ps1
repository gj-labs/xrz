# Launch the BindLab XR hub for a LAN demo (phones/headsets on the same Wi-Fi).
# Usage: .\run.ps1 [-Port 8000] [-BindHost 0.0.0.0]
# --ws-max-size 8 MiB lets the headset post 6 MB JPEG snapshots over the websocket.
# Note: no authentication; only bind to 0.0.0.0 on a trusted demo network.
param(
    [int]$Port = 8000,
    [string]$BindHost = "0.0.0.0",
    [switch]$XrUsb
)
$ErrorActionPreference = "Stop"
if ($XrUsb) { $env:BINDLAB_XR_USB = '1' }
Set-Location $PSScriptRoot
$py = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
    Write-Host "Creating .venv and installing requirements..."
    python -m venv .venv
    & $py -m pip install -q -r requirements.txt
}
$ip = try {
    (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
        Sort-Object InterfaceMetric | Select-Object -First 1).IPAddress
} catch { "<this machine's IPv4>" }
Write-Host "BindLab hub: http://${ip}:$Port  (health: /health, web app: / if web/dist is built)"
& $py -m uvicorn main:app --host $BindHost --port $Port --ws-max-size 8388608
