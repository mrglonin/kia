# KIA CANBUS

Подключаем Android-магнитолу TEYES/CC4 к штатной панели Kia через переделанный USB CAN-адаптер: TPMS, медиа, звонки, навигация, RCTA, CANBUS и обновления APK без отдельного закрытого сервера.

[![Скачать KIA 23.13 APK](https://img.shields.io/badge/%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C-KIA%2023.13%20APK-17a673?style=for-the-badge&logo=android&logoColor=white)](https://github.com/mrglonin/kia/releases/download/v23.13-367/kia_367.apk)

- APK KIA: [kia_367.apk](https://github.com/mrglonin/kia/releases/download/v23.13-367/kia_367.apk)
- APK Yandex Navigator mod: [yandex_navi-7_10-universal-kia-mod-teyes-compat.apk](https://github.com/mrglonin/kia/releases/download/v23.10-364/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk)
- Манифест обновлений: [updates/latest.json](updates/latest.json)
- GitHub Release: [`v23.13-367`](https://github.com/mrglonin/kia/releases/tag/v23.13-367)
- Текущий публичный релиз: `23.13` / `367`

## Релиз

| Компонент | Версия | Файл | SHA-256 |
| --- | --- | --- | --- |
| KIA app | `23.13` / `367` | `updates/kia_367.apk`, 6 716 043 байт | `faf775d9cbc42880db74eecb30fa5ae80176312ef7a872b0d962c30a617ad617` |
| Yandex Navigator Kia mod | `7.10-kia.20260805-teyes-compat` / `71011062` | [release asset `yandex_navi-7_10-universal-kia-mod-teyes-compat.apk`](https://github.com/mrglonin/kia/releases/download/v23.10-364/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk), 115 302 487 байт | `4933982fff578e64fe7b2d31a67dd0346afa167a27004fcc70e9c0f7646c72b8` |

В `23.13 / 367` добавлена собственная карточка **KIA · ШИНЫ** поверх главного экрана TEYES Home: четыре давления в Bar и четыре температуры в °C. Она работает без обновления системных приложений TEYES и не заполняет внутренние поля штатной 3D-сцены. Карточку можно перетащить; положение сохраняется.

При обновлении с `366` уже включённая функция начинает использовать карточку автоматически. Переключатель теперь называется **Настройки → TPMS → Показывать давление и температуру**. Существующее разрешение «поверх других приложений» используется повторно; новые права Accessibility или Usage Access не требуются. При первой установке функция по-прежнему выключена.

Карточка появляется при видимом TEYES Home, скрывается при камере AVM, открытой KIA, активном RCTA и погашенном/заблокированном экране. Используются штатные сигналы Home и AVM; потеря подтверждения видимости скрывает окно. В многоконном режиме и с полупрозрачными экранами положение карточки требует отдельной проверки на ГУ.

Источник показаний — отдельный кэш USB-данных KIA. Передаётся числовая температура, если её байт допустим для формата пакета; отсутствующая/недопустимая температура отображается прочерком независимо от давления. Настоящие `0 °C` сохраняются. Неизвестные показания, данные старше `180 с` и показания после отключения USB очищаются. Новое USB-подключение требует новых кадров; QA-образцы и данные штатного TPMS не становятся источником карточки.

Отдельные Android-виджеты `com.teyes.tpms.widget` также получают давление и температуру. Их старое ограничение остаётся: после принудительной остановки KIA последнее значение может сохраниться в чужом виджете. Собственная карточка KIA исчезает вместе с процессом.

В `23.11` в **Настройки → RCTA → Тип анимации** добавлен третий стиль **TEYES**: оригинальная красная пульсация по краю экрана со стороны опасности. Типы 1 и 2 и их параметры сохранены; новый стиль включается вручную. RCTA передаёт только наличие опасности слева/справа, поэтому используется красное предупреждение без выдуманного уровня близости.

В блоке **Тип звукового предупреждения** можно выбрать **Сигнал** или **Голос** для любого стиля. В стиле TEYES сигнал направлен в левый/правый стереоканал; голос использует оригинальные русские подсказки TEYES. При опасности с обеих сторон фразы чередуются с паузой и не перекрывают друг друга. Реальное голосовое предупреждение имеет приоритет над демонстрацией. Отключение звука, снятие события и смена режима отменяют воспроизведение и отложенный повтор. Выбор сохраняется и входит в экспорт/импорт настроек; по умолчанию остаётся сигнал.

Проверено для `23.13`: `380` unit-тестов без failures/errors/skips; `lintRelease` — `0 errors / 79 warnings`; release-сборка, ZIP CRC, zipalign и прежняя подпись APK. JVM-стенд прошёл `42/42` сценария USB-кэша, передачи и жизненного цикла. На Android 15 подписанный KIA `367` прошёл `30/30` instrumentation-проверок: обновление с `366` с сохранением настройки, отображение четырёх давлений и температур, перетаскивание, скрытие/возврат, камера, RCTA, неизвестная температура, отключение и новое подключение USB, срок действия видимости. USB-транспорт и сигналы Home на этом стенде моделировались отдельным тестовым APK; он не входит в релиз. На физической TEYES эта версия ещё не проверена.

Yandex Navigator mod остаётся версии `7.10-kia.20260805-teyes-compat / 71011062` из `v23.10-364`; его APK и блок манифеста не изменены.

## Что работает

- Поддерживается только адаптер после переделки и прошивки от автора Drive2: [профиль автора](https://www.drive2.ru/users/76508/), [информация о переделке адаптера](https://www.drive2.ru/l/717368666034802531/).
- TPMS-экран под Kia: давление, температура, предупреждения, ускоренный опрос при аварийных значениях. Опционально — карточка давления и температуры на TEYES Home и передача в отдельные Android-виджеты TEYES.
- Медиа и звонки: TEYES/CC4, Android-плееры, BT/USB/FM/AM, подписи источника и текста на приборку.
- Навигация: Yandex Core Bridge, 2GIS fallback, режимы `обычный`, `TBT`, `стрелка к финишу`, номерные съезды с кругового, угловые манёвры, lane/gray-road/micro-maneuver логика и опциональный дальний знак «прямо».
- RCTA: предупреждение слева/справа/с двух сторон, overlay поверх камеры, три стиля, сигнал или русские голосовые подсказки.
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
adb install -r updates/kia_367.apk
curl -L -o /tmp/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk \
  https://github.com/mrglonin/kia/releases/download/v23.10-364/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk
adb install -r /tmp/yandex_navi-7_10-universal-kia-mod-teyes-compat.apk
```

На магнитоле:

1. Установить `kia_367.apk`.
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
$ADB -s emulator-5554 install -r updates/kia_367.apk
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
