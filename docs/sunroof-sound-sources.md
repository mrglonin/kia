# Sunroof sound sources

Build 374 uses generated Russian speech for roof transitions and the selected
**TEYES ADAS rear warning**, routed to both speakers, for the parking warning.
Source and app-copy checks were completed on 2026-11-04. The female speech is
synthesized using the original Russian TEYES ADAS left/right spoken warnings as
its reference. It is not a newly recorded phrase supplied by TEYES.

| Event | App resource | Source member / resource | Duration | SHA-256 of original and app copy |
|---|---|---|---|---|
| Opening | `res/raw/sunroof_open_voice.wav` | Selected master `female-voice/sunroof-open.wav`: “Люк открыт.” | 1.160000 s | `7d1eb7b2b2929b604a4871119a9b1f3f49a2cce365bb17fc08f8465f40579898` |
| Closing | `res/raw/sunroof_close_voice.wav` | Selected master `female-voice/sunroof-closed.wav`: “Люк закрыт.” | 1.400000 s | `da57b0919d9e790aadb009b90c577ad4181cab9d4ddbde4720926c70c998d83f` |
| Parking warning | `res/raw/sunroof_warning_adas.wav` | Balanced derivative `rear-and-door/rear_warning_both_speakers.wav` | 0.534059 s | `9ec65ca92ddde1f6227e7b06fb9c8921d403e99dacdecc20364c80be575c7623` |

## Generated speech

The two exact texts were generated locally using **MLX Audio 0.6.4** and
`mlx-community/Qwen3-TTS-12Hz-1.7B-Base-8bit`, revision
`e7dd0585652209fa0d7783659aad4e8a324de11c`. Both use the same reference and seed
`20261105`. The model is reference-conditioned; no preset female voice was
substituted. The unshipped male trial is not part of this release.

The exact reference recordings are the existing `teyes_rear_left_warning_voice.mp3`
and `teyes_rear_right_warning_voice.mp3` resources. They are unchanged copies of
`assets/adas_voice/timbre_1/ru/rear_left_warning_voice.mp3` and its right counterpart
from the ADAS APK below. Their SHA-256 values are
`0f9e2c0f886a218a2397c946bb9328a440104176611602b404c3f0615bcc11d4` and
`76d94e825799a7216d74c661b6b928d382a2692ddd508ba2e2b058bab265d266`.
Independent Russian ASR established their words as “Внимание, слева сзади.” and
“Внимание, справа сзади.” Their active left/right channels were selected,
resampled to mono 24 kHz and concatenated for conditioning; the silent channels
were not averaged into the reference.

The selected generated audio was converted to 24 kHz mono PCM16, with fixed gain
to −17 dBFS RMS of the generated waveform and 80/120 ms leading/trailing silence.
There is no pitch/rate processing, compression, reverb or added effect. The app
resources are exact copies of these masters; preview MP3s are not app sources.
Independent ASR also confirmed the exact words in both final PCM16 masters.

Three internal seeds were compared using a reconstruction of the same original
left-warning phrase. Seed `20261105` had the highest Resemblyzer cosine similarity
(`0.9096`) of the three controls. This number is not a percentage or proof of
indistinguishability. The original ADAS speaker and synthesis engine remain
unidentified; the result is reference-conditioned synthetic speech. Human
head-unit audibility was not measured by the no-audio emulator.

[Curated generation and validation receipt](sunroof-voice-374.json). Local masters,
raw generations, checkpoint hashes, scripts, selection and independent ASR evidence
are retained under `output/sunroof-audio-next-20261104/female-voice/` and
`female-reference-analysis/`. No paid API or runtime network/TTS dependency is used.

Primary sources: [Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS),
[MLX Audio reference synthesis](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/tts/qwen3-tts.md),
[the selected checkpoint](https://huggingface.co/mlx-community/Qwen3-TTS-12Hz-1.7B-Base-8bit).

## Rear ADAS source and balanced routing

The original ADAS source container, from the retained 2026-10-13 device capture, is:

```text
vendor/extracted/20261013T124619.186508Z/com.teyes.adas/TeyesAdas.apk
```

Its package is `com.teyes.adas`, version `1.001` / code `1001`. APK SHA-256:
`e555cb593b48949e3e70d5e8049296e6445ffa198d1795e1c0295eeae64d956d`.
The saved source path on the device is `/spdproject/app/TeyesAdas/TeyesAdas.apk`.

Original ZIP member: `assets/adas_voice/common/rear_left_warning.wav`, SHA-256
`fceb3a2214c8f62fcaf4d22bc83b43ef606732ee5a332e84fe8e3f98ab09390b`.
It is PCM16 stereo 44.1 kHz; its right channel is exactly silent. The balanced
derivative copies the original active left-channel samples, bit for bit, into both
output channels. There is **no summing, gain, resampling or timing change**. The
result remains PCM16 stereo 44.1 kHz, 23,552 frames. The app copy matches the
balanced master SHA-256 in the table.

The original rear-left/rear-right active waveforms have correlation
`0.9999999536`; they are in phase, rather than opposite-phase signals. Routing one
original active waveform to both channels avoids introducing a mix. Local evidence
is retained in `rear-and-door/balanced-proof.json`, `channel-analysis.json`, the
unchanged original left/right WAVs and the balanced master. This capture is not a
claim of newly released TS/TEYES firmware. The previous `virtual_bumpers` parking
cue and Bibi transition MP3 are replaced in build 374.

## Playback

`SunroofSoundPlayer.play(Kind)` selects the matching opening/closing phrase once.
One existing transition-sound switch controls both phrases; no mode selector or
separate voice switches are added. A visible parking warning always has sound;
it has no separate mute switch. Its playback is
limited to starts at 0, 3 and 6 seconds, with a hard 9.5-second lifetime.
Acknowledgement, close, replacement or service teardown cancels warning playback
immediately. Turning transition sound off stops only its transition cue. No
AudioFocus request or volume adjustment is made. Speech uses
`CONTENT_TYPE_SPEECH`; the ADAS cue uses `CONTENT_TYPE_SONIFICATION`.

The existing `tpms6_error.wav` remains unchanged for TPMS. Its SHA-256 remains
`7caa565f9940341f31338f2fa6d1f1e37ffae77b6494f56935f2bfc2af5820c9`.
This source attribution does not infer a separate licence grant over vendor audio.
