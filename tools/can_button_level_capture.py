#!/usr/bin/env python3
import argparse
import json
import threading
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from gs_can_logger_server import CAN_PROFILES, GsCanLogger, ensure_libusb_backend, slug


LOG_ROOT = Path("/Users/legion/Downloads/canbus/logs/button_levels")

BUTTONS = {
    "wheel": {
        "name": "обогрев руля",
        "kind": "binary",
        "states": ["on", "off"],
    },
    "driver_heat": {
        "name": "обогрев водитель",
        "kind": "levels",
        "states": ["level1", "level2", "level3", "off"],
    },
    "driver_vent": {
        "name": "обдув водитель",
        "kind": "levels",
        "states": ["level1", "level2", "level3", "off"],
    },
    "pass_heat": {
        "name": "обогрев пассажир",
        "kind": "levels",
        "states": ["level1", "level2", "level3", "off"],
    },
    "pass_vent": {
        "name": "обдув пассажир",
        "kind": "levels",
        "states": ["level1", "level2", "level3", "off"],
    },
}

STATE_RU = {
    "off": "OFF",
    "on": "ON",
    "level1": "уровень 1",
    "level2": "уровень 2",
    "level3": "уровень 3",
}


@dataclass(frozen=True)
class Frame:
    wall_ts: float
    bus: str
    dev_index: int
    channel: int
    extended: bool
    can_id: int
    dlc: int
    data: bytes
    data_hex: str


@dataclass(frozen=True)
class Field:
    name: str
    start: int
    width: int


def make_fields():
    fields = []
    for byte in range(8):
        fields.append(Field(f"b{byte}", byte * 8, 8))
        fields.append(Field(f"b{byte}.lo", byte * 8, 4))
        fields.append(Field(f"b{byte}.hi", byte * 8 + 4, 4))
        for bit in range(8):
            fields.append(Field(f"b{byte}.bit{bit}", byte * 8 + bit, 1))
        for bit in range(7):
            fields.append(Field(f"b{byte}.bits{bit}-{bit + 1}", byte * 8 + bit, 2))
        for bit in range(6):
            fields.append(Field(f"b{byte}.bits{bit}-{bit + 2}", byte * 8 + bit, 3))
    return fields


FIELDS = make_fields()


def field_value(data, field):
    value = 0
    for index in range(field.width):
        bit_index = field.start + index
        byte_index = bit_index // 8
        if byte_index >= len(data):
            return None
        if data[byte_index] & (1 << (bit_index % 8)):
            value |= 1 << index
    return value


class DirectCapture:
    def __init__(self, mode, label, out_dir):
        self.mode = mode
        self.label = label
        self.out_dir = Path(out_dir)
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.file_lock = threading.Lock()
        self.frames = []
        self.files = {}
        self.file_handles = {}
        self.profiles = {}
        self.thread = None
        self.error = ""

    def start(self):
        self.out_dir.mkdir(parents=True, exist_ok=True)
        keys = self._mode_keys()
        stamp = time.strftime("%Y%m%d_%H%M%S")
        clean_label = slug(self.label)
        self.profiles = {}
        self.files = {}
        for key in keys:
            profile = dict(CAN_PROFILES[key])
            bitrate = int(profile["bitrate"])
            path = self.out_dir / f"{clean_label}_{key}_{bitrate // 1000}k_{stamp}.txt"
            self.files[key] = str(path)
            self.profiles[key] = {
                "label": profile["label"],
                "preferredChannel": int(profile["preferred_channel"]),
                "channel": int(profile["preferred_channel"]),
                "deviceIndex": None,
                "device": "",
                "bitrate": bitrate,
                "file": str(path),
                "frames": 0,
            }
        self.thread = threading.Thread(target=self._run, args=(keys,), daemon=True)
        self.thread.start()
        time.sleep(0.4)
        if self.error:
            raise RuntimeError(self.error)

    def stop(self):
        self.stop_event.set()
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=5.0)

    def mark(self, text):
        line = f"# MARK {time.time():.6f} {text}\n"
        with self.file_lock:
            for handle in self.file_handles.values():
                handle.write(line)
                handle.flush()

    def snapshot_frames(self):
        with self.lock:
            return list(self.frames)

    def _mode_keys(self):
        mode = self.mode.lower()
        if mode == "both":
            return ["mcan", "ccan"]
        if mode in CAN_PROFILES:
            return [mode]
        raise ValueError("mode must be ccan, mcan, or both")

    def _run(self, keys):
        active_devs = {}
        try:
            ensure_libusb_backend()
            import gs_usb.gs_usb as gs_mod
            from gs_usb.gs_usb_frame import GsUsbFrame
            from gs_usb.gs_usb_structures import DeviceMode

            devs = gs_mod.GsUsb.scan()
            if not devs:
                raise RuntimeError("GS-USB device not visible")
            active_devs = GsCanLogger._assign_profiles(devs, keys, self.profiles)

            channels_by_device = defaultdict(set)
            for key in keys:
                profile = self.profiles[key]
                channels_by_device[int(profile["deviceIndex"])].add(int(profile["channel"]))
            for dev_index, channels in channels_by_device.items():
                GsCanLogger._reset_channels(active_devs[dev_index], channels, gs_mod, DeviceMode)
            for key in keys:
                profile = self.profiles[key]
                dev = active_devs[int(profile["deviceIndex"])]
                GsCanLogger._configure_channel(dev, profile["channel"], profile["bitrate"], gs_mod)
            for key in keys:
                profile = self.profiles[key]
                dev = active_devs[int(profile["deviceIndex"])]
                dev.gs_usb.ctrl_transfer(
                    0x41,
                    gs_mod._GS_USB_BREQ_MODE,
                    int(profile["channel"]),
                    0,
                    DeviceMode(gs_mod.GS_CAN_MODE_START, 0).pack(),
                )
                dev.device_flags = 0

            with self.file_lock:
                for key in keys:
                    profile = self.profiles[key]
                    handle = open(profile["file"], "w", encoding="utf-8")
                    self.file_handles[key] = handle
                    handle.write("# KIA GS-USB CAN BUTTON LEVEL CAPTURE\n")
                    handle.write(f"# NAME {self.label}\n")
                    handle.write(f"# PROFILE {key}\n")
                    handle.write(f"# LABEL {profile['label']}\n")
                    handle.write(f"# DEVICE_INDEX {profile['deviceIndex']}\n")
                    handle.write(f"# CHANNEL {profile['channel']}\n")
                    handle.write(f"# PREFERRED_CHANNEL {profile['preferredChannel']}\n")
                    handle.write(f"# BITRATE {profile['bitrate']}\n")
                    handle.write(f"# DEVICE {profile['device']}\n")
                    if profile.get("note"):
                        handle.write(f"# NOTE {profile['note']}\n")
                    handle.write(f"# START {time.strftime('%Y-%m-%d %H:%M:%S %z')}\n")
                    handle.write("# FORMAT ts devN chN STD|EXT can_id dlc=N datahex\n")
                    handle.flush()

            frames = {index: GsUsbFrame() for index in active_devs}
            while not self.stop_event.is_set():
                for dev_index, dev in active_devs.items():
                    frame = frames[dev_index]
                    if not dev.read(frame, 50):
                        continue
                    channel = int(getattr(frame, "channel", 0))
                    target_key = None
                    for key in keys:
                        profile = self.profiles[key]
                        if int(profile["deviceIndex"]) == int(dev_index) and int(profile["channel"]) == channel:
                            target_key = key
                            break
                    if target_key is None:
                        continue
                    wall_ts = time.time()
                    data = bytes(frame.data[:frame.can_dlc])
                    data_hex = "".join("%02X" % b for b in data)
                    item = Frame(
                        wall_ts=wall_ts,
                        bus=target_key,
                        dev_index=int(dev_index),
                        channel=channel,
                        extended=bool(frame.is_extended_id),
                        can_id=int(frame.arbitration_id),
                        dlc=int(frame.can_dlc),
                        data=data,
                        data_hex=data_hex,
                    )
                    with self.lock:
                        self.frames.append(item)
                    self.profiles[target_key]["frames"] += 1
                    line = "%.6f dev%d ch%d %s %08X dlc=%d %s\n" % (
                        frame.timestamp if frame.timestamp else wall_ts,
                        int(dev_index),
                        channel,
                        "EXT" if frame.is_extended_id else "STD",
                        frame.arbitration_id,
                        frame.can_dlc,
                        data_hex,
                    )
                    with self.file_lock:
                        self.file_handles[target_key].write(line)
        except Exception as exc:
            self.error = f"{exc.__class__.__name__}: {exc}"
        finally:
            with self.file_lock:
                for handle in self.file_handles.values():
                    try:
                        handle.write(f"# STOP {time.strftime('%Y-%m-%d %H:%M:%S %z')}\n")
                        handle.flush()
                        handle.close()
                    except Exception:
                        pass
            for index, dev in active_devs.items():
                try:
                    channels = []
                    for key in keys:
                        profile = self.profiles[key]
                        if profile.get("deviceIndex") is not None and int(profile["deviceIndex"]) == int(index):
                            channels.append(profile["channel"])
                    GsCanLogger._reset_channels(dev, channels, None, None)
                except Exception:
                    pass


def build_button_plan(button_key, cycles):
    button = BUTTONS[button_key]
    steps = [
        {
            "state": "off",
            "click": False,
            "title": "baseline OFF",
            "instruction": f"{button['name']}: поставь OFF, ничего не нажимай, подожди секунду и нажми Enter.",
        }
    ]
    for cycle in range(1, cycles + 1):
        for state in button["states"]:
            steps.append(
                {
                    "state": state,
                    "click": True,
                    "title": f"cycle {cycle} -> {STATE_RU[state]}",
                    "instruction": (
                        f"{button['name']}: нажми кнопку 1 раз -> {STATE_RU[state]}, "
                        "подожди 1 секунду и нажми Enter."
                    ),
                }
            )
    return steps


def frames_between(frames, start_ts, end_ts):
    return [frame for frame in frames if start_ts <= frame.wall_ts <= end_ts]


def frames_for_tail(frames, segment, tail_seconds):
    end_ts = segment["end_wall"]
    start_ts = max(segment["start_wall"], end_ts - tail_seconds)
    return frames_between(frames, start_ts, end_ts)


def payload_sets(frames):
    values = defaultdict(set)
    for frame in frames:
        values[(frame.bus, frame.can_id)].add(frame.data_hex)
    return values


def print_live_diff(frames, previous_segment, current_segment, tail_seconds, limit):
    if previous_segment is None:
        return
    prev_values = payload_sets(frames_for_tail(frames, previous_segment, tail_seconds))
    curr_values = payload_sets(frames_for_tail(frames, current_segment, tail_seconds))
    rows = []
    for key in sorted(set(prev_values) | set(curr_values)):
        before = prev_values.get(key, set())
        after = curr_values.get(key, set())
        if before == after:
            continue
        if len(before) > 6 or len(after) > 6:
            continue
        rows.append((key, before, after))
    if not rows:
        print("  tail diff: чистых малошумных изменений нет")
        return
    print("  tail diff:")
    for (bus, can_id), before, after in rows[:limit]:
        before_s = " / ".join(sorted(before)) if before else "-"
        after_s = " / ".join(sorted(after)) if after else "-"
        print(f"    {bus.upper()} 0x{can_id:03X}: {before_s} -> {after_s}")
    if len(rows) > limit:
        print(f"    ... ещё {len(rows) - limit}")


def dominant_by_segment(frames, segment, tail_seconds):
    tail = frames_for_tail(frames, segment, tail_seconds)
    values = defaultdict(Counter)
    examples = defaultdict(Counter)
    for frame in tail:
        key_base = (frame.bus, frame.can_id)
        examples[key_base][frame.data_hex] += 1
        for field in FIELDS:
            value = field_value(frame.data, field)
            if value is None:
                continue
            values[(frame.bus, frame.can_id, field.name)][value] += 1
    result = {}
    for key, counter in values.items():
        value, count = counter.most_common(1)[0]
        total = sum(counter.values())
        result[key] = {
            "value": value,
            "count": count,
            "total": total,
            "ratio": count / total if total else 0.0,
            "unique": len(counter),
        }
    return result, examples


def analyze_persistent_candidates(frames, segments, tail_seconds, min_ratio):
    by_button = defaultdict(list)
    for segment in segments:
        by_button[segment["button"]].append(segment)

    result = {}
    for button_key, button_segments in by_button.items():
        button = BUTTONS[button_key]
        required_states = ["off", "on"] if button["kind"] == "binary" else ["off", "level1", "level2", "level3"]
        segment_values = {}
        examples_by_segment = {}
        for segment in button_segments:
            values, examples = dominant_by_segment(frames, segment, tail_seconds)
            segment_values[segment["id"]] = values
            examples_by_segment[segment["id"]] = examples

        all_keys = set()
        for values in segment_values.values():
            all_keys.update(values.keys())

        candidates = []
        for key in sorted(all_keys):
            state_values = {}
            state_ratios = defaultdict(list)
            state_examples = defaultdict(Counter)
            ok = True
            for state in required_states:
                state_segments = [segment for segment in button_segments if segment["state"] == state]
                seen = []
                for segment in state_segments:
                    item = segment_values[segment["id"]].get(key)
                    if item is None:
                        continue
                    if item["ratio"] < min_ratio:
                        ok = False
                        break
                    seen.append(item["value"])
                    state_ratios[state].append(item["ratio"])
                    base_key = (key[0], key[1])
                    state_examples[state].update(examples_by_segment[segment["id"]].get(base_key, Counter()))
                if not ok:
                    break
                if not seen:
                    ok = False
                    break
                if len(set(seen)) != 1:
                    ok = False
                    break
                state_values[state] = seen[0]
            if not ok:
                continue
            if len(set(state_values.values())) != len(required_states):
                continue
            avg_ratio = sum(sum(items) for items in state_ratios.values()) / sum(len(items) for items in state_ratios.values())
            candidates.append(
                {
                    "bus": key[0],
                    "can_id": f"0x{key[1]:03X}",
                    "field": key[2],
                    "values": {state: f"0x{value:X}" for state, value in state_values.items()},
                    "avg_ratio": round(avg_ratio, 3),
                    "examples": {
                        state: [payload for payload, _ in state_examples[state].most_common(5)]
                        for state in required_states
                    },
                }
            )
        candidates.sort(key=lambda item: (-item["avg_ratio"], item["bus"], item["can_id"], item["field"]))
        result[button_key] = candidates[:30]
    return result


def analyze_event_payloads(frames, segments, limit=20):
    by_button = defaultdict(list)
    for segment in segments:
        by_button[segment["button"]].append(segment)

    output = {}
    for button_key, button_segments in by_button.items():
        baseline_segments = [segment for segment in button_segments if not segment["click"]]
        baseline_payloads = defaultdict(Counter)
        for segment in baseline_segments:
            for frame in frames_between(frames, segment["start_wall"], segment["end_wall"]):
                baseline_payloads[(frame.bus, frame.can_id)][frame.data_hex] += 1

        rows = []
        for segment in button_segments:
            if not segment["click"]:
                continue
            payloads = defaultdict(Counter)
            for frame in frames_between(frames, segment["start_wall"], segment["end_wall"]):
                key = (frame.bus, frame.can_id)
                if frame.data_hex not in baseline_payloads.get(key, Counter()):
                    payloads[key][frame.data_hex] += 1
            for (bus, can_id), counter in payloads.items():
                if len(counter) > 8:
                    continue
                rows.append(
                    {
                        "segment": segment["id"],
                        "state": segment["state"],
                        "bus": bus,
                        "can_id": f"0x{can_id:03X}",
                        "payloads": [payload for payload, _ in counter.most_common(8)],
                        "frames": sum(counter.values()),
                    }
                )
        rows.sort(key=lambda item: (-item["frames"], item["bus"], item["can_id"], item["segment"]))
        output[button_key] = rows[:limit]
    return output


def write_report(out_dir, meta, segments, profiles, frames, persistent, events):
    json_path = Path(out_dir) / "button_level_capture_summary.json"
    md_path = Path(out_dir) / "button_level_capture_summary.md"
    raw = {
        "meta": meta,
        "profiles": profiles,
        "raw_files": {key: profile["file"] for key, profile in profiles.items()},
        "segments": segments,
        "frame_count": len(frames),
        "persistent_candidates": persistent,
        "event_payload_candidates": events,
    }
    json_path.write_text(json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = []
    lines.append("# CAN button level capture")
    lines.append("")
    lines.append(f"- started: {meta['started']}")
    lines.append(f"- mode: {meta['mode']}")
    lines.append(f"- frames: {len(frames)}")
    lines.append("")
    lines.append("## Raw files")
    for key, profile in profiles.items():
        lines.append(f"- {key}: `{profile['file']}`")
    lines.append("")
    lines.append("## Persistent level candidates")
    for button_key in BUTTONS:
        if button_key not in persistent:
            continue
        lines.append(f"### {BUTTONS[button_key]['name']}")
        candidates = persistent[button_key]
        if not candidates:
            lines.append("- no stable per-level field found")
            lines.append("")
            continue
        lines.append("| bus | id | field | values | ratio | examples |")
        lines.append("|---|---:|---|---|---:|---|")
        for item in candidates[:12]:
            values = ", ".join(f"{state}={value}" for state, value in item["values"].items())
            examples = "; ".join(
                f"{state}:{'/'.join(payloads[:2])}" for state, payloads in item["examples"].items() if payloads
            )
            lines.append(f"| {item['bus']} | {item['can_id']} | {item['field']} | {values} | {item['avg_ratio']} | {examples} |")
        lines.append("")
    lines.append("## Event payload candidates")
    for button_key in BUTTONS:
        if button_key not in events:
            continue
        lines.append(f"### {BUTTONS[button_key]['name']}")
        items = events[button_key]
        if not items:
            lines.append("- no low-noise event payloads outside baseline")
            lines.append("")
            continue
        lines.append("| segment | state | bus | id | payloads | frames |")
        lines.append("|---|---|---|---:|---|---:|")
        for item in items[:20]:
            payloads = " / ".join(item["payloads"])
            lines.append(f"| {item['segment']} | {item['state']} | {item['bus']} | {item['can_id']} | {payloads} | {item['frames']} |")
        lines.append("")
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, md_path


def parse_buttons(value):
    if value == "all":
        return list(BUTTONS.keys())
    items = [item.strip() for item in value.split(",") if item.strip()]
    unknown = [item for item in items if item not in BUTTONS]
    if unknown:
        raise ValueError("unknown button(s): " + ", ".join(unknown))
    return items


def main():
    parser = argparse.ArgumentParser(
        description="Interactive GS-USB capture for KIA climate button levels."
    )
    parser.add_argument("--mode", choices=["ccan", "mcan", "both"], default="ccan")
    parser.add_argument("--buttons", default="all", help="all or comma list: " + ",".join(BUTTONS.keys()))
    parser.add_argument("--cycles", type=int, default=3, help="repeat full button cycle this many times")
    parser.add_argument("--stable-tail", type=float, default=1.0, help="seconds at segment end used for stable-state analysis")
    parser.add_argument("--post-enter", type=float, default=0.25, help="extra capture seconds after Enter")
    parser.add_argument("--min-ratio", type=float, default=0.85, help="dominant field ratio for persistent candidate")
    parser.add_argument("--out", default=str(LOG_ROOT))
    parser.add_argument("--label", default="kia_climate_buttons")
    args = parser.parse_args()

    buttons = parse_buttons(args.buttons)
    session_dir = Path(args.out) / time.strftime("%Y%m%d_%H%M%S")
    capture = DirectCapture(args.mode, args.label, session_dir)

    print("GS-USB capture start")
    print(f"mode={args.mode} out={session_dir}")
    print("Перед каждым шагом: нажал кнопку, дождался реакции панели, потом Enter.")
    capture.start()
    print("Raw files:")
    for key, profile in capture.profiles.items():
        print(f"  {key}: {profile['file']}")

    started = time.strftime("%Y-%m-%d %H:%M:%S %z")
    segments = []
    interrupted = False
    try:
        for button_key in buttons:
            button = BUTTONS[button_key]
            print("")
            print("=" * 72)
            print(f"{button['name']}")
            print("Если состояние неизвестно, прокрути кнопку до OFF перед baseline.")
            previous_segment = None
            for index, step in enumerate(build_button_plan(button_key, args.cycles), 1):
                segment_id = f"{button_key}_{index:02d}_{step['state']}"
                print("")
                print(f"[{segment_id}] {step['instruction']}")
                start_wall = time.time()
                capture.mark(f"START {segment_id} button={button_key} state={step['state']} click={int(step['click'])}")
                input("Enter после выполнения шага: ")
                if args.post_enter > 0:
                    time.sleep(args.post_enter)
                end_wall = time.time()
                capture.mark(f"END {segment_id}")
                frames = capture.snapshot_frames()
                segment = {
                    "id": segment_id,
                    "button": button_key,
                    "button_name": button["name"],
                    "state": step["state"],
                    "title": step["title"],
                    "click": bool(step["click"]),
                    "start_wall": start_wall,
                    "end_wall": end_wall,
                    "duration_sec": round(end_wall - start_wall, 3),
                    "frames": len(frames_between(frames, start_wall, end_wall)),
                }
                segments.append(segment)
                print(f"  frames={segment['frames']} duration={segment['duration_sec']}s")
                print_live_diff(frames, previous_segment, segment, args.stable_tail, 12)
                previous_segment = segment
    except KeyboardInterrupt:
        interrupted = True
        print("")
        print("Interrupted: stopping capture and writing partial report.")
    finally:
        capture.stop()

    frames = capture.snapshot_frames()
    persistent = analyze_persistent_candidates(frames, segments, args.stable_tail, args.min_ratio)
    events = analyze_event_payloads(frames, segments)
    meta = {
        "started": started,
        "finished": time.strftime("%Y-%m-%d %H:%M:%S %z"),
        "mode": args.mode,
        "label": args.label,
        "cycles": args.cycles,
        "stable_tail": args.stable_tail,
        "min_ratio": args.min_ratio,
        "interrupted": interrupted,
    }
    json_path, md_path = write_report(session_dir, meta, segments, capture.profiles, frames, persistent, events)

    print("")
    print("Done")
    print(f"summary json: {json_path}")
    print(f"summary md:   {md_path}")
    if capture.error:
        print(f"capture warning: {capture.error}")


if __name__ == "__main__":
    main()
