$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $root

$ssh = Get-Command ssh.exe -ErrorAction SilentlyContinue
if (-not $ssh) {
    throw 'No encuentro OpenSSH (ssh.exe) en Windows. Instala el cliente OpenSSH y vuelve a abrir Compartir.cmd.'
}

Write-Host 'Iniciando Traina en esta PC...'
& (Join-Path $root 'Main.ps1') -NoBrowser
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
    throw 'Traina no pudo iniciar. Revisa el mensaje anterior.'
}

Write-Host ''
Write-Host 'Abriendo un enlace HTTPS temporal. Si SSH pregunta si confias en localhost.run, escribe yes.'
Write-Host 'Deja esta ventana abierta mientras uses el enlace.'
Write-Host ''
& $ssh.Source -R '80:localhost:3000' 'nokey@localhost.run'
