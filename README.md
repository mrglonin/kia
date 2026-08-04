# KIA CANBUS

Подключаем Android-магнитолу TEYES/CC4 к штатной панели Kia через переделанный USB CAN-адаптер: TPMS, медиа, звонки, навигация, RCTA, CANBUS и обновления APK без отдельного закрытого сервера.

[![Скачать KIA 23.09 APK](https://img.shields.io/badge/%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C-KIA%2023.09%20APK-17a673?style=for-the-badge&logo=android&logoColor=white)](https://github.com/mrglonin/kia/releases/download/v23.09-363/kia_363.apk)

- APK KIA: [kia_363.apk](https://github.com/mrglonin/kia/releases/download/v23.09-363/kia_363.apk)
- APK Yandex Navigator mod: [yandex_navi-7_10-universal-kia-mod-background.apk](https://github.com/mrglonin/kia/releases/download/v23.09-363/yandex_navi-7_10-universal-kia-mod-background.apk)
- Манифест обновлений: [updates/latest.json](updates/latest.json)
- GitHub Release: [`v23.09-363`](https://github.com/mrglonin/kia/releases/tag/v23.09-363)
- Текущий публичный релиз: `23.09` / `363`

## Релиз

| Компонент | Версия | Файл | SHA-256 |
| --- | --- | --- | --- |
| KIA app | `23.09` / `363` | `updates/kia_363.apk`, 6 062 771 байт | `8aed01c8bfbc0cb71a107d7d5789ce75cc7fb3ee6e092133c7ba88093999e75f` |
| Yandex Navigator Kia mod | `7.10-kia.20260804-background` / `71011062` | [release asset `yandex_navi-7_10-universal-kia-mod-background.apk`](https://github.com/mrglonin/kia/releases/download/v23.09-363/yandex_navi-7_10-universal-kia-mod-background.apk), 115 302 487 байт | `712f153aae0d1431e3a59b59b97b44ca43ca6120d7907a65d5ab37e1316588cf` |

В `23.09` устранены зависания компаса, текущей скорости и дорожного ограничения. Компас контролирует не только наличие датчика, но и поступление реальных samples, переключается на геомагнитный fallback и повторяет актуальный TX после новой USB-сессии. Текущая скорость использует свежий Yandex/GPS sample, а при истечении TTL очищается; дорожный лимит обновляется и остаётся видимым независимо от остановки и наличия маршрута.

USB-путь получил защиту от зависшего reader thread, старого поколения callbacks, нарушения порядка записи и reconnect-цикла без паузы. Очередь сохраняет только актуальные latest-only кадры, а health-monitor различает новую сессию и действительно остановившийся обмен. Запуск foreground service на Android 16 больше не удерживает wake-lock, если ОС отклонила сам запуск.

Yandex mod передаёт отдельные monotonic timestamps источников и восстанавливает подписку Core Bridge после паузы/фона. Hotfix от 2026-08-04 дополнительно выбирает реальный `fused`/`network` provider на устройствах без framework `gps` и удерживает MapKit/Core через location foreground service после выхода на Home. Фоновая работа видна в уведомлении `KIA: фоновая навигация`; смахивание Yandex из списка недавних приложений останавливает сервис и location-регистрации. KIA не принимает переставший обновляться speed/limit как свежий heartbeat. Для полного исправления фоновой свежести нужно обновить и KIA, и Yandex mod. Медиа-профиль `TEYES / CC4` не изменялся.

OTA теперь проверяется фоновым сервисом, а не только открытым Activity: успешная проверка повторяется раз в час, сетевой retry запускается каждые 15 минут. Реальное системное уведомление объединяет KIA/Yandex, восстанавливается после перезагрузки магнитолы, открывает раздел обновлений и не показывает модальное окно при движении либо неизвестной/устаревшей скорости. Успех одного источника больше не подавляет повтор другого, stale `latest.json` сравнивается с GitHub Releases, а SHA/размер обязательны до предложения установки.

Проверено: `337` unit-тестов, `0 failures/errors/skips`; `lintDebug` и `lintRelease` — по `0 errors` / `72 warnings`; clean release build, ZIP, zipalign и APK Signature Scheme v2. На физическом Android 16 проверены восстановление после остановки sensor samples, 90 секунд Doze, Yandex в фоне более 100 секунд, expiry скорости и безопасный отказ запуска foreground service. Физический USB CAN-адаптер в этот прогон не подключался.

## Что работает

- Поддерживается только адаптер после переделки и прошивки от автора Drive2: [профиль автора](https://www.drive2.ru/users/76508/), [информация о переделке адаптера](https://www.drive2.ru/l/717368666034802531/).
- TPMS-экран под Kia: давление, температура, предупреждения, ускоренный опрос при аварийных значениях.
- Медиа и звонки: TEYES/CC4, Android-плееры, BT/USB/FM/AM, подписи источника и текста на приборку.
- Навигация: Yandex Core Bridge, 2GIS fallback, режимы `обычный`, `TBT`, `стрелка к финишу`, номерные съезды с кругового, угловые манёвры, lane/gray-road/micro-maneuver логика и опциональный дальний знак «прямо».
- RCTA: предупреждение слева/справа/с двух сторон, overlay поверх камеры, звук, настройка стиля.
- CAN/USB: обмен с переделанным адаптером, TPMS poll, температура панели и health-статус адаптера.
- Обновления: KIA APK и Yandex mod APK через `updates/latest.json`.

## Скриншоты

| TPMS | TPMS warning |
| --- | --- |
| ![TPMS](docs/screenshots/dashboard-tpms.png) | ![TPMS warning](docs/screenshots/dashboard-tpms-warning.png) |

| Widget mode | Sunroof |
| --- | --- |
| ![Navigation widget](docs/screenshots/dashboard-navigation-widget.png) | ![Sunroof open](docs/screenshots/dashboard-sunroof-open.png) |

| RCTA overlay | RCTA settings |
| --- | --- |
| ![RCTA overlay](docs/screenshots/overlay-rcta-both.png) | ![RCTA settings](docs/screenshots/settings-rcta.png) |

| Navigation | Media |
| --- | --- |
| ![Navigation settings](docs/screenshots/settings-navigation.png) | ![Media settings](docs/screenshots/settings-media.png) |

| TPMS settings | CANBUS |
| --- | --- |
| ![TPMS settings](docs/screenshots/settings-tpms.png) | ![CANBUS settings](docs/screenshots/settings-canbus.png) |

| General |
| --- |
| ![General settings](docs/screenshots/settings-general.png) |

## Установка

ADB:

```bash
adb install -r updates/kia_363.apk
curl -L -o /tmp/yandex_navi-7_10-universal-kia-mod-background.apk \
  https://github.com/mrglonin/kia/releases/download/v23.09-363/yandex_navi-7_10-universal-kia-mod-background.apk
adb install -r /tmp/yandex_navi-7_10-universal-kia-mod-background.apk
```

На магнитоле:

1. Установить `kia_363.apk`.
2. Выдать runtime permissions: уведомления, геолокация и Bluetooth — только для используемых функций.
3. Включить специальные права: поверх окон, доступ к уведомлениям и игнор оптимизации батареи.
4. Подключить USB CAN-адаптер, переделанный и прошитый по инструкции автора: [Drive2 76508](https://www.drive2.ru/users/76508/), [переделка адаптера](https://www.drive2.ru/l/717368666034802531/).
5. Установить или обновить Yandex Navigator mod до `yandex_navi-7_10-universal-kia-mod-background.apk`. Package/versionCode остаются `ru.yandex.yandexnavi` / `71011062`; KIA распознаёт новую сборку по SHA-256 и размеру.

С уже установленного Kia mod обновление ставится поверх и сохраняет данные. Официальный либо иначе подписанный Yandex Android не разрешит заменить этим APK: сначала сохраните нужные данные, удалите прежнее приложение и затем установите mod.

## Сборка

Требования: Android SDK, JDK 17 из Android Studio, Gradle wrapper из `app/`.

```bash
cd app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/legion/Library/Android/sdk" \
./gradlew :app:assembleRelease --console=plain
```

Release-сборка подписывается публичным debug-keystore из `signing/kia-debug-release.keystore`, чтобы публичный APK можно было пересобрать с той же подписью. Для своего форка меняйте keystore в `app/app/build.gradle`.

## QA

Быстрые сценарии через встроенный `QaReceiver`:

```bash
ADB=/Users/legion/Library/Android/sdk/platform-tools/adb
$ADB -s emulator-5554 install -r updates/kia_363.apk
$ADB -s emulator-5554 shell am start -n kia.app/.entry.MainActivity
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario tpms_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario tpms_high_pressure_warning
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_stale_lane_conflict_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_micro_main_counter_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_main_preview_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_roundabout_exit_continuity_sample --ei exit 3 --ez tbt false
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_speed_limit_clear_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_speed_limit_idle_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario rcta_both
```

Проверка установленной версии:

```bash
adb shell dumpsys package kia.app | grep -E 'versionCode|versionName|lastUpdateTime'
```

Подробный журнал решений и фактических TX в приборку:

```bash
adb pull /sdcard/Android/data/kia.app/files/navigation-logs/
```

## Структура

- `app/` - Android Gradle project для `kia.app`.
- `updates/` - публичные APK и `latest.json`.
- `updates/yandex/` - прежний совместимый ARM7-артефакт; актуальный universal Yandex mod публикуется в assets текущего GitHub Release.
- `signing/` - публичный debug release keystore.
- `tools/` - CAN/UART/navigation/APK utilities.
- `docs/screenshots/` - скриншоты текущего публичного релиза.

## Границы

- APK Yandex Navigator mod лежит как готовый installable artifact; права на Yandex/брендовые компоненты не переоформляются этим репозиторием.
- Рабочий USB CAN-адаптер не является обычным заводским адаптером: нужна переделка и прошивка от [автора Drive2](https://www.drive2.ru/users/76508/), описание здесь: [drive2.ru/l/717368666034802531](https://www.drive2.ru/l/717368666034802531/).
- Публичный debug-keystore нужен для воспроизводимости обновлений, не используйте его как приватный production key.
- `updates/latest.json` и ссылки в этом README указывают на текущий публичный `main`.

## Лицензия

Исходный код KIA-приложения и локальные tools открыты по MIT License. Сторонние APK, фирменные изображения, логотипы и материалы сохраняют права владельцев.
