# KIA CANBUS

Подключаем Android-магнитолу TEYES/CC4 к штатной панели Kia через переделанный USB CAN-адаптер: TPMS, медиа, звонки, навигация, RCTA, CANBUS и обновления APK без отдельного закрытого сервера.

[![Скачать KIA 23.10 APK](https://img.shields.io/badge/%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C-KIA%2023.10%20APK-17a673?style=for-the-badge&logo=android&logoColor=white)](https://github.com/mrglonin/kia/releases/download/v23.10-364/kia_364.apk)

- APK KIA: [kia_364.apk](https://github.com/mrglonin/kia/releases/download/v23.10-364/kia_364.apk)
- APK Yandex Navigator mod: [yandex_navi-7_10-universal-kia-mod-teyes-compat.apk](https://github.com/mrglonin/kia/releases/download/v23.10-364/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk)
- Манифест обновлений: [updates/latest.json](updates/latest.json)
- GitHub Release: [`v23.10-364`](https://github.com/mrglonin/kia/releases/tag/v23.10-364)
- Текущий публичный релиз: `23.10` / `364`

## Релиз

| Компонент | Версия | Файл | SHA-256 |
| --- | --- | --- | --- |
| KIA app | `23.10` / `364` | `updates/kia_364.apk`, 6 062 771 байт | `6e67dcf20e0bbc121f26208bc8a131e1d5303a8a463e1d320afed90bb8129165` |
| Yandex Navigator Kia mod | `7.10-kia.20260805-teyes-compat` / `71011062` | [release asset `yandex_navi-7_10-universal-kia-mod-teyes-compat.apk`](https://github.com/mrglonin/kia/releases/download/v23.10-364/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk), 115 302 487 байт | `4933982fff578e64fe7b2d31a67dd0346afa167a27004fcc70e9c0f7646c72b8` |

В `23.10` KIA и совместимый Yandex mod опубликованы одним общим обновлением. На Android 14 и ниже Yandex сохраняет штатный жизненный цикл MapKit/Core и штатный маршрутный foreground service, поэтому мод больше не вмешивается в переходы запуска и построения маршрута на TEYES CC4 Pro.

USB-путь получил защиту от зависшего reader thread, старого поколения callbacks, нарушения порядка записи и reconnect-цикла без паузы. Очередь сохраняет только актуальные latest-only кадры, а health-monitor различает новую сессию и действительно остановившийся обмен. Запуск foreground service на Android 16 больше не удерживает wake-lock, если ОС отклонила сам запуск.

На Android 15+ без framework `gps` расширенный фоновый режим включается только при наличии реального `fused` либо `network`. KIA по-прежнему не принимает переставший обновляться speed/limit как свежий heartbeat и безопасно очищает устаревшее ограничение.

OTA теперь проверяется фоновым сервисом, а не только открытым Activity: успешная проверка повторяется раз в час, сетевой retry запускается каждые 15 минут. Реальное системное уведомление объединяет KIA/Yandex, восстанавливается после перезагрузки магнитолы, открывает раздел обновлений и не показывает модальное окно при движении либо неизвестной/устаревшей скорости. Успех одного источника больше не подавляет повтор другого, stale `latest.json` сравнивается с GitHub Releases, а SHA/размер обязательны до предложения установки.

Проверено: `337` unit-тестов, `0 failures/errors/skips`; `lintDebug` и `lintRelease` — по `0 errors` / `72 warnings`; KIA release build, ZIP, zipalign и подпись. На физическом Android 16 проверены запуск, построение/старт/отмена маршрута и фон; отдельно принудительно проверена штатная ветка старого Android. Реальная TEYES CC4 Pro в момент публикации не была подключена и требует контрольного аппаратного прогона.

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
adb install -r updates/kia_364.apk
curl -L -o /tmp/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk \
  https://github.com/mrglonin/kia/releases/download/v23.10-364/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk
adb install -r /tmp/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk
```

На магнитоле:

1. Установить `kia_364.apk`.
2. Выдать runtime permissions: уведомления, геолокация и Bluetooth — только для используемых функций.
3. Включить специальные права: поверх окон, доступ к уведомлениям и игнор оптимизации батареи.
4. Подключить USB CAN-адаптер, переделанный и прошитый по инструкции автора: [Drive2 76508](https://www.drive2.ru/users/76508/), [переделка адаптера](https://www.drive2.ru/l/717368666034802531/).
5. Установить или обновить Yandex Navigator mod до `yandex_navi-7_10-universal-kia-mod-teyes-compat.apk`. Package/versionCode остаются `ru.yandex.yandexnavi` / `71011062`; KIA распознаёт новую сборку по SHA-256 и размеру.

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
$ADB -s emulator-5554 install -r updates/kia_364.apk
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
