param([switch]$CheckOnly)
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
& (Join-Path $root 'Main.ps1') -CheckOnly:$CheckOnly
