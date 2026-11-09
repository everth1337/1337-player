# Сборка

Нужны JDK 21, Android SDK Platform `android-37.0`, Build-Tools `36.0.0`, Python 3 и Node.js для отдельных проверок/упаковки. Gradle Wrapper включён в репозиторий. Точные зависимости находятся в `base/gradle/libs.versions.toml`.

Укажи Android SDK в `base/local.properties` (`sdk.dir=...`) или настрой `ANDROID_HOME`. Этот файл не публикуется.

## Debug

Из `base/`:

```sh
./gradlew :app:assembleDebug -Pabis=arm64-v8a
```

Windows: `./build-nexi.ps1 -Configuration Debug` из корня. Debug имеет пакет `app.nexi.player.dev` и устанавливается отдельно от релиза.

## Подписанный релиз

Задай переменные окружения со своим ключом:

- `MYSPOTI_KEYSTORE` — абсолютный путь к `.p12`/`.jks`;
- `MYSPOTI_KEYSTORE_PASSWORD`;
- `MYSPOTI_KEY_ALIAS`;
- `MYSPOTI_KEY_PASSWORD`.

Названия переменных унаследованы от системы сборки Myspoti. Значений ключа проекта в исходниках нет.

```sh
cd base
./gradlew :app:assembleRelease -Pabis=arm64-v8a
```

APK: `base/app/build/outputs/apk/release/app-release.apk`. Версия, код и канал обновлений заданы в `base/gradle.properties`. Локальная сборка с другим ключом не обновит установленный официальный APK. Для независимого форка измени `updateRepo` на свой репозиторий либо передай `-PupdateRepo=` для отключения проверки.

## Проверки

```sh
cd base
./gradlew :app:testDebugUnitTest --tests '*UpdateCheckerTest' --tests '*OfflineDownloaderTest' -Pabis=arm64-v8a
cd ..
node nexi-youtube/scripts/nexi-rate-limit-test.cjs
```

DownloadServiceTest предназначен только для эмулятора: он временно отключает сеть и использует вымышленные запросы. Не запускай весь набор инструментальных тестов на личном аккаунте.

На Windows с JDK 25 при ошибке создания loopback socket можно задать `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\player-sockets`, предварительно создав этот короткий каталог. В CI используется JDK 21.
