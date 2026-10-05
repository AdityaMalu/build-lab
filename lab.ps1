<#
  Build Lab launcher.

    .\lab.ps1                         start the web UI (http://localhost:8090)
    .\lab.ps1 -Port 9000              start on another port
    .\lab.ps1 test rate-limiter                    run tests against your Java workspace code
    .\lab.ps1 test rate-limiter workspace go       ...your Go code (java | python | go | cpp)
    .\lab.ps1 test rate-limiter solution python    run tests against the reference solution
    .\lab.ps1 verify                               every project, every language, against its solution
#>
param(
    [Parameter(Position = 0)] [string] $Command = "serve",
    [Parameter(Position = 1)] [string] $Project,
    [Parameter(Position = 2)] [string] $Mode = "workspace",
    [Parameter(Position = 3)] [string] $Lang = "java",
    [int] $Port = 8090
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Find-Java {
    $candidates = @()
    $local = Get-ChildItem -Path (Join-Path $PSScriptRoot ".jdk") -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($local) { $candidates += (Join-Path $local.FullName "bin\java.exe") }
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\java.exe") }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($c in $candidates) {
        if (Test-Path $c) {
            $javac = Join-Path (Split-Path $c) "javac.exe"
            if (Test-Path $javac) { return $c }
        }
    }
    Write-Host "No JDK 17+ found (need java AND javac)." -ForegroundColor Red
    Write-Host "Install one with:  winget install Microsoft.OpenJDK.21" -ForegroundColor Yellow
    exit 1
}

$java = Find-Java

switch ($Command) {
    "serve" { & $java server/LabServer.java $Port }
    "test" {
        if (-not $Project) { Write-Host "usage: .\lab.ps1 test <project-id> [workspace|solution|starter] [java|python|go|cpp]"; exit 1 }
        & $java server/LabServer.java test $Project $Mode $Lang
        exit $LASTEXITCODE
    }
    "verify" {
        $ids = (Get-Content projects/catalog.json -Raw | ConvertFrom-Json) | ForEach-Object { $_.id }
        $bad = @()
        foreach ($id in $ids) {
            foreach ($l in "java", "python", "go", "cpp") {
                if (-not (Test-Path "projects/$id/$l/starter/src")) { continue }
                Write-Host "== $id ($l)" -ForegroundColor Cyan
                & $java server/LabServer.java test $id solution $l
                if ($LASTEXITCODE -ne 0) { $bad += "$id ($l)" }
            }
        }
        if ($bad.Count -eq 0) { Write-Host "`nAll reference solutions pass." -ForegroundColor Green }
        else { Write-Host "`nFailing: $($bad -join ', ')" -ForegroundColor Red; exit 1 }
    }
    default { Write-Host "unknown command '$Command'"; exit 1 }
}
