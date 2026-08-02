# Yandex Core Bridge heartbeat

The public Yandex Navigator Kia mod is event-driven. When Yandex pauses guidance callbacks in the
background, KIA cannot learn a changed current speed or road speed limit. Release `v23.08-362`
adds a one-second main-thread heartbeat to the already injected `com.kia.yandex.v2` bridge.

The heartbeat calls the existing synchronized `YandexKiaBridge.publish("heartbeat")`. That method
reads the current Guide/Guidance snapshot and sends the existing explicit broadcast to KIA. It does
not fabricate navigation data and stops naturally when the Yandex process stops.

Injection contract:

1. Add static `Handler`, `Runnable`, and started-flag fields to `YandexKiaBridge`.
2. Call `startHeartbeat()` once from `YandexKiaBridge.init(...)` after `configureGuide()`.
3. Create the handler on `Looper.getMainLooper()` and post `YandexKiaBridgeHeartbeat`.
4. `scheduleHeartbeat()` reposts the same runnable after `1000 ms`.
5. Keep package, version code and signing certificate unchanged so the mod installs over the
   previous public Kia mod; OTA distinguishes this archive by SHA-256 and size.

Runtime verification on Android 15 must show repeating lines such as
`YandexKiaBridge: snapshot ... cb=heartbeat` both in foreground and after `KEYCODE_HOME`.
