$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

Push-Location $projectRoot
try {
    & sbt --server publishLocal
    if ($LASTEXITCODE -ne 0) { throw "publishLocal failed with exit code $LASTEXITCODE" }

    Push-Location (Join-Path $projectRoot 'examples\runtime-consumer')
    try {
        & sbt --server 'runMain RuntimeSmoke'
        if ($LASTEXITCODE -ne 0) { throw "RuntimeSmoke failed with exit code $LASTEXITCODE" }
    }
    finally { Pop-Location }
}
finally { Pop-Location }
