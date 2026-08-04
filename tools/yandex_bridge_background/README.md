# Yandex background location provider fix

This patch targets the published `7.10-kia.20260723` freshness APK from KIA
release `v23.09-363`. It fixes the observed background transition on Android
devices without a framework `gps` provider:

- A requested `gps` subscription remains unchanged when the provider exists.
- If `gps` is absent, the runtime selects the real framework `fused` provider,
  then `network` as a secondary fallback. It never uses `passive` and never
  fabricates a location or timestamp.
- While the task exists, Navilib keeps MapKit and the native core resumed across
  Home and starts the existing location-typed `GuidanceService` as a real
  foreground service. A low-importance, ongoing notification makes that
  background ownership explicit to Android and the user.
- The KIA foreground owner guards the stock guidance callbacks from replacing
  or removing its notification. Re-entering the activity does not double-start
  MapKit.
- Removing the task performs the balanced teardown in lifecycle order:
  MapKit stop, native-core stop, guidance callback, notification removal, and
  service stop. A force-stop is still handled by Android process teardown.

KIA's source-timestamp freshness and 12-second fail-closed TTL remain unchanged.
If Android stops delivering genuine locations, the instrument cluster is still
cleared instead of retaining a cached speed-limit sign.

This deliberately trades some background battery use for continuous free-drive
data. Background operation is visible as `KIA: фоновая навигация`; swiping
Yandex Navigator from Recents stops it.

Build with the repository's public update certificate:

```sh
tools/yandex_bridge_background/build_mod.sh \
  /path/to/yandex_navi-7_10-universal-kia-mod-freshness.apk \
  /tmp/yandex_navi-7_10-universal-kia-mod-background.apk
```

The build uses apktool `--no-res` to preserve Yandex's raw resource table. It
fails closed unless the input SHA-256 is the exact published freshness APK
(`0b6e336a…14a71fd`) and verifies package, version, zip alignment, and signing
certificate on the result.

Device acceptance on the Redmi Pad included a 65-second Home run with increasing
real source timestamps, two Home/return cycles without a crash or duplicate
foreground owner, and a Recents swipe that removed the location registrations
and foreground notification.
