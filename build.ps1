param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not $SkipInstall -and -not (Test-Path 'node_modules')) {
        npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
$staticPath = Join-Path $projectRoot 'backend/src/main/resources/static'
New-Item -ItemType Directory -Force $staticPath | Out-Null
Copy-Item -Path (Join-Path $projectRoot 'frontend/dist/*') -Destination $staticPath -Recurse -Force
Push-Location (Join-Path $projectRoot 'backend')
try {
    mvn.cmd -B package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
} finally { Pop-Location }
Write-Host 'Built Leaf. Run .\start.ps1 and open http://127.0.0.1:8080'
$packagePath = Join-Path $projectRoot 'Leaf Bookstore'
New-Item -ItemType Directory -Force $packagePath | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend/target/leaf-store-1.0.0.jar') -Destination (Join-Path $packagePath 'Leaf Bookstore.jar') -Force
Write-Host 'Updated Leaf Bookstore/Leaf Bookstore.jar'
