# Changelog

## v23.03 / 357 - 2026-07-24

- KIA app `23.03 / 357`: `updates/kia_357.apk`.
- Новая синхронизированная TX-ветка включена только для профилей `Android` и `UART real`; проверенный профиль `TEYES / CC4` продолжает использовать прежние capture, radio resolver и отправитель музыки/звонков.
- Смена трека больше не имитирует смену источника: Android отправляет `media off / source 0x7A` только при реальном переходе режима или частоты, а обновление artist/title отправляет только текст. В `UART real` исходящий синтетический `0x7A` полностью запрещён — реальный source магнитолы остаётся авторитетным.
- Добавлены отдельные задержки после source для Android и UART, настраиваемое время показа исполнителя перед названием трека и один опциональный контрольный повтор. Отложенные callbacks защищены generation/profile/call/media-key guard.
- Повтор того же позднего UART source восстанавливает текущий текст ровно один раз для данной версии metadata; pause→play повторно показывает тот же трек, pause/idle не запускают устаревшие уведомления.
- Частичный `BLOCKED` больше не повторяет уже успешно отправленный `media off`; актуальное состояние повторяется следующим Universal poll. USB connection epoch обнаруживает даже быстрый reconnect между соседними poll и выполняет один полный resync.
- Для UART разобраны FM/AM, BT, USB, phone, Android Auto, My Music, CarPlay, AUX и off. При FM/AM частота из SPD принимается только после совпадения с реальным UART source, поэтому старый Binder snapshot не может подменить станцию.
- Радиостанции получают стабильные названия из списка/PS без сохранения меняющегося RDS RT. Добавлен ручной список FM/AM с добавлением, редактированием, удалением и защищённым приоритетом ручного названия над автообновлением.
- Смена профиля отменяет старые TEYES/Universal таймеры и ждёт свежий capture выбранного профиля вместо отправки старого `StateStore.media`.
- Yandex Navigator Kia mod не изменён и для этого обновления не требуется.
- Проверено: `107` unit-тестов без failures/errors/skips, `lintRelease` — `0 errors` / `102 warnings`, clean release build, zipalign, ZIP и APK signature verification успешны.
- KIA APK: `5890783` байт, SHA-256 `a1700e5065f1bd5ff45da0d2c6fb063165b042a81c3a055c6b29001d0427d14d`; сертификат SHA-256 `72631978082200032bd33700f86195786e63a5ddb43166d186baa934c0942ca7` совпадает с `23.02`.

## v23.02 / 356 - 2026-07-24

- KIA app `23.02 / 356`: `updates/kia_356.apk`.
- Микроманёвр теперь управляет только жёлтым знаком и серой схемой дороги. Числовая дистанция и progress во всех Yandex TX-путях остаются привязаны к основному манёвру: собственные `150/140/130 м` микроманёвра больше не попадают в счётчик приборки.
- Манёвр и его дистанция выбираются как единый снимок: новый знак без валидной дистанции не может получить старые метры от предыдущего манёвра.
- Transient `0/1 м` во время lane guidance не отправляется в приборку и не портит сохранённое значение. При известном остатке маршрута счётчик безопасно продолжается по пройденной дельте; при недостатке данных удерживается последний ненулевой кадр.
- После прохождения, очистки или expiry микроманёвра основной знак восстанавливается с актуальной дистанцией. Reconnect хранит отдельно последний основной кадр, обновляет его даже под micro overlay и не возвращает устаревший micro/нулевой кадр.
- Нормализация Yandex Core Bridge синхронизирует generic и provenance-specific aliases дистанции; annotation/notification fallback не создаёт искусственный `0 м`, route/provenance change остаётся fail-closed.
- Добавлен QA-сценарий `nav_micro_main_counter_sample`. На эмуляторе подтверждены TX `1.0 км -> 990 -> 980 -> 970 м`, отсутствие TX на transient `0`, затем восстановление основного манёвра с `960 м`; micro-дистанции `150/140/130/120/110 м` в TX отсутствуют.
- Yandex Navigator Kia mod не изменён: исправление полностью выполнено в KIA consumer/TX-логике, установленный mod `7.10-kia.20260723` переустанавливать не требуется.
- Проверено: `78` unit-тестов без failures/errors/skips, `lintRelease` — `0 errors` / `102 warnings`, clean release build, zipalign, ZIP и APK signature verification успешны.
- KIA APK: `5860531` байт, SHA-256 `9277f39c2f7dcd1eacf112e6ee20b94dc3423a1a3b61413c3e1db58f459ae7ab`; сертификат SHA-256 `72631978082200032bd33700f86195786e63a5ddb43166d186baa934c0942ca7` совпадает с `23.01`.

## v23.01 / 355 - 2026-07-23

- KIA app `23.01 / 355`: `updates/kia_355.apk`.
- Исправлен номерной манёвр кругового движения: KIA теперь принимает `roundabout_exit_number` / `maneuver_exit_number`, которые уже передаёт displayed annotation из Yandex Navigator Kia mod.
- В режиме TBT съезды 1/2/3/4 больше не схлопываются в общий код `61 00 00 00`: приборка получает проверенные составные кадры `20 08 11 0C/00/24/18` с серым кругом и выбранной жёлтой веткой. Общий манёвр выхода с кругового остаётся `61 00 00 00`.
- Арбитраж main/micro, дистанции, hold кругового, gray-road и остальные манёвры не изменялись.
- Yandex Navigator Kia mod не изменён: он уже корректно извлекает номер съезда, исправлено несовпадение consumer-контракта на стороне KIA.
- OTA KIA переведён на GitHub Release asset `v23.01-355`; установленный Yandex mod `7.10-kia.20260723` переустанавливать не требуется.
- Проверено: `51` unit-тест без failures/errors/skips, `lintRelease` — `0 errors`, release build, zipalign и APK signature verification успешны.
- KIA APK: `5849071` байт, SHA-256 `3637ffb75fda89470f64a853c92a94e4cb79a2fee59ff467c6332b2f6b82e8de`; сертификат SHA-256 `72631978082200032bd33700f86195786e63a5ddb43166d186baa934c0942ca7` совпадает с `23.00`.

## v23.00 / 354 - 2026-07-23

- KIA app `23.00 / 354`: `updates/kia_354.apk`.
- Yandex Navigator Kia mod `7.10-kia.20260723 / 71011062`: `updates/yandex/yandex_navi-7_10-arm7-kia-mod.apk`.
- Исправлен основной навигационный баг: тип кругового движения больше не является безусловным приоритетом. Пространственный арбитр сравнивает дистанции и показывает ближайший подтверждённый micro/lane-манёвр до удалённого кругового.
- Убраны обходы арбитрации в ETA, guidance, gray-road, resend и других TX-путях; все достижимые отправки манёвра проходят общий spatial guard.
- Hold кругового ограничен текущим событием и ближней зоной, не продлевается одинаковыми снимками и очищается при более раннем micro или смене маршрута.
- Yandex mod больше не перезаписывает displayed annotation данными notification: annotation/notification разделены, notification используется только как fallback; `leave_roundabout` распознаётся раньше общего roundabout.
- Исправлены stale/out-of-order снимки, перезапуск sequence, откат к старому route ID, provenance дистанции/выезда, формат расстояния `3 000 м`, очистка исчезнувшего micro и сохранение коротких semantic/lifecycle переходов в очереди.
- USB-очередь ограничена и объединяет устаревшие навигационные кадры; после reconnect и обновления прошивки адаптера восстанавливается актуальное состояние. TX-лог теперь фиксирует `WRITTEN`, `QUEUED` или `BLOCKED`.
- Исправлены TBT-глифы выходов с кругового, dedup стрелки к финишу учитывает закодированную дистанцию, а route/maneuver reset больше не оставляет старый экран.
- Yandex bridge защищён signature-permission и проверкой envelope до запуска сервиса/wakelock; legacy receiver отделён, QA receiver ограничен `android.permission.DUMP`.
- Добавлен ограниченный ротацией журнал `navigation-logs/navigation.log`; снижена частота записи TX в SharedPreferences, исправлены проверки location permission и timeout wakelock.
- Исправлен запуск foreground service на Android 14/15 без выданных runtime permissions: `connectedDevice` всегда имеет допустимое основание, location-тип включается только с foreground/background location, запрещённый нулевой тип больше не используется; маска обновляется при смене location provider.
- OTA переведён на GitHub Release assets `v23.00-354`; fallback читает GitHub SHA-256 digest, а KIA/Yandex updater больше не принимает APK без ожидаемого SHA.
- Проверено: `47` unit-тестов без failures/errors/skips, `lintRelease` — `0 errors`, release build успешен, обновление KIA `22.43 -> 23.00` и foreground service проверены на Android 15, оба APK проходят package/version, zipalign, ZIP и signature verification.
- KIA APK: `5848795` байт, SHA-256 `db291fcc3eacf28ddbdc7578c4a49227bfc20db656ec0b713b48beca7521b06e`.
- Yandex mod APK: `79674122` байт, SHA-256 `07f4979ee2df95b62ce8c1a44f8ecc6a9892b9481951ab780962fe06508f8be7`; сертификат обоих APK — `72631978082200032bd33700f86195786e63a5ddb43166d186baa934c0942ca7`.

## v22.43 / 353 - 2026-06-19

- KIA app `22.43 / 353`: `updates/kia_353.apk`.
- Исправлен порядок данных TPMS для задних колес в native-пакете `0x51`: заднее правое и заднее левое больше не меняются местами.
- `updates/latest.json` переведён на `kia_353.apk` с актуальными SHA-256 и размером.
- Проверено сборкой `./gradlew :app:assembleRelease`; APK внутри: `kia.app` / `versionCode=353` / `versionName=22.43`, SHA-256 `d1c87dc7706590ac326f35e1651b94f592e8adcba460a4ff7a770c7c03b3b6cb`.

## v22.42 / 352 - 2026-06-13

- KIA app `22.42 / 352`: `updates/kia_352.apk`.
- Убран лишний шум компаса: одинаковое направление больше не отправляется каждую секунду; повторная отправка оставлена только при восстановлении USB-связи.
- TPMS warning теперь строго overlay: предупреждение не рисуется внутренней плашкой в приложении, включая widget/compact режимы, и не скрывается только из-за открытой Kia Activity.
- `updates/latest.json` переведён на `kia_352.apk` с актуальными SHA-256 и размером.
- Проверено сборкой `./gradlew :app:assembleRelease`; APK внутри: `kia.app` / `versionCode=352` / `versionName=22.42`, SHA-256 `d8126f2355a80284116713415d3d2cd5841ab521a4844cde7d30a215ff3cdb21`.
- Проверено на эмуляторе `kia_teyes_2000x1200`: TPMS low-pressure warning отображается верхним overlay-слоем, без inline-плашки в приложении.

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
