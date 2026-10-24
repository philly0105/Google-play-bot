param(
    [string[]]$Tasks = @(':app:test', ':app:createDistributable')
)
$ErrorActionPreference = 'Stop'

# tools/ is gitignored, so a git worktree has none; use the main checkout's.
$tools = Join-Path $PSScriptRoot 'tools'
if (!(Test-Path -LiteralPath (Join-Path $tools 'jdk'))) {
    $common = (& git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir).Trim()
    $tools = Join-Path (Split-Path $common -Parent) 'tools'
}
$gradle = Join-Path $tools 'gradle-8.11.1\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradle)) {
    throw "Gradle is missing from $tools. Copy jdk and gradle-8.11.1 there (see AGENTS.md)."
}

$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'GRADLE_USER_HOME')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:JAVA_HOME = Join-Path $tools 'jdk'
    $env:GRADLE_USER_HOME = Join-Path $tools 'gradle-cache'
    Push-Location $PSScriptRoot
    try {
        & $gradle @Tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
