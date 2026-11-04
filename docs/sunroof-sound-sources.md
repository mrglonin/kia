# Sunroof sound sources

The two sunroof cues use original bytes from the retained TEYES device capture of
2026-10-13. Selection and source checks were completed on 2026-11-04. Opening and
closing intentionally share **one TEYES Bibi UI effect**. The parking warning is
**virtual_bumpers from the original com.teyes.adas APK**.

| Event | App resource | Source member / resource | Duration | SHA-256 of original and app copy |
|---|---|---|---|---|
| Opening and closing | `res/raw/sunroof_transition_teyes.mp3` | Bibi `res/Av.mp3`, `raw/float_send` | 0.364875 s | `fe925643d68ab14bfc0644171cf5b18faa8d70919c9bf510d8131ff33208fa42` |
| Parking warning | `res/raw/sunroof_warning_adas.mp3` | ADAS `assets/adas_voice/common/virtual_bumpers.mp3` | 1.068563 s | `0002f1c759f5895b80f2df2d81eec380cd347e73bf5791eb4408cd2fa4fe255d` |

The Bibi source container is:

```text
vendor/all-apks-20261013T125110Z/com.teyes.bibi/bibi.apk
```

Its package is `com.teyes.bibi`, version `1.1.0` / code `26`. APK SHA-256:
`1911b78eefd29385976596bc477741642bdd645f2f8f7dda47b7d8d122483cf8`.
The obfuscated ZIP filenames were mapped to the resource names above using
Android SDK `aapt2 dump resources`.

The ADAS source container is:

```text
vendor/extracted/20261013T124619.186508Z/com.teyes.adas/TeyesAdas.apk
```

Its package is `com.teyes.adas`, version `1.001` / code `1001`. APK SHA-256:
`e555cb593b48949e3e70d5e8049296e6445ffa198d1795e1c0295eeae64d956d`.
The saved source path on the device is `/spdproject/app/TeyesAdas/TeyesAdas.apk`.

The capture date identifies the retained source, not a claim that this is newly
released TS/TEYES firmware. No normalization, amplification, trimming, synthesis
or transcoding was applied to either app resource. The warning retains its actual
MP3 format and extension. Catalogue MP3/M4A previews are separate from app sources.

The ADAS warning has a measured decoded peak of **-1.674 dBFS**, without samples
above full scale, and a main tone around **906 Hz**. The file includes about
0.246 seconds of active signal. This is the non-directional common ADAS cue;
the left/right rear and lane-departure clips are available separately for audition.
The catalogue includes all seven common non-voice ADAS clips with exact members
and hashes, plus an inventory of all 57 ADAS audio assets. The previous Alya and
separate opening cue are not app resources or defaults.

`SunroofSoundPlayer.play(Kind)` maps both opening and closing to the same resource,
played once. Their settings switch controls only these transition cues. A visible
parking warning always has sound; it has no separate mute switch. Its playback is
limited to starts at 0, 3 and 6 seconds, with a hard 9.5-second lifetime.
Acknowledgement, close, replacement or service teardown cancels warning playback
immediately. Turning transition sound off stops only its transition cue. No
AudioFocus request or volume adjustment is made.

The existing `tpms6_error.wav` remains unchanged for TPMS. Its SHA-256 remains
`7caa565f9940341f31338f2fa6d1f1e37ffae77b6494f56935f2bfc2af5820c9`.
This source attribution does not infer a separate licence grant over vendor audio.
