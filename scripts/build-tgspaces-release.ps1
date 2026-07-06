$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
Set-Location -LiteralPath $projectRoot

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:JAVA_HOME = "D:\AndroidStudio\jbr"
}

$javaBin = Join-Path $env:JAVA_HOME "bin"
if (-not (Test-Path -LiteralPath $javaBin -PathType Container)) {
    throw "JAVA_HOME\bin was not found: $javaBin"
}

$pathParts = $env:PATH -split [IO.Path]::PathSeparator
if ($pathParts -notcontains $javaBin) {
    $env:PATH = $javaBin + [IO.Path]::PathSeparator + $env:PATH
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
java -version
if ($LASTEXITCODE -ne 0) {
    throw "java -version failed with exit code $LASTEXITCODE"
}

Write-Host ""
Write-Host "==> Building TGSpaces release APK"
& .\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) {
    throw "Gradle release build failed with exit code $LASTEXITCODE"
}

$sourceApk = Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
    throw "Release APK was not found: $sourceApk"
}

$distDir = Join-Path $projectRoot "dist\release-apks"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

$targetApk = Join-Path $distDir "TGSpaces-release.apk"
Copy-Item -LiteralPath $sourceApk -Destination $targetApk -Force

Write-Host ""
Write-Host "TGSpaces release APK:"
Write-Host $targetApk
