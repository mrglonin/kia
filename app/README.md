# KIA Android app

Android source for `kia.app`.

- `applicationId`: `kia.app`
- `versionName`: `22.42`
- `versionCode`: `352`
- release APK name: `kia_352.apk`
- min SDK: `29`
- target SDK: `35`
- compile SDK: `35`

## Runtime flow

```text
capture source -> domain state -> cluster sender -> AdapterGateway -> UsbTransport
```

Main packages:

- `kia.app.core`: state models, settings, logging.
- `kia.app.transport.usb`: USB serial connection, permissions, frame reader.
- `kia.app.protocol.adapter`: adapter packets, checksums, incoming frame routing.
- `kia.app.media`: media/call capture and cluster output.
- `kia.app.navigation`: Yandex/2GIS capture, overlay, cluster output.
- `kia.app.tpms`: TPMS polling, dashboard, alert logic.
- `kia.app.rcta`: RCTA protocol state, overlay, preview, sound.
- `kia.app.update`: KIA APK and Yandex mod update checks.
- `kia.app.diagnostics`: adapter health polling.
- `kia.app.entry`: Activity, foreground service, boot and USB receivers.

## Adapter

The public setup is for a USB CAN adapter modified and flashed by the Drive2 author:

- Author profile: <https://www.drive2.ru/users/76508/>
- Adapter modification notes: <https://www.drive2.ru/l/717368666034802531/>

## Build

From this folder:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/legion/Library/Android/sdk" \
./gradlew :app:assembleDebug --console=plain
```

Release APK:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/legion/Library/Android/sdk" \
./gradlew :app:assembleRelease --console=plain
```

Release signing uses `../signing/kia-debug-release.keystore`.

## Quick QA broadcast

Use the full adb path if `adb` is not in `PATH`:

```bash
/Users/legion/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario tpms_sample
```
