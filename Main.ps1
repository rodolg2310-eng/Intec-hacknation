param([switch]$CheckOnly, [switch]$NoBrowser, [switch]$Stop)
$ErrorActionPreference = 'Stop'
# Use this shell's modules when started from an IDE that inherited PowerShell 7 paths.
$env:PSModulePath = "$PSHOME\Modules;$env:SystemRoot\system32\WindowsPowerShell\v1.0\Modules;$env:PSModulePath"
Import-Module Microsoft.PowerShell.Utility -Force
$root = $PSScriptRoot
$apiProject = Join-Path $root 'Backend\Capacitación\apprentice-training-api\apprentice-training-api'
$webProject = Join-Path $root 'Frontend\traina\traina'
$runtimeDir = Join-Path $root '.runtime'
$logsDir = Join-Path $runtimeDir 'logs'
$stateFile = Join-Path $runtimeDir 'processes.json'
New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
function Stop-Owned($entry) {
    if (-not $entry) { return }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
    if ($processInfo -and $processInfo.CommandLine -and $processInfo.CommandLine.Contains($entry.artifact)) {
        Stop-Process -Id $entry.pid -Force -ErrorAction SilentlyContinue
    }
}
$previous = if (Test-Path -LiteralPath $stateFile) { Get-Content -LiteralPath $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
if ($Stop) { Stop-Owned $previous.web; Stop-Owned $previous.api; Remove-Item -LiteralPath $stateFile -Force -ErrorAction SilentlyContinue; Write-Host 'Traina detenida.'; exit 0 }
if (Test-Path -LiteralPath (Join-Path $root '.env')) {
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env') -Encoding UTF8) {
        if ($line.TrimStart([char]0xFEFF) -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            $keyName = $Matches[1]; $keyValue = $Matches[2].Trim().Trim('"').Trim("'")
            [Environment]::SetEnvironmentVariable($keyName, $keyValue, 'Process')
        }
    }
}
$javaCandidates = [System.Collections.Generic.List[string]]::new()
if ($env:JAVA_HOME) { $javaCandidates.Add($env:JAVA_HOME) }
$bundledJava = Join-Path $apiProject 'src\main\java\com\apprentice\ai\oracleJdk-27'
if (Test-Path -LiteralPath $bundledJava) { $javaCandidates.Add($bundledJava) }
$javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
if ($javacCommand) { $javaCandidates.Add((Split-Path -Parent (Split-Path -Parent $javacCommand.Source))) }
$javaHome = $null
foreach ($candidate in ($javaCandidates | Select-Object -Unique)) {
    $candidateJava = Join-Path $candidate 'bin\java.exe'
    $candidateJavac = Join-Path $candidate 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $candidateJava) -or -not (Test-Path -LiteralPath $candidateJavac)) { continue }
    $javaVersionText = (& $candidateJava -version 2>&1 | Out-String)
    if ($javaVersionText -match 'version "(?:1\.)?(\d+)' -and [int]$Matches[1] -ge 21) { $javaHome = $candidate; break }
}
if (-not $javaHome) { throw 'Se requiere un JDK 21 o superior. Instálalo y agrégalo a PATH o configura JAVA_HOME.' }
$javaExe = Join-Path $javaHome 'bin\java.exe'
$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
$nodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $nodeCommand -or -not $npmCommand) { throw 'Instala Node.js 22 o superior y vuelve a ejecutar Main.' }
if ([int]((& $nodeCommand.Source -v).TrimStart('v').Split('.')[0]) -lt 22) { throw 'Se requiere Node.js 22 o superior.' }
$toolsDir = Join-Path $env:LOCALAPPDATA 'PrototipoHack\tools'
$mavenVersion = '3.9.16'
$mavenExe = Join-Path $toolsDir "apache-maven-$mavenVersion\bin\mvn.cmd"
$installedMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($installedMaven) { $mavenExe = $installedMaven.Source }
if (-not (Test-Path -LiteralPath $mavenExe)) {
    New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
    $archivePath = Join-Path $toolsDir "apache-maven-$mavenVersion-bin.zip"
    $downloadUrl = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
    Write-Host 'Preparando Maven en el perfil local...'
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath -UseBasicParsing
    $expectedHash = ((Invoke-WebRequest -Uri "$downloadUrl.sha512" -UseBasicParsing).Content.Trim() -split '\s+')[0]
    if ($expectedHash -notmatch '^[0-9a-fA-F]{128}$' -or (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash -ne $expectedHash) { throw 'El archivo de Maven no superó la verificación SHA-512.' }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsDir -Force
    Remove-Item -LiteralPath $archivePath -Force
}
function Is-Stale($artifactPath, $sourcePaths) {
    if (-not (Test-Path -LiteralPath $artifactPath)) { return $true }
    $builtAt = (Get-Item -LiteralPath $artifactPath).LastWriteTimeUtc
    foreach ($sourcePath in $sourcePaths) {
        if ((Get-Item -LiteralPath $sourcePath).PSIsContainer) {
            if (Get-ChildItem -LiteralPath $sourcePath -Recurse -File | Where-Object { $_.FullName -notlike '*\oracleJdk-27\*' -and $_.LastWriteTimeUtc -gt $builtAt } | Select-Object -First 1) { return $true }
        } elseif ((Get-Item -LiteralPath $sourcePath).LastWriteTimeUtc -gt $builtAt) { return $true }
    }
    return $false
}
function Is-RunningArtifactStale($entry, $artifactPath) {
    if (-not $entry -or -not (Test-Path -LiteralPath $artifactPath)) { return $false }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
    if (-not $processInfo -or -not $processInfo.CommandLine -or -not $processInfo.CommandLine.Contains($artifactPath)) { return $false }
    $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
    if (-not $process) { return $false }
    return (Get-Item -LiteralPath $artifactPath).LastWriteTimeUtc -gt $process.StartTime.ToUniversalTime().AddSeconds(2)
}
$ffmpegVersion = '9.0.2'
$recordingToolsDir = Join-Path $runtimeDir 'tools'
$ffmpegDir = Join-Path $recordingToolsDir "ffmpeg-$ffmpegVersion-essentials_build"
$ffmpegExe = Join-Path $ffmpegDir 'bin\ffmpeg.exe'
if (-not (Test-Path -LiteralPath $ffmpegExe)) {
    New-Item -ItemType Directory -Path $recordingToolsDir -Force | Out-Null
    $ffmpegZip = Join-Path $runtimeDir "ffmpeg-$ffmpegVersion.zip"
    Write-Host 'Preparing portable FFmpeg for recording playback...'
    Invoke-WebRequest -Uri "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-$ffmpegVersion-essentials_build.zip" -OutFile $ffmpegZip -UseBasicParsing
    if ((Get-FileHash -LiteralPath $ffmpegZip -Algorithm SHA256).Hash -ne '60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba') { throw 'FFmpeg checksum verification failed.' }
    Expand-Archive -LiteralPath $ffmpegZip -DestinationPath $recordingToolsDir -Force
    Remove-Item -LiteralPath $ffmpegZip -Force
}
$env:FFMPEG_PATH = $ffmpegExe
$env:Path = "$(Join-Path $ffmpegDir 'bin');$env:Path"
$jarPath = Join-Path $apiProject 'target\training-api-1.0.0.jar'
$serverPath = Join-Path $webProject '.output\server\index.mjs'
$apiStale = (Is-Stale $jarPath @((Join-Path $apiProject 'src\main'), (Join-Path $apiProject 'pom.xml'))) -or (Is-RunningArtifactStale $previous.api $jarPath)
$webStale = (Is-Stale $serverPath @((Join-Path $webProject 'src'), (Join-Path $webProject 'public'), (Join-Path $webProject 'package.json'), (Join-Path $webProject 'package-lock.json'), (Join-Path $webProject 'vite.config.ts'))) -or (Is-RunningArtifactStale $previous.web $serverPath)
if ($apiStale -or $CheckOnly) {
    Stop-Owned $previous.api
    Write-Host 'Compilando backend y comprobando sus pruebas...'
    Push-Location $apiProject
    try { & $mavenExe -q package; if ($LASTEXITCODE -ne 0) { throw 'La compilación del backend falló.' } } finally { Pop-Location }
}
Push-Location $webProject
try {
    $lockHash = (Get-FileHash -LiteralPath 'package-lock.json').Hash
    $stampPath = Join-Path $runtimeDir 'npm-lock.sha256'
    $installNeeded = -not (Test-Path -LiteralPath 'node_modules') -or -not (Test-Path -LiteralPath $stampPath) -or (Get-Content -LiteralPath $stampPath -Raw).Trim() -ne $lockHash
    if ($installNeeded) { Write-Host 'Preparando dependencias web...'; & $npmCommand.Source ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'No se pudieron preparar las dependencias web.' }; Set-Content -LiteralPath $stampPath -Value $lockHash -Encoding UTF8 }
    if ($CheckOnly) { & $npmCommand.Source exec -- tsc --noEmit; if ($LASTEXITCODE -ne 0) { throw 'La comprobación TypeScript falló.' }; & $npmCommand.Source test; if ($LASTEXITCODE -ne 0) { throw 'Las pruebas web fallaron.' } }
    if ($webStale -or $CheckOnly) { Stop-Owned $previous.web; Write-Host 'Compilando interfaz...'; & $npmCommand.Source run build; if ($LASTEXITCODE -ne 0) { throw 'La compilación de la interfaz falló.' } }
} finally { Pop-Location }
if ($CheckOnly) { Write-Host 'Compilación y pruebas correctas. Ejecuta Main para abrir la aplicación.'; exit 0 }
function Wait-Service($url, $name) {
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        try { $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2; if ($response.StatusCode -eq 200) { return } } catch {}
        Start-Sleep -Milliseconds 800
    }
    throw "No arrancó $name. Revisa los archivos en $logsDir."
}
function Get-Owned($entry) {
    if (-not $entry) { return $null }
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
    if ($p -and $p.CommandLine -and $p.CommandLine.Contains($entry.artifact)) { return $entry }
    return $null
}
$apiEntry = Get-Owned $previous.api
$webEntry = Get-Owned $previous.web
$env:APP_DATA_DIR = Join-Path $runtimeDir 'data'
$env:APP_ALLOWED_ORIGINS = 'http://localhost:3000,http://127.0.0.1:3000'
$env:DEMO_DATA = 'false'
$env:PORT = '8080'
if (-not $apiEntry) {
    if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { throw 'El puerto 8080 está ocupado por otro proceso. Ciérralo y vuelve a ejecutar Main.' }
    $schemaMarker = Join-Path $runtimeDir 'schema-v4.ready'
    $existingDatabase = Join-Path $runtimeDir 'data\traina.mv.db'
    if ((Test-Path -LiteralPath $existingDatabase) -and -not (Test-Path -LiteralPath $schemaMarker)) {
        $migrationBackupDir = Join-Path $runtimeDir ('backups\schema-v4-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        New-Item -ItemType Directory -Path $migrationBackupDir -Force | Out-Null
        Copy-Item -LiteralPath $existingDatabase -Destination $migrationBackupDir
        Write-Host "Database backup saved before migration: $migrationBackupDir"
    }
    Write-Host 'Iniciando API y base persistente local...'
    $p = Start-Process -FilePath $javaExe -ArgumentList @('-jar', "`"$jarPath`"", '--spring.profiles.active=local', '--server.address=127.0.0.1') -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsDir 'api.log') -RedirectStandardError (Join-Path $logsDir 'api-error.log')
    $apiEntry = @{ pid = $p.Id; artifact = $jarPath }
}
@{ api = $apiEntry; web = $webEntry } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $stateFile -Encoding UTF8
Wait-Service 'http://127.0.0.1:8080/api/health' 'el backend'
Set-Content -LiteralPath (Join-Path $runtimeDir 'schema-v4.ready') -Value 'roles-and-live-camera-evidence-v4' -Encoding UTF8
$env:PORT = '3000'; $env:HOST = '127.0.0.1'; $env:API_ORIGIN = 'http://127.0.0.1:8080'
if (-not $webEntry) {
    if (Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue) { throw 'El puerto 3000 está ocupado por otro proceso. Ciérralo y vuelve a ejecutar Main.' }
    Write-Host 'Iniciando interfaz...'
    $p = Start-Process -FilePath $nodeCommand.Source -ArgumentList "`"$serverPath`"" -WorkingDirectory $webProject -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsDir 'web.log') -RedirectStandardError (Join-Path $logsDir 'web-error.log')
    $webEntry = @{ pid = $p.Id; artifact = $serverPath }
}
@{ api = $apiEntry; web = $webEntry } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $stateFile -Encoding UTF8
Wait-Service 'http://127.0.0.1:3000/api/health' 'la interfaz y su conexión con el backend'
Write-Host 'Traina lista: http://localhost:3000'
Write-Host 'Claude razona; ElevenLabs convierte sus respuestas en voz.'
Write-Host 'Tus datos permanecen en .runtime/data. Para detener: Main.cmd -Stop'
if (-not $NoBrowser) { Start-Process 'http://localhost:3000' }
