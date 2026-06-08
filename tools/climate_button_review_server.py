#!/usr/bin/env python3
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


DEFAULT_CAPTURE_DIR = Path("/Users/legion/Downloads/canbus/logs/button_levels/20260606_154637")
SUMMARY_NAME = "button_level_capture_summary.json"
STATE_NAME = "climate_button_review_state.json"
EVENTS_NAME = "climate_button_review_events.jsonl"

CONTROL_LABELS = {
    "driver_heat": "Обогрев водитель",
    "pass_heat": "Обогрев пассажир",
    "driver_vent": "Обдув водитель",
    "pass_vent": "Обдув пассажир",
}

CONTROL_ORDER = ["driver_heat", "pass_heat", "driver_vent", "pass_vent"]

# Capture labels came from the script's generic cycle. In this car the physical
# cycle is reversed: first press is level 3, then 2, then 1, then OFF.
LEVELS = [
    {"key": "level3", "label": "3", "capture_state": "level1", "press": "1-е нажатие"},
    {"key": "level2", "label": "2", "capture_state": "level2", "press": "2-е нажатие"},
    {"key": "level1", "label": "1", "capture_state": "level3", "press": "3-е нажатие"},
    {"key": "off", "label": "OFF", "capture_state": "off", "press": "4-е нажатие"},
]

RESULTS = {
    "ok": "работает",
    "bad": "не работает",
    "maybe": "сомнительно",
    "clear": "",
}


HTML = r"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>KIA climate review</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #111416;
      --surface: #181d21;
      --surface-2: #20262b;
      --border: #303941;
      --text: #f0f4f7;
      --muted: #98a5ad;
      --ok: #22c55e;
      --bad: #ef4444;
      --maybe: #f59e0b;
      --accent: #38bdf8;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    * { box-sizing: border-box; }
    body { margin: 0; background: var(--bg); color: var(--text); }
    main { max-width: 1180px; margin: 0 auto; padding: 18px; }
    header { display: flex; gap: 14px; justify-content: space-between; align-items: end; margin-bottom: 14px; }
    h1 { margin: 0; font-size: 22px; letter-spacing: 0; }
    .sub { margin-top: 5px; color: var(--muted); font-size: 13px; }
    .top-status { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
    .pill { border: 1px solid var(--border); border-radius: 7px; padding: 7px 9px; background: var(--surface); color: #cbd5dd; font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
    .control { border: 1px solid var(--border); border-radius: 8px; background: var(--surface); padding: 12px; }
    .control-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
    .name { font-weight: 750; font-size: 16px; }
    .state { min-width: 108px; text-align: center; border-radius: 7px; border: 1px solid var(--border); padding: 6px 8px; color: var(--muted); background: #101315; font-size: 13px; }
    .state.ok { color: #d9ffe5; border-color: #1e8b4a; background: #12351f; }
    .state.bad { color: #ffe5e5; border-color: #b13a3a; background: #3a1717; }
    .state.maybe { color: #fff0cc; border-color: #a66a08; background: #36250b; }
    .levels { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
    .level { border: 1px solid var(--border); border-radius: 8px; background: var(--surface-2); padding: 9px; min-height: 128px; display: grid; gap: 7px; align-content: start; }
    .level.ok { border-color: #1e8b4a; box-shadow: inset 0 0 0 1px #1e8b4a; }
    .level.bad { border-color: #b13a3a; box-shadow: inset 0 0 0 1px #b13a3a; }
    .level.maybe { border-color: #a66a08; box-shadow: inset 0 0 0 1px #a66a08; }
    .level-label { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .level-label b { font-size: 20px; }
    .capture { color: var(--muted); font-size: 11px; line-height: 1.25; }
    .actions { display: grid; grid-template-columns: repeat(3, 1fr); gap: 5px; }
    button { border: 1px solid var(--border); border-radius: 7px; background: #14191d; color: var(--text); min-height: 30px; font: 700 12px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; cursor: pointer; }
    button:hover { border-color: var(--accent); }
    button.ok { background: #12351f; border-color: #1e8b4a; color: #d9ffe5; }
    button.bad { background: #3a1717; border-color: #b13a3a; color: #ffe5e5; }
    button.maybe { background: #36250b; border-color: #a66a08; color: #fff0cc; }
    .mini { color: var(--muted); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .candidates { margin-top: 9px; border-top: 1px solid var(--border); padding-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; }
    .candidate { color: #c8d2da; background: #0f1214; border: 1px solid #26313a; border-radius: 6px; padding: 5px 7px; font: 11px ui-monospace, SFMono-Regular, Menlo, monospace; }
    .panel { margin-top: 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); padding: 12px; }
    .log { min-height: 70px; max-height: 160px; overflow: auto; font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; color: #c9d3dc; white-space: pre-wrap; }
    @media (max-width: 960px) {
      header { align-items: start; flex-direction: column; }
      .top-status { justify-content: flex-start; }
      .grid { grid-template-columns: 1fr; }
    }
    @media (max-width: 620px) {
      main { padding: 12px; }
      .levels { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }
  </style>
</head>
<body>
<main>
  <header>
    <div>
      <h1>KIA climate review</h1>
      <div class="sub">Порядок уровней: 3 -> 2 -> 1 -> OFF</div>
    </div>
    <div class="top-status">
      <div class="pill" id="frames">frames: -</div>
      <div class="pill" id="savedPath">state: -</div>
    </div>
  </header>
  <div id="controls" class="grid"></div>
  <section class="panel">
    <div class="control-head">
      <div class="name">Журнал</div>
      <button onclick="loadState()">Обновить</button>
    </div>
    <div id="log" class="log"></div>
  </section>
</main>
<script>
let appState = null;
const resultText = {ok:'работает', bad:'не работает', maybe:'сомнительно'};

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}

function controlStatus(control) {
  const marks = appState.saved.review[control.key] || {};
  const values = appState.levels.map(l => marks[l.key]?.result).filter(Boolean);
  if (!values.length) return {key:'', text:'не проверено'};
  if (values.some(v => v === 'bad')) return {key:'bad', text:'есть НЕ работает'};
  if (values.some(v => v === 'maybe')) return {key:'maybe', text:'проверить'};
  if (values.length === appState.levels.length && values.every(v => v === 'ok')) return {key:'ok', text:'работает'};
  return {key:'maybe', text:'частично'};
}

function candidateChips(control) {
  const counts = {};
  for (const item of control.candidates || []) counts[item.can_id] = (counts[item.can_id] || 0) + item.frames;
  return Object.entries(counts)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([id, frames]) => `<span class="candidate">${esc(id)} ${frames}</span>`)
    .join('');
}

function render() {
  document.getElementById('frames').textContent = `frames: ${appState.meta.frames.toLocaleString('ru-RU')}`;
  document.getElementById('savedPath').textContent = appState.state_path.split('/').slice(-1)[0];
  const root = document.getElementById('controls');
  root.innerHTML = '';
  for (const control of appState.controls) {
    const status = controlStatus(control);
    const marks = appState.saved.review[control.key] || {};
    const html = `
      <section class="control">
        <div class="control-head">
          <div>
            <div class="name">${esc(control.label)}</div>
            <div class="mini">устойчивое поле: ${control.persistent ? 'есть' : 'нет'} | кандидаты: ${(control.candidates || []).length}</div>
          </div>
          <div class="state ${status.key}">${esc(status.text)}</div>
        </div>
        <div class="levels">
          ${appState.levels.map(level => {
            const mark = marks[level.key] || {};
            const cls = mark.result || '';
            return `
              <div class="level ${cls}">
                <div class="level-label"><b>${esc(level.label)}</b><span class="mini">${esc(mark.result ? resultText[mark.result] : 'нет отметки')}</span></div>
                <div class="capture">${esc(level.press)}<br>capture: ${esc(level.capture_state)}</div>
                <div class="actions">
                  <button class="ok" onclick="mark('${control.key}','${level.key}','ok')">OK</button>
                  <button class="bad" onclick="mark('${control.key}','${level.key}','bad')">Нет</button>
                  <button class="maybe" onclick="mark('${control.key}','${level.key}','maybe')">?</button>
                </div>
                <button onclick="mark('${control.key}','${level.key}','clear')">Сброс</button>
              </div>
            `;
          }).join('')}
        </div>
        <div class="candidates">${candidateChips(control)}</div>
      </section>
    `;
    root.insertAdjacentHTML('beforeend', html);
  }
  const events = appState.saved.events || [];
  document.getElementById('log').textContent = events.slice(-12).reverse().map(e =>
    `${e.time}  ${e.control_label}  ${e.level_label}: ${e.result_label}`
  ).join('\n') || 'пусто';
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {'Content-Type': 'application/json', ...(options.headers || {})}
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok || data.ok === false) throw new Error(data.error || response.statusText);
  return data;
}

async function mark(control, level, result) {
  appState = await api('/api/mark', {method:'POST', body:JSON.stringify({control, level, result})});
  render();
}

async function loadState() {
  appState = await api('/api/state');
  render();
}

loadState().catch(error => {
  document.getElementById('log').textContent = error.message;
});
</script>
</body>
</html>
"""


class ClimateReviewApp:
    def __init__(self, capture_dir):
        self.capture_dir = Path(capture_dir)
        self.summary_path = self.capture_dir / SUMMARY_NAME
        self.state_path = self.capture_dir / STATE_NAME
        self.events_path = self.capture_dir / EVENTS_NAME

    def load_summary(self):
        if not self.summary_path.exists():
            raise FileNotFoundError(str(self.summary_path))
        return json.loads(self.summary_path.read_text(encoding="utf-8"))

    def load_saved(self):
        if self.state_path.exists():
            return json.loads(self.state_path.read_text(encoding="utf-8"))
        return {"review": {}, "events": []}

    def save_saved(self, saved):
        self.state_path.write_text(json.dumps(saved, ensure_ascii=False, indent=2), encoding="utf-8")

    def public_state(self):
        summary = self.load_summary()
        saved = self.load_saved()
        persistent = summary.get("persistent_candidates") or {}
        events = summary.get("event_payload_candidates") or {}
        controls = []
        for key in CONTROL_ORDER:
            controls.append({
                "key": key,
                "label": CONTROL_LABELS[key],
                "persistent": bool(persistent.get(key)),
                "candidates": events.get(key, [])[:20],
            })
        return {
            "ok": True,
            "meta": {
                "started": summary.get("meta", {}).get("started", ""),
                "finished": summary.get("meta", {}).get("finished", ""),
                "frames": int(summary.get("frame_count") or 0),
                "mode": summary.get("meta", {}).get("mode", ""),
            },
            "levels": LEVELS,
            "controls": controls,
            "saved": saved,
            "state_path": str(self.state_path),
        }

    def mark(self, data):
        control = str(data.get("control") or "")
        level = str(data.get("level") or "")
        result = str(data.get("result") or "")
        if control not in CONTROL_LABELS:
            raise ValueError("unknown control")
        level_item = next((item for item in LEVELS if item["key"] == level), None)
        if not level_item:
            raise ValueError("unknown level")
        if result not in RESULTS:
            raise ValueError("unknown result")

        saved = self.load_saved()
        saved.setdefault("review", {}).setdefault(control, {})
        saved.setdefault("events", [])
        if result == "clear":
            saved["review"][control].pop(level, None)
        else:
            saved["review"][control][level] = {
                "result": result,
                "updated_at": time.strftime("%Y-%m-%d %H:%M:%S %z"),
            }
        event = {
            "time": time.strftime("%Y-%m-%d %H:%M:%S %z"),
            "control": control,
            "control_label": CONTROL_LABELS[control],
            "level": level,
            "level_label": level_item["label"],
            "capture_state": level_item["capture_state"],
            "result": result,
            "result_label": RESULTS[result] or "сброс",
        }
        saved["events"].append(event)
        saved["events"] = saved["events"][-200:]
        self.save_saved(saved)
        with self.events_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, ensure_ascii=False) + "\n")
        return self.public_state()


APP = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)

    def _send(self, status, payload, content_type="application/json"):
        if isinstance(payload, (dict, list)):
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        elif isinstance(payload, str):
            raw = payload.encode("utf-8")
        else:
            raw = payload
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _read_json(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", "0") or "0"))
        return json.loads(raw.decode("utf-8") or "{}")

    def do_GET(self):
        try:
            path = urlparse(self.path).path
            if path == "/" or path == "/index.html":
                self._send(200, HTML, "text/html; charset=utf-8")
            elif path == "/api/state":
                self._send(200, APP.public_state())
            else:
                self._send(404, {"ok": False, "error": "not found"})
        except Exception as exc:
            self._send(500, {"ok": False, "error": str(exc)})

    def do_POST(self):
        try:
            path = urlparse(self.path).path
            data = self._read_json()
            if path == "/api/mark":
                self._send(200, APP.mark(data))
            else:
                self._send(404, {"ok": False, "error": "not found"})
        except Exception as exc:
            self._send(500, {"ok": False, "error": str(exc)})


def main():
    global APP
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture-dir", default=str(DEFAULT_CAPTURE_DIR))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8791)
    args = parser.parse_args()

    APP = ClimateReviewApp(args.capture_dir)
    APP.load_summary()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"climate review listening on http://{args.host}:{args.port}/", flush=True)
    print(f"capture: {args.capture_dir}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
