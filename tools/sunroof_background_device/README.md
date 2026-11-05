# Real Android background regression

This standalone instrumentation APK targets the signed release `kia.app`. It runs
the actual `AppService`, `MainActivity`, `FrameRouter`, `StateStore`, roof policy,
TPMS controller and Android `WindowManager`. It never calls `runQaScenario`, the
preview API or a QA policy.

Only two hardware boundaries are replaced:

- A reflective `UsbSerialPort` proxy makes the existing gateway connected. Writes
  are nonblocking; the normal health worker remains active. Roof and TPMS packets
  enter the real `FrameRouter.onFrame` method with USB epoch 99.
- In `main`, `restart` and `audio`, known ACC input enters the existing TEYES
  observer's actual listener. The separate `teyes` phase instead replaces only
  the system-service locator and exercises the actual observer's Android Binder
  registration, aggregate reads and callback decoding. Neither path establishes
  physical ignition or the installed TEYES service's availability.

The runner accepts only `emulator-*` serials. It changes the emulator's overlay
permission, installs the signed fixture and optionally the supplied target APK,
and force-stops the target between phases and during final cleanup. It restores
only the settings changed by the fixture; real roof-cache writes remain available
for the process-restart test.

Build the target release and debug classes first, then run from the repository root:

```sh
JAVA_HOME=/path/to/jdk17 \
python3 tools/sunroof_background_device/run.py \
  --app-apk app/app/build/outputs/apk/release/kia_376_sunroof.apk \
  --serial emulator-5570 \
  --output /absolute/path/to/evidence
```

The default SDK is `~/Library/Android/sdk`, Android 36 with build-tools 37.0.0.
Override `ANDROID_HOME` and `JAVA_HOME` when needed. `--classes` selects the target's
compile classes JAR. `--build-only` produces the fixture without touching a device.
`--phase startup`, `--phase teyes`, `--phase main`, `--phase restart`, `--phase ui`
or `--phase audio` runs one phase; the default `full` runs startup, teyes, main and restart in separate
force-stopped target processes. The audio phase remains a separate targeted gate.

The startup phase clears only the emulator target's `kia_sunroof_state` roof cache
before launching the Activity/service. It verifies both saved and live roof state
are unknown and injects **OPEN as the first roof packet**, with no manufactured
CLOSED baseline. The real policy must enter OPENING, WindowManager must attach the
background overlay and the production MediaPlayer must play the installed opening
voice. Three duplicate OPEN packets must retain the same presentation sequence
and player; another duplicate after natural completion must not replay audio.
Actual CLOSE and reopening packets must then each show their window and play the
corresponding installed voice. `roofInputs` preserves every injected packet in
order, and `startupProbes` records policy sequence, window and player observations.
This phase does not inject ACC or call a preview/QA scenario.
Two further startup scenarios retain a real CLOSED observation over USB loss,
reconnect the proxy with epoch 100 and require the first OPEN to play without a
new CLOSED packet; another reconnect at epoch 101 with the same accepted OPEN
must remain silent. These checks cover warm reconnect as well as a new process.

Run `--phase startup` against both the released 375 APK and the fixed APK using
classes matching each target. On 375 the first-OPEN scenario is expected to fail
because its policy treats that first packet as a silent baseline; closing and
reopening should still pass. That negative control distinguishes this test from
the older main/audio tests, which seed a CLOSED packet before checking opening.

The `teyes` phase also begins with an empty roof cache. After stopping the real
controller, reflection replaces its observer with another production observer
constructed with a test-only `ServiceLocator`. The locator returns a local
Android `Binder` implementing the retained `ISpdService` contract: registration
transaction 1, unregistration 3 and `getSourceInfo("android")` transaction 9.
The service initially returns a null aggregate. The actual observer must retain its
registered callback across those reads. The fixture then invokes the observer's
actual `CallbackBinder` using real Android `Parcel` objects, never `listener.onAcc`.

One real OPEN packet is persisted and USB is disconnected for more than three
seconds. Malformed, truncated and invalid-enum callbacks must not invent ACC OFF.
Transaction 13 with command 5 / arguments 1,0 must produce the fullscreen warning
and actual mandatory MediaPlayer cue from the saved roof state. Duplicate OFF
callbacks must not restart the active player or replay it after completion.
Command 8 sleep/wake must hide and restore the unacknowledged warning; its existing
audio behavior on redisplay is recorded rather than changed by this test.
Transaction 14 must acknowledge the warning without consuming the native key,
and saved callbacks invoked after stop must not change state or show/play again.
An additional restart changes the fixture to a real Parcel aggregate prefix with
the exact DeviceStatusInfo class string, platform ID 73, ACC ON/OFF and an ignored
tail. This verifies Android string/int framing and that ACC is read after the
platform ID. The saved callback from the earlier observer run must not override
the new aggregate ON; aggregate OFF must reach the real window and audio path.
`teyesInputs` and `teyesProbes` retain callback payloads, registration/read counts,
observer state and actual player/window observations. This phase tests Binder
wire handling on Android, not privileged service discovery on a physical TEYES.

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

The targeted audio phase checks the same actual settings controls, then sends
real roof packets through `FrameRouter` with the transition sound enabled. The
production `MediaPlayer` must report opening and closing voice durations of
the installed PCM WAV assets while playing about 100 ms after each transition.
The fixture reads each target raw resource independently of production cue
selection, verifies PCM16 / 24 kHz / mono voice metadata, and derives its expected
duration from the audio frame count. Both clips must complete and release
naturally. Turning the shared preference off must
silence both real transitions. With that preference and the legacy warning
preference both false, ACC OFF must still play the mandatory combined warning
once: the unchanged rear ADAS signal, a 140 ms silent gap, then the spoken roof
warning. Its 105578-frame PCM16 / 44.1 kHz / stereo resource lasts about 2394 ms.
The fixture verifies the selected whole-file hash, original 23552-frame beep PCM
hash, 6174 zero gap frames, voice boundaries and identical speaker channels.
An actual input tap and a separate real roof-close packet must each stop playback
during the spoken part. A separate episode must complete naturally while its
overlay persists; actual duplicate packets and controller refreshes at 3 s and
6 s must leave audio silent. The fixture polls the production MediaPlayer about
every 100 ms through at least 9.8 s to detect replay beyond the 9.5 s hard guard.
The report retains resource hashes and PCM metadata in
`audioAssets`, with measured durations and preference values in `audioProofs`. This phase tests
the deployed 24 kHz mono voice and 44.1 kHz stereo warning WAV decoding through
Android, without repeating the full background/cache suite. Timing observations
and cancellation positions are retained in `warningPlaybackProbes`.

Each phase writes a JSON report with the installed APK hash, process ID, assertion
counts and number of injected roof packets. Screenshots, WindowManager dumps and
window/view alpha and geometry are retained alongside the reports. A nonzero
failure count produces a nonzero runner exit status. This verifies Android app
behavior on the selected emulator; it does not establish head-unit permissions,
physical CAN delivery, native key routing, amplifier timing or physical sound.
