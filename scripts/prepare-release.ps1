param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseTag,

    [string]$TelegramBasePath = "D:\Projects\TelegramClones\TelegramBase",

    [switch]$CleanClones,

    [switch]$SkipCloneBuild
)

$ErrorActionPreference = "Stop"

function Assert-FileExists {
    param(
        [string]$Path,
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description was not found: $Path"
    }
}

function Assert-DirectoryExists {
    param(
        [string]$Path,
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Description was not found: $Path"
    }
}

function Invoke-Checked {
    param(
        [scriptblock]$Command,
        [string]$Description
    )

    Write-Host ""
    Write-Host "==> $Description"
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

function Get-CloneApkPaths {
    param([string]$ReleaseApksPath)

    $paths = @()
    foreach ($slot in 1..10) {
        $slotText = "{0:D2}" -f $slot
        $apkPath = Join-Path $ReleaseApksPath "TGClone$slotText-release.apk"
        Assert-FileExists -Path $apkPath -Description "Clone release APK"
        $paths += $apkPath
    }
    return $paths
}

function Get-FileSha256 {
    param([string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Update-AppCatalogSha256 {
    param(
        [string]$AppCatalogPath,
        [string]$ReleaseTag,
        [string]$ApkPath
    )

    Assert-FileExists -Path $AppCatalogPath -Description "TGSpaces app catalog"
    $catalog = Get-Content -LiteralPath $AppCatalogPath -Raw | ConvertFrom-Json
    $catalog.releaseTag = $ReleaseTag
    $catalog.apkFileName = "TGSpaces-release.apk"
    $catalog.apkUrl = "https://github.com/DesperadoBoi/TGSpaces/releases/download/$ReleaseTag/TGSpaces-release.apk"
    $catalog | Add-Member -NotePropertyName "sha256" -NotePropertyValue (Get-FileSha256 -Path $ApkPath) -Force
    $catalog | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $AppCatalogPath -Encoding UTF8
}

$tgSpacesPath = Split-Path -Parent $PSScriptRoot
$telegramBasePathResolved = (Resolve-Path -LiteralPath $TelegramBasePath).Path
$releaseApksPath = Join-Path $telegramBasePathResolved "dist\release-apks"
$telegramBaseBuildScript = Join-Path $telegramBasePathResolved "scripts\build-clone-apks-release.ps1"
$updateCatalogScript = Join-Path $tgSpacesPath "scripts\update-clone-catalog.ps1"
$tgSpacesGradlew = Join-Path $tgSpacesPath "gradlew.bat"
$telegramBaseGradlew = Join-Path $telegramBasePathResolved "gradlew.bat"
$tgSpacesSigningProperties = Join-Path $tgSpacesPath "signing.properties"
$telegramBaseSigningProperties = Join-Path $telegramBasePathResolved "signing.properties"

Assert-FileExists -Path $tgSpacesSigningProperties -Description "TGSpaces signing.properties"
Assert-FileExists -Path $telegramBaseSigningProperties -Description "TelegramBase signing.properties"
Assert-FileExists -Path $tgSpacesGradlew -Description "TGSpaces Gradle wrapper"
Assert-FileExists -Path $telegramBaseGradlew -Description "TelegramBase Gradle wrapper"
Assert-FileExists -Path $telegramBaseBuildScript -Description "TelegramBase clone release build script"
Assert-FileExists -Path $updateCatalogScript -Description "TGSpaces catalog update script"

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "D:\AndroidStudio\jbr"
    Write-Host "JAVA_HOME was not set. Using $env:JAVA_HOME"
}

Assert-DirectoryExists -Path $env:JAVA_HOME -Description "JAVA_HOME directory"
$javaBin = Join-Path $env:JAVA_HOME "bin"
Assert-DirectoryExists -Path $javaBin -Description "JAVA_HOME\bin directory"
if (($env:Path -split ";") -notcontains $javaBin) {
    $env:Path = "$javaBin;$env:Path"
}

$previousLocation = Get-Location
try {
    if ($SkipCloneBuild) {
        Assert-DirectoryExists -Path $releaseApksPath -Description "TelegramBase release APK directory"
        [void](Get-CloneApkPaths -ReleaseApksPath $releaseApksPath)
        Write-Host "Skipping clone build. Using existing APK files in $releaseApksPath"
    } else {
        Set-Location -LiteralPath $telegramBasePathResolved
        $cloneBuildArgs = @("-ExecutionPolicy", "Bypass", "-File", $telegramBaseBuildScript)
        if ($CleanClones) {
            $cloneBuildArgs += "-Clean"
        }
        Invoke-Checked -Description "Build Telegram clone release APKs" -Command {
            & powershell.exe @cloneBuildArgs
        }
        [void](Get-CloneApkPaths -ReleaseApksPath $releaseApksPath)
    }

    Set-Location -LiteralPath $tgSpacesPath

    Invoke-Checked -Description "Update clone catalog" -Command {
        & powershell.exe -ExecutionPolicy Bypass -File $updateCatalogScript -ReleaseTag $ReleaseTag -TelegramBasePath $telegramBasePathResolved
    }

    Invoke-Checked -Description "Build TGSpaces release APK" -Command {
        & $tgSpacesGradlew ":app:assembleRelease"
    }

    $tgSpacesReleaseApk = Join-Path $tgSpacesPath "app\build\outputs\apk\release\app-release.apk"
    $catalogPath = Join-Path $tgSpacesPath "catalog\clones.json"
    $appCatalogPath = Join-Path $tgSpacesPath "catalog\app.json"
    Assert-FileExists -Path $tgSpacesReleaseApk -Description "TGSpaces release APK"
    Assert-FileExists -Path $catalogPath -Description "Clone catalog"
    Update-AppCatalogSha256 -AppCatalogPath $appCatalogPath -ReleaseTag $ReleaseTag -ApkPath $tgSpacesReleaseApk

    $releaseKitPath = Join-Path $tgSpacesPath "dist\github-release\$ReleaseTag"
    if (Test-Path -LiteralPath $releaseKitPath) {
        Remove-Item -LiteralPath $releaseKitPath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $releaseKitPath | Out-Null

    Copy-Item -LiteralPath $tgSpacesReleaseApk -Destination (Join-Path $releaseKitPath "TGSpaces-release.apk")
    $cloneApkPaths = Get-CloneApkPaths -ReleaseApksPath $releaseApksPath
    foreach ($cloneApkPath in $cloneApkPaths) {
        Copy-Item -LiteralPath $cloneApkPath -Destination $releaseKitPath
    }
    Copy-Item -LiteralPath $catalogPath -Destination (Join-Path $releaseKitPath "clones.json")
    Copy-Item -LiteralPath $appCatalogPath -Destination (Join-Path $releaseKitPath "app.json")

    $releaseFiles = Get-ChildItem -LiteralPath $releaseKitPath -File | Sort-Object Name
    $assetList = ($releaseFiles | ForEach-Object { "- $($_.Name)" }) -join [Environment]::NewLine
    $checklist = @"
# TGSpaces Release Checklist

Release tag: $ReleaseTag

## Files to upload to GitHub Release

$assetList

## Steps

1. Create a GitHub Release with tag `$ReleaseTag`.
2. Upload all APK files from this folder.
3. After the assets are uploaded, push `catalog/clones.json` and `catalog/app.json` if they changed.
4. Update the `TGSpaces-release.apk` asset.

## Warning

Push `catalog/clones.json` and `catalog/app.json` only after uploading APK assets to the GitHub Release. Otherwise already installed TGSpaces builds may see the new `releaseTag` before the assets exist and receive 404 errors.
"@
    Set-Content -LiteralPath (Join-Path $releaseKitPath "RELEASE_CHECKLIST.md") -Value $checklist -Encoding UTF8

    Write-Host ""
    Write-Host "Release kit: $releaseKitPath"
    Write-Host "Files:"
    Get-ChildItem -LiteralPath $releaseKitPath -File | Sort-Object Name | ForEach-Object {
        Write-Host " - $($_.Name)"
    }
    Write-Host ""
    Write-Host "Reminder: upload GitHub Release assets first, then push catalog/clones.json."
} finally {
    Set-Location -LiteralPath $previousLocation
}
