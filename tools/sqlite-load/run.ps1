param(
    [ValidateSet('small', 'large', 'both')][string]$Profile = 'both',
    [string]$OutputRoot
)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $projectRoot ('.runtime-test/sqlite-load-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
if (Test-Path -LiteralPath $OutputRoot) { throw 'Use a new output directory to preserve earlier results.' }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$classpathFile = Join-Path $OutputRoot 'classpath.txt'
Push-Location $projectRoot
try {
    & mvn '-DskipTests' package dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-DincludeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'Maven failed.' }
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $classes = Join-Path $OutputRoot 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $runtimeClasspath = "$classes;$(Join-Path $projectRoot 'target/classes');$classpath"
    & javac '-proc:none' -cp $runtimeClasspath -d $classes (Join-Path $PSScriptRoot 'SqliteLoadTest.java') (Join-Path $PSScriptRoot 'SqliteQueueLimitTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }
    $profiles = if ($Profile -eq 'both') { @('small', 'large') } else { @($Profile) }
    foreach ($item in $profiles) {
        & java '-XX:ActiveProcessorCount=2' '-Xms128M' '-Xmx512M' -cp $runtimeClasspath SqliteLoadTest (Join-Path $OutputRoot $item) $item 2>&1 |
            Tee-Object -FilePath (Join-Path $OutputRoot "$item.log")
        if ($LASTEXITCODE -ne 0) { throw "Load test failed: $item" }
        if ($item -eq 'large') {
            & java '-XX:ActiveProcessorCount=2' '-Xms128M' '-Xmx512M' -cp $runtimeClasspath SqliteQueueLimitTest (Join-Path $OutputRoot 'large/bancinematic.db') (Join-Path $OutputRoot 'queue-limit') 2>&1 |
                Tee-Object -FilePath (Join-Path $OutputRoot 'queue-limit.log')
            if ($LASTEXITCODE -ne 0) { throw 'Queue limit test failed.' }
        }
    }
    Write-Output "Results: $OutputRoot"
} finally {
    Pop-Location
}
