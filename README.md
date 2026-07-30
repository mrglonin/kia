# KIA CANBUS

Подключаем Android-магнитолу TEYES/CC4 к штатной панели Kia через переделанный USB CAN-адаптер: TPMS, медиа, звонки, навигация, RCTA, CANBUS и обновления APK без отдельного закрытого сервера.

[![Скачать KIA 23.04 APK](https://img.shields.io/badge/%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C-KIA%2023.04%20APK-17a673?style=for-the-badge&logo=android&logoColor=white)](https://github.com/mrglonin/kia/releases/download/v23.04-358/kia_358.apk)

- APK KIA: [kia_358.apk](https://github.com/mrglonin/kia/releases/download/v23.04-358/kia_358.apk)
- APK Yandex Navigator mod: [yandex_navi-7_10-universal-kia-mod-bgfix.apk](https://github.com/mrglonin/kia/releases/download/v23.04-358/yandex_navi-7_10-universal-kia-mod-bgfix.apk)
- ABI-варианты Yandex mod: [ARM64](https://github.com/mrglonin/kia/releases/download/v23.04-358/yandex_navi-7_10-arm64-kia-mod-bgfix.apk), [ARM7](https://github.com/mrglonin/kia/releases/download/v23.04-358/yandex_navi-7_10-arm7-kia-mod-bgfix.apk)
- Манифест обновлений: [updates/latest.json](updates/latest.json)
- GitHub Release: [`v23.04-358`](https://github.com/mrglonin/kia/releases/tag/v23.04-358)
- Текущий публичный релиз: `23.04` / `358`

## Релиз

| Компонент | Версия | Файл | SHA-256 |
| --- | --- | --- | --- |
| KIA app | `23.04` / `358` | `updates/kia_358.apk`, 5 915 023 байта | `ecc651bf018166774665534a26e6e448d901cee3f30f0f127ed2043fbe7a4dc6` |
| Yandex Navigator Kia mod | `7.10-kia.20260723` / `71011062` | [release asset `yandex_navi-7_10-universal-kia-mod-bgfix.apk`](https://github.com/mrglonin/kia/releases/download/v23.04-358/yandex_navi-7_10-universal-kia-mod-bgfix.apk), 115 101 951 байт | `ff34e9f25eeee2ee5a7c95b4b1585586e5c8c5b68f2dce18d10790e8a5d043f1` |

В `23.04` номерные съезды с кругового 1/2/3/4 сохраняются во всей цепочке main/micro/ETA и передаются правильной жёлтой веткой. Для Yandex в обычном режиме добавлен отключаемый показ «прямо» до приближения к основному манёвру с порогом `100/200/300/400/500 м`; micro-подсказки работают независимо и имеют собственный диапазон `100/150/200/250/300/400/500 м`. Также уточнены угловые манёвры, удержание маршрута при переходе Yandex в фон, watchdog и очистка устаревшего ограничения скорости по TTL либо подтверждённому нулю.

В релиз входит один универсальный Yandex Navigator Kia mod с исправлением фонового режима. Версия пакета остаётся `7.10-kia.20260723` / `71011062`. Медиа-профиль TEYES/CC4 и его TX-логика этим обновлением не изменялись.

Проверено: `146` unit-тестов, `0 failures`; `lintRelease` — `0 errors` / `102 warnings`.

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
adb install -r updates/kia_358.apk
curl -L -o /tmp/yandex_navi-7_10-universal-kia-mod-bgfix.apk \
  https://github.com/mrglonin/kia/releases/download/v23.04-358/yandex_navi-7_10-universal-kia-mod-bgfix.apk
adb install -r /tmp/yandex_navi-7_10-universal-kia-mod-bgfix.apk
```

На магнитоле:

1. Установить `kia_358.apk`.
2. Выдать runtime permissions: уведомления, геолокация, Bluetooth, audio/media.
3. Включить специальные права: поверх окон, изменение системных настроек, доступ к уведомлениям, игнор оптимизации батареи.
4. Подключить USB CAN-адаптер, переделанный и прошитый по инструкции автора: [Drive2 76508](https://www.drive2.ru/users/76508/), [переделка адаптера](https://www.drive2.ru/l/717368666034802531/).
5. Установить или обновить Yandex Navigator mod до `yandex_navi-7_10-universal-kia-mod-bgfix.apk`, чтобы получить исправление фонового режима. Его package/version остаются `ru.yandex.yandexnavi` / `7.10-kia.20260723`.

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
$ADB -s emulator-5554 install -r updates/kia_358.apk
$ADB -s emulator-5554 shell am start -n kia.app/.entry.MainActivity
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario tpms_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario tpms_high_pressure_warning
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_stale_lane_conflict_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_micro_main_counter_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_main_preview_sample
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_roundabout_exit_continuity_sample --ei exit 3 --ez tbt false
$ADB -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario nav_speed_limit_clear_sample
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
- `updates/yandex/` - installable Yandex Navigator Kia mod APK.
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
