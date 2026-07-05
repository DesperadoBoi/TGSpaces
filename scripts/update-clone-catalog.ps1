param(
    [string]$ReleaseTag = "v0.2-release",
    [string]$TelegramBasePath = "D:\Projects\TelegramClones\TelegramBase"
)

$ErrorActionPreference = "Stop"

function Get-LatestAapt {
    $buildToolsPath = "D:\Android\Sdk\build-tools"
    if (-not (Test-Path -LiteralPath $buildToolsPath)) {
        throw "Android build-tools directory was not found: $buildToolsPath"
    }

    $buildToolsVersions = Get-ChildItem -LiteralPath $buildToolsPath -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "aapt.exe") } |
        Sort-Object -Property @{
            Expression = {
                try {
                    [version]$_.Name
                } catch {
                    [version]"0.0.0"
                }
            }
        } -Descending

    if (-not $buildToolsVersions) {
        throw "aapt.exe was not found under $buildToolsPath. Install Android SDK Build-Tools first."
    }

    return Join-Path $buildToolsVersions[0].FullName "aapt.exe"
}

function Read-ApkBadging {
    param(
        [string]$AaptPath,
        [string]$ApkPath
    )

    $badging = & $AaptPath dump badging $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "aapt dump badging failed for $ApkPath. Output: $badging"
    }

    $packageLine = $badging | Where-Object { $_ -like "package:*" } | Select-Object -First 1
    if (-not $packageLine) {
        throw "Could not find package badging line in $ApkPath"
    }

    $match = [regex]::Match($packageLine, "name='(?<name>[^']+)'\s+versionCode='(?<versionCode>[^']+)'\s+versionName='(?<versionName>[^']*)'")
    if (-not $match.Success) {
        throw "Could not parse package name/versionCode/versionName from $ApkPath. Package line: $packageLine"
    }

    return [ordered]@{
        PackageName = $match.Groups["name"].Value
        VersionCode = [long]$match.Groups["versionCode"].Value
        VersionName = $match.Groups["versionName"].Value
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$catalogPath = Join-Path $repoRoot "catalog\clones.json"
$releaseApksPath = Join-Path $TelegramBasePath "dist\release-apks"

if (-not (Test-Path -LiteralPath $releaseApksPath)) {
    throw "Release APK directory was not found: $releaseApksPath"
}

$aaptPath = Get-LatestAapt
$clones = @()

foreach ($slot in 1..10) {
    $slotText = "{0:D2}" -f $slot
    $apkFileName = "TGClone$slotText-release.apk"
    $apkPath = Join-Path $releaseApksPath $apkFileName

    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "Required clone APK was not found: $apkPath"
    }

    $badging = Read-ApkBadging -AaptPath $aaptPath -ApkPath $apkPath
    $expectedPackageName = "com.desperadoboi.tgclone$slotText"
    if ($badging.PackageName -ne $expectedPackageName) {
        throw "Unexpected package name in $apkFileName. Expected $expectedPackageName, got $($badging.PackageName)."
    }

    $clones += [ordered]@{
        slot = $slot
        name = "TGClone $slotText"
        packageName = $badging.PackageName
        apkFileName = $apkFileName
        apkUrl = "https://github.com/DesperadoBoi/TGSpaces/releases/download/$ReleaseTag/$apkFileName"
        versionName = $badging.VersionName
        versionCode = $badging.VersionCode
    }
}

$catalog = [ordered]@{
    catalogVersion = 1
    releaseTag = $ReleaseTag
    clones = $clones
}

$catalogDirectory = Split-Path -Parent $catalogPath
if (-not (Test-Path -LiteralPath $catalogDirectory)) {
    New-Item -ItemType Directory -Path $catalogDirectory | Out-Null
}

$json = $catalog | ConvertTo-Json -Depth 5
Set-Content -LiteralPath $catalogPath -Value $json -Encoding UTF8

Write-Host "Updated $catalogPath"
Write-Host "Release tag: $ReleaseTag"
Write-Host "aapt: $aaptPath"
