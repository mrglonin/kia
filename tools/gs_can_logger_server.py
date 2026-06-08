#!/usr/bin/env python3
import argparse
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


LOG_ROOT = "/Users/legion/Downloads/canbus/logs/gs_clean"

CAN_PROFILES = {
    "mcan": {"label": "M-CAN", "preferred_channel": 0, "bitrate": 100000},
    "ccan": {"label": "C-CAN", "preferred_channel": 1, "bitrate": 500000},
}

HTML = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>KIA CAN logger</title>
  <style>
    :root { color-scheme: dark; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    body { margin: 0; background: #101419; color: #edf3f8; }
    main { max-width: 920px; margin: 0 auto; padding: 22px; }
    h1 { margin: 0 0 8px; font-size: 24px; }
    h2 { margin: 0 0 12px; font-size: 16px; }
    p { margin: 0 0 14px; color: #9eabb8; }
    section { border: 1px solid #28323d; border-radius: 8px; padding: 16px; background: #171d24; margin-bottom: 14px; }
    label { display: block; margin-bottom: 6px; color: #bcc8d4; font-size: 13px; }
    input, button { box-sizing: border-box; border: 1px solid #394755; border-radius: 7px; background: #202934; color: #edf3f8; font: inherit; }
    input { width: 100%; padding: 11px 12px; }
    button { padding: 11px 12px; cursor: pointer; font-weight: 700; }
    button.primary { background: #1f6feb; border-color: #388bfd; }
    button.warn { background: #6b2b19; border-color: #c4552f; }
    button:hover { border-color: #7aa2d6; }
    .buttons { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; }
    .status { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; color: #d7e2ec; }
    .counters { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
    .counter { border: 1px solid #28323d; border-radius: 8px; padding: 12px; background: #0d1116; }
    .counter b { display: block; font-size: 22px; margin-top: 4px; }
    .log { min-height: 180px; max-height: 420px; overflow: auto; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.45; background: #0b0f14; border: 1px solid #27313b; border-radius: 8px; padding: 12px; white-space: pre-wrap; }
    .muted { color: #8795a3; }
  </style>
</head>
<body>
<main>
  <h1>KIA CAN logger</h1>
  <p>GS-USB only. M-CAN = 100k, C-CAN = 500k. One-channel adapters record either bus on ch0; both at once needs two channels.</p>

  <section>
    <h2>GS-USB</h2>
    <div id="usb" class="status muted">scanning</div>
  </section>

  <section>
    <h2>Capture</h2>
    <label for="label">Log name</label>
    <input id="label" placeholder="climate_auto_button">
    <div style="height:12px"></div>
    <div class="buttons">
      <button class="primary" onclick="startLog('mcan')">Start M-CAN 100k</button>
      <button class="primary" onclick="startLog('ccan')">Start C-CAN 500k</button>
      <button class="primary" onclick="startLog('both')">Start both</button>
      <button class="warn" onclick="stopLog()">Stop</button>
    </div>
    <div class="counters">
      <div class="counter">M-CAN frames<b id="mFrames">0</b></div>
      <div class="counter">C-CAN frames<b id="cFrames">0</b></div>
    </div>
    <div style="height:12px"></div>
    <div id="status" class="status muted">idle</div>
  </section>

  <section>
    <h2>Mini log</h2>
    <div id="log" class="log"></div>
  </section>
</main>
<script>
const $ = id => document.getElementById(id);

function append(line) {
  const now = new Date().toLocaleTimeString();
  $('log').textContent += `[${now}] ${line}\n`;
  $('log').scrollTop = $('log').scrollHeight;
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: {'Content-Type': 'application/json', ...(options.headers || {})}
  });
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = {ok: false, error: text}; }
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}

async function startLog(mode) {
  try {
    const data = await api('/api/start', {
      method: 'POST',
      body: JSON.stringify({mode, label: $('label').value})
    });
    if (data.ok === false) throw new Error(data.error || 'start failed');
    append(`started ${data.mode}`);
    for (const file of Object.values(data.files || {})) append(file);
    await refresh();
  } catch (e) { append(`start error: ${e.message}`); }
}

async function stopLog() {
  try {
    const data = await api('/api/stop', {method: 'POST', body: '{}'});
    if (data.ok === false) throw new Error(data.error || 'stop failed');
    append(`stopped: ${data.message}`);
    for (const file of Object.values(data.files || {})) append(file);
    await refresh();
  } catch (e) { append(`stop error: ${e.message}`); }
}

async function refresh() {
  try {
    const data = await api('/api/status');
    const profiles = data.profiles || {};
    const m = profiles.mcan || {};
    const c = profiles.ccan || {};
    $('mFrames').textContent = m.frames || 0;
    $('cFrames').textContent = c.frames || 0;
    const usb = data.usb || {};
    const usbLines = [];
    if (usb.error) usbLines.push(`scan error: ${usb.error}`);
    for (const dev of (usb.devices || [])) {
      usbLines.push(`dev${dev.index}: ${dev.serial || 'gs_usb'} bus=${dev.bus} addr=${dev.address} channels=${dev.channels ?? '?'} icount_raw=${dev.icount} clock=${dev.fclkCan}`);
    }
    if (!usbLines.length) usbLines.push(data.running ? 'in use by recorder' : 'GS-USB not visible');
    $('usb').textContent = usbLines.join('\n');
    const lines = [];
    lines.push(`state: ${data.running ? 'recording' : 'stopped'} ${data.mode || ''}`);
    lines.push(`device: ${data.device || '-'}`);
    lines.push(`status: ${data.status || '-'}`);
    if (data.error) lines.push(`error: ${data.error}`);
    if (data.warning) lines.push(`warning: ${data.warning}`);
    for (const [key, profile] of Object.entries(profiles)) {
      const last = profile.lastId ? ` last=${profile.lastId} ${profile.lastData || ''}` : '';
      lines.push(`${key}: dev${profile.deviceIndex ?? '-'} ch${profile.channel ?? '-'} ${profile.bitrate || '-'}bit frames=${profile.frames || 0}${last}`);
    }
    for (const [key, file] of Object.entries(data.files || {})) lines.push(`${key}: ${file}`);
    for (const line of (data.log || []).slice(-8)) lines.push(line);
    $('status').textContent = lines.join('\n');
  } catch (e) { append(`status error: ${e.message}`); }
}

refresh();
setInterval(refresh, 1000);
</script>
</body>
</html>
"""


def ensure_libusb_backend():
    try:
        import libusb_package
        import usb.backend.libusb1
    except Exception:
        return None
    backend = libusb_package.get_libusb1_backend()
    if backend is not None:
        usb.backend.libusb1.get_backend = lambda *args, **kwargs: backend
    return backend


def slug(value):
    value = str(value or "").strip().lower()
    value = re.sub(r"[^a-z0-9а-яё._-]+", "_", value, flags=re.IGNORECASE)
    value = value.strip("._-")
    return value[:80] or "can_log"


def hex_bytes(data):
    return " ".join("%02X" % b for b in bytes(data or b""))


def describe_gs_device(dev, index=0):
    info = dev.device_info
    cap = dev.device_capability
    raw_icount = int(info.icount)
    channels = raw_icount + 1
    serial = dev.serial_number or "gs_usb"
    bus = getattr(dev, "bus", "?")
    address = getattr(dev, "address", "?")
    return {
        "index": int(index),
        "serial": str(serial),
        "bus": bus,
        "address": address,
        "icount": raw_icount,
        "channels": channels,
        "fclkCan": int(cap.fclk_can),
        "text": "%s dev%d bus=%s addr=%s channels=%s icount_raw=%s clock=%s" % (
            serial,
            int(index),
            bus,
            address,
            channels,
            raw_icount,
            int(cap.fclk_can),
        ),
    }


def scan_gs_usb_devices():
    try:
        ensure_libusb_backend()
        import gs_usb.gs_usb as gs_mod
        devices = []
        for index, dev in enumerate(gs_mod.GsUsb.scan()):
            devices.append(describe_gs_device(dev, index))
        return {"ok": True, "devices": devices, "error": ""}
    except Exception as exc:
        return {
            "ok": False,
            "devices": [],
            "error": "%s: %s" % (exc.__class__.__name__, exc),
        }


class GsCanLogger:
    def __init__(self):
        self.lock = threading.Lock()
        self.stop_event = None
        self.thread = None
        self.state = {
            "ok": True,
            "running": False,
            "mode": "",
            "label": "",
            "device": "",
            "status": "idle",
            "startedAt": 0.0,
            "stoppedAt": 0.0,
            "profiles": {},
            "files": {},
            "error": "",
            "log": [],
            "usb": {"ok": True, "devices": [], "error": ""},
        }

    def snapshot(self):
        with self.lock:
            snap = dict(self.state)
            snap["profiles"] = {k: dict(v) for k, v in self.state.get("profiles", {}).items()}
            snap["files"] = dict(self.state.get("files", {}))
            snap["log"] = list(self.state.get("log", []))
            snap["usb"] = dict(self.state.get("usb", {"ok": True, "devices": [], "error": ""}))
        if not snap.get("running"):
            scan = scan_gs_usb_devices()
            snap["usb"] = scan
            if scan.get("devices"):
                snap["device"] = ", ".join(item.get("text", "") for item in scan["devices"])
        if snap.get("running"):
            total = sum(int(item.get("frames", 0)) for item in snap.get("profiles", {}).values())
            if total == 0 and time.time() - float(snap.get("startedAt") or 0) > 2.0:
                snap["warning"] = "GS-USB is open, but 0 CAN frames received"
        return snap

    def log(self, line):
        with self.lock:
            self.state["status"] = str(line)
            items = self.state.setdefault("log", [])
            items.append("[%s] %s" % (time.strftime("%H:%M:%S"), line))
            if len(items) > 200:
                del items[:-200]

    def start(self, mode, label):
        mode = str(mode or "mcan").lower()
        if mode == "both":
            keys = ["mcan", "ccan"]
        elif mode in CAN_PROFILES:
            keys = [mode]
        else:
            raise ValueError("mode must be mcan, ccan, or both")

        self.stop(wait=True)
        os.makedirs(LOG_ROOT, exist_ok=True)
        clean_label = slug(label)
        stamp = time.strftime("%Y%m%d_%H%M%S")
        profiles = {}
        files = {}
        for key in keys:
            profile = dict(CAN_PROFILES[key])
            bitrate = int(profile["bitrate"])
            preferred_channel = int(profile["preferred_channel"])
            path = os.path.join(LOG_ROOT, "%s_%s_%dk_%s.txt" % (
                clean_label,
                key,
                bitrate // 1000,
                stamp,
            ))
            files[key] = path
            profiles[key] = {
                "label": profile["label"],
                "preferredChannel": preferred_channel,
                "channel": preferred_channel,
                "deviceIndex": None,
                "device": "",
                "bitrate": bitrate,
                "frames": 0,
                "bytes": 0,
                "lastId": "",
                "lastData": "",
                "lastAt": 0.0,
                "file": path,
            }

        self.stop_event = threading.Event()
        with self.lock:
            self.state.update({
                "ok": True,
                "running": True,
                "mode": mode,
                "label": clean_label,
                "device": "",
                "status": "starting " + mode,
                "startedAt": time.time(),
                "stoppedAt": 0.0,
                "profiles": profiles,
                "files": files,
                "error": "",
                "log": [],
                "usb": {"ok": True, "devices": [], "error": ""},
            })
        self.thread = threading.Thread(target=self._run, args=(keys, profiles, clean_label, self.stop_event), daemon=True)
        self.thread.start()
        time.sleep(0.2)
        return self.snapshot()

    def stop(self, wait=False):
        if self.stop_event is not None:
            self.stop_event.set()
        thread = self.thread
        if wait and thread and thread.is_alive():
            thread.join(timeout=5.0)
        if thread and thread.is_alive():
            with self.lock:
                self.state["status"] = "stopping previous reader"
            thread.join(timeout=5.0)
        with self.lock:
            if self.state.get("running"):
                self.state["running"] = False
                self.state["stoppedAt"] = time.time()
                if not self.state.get("error"):
                    self.state["status"] = "stopped"
        return self.snapshot()

    def _set_error(self, exc):
        message = "%s: %s" % (exc.__class__.__name__, exc)
        with self.lock:
            self.state["ok"] = False
            self.state["running"] = False
            self.state["stoppedAt"] = time.time()
            self.state["error"] = message
        self.log("error: " + message)

    @staticmethod
    def _assign_profiles(devs, keys, profiles):
        devices = []
        for index, dev in enumerate(devs):
            item = describe_gs_device(dev, index)
            if int(item["channels"]) > 0:
                devices.append((index, dev, item))
        if not devices:
            raise RuntimeError("GS-USB device not visible")

        if len(keys) == 1:
            key = keys[0]
            preferred = int(profiles[key]["preferredChannel"])
            selected = None
            for index, dev, item in devices:
                if int(item["channels"]) > preferred:
                    selected = (index, dev, item, preferred)
                    break
            if selected is None:
                index, dev, item = devices[0]
                selected = (index, dev, item, 0)
            index, dev, item, channel = selected
            profiles[key]["deviceIndex"] = index
            profiles[key]["device"] = item["text"]
            profiles[key]["channel"] = int(channel)
            if int(channel) != preferred:
                profiles[key]["note"] = "single-channel adapter: connect %s to ch0" % profiles[key]["label"]
            return {index: dev}

        preferred_required = max(int(profiles[key]["preferredChannel"]) for key in keys) + 1
        for index, dev, item in devices:
            if int(item["channels"]) >= preferred_required:
                for key in keys:
                    profiles[key]["deviceIndex"] = index
                    profiles[key]["device"] = item["text"]
                    profiles[key]["channel"] = int(profiles[key]["preferredChannel"])
                return {index: dev}

        if len(devices) >= len(keys):
            active = {}
            for key, (index, dev, item) in zip(keys, devices):
                profiles[key]["deviceIndex"] = index
                profiles[key]["device"] = item["text"]
                profiles[key]["channel"] = 0
                if int(profiles[key]["preferredChannel"]) != 0:
                    profiles[key]["note"] = "mapped to ch0 on a separate one-channel adapter"
                active[index] = dev
            return active

        available = ", ".join("dev%d:%sch" % (item["index"], item["channels"]) for _, _, item in devices)
        raise RuntimeError(
            "GS-USB exposes %s, need one 2-channel device or two 1-channel devices for both" % available
        )

    def _run(self, keys, profiles, label, stop_event):
        active_devs = {}
        files = {}
        try:
            ensure_libusb_backend()
            import gs_usb.gs_usb as gs_mod
            from gs_usb.gs_usb_frame import GsUsbFrame
            from gs_usb.gs_usb_structures import DeviceMode

            devs = gs_mod.GsUsb.scan()
            if not devs:
                raise RuntimeError("GS-USB device not visible")
            active_devs = self._assign_profiles(devs, keys, profiles)
            device_text = ", ".join(describe_gs_device(dev, index)["text"] for index, dev in active_devs.items())
            with self.lock:
                self.state["device"] = device_text
                self.state["usb"] = {
                    "ok": True,
                    "devices": [describe_gs_device(dev, index) for index, dev in active_devs.items()],
                    "error": "",
                }

            channels_by_device = {}
            for key in keys:
                profile = profiles[key]
                channels_by_device.setdefault(int(profile["deviceIndex"]), set()).add(int(profile["channel"]))
            for index, channels in channels_by_device.items():
                self._reset_channels(active_devs[index], channels, gs_mod, DeviceMode)
            for key in keys:
                profile = profiles[key]
                dev = active_devs[int(profile["deviceIndex"])]
                self._configure_channel(dev, profile["channel"], profile["bitrate"], gs_mod)
            for key in keys:
                profile = profiles[key]
                dev = active_devs[int(profile["deviceIndex"])]
                dev.gs_usb.ctrl_transfer(
                    0x41,
                    gs_mod._GS_USB_BREQ_MODE,
                    int(profile["channel"]),
                    0,
                    DeviceMode(gs_mod.GS_CAN_MODE_START, 0).pack(),
                )
                dev.device_flags = 0

            for key in keys:
                profile = profiles[key]
                fh = open(profile["file"], "w", encoding="utf-8")
                files[key] = fh
                fh.write("# KIA GS-USB CAN LOG\n")
                fh.write("# NAME %s\n" % label)
                fh.write("# PROFILE %s\n" % key)
                fh.write("# LABEL %s\n" % profile["label"])
                fh.write("# DEVICE_INDEX %s\n" % profile["deviceIndex"])
                fh.write("# CHANNEL %s\n" % profile["channel"])
                fh.write("# PREFERRED_CHANNEL %s\n" % profile["preferredChannel"])
                fh.write("# BITRATE %s\n" % profile["bitrate"])
                fh.write("# DEVICE %s\n" % profile["device"])
                if profile.get("note"):
                    fh.write("# NOTE %s\n" % profile["note"])
                fh.write("# START %s\n" % time.strftime("%Y-%m-%d %H:%M:%S %z"))
                fh.write("# FORMAT ts devN chN STD|EXT can_id dlc=N datahex\n")
                fh.flush()
                self.log("%s -> %s" % (profile["label"], profile["file"]))

            frames = {index: GsUsbFrame() for index in active_devs}
            while not stop_event.is_set():
                for dev_index, dev in active_devs.items():
                    frame = frames[dev_index]
                    if not dev.read(frame, 50):
                        continue
                    channel = int(getattr(frame, "channel", 0))
                    target_key = None
                    for key in keys:
                        if int(profiles[key]["deviceIndex"]) == int(dev_index) and int(profiles[key]["channel"]) == channel:
                            target_key = key
                            break
                    if target_key is None:
                        continue
                    profile = profiles[target_key]
                    data_hex = "".join("%02X" % b for b in frame.data[:frame.can_dlc])
                    line = "%.6f dev%d ch%d %s %08X dlc=%d %s\n" % (
                        frame.timestamp if frame.timestamp else time.time(),
                        int(dev_index),
                        channel,
                        "EXT" if frame.is_extended_id else "STD",
                        frame.arbitration_id,
                        frame.can_dlc,
                        data_hex,
                    )
                    files[target_key].write(line)
                    with self.lock:
                        state_profile = self.state.get("profiles", {}).get(target_key)
                        if state_profile is not None:
                            state_profile["frames"] = int(state_profile.get("frames", 0)) + 1
                            state_profile["bytes"] = int(state_profile.get("bytes", 0)) + frame.can_dlc
                            state_profile["lastId"] = "0x%03X" % frame.arbitration_id
                            state_profile["lastData"] = data_hex
                            state_profile["lastAt"] = time.time()
                    if int(time.time() * 10) % 10 == 0:
                        for fh in files.values():
                            fh.flush()
        except Exception as exc:
            self._set_error(exc)
        finally:
            for fh in files.values():
                try:
                    fh.write("# STOP %s\n" % time.strftime("%Y-%m-%d %H:%M:%S %z"))
                    fh.flush()
                    fh.close()
                except Exception:
                    pass
            for index, dev in active_devs.items():
                try:
                    channels = []
                    for key in keys:
                        device_index = profiles[key].get("deviceIndex")
                        if device_index is not None and int(device_index) == int(index):
                            channels.append(profiles[key]["channel"])
                    self._reset_channels(dev, channels, None, None)
                except Exception:
                    pass
            with self.lock:
                if self.state.get("running"):
                    self.state["running"] = False
                    self.state["stoppedAt"] = time.time()
                    if not self.state.get("error"):
                        self.state["status"] = "stopped"

    @staticmethod
    def _reset_channels(dev, channels, gs_mod=None, DeviceMode=None):
        if gs_mod is None:
            import gs_usb.gs_usb as gs_mod
        if DeviceMode is None:
            from gs_usb.gs_usb_structures import DeviceMode
        for channel in sorted(set(int(ch) for ch in channels)):
            try:
                dev.gs_usb.ctrl_transfer(
                    0x41,
                    gs_mod._GS_USB_BREQ_MODE,
                    channel,
                    0,
                    DeviceMode(gs_mod.GS_CAN_MODE_RESET, 0).pack(),
                )
            except Exception:
                pass

    @staticmethod
    def _configure_channel(dev, channel, bitrate, gs_mod):
        clock = int(dev.device_capability.fclk_can)
        if clock == 36000000 and bitrate in {100000, 250000, 500000}:
            brp = clock // (int(bitrate) * 18)
            from gs_usb.gs_usb_structures import DeviceBitTiming
            timing = DeviceBitTiming(1, 14, 2, 1, brp)
            dev.gs_usb.ctrl_transfer(0x41, gs_mod._GS_USB_BREQ_BITTIMING, int(channel), 0, timing.pack())
            return
        if clock == 48000000 and bitrate in {100000, 500000}:
            brp = 30 if bitrate == 100000 else 6
            from gs_usb.gs_usb_structures import DeviceBitTiming
            timing = DeviceBitTiming(1, 12, 2, 1, brp)
            dev.gs_usb.ctrl_transfer(0x41, gs_mod._GS_USB_BREQ_BITTIMING, int(channel), 0, timing.pack())
            return
        if int(channel) == 0 and dev.set_bitrate(int(bitrate)):
            return
        raise RuntimeError("unsupported bitrate %s on channel %s, clock %s" % (bitrate, channel, clock))


LOGGER = GsCanLogger()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)

    def _send(self, status, payload, content_type="application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self._send(204, b"")

    def do_GET(self):
        try:
            if self.path == "/" or self.path.startswith("/index"):
                self._send(200, HTML.encode("utf-8"), "text/html; charset=utf-8")
            elif self.path.startswith("/api/status"):
                self._send(200, LOGGER.snapshot())
            elif self.path.startswith("/api/devices"):
                self._send(200, scan_gs_usb_devices())
            else:
                self._send(404, {"ok": False, "error": "not found"})
        except Exception as exc:
            self._send(500, {"ok": False, "error": str(exc)})

    def do_POST(self):
        try:
            body = self.rfile.read(int(self.headers.get("Content-Length", "0") or "0"))
            data = json.loads(body.decode("utf-8") or "{}")
            if self.path.startswith("/api/start"):
                snap = LOGGER.start(data.get("mode"), data.get("label"))
                ok = not bool(snap.get("error"))
                self._send(200 if ok else 409, {
                    "ok": ok,
                    "mode": snap.get("mode"),
                    "files": snap.get("files", {}),
                    "error": snap.get("error", ""),
                    "status": snap.get("status", ""),
                })
            elif self.path.startswith("/api/stop"):
                snap = LOGGER.stop(wait=True)
                self._send(200, {"ok": True, "message": snap.get("status", "stopped"), "files": snap.get("files", {})})
            else:
                self._send(404, {"ok": False, "error": "not found"})
        except Exception as exc:
            self._send(500, {"ok": False, "error": str(exc)})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8791)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"GS CAN logger listening on http://{args.host}:{args.port}/", flush=True)
    try:
        server.serve_forever()
    finally:
        LOGGER.stop(wait=True)


if __name__ == "__main__":
    main()
