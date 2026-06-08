#!/usr/bin/env python3
import argparse
import base64
import glob
import hashlib
import json
import os
import select
import threading
import time
import termios
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


CMD_RADIO_TEXT = 0x20
CMD_MEDIA_TEXT = 0x21
CMD_USB_TEXT = 0x22
CMD_ANDROID_AUTO_TEXT = 0x23
CMD_MY_MUSIC_TEXT = 0x24
CMD_CARPLAY_TEXT = 0x25
CMD_CALL_TEXT = 0x26
CMD_SPEED_LIMIT = 0x44
CMD_MANEUVER = 0x45
CMD_NAV_ON = 0x48
CMD_NAV_TEXT = 0x4A
CMD_FIRMWARE = 0x55
CMD_SOURCE_STATUS = 0x7A
CMD_CANLOG = 0x70
CMD_RAISE_UART = 0x70
CMD_CAN_RING_READ = 0x76
MAX_FIRMWARE_SIZE = 114688
FIRMWARE_ROOTS = [
    "/Users/legion/Downloads/canbus/build/git/canbus2can35/firmware/trusted",
    "/Users/legion/Downloads/canbus/build/git/canbus2can35/app/app/src/main/assets/firmware",
    "/Users/legion/Downloads/canbus/build/git/canbus2can35/adapter_firmware",
]
CAN_LOG_ROOT = "/Users/legion/Downloads/canbus/logs/cdc_raw"
NAV_LANE_NOTES = "/Users/legion/Downloads/canbus/logs/nav_lane_mapper_20260531.jsonl"
CAN_PROFILES = {
    "ccan": {"label": "C-CAN bus0", "bus": 0},
    "mcan": {"label": "M-CAN bus1", "bus": 1},
}

BAUDS = {
    9600: termios.B9600,
    19200: termios.B19200,
    38400: termios.B38400,
    57600: termios.B57600,
    115200: termios.B115200,
}

HTML = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Kia USB serial tester</title>
  <style>
    :root { color-scheme: dark; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    body { margin: 0; background: #111418; color: #eef3f8; }
    main { max-width: 1120px; margin: 0 auto; padding: 24px; }
    h1 { margin: 0 0 8px; font-size: 24px; }
    h2 { margin: 0 0 12px; font-size: 16px; color: #dbe7f3; }
    p { margin: 0 0 16px; color: #aebdca; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(310px, 1fr)); gap: 16px; }
    section { border: 1px solid #29313a; border-radius: 8px; padding: 16px; background: #171c22; }
    button, select, input, textarea { border: 1px solid #394552; border-radius: 7px; background: #202832; color: #eef3f8; font: inherit; }
    button { padding: 10px 12px; cursor: pointer; }
    button.primary { background: #1f6feb; border-color: #388bfd; }
    button.warn { background: #5a2d0c; border-color: #b15c16; }
    button:hover { border-color: #7aa2d6; }
    select, input { box-sizing: border-box; width: 100%; padding: 10px; }
    input[type="range"] { padding: 0; accent-color: #2dd4bf; }
    input[type="checkbox"] { width: auto; transform: scale(1.25); accent-color: #2dd4bf; }
    textarea { box-sizing: border-box; width: 100%; min-height: 88px; padding: 10px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .row { display: grid; grid-template-columns: 1fr auto auto; gap: 8px; align-items: end; }
    .two { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .four { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
    .buttons { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 8px; }
    .wide { grid-column: 1 / -1; }
    .toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; }
    .toolbar button { min-width: 120px; }
    .toolbar-field { min-width: 150px; max-width: 220px; flex: 1; }
    .climate-board { display: grid; gap: 8px; margin-top: 12px; }
    .climate-control { display: grid; grid-template-columns: 28px minmax(220px, 1fr) minmax(220px, auto); gap: 10px; align-items: center; border: 1px solid #2b3743; border-radius: 8px; background: #10151b; padding: 10px; }
    .climate-pick { display: flex; justify-content: center; }
    .climate-name { font-weight: 700; color: #edf5ff; }
    .climate-meta { margin-top: 3px; font-size: 12px; color: #9eacb9; }
    .climate-note { margin-top: 5px; font-size: 12px; color: #d7b46a; }
    .climate-state { display: grid; grid-template-columns: repeat(3, minmax(70px, 1fr)); gap: 6px; }
    .climate-state button { padding: 8px 10px; }
    .climate-state button.active-on { background: #136f4a; border-color: #2dd4bf; }
    .climate-state button.active-off { background: #553019; border-color: #d97706; }
    .climate-tx { grid-column: 2 / -1; font-size: 11px; color: #8695a3; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; overflow-wrap: anywhere; }
    .status { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; color: #d7e2ec; }
    .log { min-height: 260px; max-height: 460px; overflow: auto; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.45; background: #0c0f13; border: 1px solid #27313b; border-radius: 8px; padding: 12px; white-space: pre-wrap; }
    label { display: block; margin: 0 0 6px; color: #b9c7d5; font-size: 13px; }
    .muted { color: #8795a3; }
    .tabs { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; margin: 18px 0 16px; }
    .tab { border-color: #2b3743; background: #151b22; color: #b9c7d5; font-weight: 700; }
    .tab.active { background: #1f6feb; border-color: #388bfd; color: #fff; }
    .tab-pane { display: none; }
    .tab-pane.active { display: block; }
    .checkline { display: flex; gap: 10px; align-items: center; color: #cbd7e3; }
    .readout { margin-top: 10px; padding: 12px; border: 1px solid #2b3743; border-radius: 8px; background: #10151b; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    @media (max-width: 760px) {
      .four { grid-template-columns: repeat(2, 1fr); }
      .climate-control { grid-template-columns: 28px 1fr; }
      .climate-state { grid-column: 2; }
      .climate-tx { grid-column: 2; }
    }
  </style>
</head>
<body>
<main>
  <h1>Kia USB serial tester</h1>
  <p>Open the adapter USB serial port, then send media, navigation, or raise climate UART frames through the adapter.</p>
  <div class="tabs">
    <button id="tabBtn-media" class="tab active" onclick="showTab('media')">Media</button>
    <button id="tabBtn-navi" class="tab" onclick="showTab('navi')">Navi</button>
    <button id="tabBtn-climate" class="tab" onclick="showTab('climate')">Climate</button>
    <button id="tabBtn-logger" class="tab" onclick="showTab('logger')">Logger</button>
    <button id="tabBtn-update" class="tab" onclick="showTab('update')">Update</button>
  </div>

  <div id="tab-media" class="tab-pane active">
  <div class="grid">
    <section>
      <h2>USB adapter</h2>
      <div class="row">
        <div>
          <label for="port">Serial port</label>
          <select id="port"></select>
        </div>
        <button onclick="refreshPorts()">Refresh</button>
        <button class="primary" onclick="openPort()">Open</button>
      </div>
      <div style="height:8px"></div>
      <div class="buttons">
        <button onclick="closePort()">Close</button>
      </div>
      <div style="height:12px"></div>
      <div id="adapterStatus" class="status muted">Checking adapter...</div>
    </section>

    <section>
      <h2>Title</h2>
      <label for="first">Header</label>
      <input id="first" value="KIA">
      <div style="height:8px"></div>
      <label for="track">Track / text</label>
      <input id="track" value="MEDIA TEST">
      <div style="height:12px"></div>
      <div class="row" style="grid-template-columns:1fr 1fr">
        <div>
          <label for="fmFreq">FM frequency</label>
          <input id="fmFreq" value="101.0">
        </div>
        <div>
          <label for="amFreq">AM frequency</label>
          <input id="amFreq" value="24">
        </div>
      </div>
    </section>

    <section>
      <h2>Sources</h2>
      <div class="buttons">
        <button class="primary" onclick="sendMode('usb')">USB music</button>
        <button class="primary" onclick="sendMode('bt')">BT audio</button>
        <button class="primary" onclick="sendMode('fm')">FM radio</button>
        <button class="primary" onclick="sendMode('am')">AM radio</button>
        <button onclick="sendMode('am_old')">AM old test</button>
        <button class="primary" onclick="sendMode('aa')">Android Auto</button>
        <button class="primary" onclick="sendMode('cp')">CarPlay</button>
        <button class="primary" onclick="sendMode('my')">My music</button>
        <button class="warn" onclick="sendMode('off')">Media off</button>
      </div>
      <p class="muted" style="margin-top:12px">Every source sends Media off first. USB / BT / FM / AM send source + text; Android Auto / CarPlay / My music send their working BB 41 A1 text ids.</p>
    </section>
  </div>
  </div>

  <div id="tab-navi" class="tab-pane">
  <div class="grid">
    <section>
      <h2>Navi speed limit</h2>
      <div class="buttons">
        <button class="warn" onclick="sendSpeedLimit(0)">0</button>
        <button class="primary" onclick="sendSpeedLimit(20)">20</button>
        <button class="primary" onclick="sendSpeedLimit(40)">40</button>
        <button class="primary" onclick="sendSpeedLimit(60)">60</button>
        <button class="primary" onclick="sendSpeedLimit(80)">80</button>
        <button class="primary" onclick="sendSpeedLimit(90)">90</button>
      </div>
    </section>

    <section>
      <h2>Navi progress ручной тест</h2>
      <div class="buttons">
        <button class="primary" onclick="sendNaviAction('nav_on')">Nav on</button>
        <button class="warn" onclick="sendNaviAction('nav_off')">Nav off</button>
        <button onclick="sendNaviText()">Текст</button>
      </div>
      <div style="height:12px"></div>
      <div class="two">
        <div>
          <label for="navMode">Режим иконки</label>
          <select id="navMode">
            <option value="classic">Classic app default</option>
            <option value="tbt">TBT</option>
          </select>
        </div>
        <div>
          <label for="navIcon">Манёвр</label>
          <select id="navIcon">
            <option value="right">Направо</option>
            <option value="left">Налево</option>
            <option value="forward">Прямо</option>
          </select>
        </div>
      </div>
      <div style="height:10px"></div>
      <div class="two">
        <div>
          <label for="navDistance">Расстояние, м</label>
          <input id="navDistance" type="number" min="0" max="9999" value="40" oninput="updateNaviReadout()">
        </div>
        <div>
          <label for="navText">Текст</label>
          <input id="navText" value="Classic progress">
        </div>
      </div>
      <div style="height:12px"></div>
      <label for="navProgress">Progress nibble: <span id="navProgressLabel">4</span></label>
      <input id="navProgress" type="range" min="0" max="9" step="1" value="4" oninput="updateNaviReadout()">
      <div style="height:10px"></div>
      <label class="checkline">
        <input id="navInvert" type="checkbox" checked onchange="updateNaviReadout()">
        <span>Инвертировать отправку: в кадр пойдёт 9 - progress</span>
      </label>
      <div id="naviReadout" class="readout">sent progress: 4</div>
      <div style="height:12px"></div>
      <div class="buttons">
        <button class="primary" onclick="sendNaviManeuver()">Отправить манёвр</button>
        <button onclick="sendNaviSweep('down')">Sweep 9 → 0</button>
        <button onclick="sendNaviSweep('up')">Sweep 0 → 9</button>
      </div>
      <p class="muted" style="margin-top:12px">Classic повторяет текущую отправку приложения. Progress лежит в старшем nibble байта 12 кадра 0x45.</p>
    </section>
    <section>
      <h2>Nav lane mapper: серая дорога + жёлтая стрелка</h2>
      <p class="muted">Тестер для разметки неизвестных режимов приборки. Отправляет raw 0x45 с ручными байтами b5..b8; b8 можно прогнать как 18 серых режимов 00..11.</p>
      <div class="two">
        <div>
          <label for="lanePreset">Пресет</label>
          <select id="lanePreset" onchange="applyLanePreset()">
            <option value="0D,00,01,09">classic прямо</option>
            <option value="0D,00,00,0C">classic направо</option>
            <option value="0D,00,00,24">classic налево</option>
            <option value="1F,00,00,0C">classic съезд направо</option>
            <option value="1F,00,00,24">classic съезд налево</option>
            <option value="41,00,00,00">TBT прямо</option>
            <option value="43,00,00,00">TBT направо</option>
            <option value="46,00,00,00">TBT налево</option>
            <option value="manual">manual</option>
          </select>
        </div>
        <div>
          <label for="lanePhoto">Фото/заметка</label>
          <input id="lanePhoto" placeholder="IMG_1234 или коротко что видно">
        </div>
      </div>
      <div style="height:10px"></div>
      <div class="four">
        <div><label for="laneB5">b5</label><input id="laneB5" value="0D" oninput="updateLaneReadout()"></div>
        <div><label for="laneB6">b6</label><input id="laneB6" value="00" oninput="updateLaneReadout()"></div>
        <div><label for="laneB7">b7</label><input id="laneB7" value="00" oninput="updateLaneReadout()"></div>
        <div><label for="laneB8">b8 / gray</label><input id="laneB8" value="0C" oninput="updateLaneReadout()"></div>
      </div>
      <div style="height:10px"></div>
      <div class="two">
        <div><label for="laneDistance">Расстояние, м</label><input id="laneDistance" type="number" min="0" max="9999" value="80" oninput="updateLaneReadout()"></div>
        <div><label for="laneProgress">Progress 0..9</label><input id="laneProgress" type="number" min="0" max="9" value="8" oninput="updateLaneReadout()"></div>
      </div>
      <div style="height:10px"></div>
      <label for="laneLabel">Человеческое название результата</label>
      <input id="laneLabel" placeholder="например: 3 полосы, едем прямо, справа съезд">
      <div id="laneReadout" class="readout">0x45 bytes: b5=0D b6=00 b7=00 b8=0C</div>
      <div style="height:12px"></div>
      <div class="buttons">
        <button class="primary" onclick="sendLaneCombo()">Отправить combo</button>
        <button onclick="sendLaneSweep('gray18')">Gray 00 → 11</button>
        <button onclick="sendLaneSweep('known')">Known classic</button>
        <button onclick="saveLaneNote()">Сохранить заметку</button>
      </div>
      <p class="muted" style="margin-top:12px">Если режим на приборке понятен, впиши название и фото-имя. Так соберём таблицу вместо “00/01/0C”.</p>
    </section>
  </div>
  </div>

  <div id="tab-climate" class="tab-pane">
  <div class="grid">
    <section>
      <h2>Climate raise UART через 0x70</h2>
      <p class="muted">Кадры отправляются как внешний пакет <code>BB 41 A1 ... 70 ...</code>. Для popup нужны именно RX-события магнитолы; старые <code>83 AD/AE</code> оставлены как TX-команды canbox и не обновляют popup напрямую.</p>
      <div class="buttons">
        <button class="primary" onclick="openLikelyPort()">Open likely USB</button>
        <button onclick="refreshPorts()">Refresh ports</button>
        <button onclick="closePort()">Close USB</button>
      </div>
      <div style="height:12px"></div>
      <label for="climateCommand">Команда</label>
      <select id="climateCommand" onchange="updateClimateReadout()"></select>
      <div style="height:10px"></div>
      <div class="two">
        <button class="primary" onclick="sendClimate('on')">UART включение</button>
        <button class="warn" onclick="sendClimate('off')">UART выключение</button>
      </div>
      <div style="height:10px"></div>
      <div class="row" style="grid-template-columns:1fr auto">
        <div>
          <label for="climatePulseDelay">Pulse delay, ms</label>
          <input id="climatePulseDelay" type="number" min="40" max="5000" value="300">
        </div>
        <button onclick="sendClimate('pulse')">Pulse on → off</button>
      </div>
      <div id="climateReadout" class="readout">Выберите команду.</div>
    </section>

    <section class="wide">
      <h2>Popup climate пульт</h2>
      <div class="toolbar">
        <button class="primary" onclick="sendClimateQuick('popup_climate', 'on')">Popup ON</button>
        <button class="warn" onclick="sendClimateQuick('popup_climate', 'off')">Popup OFF</button>
        <button onclick="sendClimateBatch(true, false)">Выбранные</button>
        <button onclick="sendClimateBatch(true, true)">Popup + выбранные</button>
        <button onclick="sendClimateBatch(false, false)">Все состояния</button>
        <button onclick="setSelectedClimateState('on')">Выбранные ON</button>
        <button onclick="setSelectedClimateState('off')">Выбранные OFF</button>
        <button class="warn" onclick="resetClimateStates()">Очистить popup</button>
        <div class="toolbar-field">
          <label for="climateBatchDelay">Delay между кадрами, ms</label>
          <input id="climateBatchDelay" type="number" min="40" max="5000" value="180">
        </div>
      </div>
      <div id="climateGrid" class="climate-board"></div>
      <p class="muted" style="margin-top:12px">Галочка слева включает кнопку в комбинацию. ON/OFF сразу отправляет состояние; RX climate кадр собирается только из уже включенных кнопок, popup открывается автоматически перед каждым изменением. Очистить popup отправляет пустое состояние в магнитолу.</p>
    </section>

    <section>
      <h2>Raw raise UART</h2>
      <label for="raiseRawHex">FD frame</label>
      <textarea id="raiseRawHex">FD 05 06 01 00 0C</textarea>
      <div style="height:10px"></div>
      <button class="primary" onclick="sendRaiseRaw()">Wrap через 0x70 и отправить</button>
      <p class="muted" style="margin-top:12px">Пример popup climate: <code>FD 05 06 01 00 0C</code> → <code>BB 41 A1 0C 70 FD 05 06 01 00 0C 2E</code>.</p>
    </section>
  </div>
  </div>

  <div id="tab-update" class="tab-pane">
  <div class="grid">
    <section>
      <h2>Update</h2>
      <label for="firmwareFile">Firmware BIN</label>
      <input id="firmwareFile" type="file" accept=".bin,application/octet-stream">
      <div style="height:8px"></div>
      <button class="warn" onclick="flashUploadedFirmware()">Flash BIN</button>
      <div style="height:12px"></div>
      <div id="firmwareStatus" class="status muted">Firmware idle.</div>
    </section>
  </div>
  </div>

  <div id="tab-logger" class="tab-pane">
  <div class="grid">
    <section>
      <h2>CDC RAW CAN logger</h2>
      <p class="muted">RAW log via mode1 CDC firmware: C-CAN bus0, M-CAN bus1. Both mode saves two separate files.</p>
      <div class="buttons">
        <button class="primary" onclick="startCanLog('ccan')">Write C-CAN</button>
        <button class="primary" onclick="startCanLog('mcan')">Write M-CAN</button>
        <button class="primary" onclick="startCanLog('both')">Write both</button>
        <button class="warn" onclick="stopCanLog()">Stop</button>
      </div>
      <div style="height:12px"></div>
      <div id="canCounters" class="status muted">C-CAN: 0 frames | M-CAN: 0 frames</div>
      <div style="height:8px"></div>
      <div id="canStatus" class="status muted">CAN logger idle.</div>
    </section>
  </div>
  </div>

  <section style="margin-top:16px">
    <h2>Log</h2>
    <div id="log" class="log"></div>
  </section>
</main>
<script>
const $ = id => document.getElementById(id);

function showTab(name) {
  for (const item of ['media', 'navi', 'climate', 'logger', 'update']) {
    $(`tab-${item}`).classList.toggle('active', item === name);
    $(`tabBtn-${item}`).classList.toggle('active', item === name);
  }
}

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
  if (!res.ok || data.ok === false) throw new Error(data.error || res.statusText);
  return data;
}

async function refreshPorts() {
  try {
    const data = await api('/api/ports');
    $('port').innerHTML = '';
    for (const item of data.ports) {
      const opt = document.createElement('option');
      opt.value = item.path;
      opt.textContent = `${item.path}${item.likely ? '  * likely adapter' : ''}`;
      $('port').appendChild(opt);
    }
    if (!data.ports.length) {
      const opt = document.createElement('option');
      opt.textContent = 'No serial ports yet';
      $('port').appendChild(opt);
    }
    append(`ports: ${data.ports.map(p => p.path).join(', ') || 'none'}`);
  } catch (e) { append(`ports error: ${e.message}`); }
}

async function openPort() {
  try {
    const data = await api('/api/open', {method: 'POST', body: JSON.stringify({port: $('port').value, baud: 115200})});
    append(`open: ${data.port} @ ${data.baud}`);
    adapterStatus();
  } catch (e) { append(`open error: ${e.message}`); }
}

async function openLikelyPort() {
  try {
    const ports = await api('/api/ports');
    const target = (ports.ports || []).find(p => p.likely) || (ports.ports || [])[0];
    if (!target) throw new Error('no serial ports');
    const data = await api('/api/open', {method: 'POST', body: JSON.stringify({port: target.path, baud: 115200})});
    append(`open likely: ${data.port} @ ${data.baud}`);
    await adapterStatus();
  } catch (e) { append(`open likely error: ${e.message}`); }
}

async function closePort() {
  try { const data = await api('/api/close', {method: 'POST', body: '{}'}); append(data.message); adapterStatus(); }
  catch (e) { append(`close error: ${e.message}`); }
}

function sourceDetail(mode) {
  if (mode === 'fm') return $('fmFreq').value || '101.0';
  if (mode === 'am') return $('amFreq').value || '999';
  return '';
}

async function adapterStatus() {
  try {
    const data = await api('/api/adapter/status');
    const serial = data.serial || {};
    const firmware = data.firmware || {};
    const lines = [];
    lines.push(`adapter: ${data.connected ? data.mode : 'not found'}`);
    lines.push(`cdc: ${data.cdc || '-'}`);
    lines.push(`serial: ${serial.open ? 'open' : 'closed'} ${serial.port || serial.last_port || ''} ${serial.open ? '@ ' + serial.baud : ''}`.trim());
    if (serial.last_tx) lines.push(`last tx: ${serial.last_tx}`);
    if (firmware.running) lines.push(`firmware: flashing ${firmware.percent || 0}% ${firmware.name || ''}`);
    const gs = (data.gs_usb || []).filter(item => !item.error)[0];
    if (gs) lines.push(`gs-usb: serial=${gs.serial || '-'} channels=${gs.icount ?? '-'}`);
    $('adapterStatus').textContent = lines.join('\n');
  } catch (e) { append(`adapter status error: ${e.message}`); }
}

async function sendMode(mode) {
  try {
    const data = await api('/api/test', {
      method: 'POST',
      body: JSON.stringify({mode, first: $('first').value, track: $('track').value, detail: sourceDetail(mode)})
    });
    append(`${mode}: sent ${data.frames.length} frames`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`${mode} error: ${e.message}`); }
}

async function sendSpeedLimit(value) {
  try {
    const data = await api('/api/navi', {
      method: 'POST',
      body: JSON.stringify({
        action: 'speed_quick',
        speed: value,
        alarm: value > 0 ? '0x08' : '0x04',
        info: '0x95'
      })
    });
    append(`speed ${value}: sent ${data.frames.length} frame`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`speed ${value} error: ${e.message}`); }
}

function naviPayload(action) {
  const rawProgress = parseInt($('navProgress').value || '0', 10);
  const sentProgress = $('navInvert').checked ? 9 - rawProgress : rawProgress;
  return {
    action,
    mode: $('navMode').value,
    icon: $('navIcon').value,
    distance: $('navDistance').value,
    progress: sentProgress,
    rawProgress,
    invert: $('navInvert').checked,
    text: $('navText').value,
    speed: 60,
    alarm: '0x08',
    info: '0x95'
  };
}

function updateNaviReadout() {
  if (!$('navProgress')) return;
  const rawProgress = parseInt($('navProgress').value || '0', 10);
  const sentProgress = $('navInvert').checked ? 9 - rawProgress : rawProgress;
  $('navProgressLabel').textContent = rawProgress;
  $('naviReadout').textContent = `sent progress: ${sentProgress} | byte12 high nibble: 0x${sentProgress.toString(16).toUpperCase()} | distance: ${$('navDistance').value || 0}m`;
}

async function sendNaviAction(action) {
  try {
    const data = await api('/api/navi', {method: 'POST', body: JSON.stringify(naviPayload(action))});
    append(`${action}: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`${action} error: ${e.message}`); }
}

async function sendNaviText() {
  try {
    const data = await api('/api/navi', {method: 'POST', body: JSON.stringify(naviPayload('text'))});
    append(`navi text: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`navi text error: ${e.message}`); }
}

async function sendNaviManeuver() {
  try {
    const data = await api('/api/navi', {method: 'POST', body: JSON.stringify(naviPayload('maneuver'))});
    append(`navi maneuver: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`navi maneuver error: ${e.message}`); }
}

async function sendNaviSweep(direction) {
  try {
    const payload = naviPayload('sweep');
    payload.direction = direction;
    const data = await api('/api/navi', {method: 'POST', body: JSON.stringify(payload)});
    append(`navi sweep ${direction}: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`navi sweep ${direction} error: ${e.message}`); }
}

function laneByte(id) {
  let value = ($(id).value || '0').trim().toLowerCase();
  if (!value.startsWith('0x')) value = '0x' + value;
  const parsed = parseInt(value, 16);
  return Number.isFinite(parsed) ? Math.max(0, Math.min(255, parsed)) : 0;
}

function laneByteHex(id) {
  return laneByte(id).toString(16).toUpperCase().padStart(2, '0');
}

function applyLanePreset() {
  const preset = $('lanePreset').value || 'manual';
  if (preset !== 'manual') {
    const parts = preset.split(',');
    $('laneB5').value = parts[0] || '0D';
    $('laneB6').value = parts[1] || '00';
    $('laneB7').value = parts[2] || '00';
    $('laneB8').value = parts[3] || '00';
  }
  updateLaneReadout();
}

function lanePayload(action) {
  return {
    action,
    b5: laneByteHex('laneB5'),
    b6: laneByteHex('laneB6'),
    b7: laneByteHex('laneB7'),
    b8: laneByteHex('laneB8'),
    distance: $('laneDistance').value || 80,
    progress: $('laneProgress').value || 8,
    label: $('laneLabel').value || '',
    photo: $('lanePhoto').value || ''
  };
}

function updateLaneReadout() {
  if (!$('laneReadout')) return;
  const b5 = laneByteHex('laneB5');
  const b6 = laneByteHex('laneB6');
  const b7 = laneByteHex('laneB7');
  const b8 = laneByteHex('laneB8');
  const progress = Math.max(0, Math.min(9, parseInt($('laneProgress').value || '0', 10) || 0));
  $('laneReadout').textContent = `0x45 bytes: b5=${b5} b6=${b6} b7=${b7} b8=${b8} | progress=${progress} | gray index decimal=${parseInt(b8, 16)}`;
}

async function sendLaneCombo() {
  try {
    const data = await api('/api/navi', {method: 'POST', body: JSON.stringify(lanePayload('lane_combo'))});
    append(`lane combo: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`lane combo error: ${e.message}`); }
}

async function sendLaneSweep(kind) {
  try {
    const payload = lanePayload('lane_sweep');
    payload.kind = kind;
    const data = await api('/api/navi', {method: 'POST', body: JSON.stringify(payload)});
    append(`lane sweep ${kind}: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`lane sweep ${kind} error: ${e.message}`); }
}

async function saveLaneNote() {
  try {
    const data = await api('/api/navi/lane-note', {method: 'POST', body: JSON.stringify(lanePayload('lane_note'))});
    append(`lane note saved: ${data.path}`);
  } catch (e) { append(`lane note error: ${e.message}`); }
}

let climateCommands = [];
const climateStates = {};
const climateSelected = {};

async function refreshClimateCommands() {
  try {
    const data = await api('/api/climate/list');
    climateCommands = data.commands || [];
    $('climateCommand').innerHTML = '';
    for (const item of climateCommands) {
      const opt = document.createElement('option');
      opt.value = item.key;
      opt.textContent = climateOptionText(item);
      $('climateCommand').appendChild(opt);
      if (!climateStates[item.key]) climateStates[item.key] = 'off';
      if (typeof climateSelected[item.key] === 'undefined') {
        climateSelected[item.key] = false;
      }
    }
    renderClimateGrid();
    updateClimateReadout();
  } catch (e) { append(`climate list error: ${e.message}`); }
}

function selectedClimateCommand() {
  const key = $('climateCommand') && $('climateCommand').value;
  return climateCommands.find(item => item.key === key) || climateCommands[0] || null;
}

function updateClimateReadout() {
  const item = selectedClimateCommand();
  if (!item || !$('climateReadout')) return;
  $('climateReadout').textContent = climateReadoutLines(item).join('\n');
}

function escapeHtml(value) {
  return String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function climateReadoutLines(item) {
  return [
    `button: ${item.button}`,
    `car: ${item.car || '-'}`,
    `UART on:  ${item.on}`,
    `UART off: ${item.off}`,
    item.note ? `note: ${item.note}` : null
  ].filter(Boolean);
}

function climateCommandOptionLabel(item) {
  const suffix = item.note ? ' *' : '';
  return `${item.button} — в машине: ${item.car || '-'}${suffix}`;
}

function climateOptionText(item) {
  return climateCommandOptionLabel(item);
}

function renderClimateGrid() {
  const grid = $('climateGrid');
  if (!grid) return;
  grid.innerHTML = '';
  for (const item of climateCommands) {
    const state = climateStates[item.key] || 'off';
    const checked = climateSelected[item.key] !== false;
    const row = document.createElement('div');
    row.className = 'climate-control';
    row.id = `climateRow_${item.key}`;
    row.innerHTML = `
      <label class="climate-pick" title="Включить в комбинацию">
        <input type="checkbox" ${checked ? 'checked' : ''} onchange="setClimateSelected('${item.key}', this.checked)">
      </label>
      <div>
        <div class="climate-name">${escapeHtml(item.button)}</div>
        <div class="climate-meta">key: ${escapeHtml(item.key)} | car: ${escapeHtml(item.car || '-')}</div>
        ${item.note ? `<div class="climate-note">${escapeHtml(item.note)}</div>` : ''}
      </div>
      <div class="climate-state">
        <button class="${state === 'on' ? 'active-on' : ''}" onclick="sendClimateState('${item.key}', 'on')">ON</button>
        <button class="${state === 'off' ? 'active-off' : ''}" onclick="sendClimateState('${item.key}', 'off')">OFF</button>
        <button onclick="sendClimateState('${item.key}', 'pulse')">PULSE</button>
      </div>
      <div class="climate-tx">ON ${escapeHtml(item.on)}<br>OFF ${escapeHtml(item.off)}</div>
    `;
    grid.appendChild(row);
  }
}

function setClimateSelected(key, checked) {
  climateSelected[key] = checked;
}

function setClimateLocalState(key, state) {
  if (state === 'on' || state === 'off') climateStates[key] = state;
  renderClimateGrid();
}

async function sendClimateState(key, state) {
  if (state === 'on' || state === 'off') setClimateLocalState(key, state);
  await sendClimateQuick(key, state);
}

function selectedClimateKeys() {
  return climateCommands
    .filter(item => climateSelected[item.key] !== false)
    .map(item => item.key);
}

async function setSelectedClimateState(state) {
  const keys = selectedClimateKeys();
  for (const key of keys) climateStates[key] = state;
  renderClimateGrid();
  await sendClimateBatch(true, false);
}

async function resetClimateStates() {
  for (const item of climateCommands) climateStates[item.key] = 'off';
  renderClimateGrid();
  try {
    const data = await api('/api/climate/clear', {
      method: 'POST',
      body: JSON.stringify({delayMs: $('climateBatchDelay') ? $('climateBatchDelay').value : 180})
    });
    append(`climate clear popup: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`climate clear error: ${e.message}`); }
}

async function sendClimateBatch(selectedOnly, openPopup) {
  const items = climateCommands
    .filter(item => !selectedOnly || climateSelected[item.key] !== false)
    .map(item => ({key: item.key, state: climateStates[item.key] || 'off'}));
  if (!items.length) return append('climate batch error: no selected commands');
  try {
    const data = await api('/api/climate/batch', {
      method: 'POST',
      body: JSON.stringify({
        openPopup,
        items,
        states: climateStates,
        delayMs: $('climateBatchDelay') ? $('climateBatchDelay').value : 180
      })
    });
    append(`climate batch ${selectedOnly ? 'selected' : 'all'}${openPopup ? ' + popup' : ''}: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`climate batch error: ${e.message}`); }
}

async function sendClimate(state) {
  const item = selectedClimateCommand();
  if (!item) return append('climate error: no command selected');
  if (state === 'on' || state === 'off') setClimateLocalState(item.key, state);
  await sendClimateQuick(item.key, state);
}

async function sendClimateQuick(key, state) {
  try {
    const data = await api('/api/climate', {
      method: 'POST',
      body: JSON.stringify({key, state, states: climateStates, delayMs: $('climatePulseDelay') ? $('climatePulseDelay').value : 300})
    });
    append(`climate ${key} ${state}: sent ${data.frames.length} frame(s)`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`climate ${key} ${state} error: ${e.message}`); }
}

async function sendRaiseRaw() {
  try {
    const data = await api('/api/climate/raw', {
      method: 'POST',
      body: JSON.stringify({hex: $('raiseRawHex').value})
    });
    append(`raw raise UART: sent ${data.frames.length} frame`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
    adapterStatus();
  } catch (e) { append(`raw raise error: ${e.message}`); }
}

async function sendTextOnly(textId) {
  try {
    const data = await api('/api/custom', {
      method: 'POST',
      body: JSON.stringify({
        source: '0x80',
        text: textId,
        detail: $('detail').value,
        first: $('first').value,
        track: $('track').value,
        sendOff: false,
        sendSource: false,
        sendText: true,
        delayMs: $('customDelay').value
      })
    });
    append(`text-only ${textId}: sent ${data.frames.length} frames`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
  } catch (e) { append(`text-only ${textId} error: ${e.message}`); }
}

function setChecked(id, value) {
  $(id).checked = value;
}

function presetCustom(mode) {
  if (mode === 'sourceOnly') {
    setChecked('sendOff', true);
    setChecked('sendSource', true);
    setChecked('sendText', false);
  } else if (mode === 'textOnly') {
    setChecked('sendOff', false);
    setChecked('sendSource', false);
    setChecked('sendText', true);
  } else if (mode === 'sourceTextNoOff') {
    setChecked('sendOff', false);
    setChecked('sendSource', true);
    setChecked('sendText', true);
  }
  sendCustom();
}

async function sendCustom() {
  try {
    const data = await api('/api/custom', {
      method: 'POST',
      body: JSON.stringify({
        source: $('sourceId').value,
        text: $('textId').value,
        detail: $('detail').value,
        first: $('first').value,
        track: $('track').value,
        sendOff: $('sendOff').checked,
        sendSource: $('sendSource').checked,
        sendText: $('sendText').checked,
        delayMs: $('customDelay').value
      })
    });
    append(`custom: sent ${data.frames.length} frames`);
    for (const frame of data.frames) append(`TX ${frame.label}: ${frame.hex}`);
  } catch (e) { append(`custom error: ${e.message}`); }
}

async function txHex() {
  try {
    const data = await api('/api/tx', {method: 'POST', body: JSON.stringify({hex: $('hex').value})});
    append(`raw TX ${data.bytes} bytes: ${data.hex}`);
  } catch (e) { append(`raw tx error: ${e.message}`); }
}

async function rx() {
  try {
    const data = await api('/api/rx');
    append(`RX ${data.bytes} bytes: ${data.hex || '-'}`);
  } catch (e) { append(`rx error: ${e.message}`); }
}

function readFileDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error || new Error('file read failed'));
    reader.readAsDataURL(file);
  });
}

async function flashUploadedFirmware() {
  const file = $('firmwareFile').files && $('firmwareFile').files[0];
  if (!file) return append('firmware upload error: no BIN selected');
  try {
    const dataB64 = await readFileDataUrl(file);
    const data = await api('/api/firmware/flash', {
      method: 'POST',
      body: JSON.stringify({name: file.name, dataB64, confirm: true})
    });
    append(`firmware upload flash started: ${data.name}`);
    firmwareStatus();
  } catch (e) { append(`firmware upload error: ${e.message}`); }
}

async function firmwareStatus() {
  try {
    const data = await api('/api/firmware/status');
    $('firmwareStatus').textContent = JSON.stringify(data, null, 2);
  } catch (e) { append(`firmware status error: ${e.message}`); }
}

async function startCanLog(mode) {
  try {
    const data = await api('/api/can/start', {
      method: 'POST',
      body: JSON.stringify({mode})
    });
    append(`can logger started: ${data.mode}`);
    for (const file of Object.values(data.files || {})) append(`log file: ${file}`);
    await canLoggerStatus();
  } catch (e) { append(`can logger start error: ${e.message}`); }
}

async function stopCanLog() {
  try {
    const data = await api('/api/can/stop', {method: 'POST', body: '{}'});
    append(`can logger stopped: ${data.message}`);
    await canLoggerStatus();
  } catch (e) { append(`can logger stop error: ${e.message}`); }
}

async function canLoggerStatus() {
  try {
    const data = await api('/api/can/status');
    const profiles = data.profiles || {};
    const c = profiles.ccan || {};
    const m = profiles.mcan || {};
    $('canCounters').textContent = `C-CAN: ${c.frames || 0} frames | M-CAN: ${m.frames || 0} frames`;
    const lines = [];
    lines.push(`state: ${data.running ? 'recording' : 'stopped'} ${data.mode || ''}`);
    lines.push(`status: ${data.status || '-'}`);
    if (data.error) lines.push(`error: ${data.error}`);
    for (const [key, file] of Object.entries(data.files || {})) lines.push(`${key}: ${file}`);
    for (const line of (data.log || []).slice(-8)) lines.push(line);
    $('canStatus').textContent = lines.join('\n');
  } catch (e) { append(`can logger status error: ${e.message}`); }
}

refreshPorts();
refreshClimateCommands();
updateNaviReadout();
applyLanePreset();
adapterStatus();
firmwareStatus();
canLoggerStatus();
setInterval(adapterStatus, 1500);
setInterval(refreshPorts, 5000);
setInterval(firmwareStatus, 1000);
setInterval(canLoggerStatus, 1000);
</script>
</body>
</html>
"""


class SerialBridge:
    def __init__(self):
        self.fd = None
        self.port = ""
        self.baud = 115200
        self.lock = threading.Lock()
        self.rx = bytearray()
        self.reader = None
        self.running = False
        self.opened_at = 0.0
        self.last_tx = ""
        self.last_port = ""
        self.last_rx_at = 0.0

    def open(self, port, baud=115200):
        if not port:
            raise ValueError("missing port")
        if baud not in BAUDS:
            raise ValueError("unsupported baud")
        self.close()
        fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        attrs = termios.tcgetattr(fd)
        attrs[0] = termios.IGNPAR
        attrs[1] = 0
        attrs[2] = BAUDS[baud] | termios.CS8 | termios.CLOCAL | termios.CREAD
        attrs[3] = 0
        attrs[4] = BAUDS[baud]
        attrs[5] = BAUDS[baud]
        attrs[6][termios.VMIN] = 0
        attrs[6][termios.VTIME] = 1
        termios.tcsetattr(fd, termios.TCSANOW, attrs)
        termios.tcflush(fd, termios.TCIOFLUSH)
        with self.lock:
            self.fd = fd
            self.port = port
            self.last_port = port
            self.baud = baud
            self.rx.clear()
            self.running = True
            self.opened_at = time.time()
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def close(self):
        with self.lock:
            fd = self.fd
            self.fd = None
            self.running = False
            self.port = ""
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass

    def write(self, data):
        with self.lock:
            fd = self.fd
        if fd is None:
            raise RuntimeError("serial port is not open")
        total = 0
        while total < len(data):
            written = os.write(fd, data[total:])
            if written <= 0:
                raise RuntimeError("serial write returned zero")
            total += written
        with self.lock:
            self.last_tx = hex_bytes(data)
        return total

    def read_recent(self):
        with self.lock:
            data = bytes(self.rx)
            self.rx.clear()
        return data

    def clear_rx(self):
        with self.lock:
            self.rx.clear()

    def take_rx(self):
        with self.lock:
            data = bytes(self.rx)
            self.rx.clear()
        return data

    def snapshot(self):
        with self.lock:
            return {
                "ok": True,
                "open": self.fd is not None,
                "port": self.port,
                "last_port": self.last_port,
                "baud": self.baud,
                "rx_buffer": len(self.rx),
                "last_tx": self.last_tx,
                "last_rx_at": self.last_rx_at,
                "opened_at": self.opened_at,
            }

    def _read_loop(self):
        while True:
            with self.lock:
                fd = self.fd
                running = self.running
            if not running or fd is None:
                return
            try:
                ready, _, _ = select.select([fd], [], [], 0.2)
                if not ready:
                    continue
                chunk = os.read(fd, 4096)
                if chunk:
                    with self.lock:
                        self.rx.extend(chunk)
                        if len(self.rx) > 65536:
                            del self.rx[:-65536]
                        self.last_rx_at = time.time()
            except OSError:
                with self.lock:
                    if self.fd == fd:
                        self.fd = None
                        self.running = False
                        self.port = ""
                try:
                    os.close(fd)
                except OSError:
                    pass
                return


bridge = SerialBridge()

firmware_lock = threading.Lock()
firmware_state = {
    "ok": True,
    "running": False,
    "done": False,
    "error": "",
    "status": "idle",
    "name": "",
    "bytes": 0,
    "sha256": "",
    "blocksDone": 0,
    "blocksTotal": 0,
    "percent": 0,
    "lastAck": -1,
    "lastRx": "",
    "startedAt": 0.0,
    "finishedAt": 0.0,
    "log": [],
}


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


def firmware_busy():
    with firmware_lock:
        return bool(firmware_state.get("running"))


def require_not_flashing():
    if firmware_busy():
        raise RuntimeError("firmware update is active")


def set_firmware_state(**updates):
    with firmware_lock:
        firmware_state.update(updates)


def firmware_log(line):
    text = str(line)
    with firmware_lock:
        firmware_state["status"] = text
        log = firmware_state.setdefault("log", [])
        log.append("[%s] %s" % (time.strftime("%H:%M:%S"), text))
        if len(log) > 200:
            del log[:-200]


def firmware_snapshot():
    with firmware_lock:
        snap = dict(firmware_state)
        snap["log"] = list(firmware_state.get("log", []))
    return snap


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


def gs_usb_devices():
    ensure_libusb_backend()
    try:
        from gs_usb.gs_usb import GsUsb
    except Exception as e:
        return [{"error": "%s: %s" % (e.__class__.__name__, e)}]
    try:
        scanned = GsUsb.scan()
    except Exception as e:
        return [{"error": "%s: %s" % (e.__class__.__name__, e)}]
    devices = []
    for index, dev in enumerate(scanned):
        item = {
            "index": index,
            "bus": getattr(dev, "bus", None),
            "address": getattr(dev, "address", None),
            "serial": "",
            "icount": None,
            "fw_version": None,
            "hw_version": None,
        }
        try:
            item["serial"] = dev.serial_number or ""
        except Exception:
            pass
        try:
            info = dev.device_info
            item["icount"] = info.icount
            item["fw_version"] = info.fw_version
            item["hw_version"] = info.hw_version
        except Exception as e:
            item["info_error"] = "%s: %s" % (e.__class__.__name__, e)
        devices.append(item)
    return devices


def auto_cdc_port():
    candidates = []
    for item in list_ports():
        path = item["path"]
        if "usbmodem" in os.path.basename(path).lower() or item.get("likely"):
            candidates.append(path)
    return candidates[0] if candidates else ""


def adapter_status():
    ports = list_ports()
    cdc = auto_cdc_port()
    gs = gs_usb_devices()
    gs_ready = [item for item in gs if not item.get("error")]
    serial = bridge.snapshot()
    firmware = firmware_snapshot()
    if gs_ready:
        mode = "GS-USB"
    elif cdc:
        mode = "CDC serial"
    else:
        mode = "not found"
    return {
        "ok": True,
        "connected": bool(cdc or gs_ready or serial.get("open")),
        "mode": mode,
        "cdc": cdc,
        "ports": ports,
        "serial": serial,
        "firmware": {
            "running": firmware.get("running"),
            "status": firmware.get("status"),
            "name": firmware.get("name"),
            "percent": firmware.get("percent"),
            "error": firmware.get("error"),
        },
        "gs_usb": gs,
    }


def valid_frame(frame):
    return len(frame) >= 6 and frame[0] == 0xBB and frame[3] == len(frame) and (sum(frame[:-1]) & 0xFF) == frame[-1]


class CanLogger:
    def __init__(self):
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.threads = []
        self.state = {
            "ok": True,
            "running": False,
            "mode": "",
            "status": "idle",
            "startedAt": 0.0,
            "stoppedAt": 0.0,
            "profiles": {},
            "files": {},
            "error": "",
            "log": [],
        }

    def snapshot(self):
        with self.lock:
            snap = dict(self.state)
            snap["profiles"] = {k: dict(v) for k, v in self.state.get("profiles", {}).items()}
            snap["files"] = dict(self.state.get("files", {}))
            snap["log"] = list(self.state.get("log", []))
        if snap.get("running"):
            total = sum(int(item.get("frames", 0)) for item in snap.get("profiles", {}).values())
            if total == 0 and time.time() - float(snap.get("startedAt") or 0) > 2.0:
                snap["warning"] = "CDC raw logger is open, but 0 CAN frames received"
        snap["devices"] = {
            "cdc": auto_cdc_port(),
            "gs_usb": gs_usb_devices(),
        }
        return snap

    def log(self, line):
        with self.lock:
            self.state["status"] = str(line)
            log = self.state.setdefault("log", [])
            log.append("[%s] %s" % (time.strftime("%H:%M:%S"), line))
            if len(log) > 200:
                del log[:-200]

    def start(self, mode):
        mode = str(mode or "").lower()
        if mode == "both":
            keys = ["ccan", "mcan"]
        elif mode in CAN_PROFILES:
            keys = [mode]
        else:
            raise ValueError("mode must be ccan, mcan, or both")
        cdc_port = auto_cdc_port()
        if not cdc_port:
            raise RuntimeError("CDC raw CAN port not visible")
        if firmware_busy():
            raise RuntimeError("firmware update is active")
        self.stop(wait=True)
        if bridge.snapshot().get("open"):
            bridge.close()
            self.log("media serial closed for CAN logger")
        os.makedirs(CAN_LOG_ROOT, exist_ok=True)
        stamp = time.strftime("%Y%m%d_%H%M%S")
        files = {}
        profiles = {}
        for key in keys:
            profile = dict(CAN_PROFILES[key])
            profile["port"] = cdc_port
            path = os.path.join(CAN_LOG_ROOT, "%s_bus%d_%s.txt" % (key, profile["bus"], stamp))
            files[key] = path
            profiles[key] = {
                "label": profile["label"],
                "bus": profile["bus"],
                "port": profile["port"],
                "frames": 0,
                "bytes": 0,
                "lastId": "",
                "lastData": "",
                "lastAt": 0.0,
                "file": path,
            }
        self.stop_event.clear()
        with self.lock:
            self.state.update({
                "ok": True,
                "running": True,
                "mode": mode,
                "status": "starting " + mode,
                "startedAt": time.time(),
                "stoppedAt": 0.0,
                "profiles": profiles,
                "files": files,
                "error": "",
                "log": [],
            })
            self.threads = []
        thread = threading.Thread(target=self._run_profiles, args=(keys, profiles), daemon=True)
        with self.lock:
            self.threads.append(thread)
        thread.start()
        return self.snapshot()

    def stop(self, wait=False):
        with self.lock:
            running = bool(self.state.get("running"))
            threads = list(self.threads)
        self.stop_event.set()
        if wait:
            for thread in threads:
                if thread.is_alive():
                    thread.join(timeout=1.5)
        with self.lock:
            if running:
                self.state["running"] = False
                self.state["stoppedAt"] = time.time()
                self.state["status"] = "stopped"
            self.threads = [thread for thread in self.threads if thread.is_alive()]
        return self.snapshot()

    def _set_error(self, key, exc):
        message = "%s: %s" % (exc.__class__.__name__, exc)
        with self.lock:
            self.state["ok"] = False
            self.state["error"] = message
            profile = self.state.get("profiles", {}).get(key)
            if profile is not None:
                profile["error"] = message
        self.log("%s error: %s" % (key, message))

    def _run_profiles(self, keys, profiles):
        ser = None
        files = {}
        try:
            import serial
            port = auto_cdc_port()
            if not port:
                raise RuntimeError("CDC raw CAN port not visible")
            ser = serial.Serial(port, 19200, timeout=0.05, write_timeout=1)
            ser.reset_input_buffer()
            ser.reset_output_buffer()
            ser.write(adapter_packet(CMD_CANLOG, b"\x01"))
            ser.flush()
            ack = self._read_cdc_frame(ser, 1.0)
            self.log("CDC raw start %s%s" % (port, " ack " + hex_bytes(ack) if ack else " no ack"))
            for key in keys:
                profile = profiles[key]
                fh = open(profile["file"], "w", encoding="utf-8")
                files[key] = fh
                fh.write("# KIA CDC RAW CAN LOG\n")
                fh.write("# PROFILE %s\n" % key)
                fh.write("# LABEL %s\n" % profile["label"])
                fh.write("# PORT %s\n" % port)
                fh.write("# BUS %s\n" % profile["bus"])
                fh.write("# START %s\n" % time.strftime("%Y-%m-%d %H:%M:%S %z"))
                fh.write("# FORMAT ts bus=N STD|EXT can_id dlc=N datahex\n")
                fh.flush()
                self.log("%s logging to %s" % (profile["label"], profile["file"]))
            while not self.stop_event.is_set():
                ser.write(adapter_packet(CMD_CAN_RING_READ))
                ser.flush()
                frame = self._read_cdc_frame(ser, 0.25)
                decoded = self._decode_cdc_can_frame(frame) if frame else None
                if not decoded:
                    continue
                frame_bus = int(decoded["bus"])
                target_key = None
                for key in keys:
                    if int(profiles[key]["bus"]) == frame_bus:
                        target_key = key
                        break
                if target_key is None:
                    continue
                profile = profiles[target_key]
                fh = files[target_key]
                line = "%.6f bus=%d %s %08X dlc=%d %s\n" % (
                    time.time(),
                    frame_bus,
                    "EXT" if decoded["ext"] else "STD",
                    decoded["can_id"],
                    decoded["dlc"],
                    decoded["data"],
                )
                fh.write(line)
                with self.lock:
                    state_profile = self.state.get("profiles", {}).get(target_key)
                    if state_profile is not None:
                        state_profile["frames"] = int(state_profile.get("frames", 0)) + 1
                        state_profile["bytes"] = int(state_profile.get("bytes", 0)) + decoded["dlc"]
                        state_profile["lastId"] = "0x%03X" % decoded["can_id"]
                        state_profile["lastData"] = decoded["data"]
                        state_profile["lastAt"] = time.time()
                if int(time.time() * 10) % 10 == 0:
                    for opened in files.values():
                        opened.flush()
            for key, fh in files.items():
                fh.write("# STOP %s\n" % time.strftime("%Y-%m-%d %H:%M:%S %z"))
                fh.flush()
        except Exception as e:
            self._set_error(",".join(keys), e)
        finally:
            if ser is not None:
                try:
                    ser.write(adapter_packet(CMD_CANLOG, b"\x00"))
                    ser.flush()
                except Exception:
                    pass
                try:
                    ser.close()
                except Exception:
                    pass
            for fh in files.values():
                try:
                    fh.close()
                except Exception:
                    pass
            with self.lock:
                alive = any(thread.is_alive() and thread is not threading.current_thread()
                            for thread in self.threads)
                if not alive and self.state.get("running"):
                    self.state["running"] = False
                    self.state["stoppedAt"] = time.time()
                    if not self.state.get("error"):
                        self.state["status"] = "stopped"

    @staticmethod
    def _read_cdc_frame(ser, timeout):
        deadline = time.time() + timeout
        buf = bytearray()
        while time.time() < deadline:
            chunk = ser.read(1)
            if not chunk:
                continue
            byte = chunk[0]
            if not buf and byte != 0xBB:
                continue
            buf.append(byte)
            if len(buf) == 4 and not (6 <= buf[3] <= 64):
                buf.clear()
                continue
            if len(buf) >= 4 and len(buf) == buf[3]:
                frame = bytes(buf)
                if valid_frame(frame):
                    return frame
                buf.clear()
        return None

    @staticmethod
    def _decode_cdc_can_frame(frame):
        if not frame or len(frame) != 23 or frame[4] != CMD_CAN_RING_READ:
            return None
        if frame[5] == 0:
            return None
        bus = frame[6]
        flags = frame[7]
        dlc = max(0, min(frame[8], 8))
        can_id = frame[10] | (frame[11] << 8) | (frame[12] << 16) | (frame[13] << 24)
        data = frame[14:14 + dlc].hex().upper()
        return {
            "bus": bus,
            "ext": bool(flags & 1),
            "rtr": bool(flags & 2),
            "can_id": can_id,
            "dlc": dlc,
            "data": data,
        }

    @staticmethod
    def _ensure_requested_channels(dev, profiles):
        try:
            icount = int(dev.device_info.icount)
        except Exception:
            icount = 1
        missing = []
        for key, profile in profiles.items():
            channel = int(profile["channel"])
            if channel >= icount:
                missing.append("%s needs ch%d" % (key, channel))
        if missing:
            raise RuntimeError(
                "GS-USB firmware exposes icount=%d; %s. Flash two-channel logger firmware for C-CAN."
                % (icount, ", ".join(missing))
            )

    @staticmethod
    def _reset_gs_usb_channels(dev, channels, gs_mod=None, DeviceMode=None):
        if gs_mod is None:
            import gs_usb.gs_usb as gs_mod
        if DeviceMode is None:
            from gs_usb.gs_usb_structures import DeviceMode
        seen = []
        for channel in channels:
            channel = int(channel)
            if channel in seen:
                continue
            seen.append(channel)
            try:
                dev.gs_usb.ctrl_transfer(
                    0x41,
                    gs_mod._GS_USB_BREQ_MODE,
                    0,
                    channel,
                    DeviceMode(gs_mod.GS_CAN_MODE_RESET, 0).pack(),
                )
            except Exception:
                pass

    @staticmethod
    def _configure_gs_usb(dev, channel, bitrate, gs_mod):
        clock = int(dev.device_capability.fclk_can)
        if clock == 36000000 and bitrate in {100000, 500000}:
            brp = clock // (bitrate * 18)
            from gs_usb.gs_usb_structures import DeviceBitTiming
            timing = DeviceBitTiming(1, 14, 2, 1, brp)
            dev.gs_usb.ctrl_transfer(
                0x41,
                gs_mod._GS_USB_BREQ_BITTIMING,
                0,
                int(channel),
                timing.pack(),
            )
            return
        if int(channel) == 0 and dev.set_bitrate(bitrate):
            return
        raise RuntimeError("unsupported GS-USB bitrate %s at clock %s channel %s" % (bitrate, clock, channel))


CAN_LOGGER = CanLogger()


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def known_firmware_files():
    files = {}
    for root in FIRMWARE_ROOTS:
        if not os.path.isdir(root):
            continue
        for path in glob.glob(os.path.join(root, "**", "*.bin"), recursive=True):
            real = os.path.realpath(path)
            if real in files:
                continue
            try:
                size = os.path.getsize(real)
            except OSError:
                continue
            name = os.path.basename(real)
            lower = name.lower()
            files[real] = {
                "path": real,
                "name": name,
                "size": size,
                "usb": "_usb" in lower or lower.endswith("usb.bin"),
                "stlink": "stlink" in lower,
                "tooLarge": size > MAX_FIRMWARE_SIZE,
                "sha256": file_sha256(real) if size <= MAX_FIRMWARE_SIZE else "",
            }
    def score(item):
        name = item["name"].lower()
        value = 0
        if "/trusted/" in item["path"]:
            value -= 1000
        if "v25" in item["path"].lower() or name.startswith("25_"):
            value -= 200
        if item["usb"]:
            value -= 100
        if item["stlink"]:
            value += 500
        return (value, item["name"], item["path"])
    return sorted(files.values(), key=score)


def load_firmware_from_request(data):
    if not data.get("confirm"):
        raise ValueError("missing flash confirmation")
    if data.get("path"):
        real = os.path.realpath(str(data.get("path")))
        allowed = {item["path"] for item in known_firmware_files()}
        if real not in allowed:
            raise ValueError("firmware path is not in the known BIN list")
        with open(real, "rb") as fh:
            blob = fh.read(MAX_FIRMWARE_SIZE + 1)
        return os.path.basename(real), blob
    encoded = str(data.get("dataB64") or "")
    if not encoded:
        raise ValueError("missing firmware data")
    if encoded.startswith("data:") and "," in encoded:
        encoded = encoded.split(",", 1)[1]
    blob = base64.b64decode(encoded, validate=True)
    name = os.path.basename(str(data.get("name") or "uploaded.bin")) or "uploaded.bin"
    return name, blob


def checksum(frame):
    if not frame:
        return frame
    out = bytearray(frame)
    length = out[3] if len(out) > 3 else len(out)
    out[length - 1] = sum(out[: length - 1]) & 0xFF
    return bytes(out)


def adapter_packet(command, payload=b""):
    payload = bytes(payload)
    frame = bytearray(6 + len(payload))
    frame[0] = 0xBB
    frame[1] = 0x41
    frame[2] = 0xA1
    frame[3] = len(frame)
    frame[4] = command & 0xFF
    frame[5 : 5 + len(payload)] = payload
    return checksum(frame)


def firmware_start_packet():
    return adapter_packet(CMD_FIRMWARE, b"\x01")


def firmware_finish_packet():
    return adapter_packet(CMD_FIRMWARE, b"\x00")


def firmware_block_packet(offset, block):
    frame = bytearray(25)
    frame[0] = 0xBB
    frame[1] = 0x41
    frame[2] = 0xA1
    frame[3] = 25
    frame[4] = CMD_FIRMWARE
    frame[5] = (offset >> 16) & 0xFF
    frame[6] = (offset >> 8) & 0xFF
    frame[7] = offset & 0xFF
    frame[8:24] = b"\xFF" * 16
    block = bytes(block or b"")
    frame[8:8 + min(16, len(block))] = block[:16]
    return checksum(frame)


def parse_firmware_acks(data):
    out = []
    i = 0
    while i <= len(data) - 6:
        if data[i] != 0xBB or not (
            data[i + 1] == 0x41 and data[i + 2] == 0xA1
            or data[i + 1] == 0xA1 and data[i + 2] == 0x41
        ):
            i += 1
            continue
        length = data[i + 3]
        if length < 6 or length > 64:
            i += 1
            continue
        if i + length > len(data):
            break
        frame = data[i:i + length]
        if frame[4] == CMD_FIRMWARE:
            out.append((frame[5], frame))
        i += length
    return out


def wait_firmware_ack(expected, timeout=1.2):
    deadline = time.time() + timeout
    last_ack = -1
    last_rx = b""
    while time.time() < deadline:
        data = bridge.take_rx()
        if data:
            last_rx += data
            set_firmware_state(lastRx=hex_bytes(last_rx[-256:]))
            for ack, frame in parse_firmware_acks(data):
                last_ack = ack
                set_firmware_state(lastAck=ack, lastRx=hex_bytes(frame))
                if ack == expected:
                    return True, ack
                if ack == 0:
                    return False, ack
        time.sleep(0.02)
    set_firmware_state(lastAck=last_ack)
    return False, last_ack


def reopen_serial_after_reset(previous_port="", previous_baud=115200, attempts=8):
    snap = bridge.snapshot()
    port_hint = previous_port or snap.get("port") or snap.get("last_port") or ""
    baud = int(previous_baud or snap.get("baud") or 115200)
    bridge.close()
    for attempt in range(1, attempts + 1):
        candidates = []
        if port_hint:
            candidates.append(port_hint)
        candidates.extend(item["path"] for item in list_ports() if item["likely"])
        candidates.extend(item["path"] for item in list_ports() if not item["likely"])
        seen = set()
        for port in candidates:
            if not port or port in seen:
                continue
            seen.add(port)
            if not os.path.exists(port):
                continue
            try:
                bridge.open(port, baud)
                firmware_log("serial reopened %s" % port)
                return True
            except OSError as e:
                firmware_log("reopen attempt %d failed on %s: %s" % (attempt, port, e))
        time.sleep(0.6)
    return False


def start_firmware_flash(name, firmware):
    if not bridge.snapshot().get("open"):
        raise RuntimeError("serial port is not open")
    if firmware_busy():
        raise RuntimeError("firmware update is already running")
    if not firmware:
        raise ValueError("empty firmware")
    if len(firmware) > MAX_FIRMWARE_SIZE:
        raise ValueError("firmware too large")
    digest = hashlib.sha256(firmware).hexdigest()
    with firmware_lock:
        firmware_state.update({
            "ok": True,
            "running": True,
            "done": False,
            "error": "",
            "status": "starting",
            "name": name,
            "bytes": len(firmware),
            "sha256": digest,
            "blocksDone": 0,
            "blocksTotal": (len(firmware) + 15) // 16,
            "percent": 0,
            "lastAck": -1,
            "lastRx": "",
            "startedAt": time.time(),
            "finishedAt": 0.0,
            "log": [],
        })
    thread = threading.Thread(target=firmware_flash_worker, args=(name, firmware), daemon=True)
    thread.start()
    return digest


def firmware_flash_worker(name, firmware):
    try:
        start_snap = bridge.snapshot()
        previous_port = start_snap.get("port") or start_snap.get("last_port") or ""
        previous_baud = start_snap.get("baud") or 115200
        firmware_log("enter bootloader")
        started = False
        for attempt in range(1, 11):
            bridge.clear_rx()
            try:
                bridge.write(firmware_start_packet())
            except OSError as e:
                firmware_log("start write reset on attempt %d: %s" % (attempt, e))
                if reopen_serial_after_reset(previous_port, previous_baud):
                    continue
                raise
            ok, ack = wait_firmware_ack(1, 1.2)
            if ok:
                started = True
                firmware_log("bootloader ACK 0x01")
                break
            if not bridge.snapshot().get("open"):
                reopen_serial_after_reset(previous_port, previous_baud)
            firmware_log("start retry %d, last ACK 0x%02X" % (attempt, ack if ack >= 0 else 0xFF))
        if not started:
            raise RuntimeError("adapter did not enter firmware mode")

        blocks = (len(firmware) + 15) // 16
        for index in range(blocks):
            offset = index * 16
            block = firmware[offset:offset + 16]
            accepted = False
            for attempt in range(1, 4):
                bridge.clear_rx()
                try:
                    bridge.write(firmware_block_packet(offset, block))
                except OSError as e:
                    firmware_log("block %d write reset on retry %d: %s" % (index, attempt, e))
                    if reopen_serial_after_reset(previous_port, previous_baud):
                        continue
                    raise
                ok, ack = wait_firmware_ack(2, 1.2)
                if ack == 0:
                    raise RuntimeError("adapter cancelled at block %d" % index)
                if ok:
                    accepted = True
                    break
                if not bridge.snapshot().get("open"):
                    reopen_serial_after_reset(previous_port, previous_baud)
                firmware_log("block %d retry %d, last ACK 0x%02X" % (index, attempt, ack if ack >= 0 else 0xFF))
            if not accepted:
                raise RuntimeError("no ACK for block %d" % index)
            percent = int(round((index + 1) * 100.0 / blocks))
            set_firmware_state(blocksDone=index + 1, percent=percent)
            if index == 0 or index == blocks - 1 or (index + 1) % 16 == 0:
                firmware_log("blocks %d/%d (%d%%)" % (index + 1, blocks, percent))

        bridge.clear_rx()
        try:
            bridge.write(firmware_finish_packet())
        except OSError as e:
            firmware_log("finish write reset: %s" % e)
        firmware_log("finish sent, adapter may reboot")
        set_firmware_state(running=False, done=True, finishedAt=time.time(), percent=100)
    except Exception as e:
        firmware_log("error: %s" % e)
        set_firmware_state(ok=False, running=False, done=False, error=str(e), finishedAt=time.time())


def raise_frame(payload):
    payload = bytes(payload)
    frame = bytearray(2 + len(payload))
    frame[0] = 0xFD
    frame[1 : 1 + len(payload)] = payload
    frame[-1] = sum(frame[1:-1]) & 0xFF
    return bytes(frame)


media_second = 1
media_lock = threading.Lock()


def next_second():
    global media_second
    with media_lock:
        media_second = (media_second + 1) % 60
        return media_second


def source_value(source, detail=""):
    value = int(source) & 0xFF
    if value == 0x02:
        return fm_source(detail)
    if value == 0x09:
        return am_source(detail)
    if value == 0x0B:
        return raise_frame([0x06, 0x09, 0x0B, 0x00, 0x00])
    if value == 0x0E:
        call_type = 0x03 if str(detail or "").lower() in {"out", "outgoing", "3", "03"} else 0x01
        return raise_frame([0x06, 0x09, 0x0E, call_type, 0x00])
    if value == 0x07:
        return raise_frame([0x06, 0x09, 0x07, 0x02, 0x00])
    if value == 0x11:
        return raise_frame([0x06, 0x09, 0x11, 0x00, 0x00])
    if value == 0x25:
        return raise_frame([0x06, 0x09, 0x25, 0x00, 0x00])
    if value == 0x80:
        return raise_frame([0x06, 0x09, 0x81, 0x00, 0x00])
    if value == 0x16:
        return usb_source()
    return raise_frame([0x06, 0x09, value, 0x00, 0x00])


def fm_source(detail):
    mhz10 = parse_fm_mhz10(detail)
    whole = mhz10 // 10
    decimal_tens = (mhz10 % 10) * 10
    return raise_frame([0x08, 0x09, 0x02, 0x00, whole & 0xFF, decimal_tens & 0xFF, 0x00])


def am_source(detail):
    khz = parse_am_khz(detail)
    high = (khz >> 8) & 0xFF
    low = khz & 0xFF
    return raise_frame([0x08, 0x09, 0x09, 0x04, high, low, 0x00])


def am_old_source():
    return raise_frame([0x06, 0x09, 0x09, 0x00, 0x00])


def usb_source():
    second = next_second()
    return raise_frame([0x0A, 0x09, 0x16, 0x00, 0x01, 0x00, 0x00, second, 0x00])


def parse_fm_mhz10(value):
    text = str(value or "")
    digits = ""
    sep = False
    for ch in text:
        if ch.isdigit():
            digits += ch
            continue
        if ch in ".," and not sep:
            sep = True
            digits += "."
            continue
        if digits:
            break
    try:
        parsed = float(digits) if digits else 0.0
    except ValueError:
        parsed = 0.0
    if parsed <= 0:
        parsed = 101.0
    if parsed > 1000:
        parsed = parsed / 1000.0
    return max(0, min(2550, int(round(parsed * 10.0))))


def parse_am_khz(value):
    text = str(value or "")
    digits = ""
    for ch in text:
        if ch.isdigit():
            digits += ch
        elif digits:
            break
    try:
        parsed = int(digits) if digits else 0
    except ValueError:
        parsed = 0
    if parsed <= 0:
        parsed = 999
    return max(0, min(0xFFFF, parsed))


def source_packet(source, detail=""):
    return adapter_packet(CMD_SOURCE_STATUS, source_value(source, detail))


def am_old_source_packet():
    return adapter_packet(CMD_SOURCE_STATUS, am_old_source())


def media_off():
    return adapter_packet(CMD_SOURCE_STATUS, source_value(0x80))


def nav_on_packet(active):
    return checksum(bytes([0xBB, 0x41, 0xA1, 0x07, CMD_NAV_ON, 0x01 if active else 0x00, 0x00]))


def nav_text_packet(value):
    text = str(value or "").strip()
    if not text:
        return checksum(bytes([0xBB, 0x41, 0xA1, 0x09, CMD_NAV_TEXT, 0xF0, 0x00, 0x00, 0x00]))
    payload = text.encode("utf-16le")[:56]
    payload = payload[:len(payload) & ~1]
    frame = bytearray(7 + len(payload))
    frame[0] = 0xBB
    frame[1] = 0x41
    frame[2] = 0xA1
    frame[3] = len(frame)
    frame[4] = CMD_NAV_TEXT
    frame[5] = 0xF0
    frame[6:6 + len(payload)] = payload
    return checksum(frame)


def parse_kmh(value):
    text = str(value or "").strip()
    digits = ""
    for ch in text:
        if ch.isdigit():
            digits += ch
        elif digits:
            break
    try:
        kmh = int(digits) if digits else 0
    except ValueError:
        kmh = 0
    return max(0, min(255, kmh))


def parse_byte(value, default=0):
    text = str(value if value is not None else "").strip().lower()
    if not text:
        return default & 0xFF
    try:
        if text.startswith("0x"):
            parsed = int(text, 16)
        elif any(ch in text for ch in "abcdef"):
            parsed = int(text, 16)
        else:
            parsed = int(text, 10)
    except ValueError:
        parsed = default
    return max(0, min(255, parsed))


def speed_limit_packet(kmh, alarm=0x04, info=0x95):
    value = parse_kmh(kmh)
    return checksum(bytes([0xBB, 0x41, 0xA1, 0x09, CMD_SPEED_LIMIT,
                           value & 0xFF, parse_byte(alarm, 0x04), parse_byte(info, 0x95), 0x00]))


def parse_distance_m(value):
    text = str(value or "").strip().replace(",", ".")
    try:
        parsed = float(text) if text else 0.0
    except ValueError:
        parsed = 0.0
    return max(0.0, min(9999.9, parsed))


def parse_progress(value):
    try:
        parsed = int(float(str(value or "0").strip()))
    except ValueError:
        parsed = 0
    return max(0, min(9, parsed))


def nav_maneuver_packet(icon, distance_m, progress, mode="classic"):
    icon = str(icon or "right").lower()
    mode = str(mode or "classic").lower()
    whole = int(parse_distance_m(distance_m))
    tenth = round((parse_distance_m(distance_m) - whole) * 10.0) & 0x0F
    progress = parse_progress(progress)
    frame = bytearray([0xBB, 0x41, 0xA1, 0x0E, CMD_MANEUVER,
                       0x0D, 0x00, 0x00, 0x09,
                       (whole >> 8) & 0xFF, whole & 0xFF,
                       0x00, ((progress & 0x0F) << 4) | tenth, 0x00])
    if mode == "tbt":
        mapping = {
            "forward": (0x41, 0x00, 0x00, 0x00),
            "left": (0x46, 0x00, 0x00, 0x00),
            "right": (0x43, 0x00, 0x00, 0x00),
        }
    else:
        mapping = {
            "forward": (0x0D, 0x00, 0x01, 0x00),
            "left": (0x0D, 0x00, 0x00, 0x24),
            "right": (0x0D, 0x00, 0x00, 0x0C),
        }
    frame[5], frame[6], frame[7], frame[8] = mapping.get(icon, mapping["right"])
    return checksum(frame)


def nav_lane_packet(data, b8_override=None):
    whole = int(parse_distance_m(data.get("distance", 80)))
    tenth = round((parse_distance_m(data.get("distance", 80)) - whole) * 10.0) & 0x0F
    progress = parse_progress(data.get("progress", 8))
    b5 = parse_byte(data.get("b5"), 0x0D)
    b6 = parse_byte(data.get("b6"), 0x00)
    b7 = parse_byte(data.get("b7"), 0x00)
    b8 = parse_byte(b8_override if b8_override is not None else data.get("b8"), 0x0C)
    frame = bytearray([0xBB, 0x41, 0xA1, 0x0E, CMD_MANEUVER,
                       b5, b6, b7, b8,
                       (whole >> 8) & 0xFF, whole & 0xFF,
                       0x00, ((progress & 0x0F) << 4) | tenth, 0x00])
    return checksum(frame)


def lane_byte_summary(data, b8_override=None):
    b5 = parse_byte(data.get("b5"), 0x0D)
    b6 = parse_byte(data.get("b6"), 0x00)
    b7 = parse_byte(data.get("b7"), 0x00)
    b8 = parse_byte(b8_override if b8_override is not None else data.get("b8"), 0x0C)
    return f"b5={b5:02X} b6={b6:02X} b7={b7:02X} b8={b8:02X}"


def lane_note_path():
    os.makedirs(os.path.dirname(NAV_LANE_NOTES), exist_ok=True)
    return NAV_LANE_NOTES


def save_lane_note(data):
    note = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "bytes": lane_byte_summary(data),
        "b5": f"{parse_byte(data.get('b5'), 0x0D):02X}",
        "b6": f"{parse_byte(data.get('b6'), 0x00):02X}",
        "b7": f"{parse_byte(data.get('b7'), 0x00):02X}",
        "b8": f"{parse_byte(data.get('b8'), 0x0C):02X}",
        "distance_m": parse_distance_m(data.get("distance", 80)),
        "progress": parse_progress(data.get("progress", 8)),
        "label": str(data.get("label") or "").strip(),
        "photo": str(data.get("photo") or "").strip(),
    }
    path = lane_note_path()
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(note, ensure_ascii=False) + "\n")
    return path, note


def navi_frames(data_or_action, speed=None, alarm=0x04, info=0x95):
    if isinstance(data_or_action, dict):
        data = data_or_action
        action = str(data.get("action") or "").lower()
        speed = data.get("speed", speed)
        alarm = data.get("alarm", alarm)
        info = data.get("info", info)
    else:
        data = {}
        action = str(data_or_action or "").lower()
    action = str(action or "").lower()
    if action == "nav_on":
        return [("nav on 0x48", nav_on_packet(True))]
    if action == "nav_off":
        return [("nav off 0x48", nav_on_packet(False))]
    if action == "text":
        return [("nav text 0x4A", nav_text_packet(data.get("text") or "Classic progress"))]
    if action == "maneuver":
        distance = parse_distance_m(data.get("distance", 40))
        progress = parse_progress(data.get("progress", 4))
        mode = data.get("mode") or "classic"
        icon = data.get("icon") or "right"
        return [
            ("nav on 0x48", nav_on_packet(True)),
            ("nav maneuver %s %s %.1fm progress=%d" % (mode, icon, distance, progress),
             nav_maneuver_packet(icon, distance, progress, mode)),
        ]
    if action == "sweep":
        direction = str(data.get("direction") or "down").lower()
        base_distance = parse_distance_m(data.get("distance", 40))
        mode = data.get("mode") or "classic"
        icon = data.get("icon") or "right"
        invert = bool(data.get("invert"))
        raw_values = [9, 7, 5, 4, 2, 0] if direction != "up" else [0, 2, 4, 5, 7, 9]
        frames = [
            ("nav on 0x48", nav_on_packet(True)),
            ("nav text 0x4A", nav_text_packet(data.get("text") or "Progress sweep")),
        ]
        for raw_progress in raw_values:
            progress = 9 - raw_progress if invert else raw_progress
            frames.append((
                "sweep %s %s %.1fm raw=%d sent=%d" % (mode, icon, base_distance, raw_progress, progress),
                nav_maneuver_packet(icon, base_distance, progress, mode),
            ))
        return frames
    if action == "lane_combo":
        return [
            ("nav on 0x48", nav_on_packet(True)),
            ("lane combo 0x45 %s" % lane_byte_summary(data), nav_lane_packet(data)),
        ]
    if action == "lane_sweep":
        kind = str(data.get("kind") or "gray18").lower()
        values = list(range(0x12)) if kind != "known" else [0x00, 0x02, 0x03, 0x06, 0x09, 0x0C, 0x12, 0x1E, 0x24, 0x2D]
        frames = [("nav on 0x48", nav_on_packet(True))]
        for value in values:
            frames.append((
                "lane gray b8=0x%02X %s" % (value, lane_byte_summary(data, value)),
                nav_lane_packet(data, value),
            ))
        return frames
    if action == "speed_quick":
        kmh = parse_kmh(speed)
        alarm_byte = 0x08 if kmh > 0 else 0x04
        info_byte = parse_byte(info, 0x95)
        if kmh <= 0:
            return [
                ("speed clear 0x44 alarm=0x04 info=0x%02X" % info_byte,
                 speed_limit_packet(0, 0x04, info_byte)),
                ("nav off 0x48", nav_on_packet(False)),
            ]
        return [
            ("nav on 0x48", nav_on_packet(True)),
            ("speed limit %d 0x44 alarm=0x%02X info=0x%02X" % (kmh, alarm_byte, info_byte),
             speed_limit_packet(kmh, alarm_byte, info_byte)),
        ]
    if action == "speed":
        kmh = parse_kmh(speed)
        alarm_byte = parse_byte(alarm, 0x04)
        info_byte = parse_byte(info, 0x95)
        return [(f"speed limit {kmh} 0x44 alarm=0x{alarm_byte:02X} info=0x{info_byte:02X}",
                 speed_limit_packet(kmh, alarm_byte, info_byte))]
    raise ValueError("unknown navi action")


CLIMATE_COMMANDS = [
    {
        "key": "power",
        "button": "POWER",
        "car": "6",
        "on": "FD 0A 03 10 0D 01 02 FF 00 01 2C",
        "off": "FD 0A 03 FF FF 00 02 FF 00 03 0C",
    },
    {
        "key": "ac",
        "button": "A/C",
        "car": "9",
        "on": "FD 0A 03 10 0D 01 03 FF 00 01 2D",
        "off": "FD 0A 03 10 0D 01 02 FF 00 01 2C",
    },
    {
        "key": "max",
        "button": "MAX TX-only (popup не подтверждён)",
        "car": "при скорости обдува 8",
        "on": "FD 06 83 BB 02 01 46",
        "off": "FD 06 83 BB 00 01 44",
        "note": "Чистая TX-команда AC_MAX из RaiseHyundaiKia setAirCondition: 0x7537 -> 83 BB 02/00. По ADB доходит как rxData 83 6 bb 2, но штатный popup не обновляет MAX.",
    },
    {
        "key": "seat_left_heat",
        "button": "Левый/водительский обогрев сиденья RX, уровень 1",
        "car": "11 / popup Seats",
        "on": "FD 06 52 06 01 00 5F",
        "off": "FD 06 52 06 00 00 5E",
        "note": "Проверено по ADB: RaiseHyundaiKia дает CarInfo Seats cmd=40001.",
    },
    {
        "key": "seat_left_heat_l2",
        "button": "Левый/водительский обогрев сиденья RX, уровень 2",
        "car": "11 / popup Seats",
        "on": "FD 06 52 06 02 00 60",
        "off": "FD 06 52 06 00 00 5E",
        "note": "RX 0x52 item 0x06 value 2.",
    },
    {
        "key": "seat_left_heat_l3",
        "button": "Левый/водительский обогрев сиденья RX, уровень 3",
        "car": "11 / popup Seats",
        "on": "FD 06 52 06 03 00 61",
        "off": "FD 06 52 06 00 00 5E",
        "note": "RX 0x52 item 0x06 value 3.",
    },
    {
        "key": "seat_left_heat_l1_alt0",
        "button": "Левый обогрев сиденья, старый TX AD byte00 (не popup)",
        "car": "11 / TX only",
        "on": "FD 06 83 AD 01 00 37",
        "off": "FD 06 83 AD 00 00 36",
        "note": "Это TX-команда canbox; при отправке в магнитолу не создает Seats popup.",
    },
    {
        "key": "seat_left_vent_l1",
        "button": "Левый/водительский обдув сиденья TX-only, уровень 1",
        "car": "TX 0x83 BF",
        "on": "FD 06 83 BF 01 01 49",
        "off": "FD 06 83 BF 00 01 48",
        "note": "По setSeats это водительская вентиляция/охлаждение: 0x9C42 arg1=0 -> 83 BF. Текущий CarInfoService видит rxData, но штатно не дает Seats popup.",
    },
    {
        "key": "seat_left_vent_l2",
        "button": "Левый/водительский обдув сиденья TX-only, уровень 2",
        "car": "TX 0x83 BF",
        "on": "FD 06 83 BF 02 01 4A",
        "off": "FD 06 83 BF 00 01 48",
        "note": "По setSeats это водительская вентиляция/охлаждение: 0x9C42 arg1=0 -> 83 BF. Текущий CarInfoService видит rxData, но штатно не дает Seats popup.",
    },
    {
        "key": "seat_left_vent_l3",
        "button": "Левый/водительский обдув сиденья TX-only, уровень 3",
        "car": "TX 0x83 BF",
        "on": "FD 06 83 BF 03 01 4B",
        "off": "FD 06 83 BF 00 01 48",
        "note": "По setSeats это водительская вентиляция/охлаждение: 0x9C42 arg1=0 -> 83 BF. Текущий CarInfoService видит rxData, но штатно не дает Seats popup.",
    },
    {
        "key": "recirculation",
        "button": "Рециркуляция",
        "car": "10",
        "on": "FD 0A 03 10 0D 00 87 FF 00 01 B0",
        "off": "FD 0A 03 10 0D 00 07 FF 00 01 30",
    },
    {
        "key": "aqs",
        "button": "AQS",
        "car": "нету",
        "on": "FD 06 52 02 01 00 5B",
        "off": "FD 06 52 02 00 00 5A",
    },
    {
        "key": "auto_small_fan",
        "button": "AUTO обычный, маленький вентилятор",
        "car": "14",
        "on": "FD 0A 03 10 0D 00 07 FF 00 01 30",
        "off": "FD 0A 03 10 0D 00 03 FF 00 01 2C",
    },
    {
        "key": "auto_windshield_big_fan",
        "button": "AUTO большой вентилятор (AIR_AUTO_LEVEL)",
        "car": "7",
        "on": "FD 0B 03 10 0D 01 00 FF 00 08 01 33",
        "off": "FD 0B 03 10 0D 01 00 FF 00 00 01 2B",
        "note": "RX airAutoLevel: cmd 0x75aa из v2[8] bit3. Тестер меняет только bit 0x08 длинного 0B 03 кадра; штатный парсер вместе даёт rearAuto cmd=30025.",
    },
    {
        "key": "rear_defrost",
        "button": "Обогрев заднего стекла",
        "car": "8",
        "on": "FD 0A 03 10 0D 00 22 FF 00 01 4B",
        "off": "FD 0A 03 10 0D 00 02 FF 00 01 2B",
    },
    {
        "key": "dual",
        "button": "DUAL",
        "car": "13",
        "on": "FD 0A 03 10 0D 00 02 FF 00 01 2B",
        "off": "FD 0A 03 10 0D 00 00 FF 00 01 29",
    },
    {
        "key": "lock_car",
        "button": "Машинка с замочком",
        "car": "двери закрыты",
        "on": "FD 0B 03 10 0D 00 00 FF 00 40 01 6A",
        "off": "FD 0B 03 10 0D 00 00 FF 00 00 01 2A",
        "note": "RX rearAirLock без sync/DUAL bit, чтобы замок не тащил DUAL.",
    },
    {
        "key": "seat_right_heat_l1",
        "button": "Правый обогрев сиденья TX-only, уровень 1",
        "car": "12 / TX only",
        "on": "FD 06 83 AE 01 01 38",
        "off": "FD 06 83 AE 00 01 37",
        "note": "Чистый passenger heat TX: setSeats HEATED arg1=1 -> 83 AE. В RX-парсере RaiseHyundaiKia нет passengerHeat для popup.",
    },
    {
        "key": "seat_right_heat_l2",
        "button": "Правый обогрев сиденья TX-only, уровень 2",
        "car": "12 / TX only",
        "on": "FD 06 83 AE 02 01 39",
        "off": "FD 06 83 AE 00 01 37",
        "note": "Чистый passenger heat TX: setSeats HEATED arg1=1 -> 83 AE. В RX-парсере RaiseHyundaiKia нет passengerHeat для popup.",
    },
    {
        "key": "seat_right_heat_l3",
        "button": "Правый обогрев сиденья TX-only, уровень 3",
        "car": "12 / TX only",
        "on": "FD 06 83 AE 03 01 3A",
        "off": "FD 06 83 AE 00 01 37",
        "note": "Чистый passenger heat TX: setSeats HEATED arg1=1 -> 83 AE. В RX-парсере RaiseHyundaiKia нет passengerHeat для popup.",
    },
    {
        "key": "seat_right_vent_l1",
        "button": "Правый/пассажирский обдув сиденья TX-only, уровень 1",
        "car": "TX 0x83 C0",
        "on": "FD 06 83 C0 01 01 4A",
        "off": "FD 06 83 C0 00 01 49",
        "note": "По setSeats это пассажирская вентиляция/охлаждение: 0x9C42 arg1=1 -> 83 C0. Текущий CarInfoService видит rxData, но штатно не дает Seats popup.",
    },
    {
        "key": "seat_right_vent_l2",
        "button": "Правый/пассажирский обдув сиденья TX-only, уровень 2",
        "car": "TX 0x83 C0",
        "on": "FD 06 83 C0 02 01 4B",
        "off": "FD 06 83 C0 00 01 49",
        "note": "По setSeats это пассажирская вентиляция/охлаждение: 0x9C42 arg1=1 -> 83 C0. Текущий CarInfoService видит rxData, но штатно не дает Seats popup.",
    },
    {
        "key": "seat_right_vent_l3",
        "button": "Правый/пассажирский обдув сиденья TX-only, уровень 3",
        "car": "TX 0x83 C0",
        "on": "FD 06 83 C0 03 01 4C",
        "off": "FD 06 83 C0 00 01 49",
        "note": "По setSeats это пассажирская вентиляция/охлаждение: 0x9C42 arg1=1 -> 83 C0. Текущий CarInfoService видит rxData, но штатно не дает Seats popup.",
    },
    {
        "key": "face_air",
        "button": "Обдув в лицо",
        "car": "4",
        "on": "FD 0A 03 10 0D 00 8B FF 00 01 B4",
        "off": "FD 0A 03 10 0D 00 83 FF 00 01 AC",
    },
    {
        "key": "windshield_air",
        "button": "Обдув вверх",
        "car": "3",
        "on": "FD 0B 03 10 0D 00 02 FF 00 01 01 2D",
        "off": "FD 0B 03 10 0D 00 02 FF 00 00 01 2C",
    },
    {
        "key": "feet_air",
        "button": "Обдув ноги",
        "car": "5",
        "on": "FD 0A 03 10 0D 00 9B FF 00 01 C4",
        "off": "FD 0A 03 10 0D 00 8B FF 00 01 B4",
    },
    {
        "key": "fan_down",
        "button": "Скорость меньше",
        "car": "1",
        "on": "FD 0A 03 10 0D 01 00 FF 00 01 2A",
        "off": "FD 0A 03 10 0D 02 00 FF 00 01 2B",
        "note": "RX state: меняет windLevel, вместо старого TX 8A 0D.",
    },
    {
        "key": "fan_up",
        "button": "Скорость больше",
        "car": "2",
        "on": "FD 0A 03 10 0D 02 00 FF 00 01 2B",
        "off": "FD 0A 03 10 0D 03 00 FF 00 01 2C",
        "note": "RX state: меняет windLevel, вместо старого TX 8A 0C.",
    },
    {
        "key": "popup_climate",
        "button": "Открыть popup климата",
        "car": "кнопка слева",
        "on": "FD 05 06 01 00 0C",
        "off": "FD 05 06 00 00 0B",
    },
]

CLIMATE_COMMAND_INDEX = {item["key"]: item for item in CLIMATE_COMMANDS}
CLIMATE_RUNTIME = {
    "wind_level": 1,
    "last_wind_level": 1,
    "wind_before_max": 1,
    "max_requested": False,
    "flags": 0x00,
    "rear_byte": 0x00,
}

CLIMATE_ON_VALUES = {"on", "1", "true"}
CLIMATE_OFF_VALUES = {"off", "0", "false"}
CLIMATE_PULSE_VALUES = {"pulse", "click", "tap"}
CLIMATE_FRONT_FLAG_BITS = {
    "ac": 0x01,
    "dual": 0x02,
    "auto_small_fan": 0x04,
    "face_air": 0x08,
    "feet_air": 0x10,
    "rear_defrost": 0x20,
    "recirculation": 0x80,
}
CLIMATE_REAR_BYTE_BITS = {
    "windshield_air": 0x01,
    "auto_windshield_big_fan": 0x08,
    "lock_car": 0x40,
}
CLIMATE_DYNAMIC_KEYS = set(CLIMATE_FRONT_FLAG_BITS) | set(CLIMATE_REAR_BYTE_BITS) | {
    "power",
    "fan_up",
    "fan_down",
}


def climate_command_public(item):
    return {
        "key": item["key"],
        "button": item["button"],
        "car": item["car"],
        "on": item["on"],
        "off": item["off"],
        "note": item.get("note", ""),
        "diagnostic": bool(item.get("diagnostic", False)),
    }


def normalize_raise_uart_frame(value):
    raw = bytes(value) if isinstance(value, (bytes, bytearray)) else parse_hex(value)
    if len(raw) < 6:
        raise ValueError("raise UART frame is too short")
    if raw[0] != 0xFD:
        raise ValueError("raise UART frame must start with FD")
    return raw


def raise_uart_packet(raw_frame):
    return adapter_packet(CMD_RAISE_UART, normalize_raise_uart_frame(raw_frame))


def climate_fd_checksum(frame_without_checksum):
    return sum(frame_without_checksum[1:-1]) & 0xFF


def climate_state_on(value):
    return str(value or "").strip().lower() in CLIMATE_ON_VALUES


def climate_state_fd_frame(wind_level=None, flags=None, rear_byte=None, force_long=False):
    if wind_level is not None:
        wind = max(0, min(8, int(wind_level)))
        CLIMATE_RUNTIME["wind_level"] = wind
        if wind > 0:
            CLIMATE_RUNTIME["last_wind_level"] = wind
    if flags is not None:
        CLIMATE_RUNTIME["flags"] = int(flags) & 0xFF
    if rear_byte is not None:
        CLIMATE_RUNTIME["rear_byte"] = int(rear_byte) & 0xFF

    wind = CLIMATE_RUNTIME["wind_level"] & 0xFF
    active_flags = CLIMATE_RUNTIME["flags"] & 0xFF
    active_rear_byte = CLIMATE_RUNTIME["rear_byte"] & 0xFF
    if force_long or active_rear_byte:
        raw = bytearray([0xFD, 0x0B, 0x03, 0x10, 0x0D, wind, active_flags, 0xFF, 0x00, active_rear_byte, 0x01])
    else:
        raw = bytearray([0xFD, 0x0A, 0x03, 0x10, 0x0D, wind, active_flags, 0xFF, 0x00, 0x01])
    raw.append(climate_fd_checksum(raw))
    return bytes(raw)


def climate_sync_runtime_from_states(states):
    if not isinstance(states, dict):
        return False
    flags = 0
    rear_byte = 0
    for state_key, bit in CLIMATE_FRONT_FLAG_BITS.items():
        if climate_state_on(states.get(state_key)):
            flags |= bit
    for state_key, bit in CLIMATE_REAR_BYTE_BITS.items():
        if climate_state_on(states.get(state_key)):
            rear_byte |= bit
    CLIMATE_RUNTIME["flags"] = flags & 0xFF
    CLIMATE_RUNTIME["rear_byte"] = rear_byte & 0xFF
    return True


def climate_set_bit(value, bit, enabled):
    return (value | bit) if enabled else (value & ~bit)


def climate_apply_key_state_to_runtime(key, state):
    if state in CLIMATE_PULSE_VALUES:
        return
    enabled = state in CLIMATE_ON_VALUES
    if key in CLIMATE_FRONT_FLAG_BITS:
        flags = int(CLIMATE_RUNTIME.get("flags", 0)) & 0xFF
        CLIMATE_RUNTIME["flags"] = climate_set_bit(flags, CLIMATE_FRONT_FLAG_BITS[key], enabled) & 0xFF
    elif key in CLIMATE_REAR_BYTE_BITS:
        rear_byte = int(CLIMATE_RUNTIME.get("rear_byte", 0)) & 0xFF
        CLIMATE_RUNTIME["rear_byte"] = climate_set_bit(rear_byte, CLIMATE_REAR_BYTE_BITS[key], enabled) & 0xFF


def climate_dynamic_action_frames(key, state, states_applied=False):
    frames = []
    wind = int(CLIMATE_RUNTIME.get("wind_level", 1))
    flags = int(CLIMATE_RUNTIME.get("flags", 0)) & 0xFF
    rear_byte = int(CLIMATE_RUNTIME.get("rear_byte", 0)) & 0xFF

    if key == "fan_up":
        if state in CLIMATE_ON_VALUES or state in CLIMATE_PULSE_VALUES:
            wind = min(8, wind + 1)
        frames.append(("climate fan_up windLevel %d" % wind, raise_uart_packet(climate_state_fd_frame(wind_level=wind))))
        return frames

    if key == "fan_down":
        if state in CLIMATE_ON_VALUES or state in CLIMATE_PULSE_VALUES:
            wind = max(0, wind - 1)
        frames.append(("climate fan_down windLevel %d" % wind, raise_uart_packet(climate_state_fd_frame(wind_level=wind))))
        return frames

    if key == "power":
        if state in CLIMATE_ON_VALUES:
            wind = max(1, min(8, int(CLIMATE_RUNTIME.get("last_wind_level", 1))))
            frames.append(("climate power on windLevel %d" % wind, raise_uart_packet(climate_state_fd_frame(wind_level=wind))))
            return frames
        if state in CLIMATE_OFF_VALUES:
            if wind > 0:
                CLIMATE_RUNTIME["last_wind_level"] = wind
            frames.append(("climate power off windLevel 0", raise_uart_packet(climate_state_fd_frame(wind_level=0))))
            return frames
        if state in CLIMATE_PULSE_VALUES:
            on_wind = max(1, min(8, int(CLIMATE_RUNTIME.get("last_wind_level", 1))))
            frames.append(("climate power on windLevel %d" % on_wind, raise_uart_packet(climate_state_fd_frame(wind_level=on_wind))))
            frames.append(("climate power off windLevel 0", raise_uart_packet(climate_state_fd_frame(wind_level=0))))
            return frames

    if key in CLIMATE_FRONT_FLAG_BITS or key in CLIMATE_REAR_BYTE_BITS:
        if not states_applied:
            climate_apply_key_state_to_runtime(key, state)
        force_long = key in CLIMATE_REAR_BYTE_BITS or bool(rear_byte)
        if state in CLIMATE_ON_VALUES or state in CLIMATE_OFF_VALUES:
            frames.append(("climate %s %s isolated state" % (key, state), raise_uart_packet(
                climate_state_fd_frame(force_long=force_long)
            )))
            return frames
        if state in CLIMATE_PULSE_VALUES:
            if key in CLIMATE_FRONT_FLAG_BITS:
                bit = CLIMATE_FRONT_FLAG_BITS[key]
                before_flags = flags
                frames.append(("climate %s pulse on" % key, raise_uart_packet(
                    climate_state_fd_frame(flags=before_flags | bit)
                )))
                frames.append(("climate %s pulse off" % key, raise_uart_packet(
                    climate_state_fd_frame(flags=before_flags & ~bit)
                )))
            else:
                bit = CLIMATE_REAR_BYTE_BITS[key]
                before_rear = rear_byte
                frames.append(("climate %s pulse on" % key, raise_uart_packet(
                    climate_state_fd_frame(rear_byte=before_rear | bit, force_long=True)
                )))
                frames.append(("climate %s pulse off" % key, raise_uart_packet(
                    climate_state_fd_frame(rear_byte=before_rear & ~bit, force_long=True)
                )))
            return frames

    return None


def climate_auto_popup_frames(key, frames, enabled=True):
    if not enabled or key == "popup_climate":
        return frames
    popup = raise_uart_packet(CLIMATE_COMMAND_INDEX["popup_climate"]["on"])
    wrapped = []
    for label, frame in frames:
        wrapped.append(("climate popup auto before %s" % key, popup))
        wrapped.append((label, frame))
    return wrapped


def climate_frames(data):
    key = str(data.get("key") or "").strip().lower()
    item = CLIMATE_COMMAND_INDEX.get(key)
    if not item:
        raise ValueError("unknown climate command")
    state = str(data.get("state") or "on").strip().lower()
    auto_popup = data.get("autoPopup", True) is not False
    states_applied = climate_sync_runtime_from_states(data.get("states"))
    dynamic_frames = climate_dynamic_action_frames(key, state, states_applied)
    if dynamic_frames is not None:
        return climate_auto_popup_frames(key, dynamic_frames, auto_popup)
    frames = []
    if state in CLIMATE_ON_VALUES:
        frames.append(("climate %s on" % key, raise_uart_packet(item["on"])))
        return climate_auto_popup_frames(key, frames, auto_popup)
    if state in CLIMATE_OFF_VALUES:
        frames.append(("climate %s off" % key, raise_uart_packet(item["off"])))
        return climate_auto_popup_frames(key, frames, auto_popup)
    if state in CLIMATE_PULSE_VALUES:
        frames.append(("climate %s on" % key, raise_uart_packet(item["on"])))
        frames.append(("climate %s off" % key, raise_uart_packet(item["off"])))
        return climate_auto_popup_frames(key, frames, auto_popup)
    raise ValueError("state must be on, off, or pulse")


def climate_batch_frames(data):
    batch = data.get("items") or []
    if not isinstance(batch, list):
        raise ValueError("items must be a list")
    state_map = data.get("states")
    if not isinstance(state_map, dict):
        state_map = {
            str(item.get("key") or "").strip().lower(): str(item.get("state") or "off").strip().lower()
            for item in batch
            if isinstance(item, dict)
        }
    frames = []
    if data.get("openPopup"):
        frames.extend(climate_frames({"key": "popup_climate", "state": "on"}))
    for item in batch:
        if not isinstance(item, dict):
            raise ValueError("each climate batch item must be an object")
        key = str(item.get("key") or "").strip().lower()
        state = str(item.get("state") or "off").strip().lower()
        if state in {"skip", "none", ""}:
            continue
        frames.extend(climate_frames({"key": key, "state": state, "states": state_map}))
    if not frames:
        raise ValueError("no climate frames to send")
    return frames


def climate_clear_frames(data=None):
    CLIMATE_RUNTIME.update({
        "wind_level": 0,
        "last_wind_level": 1,
        "wind_before_max": 1,
        "max_requested": False,
        "flags": 0x00,
        "rear_byte": 0x00,
    })
    empty_climate = climate_state_fd_frame(wind_level=0, flags=0, rear_byte=0, force_long=True)
    return [
        ("climate clear popup auto", raise_uart_packet(CLIMATE_COMMAND_INDEX["popup_climate"]["on"])),
        ("climate clear empty AirCondition", raise_uart_packet(empty_climate)),
        ("climate clear AQS off", raise_uart_packet(CLIMATE_COMMAND_INDEX["aqs"]["off"])),
        ("climate clear left seat heat off", raise_uart_packet(CLIMATE_COMMAND_INDEX["seat_left_heat"]["off"])),
        ("climate clear right seat heat TX off", raise_uart_packet(CLIMATE_COMMAND_INDEX["seat_right_heat_l1"]["off"])),
    ]


def raw_raise_uart_frames(data):
    raw = normalize_raise_uart_frame(data.get("hex") or "")
    return [("raise UART raw through 0x70", adapter_packet(CMD_RAISE_UART, raw))]


def text_packet(command, text):
    clean = str(text or "").strip()[:28]
    if not clean:
        return checksum(bytes([0xBB, 0x41, 0xA1, 0x08, command & 0xFF, 0x00, 0x00, 0x00]))
    payload = clean.encode("utf-16le")
    frame = bytearray(6 + len(payload))
    frame[0] = 0xBB
    frame[1] = 0x41
    frame[2] = 0xA1
    frame[3] = len(frame)
    frame[4] = command & 0xFF
    frame[5 : 5 + len(payload)] = payload
    return checksum(frame)


def scenario_frames(mode, first, track, detail=""):
    first = first or "KIA"
    track = track or "MEDIA TEST"
    new_text_scenarios = {
        "aa": CMD_ANDROID_AUTO_TEXT,
        "cp": CMD_CARPLAY_TEXT,
        "my": CMD_MY_MUSIC_TEXT,
    }
    fm_detail = str(detail or "101.0").strip() or "101.0"
    am_detail = str(detail or "999").strip() or "999"
    scenarios = {
        "usb": (0x16, CMD_USB_TEXT, "", first, track),
        "bt": (0x0B, CMD_MEDIA_TEXT, "", first, track),
        "fm": (0x02, CMD_RADIO_TEXT, fm_detail, "FM %s" % fm_detail, ""),
        "am": (0x09, CMD_RADIO_TEXT, am_detail, "AM %s" % am_detail, ""),
    }
    if mode == "off":
        return [("off 0x80", media_off())]
    if mode in new_text_scenarios:
        text_id = new_text_scenarios[mode]
        return [
            ("off 0x80", media_off()),
            (f"text 0x{text_id:02X}", text_packet(text_id, first)),
        ]
    if mode == "am_old":
        return [
            ("off 0x80", media_off()),
            ("source 0x09 old", am_old_source_packet()),
            ("text 0x20 first", text_packet(CMD_RADIO_TEXT, "AM %s" % am_detail)),
        ]
    if mode not in scenarios:
        raise ValueError("unknown mode")
    source, text_id, detail, first_text, track_text = scenarios[mode]
    frames = [
        ("off 0x80", media_off()),
        (f"source 0x{source:02X}", source_packet(source, detail)),
        (f"text 0x{text_id:02X} first", text_packet(text_id, first_text)),
    ]
    if track_text and track_text != first_text:
        frames.append((f"text 0x{text_id:02X} track", text_packet(text_id, track_text)))
    return frames


def hex_bytes(data):
    return " ".join(f"{b:02X}" for b in data)


def parse_hex(text):
    clean = str(text or "").replace(",", " ").replace("0x", " ").replace("0X", " ")
    parts = [p for p in clean.split() if p]
    out = bytearray()
    for part in parts:
        if len(part) > 2 and all(ch in "0123456789abcdefABCDEF" for ch in part) and len(part) % 2 == 0:
            for i in range(0, len(part), 2):
                out.append(int(part[i : i + 2], 16))
        else:
            out.append(int(part, 16) & 0xFF)
    return bytes(out)


def parse_id(value):
    text = str(value or "").strip()
    if not text:
        raise ValueError("missing id")
    return int(text, 0) & 0xFF


def custom_frames(source, text_id, detail, first, track,
                  send_off=True, send_source=True, send_text=True):
    source = parse_id(source)
    text_id = parse_id(text_id)
    frames = []
    if send_off:
        frames.append(("off 0x80", media_off()))
    if send_source:
        frames.append((f"source 0x{source:02X}", source_packet(source, detail or "")))
    if not send_text:
        return frames
    first = first or "KIA"
    track = track or ""
    frames.append((f"text 0x{text_id:02X} first", text_packet(text_id, first)))
    if track and track != first:
        frames.append((f"text 0x{text_id:02X} track", text_packet(text_id, track)))
    return frames


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
            elif self.path.startswith("/api/adapter/status"):
                self._send(200, adapter_status())
            elif self.path.startswith("/api/ports"):
                self._send(200, {"ok": True, "ports": list_ports()})
            elif self.path.startswith("/api/status"):
                self._send(200, bridge.snapshot())
            elif self.path.startswith("/api/firmware/list"):
                self._send(200, {"ok": True, "files": known_firmware_files()})
            elif self.path.startswith("/api/firmware/status"):
                self._send(200, firmware_snapshot())
            elif self.path.startswith("/api/climate/list"):
                self._send(200, {
                    "ok": True,
                    "commands": [climate_command_public(item) for item in CLIMATE_COMMANDS],
                })
            elif self.path.startswith("/api/navi/lane-notes"):
                path = lane_note_path()
                notes = []
                if os.path.exists(path):
                    with open(path, "r", encoding="utf-8") as fh:
                        notes = [json.loads(line) for line in fh if line.strip()]
                self._send(200, {"ok": True, "path": path, "notes": notes[-50:]})
            elif self.path.startswith("/api/can/devices"):
                self._send(200, {"ok": True, "devices": gs_usb_devices()})
            elif self.path.startswith("/api/can/status"):
                self._send(200, CAN_LOGGER.snapshot())
            elif self.path.startswith("/api/rx"):
                require_not_flashing()
                data = bridge.read_recent()
                self._send(200, {"ok": True, "bytes": len(data), "hex": hex_bytes(data)})
            else:
                self._send(404, {"ok": False, "error": "not found"})
        except Exception as e:
            self._send(500, {"ok": False, "error": str(e)})

    def do_POST(self):
        try:
            body = self.rfile.read(int(self.headers.get("Content-Length", "0") or "0"))
            data = json.loads(body.decode("utf-8") or "{}")
            if self.path.startswith("/api/open"):
                require_not_flashing()
                port = data.get("port")
                baud = int(data.get("baud") or 115200)
                bridge.open(port, baud)
                self._send(200, {"ok": True, "port": port, "baud": baud})
            elif self.path.startswith("/api/close"):
                require_not_flashing()
                bridge.close()
                self._send(200, {"ok": True, "message": "closed"})
            elif self.path.startswith("/api/tx"):
                require_not_flashing()
                raw = parse_hex(data.get("hex", ""))
                bridge.write(raw)
                self._send(200, {"ok": True, "bytes": len(raw), "hex": hex_bytes(raw)})
            elif self.path.startswith("/api/test"):
                require_not_flashing()
                frames = scenario_frames(data.get("mode"), data.get("first"), data.get("track"), data.get("detail"))
                sent = []
                for idx, (label, frame) in enumerate(frames):
                    if idx == 3:
                        time.sleep(2.5)
                    bridge.write(frame)
                    sent.append({"label": label, "hex": hex_bytes(frame), "bytes": len(frame)})
                    time.sleep(0.04)
                self._send(200, {"ok": True, "frames": sent})
            elif self.path.startswith("/api/custom"):
                require_not_flashing()
                delay_ms = int(data.get("delayMs") or 80)
                delay_s = max(0, min(delay_ms, 5000)) / 1000.0
                frames = custom_frames(
                    data.get("source"),
                    data.get("text"),
                    data.get("detail"),
                    data.get("first"),
                    data.get("track"),
                    data.get("sendOff", True) is not False,
                    data.get("sendSource", True) is not False,
                    data.get("sendText", True) is not False,
                )
                sent = []
                for idx, (label, frame) in enumerate(frames):
                    if idx > 0:
                        time.sleep(delay_s)
                    if "track" in label:
                        time.sleep(2.5)
                    bridge.write(frame)
                    sent.append({"label": label, "hex": hex_bytes(frame), "bytes": len(frame)})
                    time.sleep(0.04)
                self._send(200, {"ok": True, "frames": sent})
            elif self.path.startswith("/api/navi/lane-note"):
                path, note = save_lane_note(data)
                self._send(200, {"ok": True, "path": path, "note": note})
            elif self.path.startswith("/api/navi"):
                require_not_flashing()
                frames = navi_frames(data)
                sent = []
                action = str(data.get("action") or "").lower()
                delay_s = 1.15 if action in ("sweep", "lane_sweep") else 0.06
                for label, frame in frames:
                    bridge.write(frame)
                    sent.append({"label": label, "hex": hex_bytes(frame), "bytes": len(frame)})
                    time.sleep(delay_s if label.startswith("sweep ") or label.startswith("lane gray ") else 0.06)
                self._send(200, {"ok": True, "frames": sent})
            elif self.path.startswith("/api/climate/raw"):
                require_not_flashing()
                frames = raw_raise_uart_frames(data)
                sent = []
                for label, frame in frames:
                    bridge.write(frame)
                    sent.append({"label": label, "hex": hex_bytes(frame), "bytes": len(frame)})
                    time.sleep(0.04)
                self._send(200, {"ok": True, "frames": sent})
            elif self.path.startswith("/api/climate/batch"):
                require_not_flashing()
                frames = climate_batch_frames(data)
                sent = []
                delay_ms = int(data.get("delayMs") or 180)
                delay_s = max(0.04, min(delay_ms, 5000) / 1000.0)
                for idx, (label, frame) in enumerate(frames):
                    if idx > 0:
                        time.sleep(delay_s)
                    bridge.write(frame)
                    sent.append({"label": label, "hex": hex_bytes(frame), "bytes": len(frame)})
                    time.sleep(0.04)
                self._send(200, {"ok": True, "frames": sent})
            elif self.path.startswith("/api/climate/clear"):
                require_not_flashing()
                frames = climate_clear_frames(data)
                sent = []
                delay_ms = int(data.get("delayMs") or 180)
                delay_s = max(0.04, min(delay_ms, 5000) / 1000.0)
                for idx, (label, frame) in enumerate(frames):
                    if idx > 0:
                        time.sleep(delay_s)
                    bridge.write(frame)
                    sent.append({"label": label, "hex": hex_bytes(frame), "bytes": len(frame)})
                    time.sleep(0.04)
                self._send(200, {"ok": True, "frames": sent})
            elif self.path.startswith("/api/climate"):
                require_not_flashing()
                frames = climate_frames(data)
                sent = []
                delay_ms = int(data.get("delayMs") or 300)
                delay_s = max(0.04, min(delay_ms, 5000) / 1000.0)
                for idx, (label, frame) in enumerate(frames):
                    if idx > 0:
                        time.sleep(delay_s)
                    bridge.write(frame)
                    sent.append({"label": label, "hex": hex_bytes(frame), "bytes": len(frame)})
                    time.sleep(0.04)
                self._send(200, {"ok": True, "frames": sent})
            elif self.path.startswith("/api/firmware/flash"):
                name, firmware = load_firmware_from_request(data)
                digest = start_firmware_flash(name, firmware)
                self._send(200, {"ok": True, "name": name, "bytes": len(firmware), "sha256": digest})
            elif self.path.startswith("/api/can/start"):
                snap = CAN_LOGGER.start(data.get("mode"))
                self._send(200, {"ok": True, "mode": snap.get("mode"), "files": snap.get("files", {})})
            elif self.path.startswith("/api/can/stop"):
                snap = CAN_LOGGER.stop(wait=True)
                self._send(200, {"ok": True, "message": snap.get("status", "stopped"), "files": snap.get("files", {})})
            else:
                self._send(404, {"ok": False, "error": "not found"})
        except Exception as e:
            self._send(500, {"ok": False, "error": str(e)})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8791)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"media serial tester listening on http://{args.host}:{args.port}/", flush=True)
    try:
        server.serve_forever()
    finally:
        bridge.close()


if __name__ == "__main__":
    main()
