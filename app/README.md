# KIA Android app

Android application for TEYES head units, package `kia.app`. The release version, minimum Android version and output filename are defined in the [app module](app/build.gradle). The [installation guide](../README.md) covers supported hardware and in-app updates.

## Runtime flow

```text
capture source -> domain state -> cluster sender -> AdapterGateway -> UsbTransport
```

Main packages:

- `kia.app.core`: state models, settings, logging.
- `kia.app.transport.usb`: USB serial connection, permissions, frame reader.
- `kia.app.protocol.adapter`: adapter packets, checksums, incoming frame routing.
- `kia.app.media`: media/call capture and cluster output.
- `kia.app.navigation` receives Yandex, MVite and 2GIS events and updates the overlay and cluster.
- `kia.app.adas` connects to TEYES ADAS and records lane warnings.
- `kia.app.sunroof` handles the sunroof overlay and audio.
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

Use JDK 17 and an Android SDK with Platform 36 and Build Tools 37.0.0. Set `ANDROID_HOME` or configure `sdk.dir` in `local.properties`. Run the Gradle wrapper from this directory.

```sh
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

Release signing uses `../signing/kia-debug-release.keystore`.

Navigation decisions and adapter TX outcomes are written to the bounded rotating log at
`/sdcard/Android/data/kia.app/files/navigation-logs/navigation.log`.

## Quick QA broadcast

On an emulator, display sample tyre pressures with the following command.

```bash
adb -e shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario tpms_sample
```
