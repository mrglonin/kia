# Kia Android App

Current Android source for `kia.app`.

- `versionName`: `22.19`
- `versionCode`: `329`
- release APK name: `kia_329.apk`

## Runtime Flow

```text
capture source -> domain state -> cluster sender -> AdapterGateway -> UsbTransport
```

Main packages:

- `kia.app.core`: state models, settings and logging.
- `kia.app.transport.usb`: USB serial connection, permissions and frame reader.
- `kia.app.protocol.adapter`: adapter packet builders, checksums and incoming frame router.
- `kia.app.media`: media/call capture and cluster output.
- `kia.app.navigation`: navigation capture, overlay and cluster output.
- `kia.app.tpms`: TPMS polling, state, alert logic and dashboard.
- `kia.app.rcta`: RCTA protocol state, overlay, preview and sound.
- `kia.app.update`: APK/mod update checks and manual firmware flashing.
- `kia.app.diagnostics`: gs_usb logger flow.
- `kia.app.entry`: Activity, foreground service, boot and USB receivers.

## Bundled Firmware

The APK intentionally bundles only this firmware asset:

```text
app/src/main/assets/firmware/gs_updated.bin
```

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

## Quick QA Broadcast

Use the full adb path if `adb` is not in `PATH`:

```bash
/Users/legion/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am broadcast -n kia.app/.qa.QaReceiver -a kia.app.QA_SCENARIO --es scenario tpms_sample
```
