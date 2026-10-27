param([string]$OutputRoot, [string]$PaperTemplate)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
foreach ($name in @('BANCINEMATIC_TEST_MYSQL_HOST', 'BANCINEMATIC_TEST_MYSQL_DATABASE', 'BANCINEMATIC_TEST_MYSQL_USERNAME', 'BANCINEMATIC_TEST_MYSQL_PASSWORD')) {
    if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Missing environment variable: $name" }
}
if (-not $OutputRoot) { $OutputRoot = Join-Path $projectRoot ('.runtime-test/mysql-load-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
if (Test-Path -LiteralPath $OutputRoot) { throw 'Use a new output directory.' }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$classpathFile = Join-Path $OutputRoot 'classpath.txt'
$savedPassword = $env:BANCINEMATIC_MYSQL_PASSWORD
$savedPort = $env:BCLOAD_PORT
$savedBase = $env:BCLOAD_PLAYER_BASE
$savedPlan = $env:BCLOAD_PLAN
$savedLimit = $env:BCLOAD_MAX_PRELOGIN_MS
Push-Location $projectRoot
$compiled = $false
try {
    & mvn '-DskipTests' package dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-DincludeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'Maven failed.' }
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $classes = Join-Path $OutputRoot 'classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    $runtimeClasspath = "$classes;$(Join-Path $projectRoot 'target/classes');$classpath"
    & javac '-proc:none' -cp $runtimeClasspath -d $classes tools/sqlite-load/SqliteLoadTest.java tools/mysql-load/MysqlInspect.java tools/mysql-load/MysqlLoadTest.java tools/mysql-load/MysqlVerify.java
    if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }
    $compiled = $true
    & java '-XX:ActiveProcessorCount=2' '-Xms128M' '-Xmx512M' -cp $runtimeClasspath MysqlInspect (Join-Path $OutputRoot 'calibration.json') 2>&1 |
        Tee-Object -FilePath (Join-Path $OutputRoot 'calibration.log')
    if ($LASTEXITCODE -ne 0) { throw 'Calibration failed.' }
    & java '-XX:ActiveProcessorCount=2' '-Xms128M' '-Xmx512M' -cp $runtimeClasspath MysqlLoadTest $OutputRoot 2>&1 |
        Tee-Object -FilePath (Join-Path $OutputRoot 'load.log')
    if ($LASTEXITCODE -ne 0) { throw 'Load harness failed.' }
    if ($PaperTemplate) {
        $probeClasses = Join-Path $OutputRoot 'probe-classes'
        New-Item -ItemType Directory -Path $probeClasses | Out-Null
        & javac '-proc:none' -cp $runtimeClasspath -d $probeClasses tools/sqlite-load/SqlitePaperProbe.java
        if ($LASTEXITCODE -ne 0) { throw 'Probe compile failed.' }
        & python tools/mysql-load/prepare-paper.py --template $PaperTemplate --state (Join-Path $OutputRoot 'state.json') --probe-classes $probeClasses --output (Join-Path $OutputRoot 'paper')
        if ($LASTEXITCODE -ne 0) { throw 'Paper preparation failed.' }
        $env:BANCINEMATIC_MYSQL_PASSWORD = $env:BANCINEMATIC_TEST_MYSQL_PASSWORD
        $env:BCLOAD_PORT = '25586'
        $env:BCLOAD_PLAYER_BASE = '8000'
        $env:BCLOAD_PLAN = '[["ramp10_staff0_2",0,10,0.2,500],["burst20_staff0_2",10,20,0.2,0]]'
        $env:BCLOAD_MAX_PRELOGIN_MS = '5000'
        & node tools/sqlite-load/paper-load.cjs (Join-Path $OutputRoot 'paper')
        if ($LASTEXITCODE -ne 0) { throw 'Paper phase failed.' }
    }
    & java -cp $runtimeClasspath MysqlVerify $OutputRoot 2>&1 | Tee-Object -FilePath (Join-Path $OutputRoot 'verification.log')
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed.' }
} finally {
    if ($compiled -and (Test-Path -LiteralPath (Join-Path $OutputRoot 'state.json'))) {
        & java -cp $runtimeClasspath MysqlVerify $OutputRoot cleanup 2>&1 | Tee-Object -FilePath (Join-Path $OutputRoot 'cleanup.log')
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Cleanup failed. Keep state.json to retry cleanup of the owned tables.' }
    }
    $env:BANCINEMATIC_MYSQL_PASSWORD = $savedPassword
    $env:BCLOAD_PORT = $savedPort
    $env:BCLOAD_PLAYER_BASE = $savedBase
    $env:BCLOAD_PLAN = $savedPlan
    $env:BCLOAD_MAX_PRELOGIN_MS = $savedLimit
    Pop-Location
}
