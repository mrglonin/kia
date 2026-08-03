# Yandex Core Bridge source freshness

This patch targets the public universal `7.10-kia.20260723-heartbeat` APK from
release `v23.08-362`. It fixes three failure modes without changing the package,
version code or signing certificate:

- MapKit location `getRelativeTimestamp()` and `getAbsoluteTimestamp()` are
  exported as `location_relative_timestamp_ms` and
  `location_absolute_timestamp_ms`. KIA can therefore distinguish a new source
  sample from a one-second transport heartbeat that rereads cached Guide data.
- Guidance and Guide subscriptions are tracked independently. A replacement
  `Guidance` clears both gates, and the heartbeat retries only a subscription
  that actually failed, so one transient native error cannot permanently turn
  the bridge into polling-only mode.
- Heartbeat scheduling survives a snapshot exception and logs the failure.

Build with the repository's public update certificate:

```sh
tools/yandex_bridge_freshness/build_mod.sh \
  /path/to/yandex_navi-7_10-universal-kia-mod-heartbeat.apk \
  /tmp/yandex_navi-7_10-universal-kia-mod-freshness.apk
```

The patch deliberately uses apktool's `--no-res` mode. This preserves Yandex's
raw resource table; a normal resource decode reports hundreds of vendor XML
decode failures and is not safe for an update artifact.

The build fails closed unless the input SHA-256 is the published heartbeat APK
(`21fb1528…b484ba37`). It also verifies the unchanged package/version and the
public update certificate SHA-256 (`72631978…0942ca7`) on the output.
