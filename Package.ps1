param(
    # Skips the tests, for a second package of a build already tested.
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'

# Builds the app and zips it for someone else: dist\ClashFarmerPC-<version>-<date>.zip holds the
# ClashFarmerPC folder and READ-ME-FIRST.txt, nothing else. The version is appVersion in
# gradle.properties.
$app = Join-Path $PSScriptRoot 'app\build\compose\binaries\main\app\ClashFarmerPC'

# A copy running from the build folder holds its files, so the build can't replace them.
$running = Get-Process -Name ClashFarmerPC -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($app, [StringComparison]::OrdinalIgnoreCase) }
if ($running) { throw "Clash Farmer is running from $app. Quit it from its tray icon, then package again." }

$tasks = if ($SkipTests) { @(':app:createDistributable') } else { @(':app:test', ':app:createDistributable') }
& (Join-Path $PSScriptRoot 'Build.ps1') -Tasks $tasks
if (!(Test-Path -LiteralPath (Join-Path $app 'ClashFarmerPC.exe'))) { throw "The build left no ClashFarmerPC.exe in $app." }

$version = (Select-String -LiteralPath (Join-Path $PSScriptRoot 'gradle.properties') -Pattern '^appVersion=(.+)$').Matches[0].Groups[1].Value.Trim()
$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$zip = Join-Path $dist ('ClashFarmerPC-{0}-{1}.zip' -f $version, (Get-Date -Format 'yyyyMMdd'))
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip }

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$level = [System.IO.Compression.CompressionLevel]::Optimal
[System.IO.Compression.ZipFile]::CreateFromDirectory($app, $zip, $level, $true)
$archive = [System.IO.Compression.ZipFile]::Open($zip, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $readMe = Join-Path $PSScriptRoot 'packaging\READ-ME-FIRST.txt'
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $readMe, 'READ-ME-FIRST.txt', $level) | Out-Null
} finally {
    $archive.Dispose()
}

Write-Host "Packaged $zip"
Write-Host "SHA256 $((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash)"
