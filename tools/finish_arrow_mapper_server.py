#!/usr/bin/env python3
import argparse
import glob
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

try:
    import serial
except Exception:  # pragma: no cover - shown through /api/status
    serial = None


CMD_MANEUVER = 0x45
CMD_NAV_ON = 0x48
DEFAULT_PORT = "/dev/cu.usbmodemKIA1"
DEFAULT_BAUD = 115200
ROOT = "/Users/legion/Downloads/appkia"
DATA_DIR = os.path.join(ROOT, "work")
JSONL_PATH = os.path.join(DATA_DIR, "finish_arrow_mapping.jsonl")
LATEST_PATH = os.path.join(DATA_DIR, "finish_arrow_mapping_latest.json")


HTML = r"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>KIA finish arrow mapper</title>
  <style>
    :root {
      color-scheme: dark;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: #111418;
      color: #eef3f8;
    }
    body { margin: 0; background: #111418; }
    main { max-width: 1180px; margin: 0 auto; padding: 22px; }
    h1 { margin: 0 0 4px; font-size: 24px; font-weight: 700; }
    h2 { margin: 0 0 12px; font-size: 16px; }
    p { margin: 0; color: #aebdca; }
    section {
      background: #171d24;
      border: 1px solid #2b3440;
      border-radius: 8px;
      padding: 16px;
    }
    .grid { display: grid; grid-template-columns: minmax(340px, 0.9fr) minmax(420px, 1.1fr); gap: 14px; margin-top: 14px; }
    .wide { grid-column: 1 / -1; }
    label { display: block; color: #aebdca; font-size: 12px; margin-bottom: 6px; }
    button, select, input {
      border: 1px solid #3a4654;
      background: #202832;
      color: #eef3f8;
      border-radius: 7px;
      font: inherit;
      min-height: 38px;
    }
    button { padding: 8px 12px; cursor: pointer; }
    button:hover { border-color: #6b7f97; }
    button.primary { background: #1d5fbf; border-color: #388bfd; }
    button.warn { background: #5b2a16; border-color: #b15c16; }
    button.good { background: #1f6a42; border-color: #2f9b62; }
    button.ghost { background: #161b22; }
    input, select { box-sizing: border-box; padding: 7px 9px; width: 100%; }
    input[type="range"] { padding: 0; }
    .row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .row > * { flex: 0 0 auto; }
    .row .grow { flex: 1 1 180px; }
    .stack { display: grid; gap: 12px; }
    .readout {
      background: #0d1117;
      border: 1px solid #2b3440;
      border-radius: 7px;
      padding: 10px 12px;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      color: #d9e6f2;
      min-height: 22px;
      overflow-wrap: anywhere;
    }
    .big {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 8px;
    }
    .big button { min-height: 46px; }
    .clock-grid {
      display: grid;
      grid-template-columns: repeat(6, minmax(0, 1fr));
      gap: 8px;
    }
    .clock-grid button { min-height: 44px; padding: 6px; }
    .muted { color: #8f9daa; font-size: 12px; }
    .status-ok { color: #71d391; }
    .status-bad { color: #ff9b8f; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { border-bottom: 1px solid #2b3440; padding: 7px 6px; text-align: left; }
    th { color: #aebdca; font-weight: 600; }
    code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    @media (max-width: 860px) {
      .grid { grid-template-columns: 1fr; }
      .clock-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
    }
  </style>
</head>
<body>
<main>
  <h1>KIA finish arrow mapper</h1>
  <p>USB adapter direct: <code>b5=02 b6=00 b7=00 b8=raw</code>, valid raw dec <code>0..45</code> step <code>3</code></p>

  <div class="grid">
    <section class="stack">
      <h2>Adapter</h2>
      <div class="row">
        <div class="grow">
          <label for="port">Port</label>
          <select id="port"></select>
        </div>
        <button onclick="refreshPorts()">Refresh</button>
      </div>
      <div class="row">
        <button class="primary" onclick="openPort()">Open</button>
        <button onclick="closePort()">Close</button>
        <button class="good" onclick="navOn()">Nav on</button>
        <button class="warn" onclick="navOff()">Nav off</button>
      </div>
      <div id="status" class="readout">status</div>
      <div id="lastTx" class="readout">last tx</div>
    </section>

    <section class="stack">
      <h2>Rotate</h2>
      <div class="row">
        <button onclick="bump(-12)">-12</button>
        <button onclick="bump(-3)">-3</button>
        <div class="grow">
          <label for="raw">Raw b8 dec: <span id="rawLabel">00</span></label>
          <input id="raw" type="range" min="0" max="45" step="3" value="0" oninput="syncRaw()" onchange="sendCurrent()">
        </div>
        <input id="rawNum" style="width:76px" type="number" min="0" max="45" step="3" value="0" oninput="syncRawNum()">
        <button onclick="bump(3)">+3</button>
        <button onclick="bump(12)">+12</button>
      </div>
      <div class="row">
        <label style="margin:0">Distance m</label>
        <input id="meters" style="width:96px" type="number" min="1" max="9999" value="120">
        <button class="primary" onclick="sendCurrent()">Send</button>
        <button onclick="startSweep()">Sweep 0..45</button>
        <button class="warn" onclick="stopSweep()">Stop</button>
      </div>
      <div class="readout" id="framePreview">frame</div>
    </section>

    <section class="stack">
      <h2>Valid raw positions</h2>
      <div class="clock-grid" id="validButtons"></div>
      <p class="muted">Decimal buttons: 00,03,06,...,45. Frame HEX for 45 dec is 2D.</p>
    </section>

    <section class="wide stack">
      <h2>Remember actual view</h2>
      <div class="row">
        <label style="margin:0">Sent raw</label>
        <div class="readout" id="sentForSave" style="min-width:150px">00</div>
        <label style="margin:0">Comment</label>
        <input id="comment" class="grow" value="">
      </div>
      <div class="big" id="observeButtons"></div>
      <div class="row">
        <button class="warn" onclick="remember('missing')">Propala / missing</button>
        <button onclick="remember('unclear')">Unclear</button>
      </div>
      <div class="readout" id="saveStatus">saved notes will appear here</div>
    </section>

    <section class="wide stack">
      <h2>Saved</h2>
      <div class="row">
        <button onclick="loadNotes()">Reload</button>
        <span class="muted" id="notesPath"></span>
      </div>
      <div style="overflow:auto">
        <table>
          <thead><tr><th>time</th><th>raw</th><th>hex</th><th>expected</th><th>actual</th><th>visible</th><th>comment</th></tr></thead>
          <tbody id="notes"></tbody>
        </table>
      </div>
    </section>
  </div>
</main>

<script>
const hours = ['12:00','13:00','14:00','15:00','16:00','17:00','18:00','19:00','20:00','21:00','22:00','23:00'];
const validRaw = Array.from({length: 16}, (_, i) => i * 3);
let lastSent = {raw: 0, expected: '', hex: ''};
let sweepTimer = null;

function $(id) { return document.getElementById(id); }
function hex2(n) { return Number(n).toString(16).toUpperCase().padStart(2, '0'); }
function dec2(n) { return Number(n).toString(10).padStart(2, '0'); }
function validRawValue(v) {
  v = Math.max(0, Math.min(45, Number(v) || 0));
  return Math.max(0, Math.min(45, Math.round(v / 3) * 3));
}

async function api(path, opts = {}) {
  const res = await fetch(path, opts);
  const data = await res.json();
  if (!res.ok || data.ok === false) throw new Error(data.error || res.statusText);
  return data;
}

function setStatus(text, ok = true) {
  $('status').innerHTML = `<span class="${ok ? 'status-ok' : 'status-bad'}">${text}</span>`;
}

async function refreshPorts() {
  const data = await api('/api/ports');
  const select = $('port');
  select.innerHTML = '';
  for (const item of data.ports) {
    const opt = document.createElement('option');
    opt.value = item.path;
    opt.textContent = `${item.path}${item.likely ? ' *' : ''}`;
    select.appendChild(opt);
  }
  if (data.default_port) select.value = data.default_port;
}

async function refreshStatus() {
  try {
    const data = await api('/api/status');
    setStatus(`${data.open ? 'open' : 'closed'} ${data.port || data.last_port || ''} @ ${data.baud || ''}`);
    $('lastTx').textContent = data.last_tx || 'last tx';
  } catch (e) {
    setStatus(e.message, false);
  }
}

async function openPort() {
  try {
    const data = await api('/api/open', {
      method: 'POST',
      body: JSON.stringify({port: $('port').value, baud: 115200})
    });
    setStatus(`open ${data.port} @ ${data.baud}`);
  } catch (e) { setStatus(e.message, false); }
  refreshStatus();
}

async function closePort() {
  try { await api('/api/close', {method: 'POST', body: '{}'}); } catch (e) { setStatus(e.message, false); }
  refreshStatus();
}

function rawValue() {
  return validRawValue(parseInt($('rawNum').value || $('raw').value || '0', 10));
}

function syncRaw() {
  const v = validRawValue(parseInt($('raw').value || '0', 10));
  $('raw').value = v;
  $('rawNum').value = v;
  $('rawLabel').textContent = `${dec2(v)} / hex ${hex2(v)}`;
  previewFrame(v);
}

function syncRawNum() {
  let v = rawValue();
  $('raw').value = v;
  $('rawNum').value = v;
  $('rawLabel').textContent = `${dec2(v)} / hex ${hex2(v)}`;
  previewFrame(v);
}

function setRaw(v, expected = '') {
  v = validRawValue(v);
  $('raw').value = v;
  $('rawNum').value = v;
  $('rawLabel').textContent = `${dec2(v)} / hex ${hex2(v)}`;
  previewFrame(v);
  sendCurrent(expected);
}

function bump(delta) {
  setRaw(rawValue() + delta);
}

function previewFrame(v) {
  const meters = Math.max(1, Math.min(9999, parseInt($('meters').value || '120', 10)));
  const hi = (meters >> 8) & 255;
  const lo = meters & 255;
  const bytes = [0xBB,0x41,0xA1,0x0E,0x45,0x02,0x00,0x00,v & 255,hi,lo,0x00,0x00,0x00];
  bytes[13] = bytes.slice(0, 13).reduce((a, b) => (a + b) & 255, 0);
  $('framePreview').textContent = bytes.map(hex2).join(' ');
}

async function sendCurrent(expected = '') {
  const raw = rawValue();
  const meters = Math.max(1, parseInt($('meters').value || '120', 10));
  try {
    const data = await api('/api/send', {
      method: 'POST',
      body: JSON.stringify({raw, meters, expected})
    });
    lastSent = {raw: data.raw, expected: data.expected || expected || '', hex: data.frame_hex};
    $('lastTx').textContent = data.frame_hex;
    $('sentForSave').textContent = `raw=${dec2(data.raw)} / hex=${hex2(data.raw)} ${lastSent.expected}`;
    setStatus(`sent raw ${dec2(data.raw)} / hex ${hex2(data.raw)}`);
  } catch (e) {
    setStatus(e.message, false);
  }
  refreshStatus();
}

async function navOn() {
  try { await api('/api/nav', {method: 'POST', body: JSON.stringify({active: true})}); } catch (e) { setStatus(e.message, false); }
  refreshStatus();
}

async function navOff() {
  try { await api('/api/nav', {method: 'POST', body: JSON.stringify({active: false})}); } catch (e) { setStatus(e.message, false); }
  refreshStatus();
}

function startSweep() {
  stopSweep();
  let v = 0;
  sweepTimer = setInterval(() => {
    setRaw(v, `sweep ${dec2(v)}`);
    v = v >= 45 ? 0 : v + 3;
  }, 700);
}

function stopSweep() {
  if (sweepTimer) clearInterval(sweepTimer);
  sweepTimer = null;
}

async function remember(actual) {
  try {
    const visible = actual !== 'missing';
    const data = await api('/api/remember', {
      method: 'POST',
      body: JSON.stringify({
        raw: lastSent.raw,
        expected: lastSent.expected,
        actual,
        visible,
        comment: $('comment').value || '',
        frame_hex: lastSent.hex
      })
    });
    $('saveStatus').textContent = `saved raw=${dec2(data.note.raw)} actual=${data.note.actual}`;
    await loadNotes();
  } catch (e) {
    $('saveStatus').textContent = e.message;
  }
}

async function loadNotes() {
  const data = await api('/api/notes');
  $('notesPath').textContent = data.path;
  const rows = data.notes.slice().reverse().map(n => `
    <tr>
      <td>${n.time || ''}</td>
      <td>${dec2(n.raw || 0)} / hex ${hex2(n.raw || 0)}</td>
      <td><code>${n.frame_hex || ''}</code></td>
      <td>${n.expected || ''}</td>
      <td>${n.actual || ''}</td>
      <td>${n.visible === false ? 'no' : 'yes'}</td>
      <td>${n.comment || ''}</td>
    </tr>
  `).join('');
  $('notes').innerHTML = rows || '<tr><td colspan="7" class="muted">empty</td></tr>';
}

function buildButtons() {
  const valid = $('validButtons');
  const observe = $('observeButtons');
  valid.innerHTML = '';
  observe.innerHTML = '';
  validRaw.forEach((raw) => {
    const button = document.createElement('button');
    button.textContent = `${dec2(raw)} / 0x${hex2(raw)}`;
    button.onclick = () => setRaw(raw, `raw ${dec2(raw)}`);
    valid.appendChild(button);
  });
  hours.forEach((label, i) => {
    const obs = document.createElement('button');
    obs.textContent = label;
    obs.onclick = () => remember(label);
    observe.appendChild(obs);
  });
}

buildButtons();
syncRaw();
refreshPorts().then(refreshStatus).then(loadNotes);
setInterval(refreshStatus, 1500);
</script>
</body>
</html>
"""


class SerialBridge:
    def __init__(self, default_port=DEFAULT_PORT, baud=DEFAULT_BAUD):
        self.default_port = default_port
        self.baud = baud
        self.ser = None
        self.lock = threading.Lock()
        self.rx = bytearray()
        self.last_tx = ""
        self.last_port = ""
        self.last_rx_at = 0.0
        self.reader = None
        self.running = False

    def open(self, port=None, baud=None):
        if serial is None:
            raise RuntimeError("pyserial is not available")
        port = port or self.default_port
        baud = int(baud or self.baud or DEFAULT_BAUD)
        if not port:
            raise RuntimeError("missing serial port")
        self.close()
        ser = serial.Serial(port, baudrate=baud, timeout=0.05, write_timeout=1)
        with self.lock:
            self.ser = ser
            self.baud = baud
            self.last_port = port
            self.rx.clear()
            self.running = True
        self.reader = threading.Thread(target=self._reader_loop, daemon=True)
        self.reader.start()
        return {"ok": True, "port": port, "baud": baud}

    def close(self):
        with self.lock:
            ser = self.ser
            self.ser = None
            self.running = False
        if ser is not None:
            try:
                ser.close()
            except Exception:
                pass

    def ensure_open(self):
        with self.lock:
            opened = self.ser is not None and self.ser.is_open
        if not opened:
            self.open(self.default_port, self.baud)

    def write(self, data):
        self.ensure_open()
        raw = bytes(data)
        with self.lock:
            ser = self.ser
        if ser is None:
            raise RuntimeError("serial port is not open")
        written = ser.write(raw)
        ser.flush()
        if written != len(raw):
            raise RuntimeError("serial short write")
        with self.lock:
            self.last_tx = hex_bytes(raw)
        return written

    def snapshot(self):
        with self.lock:
            port = self.ser.port if self.ser is not None else ""
            opened = self.ser is not None and self.ser.is_open
            return {
                "ok": True,
                "open": opened,
                "port": port,
                "last_port": self.last_port,
                "baud": self.baud,
                "rx_buffer": len(self.rx),
                "last_rx_at": self.last_rx_at,
                "last_tx": self.last_tx,
                "default_port": self.default_port,
                "pyserial": serial is not None,
            }

    def _reader_loop(self):
        while True:
            with self.lock:
                ser = self.ser
                running = self.running
            if not running or ser is None:
                return
            try:
                chunk = ser.read(4096)
                if chunk:
                    with self.lock:
                        self.rx.extend(chunk)
                        if len(self.rx) > 65536:
                            del self.rx[:-65536]
                        self.last_rx_at = time.time()
            except Exception:
                with self.lock:
                    if self.ser is ser:
                        self.ser = None
                        self.running = False
                try:
                    ser.close()
                except Exception:
                    pass
                return


bridge = SerialBridge()


def hex_bytes(data):
    return " ".join(f"{b:02X}" for b in bytes(data))


def checksum(frame):
    out = bytearray(frame)
    length = out[3] if len(out) > 3 else len(out)
    out[length - 1] = sum(out[:length - 1]) & 0xFF
    return bytes(out)


def clamp_int(value, low, high, default=0):
    try:
        parsed = int(float(str(value).strip()))
    except Exception:
        parsed = default
    return max(low, min(high, parsed))


def valid_finish_raw(value):
    parsed = clamp_int(value, 0, 45, 0)
    return max(0, min(45, int(round(parsed / 3.0)) * 3))


def nav_on_packet(active):
    return checksum(bytes([0xBB, 0x41, 0xA1, 0x07, CMD_NAV_ON, 0x01 if active else 0x00, 0x00]))


def finish_direction_packet(raw_b8, meters):
    raw = valid_finish_raw(raw_b8)
    whole = clamp_int(meters, 1, 9999, 120)
    frame = bytearray([
        0xBB, 0x41, 0xA1, 0x0E, CMD_MANEUVER,
        0x02, 0x00, 0x00, raw,
        (whole >> 8) & 0xFF, whole & 0xFF,
        0x00, 0x00, 0x00,
    ])
    return checksum(frame)


def likely_port(path):
    name = os.path.basename(path).lower()
    return (
        "usbmodem" in name
        or "usbserial" in name
        or "slab_usbtouart" in name
        or "wchusbserial" in name
        or "kia" in name
    )


def list_ports():
    paths = sorted(set(glob.glob("/dev/cu.*")))
    return [
        {"path": path, "likely": likely_port(path)}
        for path in paths
        if "Bluetooth-Incoming-Port" not in path and "debug-console" not in path
    ]


def load_notes(limit=200):
    if not os.path.exists(JSONL_PATH):
        return []
    notes = []
    with open(JSONL_PATH, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                notes.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return notes[-limit:]


def save_note(note):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(JSONL_PATH, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(note, ensure_ascii=False) + "\n")
    notes = load_notes(500)
    latest = {}
    for item in notes:
        latest[str(item.get("raw", ""))] = item
    with open(LATEST_PATH, "w", encoding="utf-8") as fh:
        json.dump({
            "updated": time.strftime("%Y-%m-%d %H:%M:%S %z"),
            "jsonl": JSONL_PATH,
            "latest_by_raw": latest,
        }, fh, ensure_ascii=False, indent=2)


def json_body(handler):
    size = int(handler.headers.get("Content-Length", "0") or "0")
    if size <= 0:
        return {}
    raw = handler.rfile.read(size)
    return json.loads(raw.decode("utf-8") or "{}")


class Handler(BaseHTTPRequestHandler):
    server_version = "KiaFinishArrowMapper/1.0"

    def log_message(self, fmt, *args):
        print("[%s] %s" % (time.strftime("%H:%M:%S"), fmt % args), flush=True)

    def _send(self, status, payload, content_type="application/json; charset=utf-8"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
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
            path = urlparse(self.path).path
            if path == "/" or path == "/index.html":
                self._send(200, HTML.encode("utf-8"), "text/html; charset=utf-8")
            elif path == "/api/ports":
                ports = list_ports()
                default = bridge.default_port if os.path.exists(bridge.default_port) else ""
                if not default:
                    likely = next((p["path"] for p in ports if p["likely"]), "")
                    default = likely or (ports[0]["path"] if ports else "")
                self._send(200, {"ok": True, "ports": ports, "default_port": default})
            elif path == "/api/status":
                self._send(200, bridge.snapshot())
            elif path == "/api/notes":
                self._send(200, {
                    "ok": True,
                    "path": JSONL_PATH,
                    "latest_path": LATEST_PATH,
                    "notes": load_notes(),
                })
            else:
                self._send(404, {"ok": False, "error": "not found"})
        except Exception as e:
            self._send(500, {"ok": False, "error": str(e)})

    def do_POST(self):
        try:
            path = urlparse(self.path).path
            data = json_body(self)
            if path == "/api/open":
                port = data.get("port") or bridge.default_port
                baud = clamp_int(data.get("baud"), 9600, 115200, DEFAULT_BAUD)
                bridge.open(port, baud)
                self._send(200, {"ok": True, "port": port, "baud": baud})
            elif path == "/api/close":
                bridge.close()
                self._send(200, {"ok": True})
            elif path == "/api/nav":
                active = data.get("active") is not False
                frame = nav_on_packet(active)
                bridge.write(frame)
                self._send(200, {"ok": True, "active": active, "frame_hex": hex_bytes(frame)})
            elif path == "/api/send":
                raw = valid_finish_raw(data.get("raw"))
                meters = clamp_int(data.get("meters"), 1, 9999, 120)
                expected = str(data.get("expected") or "")
                nav = nav_on_packet(True)
                frame = finish_direction_packet(raw, meters)
                bridge.write(nav)
                time.sleep(0.04)
                bridge.write(frame)
                self._send(200, {
                    "ok": True,
                    "raw": raw,
                    "meters": meters,
                    "expected": expected,
                    "nav_hex": hex_bytes(nav),
                    "frame_hex": hex_bytes(frame),
                })
            elif path == "/api/remember":
                raw = valid_finish_raw(data.get("raw"))
                note = {
                    "time": time.strftime("%Y-%m-%d %H:%M:%S %z"),
                    "raw": raw,
                    "raw_hex": f"{raw:02X}",
                    "expected": str(data.get("expected") or ""),
                    "actual": str(data.get("actual") or ""),
                    "visible": data.get("visible") is not False,
                    "comment": str(data.get("comment") or ""),
                    "frame_hex": str(data.get("frame_hex") or ""),
                }
                save_note(note)
                self._send(200, {"ok": True, "path": JSONL_PATH, "note": note})
            else:
                self._send(404, {"ok": False, "error": "not found"})
        except Exception as e:
            self._send(500, {"ok": False, "error": str(e)})


def main():
    parser = argparse.ArgumentParser(description="Small USB serial mapper for KIA finish arrow bytes.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8788)
    parser.add_argument("--serial", default=DEFAULT_PORT)
    parser.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    parser.add_argument("--auto-open", action="store_true")
    args = parser.parse_args()

    bridge.default_port = args.serial
    bridge.baud = args.baud
    if args.auto_open:
        try:
            bridge.open(args.serial, args.baud)
            print(f"serial open {args.serial} @ {args.baud}", flush=True)
        except Exception as e:
            print(f"serial open failed: {e}", flush=True)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"finish arrow mapper listening on http://{args.host}:{args.port}/", flush=True)
    print(f"notes: {JSONL_PATH}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
