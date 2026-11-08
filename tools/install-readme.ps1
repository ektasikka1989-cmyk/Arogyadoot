param([string]$RepoPath = 'C:\Arogyadoot')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path $RepoPath).Path
$source = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Test-Path (Join-Path $repo '.git'))) { throw "Not a Git repository root: $repo" }
$root = (& git -C $repo rev-parse --show-toplevel).Trim().Replace('\','/').TrimEnd('/')
if ($root.ToLowerInvariant() -ne $repo.Replace('\','/').TrimEnd('/').ToLowerInvariant()) { throw "Requested path is not Git root: $root" }
$destReadme = Join-Path $repo 'README.md'
$backupRoot = Join-Path ([IO.Path]::GetTempPath()) ('ArogyaDoot_README_Backup_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
if (Test-Path $destReadme) { Copy-Item $destReadme (Join-Path $backupRoot 'README.md') }
$destImgs = Join-Path $repo 'docs\readme-v1'
if (Test-Path $destImgs) { Copy-Item $destImgs (Join-Path $backupRoot 'prior-readme-v1') -Recurse -Force }
$srcImgs = Join-Path $source 'docs\readme-v1'
$images = @(Get-ChildItem -LiteralPath $srcImgs -Filter '*.jpg' -File)
if ($images.Count -ne 8) { throw "Expected 8 JPG screenshots; found $($images.Count). No project files replaced." }
New-Item -ItemType Directory -Force -Path $destImgs | Out-Null
foreach ($f in $images) { Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $destImgs $f.Name) -Force }
Copy-Item -LiteralPath (Join-Path $source 'README.md') -Destination $destReadme -Force
Write-Host "Updated README and $($images.Count) screenshots. Backup: $backupRoot"
Write-Host 'Inspect: git status --short; git diff -- README.md; git diff --check'
Write-Host 'No commit or push was performed.'
