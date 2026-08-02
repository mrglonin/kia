# KIA CANBUS

Подключаем Android-магнитолу TEYES/CC4 к штатной панели Kia через переделанный USB CAN-адаптер: TPMS, медиа, звонки, навигация, RCTA, CANBUS и обновления APK без отдельного закрытого сервера.

[![Скачать KIA 23.08 APK](https://img.shields.io/badge/%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C-KIA%2023.08%20APK-17a673?style=for-the-badge&logo=android&logoColor=white)](https://github.com/mrglonin/kia/releases/download/v23.08-362/kia_362.apk)

- APK KIA: [kia_362.apk](https://github.com/mrglonin/kia/releases/download/v23.08-362/kia_362.apk)
- APK Yandex Navigator mod: [yandex_navi-7_10-universal-kia-mod-heartbeat.apk](https://github.com/mrglonin/kia/releases/download/v23.08-362/yandex_navi-7_10-universal-kia-mod-heartbeat.apk)
- Манифест обновлений: [updates/latest.json](updates/latest.json)
- GitHub Release: [`v23.08-362`](https://github.com/mrglonin/kia/releases/tag/v23.08-362)
- Текущий публичный релиз: `23.08` / `362`

## Релиз

| Компонент | Версия | Файл | SHA-256 |
| --- | --- | --- | --- |
| KIA app | `23.08` / `362` | `updates/kia_362.apk`, 6 022 183 байт | `90041771384011d91ca19ee63a372763b0e3cf5a2768dde1ae914cdefd19e4d5` |
| Yandex Navigator Kia mod | `7.10-kia.20260723-heartbeat` / `71011062` | [release asset `yandex_navi-7_10-universal-kia-mod-heartbeat.apk`](https://github.com/mrglonin/kia/releases/download/v23.08-362/yandex_navi-7_10-universal-kia-mod-heartbeat.apk), 115 302 487 байт | `21fb15289695e7913b8e3e62902212ad0c06dd93f6f16c642c027a41b484ba37` |

В `23.08` компас возвращает последнее достоверное направление сразу после стартовой паузы и без начала движения. GPS-course ниже `1,4 м/с` не может заменить его ложным севером, а запуск, завершение маршрута и новая USB-сессия принудительно переотправляют тот же compass TX.

Дорожное ограничение теперь не зависит ни от скорости автомобиля, ни от наличия маршрута: свежий лимит остаётся после `NAV OFF`, финиша и reconnect. Текущая скорость — отдельный знак: он скрыт при `0`, но не скрывает лимит. Подтверждённый ноль, истёкшая свежесть, выключение навигации или смена источника по-прежнему очищают его.

Yandex mod теперь отправляет свежий Core Bridge snapshot раз в секунду, в том числе без маршрута и после ухода в фон. Это даёт KIA реально обновлять лимит, а не просто долго держать старое значение. Для полного исправления нужно обновить и KIA, и Yandex mod. Медиа-профиль `TEYES / CC4` не изменялся.

Одинаковый heartbeat не перезапускает фоновый сервис и не перезаписывает UI/настройки: он только освежает freshness и проверяет USB-сессию. Изменения скорости/лимита применяются сразу, а свежий лимит повторяется после нового `connectionEpoch`.

Проверено: `275` unit-тестов, `0 failures/errors/skips`; `lintRelease` — `0 errors` / `72 warnings`; оба APK прошли ZIP/zipalign/signature. На Android 15 проверены обновления KIA `23.07 → 23.08` и прежнего Yandex mod на heartbeat-сборку с той же подписью; heartbeat и его приём KIA подтверждены в foreground и background.

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
adb install -r updates/kia_362.apk
curl -L -o /tmp/yandex_navi-7_10-universal-kia-mod-heartbeat.apk \
  https://github.com/mrglonin/kia/releases/download/v23.08-362/yandex_navi-7_10-universal-kia-mod-heartbeat.apk
adb install -r /tmp/yandex_navi-7_10-universal-kia-mod-heartbeat.apk
```

На магнитоле:

1. Установить `kia_362.apk`.
2. Выдать runtime permissions: уведомления, геолокация и Bluetooth — только для используемых функций.
3. Включить специальные права: поверх окон, доступ к уведомлениям и игнор оптимизации батареи.
4. Подключить USB CAN-адаптер, переделанный и прошитый по инструкции автора: [Drive2 76508](https://www.drive2.ru/users/76508/), [переделка адаптера](https://www.drive2.ru/l/717368666034802531/).
5. Установить или обновить Yandex Navigator mod до `yandex_navi-7_10-universal-kia-mod-heartbeat.apk`. Package/versionCode остаются `ru.yandex.yandexnavi` / `71011062`; KIA распознаёт новую сборку по SHA-256 и размеру.

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
$ADB -s emulator-5554 install -r updates/kia_362.apk
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
