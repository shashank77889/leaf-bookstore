$ErrorActionPreference = 'Stop'
$backendPath = Join-Path $PSScriptRoot 'backend'
if (-not (Test-Path (Join-Path $backendPath 'target/leaf-store-1.0.0.jar'))) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}
Push-Location $backendPath
try {
    Write-Host 'Starting Leaf at http://127.0.0.1:8080 (Ctrl+C to stop)'
    java -jar target/leaf-store-1.0.0.jar
} finally { Pop-Location }
