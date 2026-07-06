# Инструкция релиза

## Локальные директории

Локальные директории разработчика задаются самостоятельно:

```powershell
$TGSpacesDir = "<TGSPACES_DIR>"
$TelegramBaseDir = "<TELEGRAM_BASE_DIR>"
$SigningDir = "<SIGNING_DIR>"
$ReleaseKeystorePath = "<RELEASE_KEYSTORE_PATH>"
```

## Подготовка release-комплекта

Полная подготовка релиза:

```powershell
.\scripts\prepare-release.ps1 -ReleaseTag v0.x-release
```

Подготовка релиза без пересборки клонов:

```powershell
.\scripts\prepare-release.ps1 -ReleaseTag v0.x-release -SkipCloneBuild
```

## Порядок релиза

1. Собрать release-комплект.
2. Создать GitHub Release с нужным tag.
3. Загрузить все APK.
4. Только после загрузки APK запушить `catalog/*.json`, если `releaseTag`, `version` или `sha256` изменились.
5. Скачать `TGSpaces-release.apk` и проверить установку одного клона.

## Важно

Release signing key нельзя терять и нельзя коммитить. В публичной документации и репозитории используйте только нейтральное обозначение `<RELEASE_KEYSTORE_PATH>`.
