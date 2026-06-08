#!/usr/bin/env python3
import argparse
import json
import os
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


TRC_RE = re.compile(
    r"^\s*(?P<time>\d+[\.,]\d+)\s+"
    r"(?P<can_id>[0-9A-Fa-f]{2,4})\s+"
    r"(?P<dlc>\d+)\s+"
    r"(?P<data>(?:[0-9A-Fa-f]{2}\s*)+)"
)

WATCH_IDS = {"0CA", "0CB", "0CC", "115", "1CA", "122", "123", "1E7", "49B", "4E8"}
TEXT_IDS = {"49B", "4E8"}


@dataclass(frozen=True)
class Frame:
    path: str
    line_no: int
    raw_time: float
    time: float
    can_id: str
    dlc: int
    data: Tuple[int, ...]

    @property
    def hex_data(self) -> str:
        return " ".join(f"{b:02X}" for b in self.data)


def parse_trc(path: str) -> List[Frame]:
    frames: List[Frame] = []
    offset = 0.0
    prev_raw: Optional[float] = None

    with open(path, "r", encoding="ascii", errors="ignore") as fh:
        for line_no, line in enumerate(fh, 1):
            match = TRC_RE.match(line)
            if not match:
                continue

            raw_time = float(match.group("time").replace(",", "."))
            if prev_raw is not None and raw_time + 0.001 < prev_raw:
                # Typical Vector/CAN trace minute wrap. Keep a monotonic timeline.
                offset += 60.0
            prev_raw = raw_time

            data = tuple(int(part, 16) for part in match.group("data").split())
            frames.append(
                Frame(
                    path=path,
                    line_no=line_no,
                    raw_time=raw_time,
                    time=raw_time + offset,
                    can_id=match.group("can_id").upper().zfill(3),
                    dlc=int(match.group("dlc")),
                    data=data,
                )
            )

    return frames


def printable_score(text: str) -> int:
    return sum(ch.isprintable() and ch not in "\x00\ufffd" for ch in text)


def decode_candidates(payload: bytes) -> List[Tuple[str, str]]:
    candidates: List[Tuple[str, str]] = []
    # 49B strings often start with a one- or two-byte control marker before
    # real UTF-16 text: F4 00 + UTF-16LE coordinates, or F0 mixed into BE text.
    heuristic_blobs: List[Tuple[str, bytes]] = []
    if payload.startswith(b"\xF4\x00") and len(payload) > 2:
        heuristic_blobs.append(("utf-16le-control-f4", payload[2:]))
    if payload.startswith(b"\xF0") and len(payload) > 3:
        heuristic_blobs.append(("utf-16be-control-f0-as-04", b"\x04" + payload[1:]))
        heuristic_blobs.append(("utf-16be-control-f0-skip", payload[1:]))

    variants = [
        *heuristic_blobs,
        ("utf-16le", payload),
        ("utf-16le-skip1", payload[1:]),
        ("utf-16le-skip2", payload[2:]),
        ("utf-16be", payload),
        ("utf-16be-skip1", payload[1:]),
        ("utf-16be-skip2", payload[2:]),
        ("latin1", payload),
    ]
    for encoding, blob in variants:
        if not blob:
            continue
        actual_encoding = encoding.split("-control", 1)[0].split("-skip", 1)[0]
        if actual_encoding.startswith("utf-16") and len(blob) % 2:
            blob = blob[:-1]
        if not blob:
            continue
        try:
            text = blob.decode(actual_encoding, errors="replace")
        except LookupError:
            continue
        text = text.replace("\x00", "").strip("\x00 \t\r\n")
        text = "".join(ch if ch.isprintable() else " " for ch in text).strip()
        if text and printable_score(text) >= max(2, len(text) // 2):
            candidates.append((encoding, text))
    def rank(item: Tuple[str, str]) -> Tuple[int, int, int, int]:
        encoding, text = item
        utf_bonus = 4 if encoding.startswith("utf-16") else 0
        control_bonus = 6 if "-control-" in encoding else 0
        repair_bonus = 5 if "as-04" in encoding else 0
        bad_penalty = text.count("�") * 8
        return (control_bonus + repair_bonus + utf_bonus - bad_penalty, printable_score(text), len(text), -len(encoding))

    candidates.sort(key=rank, reverse=True)

    unique: List[Tuple[str, str]] = []
    seen = set()
    for item in candidates:
        if item[1] in seen:
            continue
        seen.add(item[1])
        unique.append(item)
    return unique[:3]


def collect_isotp_text(frames: Sequence[Frame]) -> List[Dict[str, object]]:
    results: List[Dict[str, object]] = []
    i = 0
    while i < len(frames):
        frame = frames[i]
        data = frame.data
        if frame.can_id not in TEXT_IDS or len(data) < 2:
            i += 1
            continue

        pci = data[0]
        payload = b""
        end_line = frame.line_no
        if pci >> 4 == 0x1:
            expected_len = ((pci & 0x0F) << 8) | data[1]
            payload = bytes(data[2:])
            seq = 1
            j = i + 1
            while j < len(frames) and len(payload) < expected_len:
                nxt = frames[j]
                if nxt.can_id != frame.can_id or not nxt.data or nxt.data[0] != (0x20 | (seq & 0x0F)):
                    # In these logs consecutive frames are close, but other IDs may be interleaved.
                    if nxt.time - frame.time > 1.5:
                        break
                    j += 1
                    continue
                payload += bytes(nxt.data[1:])
                end_line = nxt.line_no
                seq += 1
                j += 1
            payload = payload[:expected_len]
            i = j
        elif pci >> 4 == 0x0 and pci:
            expected_len = pci & 0x0F
            payload = bytes(data[1 : 1 + expected_len])
            i += 1
        else:
            i += 1
            continue

        payload = payload.rstrip(b"\xAA\x00")
        candidates = decode_candidates(payload)
        results.append(
            {
                "time": round(frame.time, 3),
                "id": frame.can_id,
                "line": frame.line_no,
                "end_line": end_line,
                "payload_hex": " ".join(f"{b:02X}" for b in payload),
                "decode": candidates,
            }
        )
    return results


def unique_by_id(frames: Sequence[Frame]) -> Dict[str, Counter]:
    by_id: Dict[str, Counter] = defaultdict(Counter)
    for frame in frames:
        by_id[frame.can_id][frame.hex_data] += 1
    return by_id


def changed_events(frames: Sequence[Frame], ids: Iterable[str] = WATCH_IDS) -> List[Dict[str, object]]:
    ids = set(ids)
    prev: Dict[str, str] = {}
    events: List[Dict[str, object]] = []
    for frame in frames:
        if frame.can_id not in ids:
            continue
        hex_data = frame.hex_data
        if prev.get(frame.can_id) == hex_data:
            continue
        prev[frame.can_id] = hex_data
        events.append(
            {
                "time": round(frame.time, 3),
                "line": frame.line_no,
                "id": frame.can_id,
                "data": hex_data,
            }
        )
    return events


def nav_bursts(frames: Sequence[Frame]) -> List[Dict[str, object]]:
    bursts: List[Dict[str, object]] = []
    current: List[Frame] = []
    last_time: Optional[float] = None

    for frame in frames:
        if frame.can_id not in {"0CA", "0CB", "0CC"}:
            continue
        if last_time is None or frame.time - last_time <= 0.35:
            current.append(frame)
        else:
            if current:
                bursts.append(describe_burst(current))
            current = [frame]
        last_time = frame.time

    if current:
        bursts.append(describe_burst(current))
    return bursts


def describe_burst(frames: Sequence[Frame]) -> Dict[str, object]:
    ids = defaultdict(list)
    for frame in frames:
        ids[frame.can_id].append(frame.hex_data)
    return {
        "start": round(frames[0].time, 3),
        "end": round(frames[-1].time, 3),
        "frames": len(frames),
        "values": {can_id: list(dict.fromkeys(values)) for can_id, values in sorted(ids.items())},
    }


def summarize(path: str) -> Dict[str, object]:
    frames = parse_trc(path)
    counts = Counter(frame.can_id for frame in frames)
    uniques = unique_by_id(frames)
    start = frames[0].time if frames else 0.0
    end = frames[-1].time if frames else 0.0

    return {
        "path": path,
        "file": os.path.basename(path),
        "frames": len(frames),
        "duration": round(max(0.0, end - start), 3),
        "ids": len(counts),
        "top_ids": [
            {"id": can_id, "count": count, "unique": len(uniques[can_id])}
            for can_id, count in counts.most_common(18)
        ],
        "watch_unique": {
            can_id: [
                {"data": data, "count": count}
                for data, count in uniques.get(can_id, Counter()).most_common()
            ]
            for can_id in sorted(WATCH_IDS)
            if can_id in uniques
        },
        "text_messages": collect_isotp_text(frames),
        "events": changed_events(frames),
        "bursts": nav_bursts(frames),
    }


def best_decode(message: Dict[str, object]) -> str:
    decodes = message.get("decode") or []
    if not decodes:
        return ""
    enc, text = decodes[0]
    return f"{text} ({enc})"


def md_table(rows: Sequence[Sequence[object]], headers: Sequence[str]) -> str:
    out = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    for row in rows:
        out.append("| " + " | ".join(str(cell).replace("\n", "<br>") for cell in row) + " |")
    return "\n".join(out)


def build_markdown(summaries: Sequence[Dict[str, object]]) -> str:
    lines = [
        "# Разбор штатных Navi TRC логов",
        "",
        f"Сгенерировано: {datetime.now().isoformat(timespec='seconds')}",
        "",
        "Вывод коротко: эти файлы являются CAN trace, а не TEYES UART. Навигация в них разделена минимум на два слоя: текстовые ISO-TP сообщения и компактные бинарные блоки 0CA/0CB/0CC плюс состояние 115/1CA/122/123/1E7.",
        "",
        "## Файлы",
    ]

    lines.append(
        md_table(
            [
                [summary["file"], summary["frames"], summary["duration"], summary["ids"]]
                for summary in summaries
            ],
            ["Файл", "Кадров", "Длительность, сек", "CAN ID"],
        )
    )

    for summary in summaries:
        lines.extend(["", f"## {summary['file']}", "", "### Самые частые ID"])
        lines.append(
            md_table(
                [
                    [row["id"], row["count"], row["unique"]]
                    for row in summary["top_ids"]
                ],
                ["ID", "Кадров", "Уникальных payload"],
            )
        )

        lines.extend(["", "### Текстовые ISO-TP сообщения"])
        text_rows = []
        for message in summary["text_messages"][:20]:
            text_rows.append(
                [
                    message["time"],
                    message["id"],
                    f"{message['line']}-{message['end_line']}",
                    best_decode(message),
                    message["payload_hex"][:90],
                ]
            )
        lines.append(md_table(text_rows or [["-", "-", "-", "не найдено", "-"]], ["t", "ID", "строки", "текст", "payload"]))

        lines.extend(["", "### Уникальные значения ключевых ID"])
        unique_rows = []
        watch_unique = summary["watch_unique"]
        for can_id in sorted(watch_unique):
            for item in watch_unique[can_id][:12]:
                unique_rows.append([can_id, item["count"], item["data"]])
        lines.append(md_table(unique_rows, ["ID", "count", "payload"]))

        bursts = summary["bursts"]
        if bursts:
            lines.extend(["", "### Блоки 0CA/0CB/0CC"])
            burst_rows = []
            for burst in bursts[:14]:
                values = "; ".join(
                    f"{can_id}: {' / '.join(vals[:3])}"
                    for can_id, vals in burst["values"].items()
                )
                burst_rows.append([burst["start"], burst["end"], burst["frames"], values])
            lines.append(md_table(burst_rows, ["start", "end", "frames", "values"]))

        lines.extend(["", "### Хронология изменений наблюдаемых ID"])
        event_rows = [
            [event["time"], event["id"], event["data"]]
            for event in summary["events"][:80]
        ]
        lines.append(md_table(event_rows, ["t", "ID", "payload"]))

    lines.extend(
        [
            "",
            "## Практический смысл",
            "",
            "- 49B выглядит как штатный текстовый канал навигации/локации. Там есть координаты и строка кириллицей; перед текстом идет управляющий байт/маркер.",
            "- 4E8 тоже умеет ISO-TP текст, но в этих логах больше похож на медиа/общий текст, а не на маневры.",
            "- 0CA/0CB/0CC появляются пакетами и меняют значения только во втором логе. Это главный кандидат на компактные данные штатной навигации: полосы, геометрия/дорога, подсказки или состояние маршрута.",
            "- 115 меняется рядом с навигационными изменениями и похож на отдельное состояние/иконку/дистанцию TBT. Его нельзя игнорировать.",
            "- 440/448/44B/44D почти статичные. Они похожи на служебные/режимные кадры, а не на сами маневры.",
            "- По одним логам без синхронной картинки приборки нельзя честно назвать каждый код человеческим названием. Но теперь понятно, какие ID надо тестировать и фиксировать на машине.",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Analyze Hyundai/Kia stock navigation CAN TRC logs.")
    parser.add_argument("paths", nargs="+")
    parser.add_argument("--json-out")
    parser.add_argument("--md-out")
    args = parser.parse_args()

    summaries = [summarize(path) for path in args.paths]

    if args.json_out:
        os.makedirs(os.path.dirname(os.path.abspath(args.json_out)), exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(summaries, fh, ensure_ascii=False, indent=2)

    markdown = build_markdown(summaries)
    if args.md_out:
        os.makedirs(os.path.dirname(os.path.abspath(args.md_out)), exist_ok=True)
        with open(args.md_out, "w", encoding="utf-8") as fh:
            fh.write(markdown)
    else:
        print(markdown)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
