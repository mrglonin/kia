# Kia CANBUS

Clean local workspace for the Kia CANBUS Android app.

Source was copied from:

```text
/Users/legion/Downloads/canbus/build/git/kia
```

## Layout

- `app/` - Android Gradle project for `kia.app`.
- `tools/` - local CAN, UART, navigation and APK utilities.
- `signing/` - intentionally public debug release keystore used by release builds.
- `updates/latest.json` - manifest for Kia, Yandex mod and firmware updates.

Not tracked here: Gradle caches, build outputs, APK release archive, screenshots,
logs, `.DS_Store`, temporary captures and old generated artifacts.

## Current App

- `applicationId`: `kia.app`
- `versionName`: `22.13`
- `versionCode`: `323`
- release APK name: `kia_323.apk`

## Build

```bash
cd app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/legion/Library/Android/sdk" \
./gradlew :app:assembleDebug --console=plain
```

Release:

```bash
cd app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/legion/Library/Android/sdk" \
./gradlew :app:assembleRelease --console=plain
```

Release signing looks for `../signing/kia-debug-release.keystore`.

## Useful Tools

- `tools/can_button_level_capture.py` - guided CAN capture for climate buttons.
- `tools/climate_button_review_server.py` - local review UI for captured climate states.
- `tools/gs_can_logger_server.py` - GS-USB CAN logger UI.
- `tools/navi_lane_server.py` - navigation lane/TRC review server.
- `tools/analyze_nav_trc.py` - offline TRC analysis.
- `tools/media_serial_tester.py` - serial/media protocol tester.
- `tools/apktool_2.9.3.jar` - local APK decode/build helper.
