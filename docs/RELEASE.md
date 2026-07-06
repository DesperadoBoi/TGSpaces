# Инструкция релиза

## Проекты

Основной проект TGSpaces:

```text
D:\Projects\TGSpaces
```

Базовый проект Telegram-клонов:

```text
D:\Projects\TelegramClones\TelegramBase
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

Signing key `D:\Projects\signing\tgspaces-release.jks` нельзя терять и нельзя коммитить.
