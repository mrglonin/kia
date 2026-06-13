# Changelog

## v22.41 / 351 - 2026-06-13

- KIA app `22.41 / 351`: `updates/kia_351.apk`.
- Вырезан режим logger/gs_usb CAN: отдельная вкладка/раздел диагностики, переключатель записи CAN, выбор C-CAN/M-CAN, сохранение `.log.gz` и прошивка `gs_updated.bin`.
- Удалены app-side классы `CanLogger`, `GsUsbCanLogger`, `DiagnosticState` и asset `firmware/gs_updated.bin`; bundled firmware list больше не содержит logger-прошивку.
- Health polling оставлен для штатного адаптера, но быстрый RAW CAN poll каждые 40 мс для записи лога убран; обычный разбор RAW CAN кадров для температуры/люка сохранён.
- `updates/latest.json` переведён на `kia_351.apk` с актуальными SHA-256 и размером.
- Проверена оптимизация APK: размер `5861279` -> `5826835` байт, минус `34444` байта; внутри APK нет `firmware/gs_updated.bin`, dex-строк `gs_usb`, `CanLogger`, `GsUsbCanLogger`.
- Проверено сборкой `./gradlew clean :app:assembleRelease`; APK внутри: `kia.app` / `versionCode=351` / `versionName=22.41`, SHA-256 `caacec68ed27a9ace02c16c6e846eca2a7b3ad27d5d3ba363fab2cbb5d4d7a6d`.

## v22.40 / 350 - 2026-06-12

- KIA app `22.40 / 350`: `updates/kia_350.apk`.
- Исправлен режим стрелки к финишу: направление теперь отправляется валидным рядом приборки `00,03,06...45`, без промежуточных байтов, на которых стрелка могла пропадать.
- Добавлено плавное докручивание finish-стрелки от последней известной позиции при смене маршрута; реальные изменения направления не пропускаются, одинаковое направление не спамится.
- Уточнён источник позиции/курса для финишной стрелки: Yandex bridge heading/current point, GPS/fused point и GPS course fallback.
- Добавлен USB web-пульт `tools/finish_arrow_mapper_server.py` для ручной калибровки finish-стрелки через адаптер.
- `updates/latest.json` переведён на `kia_350.apk` с актуальными SHA-256 и размером.
- Проверено сборкой `./gradlew :app:assembleRelease`; APK внутри: `kia.app` / `versionCode=350` / `versionName=22.40`, SHA-256 `d9925dbc43fd66e949ccc463f369e04db64ba7d896dceb34d6f3e9fbc98dd665`.
- Проверено установкой на TEYES/CC4PRO по ADB `172.20.10.3:7575`; `lastUpdateTime=2026-06-12 19:07:45`.

## v22.39 / 349 - 2026-06-12

- KIA app `22.39 / 349`: `updates/kia_349.apk`.
- Исправлена проверка обновления KIA: уже установленная версия больше не открывает старый скачанный APK как новое обновление.
- Исправлена проверка обновления Yandex Navigator mod: если установленный `versionCode` равен манифесту, повторная установка не запускается.
- `updates/latest.json` переведён на `kia_349.apk` с актуальными SHA-256 и размером.
- Проверено сборкой `./gradlew :app:assembleRelease`; APK внутри: `kia.app` / `versionCode=349` / `versionName=22.39`, SHA-256 `d95fe1d6066a3c7140814850bae7f4fb207b8a17fbb4552a404708b82a1fe305`.

## v22.38 / 348 - 2026-06-12

Первый публичный релиз репозитория.

- KIA app `22.38 / 348`: `updates/kia_348.apk`.
- Yandex Navigator Kia mod `7.10-kia.20260608`: `updates/yandex/yandex_navi-7_10-arm7-kia-mod.apk`.
- Публичный `latest.json` с SHA-256 и размерами APK.
- README с прямой кнопкой скачивания, установкой, сборкой, QA-командами и реальными скриншотами.
- README фиксирует требование к переделанному и прошитому USB CAN-адаптеру от автора Drive2: <https://www.drive2.ru/users/76508/>, <https://www.drive2.ru/l/717368666034802531/>.
- Галерея текущих вариантов UI: TPMS, warning, widget/sunroof, RCTA, Navigation, Media, CANBUS, General.

Проверено на `kia_teyes_1000x600` / `emulator-5554`: установлен APK `22.38 / 348`, `lastUpdateTime=2026-06-12 10:17:48`, скриншоты сняты через `QaReceiver`.
