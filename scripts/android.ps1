param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'workspace-path.ps1')
$projectRoot = Get-LedgerBuildRoot
$env:JAVA_HOME = 'E:\jdk-17'
$env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-user'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
Push-Location (Join-Path $projectRoot 'android')
try {
    & (Join-Path $projectRoot '.tools\gradle-8.11.1\bin\gradle.bat') @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
