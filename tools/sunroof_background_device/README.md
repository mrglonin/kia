# Real Android background regression

This standalone instrumentation APK targets the signed release `kia.app`. It runs
the actual `AppService`, `MainActivity`, `FrameRouter`, `StateStore`, roof policy,
TPMS controller and Android `WindowManager`. It never calls `runQaScenario`, the
preview API or a QA policy.

Only two hardware boundaries are replaced:

- A reflective `UsbSerialPort` proxy makes the existing gateway connected. Writes
  are nonblocking; the normal health worker remains active. Roof and TPMS packets
  enter the real `FrameRouter.onFrame` method with USB epoch 99.
- Known ACC input enters the existing TEYES observer's actual listener. This tests
  the controller/policy response, without claiming native TEYES Binder or physical
  ignition validation.

The runner accepts only `emulator-*` serials. It changes the emulator's overlay
permission, installs the signed fixture and optionally the supplied target APK,
and force-stops the target between phases and during final cleanup. It restores
only the settings changed by the fixture; real roof-cache writes remain available
for the process-restart test.

Build the target release and debug classes first, then run from the repository root:

```sh
JAVA_HOME=/path/to/jdk17 \
python3 tools/sunroof_background_device/run.py \
  --app-apk app/app/build/outputs/apk/release/kia_373_sunroof.apk \
  --serial emulator-5570 \
  --output /absolute/path/to/evidence
```

The default SDK is `~/Library/Android/sdk`, Android 36 with build-tools 37.0.0.
Override `ANDROID_HOME` and `JAVA_HOME` when needed. `--classes` selects the target's
compile classes JAR. `--build-only` produces the fixture without touching a device.
`--phase main`, `--phase restart` or `--phase ui` runs one phase; the default `full`
runs the main and restart phases.

The main phase checks background open/close over Home, a destroyed Activity with a
live foreground service, minor TPMS warnings, critical TPMS deferral and real
acknowledgement, opacity, bounded touch handling, 20 percent car growth from the
released `0.161W` to `0.1932W`, full-screen parking warning and a real input tap.
It also checks actual open/closed packets followed by USB loss and ACC OFF more
than three seconds later. An actual Android sleep/wake cycle must hide the window
and release the audio player without acknowledging the cached roof state. Its
final real open packet seeds the persistent cache.

The actual MainActivity settings controls are clicked and inspected: three preview
buttons and the warning-sound switch must be absent, with one shared transition
sound switch. The mandatory warning is also checked with both legacy sound
preferences set false: the production AndroidFactory clip must contain an actual
`android.media.MediaPlayer` that reports `isPlaying()` about 100 ms after ACC OFF.
That establishes Android playback state, not physical audibility.

The restart phase runs in a new target process after force-stop. It does not
install a USB proxy or inject any roof packet. Unknown ACC must remain silent;
known ACC OFF must display the warning from the persisted real roof observation.

Each phase writes a JSON report with the installed APK hash, process ID, assertion
counts and number of injected roof packets. Screenshots, WindowManager dumps and
window/view alpha and geometry are retained alongside the reports. A nonzero
failure count produces a nonzero runner exit status. This verifies Android app
behavior on the selected emulator; it does not establish head-unit permissions,
physical CAN delivery, native key routing, amplifier timing or physical sound.
