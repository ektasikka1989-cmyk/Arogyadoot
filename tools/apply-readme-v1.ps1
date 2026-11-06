# ArogyaDoot v1.0.0 README installation, after applying the accompanying Git patch.
# Run from the repository root: powershell -ExecutionPolicy Bypass -File tools\apply-readme-v1.ps1
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourceReadme = Join-Path $repoRoot 'docs\github\README-v1.0.0.md'
$targetReadme = Join-Path $repoRoot 'README.md'
if (-not (Test-Path $sourceReadme)) { throw "Missing staged README: $sourceReadme" }
if (-not (Test-Path (Join-Path $repoRoot 'docs\readme-v1\265409.jpg'))) {
    throw 'Screenshots were not applied. Stop and apply the Git patch first.'
}
$backupFolder = Join-Path $env:TEMP ('ArogyaDoot-README-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backupFolder | Out-Null
if (Test-Path $targetReadme) {
    Copy-Item -LiteralPath $targetReadme -Destination (Join-Path $backupFolder 'README.md')
}
Copy-Item -LiteralPath $sourceReadme -Destination $targetReadme -Force
Write-Host "README.md updated to ArogyaDoot 1.0.0. Previous file saved in: $backupFolder"
Write-Host 'Review git diff, image privacy and links before commit/push.'
