#!/usr/bin/env python3
import argparse
import base64
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from media_serial_tester import (  # noqa: E402
    bridge,
    checksum,
    hex_bytes,
    list_ports,
    nav_on_packet,
    nav_text_packet,
    navi_frames,
    speed_limit_packet,
)


LOG_ROOT = "/Users/legion/Downloads/canbus/logs"
NOTES_PATH = os.path.join(LOG_ROOT, "navi_lane_lab_20260601.jsonl")
PHOTO_DIR = os.path.join(LOG_ROOT, "navi_lane_photos")
TRC_ANALYSIS_JSON = os.path.join(LOG_ROOT, "nav_trc_analysis_20260601.json")
TRC_ANALYSIS_MD = os.path.join(LOG_ROOT, "nav_trc_analysis_20260601.md")
OFFICIAL_NAV_ROOT = "/Users/legion/Downloads/canbus/official_nav_20260601"
OFFICIAL_IMAGE_ROOT = os.path.join(OFFICIAL_NAV_ROOT, "analysis", "oem_std5_kia_images")
OFFICIAL_IMAGE_PNG_DIR = os.path.join(OFFICIAL_IMAGE_ROOT, "png")
OFFICIAL_IMAGE_MANIFEST = os.path.join(OFFICIAL_IMAGE_ROOT, "manifest.jsonl")
GUIDED_STATE = {
    "session_id": "",
    "mode": "sendable",
    "queue": [],
    "position": 0,
    "started_at": "",
    "last_sent_at": "",
    "last_result_at": "",
    "awaiting": False,
    "inputs": {},
    "last_frames": [],
}

OFFICIAL_NAV_SUMMARY = {
    "root": OFFICIAL_NAV_ROOT,
    "readme": os.path.join(OFFICIAL_NAV_ROOT, "README.md"),
    "version": {
        "source": "Sportage_2018_SD_Card_RUS",
        "manifest": "QL_PE.RUS.SOP.016.1.240306.STD_M",
        "kia": "STD5L.XXX.KIA.250919.1285a3f",
        "hyundai": "STD5L.XXX.HMC.250919.1285a3f",
    },
    "images": {
        "status": "not_extracted",
        "text": "Обычных PNG/JPG/SVG с манёврами не найдено. UI-ресурсы есть внутри oem_std5_kia.npkg/libExSLNavi.enc, но они упакованы/зашифрованы, поэтому пока у нас есть имена полей и конфиги, а не готовые картинки.",
    },
    "angle_buckets": [
        {"name": "ANGLE1", "value": 15},
        {"name": "ANGLE2", "value": 45},
        {"name": "ANGLE3", "value": 120},
        {"name": "ANGLE4", "value": 165},
        {"name": "ANGLE5", "value": 195},
        {"name": "ANGLE6", "value": 240},
        {"name": "ANGLE7", "value": 315},
        {"name": "ANGLE8", "value": 345},
    ],
    "guidance_objects": [
        {"name": "Основной манёвр / поворот", "main_type": 1, "sub_type": 1, "source": "road_guidance_config"},
        {"name": "Промежуточная точка", "main_type": 1, "sub_type": 2, "source": "road_guidance_config"},
        {"name": "Финиш / destination", "main_type": 1, "sub_type": 3, "source": "road_guidance_config"},
        {"name": "Полосы / lane guidance", "main_type": 1, "sub_type": 4, "source": "road_guidance_config"},
        {"name": "Развязка / junction view", "main_type": 2, "sub_type": 1, "source": "road_guidance_config"},
        {"name": "TBT список", "main_type": 3, "sub_type": 1, "source": "road_guidance_config"},
        {"name": "Advanced TBT", "main_type": 3, "sub_type": 2, "source": "road_guidance_config"},
        {"name": "Expressway list", "main_type": 3, "sub_type": 3, "source": "road_guidance_config"},
        {"name": "Service area list", "main_type": 3, "sub_type": 4, "source": "road_guidance_config"},
        {"name": "Камера / safety guidance", "main_type": 4, "sub_type": 1, "source": "road_guidance_config"},
        {"name": "Traffic event information", "main_type": 6, "sub_type": 2, "source": "road_guidance_config"},
        {"name": "Administration information", "main_type": 6, "sub_type": 6, "source": "road_guidance_config"},
        {"name": "Time restricted information", "main_type": 6, "sub_type": 8, "source": "road_guidance_config"},
        {"name": "International information", "main_type": 6, "sub_type": 12, "source": "road_guidance_config"},
    ],
    "counts": [
        {"name": "official guidance subtypes", "value": 15, "note": "turn, waypoint, finish, lane, junction, ILS, TBT, camera, traffic/admin/time/international info"},
        {"name": "turn angle buckets", "value": 8, "note": "ANGLE1..ANGLE8 from TurnCodeAngle.bin"},
        {"name": "route turn classes", "value": 8, "note": "S/L/R/U/X/E_U/N/N_L turn classes from RoutePlanningConfig"},
        {"name": "TEYES yellow candidates in lab", "value": 8, "note": "5 classic + 3 TBT candidate frames currently exposed"},
        {"name": "TEYES gray-road sweep", "value": 18, "note": "00..11 candidates from prior cluster observation, not proven as official KIA count"},
        {"name": "camera UI fields", "value": 36, "note": "rgCameraGuidance* and rgSubCameraGuidance* fields"},
        {"name": "safety UI fields", "value": 6, "note": "rgSafetyGuidance* fields"},
        {"name": "turn UI fields", "value": 17, "note": "first/second/exit/rotary/toll turn fields"},
    ],
    "turn_classes": [
        "S_TURN",
        "L_TURN",
        "R_TURN",
        "U_TURN",
        "X_TURN",
        "E_U_TURN",
        "N_TURN",
        "N_L_TURN",
    ],
    "warning_fields": [
        "rgCameraGuidanceVisible",
        "rgCameraGuidanceSpeedLimitType",
        "rgCameraGuidanceBusLaneVisible",
        "rgCameraGuidanceDistance",
        "rgCameraGuidanceInnerSpeedText",
        "rgSubCameraGuidanceVisible",
        "rgSafetyGuidanceVisible",
        "rgSafetyGuidanceImage",
        "rgSafetyGuidanceUnderImage",
        "flyerCameraReportVisible",
        "flyerNoticeCameraVisible",
        "trafficImage",
        "trafficDistance",
    ],
    "ui_fields": [
        {"field": "turnImage", "meaning": "картинка текущего поворота"},
        {"field": "turnName", "meaning": "улица/название манёвра"},
        {"field": "turnDistance", "meaning": "дистанция до манёвра"},
        {"field": "rgFirstTurnImage", "meaning": "первый/основной манёвр"},
        {"field": "rgFirstTurnDistance", "meaning": "дистанция до первого манёвра"},
        {"field": "rgSecondTurnImage", "meaning": "второй/микро-манёвр"},
        {"field": "rgSecondTurnDistance", "meaning": "дистанция до второго манёвра"},
        {"field": "rgFirstTurnRotaryImage", "meaning": "круг/rotary для первого манёвра"},
        {"field": "rgFirstTurnExitNumber", "meaning": "номер съезда"},
        {"field": "exitImage", "meaning": "картинка съезда/exit"},
        {"field": "exitIconImage", "meaning": "иконка съезда"},
        {"field": "rotaryExitNumber", "meaning": "номер съезда на круге"},
        {"field": "rgLaneGuidanceVisible", "meaning": "есть подсказка по полосам"},
        {"field": "mapRouteLaneTextNumber", "meaning": "номер/текст полосы"},
        {"field": "listModelTBT", "meaning": "список ближайших TBT событий"},
        {"field": "roadSpeedVisible", "meaning": "показывать ограничение скорости"},
        {"field": "roadSpeedIcon", "meaning": "иконка ограничения"},
        {"field": "roadSpeedDigitTxt", "meaning": "цифры ограничения скорости"},
    ],
    "teyes_mapping": [
        {"official": "rgFirstTurnImage / turnImage", "teyes": "CMD_MANEUVER 0x45 основной кадр", "status": "частично знаем"},
        {"official": "rgSecondTurnImage", "teyes": "нужно найти второй/микро кадр", "status": "следующий тест"},
        {"official": "rgLaneGuidanceVisible", "teyes": "b8 gray 00..11 / lane overlay", "status": "тестируем в машине"},
        {"official": "exitImage / rgFirstTurnExitNumber", "teyes": "съезд/круг/exit варианты", "status": "нужно маппить"},
        {"official": "roadSpeedDigitTxt", "teyes": "CMD_SPEED_LIMIT 0x44", "status": "есть отдельная команда"},
    ],
}

CHECK_TABLE_ROWS = [
    {
        "key": "route_start",
        "group": "00 подготовка маршрута",
        "check": "Запуск навигации",
        "official": "route guidance active / rgRouteGuidance",
        "teyes": "0x48 nav_on + 0x4A text + 0x44 speed limit",
        "expected": "приборка входит в навигационный режим, появляется маршрутный блок",
        "status": "sendable",
        "payload": {"action": "route_start", "text": "$currentStreet", "speed": "$speed"},
    },
    {
        "key": "current_street",
        "group": "00 подготовка маршрута",
        "check": "Текущая улица",
        "official": "turnName / route text field",
        "teyes": "0x4A nav text",
        "expected": "на приборке обновляется текст текущей улицы/подпись маршрута",
        "status": "sendable",
        "payload": {"action": "text", "text": "$currentStreet"},
    },
    {
        "key": "next_street",
        "group": "00 подготовка маршрута",
        "check": "Улица после манёвра",
        "official": "next road name near rgFirstTurnImage",
        "teyes": "0x4A nav text, пока как отдельная гипотеза",
        "expected": "если приборка умеет отдельный текст, видим следующую улицу",
        "status": "candidate",
        "payload": {"action": "text", "text": "$nextStreet"},
    },
    {
        "key": "finish_name",
        "group": "00 подготовка маршрута",
        "check": "Финиш / destination",
        "official": "main=1 sub=3 destination",
        "teyes": "отдельная команда не найдена",
        "expected": "нужно поймать из Yandex/мода или найти отдельный пакет",
        "status": "need_mapping",
        "payload": None,
    },
    {
        "key": "eta_remaining",
        "group": "00 подготовка маршрута",
        "check": "До финиша: время и дистанция",
        "official": "route summary / destination guidance",
        "teyes": "отдельная команда не найдена",
        "expected": "в KIA это отдельные данные маршрута, в TEYES пока не замаплено",
        "status": "need_yandex",
        "payload": None,
    },
    {
        "key": "speed_limit",
        "group": "03 знаки / предупреждения",
        "check": "Ограничение скорости",
        "official": "roadSpeedVisible / roadSpeedDigitTxt",
        "teyes": "0x44 speed limit",
        "expected": "показывается знак ограничения скорости",
        "status": "sendable",
        "payload": {"action": "speed_quick", "speed": "$speed"},
    },
    {
        "key": "current_speed",
        "group": "00 подготовка маршрута",
        "check": "Текущая скорость",
        "official": "vehicle speed / GPS speed, не map speed limit",
        "teyes": "не UART-пакет навигации; берём в нашем приложении из GPS/системы",
        "expected": "в нашем KIA-приложении скорость должна жить даже когда навигатор свернут",
        "status": "app_side",
        "payload": None,
    },
    {
        "key": "main_forward",
        "group": "02 жёлтые стрелки",
        "check": "Основной манёвр: прямо",
        "official": "rgFirstTurnImage + rgFirstTurnDistance",
        "teyes": "0x45 b5=0D b6=00 b7=01 b8=00",
        "expected": "желтая стрелка прямо, дистанция до манёвра",
        "status": "sendable",
        "payload": {"action": "lane_combo", "b5": "0D", "b6": "00", "b7": "01", "b8": "00"},
    },
    {
        "key": "main_right",
        "group": "02 жёлтые стрелки",
        "check": "Основной манёвр: направо",
        "official": "rgFirstTurnImage + rgFirstTurnDistance",
        "teyes": "0x45 b5=0D b6=00 b7=00 b8=0C",
        "expected": "желтая стрелка направо, дистанция до манёвра",
        "status": "sendable",
        "payload": {"action": "lane_combo", "b5": "0D", "b6": "00", "b7": "00", "b8": "0C"},
    },
    {
        "key": "main_left",
        "group": "02 жёлтые стрелки",
        "check": "Основной манёвр: налево",
        "official": "rgFirstTurnImage + rgFirstTurnDistance",
        "teyes": "0x45 b5=0D b6=00 b7=00 b8=24",
        "expected": "желтая стрелка налево, дистанция до манёвра",
        "status": "sendable",
        "payload": {"action": "lane_combo", "b5": "0D", "b6": "00", "b7": "00", "b8": "24"},
    },
    {
        "key": "main_progress_start",
        "group": "02 жёлтые стрелки",
        "check": "Прогресс манёвра: начало",
        "official": "rgFirstTurnDistance + progress-like cluster fill",
        "teyes": "0x45 progress=0",
        "expected": "прогресс-бар пустой, 0.1 деление не занято",
        "status": "sendable",
        "payload": {"action": "lane_combo", "b5": "0D", "b6": "00", "b7": "00", "b8": "0C", "progress": "0"},
    },
    {
        "key": "main_progress_mid",
        "group": "02 жёлтые стрелки",
        "check": "Прогресс манёвра: середина",
        "official": "rgFirstTurnDistance + progress-like cluster fill",
        "teyes": "0x45 progress=5",
        "expected": "прогресс-бар заполнен примерно наполовину",
        "status": "sendable",
        "payload": {"action": "lane_combo", "b5": "0D", "b6": "00", "b7": "00", "b8": "0C", "progress": "5"},
    },
    {
        "key": "micro_second",
        "group": "02 жёлтые стрелки",
        "check": "Второй / микро-манёвр",
        "official": "rgSecondTurnImage + rgSecondTurnDistance",
        "teyes": "отдельный второй кадр не найден",
        "expected": "нужно найти, чтобы не путать основной поворот и микро-подсказку",
        "status": "need_mapping",
        "payload": None,
    },
    {
        "key": "gray_lane_18",
        "group": "01 серые подложки b8",
        "check": "Серые режимы дороги 00..11",
        "official": "rgLaneGuidanceVisible / mapRouteLaneTextNumber",
        "teyes": "0x45 sweep b8=00..11",
        "expected": "по очереди показываются серые схемы дорог/полос; подписываем что реально видим",
        "status": "sweep",
        "payload": {"action": "lane_sweep", "kind": "gray18", "b5": "0D", "b6": "00", "b7": "00"},
    },
    {
        "key": "gray_known",
        "group": "01 серые подложки b8",
        "check": "Известные кандидаты b8",
        "official": "lane guidance candidates",
        "teyes": "0x45 sweep known b8 list",
        "expected": "быстрый прогон только тех b8, где раньше была реакция",
        "status": "sweep",
        "payload": {"action": "lane_sweep", "kind": "known", "b5": "0D", "b6": "00", "b7": "00"},
    },
    {
        "key": "exit_right",
        "group": "02 жёлтые стрелки",
        "check": "Съезд направо",
        "official": "exitImage / exitIconImage / rgFirstTurnExitNumber",
        "teyes": "0x45 b5=1F b8=0C",
        "expected": "желтая стрелка/съезд направо, если профиль это поддерживает",
        "status": "candidate",
        "payload": {"action": "lane_combo", "b5": "1F", "b6": "00", "b7": "00", "b8": "0C"},
    },
    {
        "key": "exit_left",
        "group": "02 жёлтые стрелки",
        "check": "Съезд налево",
        "official": "exitImage / exitIconImage / rgFirstTurnExitNumber",
        "teyes": "0x45 b5=1F b8=24",
        "expected": "желтая стрелка/съезд налево, если профиль это поддерживает",
        "status": "candidate",
        "payload": {"action": "lane_combo", "b5": "1F", "b6": "00", "b7": "00", "b8": "24"},
    },
    {
        "key": "rotary",
        "group": "02 жёлтые стрелки",
        "check": "Круг / rotary",
        "official": "rgFirstTurnRotaryImage / rotaryExitNumber",
        "teyes": "точный байт круга не подтвержден",
        "expected": "нужен отдельный подбор, пока не считаем рабочим",
        "status": "need_mapping",
        "payload": None,
    },
    {
        "key": "junction_ils",
        "group": "02 жёлтые стрелки",
        "check": "Junction / ILS картинка развязки",
        "official": "main=2 sub=1 junction view / illustration",
        "teyes": "отдельная команда не найдена",
        "expected": "в официальной KIA есть как отдельный guidance object, в TEYES пока не замаплено",
        "status": "need_mapping",
        "payload": None,
    },
    {
        "key": "tbt_forward",
        "group": "02 жёлтые стрелки",
        "check": "TBT прямо",
        "official": "listModelTBT / Advanced TBT",
        "teyes": "0x45 b5=41",
        "expected": "альтернативный TBT-режим прямо",
        "status": "candidate",
        "payload": {"action": "lane_combo", "b5": "41", "b6": "00", "b7": "00", "b8": "00"},
    },
    {
        "key": "tbt_right",
        "group": "02 жёлтые стрелки",
        "check": "TBT направо",
        "official": "listModelTBT / Advanced TBT",
        "teyes": "0x45 b5=43",
        "expected": "альтернативный TBT-режим направо",
        "status": "candidate",
        "payload": {"action": "lane_combo", "b5": "43", "b6": "00", "b7": "00", "b8": "00"},
    },
    {
        "key": "tbt_left",
        "group": "02 жёлтые стрелки",
        "check": "TBT налево",
        "official": "listModelTBT / Advanced TBT",
        "teyes": "0x45 b5=46",
        "expected": "альтернативный TBT-режим налево",
        "status": "candidate",
        "payload": {"action": "lane_combo", "b5": "46", "b6": "00", "b7": "00", "b8": "00"},
    },
    {
        "key": "camera",
        "group": "03 знаки / предупреждения",
        "check": "Камера / ограничение камеры",
        "official": "rgCameraGuidanceVisible / rgCameraGuidanceDistance",
        "teyes": "отдельная команда не найдена",
        "expected": "нужен пакет события камеры, не смешивать с обычным speed limit",
        "status": "need_mapping",
        "payload": None,
    },
    {
        "key": "safety",
        "group": "03 знаки / предупреждения",
        "check": "Препятствие / safety warning",
        "official": "rgSafetyGuidanceVisible / rgSafetyGuidanceImage",
        "teyes": "отдельная команда не найдена",
        "expected": "нужен пакет пассивного события без маршрута",
        "status": "need_mapping",
        "payload": None,
    },
    {
        "key": "traffic_admin_time",
        "group": "03 знаки / предупреждения",
        "check": "Traffic/admin/time/international",
        "official": "main=6 sub=2/6/8/12",
        "teyes": "отдельные команды не найдены",
        "expected": "официальная прошивка знает эти типы, но TEYES UART пока не расшифрован",
        "status": "future",
        "payload": None,
    },
]

OFFICIAL_IMAGE_ASSIGNMENTS = {
    "route_start": [
        {"file": "img_0200_8260578b_61x61.png", "label": "официальный route/current symbol"},
    ],
    "current_street": [
        {"file": "img_0020_887e7121_61x61.png", "label": "дорога + pin"},
    ],
    "next_street": [
        {"file": "img_0021_889089a2_61x61.png", "label": "следующая дорога + pin"},
    ],
    "finish_name": [
        {"file": "img_0341_e6ccd7ec_61x61.png", "label": "финишный флаг"},
    ],
    "eta_remaining": [
        {"file": "img_0341_e6ccd7ec_61x61.png", "label": "route finish"},
    ],
    "speed_limit": [
        {"file": "img_6295_2a6b6254_52x52.png", "label": "roadSpeedIcon container"},
    ],
    "current_speed": [
        {"file": "img_0212_f4954e33_113x98.png", "label": "vehicle/current symbol"},
    ],
    "main_forward": [
        {"file": "img_0012_864d7982_61x61.png", "label": "turn forward"},
    ],
    "main_right": [
        {"file": "img_0014_8671aa84_61x61.png", "label": "turn right"},
    ],
    "main_left": [
        {"file": "img_0018_86ba0c88_61x61.png", "label": "turn left"},
    ],
    "main_progress_start": [
        {"file": "img_0014_8671aa84_61x61.png", "label": "right turn, progress=0"},
    ],
    "main_progress_mid": [
        {"file": "img_0014_8671aa84_61x61.png", "label": "right turn, progress=5"},
    ],
    "micro_second": [
        {"file": "img_0013_865f9203_61x61.png", "label": "micro/diagonal candidate"},
    ],
    "gray_lane_18": [
        {"file": "img_7229_f1d7c51f_54x54.png", "label": "lane guidance sign candidate"},
    ],
    "gray_known": [
        {"file": "img_7261_a9d64bc6_84x84.png", "label": "lane guidance sign candidate"},
    ],
    "exit_right": [
        {"file": "img_0030_88fd1ca8_61x61.png", "label": "official EXIT sign"},
        {"file": "img_0013_865f9203_61x61.png", "label": "diagonal right candidate"},
    ],
    "exit_left": [
        {"file": "img_0030_88fd1ca8_61x61.png", "label": "official EXIT sign"},
        {"file": "img_0017_86a7f407_61x61.png", "label": "diagonal left candidate"},
    ],
    "rotary": [
        {"file": "img_0039_89214daa_61x61.png", "label": "roundabout/rotary"},
    ],
    "junction_ils": [
        {"file": "img_7229_f1d7c51f_54x54.png", "label": "road/lane junction candidate"},
    ],
    "tbt_forward": [
        {"file": "img_0012_864d7982_61x61.png", "label": "TBT forward candidate"},
    ],
    "tbt_right": [
        {"file": "img_0014_8671aa84_61x61.png", "label": "TBT right candidate"},
    ],
    "tbt_left": [
        {"file": "img_0018_86ba0c88_61x61.png", "label": "TBT left candidate"},
    ],
    "camera": [
        {"file": "img_6295_2a6b6254_52x52.png", "label": "camera speed-limit shell"},
    ],
    "safety": [
        {"file": "img_7404_600b2939_84x84.png", "label": "accident/safety warning"},
    ],
    "traffic_admin_time": [
        {"file": "img_7443_37ebb78a_60x60.png", "label": "road closed / traffic event"},
    ],
}


HTML = r"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>KIA Navi lane lab</title>
  <style>
    :root {
      color-scheme: dark;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: #0f1216;
      color: #edf3f8;
    }
    * { box-sizing: border-box; }
    body { margin: 0; background: #0f1216; }
    main { max-width: 1280px; margin: 0 auto; padding: 18px; }
    h1 { margin: 0; font-size: 24px; letter-spacing: 0; }
    h2 { margin: 0 0 12px; font-size: 15px; color: #dce8f3; letter-spacing: 0; }
    p { margin: 6px 0 0; color: #9facba; }
    section { border: 1px solid #28323d; border-radius: 8px; background: #161b21; padding: 14px; }
    button, select, input, textarea {
      border: 1px solid #3b4855;
      border-radius: 7px;
      background: #202831;
      color: #edf3f8;
      font: inherit;
    }
    button { padding: 10px 12px; cursor: pointer; min-height: 40px; }
    button.primary { background: #1e67d8; border-color: #3b82f6; }
    button.good { background: #0f6848; border-color: #22c55e; }
    button.warn { background: #673714; border-color: #d97706; }
    button.danger { background: #6b1d1d; border-color: #ef4444; }
    button.active { outline: 2px solid #38bdf8; border-color: #7dd3fc; }
    button:disabled { opacity: .45; cursor: not-allowed; }
    select, input, textarea { width: 100%; padding: 10px; }
    input[type="range"] { padding: 0; accent-color: #38bdf8; }
    textarea { min-height: 78px; resize: vertical; }
    .topbar {
      display: grid;
      grid-template-columns: minmax(280px, 1fr) minmax(360px, 1.2fr);
      gap: 12px;
      align-items: stretch;
      margin: 14px 0;
    }
    .grid {
      display: grid;
      grid-template-columns: minmax(360px, .9fr) minmax(420px, 1.1fr) minmax(330px, .85fr);
      gap: 12px;
      align-items: start;
    }
    .row { display: grid; grid-template-columns: 1fr auto auto; gap: 8px; align-items: end; }
    .two { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .three { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
    .buttons { display: grid; grid-template-columns: repeat(auto-fit, minmax(118px, 1fr)); gap: 8px; }
    .matrix { display: grid; grid-template-columns: repeat(6, 1fr); gap: 7px; }
    .matrix button { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; padding: 8px 6px; }
    .label { display: block; margin-bottom: 5px; color: #aebdca; font-size: 12px; }
    .muted { color: #8d9aa7; font-size: 12px; }
    .status {
      min-height: 92px;
      white-space: pre-wrap;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 12px;
      line-height: 1.45;
      color: #d7e2ec;
    }
    .current {
      display: grid;
      gap: 8px;
      border: 1px solid #344252;
      border-radius: 8px;
      padding: 12px;
      background: #10151b;
      margin-bottom: 12px;
    }
    .current-title { font-size: 18px; font-weight: 700; }
    .bytes {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 14px;
      color: #c9e8ff;
      overflow-wrap: anywhere;
    }
    .timeline { display: grid; grid-template-columns: auto 1fr auto; gap: 8px; align-items: center; }
    .bar { height: 10px; border-radius: 6px; background: #0c1015; overflow: hidden; border: 1px solid #27323d; }
    .bar > div { height: 100%; background: #38bdf8; width: 0%; }
    .log {
      min-height: 270px;
      max-height: 430px;
      overflow: auto;
      border: 1px solid #27323d;
      border-radius: 8px;
      background: #0b0f14;
      padding: 10px;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 12px;
      white-space: pre-wrap;
    }
    .note-item {
      border-bottom: 1px solid #27323d;
      padding: 8px 0;
      font-size: 12px;
      color: #c7d4df;
    }
    .note-item:last-child { border-bottom: 0; }
    .wide { margin-top: 12px; }
    .official-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 10px;
    }
    .official-card {
      border: 1px solid #2b3743;
      border-radius: 8px;
      background: #10151b;
      padding: 12px;
      min-height: 118px;
    }
    .official-card h3 {
      margin: 0 0 8px;
      font-size: 14px;
      color: #f2f7fb;
      letter-spacing: 0;
    }
    .official-card ul { margin: 0; padding-left: 18px; color: #c9d5df; }
    .official-card li { margin: 4px 0; }
    .tag {
      display: inline-block;
      margin: 0 5px 6px 0;
      padding: 5px 7px;
      border: 1px solid #344252;
      border-radius: 7px;
      background: #17202a;
      color: #dce8f3;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 12px;
    }
    .mapping-row {
      display: grid;
      grid-template-columns: minmax(160px, .9fr) minmax(180px, 1fr) minmax(110px, auto);
      gap: 8px;
      align-items: start;
      border-bottom: 1px solid #27323d;
      padding: 7px 0;
      font-size: 12px;
    }
    .mapping-row:last-child { border-bottom: 0; }
    .check-toolbar {
      display: grid;
      grid-template-columns: 1.2fr 1.2fr .55fr .55fr .55fr;
      gap: 8px;
      margin-bottom: 12px;
      align-items: end;
    }
    .check-table-wrap {
      overflow: auto;
      border: 1px solid #27323d;
      border-radius: 8px;
      background: #0b0f14;
    }
    .logic-steps {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
      margin-bottom: 12px;
    }
    .logic-step {
      border: 1px solid #2c3947;
      border-radius: 8px;
      background: #111820;
      padding: 12px;
    }
    .logic-step strong {
      display: block;
      margin-bottom: 5px;
      color: #f2f7fb;
    }
    .check-table {
      width: 100%;
      min-width: 1440px;
      border-collapse: collapse;
      font-size: 12px;
    }
    .check-table th,
    .check-table td {
      border-bottom: 1px solid #27323d;
      padding: 8px;
      vertical-align: top;
      text-align: left;
    }
    .check-table th {
      position: sticky;
      top: 0;
      background: #121820;
      color: #cbd8e3;
      z-index: 1;
    }
    .check-table tr:last-child td { border-bottom: 0; }
    .check-group { color: #93c5fd; font-weight: 700; white-space: nowrap; }
    .check-title { font-weight: 700; color: #f2f7fb; }
    .status-pill {
      display: inline-block;
      padding: 4px 7px;
      border-radius: 999px;
      border: 1px solid #344252;
      background: #17202a;
      color: #dce8f3;
      white-space: nowrap;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 11px;
    }
    .status-pill.sendable,
    .status-pill.sweep { border-color: #22c55e; color: #bbf7d0; }
    .status-pill.candidate { border-color: #f59e0b; color: #fde68a; }
    .status-pill.need_mapping,
    .status-pill.need_yandex { border-color: #fb7185; color: #fecdd3; }
    .status-pill.app_side,
    .status-pill.future { border-color: #60a5fa; color: #bfdbfe; }
    .row-note { min-width: 180px; padding: 7px; min-height: 34px; }
    button.mini { min-height: 32px; padding: 6px 8px; font-size: 12px; }
    .image-stack {
      display: flex;
      gap: 6px;
      align-items: center;
      flex-wrap: wrap;
      min-width: 88px;
    }
    .official-thumb {
      width: 48px;
      height: 48px;
      object-fit: contain;
      border: 1px solid #344252;
      border-radius: 6px;
      background: #10151b;
      padding: 3px;
      image-rendering: auto;
    }
    .official-thumb.large {
      width: 72px;
      height: 54px;
    }
    .image-caption {
      width: 100%;
      color: #8fa1b2;
      font-size: 11px;
      overflow-wrap: anywhere;
    }
    .gallery-toolbar {
      display: grid;
      grid-template-columns: 1fr auto auto auto;
      gap: 8px;
      align-items: end;
      margin-bottom: 10px;
    }
    .guided-panel {
      display: grid;
      grid-template-columns: minmax(320px, 1fr) minmax(300px, .72fr);
      gap: 12px;
      align-items: stretch;
      margin-bottom: 12px;
    }
    .guided-current {
      border: 1px solid #344252;
      border-radius: 8px;
      background: #10151b;
      padding: 12px;
      display: grid;
      gap: 9px;
      min-height: 190px;
    }
    .guided-topline {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
      color: #9fb2c3;
      font-size: 12px;
    }
    .guided-title {
      font-size: 22px;
      font-weight: 800;
      color: #f8fbff;
      letter-spacing: 0;
    }
    .guided-expected {
      color: #c7d4df;
      font-size: 15px;
      line-height: 1.35;
    }
    .guided-actions {
      display: grid;
      grid-template-columns: repeat(4, minmax(96px, 1fr));
      gap: 8px;
    }
    .guided-actions button {
      min-height: 54px;
      font-size: 17px;
      font-weight: 750;
    }
    .guided-side {
      border: 1px solid #344252;
      border-radius: 8px;
      background: #10151b;
      padding: 12px;
      display: grid;
      gap: 8px;
    }
    .guided-preview {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
      align-items: center;
      min-height: 68px;
    }
    .guided-preview img {
      width: 64px;
      height: 64px;
      object-fit: contain;
      background: #0c1117;
      border: 1px solid #344252;
      border-radius: 7px;
      padding: 4px;
    }
    .image-gallery {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
      gap: 8px;
      max-height: 520px;
      overflow: auto;
      border: 1px solid #27323d;
      border-radius: 8px;
      background: #0b0f14;
      padding: 10px;
    }
    .gallery-item {
      border: 1px solid #2b3743;
      border-radius: 8px;
      background: #111720;
      padding: 8px;
      min-height: 122px;
      display: grid;
      gap: 4px;
      justify-items: center;
      align-content: start;
      text-align: center;
    }
    .gallery-item img {
      width: 72px;
      height: 72px;
      object-fit: contain;
      background: #0c1117;
      border-radius: 6px;
      padding: 3px;
    }
    .gallery-meta {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 11px;
      color: #9fb2c3;
      overflow-wrap: anywhere;
    }
    .result-buttons {
      display: grid;
      grid-template-columns: repeat(3, minmax(68px, 1fr));
      gap: 5px;
      margin-top: 6px;
    }
    @media (max-width: 1080px) {
      .topbar, .grid { grid-template-columns: 1fr; }
      .matrix { grid-template-columns: repeat(3, 1fr); }
      .mapping-row { grid-template-columns: 1fr; }
      .check-toolbar { grid-template-columns: 1fr 1fr; }
      .logic-steps { grid-template-columns: 1fr; }
      .gallery-toolbar { grid-template-columns: 1fr 1fr; }
      .guided-panel { grid-template-columns: 1fr; }
      .guided-actions { grid-template-columns: 1fr 1fr; }
    }
  </style>
</head>
<body>
<main>
  <h1>KIA Navi lane lab</h1>
  <p>Отдельный тестер только для навигации: серые подложки, жёлтые стрелки, знаки/предупреждения, подпись результата и фото.</p>

  <div class="topbar">
    <section>
      <h2>Подключение</h2>
      <div class="row">
        <div>
          <label class="label" for="port">USB serial адаптер</label>
          <select id="port"></select>
        </div>
        <button onclick="refreshPorts()">Обновить</button>
        <button class="primary" onclick="autoOpen()">Auto open</button>
      </div>
      <div class="buttons" style="margin-top:8px">
        <button onclick="openSelected()">Открыть</button>
        <button class="warn" onclick="closePort()">Закрыть</button>
        <button onclick="bootRoute()">Nav start</button>
        <button class="danger" onclick="navOff()">Nav off</button>
      </div>
    </section>

    <section>
      <h2>Статус</h2>
      <div id="status" class="status">loading...</div>
    </section>
  </div>

  <section class="wide">
    <h2>Единая таблица проверки навигации</h2>
    <p class="muted" style="margin-bottom:12px">Рабочая логика теперь разделена на три группы. Каждую строку фиксируем по факту: OK, не то или нет реакции.</p>
    <div class="logic-steps">
      <div class="logic-step">
        <strong>1. Серые подложки b8</strong>
        <span class="muted">Берём одну жёлтую базу и прогоняем b8=00..11. Подписываем, какая серая дорога/полосы реально появились под стрелкой.</span>
      </div>
      <div class="logic-step">
        <strong>2. Жёлтые стрелки</strong>
        <span class="muted">Проверяем семейства b5/b7/b8: прямо, право, лево, съезды, TBT, progress и ищем недостающие варианты.</span>
      </div>
      <div class="logic-step">
        <strong>3. Знаки и предупреждения</strong>
        <span class="muted">Отдельно от манёвров проверяем speed limit, камеры, safety/traffic события и фиксируем, есть ли для них UART-команда.</span>
      </div>
    </div>
    <div class="check-toolbar">
      <div>
        <label class="label" for="checkStreet">Текущая улица</label>
        <input id="checkStreet" value="Текущая улица">
      </div>
      <div>
        <label class="label" for="checkNextStreet">Улица после манёвра / финиш</label>
        <input id="checkNextStreet" value="Следующая улица">
      </div>
      <div>
        <label class="label" for="checkDistance">Дистанция, м</label>
        <input id="checkDistance" value="80">
      </div>
      <div>
        <label class="label" for="checkProgress">Progress 0..9</label>
        <input id="checkProgress" value="0">
      </div>
      <div>
        <label class="label" for="checkSpeed">Лимит</label>
        <input id="checkSpeed" value="60">
      </div>
    </div>

    <div class="guided-panel">
      <div class="guided-current">
        <div class="guided-topline">
          <span id="guidedCounter">0/0</span>
          <span id="guidedModeText">guided off</span>
          <span id="guidedAwaiting">-</span>
        </div>
        <div id="guidedTitle" class="guided-title">Запусти guided test</div>
        <div id="guidedExpected" class="guided-expected">Я буду отправлять текущий кейс, ты говоришь есть/нет/не то, а результат сохранится сам.</div>
        <div id="guidedBytes" class="bytes">-</div>
        <div class="guided-actions">
          <button class="good" onclick="guidedAnswer('ok')">Есть</button>
          <button class="danger" onclick="guidedAnswer('no_rx')">Нет</button>
          <button class="warn" onclick="guidedAnswer('bad')">Не то</button>
          <button onclick="guidedAnswer('skip')">Пропуск</button>
        </div>
      </div>
      <div class="guided-side">
        <div class="two">
          <div>
            <label class="label" for="guidedMode">Очередь</label>
            <select id="guidedMode">
              <option value="gray">1. Серые подложки b8</option>
              <option value="yellow">2. Жёлтые стрелки</option>
              <option value="signs">3. Знаки / предупреждения</option>
              <option value="sendable">Все отправляемые пакеты</option>
              <option value="all">Все строки</option>
              <option value="unknown">Только где надо маппить</option>
              <option value="base">Подготовка маршрута</option>
            </select>
          </div>
          <div>
            <label class="label" for="guidedAuto">После ответа</label>
            <select id="guidedAuto">
              <option value="send">Следующий + отправить</option>
              <option value="next">Только следующий</option>
              <option value="stay">Оставаться</option>
            </select>
          </div>
        </div>
        <div class="buttons">
          <button class="primary" onclick="startGuided(true)">Старт + отправить</button>
          <button onclick="guidedSend()">Отправить текущий</button>
          <button onclick="guidedMove(-1)">Назад</button>
          <button onclick="guidedMove(1)">Дальше</button>
        </div>
        <label class="label" for="guidedNote">Комментарий к текущему</label>
        <input id="guidedNote" placeholder="например: показало правее / другая иконка / пусто">
        <div id="guidedImages" class="guided-preview"></div>
      </div>
    </div>

    <div class="check-table-wrap">
      <table class="check-table">
        <thead>
          <tr>
            <th>#</th>
            <th>Блок</th>
            <th>Проверка</th>
            <th>Картинка</th>
            <th>Официальное KIA поле</th>
            <th>TEYES / команда</th>
            <th>Что должно быть</th>
            <th>Статус</th>
            <th>Отправка</th>
            <th>Фиксация</th>
          </tr>
        </thead>
        <tbody id="checkRows">
          <tr><td colspan="10">loading...</td></tr>
        </tbody>
      </table>
    </div>
  </section>

  <section class="wide">
    <h2>Официальные картинки из KIA NPKG</h2>
    <p class="muted" style="margin-bottom:12px">Контейнер `oem_std5_kia.npkg` разобран в PNG. Здесь можно быстро смотреть extracted assets по индексу, размеру или hash и привязывать их к строкам проверки.</p>
    <div class="gallery-toolbar">
      <div>
        <label class="label" for="imageQuery">Фильтр: index/hash/размер</label>
        <input id="imageQuery" placeholder="например: 0014, 61x61, 7404">
      </div>
      <button onclick="loadImageGallery(0)">Показать</button>
      <button onclick="prevImagePage()">Назад</button>
      <button onclick="nextImagePage()">Дальше</button>
    </div>
    <div class="buttons" style="margin-bottom:10px">
      <button onclick="openContact('contact_sheet_route_61_icons.jpg')">Стрелки/route 61x61</button>
      <button onclick="openContact('contact_sheet_traffic_signs_big.jpg')">Знаки/камеры/предупреждения</button>
      <button onclick="openContact('contact_sheet_square_240.jpg')">Первые square icons</button>
    </div>
    <p class="muted" id="imageGalleryInfo"></p>
    <div id="imageGallery" class="image-gallery"></div>
  </section>

  <div class="grid">
    <section>
      <h2>Сценарий</h2>
      <div class="two">
        <div>
          <label class="label" for="queueKind">Что прогоняем</label>
          <select id="queueKind" onchange="rebuildQueue()">
            <option value="gray18">1. Серые под выбранной жёлтой: 00..11</option>
            <option value="known">1b. Быстрые известные b8</option>
            <option value="arrows">2. Жёлтые стрелки Classic</option>
            <option value="tbt">2b. Жёлтые стрелки TBT</option>
          </select>
        </div>
        <div>
          <label class="label" for="family">База стрелки</label>
          <select id="family" onchange="rebuildQueue()"></select>
        </div>
      </div>

      <div class="three" style="margin-top:8px">
        <div>
          <label class="label" for="distance">Дистанция, м</label>
          <input id="distance" value="80">
        </div>
        <div>
          <label class="label" for="progress">Progress 0..9</label>
          <input id="progress" type="range" min="0" max="9" value="8" oninput="progressOut.textContent=this.value">
          <div class="muted">value: <span id="progressOut">8</span></div>
        </div>
        <div>
          <label class="label" for="delay">Auto, сек</label>
          <input id="delay" value="2.2">
        </div>
      </div>

      <div class="buttons" style="margin-top:10px">
        <button class="primary" onclick="sendCurrent()">Показать текущий</button>
        <button onclick="prevStep()">Назад</button>
        <button class="primary" onclick="nextStep(true)">Дальше + отправить</button>
        <button class="good" onclick="startAuto()">Auto play</button>
        <button class="warn" onclick="stopAuto()">Stop</button>
      </div>

      <div class="current" style="margin-top:12px">
        <div class="timeline">
          <span id="counter">0/0</span>
          <div class="bar"><div id="barFill"></div></div>
          <span id="queueName">-</span>
        </div>
        <div id="currentTitle" class="current-title">-</div>
        <div id="currentBytes" class="bytes">-</div>
        <div id="currentHint" class="muted">-</div>
      </div>

      <h2>Быстрые основные</h2>
      <div class="buttons">
        <button onclick="quick('classic_forward')">Classic прямо</button>
        <button onclick="quick('classic_right')">Classic право</button>
        <button onclick="quick('classic_left')">Classic лево</button>
        <button onclick="quick('classic_exit_right')">Съезд право</button>
        <button onclick="quick('classic_exit_left')">Съезд лево</button>
        <button onclick="quick('tbt_forward')">TBT прямо</button>
        <button onclick="quick('tbt_right')">TBT право</button>
        <button onclick="quick('tbt_left')">TBT лево</button>
      </div>
    </section>

    <section>
      <h2>Матрица b8</h2>
      <p class="muted" style="margin-bottom:10px">Это быстрые кнопки по текущей базе стрелки. Нажал один режим, сразу увидел на popup/экране, подписал ниже.</p>
      <div id="matrix" class="matrix"></div>

      <h2 style="margin-top:14px">Ручной кадр, если нужно добить гипотезу</h2>
      <div class="three">
        <div><label class="label" for="b5">b5</label><input id="b5" value="0D"></div>
        <div><label class="label" for="b6">b6</label><input id="b6" value="00"></div>
        <div><label class="label" for="b7">b7</label><input id="b7" value="00"></div>
      </div>
      <div class="three" style="margin-top:8px">
        <div><label class="label" for="b8">b8</label><input id="b8" value="0C"></div>
        <div><label class="label" for="speedLimit">Лимит</label><input id="speedLimit" value="60"></div>
        <div><label class="label" for="navText">Текст</label><input id="navText" value="Navi lane lab"></div>
      </div>
      <div class="buttons" style="margin-top:10px">
        <button class="primary" onclick="sendManual()">Отправить ручной</button>
        <button onclick="sendSpeed()">Лимит скорости</button>
        <button onclick="sendText()">Текст</button>
      </div>

      <h2 style="margin-top:14px">Лог отправки</h2>
      <div id="log" class="log"></div>
    </section>

    <section>
      <h2>Фиксация результата</h2>
      <label class="label" for="humanLabel">Что реально показалось</label>
      <input id="humanLabel" placeholder="например: 3 полосы, едем прямо, справа съезд">
      <label class="label" for="photoFile" style="margin-top:8px">Фото экрана</label>
      <input id="photoFile" type="file" accept="image/*">
      <label class="label" for="note" style="margin-top:8px">Комментарий</label>
      <textarea id="note" placeholder="что не так, совпало ли с ожиданием"></textarea>
      <div class="buttons" style="margin-top:8px">
        <button class="good" onclick="saveNote('ok')">Сохранить OK</button>
        <button class="warn" onclick="saveNote('bad')">Сохранить не то</button>
        <button onclick="loadNotes()">Обновить журнал</button>
      </div>
      <p class="muted" id="notePath" style="margin-top:8px"></p>
      <div id="notes" class="log" style="margin-top:10px"></div>
    </section>
  </div>

  <section class="wide">
    <h2>Official KIA данные из прошивки</h2>
    <p class="muted" style="margin-bottom:12px">Это не догадки по байтам, а найденные официальные сущности из прошивки Sportage 2018. Ниже видно, что именно нашли и как это связываем с тестами TEYES.</p>
    <div id="officialSummary" class="official-grid"></div>
  </section>
</main>

<script>
const families = {
  classic_forward: {name:'Classic прямо', b5:'0D', b6:'00', b7:'01', b8:'00', hint:'желтая стрелка прямо, классический кадр'},
  classic_right: {name:'Classic направо', b5:'0D', b6:'00', b7:'00', b8:'0C', hint:'желтая стрелка направо'},
  classic_left: {name:'Classic налево', b5:'0D', b6:'00', b7:'00', b8:'24', hint:'желтая стрелка налево'},
  classic_exit_right: {name:'Classic съезд направо', b5:'1F', b6:'00', b7:'00', b8:'0C', hint:'съезд/ответвление направо'},
  classic_exit_left: {name:'Classic съезд налево', b5:'1F', b6:'00', b7:'00', b8:'24', hint:'съезд/ответвление налево'},
  tbt_forward: {name:'TBT прямо', b5:'41', b6:'00', b7:'00', b8:'00', hint:'альтернативный TBT режим прямо'},
  tbt_right: {name:'TBT направо', b5:'43', b6:'00', b7:'00', b8:'00', hint:'альтернативный TBT режим направо'},
  tbt_left: {name:'TBT налево', b5:'46', b6:'00', b7:'00', b8:'00', hint:'альтернативный TBT режим налево'},
};
const gray18 = Array.from({length:18}, (_, i) => i.toString(16).toUpperCase().padStart(2, '0'));
const knownB8 = ['00','02','03','06','09','0C','12','1E','24','2D'];
let queue = [];
let index = 0;
let lastCase = null;
let autoTimer = null;
let checkRows = [];
let imageOffset = 0;
const imageLimit = 120;

function $(id) { return document.getElementById(id); }
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, ch => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#039;',
  }[ch]));
}
function log(line) {
  const stamp = new Date().toLocaleTimeString();
  $('log').textContent = `[${stamp}] ${line}\n` + $('log').textContent;
}
function setPhase(name) {
  const yellow = name === 'yellow';
  $('phaseYellow').classList.toggle('active', yellow);
  $('phaseGray').classList.toggle('active', !yellow);
  $('phaseYellowBtn').classList.toggle('active', yellow);
  $('phaseGrayBtn').classList.toggle('active', !yellow);
}
async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: {'Content-Type':'application/json'},
    ...opts
  });
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = {ok:false, error:text}; }
  if (!res.ok || data.ok === false) throw new Error(data.error || res.statusText);
  return data;
}
function buildFamilySelect() {
  const sel = $('family');
  sel.innerHTML = '';
  Object.entries(families).forEach(([key, item]) => {
    const opt = document.createElement('option');
    opt.value = key;
    opt.textContent = item.name;
    sel.appendChild(opt);
  });
  sel.value = 'classic_right';
}
function caseFrom(baseKey, b8, namePrefix) {
  const base = families[baseKey];
  return {
    id: `${baseKey}_${b8}`,
    title: `${namePrefix}: ${base.name}, b8=${b8}`,
    b5: base.b5, b6: base.b6, b7: base.b7, b8,
    hint: base.hint,
  };
}
function rebuildQueue() {
  const kind = $('queueKind').value;
  const baseKey = $('family').value || 'classic_right';
  if (kind === 'gray18') {
    queue = gray18.map(v => caseFrom(baseKey, v, 'Gray 18'));
  } else if (kind === 'known') {
    queue = knownB8.map(v => caseFrom(baseKey, v, 'Known'));
  } else if (kind === 'tbt') {
    queue = ['tbt_forward','tbt_right','tbt_left'].map(key => {
      const f = families[key];
      return {id:key, title:f.name, b5:f.b5, b6:f.b6, b7:f.b7, b8:f.b8, hint:f.hint};
    });
  } else {
    queue = Object.entries(families)
      .filter(([key]) => key.startsWith('classic_'))
      .map(([key, f]) => ({id:key, title:f.name, b5:f.b5, b6:f.b6, b7:f.b7, b8:f.b8, hint:f.hint}));
  }
  index = Math.min(index, Math.max(0, queue.length - 1));
  renderQueue();
  renderMatrix();
}
function renderQueue() {
  const item = queue[index] || null;
  $('counter').textContent = queue.length ? `${index + 1}/${queue.length}` : '0/0';
  $('barFill').style.width = queue.length ? `${((index + 1) / queue.length) * 100}%` : '0%';
  $('queueName').textContent = $('queueKind').selectedOptions[0]?.textContent || '-';
  if (!item) {
    $('currentTitle').textContent = '-';
    $('currentBytes').textContent = '-';
    $('currentHint').textContent = '-';
    return;
  }
  $('currentTitle').textContent = item.title;
  $('currentBytes').textContent = `b5=${item.b5} b6=${item.b6} b7=${item.b7} b8=${item.b8}`;
  $('currentHint').textContent = item.hint || '-';
  ['b5','b6','b7','b8'].forEach(k => $(k).value = item[k]);
}
function renderMatrix() {
  const baseKey = $('family').value || 'classic_right';
  $('matrix').innerHTML = '';
  gray18.forEach(v => {
    const btn = document.createElement('button');
    btn.textContent = v;
    btn.onclick = () => {
      const item = caseFrom(baseKey, v, 'Matrix');
      sendCase(item);
    };
    $('matrix').appendChild(btn);
  });
}
function payloadFor(item) {
  return {
    action: 'lane_combo',
    b5: item.b5,
    b6: item.b6,
    b7: item.b7,
    b8: item.b8,
    distance: $('distance').value,
    progress: $('progress').value,
    label: $('humanLabel').value,
  };
}
async function sendCase(item) {
  lastCase = item;
  const data = await api('/api/send', {method:'POST', body: JSON.stringify(payloadFor(item))});
  const frames = data.frames || [];
  log(`${item.title} -> ${frames.map(f => f.hex).join(' | ')}`);
}
async function sendCurrent() {
  const item = queue[index];
  if (!item) return;
  await sendCase(item);
}
async function sendManual() {
  await sendCase({
    id: 'manual',
    title: 'Manual frame',
    b5: $('b5').value,
    b6: $('b6').value,
    b7: $('b7').value,
    b8: $('b8').value,
    hint: 'ручной кадр',
  });
}
async function quick(key) {
  const f = families[key];
  const item = {id:key, title:f.name, b5:f.b5, b6:f.b6, b7:f.b7, b8:f.b8, hint:f.hint};
  await sendCase(item);
}
async function sendSpeed() {
  const data = await api('/api/send', {method:'POST', body: JSON.stringify({action:'speed_quick', speed:$('speedLimit').value})});
  log(`speed ${$('speedLimit').value} -> ${data.frames.map(f => f.hex).join(' | ')}`);
}
async function sendText() {
  const data = await api('/api/send', {method:'POST', body: JSON.stringify({action:'text', text:$('navText').value})});
  log(`text -> ${data.frames.map(f => f.hex).join(' | ')}`);
}
async function bootRoute() {
  const data = await api('/api/send', {method:'POST', body: JSON.stringify({
    action:'route_start',
    text:$('navText').value,
    speed:$('speedLimit').value,
  })});
  log(`route start -> ${data.frames.map(f => f.hex).join(' | ')}`);
}
async function navOff() {
  const data = await api('/api/send', {method:'POST', body: JSON.stringify({action:'nav_off'})});
  log(`nav off -> ${data.frames.map(f => f.hex).join(' | ')}`);
}
function nextStep(send) {
  if (!queue.length) return;
  index = Math.min(queue.length - 1, index + 1);
  renderQueue();
  if (send) sendCurrent().catch(e => log(`error: ${e.message}`));
}
function prevStep() {
  if (!queue.length) return;
  index = Math.max(0, index - 1);
  renderQueue();
}
function startAuto() {
  stopAuto();
  sendCurrent().catch(e => log(`error: ${e.message}`));
  const delay = Math.max(.5, Math.min(20, Number(String($('delay').value).replace(',', '.')) || 2.2)) * 1000;
  autoTimer = setInterval(() => {
    if (index >= queue.length - 1) {
      stopAuto();
      return;
    }
    nextStep(true);
  }, delay);
  log(`auto play started, delay=${delay / 1000}s`);
}
function stopAuto() {
  if (autoTimer) clearInterval(autoTimer);
  autoTimer = null;
}
async function refreshPorts() {
  const data = await api('/api/ports');
  const sel = $('port');
  sel.innerHTML = '';
  (data.ports || []).forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.path;
    opt.textContent = `${p.likely ? '★ ' : ''}${p.path}`;
    sel.appendChild(opt);
  });
  const likely = (data.ports || []).find(p => p.likely);
  if (likely) sel.value = likely.path;
}
async function openSelected() {
  const port = $('port').value;
  const data = await api('/api/open', {method:'POST', body: JSON.stringify({port, baud:115200})});
  log(`opened ${data.port}`);
  refreshStatus();
}
async function autoOpen() {
  await refreshPorts();
  await openSelected();
}
async function closePort() {
  await api('/api/close', {method:'POST', body:'{}'});
  log('closed');
  refreshStatus();
}
async function refreshStatus() {
  try {
    const data = await api('/api/status');
    const s = data.serial || {};
    $('status').textContent = [
      `serial: ${s.open ? 'open' : 'closed'} ${s.port || s.last_port || ''}`,
      `baud: ${s.baud || '-'}`,
      `last tx: ${s.last_tx || '-'}`,
      `rx buffer: ${s.rx_buffer || 0}`,
      `notes: ${data.notes_path}`,
    ].join('\n');
  } catch (e) {
    $('status').textContent = e.message;
  }
}
function readPhoto() {
  const file = $('photoFile').files[0];
  if (!file) return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve({name:file.name, dataUrl:String(reader.result || '')});
    reader.onerror = () => reject(reader.error || new Error('photo read failed'));
    reader.readAsDataURL(file);
  });
}
async function saveNote(result) {
  const item = lastCase || queue[index] || {id:'none', title:'none', b5:$('b5').value, b6:$('b6').value, b7:$('b7').value, b8:$('b8').value};
  const photo = await readPhoto();
  const payload = {
    result,
    queue: $('queueKind').value,
    index,
    total: queue.length,
    case: item,
    distance: $('distance').value,
    progress: $('progress').value,
    human_label: $('humanLabel').value,
    note: $('note').value,
    photo,
  };
  const data = await api('/api/note', {method:'POST', body: JSON.stringify(payload)});
  $('notePath').textContent = `saved: ${data.path}${data.photo_path ? ' / photo: ' + data.photo_path : ''}`;
  $('photoFile').value = '';
  log(`note saved ${result}: ${item.title}`);
  await loadNotes();
}
async function loadNotes() {
  const data = await api('/api/notes');
  $('notePath').textContent = data.path;
  $('notes').innerHTML = '';
  (data.notes || []).slice().reverse().forEach(n => {
    const div = document.createElement('div');
    div.className = 'note-item';
    const c = n.case || {};
    div.textContent = `${n.ts} [${n.result}] ${c.title || '-'} b5=${c.b5 || '-'} b6=${c.b6 || '-'} b7=${c.b7 || '-'} b8=${c.b8 || '-'} :: ${n.human_label || ''} ${n.photo_path ? 'photo=' + n.photo_path : ''}`;
    $('notes').appendChild(div);
  });
}
function checkVar(value) {
  if (value === '$currentStreet') return $('checkStreet').value || 'Текущая улица';
  if (value === '$nextStreet') return $('checkNextStreet').value || 'Следующая улица';
  if (value === '$speed') return $('checkSpeed').value || '60';
  if (value === '$distance') return $('checkDistance').value || '80';
  if (value === '$progress') return $('checkProgress').value || '0';
  return value;
}
function buildCheckPayload(row) {
  if (!row || !row.payload) return null;
  const payload = {};
  Object.entries(row.payload).forEach(([key, value]) => { payload[key] = checkVar(value); });
  if (['lane_combo', 'lane_sweep', 'maneuver'].includes(String(payload.action || ''))) {
    if (payload.distance == null) payload.distance = $('checkDistance').value || '80';
    if (payload.progress == null) payload.progress = $('checkProgress').value || '0';
  }
  if (payload.action === 'route_start') {
    payload.text = checkVar(payload.text || '$currentStreet');
    payload.speed = checkVar(payload.speed || '$speed');
  }
  if (payload.action === 'speed_quick') payload.speed = checkVar(payload.speed || '$speed');
  return payload;
}
function guidedInputs() {
  return {
    currentStreet: $('checkStreet').value,
    nextStreet: $('checkNextStreet').value,
    distance: $('checkDistance').value,
    progress: $('checkProgress').value,
    speed: $('checkSpeed').value,
  };
}
function renderGuided(data) {
  const state = data.state || {};
  const row = data.current || null;
  $('guidedCounter').textContent = `${(state.position ?? 0) + (row ? 1 : 0)}/${state.total || 0}`;
  $('guidedModeText').textContent = state.mode ? `mode: ${state.mode}` : 'guided off';
  $('guidedAwaiting').textContent = state.awaiting ? 'жду ответ' : 'готов';
  if (!row) {
    $('guidedTitle').textContent = 'Очередь закончилась';
    $('guidedExpected').textContent = 'Можно начать новую очередь или сменить режим.';
    $('guidedBytes').textContent = '-';
    $('guidedImages').innerHTML = '';
    return;
  }
  $('guidedTitle').textContent = row.check;
  $('guidedExpected').textContent = row.expected || '-';
  const payload = row.resolved_payload || row.payload || null;
  $('guidedBytes').textContent = payload ? JSON.stringify(payload) : 'нет пакета, только фиксация';
  $('guidedImages').innerHTML = (row.images || []).map(img => `
    <img src="${escapeHtml(img.url)}" title="${escapeHtml(img.label || img.file)}">
    <span class="muted">${escapeHtml(img.label || img.file)}</span>
  `).join('');
}
async function refreshGuided() {
  const data = await api('/api/guided/status');
  renderGuided(data);
}
async function startGuided(sendFirst) {
  const data = await api('/api/guided/start', {method:'POST', body: JSON.stringify({
    mode: $('guidedMode').value,
    inputs: guidedInputs(),
  })});
  renderGuided(data);
  log(`guided start: ${data.state.total} items, mode=${data.state.mode}`);
  if (sendFirst) await guidedSend();
}
async function guidedSend() {
  const data = await api('/api/guided/send', {method:'POST', body: JSON.stringify({inputs: guidedInputs()})});
  renderGuided(data);
  const frames = data.frames || [];
  log(`guided send: ${data.current?.check || '-'} -> ${frames.length ? frames.map(f => f.hex).join(' | ') : 'no payload'}`);
}
async function guidedMove(delta) {
  const data = await api('/api/guided/move', {method:'POST', body: JSON.stringify({delta, inputs: guidedInputs()})});
  renderGuided(data);
  log(`guided move: ${data.current?.check || 'end'}`);
}
async function guidedAnswer(result) {
  const auto = $('guidedAuto').value;
  const data = await api('/api/guided/result', {method:'POST', body: JSON.stringify({
    result,
    note: $('guidedNote').value,
    inputs: guidedInputs(),
    advance: auto !== 'stay',
  })});
  $('guidedNote').value = '';
  renderGuided(data);
  log(`guided result ${result}: next=${data.current?.check || 'end'}`);
  await loadNotes();
  if (auto === 'send' && data.current) await guidedSend();
}
function renderCheckRows() {
  const tbody = $('checkRows');
  if (!checkRows.length) {
    tbody.innerHTML = '<tr><td colspan="10">Нет строк проверки</td></tr>';
    return;
  }
  tbody.innerHTML = checkRows.map((row, idx) => {
    const canSend = !!row.payload;
    const status = String(row.status || 'unknown');
    const images = row.images || [];
    const imageHtml = images.length
      ? `<div class="image-stack">${images.map(img => `<img class="official-thumb" src="${escapeHtml(img.url)}" title="${escapeHtml(img.label || img.file)}">`).join('')}<div class="image-caption">${escapeHtml(images.map(img => img.label || img.file).join(' / '))}</div></div>`
      : '<span class="muted">нет</span>';
    return `
      <tr>
        <td>${idx + 1}</td>
        <td class="check-group">${escapeHtml(row.group)}</td>
        <td><div class="check-title">${escapeHtml(row.check)}</div></td>
        <td>${imageHtml}</td>
        <td>${escapeHtml(row.official)}</td>
        <td>${escapeHtml(row.teyes)}</td>
        <td>${escapeHtml(row.expected)}</td>
        <td><span class="status-pill ${escapeHtml(status)}">${escapeHtml(status)}</span></td>
        <td>
          <button class="mini primary" onclick="sendCheckRow('${row.key}')" ${canSend ? '' : 'disabled'}>${canSend ? 'Отправить' : 'Нет пакета'}</button>
        </td>
        <td>
          <input class="row-note" id="checkNote_${row.key}" placeholder="что показала приборка">
          <div class="result-buttons">
            <button class="mini good" onclick="saveCheckResult('${row.key}', 'ok')">OK</button>
            <button class="mini warn" onclick="saveCheckResult('${row.key}', 'bad')">Не то</button>
            <button class="mini" onclick="saveCheckResult('${row.key}', 'no_rx')">Нет реакции</button>
          </div>
        </td>
      </tr>`;
  }).join('');
}
async function loadCheckRows() {
  const data = await api('/api/check-rows');
  checkRows = data.rows || [];
  renderCheckRows();
}
async function loadImageGallery(offset = imageOffset) {
  imageOffset = Math.max(0, offset);
  const params = new URLSearchParams({
    offset: String(imageOffset),
    limit: String(imageLimit),
    q: $('imageQuery')?.value || '',
  });
  const data = await api(`/api/official-images?${params}`);
  imageOffset = data.offset || 0;
  $('imageGalleryInfo').textContent = `images: ${data.total || 0}, shown: ${imageOffset + 1}-${Math.min((data.total || 0), imageOffset + (data.images || []).length)}`;
  $('imageGallery').innerHTML = (data.images || []).map(img => `
    <div class="gallery-item">
      <img src="${escapeHtml(img.url)}" title="${escapeHtml(img.file)}">
      <div class="gallery-meta">idx ${escapeHtml(img.index)}<br>${escapeHtml(img.width)}x${escapeHtml(img.height)}<br>${escapeHtml(img.resource_hash)}</div>
    </div>
  `).join('');
}
function nextImagePage() {
  loadImageGallery(imageOffset + imageLimit).catch(e => log(`image gallery error: ${e.message}`));
}
function prevImagePage() {
  loadImageGallery(Math.max(0, imageOffset - imageLimit)).catch(e => log(`image gallery error: ${e.message}`));
}
function openContact(file) {
  window.open(`/official-images/contact/${encodeURIComponent(file)}`, '_blank');
}
function findCheckRow(key) {
  return checkRows.find(row => row.key === key);
}
async function sendCheckRow(key) {
  const row = findCheckRow(key);
  const payload = buildCheckPayload(row);
  if (!row || !payload) return;
  const data = await api('/api/send', {method:'POST', body: JSON.stringify(payload)});
  lastCase = {
    id: `check:${row.key}`,
    title: row.check,
    group: row.group,
    status: row.status,
    b5: payload.b5,
    b6: payload.b6,
    b7: payload.b7,
    b8: payload.b8,
  };
  log(`check ${row.key}: ${data.frames.map(f => f.hex).join(' | ')}`);
}
async function saveCheckResult(key, result) {
  const row = findCheckRow(key);
  if (!row) return;
  const payload = buildCheckPayload(row);
  const noteEl = $(`checkNote_${key}`);
  const note = noteEl ? noteEl.value : '';
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'check-table',
    index: checkRows.indexOf(row),
    total: checkRows.length,
    case: {
      id: `check:${row.key}`,
      title: row.check,
      group: row.group,
      status: row.status,
      official: row.official,
      teyes: row.teyes,
      expected: row.expected,
      payload,
    },
    distance: $('checkDistance').value,
    progress: $('checkProgress').value,
    human_label: row.check,
    note,
  })});
  $('notePath').textContent = `saved: ${data.path}`;
  log(`check note ${result}: ${row.check}`);
  await loadNotes();
}
function officialCard(title, html) {
  return `<div class="official-card"><h3>${title}</h3>${html}</div>`;
}
function listItems(items, mapper) {
  return `<ul>${items.map(mapper).join('')}</ul>`;
}
async function loadOfficial() {
  const data = await api('/api/official');
  const version = data.version || {};
  const angles = data.angle_buckets || [];
  const objects = data.guidance_objects || [];
  const counts = data.counts || [];
  const turns = data.turn_classes || [];
  const warnings = data.warning_fields || [];
  const fields = data.ui_fields || [];
  const mapping = data.teyes_mapping || [];
  const images = data.images || {};
  $('officialSummary').innerHTML = [
    officialCard('Что скачали', listItems([
      `KIA: ${version.kia || '-'}`,
      `manifest: ${version.manifest || '-'}`,
      `папка: ${data.root || '-'}`,
    ], x => `<li>${x}</li>`)),
    officialCard('Картинки манёвров', `<p class="muted">${images.text || '-'}</p>`),
    officialCard('Сколько чего', listItems(counts, c => `<li><b>${c.value}</b> ${c.name}<br><span class="muted">${c.note}</span></li>`)),
    officialCard('Углы поворотов', `<div>${angles.map(a => `<span class="tag">${a.name}=${a.value}°</span>`).join('')}</div>`),
    officialCard('Жёлтые turn-классы', `<div>${turns.map(t => `<span class="tag">${t}</span>`).join('')}</div>`),
    officialCard('Официальные типы', listItems(objects, o => `<li>${o.name}: main=${o.main_type}, sub=${o.sub_type}</li>`)),
    officialCard('Warning / camera поля', `<div>${warnings.map(f => `<span class="tag">${f}</span>`).join('')}</div>`),
    officialCard('Поля UI', `<div>${fields.map(f => `<span class="tag" title="${f.meaning}">${f.field}</span>`).join('')}</div>`),
    officialCard('Как маппим на TEYES', `<div>${mapping.map(m => `<div class="mapping-row"><div>${m.official}</div><div>${m.teyes}</div><div class="muted">${m.status}</div></div>`).join('')}</div>`),
  ].join('');
}
document.addEventListener('DOMContentLoaded', async () => {
  buildFamilySelect();
  rebuildQueue();
  await refreshPorts().catch(e => log(`ports error: ${e.message}`));
  await refreshStatus();
  await loadNotes().catch(e => log(`notes error: ${e.message}`));
  await loadCheckRows().catch(e => log(`check table error: ${e.message}`));
  await refreshGuided().catch(e => log(`guided error: ${e.message}`));
  await loadImageGallery(0).catch(e => log(`image gallery error: ${e.message}`));
  await loadOfficial().catch(e => log(`official error: ${e.message}`));
  setInterval(refreshStatus, 1000);
});
</script>
</body>
</html>
"""

BASIC_HTML = r"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>KIA Navi base</title>
  <style>
    :root { color-scheme: dark; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    * { box-sizing: border-box; }
    [hidden] { display: none !important; }
    body { margin: 0; background: #0f1216; color: #edf3f8; }
    main { max-width: 980px; margin: 0 auto; padding: 18px; }
    h1 { margin: 0 0 6px; font-size: 24px; letter-spacing: 0; }
    h2 { margin: 0 0 12px; font-size: 16px; color: #dce8f3; }
    p { margin: 0 0 14px; color: #9facba; }
    section { border: 1px solid #28323d; border-radius: 8px; background: #161b21; padding: 14px; margin-top: 12px; }
    button, select, input, textarea {
      border: 1px solid #3b4855;
      border-radius: 7px;
      background: #202831;
      color: #edf3f8;
      font: inherit;
    }
    button { min-height: 40px; padding: 10px 12px; cursor: pointer; }
    button.primary { background: #1e67d8; border-color: #3b82f6; }
    button.good { background: #0f6848; border-color: #22c55e; }
    button.warn { background: #673714; border-color: #d97706; }
    button.danger { background: #6b1d1d; border-color: #ef4444; }
    select, input, textarea { width: 100%; padding: 10px; }
    textarea { min-height: 84px; resize: vertical; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .row { display: grid; grid-template-columns: 1fr auto auto; gap: 8px; align-items: end; }
    .buttons { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; }
    .split-workbench { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; align-items: start; }
    .split-panel { border: 1px solid #2f3b49; border-radius: 8px; background: #10151b; padding: 12px; }
    .split-panel h3 { margin: 0 0 8px; font-size: 15px; }
    .split-buttons { display: grid; grid-template-columns: repeat(auto-fit, minmax(118px, 1fr)); gap: 7px; }
    .split-buttons button { min-height: 54px; padding: 8px; text-align: left; line-height: 1.2; }
    .split-buttons button.active { border-color: #60a5fa; box-shadow: 0 0 0 2px #60a5fa inset; }
    .split-buttons strong { display: block; font-size: 13px; color: #f8fbff; }
    .split-buttons span { display: block; margin-top: 4px; color: #aebdca; font-size: 11px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .byte-strip { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
    .mini-matrix { display: grid; grid-template-columns: repeat(6, 1fr); gap: 7px; margin-top: 8px; }
    .mini-matrix button { min-height: 54px; padding: 7px 5px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; line-height: 1.2; }
    .mini-matrix strong { display: block; font-size: 14px; }
    .mini-matrix span { display: block; margin-top: 3px; color: #aebdca; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; font-size: 11px; }
    .photo-tools { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; align-items: start; }
    .photo-media video, .photo-media img {
      display: none;
      width: 100%;
      max-height: 260px;
      object-fit: contain;
      border: 1px solid #344252;
      border-radius: 8px;
      background: #0b0f14;
    }
    .photo-media video.active, .photo-media img.active { display: block; }
    .photo-name { margin-top: 8px; color: #aebdca; font-size: 12px; overflow-wrap: anywhere; }
    .classifier { display: grid; grid-template-columns: minmax(260px, 1.15fr) minmax(260px, .85fr); gap: 12px; }
    .candidate-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(136px, 1fr)); gap: 8px; }
    .candidate-list button { min-height: 62px; padding: 8px; text-align: left; }
    .candidate-list strong { display: block; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 14px; }
    .candidate-list span { display: block; margin-top: 4px; color: #aebdca; font-size: 11px; line-height: 1.25; }
    .candidate-list button.active { border-color: #60a5fa; box-shadow: 0 0 0 1px #60a5fa inset; }
    .visual-select { display: none; }
    .visual-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
      gap: 8px;
      max-height: 420px;
      overflow: auto;
      padding-right: 2px;
    }
    .visual-card {
      min-height: 118px;
      padding: 8px;
      text-align: center;
      display: grid;
      gap: 5px;
      justify-items: center;
      align-content: center;
    }
    .visual-card.active { border-color: #facc15; box-shadow: 0 0 0 1px #facc15 inset; }
    .visual-card svg { width: 92px; height: 68px; display: block; }
    .visual-card span { color: #d7e2ec; font-size: 11px; line-height: 1.2; }
    .phase-tabs { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
    .phase-tabs button { border-color: #344252; background: #10151b; }
    .phase-tabs button.active { border-color: #3b82f6; background: #1e67d8; }
    .inventory-panel { border-color: #2f3b49; background: #0d1218; }
    .inventory-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .inventory-column h3 { margin: 0 0 8px; font-size: 15px; }
    .inventory-counts { color: #aebdca; font-size: 12px; margin: 0 0 8px; }
    .inventory-buttons { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
    .inventory-buttons button { min-height: 30px; padding: 6px 8px; font-size: 12px; }
    .inventory-buttons .yellow { border-color: #facc15; background: #2a2410; color: #fff6c7; }
    .inventory-buttons .gray { border-color: #8b9bad; background: #18202a; color: #e3edf8; }
    .inventory-buttons button.active { box-shadow: 0 0 0 2px #60a5fa inset; }
    button.yellow { border-color: #facc15; background: #2a2410; color: #fff6c7; }
    .inventory-workbench {
      display: grid;
      grid-template-columns: minmax(280px, .9fr) minmax(320px, 1.1fr);
      gap: 10px;
      margin: 10px 0 12px;
    }
    .yandex-fill select[size] { min-height: 340px; }
    .yandex-fill option { padding: 5px 6px; }
    .yandex-fill code { color: #d7e2ec; }
    .inventory-workbench textarea { min-height: 70px; }
    .inventory-table-wrap { max-height: 360px; overflow: auto; border: 1px solid #27323d; border-radius: 8px; background: #0b0f14; }
    .inventory-table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .inventory-table th, .inventory-table td { padding: 7px; border-bottom: 1px solid #202a34; text-align: left; vertical-align: top; }
    .inventory-table tr { cursor: pointer; }
    .inventory-table th { position: sticky; top: 0; background: #111821; color: #aebdca; z-index: 1; }
    .inventory-table code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #d7e2ec; }
    .inventory-table .found { color: #86efac; }
    .inventory-table .missing { color: #fca5a5; }
    .inventory-table .note { color: #aebdca; overflow-wrap: anywhere; }
    .phase-panel { display: none; }
    .phase-panel.active { display: block; }
    .legacy-classifier { display: none; }
    .yellow-lab { display: grid; grid-template-columns: minmax(420px, 1.1fr) minmax(300px, .9fr); gap: 12px; }
    .yellow-table-wrap {
      max-height: 520px;
      overflow: auto;
      border: 1px solid #27323d;
      border-radius: 8px;
      background: #0b0f14;
    }
    .yellow-table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .yellow-table th, .yellow-table td { padding: 8px; border-bottom: 1px solid #202a34; text-align: left; vertical-align: top; }
    .yellow-table th { position: sticky; top: 0; background: #111821; color: #aebdca; z-index: 1; }
    .yellow-table tr { cursor: pointer; }
    .yellow-table tr.active { background: #173055; }
    .yellow-table code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #d7e2ec; }
    .yellow-table .saved { color: #facc15; overflow-wrap: anywhere; }
    .yellow-table button { min-height: 30px; padding: 6px 8px; }
    .yellow-editor { position: sticky; top: 12px; align-self: start; }
    .clock-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 7px; margin: 8px 0 10px; }
    .clock-grid button { min-height: 34px; padding: 7px 6px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .label { display: block; margin-bottom: 5px; color: #aebdca; font-size: 12px; }
    .status, .log {
      white-space: pre-wrap;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 12px;
      line-height: 1.45;
      color: #d7e2ec;
    }
    .status { min-height: 74px; }
    .log {
      min-height: 190px;
      max-height: 320px;
      overflow: auto;
      border: 1px solid #27323d;
      border-radius: 8px;
      background: #0b0f14;
      padding: 10px;
    }
    .summary {
      border: 1px solid #344252;
      border-radius: 8px;
      background: #10151b;
      padding: 12px;
      margin-top: 10px;
      color: #cfe0ef;
    }
    .summary b { color: #f2f7fb; }
    @media (max-width: 760px) {
      .grid, .row, .photo-tools, .classifier, .phase-tabs, .yellow-lab, .inventory-grid, .inventory-workbench, .split-workbench, .byte-strip { grid-template-columns: 1fr; }
      .mini-matrix { grid-template-columns: repeat(3, 1fr); }
    }
  </style>
</head>
<body>
<main>
  <h1>KIA Navi base</h1>
  <p>Пока только базовая навигация: текущая улица, манёвр, до манёвра, ETA финиша и км до финиша.</p>

  <section>
    <h2>Подключение</h2>
    <div class="row">
      <div>
        <label class="label" for="port">USB serial адаптер</label>
        <select id="port"></select>
      </div>
      <button onclick="refreshPorts()">Обновить</button>
      <button class="primary" onclick="autoOpen()">Auto open</button>
    </div>
    <div class="buttons" style="margin-top:8px">
      <button onclick="openSelected()">Открыть</button>
      <button class="warn" onclick="closePort()">Закрыть</button>
      <button class="primary" onclick="sendNavOn()">Nav on</button>
      <button class="danger" onclick="sendNavOff()">Nav off</button>
    </div>
    <div id="status" class="status" style="margin-top:10px">loading...</div>
  </section>

  <section>
    <h2>База маршрута</h2>
    <div class="grid">
      <div>
        <label class="label" for="street">Текущая улица</label>
        <input id="street" value="Текущая улица">
      </div>
      <div>
        <label class="label" for="maneuver">Манёвр</label>
        <select id="maneuver">
          <option value="forward" selected>Прямо</option>
          <option value="right">Направо</option>
          <option value="left">Налево</option>
          <option value="exit_right">Съезд направо</option>
          <option value="exit_left">Съезд налево</option>
          <option value="tbt_forward">TBT прямо</option>
          <option value="tbt_right">TBT направо</option>
          <option value="tbt_left">TBT налево</option>
        </select>
      </div>
      <div>
        <label class="label" for="distanceM">До манёвра, м</label>
        <input id="distanceM" value="80">
      </div>
      <div>
        <label class="label" for="finishEta">ETA / время финиша</label>
        <input id="finishEta" value="demo">
      </div>
      <div>
        <label class="label" for="finishKm">До финиша, км</label>
        <input id="finishKm" value="1.0">
      </div>
      <div>
        <label class="label" for="progress">Progress 0..9</label>
        <input id="progress" value="0">
      </div>
    </div>
    <div class="summary" id="summary"></div>
    <div class="buttons" style="margin-top:10px">
      <button class="primary" onclick="sendBase()">Отправить базу</button>
      <button onclick="sendStreet()">Только улицу</button>
      <button onclick="sendManeuver()">Только манёвр</button>
      <button class="good" onclick="saveResult('ok')">Есть</button>
      <button class="warn" onclick="saveResult('bad')">Не то</button>
      <button class="danger" onclick="saveResult('no_rx')">Нет реакции</button>
    </div>
    <label class="label" for="note" style="margin-top:10px">Комментарий</label>
    <textarea id="note" placeholder="что показала приборка"></textarea>
  </section>

  <section>
    <h2>Раздельная сборка кадра</h2>
    <p>Тут специально разделено: серая дорога меняет только <b>d6/d7</b>, жёлтая стрелка меняет только <b>d8</b>. Семья <b>d5/b5</b> отдельно.</p>
    <div class="byte-strip">
      <div>
        <label class="label" for="splitB5">d5 / b5 семья</label>
        <select id="splitB5">
          <option value="0D" selected>0D обычный манёвр</option>
          <option value="1F">1F съезд / развилка</option>
          <option value="20">20 круговое</option>
          <option value="02">02 стрелка к флагу</option>
          <option value="03">03 финиш</option>
        </select>
      </div>
      <div>
        <label class="label" for="splitB6">d6 / b6 только серая левая</label>
        <input id="splitB6" value="00">
      </div>
      <div>
        <label class="label" for="splitB7">d7 / b7 серая прямо/правая</label>
        <input id="splitB7" value="01">
      </div>
      <div>
        <label class="label" for="splitB8">d8 / b8 только жёлтая</label>
        <input id="splitB8" value="00">
      </div>
    </div>
    <div id="splitReadout" class="summary"></div>
    <div class="split-workbench">
      <div class="split-panel">
        <h3>1. Серая дорога: меняет только d6/d7</h3>
        <div id="splitGrayButtons" class="split-buttons"></div>
      </div>
      <div class="split-panel">
        <h3>2. Жёлтая стрелка: меняет только d8</h3>
        <div id="splitYellowButtons" class="split-buttons"></div>
      </div>
    </div>
    <label class="label" for="splitNote" style="margin-top:10px">Подпись проверки</label>
    <input id="splitNote" placeholder="например: серая прямо+лево, жёлтая 15:00, всё совпало">
    <div class="buttons" style="margin-top:10px">
      <button class="primary" onclick="sendSplitFrame('manual')">Отправить текущий кадр</button>
      <button class="good" onclick="saveSplitFrame('ok')">OK сохранить</button>
      <button class="warn" onclick="saveSplitFrame('bad')">Не то</button>
      <button class="danger" onclick="saveSplitFrame('no_rx')">Нет реакции</button>
    </div>
  </section>

  <section hidden>
    <h2>Фото приборки</h2>
    <div class="photo-tools">
      <div>
        <div class="buttons">
          <button onclick="startCamera()">Камера</button>
          <button class="primary" onclick="capturePhoto()">Снимок</button>
          <button class="warn" onclick="clearPhoto()">Очистить</button>
        </div>
        <label class="label" for="photoFile" style="margin-top:10px">Файл фото</label>
        <input id="photoFile" type="file" accept="image/*" capture="environment">
        <div id="photoName" class="photo-name">фото не выбрано</div>
      </div>
      <div class="photo-media">
        <video id="cameraPreview" playsinline muted></video>
        <img id="photoPreview" alt="">
        <canvas id="photoCanvas" style="display:none"></canvas>
      </div>
    </div>
  </section>

  <section class="inventory-panel">
    <h2>Yandex mapping</h2>
    <div id="yandexSimpleSummary" class="inventory-counts">слева выбираешь Yandex map, жёлтый манёвр сразу уходит на приборку, справа подбираешь серую дорогу и сохраняешь</div>
    <div class="inventory-workbench yandex-fill">
      <div>
        <label class="label" for="yandexFillSelect">Yandex map</label>
        <select id="yandexFillSelect" size="14"></select>
        <div id="yandexFillCounts" class="inventory-counts" style="margin-top:8px">загрузка...</div>
      </div>
      <div>
        <div id="yandexFillActive" class="summary" style="margin-top:0">выбери пункт слева</div>
        <label class="label" for="yandexFillGray" style="margin-top:10px">Серая дорога</label>
        <select id="yandexFillGray"></select>
        <div id="yandexFillTx" class="summary"></div>
        <label class="label" for="yandexFillNote" style="margin-top:10px">Подпись проверки</label>
        <input id="yandexFillNote" placeholder="что показала приборка / что исправить">
        <div class="buttons" style="margin-top:10px">
          <button class="yellow" onclick="sendYandexFillYellow()">Жёлтый</button>
          <button class="primary" onclick="sendYandexFillCombo()">Жёлтый + серый</button>
          <button class="good" onclick="saveYandexFill('ok')">OK сохранить</button>
          <button class="warn" onclick="saveYandexFill('bad')">Не то</button>
          <button class="danger" onclick="saveYandexFill('no_rx')">Нет</button>
        </div>
      </div>
    </div>
    <div id="yandexSimpleButtons" class="inventory-buttons" hidden></div>
    <div id="yandexSimpleTable" class="inventory-table-wrap" style="margin-bottom:12px" hidden></div>
    <div hidden>
      <select id="roundaboutYellowFixed"></select>
      <input id="roundaboutGrayNote">
      <div id="roundaboutGraySummary" class="inventory-counts"></div>
      <div id="roundaboutGrayButtons" class="inventory-buttons"></div>
      <div id="roundaboutGrayTable" class="inventory-table-wrap"></div>
    </div>
    <h3>Осталось искать</h3>
    <div id="yandexMissingSummary" class="inventory-counts">поиск только того, чего нет в маршруте: знаки и серая дорога кругового движения</div>
    <div id="yandexMissingButtons" class="inventory-buttons"></div>
    <div id="yandexMissingTable" class="inventory-table-wrap" style="margin-bottom:12px"></div>
  </section>

  <section class="inventory-panel" hidden>
    <h2>Что уже подписано</h2>
    <div id="inventorySummary" class="inventory-counts">загрузка...</div>
    <div class="inventory-workbench">
      <div id="inventoryActive" class="summary" style="margin-top:0">выбери жёлтую кнопку слева</div>
      <div>
        <label class="label" for="inventoryObservation">Подпись для верхней проверки</label>
        <textarea id="inventoryObservation" placeholder="что показала приборка после верхней кнопки"></textarea>
        <div class="buttons" style="margin-top:8px">
          <button class="primary" onclick="sendInventoryComboSearch()">Проверить связку выбранных</button>
          <button class="good" onclick="saveInventoryResult('observed')">Сохранить верхнюю проверку</button>
          <button class="danger" onclick="saveInventoryResult('no_rx')">Нет реакции</button>
        </div>
      </div>
    </div>
    <div>
      <h3>Связки exact для маршрута</h3>
      <div id="comboInventoryCounts" class="inventory-counts"></div>
      <div id="comboInventoryButtons" class="inventory-buttons"></div>
      <div id="comboInventoryTable" class="inventory-table-wrap" style="margin-bottom:12px"></div>
    </div>
    <div class="inventory-grid">
      <div class="inventory-column">
        <h3>Жёлтые</h3>
        <div id="yellowInventoryCounts" class="inventory-counts"></div>
        <div id="yellowInventoryButtons" class="inventory-buttons"></div>
        <div id="yellowInventoryTable" class="inventory-table-wrap"></div>
      </div>
      <div class="inventory-column">
        <h3>Серые</h3>
        <div id="grayInventoryCounts" class="inventory-counts"></div>
        <div id="grayInventoryButtons" class="inventory-buttons"></div>
        <div id="grayInventoryTable" class="inventory-table-wrap"></div>
      </div>
    </div>
  </section>

  <div class="phase-tabs" hidden>
    <button id="phaseYellowBtn" class="active" onclick="setPhase('yellow')">1. Жёлтые стрелки</button>
    <button id="phaseGrayBtn" onclick="setPhase('gray')">2. Серые дороги</button>
  </div>

  <section class="legacy-classifier" hidden>
    <h2>Быстрая разметка</h2>
    <div class="classifier">
      <div>
        <div class="grid">
          <div>
            <label class="label" for="classifyGroup">Группа</label>
            <select id="classifyGroup">
              <option value="yellow">Чисто жёлтая / жёлтая подсказка</option>
              <option value="gray" selected>Чисто серая дорога</option>
            </select>
          </div>
          <div>
            <label class="label" for="classifyRange">Диапазон</label>
            <select id="classifyRange">
              <option value="known" selected>База</option>
              <option value="next">Дальше</option>
              <option value="all">Все</option>
            </select>
          </div>
        </div>
        <div id="classifyCandidates" class="candidate-list" style="margin-top:10px"></div>
      </div>
      <div>
        <label class="label" for="visualPreset">На что похоже</label>
        <select id="visualPreset" class="visual-select"></select>
        <div id="visualPresetGrid" class="visual-grid"></div>
        <label class="label" for="visualCustom" style="margin-top:10px">Своя подпись</label>
        <input id="visualCustom" placeholder="если нет подходящей заготовки">
        <div id="classifyReadout" class="summary"></div>
        <div class="buttons" style="margin-top:10px">
          <button class="primary" onclick="sendClassifyCurrent()">Отправить выбранное</button>
          <button class="good" onclick="saveClassifyCurrent('ok')">Сохранить</button>
          <button class="warn" onclick="saveClassifyCurrent('bad')">Не то</button>
          <button class="danger" onclick="saveClassifyCurrent('no_rx')">Нет реакции</button>
        </div>
      </div>
    </div>
  </section>

  <div id="phaseYellow" class="phase-panel active" hidden>
  <section>
    <h2>Жёлтые стрелки</h2>
    <p>Чистый журнал жёлтых: слева exact-команда, справа только твоя подпись. Авто-догадок право/лево здесь нет.</p>
    <div id="yellowLogic" class="summary"></div>
    <div class="yellow-lab" style="margin-top:10px">
      <div>
        <label class="label" for="yellowSeries">Показать команды</label>
        <select id="yellowSeries">
          <option value="rotary_kia_b8_full" selected>Поиск жёлтых кругов: b5=20 полный b8</option>
          <option value="rotary_kia_b8">Поиск жёлтых кругов: шаг 03</option>
          <option value="rotary_kia_tbt">Круги KIA TBT 60/61</option>
          <option value="all">Поиск всех жёлтых неизвестных</option>
          <option value="step3">Поиск стрелок: шаг 03</option>
          <option value="b8_all">Поиск стрелок: полный b8</option>
          <option value="classic">Поиск classic exact</option>
          <option value="tbt">Поиск TBT b5 exact 40..4F</option>
        </select>
        <div id="grayMatrix" class="yellow-table-wrap" style="margin-top:10px"></div>
      </div>
      <div class="yellow-editor">
        <input id="grayB8" value="00" type="hidden">
        <label class="label">Выбранная команда</label>
        <div id="yellowSelected" class="summary" style="margin-top:0">-</div>
        <div id="grayReadout" class="summary"></div>
        <label class="label" for="yellowVisual" style="margin-top:10px">Что показала приборка</label>
        <div id="yellowClockButtons" class="clock-grid"></div>
        <textarea id="yellowVisual" placeholder="например: 12:00 прямо, 1:00, 4:30, нет жёлтой, непонятная развилка..."></textarea>
        <div class="buttons" style="margin-top:10px">
          <button class="good" onclick="saveGrayResult('observed')">Сохранить подпись</button>
          <button class="danger" onclick="saveGrayResult('no_rx')">Нет реакции</button>
        </div>
      </div>
    </div>
  </section>
  </div>

  <div id="phaseGray" class="phase-panel" hidden>
  <section>
    <h2>Серые дороги / полосы</h2>
    <p>Тот же журнал, что у жёлтых: слева exact-команда, клик сразу отправляет, справа твоя подпись серой дороги или полос.</p>
    <div id="grayRoadLogic" class="summary"></div>
    <div class="yellow-lab" style="margin-top:10px">
      <div>
        <label class="label" for="graySeries">Показать команды</label>
        <select id="graySeries">
          <option value="b7_full" selected>Поиск B7 полный 00..3F</option>
          <option value="b7_main">Поиск B7 00..23</option>
          <option value="lanes_b6">Поиск B6 левый веер 00..07</option>
          <option value="all">Поиск всех серых неизвестных</option>
        </select>
        <div id="grayRoadMatrix" class="yellow-table-wrap" style="margin-top:10px"></div>
      </div>
      <div class="yellow-editor">
        <label class="label">Выбранная команда</label>
        <div id="grayRoadSelected" class="summary" style="margin-top:0">-</div>
        <div id="grayRoadReadout" class="summary"></div>
        <label class="label" for="grayVisual" style="margin-top:10px">Что показала серая дорога / полосы</label>
        <div id="grayQuickButtons" class="clock-grid"></div>
        <textarea id="grayVisual" placeholder="например: прямо, 2 полосы, съезд справа, развилка, нет серой..."></textarea>
        <div class="buttons" style="margin-top:10px">
          <button class="good" onclick="saveGrayCommandResult('observed')">Сохранить подпись</button>
          <button class="danger" onclick="saveGrayCommandResult('no_rx')">Нет реакции</button>
        </div>
      </div>
    </div>
  </section>
  </div>

  <section>
    <h2>Лог</h2>
    <div id="log" class="log"></div>
  </section>
</main>
<script>
const $ = id => document.getElementById(id);
let currentPhoto = null;
let cameraStream = null;
let yellowCurrent = null;
let inventorySelectedYellow = null;
let inventorySelectedGray = null;
let lastInventorySend = null;
let splitGrayId = 'straight';
let splitYellowId = 'forward';
const maneuvers = {
  forward: {name:'Прямо', b5:'0D', b6:'00', b7:'01', b8:'00'},
  right: {name:'Направо', b5:'0D', b6:'00', b7:'00', b8:'0C'},
  left: {name:'Налево', b5:'0D', b6:'00', b7:'00', b8:'24'},
  exit_right: {name:'Съезд направо', b5:'1F', b6:'00', b7:'00', b8:'0C'},
  exit_left: {name:'Съезд налево', b5:'1F', b6:'00', b7:'00', b8:'24'},
  tbt_forward: {name:'TBT прямо', b5:'41', b6:'00', b7:'00', b8:'00'},
  tbt_right: {name:'TBT направо', b5:'43', b6:'00', b7:'00', b8:'00'},
  tbt_left: {name:'TBT налево', b5:'46', b6:'00', b7:'00', b8:'00'},
};
const splitGrayOptions = [
  {id:'straight', name:'прямо', b6:'00', b7:'01'},
  {id:'right', name:'направо', b6:'00', b7:'10'},
  {id:'straight_right', name:'прямо + право', b6:'00', b7:'11'},
  {id:'left', name:'налево', b6:'08', b7:'00'},
  {id:'straight_left', name:'прямо + лево', b6:'08', b7:'01'},
  {id:'left_right', name:'лево + право', b6:'08', b7:'10'},
  {id:'straight_left_right', name:'прямо + лево + право', b6:'08', b7:'11'},
  {id:'exit_right', name:'съезд справа', b6:'00', b7:'03'},
  {id:'exit_left', name:'съезд слева', b6:'20', b7:'00'},
];
const splitYellowOptions = [
  {id:'forward', name:'12:00 прямо', b8:'00'},
  {id:'right_15', name:'15:00 право', b8:'0C'},
  {id:'right_16', name:'16:00', b8:'0F'},
  {id:'left_21', name:'21:00 лево', b8:'24'},
  {id:'left_22', name:'22:00', b8:'27'},
];
const grayB8Values = Array.from({length: 18}, (_, index) => index.toString(16).toUpperCase().padStart(2, '0'));
const grayShapeB7Values = Array.from({length: 18}, (_, index) => index.toString(16).toUpperCase().padStart(2, '0'));
const grayLeftB7Values = Array.from({length: 18}, (_, index) => (index + 0x12).toString(16).toUpperCase().padStart(2, '0'));
const grayB8Hints = {
  '00': 'серая прямо + жёлтая прямо',
  '01': 'чистая серая прямо',
  '03': 'съезд вправо',
  '06': 'съезд грубее',
  '09': 'съезд ещё правее',
  '0C': 'жёлтая вправо',
  '0F': 'крутой вправо',
  '24': 'classic налево',
};
const grayShapeB7Hints = {
  '01': 'чистая прямо',
  '02': 'под 03 съезд',
  '03': 'прямо + съезд',
  '04': 'съезд больше',
  '05': 'прямо + съезд больше',
  '06': '2 съезда',
  '07': 'прямо + 2 съезда',
  '08': 'съезд крупнее',
  '09': 'съезд + прямо',
  '0A': 'сильнее вправо',
  '0B': 'сильнее + прямо',
  '0C': 'съезд с разделением',
  '0D': 'разделение + прямо',
  '0E': '3 съезда вправо',
  '0F': '3 съезда + прямо',
  '10': 'главная вправо + съезд',
  '11': 'прямо + 2 вправо',
};
const grayShapeB7Details = {
  '01': 'чистая серая прямо без жёлтой',
  '02': 'идеально ложится под b8=03: съезд вправо',
  '03': 'серая прямо + съезд направо как у b8=03',
  '04': 'только съезд направо чуть больше',
  '05': 'прямо + съезд направо чуть больше',
  '06': 'съезд направо мелкий и более крупный',
  '07': 'прямо + два съезда: мелкий и средний',
  '08': 'только съезд, уже чуть больше',
  '09': 'то же, но плюс прямо',
  '0A': 'съезд направо сильнее и ещё правее',
  '0B': 'то же, но плюс прямо',
  '0C': 'съезд направо, внутри будто разделение дороги',
  '0D': 'то же, но плюс прямо',
  '0E': 'три съезда направо',
  '0F': 'три съезда направо + прямо',
  '10': 'главная дорога уходит направо, и от неё съезд направо',
  '11': 'прямо, от неё съезд направо и ещё дальше направо',
};
const grayLeftB7Details = {};
const visualPresets = [
  ['unknown', 'не подписано'],
  ['straight', 'прямо'],
  ['yellow_straight', 'жёлтая прямо'],
  ['gray_straight', 'серая прямо'],
  ['right_exit_small', 'съезд вправо малый'],
  ['right_exit_mid', 'съезд вправо средний'],
  ['right_exit_big', 'съезд вправо большой'],
  ['right_turn', 'поворот вправо'],
  ['right_turn_sharp', 'крутой вправо'],
  ['right_main_exit', 'главная вправо + съезд'],
  ['right_split', 'разделение вправо'],
  ['right_two_exits', '2 съезда вправо'],
  ['right_three_exits', '3 съезда вправо'],
  ['straight_right_exit', 'прямо + съезд вправо'],
  ['straight_two_right', 'прямо + 2 вправо'],
  ['straight_three_right', 'прямо + 3 вправо'],
  ['left_exit', 'съезд влево'],
  ['left_turn', 'поворот влево'],
  ['left_turn_sharp', 'крутой влево'],
  ['left_split', 'разделение влево'],
  ['rotary', 'круг / rotary'],
  ['none', 'нет реакции'],
  ['other', 'другое'],
];
let classifyCurrent = null;
function log(line) {
  const stamp = new Date().toLocaleTimeString();
  $('log').textContent = `[${stamp}] ${line}\n` + $('log').textContent;
}
function setPhase(name) {
  const yellow = name === 'yellow';
  $('phaseYellow').classList.toggle('active', yellow);
  $('phaseGray').classList.toggle('active', !yellow);
  $('phaseYellowBtn').classList.toggle('active', yellow);
  $('phaseGrayBtn').classList.toggle('active', !yellow);
}
async function api(path, opts = {}) {
  const res = await fetch(path, {headers: {'Content-Type':'application/json'}, ...opts});
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = {ok:false, error:text}; }
  if (!res.ok || data.ok === false) throw new Error(data.error || res.statusText);
  return data;
}
function currentManeuver() {
  return maneuvers[$('maneuver').value] || maneuvers.right;
}
function normalizeByte(value) {
  let text = String(value || '00').trim().toUpperCase().replace(/^0X/, '');
  if (!/^[0-9A-F]{1,2}$/.test(text)) text = '00';
  return text.padStart(2, '0');
}
function parseProtocolByte(value, fallback = 0) {
  const text = String(value ?? '').trim().toLowerCase();
  if (!text) return fallback & 0xFF;
  let parsed = fallback;
  if (text.startsWith('0x') || /[a-f]/.test(text)) {
    parsed = parseInt(text.replace(/^0x/, ''), 16);
  } else {
    parsed = parseInt(text, 10);
  }
  if (!Number.isFinite(parsed)) parsed = fallback;
  return Math.max(0, Math.min(255, parsed));
}
function actualHexByte(value, fallback = 0) {
  return parseProtocolByte(value, fallback).toString(16).toUpperCase().padStart(2, '0');
}
function byteCell(value, fallback = 0) {
  const raw = String(value ?? '').trim().toUpperCase().replace(/^0X/, '') || actualHexByte(fallback);
  const actual = actualHexByte(value, fallback);
  return raw === actual ? actual : `${raw}->${actual}`;
}
function hexRange(start, end) {
  const values = [];
  for (let value = start; value <= end; value += 1) {
    values.push(value.toString(16).toUpperCase().padStart(2, '0'));
  }
  return values;
}
function withRoute(payload) {
  return {
    ...payload,
    distance: $('distanceM').value || '80',
    progress: $('progress').value || '0',
  };
}
function byteLine(payload) {
  return `b5=${byteCell(payload.b5)} b6=${byteCell(payload.b6)} b7=${byteCell(payload.b7)} b8=${byteCell(payload.b8)}`;
}
function splitGrayOption() {
  return splitGrayOptions.find(item => item.id === splitGrayId) || {
    id: 'manual',
    name: 'ручная серая',
    b6: normalizeByte($('splitB6').value),
    b7: normalizeByte($('splitB7').value),
  };
}
function splitYellowOption() {
  return splitYellowOptions.find(item => item.id === splitYellowId) || {
    id: 'manual',
    name: 'ручная жёлтая',
    b8: normalizeByte($('splitB8').value),
  };
}
function splitPayload() {
  const gray = splitGrayOption();
  const yellow = splitYellowOption();
  return withRoute({
    action: 'lane_combo',
    b5: `0x${normalizeByte($('splitB5').value)}`,
    b6: `0x${normalizeByte($('splitB6').value)}`,
    b7: `0x${normalizeByte($('splitB7').value)}`,
    b8: `0x${normalizeByte($('splitB8').value)}`,
    label: `gray:${gray.name} / yellow:${yellow.name}`,
  });
}
function updateSplitReadout() {
  const gray = splitGrayOption();
  const yellow = splitYellowOption();
  const payload = splitPayload();
  const root = $('splitReadout');
  if (root) {
    root.innerHTML =
      `<b>Текущий кадр:</b> <code>${escapeHtml(byteLine(payload))}</code><br>` +
      `<b>Серая:</b> ${escapeHtml(gray.name)} меняет только d6=${escapeHtml(normalizeByte($('splitB6').value))} d7=${escapeHtml(normalizeByte($('splitB7').value))}<br>` +
      `<b>Жёлтая:</b> ${escapeHtml(yellow.name)} меняет только d8=${escapeHtml(normalizeByte($('splitB8').value))}`;
  }
  renderSplitActiveButtons();
}
function renderSplitActiveButtons() {
  splitGrayOptions.forEach(item => {
    const btn = $(`splitGray_${item.id}`);
    if (btn) btn.classList.toggle('active', item.id === splitGrayId);
  });
  splitYellowOptions.forEach(item => {
    const btn = $(`splitYellow_${item.id}`);
    if (btn) btn.classList.toggle('active', item.id === splitYellowId);
  });
}
function renderSplitButtons() {
  const grayRoot = $('splitGrayButtons');
  const yellowRoot = $('splitYellowButtons');
  if (grayRoot) {
    grayRoot.innerHTML = splitGrayOptions.map(item => `
      <button id="splitGray_${escapeHtml(item.id)}" onclick="selectSplitGray('${escapeHtml(item.id)}')">
        <strong>${escapeHtml(item.name)}</strong>
        <span>d6=${escapeHtml(item.b6)} d7=${escapeHtml(item.b7)}</span>
      </button>
    `).join('');
  }
  if (yellowRoot) {
    yellowRoot.innerHTML = splitYellowOptions.map(item => `
      <button id="splitYellow_${escapeHtml(item.id)}" onclick="selectSplitYellow('${escapeHtml(item.id)}')">
        <strong>${escapeHtml(item.name)}</strong>
        <span>d8=${escapeHtml(item.b8)}</span>
      </button>
    `).join('');
  }
  updateSplitReadout();
}
async function selectSplitGray(id) {
  const item = splitGrayOptions.find(option => option.id === id);
  if (!item) return;
  splitGrayId = item.id;
  $('splitB6').value = item.b6;
  $('splitB7').value = item.b7;
  updateSplitReadout();
  await sendSplitFrame('gray');
}
async function selectSplitYellow(id) {
  const item = splitYellowOptions.find(option => option.id === id);
  if (!item) return;
  splitYellowId = item.id;
  $('splitB8').value = item.b8;
  updateSplitReadout();
  await sendSplitFrame('yellow');
}
async function sendSplitFrame(reason = 'manual') {
  const payload = splitPayload();
  const data = await api('/api/send', {method:'POST', body:JSON.stringify(payload)});
  const frames = (data.frames || []).map(item => item.hex).join(' | ');
  log(`split ${reason}: ${byteLine(payload)} ${frames || 'sent'}`);
}
async function saveSplitFrame(result) {
  const payload = splitPayload();
  const gray = splitGrayOption();
  const yellow = splitYellowOption();
  const note = ($('splitNote').value || '').trim();
  const data = await api('/api/note', {method:'POST', body:JSON.stringify({
    result,
    queue:'split-gray-yellow',
    index:0,
    total:1,
    case:{
      id:`split-${gray.id}-${yellow.id}`,
      title:`${gray.name} + ${yellow.name}`,
      group:'split-gray-yellow',
      gray_key:gray.id,
      gray_name:gray.name,
      gray_bytes:{b6:normalizeByte($('splitB6').value), b7:normalizeByte($('splitB7').value)},
      yellow_key:yellow.id,
      yellow_name:yellow.name,
      yellow_bytes:{b8:normalizeByte($('splitB8').value)},
      tx_text:byteLine(payload),
      payload,
    },
    distance:$('distanceM').value || '80',
    progress:$('progress').value || '0',
    human_label:note || `${gray.name} + ${yellow.name}: ${byteLine(payload)}`,
    note,
  })});
  log(`saved split ${result}: ${gray.name} + ${yellow.name} | ${data.path}`);
  await loadNotes();
}
const yandexTargets = [
  {
    key: 'route_boot',
    name: 'Маршрут старт',
    yandex: 'route active + current street + speed limit',
    cluster: '0x48 nav on + 0x4A text + 0x44 speed',
    status: 'ready',
    why: 'обязательный вход в режим навигации',
  },
  {
    key: 'current_text',
    name: 'Текущая улица',
    yandex: 'current_street/currentRoadName',
    cluster: '0x4A nav text',
    status: 'ready',
    why: 'можно слать отдельно от манёвра',
  },
  {
    key: 'main_maneuver',
    name: 'Жёлтый маршрут',
    yandex: 'imageId/context_ra_* + distance',
    cluster: '0x45 exact yellow-only или exact combo',
    status: 'ready',
    why: 'если нет lane/road данных, шлём чистую жёлтую стрелку по маршруту',
  },
  {
    key: 'eta',
    name: 'Финиш: км + время',
    yandex: 'edistance/route_time/arrival_time',
    cluster: '0x47 ETA distance + 0x49 ETA time',
    status: 'ready',
    why: 'это нужно подключать к ACTION_ETA_DATA, не к манёврам',
  },
  {
    key: 'finish_text',
    name: 'Финиш текстом',
    yandex: 'finish_address/destination_name',
    cluster: '0x4A nav text when finish/info selected',
    status: 'app-ready',
    why: 'адрес/название можно вывести текстом, но это не отдельная финишная иконка',
  },
  {
    key: 'finish_marker',
    name: 'Финиш иконкой',
    yandex: 'finish_reached/context_ra_finish',
    cluster: 'отдельный exact 0x45 не найден',
    status: 'need-map',
    why: 'мы нашли ETA/дистанцию/текст, но не нашли отдельный кадр финиша как манёвр',
  },
  {
    key: 'speed_limit',
    name: 'Знак скорости',
    yandex: 'speed_limit + current_speed/exceeded',
    cluster: '0x44 speed limit',
    status: 'ready',
    why: 'единственный подтверждённый знак; камеры отдельно не найдены',
  },
  {
    key: 'lane_guidance',
    name: 'Серая дорога / полосы',
    yandex: 'lane_guidance / road split / lane source',
    cluster: '0x45 exact combo: yellow + gray in one frame',
    status: 'need-map',
    why: 'серая не отдельная накладка; нужен точный кадр под конкретный маршрут',
  },
  {
    key: 'gray_roundabout',
    name: 'Серый круг с ответвлениями',
    yandex: 'roundabout + exit_number',
    cluster: '0x45 roundabout exact family',
    status: 'need-map',
    why: 'обычные серые не смешиваются с кругом; круговая дорога отдельная семья',
  },
  {
    key: 'camera_signs',
    name: 'Камеры/предупреждения',
    yandex: 'camera/safety/warning objects',
    cluster: 'не найден отдельный UART-пакет',
    status: 'later',
    why: 'не блокирует основную навигацию; сначала маршрут/ETA/скорость/манёвр',
  },
];
const yandexOutputPlan = [
  {
    mode: 'Обычный поворот без подсказки полос',
    fromYandex: 'imageId/context_ra_* + distance',
    output: 'чистая жёлтая стрелка: 0x45 exact из карты жёлтых',
    status: 'готово',
    rule: 'серую не рисуем, потому что Yandex не дал схему полос/дороги',
  },
  {
    mode: 'Поворот + подсказка полос',
    fromYandex: 'lane_guidance=true, список полос и рекомендованная полоса',
    output: 'один exact 0x45 кадр: жёлтый маршрут + серая дорога/полосы',
    status: 'нужно добить карту',
    rule: 'выбираем combo по ключу routeDirection + lanesShape, а не подмешиваем b7 к стрелке',
  },
  {
    mode: 'Развилка / съезд',
    fromYandex: 'context_ra_exit_* / take_* / turn_* + lane source',
    output: 'exact combo, если такой кадр подписан; иначе жёлтая стрелка без серой',
    status: 'частично',
    rule: 'серую съезда можно включать только как сохранённую связку exact',
  },
  {
    mode: 'Круговое движение',
    fromYandex: 'context_ra_in/out_circular_movement + exit_number',
    output: 'семья b5=20 для жёлтого круга; серый круг ищем отдельно',
    status: 'частично',
    rule: 'круг не смешиваем с обычными b7 серыми дорогами',
  },
  {
    mode: 'Финиш маршрута',
    fromYandex: 'arrival_time, edistance, finish_address, finish_reached',
    output: '0x47/0x49 для км+времени, 0x4A для текста; отдельный 0x45 finish не найден',
    status: 'частично',
    rule: 'финиш показываем как ETA/текст, пока не найдём настоящий кадр финишной иконки',
  },
  {
    mode: 'TBT режим',
    fromYandex: 'отдельный TBT поток/режим',
    output: 'существующий TBT exact; без серых обычных дорог',
    status: 'готово отдельно',
    rule: 'TBT не участвует в combo-логике маршрута',
  },
];
const yandexReadyManeuvers = [
  {group:'Жёлтая стрелка', yandex:'context_ra_forward', label:'12:00 прямо', b5:'0D', b6:'00', b7:'00', b8:'00'},
  {group:'Жёлтая стрелка', yandex:'right angle 13:00', label:'13:00', b5:'0D', b6:'00', b7:'00', b8:'03'},
  {group:'Жёлтая стрелка', yandex:'right angle 13:30', label:'13:30', b5:'0D', b6:'00', b7:'00', b8:'06'},
  {group:'Жёлтая стрелка', yandex:'right angle 14:00', label:'14:00', b5:'0D', b6:'00', b7:'00', b8:'09'},
  {group:'Жёлтая стрелка', yandex:'right angle 15:00', label:'15:00', b5:'0D', b6:'00', b7:'00', b8:'0C'},
  {group:'Жёлтая стрелка', yandex:'right angle 16:00', label:'16:00', b5:'0D', b6:'00', b7:'00', b8:'0F'},
  {group:'Жёлтая стрелка', yandex:'right angle 16:30', label:'16:30', b5:'0D', b6:'00', b7:'00', b8:'12'},
  {group:'Жёлтая стрелка', yandex:'right angle 17:00', label:'17:00', b5:'0D', b6:'00', b7:'00', b8:'15'},
  {group:'Жёлтая стрелка', yandex:'left angle 19:00', label:'19:00', b5:'0D', b6:'00', b7:'00', b8:'1B'},
  {group:'Жёлтая стрелка', yandex:'left angle 19:30', label:'19:30', b5:'0D', b6:'00', b7:'00', b8:'1E'},
  {group:'Жёлтая стрелка', yandex:'left angle 20:00', label:'20:00', b5:'0D', b6:'00', b7:'00', b8:'21'},
  {group:'Жёлтая стрелка', yandex:'left angle 21:00', label:'21:00', b5:'0D', b6:'00', b7:'00', b8:'24'},
  {group:'Жёлтая стрелка', yandex:'left angle 22:00', label:'22:00', b5:'0D', b6:'00', b7:'00', b8:'27'},
  {group:'Жёлтая стрелка', yandex:'left angle 22:30', label:'22:30', b5:'0D', b6:'00', b7:'00', b8:'2A'},
  {group:'Жёлтая стрелка', yandex:'left angle 23:00', label:'23:00', b5:'0D', b6:'00', b7:'00', b8:'2D'},
  {group:'Жёлтый круг', yandex:'roundabout 12:00', label:'круг 12:00', b5:'20', b6:'00', b7:'00', b8:'00'},
  {group:'Жёлтый круг', yandex:'roundabout 13:00', label:'круг 13:00', b5:'20', b6:'00', b7:'00', b8:'03'},
  {group:'Жёлтый круг', yandex:'roundabout 13:30', label:'круг 13:30', b5:'20', b6:'00', b7:'00', b8:'06'},
  {group:'Жёлтый круг', yandex:'roundabout 14:30', label:'круг 14:30', b5:'20', b6:'00', b7:'00', b8:'09'},
  {group:'Жёлтый круг', yandex:'roundabout 15:00', label:'круг 15:00', b5:'20', b6:'00', b7:'00', b8:'0C'},
  {group:'Жёлтый круг', yandex:'roundabout 15:30', label:'круг 15:30', b5:'20', b6:'00', b7:'00', b8:'0F'},
  {group:'Жёлтый круг', yandex:'roundabout 16:00', label:'круг 16:00', b5:'20', b6:'00', b7:'00', b8:'12'},
  {group:'Жёлтый круг', yandex:'roundabout 17:00', label:'круг 17:00', b5:'20', b6:'00', b7:'00', b8:'15'},
  {group:'Жёлтый круг', yandex:'roundabout u-turn', label:'круг разворот', b5:'20', b6:'00', b7:'00', b8:'18'},
  {group:'Жёлтый круг', yandex:'roundabout 19:00', label:'круг 19:00', b5:'20', b6:'00', b7:'00', b8:'1B'},
  {group:'Жёлтый круг', yandex:'roundabout 19:30', label:'круг 19:30', b5:'20', b6:'00', b7:'00', b8:'1E'},
  {group:'Жёлтый круг', yandex:'roundabout 20:30', label:'круг 20:30', b5:'20', b6:'00', b7:'00', b8:'21'},
  {group:'Жёлтый круг', yandex:'roundabout 21:00', label:'круг 21:00', b5:'20', b6:'00', b7:'00', b8:'24'},
  {group:'Жёлтый круг', yandex:'roundabout 21:30', label:'круг 21:30', b5:'20', b6:'00', b7:'00', b8:'27'},
  {group:'Жёлтый круг', yandex:'roundabout 22:00', label:'круг 22:00', b5:'20', b6:'00', b7:'00', b8:'2A'},
  {group:'Жёлтый круг', yandex:'roundabout 23:00', label:'круг 23:00', b5:'20', b6:'00', b7:'00', b8:'2D'},
  {group:'TBT отдельно', yandex:'tbt forward', label:'TBT прямо', b5:'41', b6:'00', b7:'00', b8:'00'},
  {group:'TBT отдельно', yandex:'tbt right', label:'TBT направо', b5:'43', b6:'00', b7:'00', b8:'00'},
  {group:'TBT отдельно', yandex:'tbt left', label:'TBT налево', b5:'46', b6:'00', b7:'00', b8:'00'},
];
const yandexSimpleRows = [
  {kind:'system', yandex:'route.active', label:'Маршрут включён', cluster:'0x48 + 0x4A + 0x44', status:'готово', payload:{action:'route_start'}},
  {kind:'system', yandex:'current_street', label:'Текущая улица', cluster:'0x4A text', status:'готово', payload:{action:'text', text:'$street'}},
  {kind:'system', yandex:'speed_limit', label:'Знак скорости', cluster:'0x44 speed', status:'готово', payload:{action:'speed_quick'}},
  {kind:'system', yandex:'route.eta + distance', label:'До финиша: км + время', cluster:'0x47 + 0x49', status:'готово', payload:{action:'eta_demo'}},
  {kind:'system', yandex:'finish_address/name', label:'Финиш текстом', cluster:'0x4A text', status:'готово текстом', payload:{action:'text', text:'$finish'}},

  {kind:'maneuver', yandex:'context_ra_forward', label:'Прямо', gray:'дорога прямо', status:'готово', b5:'0D', b6:'00', b7:'01', b8:'00'},
  {kind:'maneuver', yandex:'context_ra_turn_right', label:'Направо', gray:'прямо + направо', status:'проверить combo', b5:'0D', b6:'00', b7:'03', b8:'0C'},
  {kind:'maneuver', yandex:'context_ra_turn_left', label:'Налево', gray:'прямо + налево', status:'проверить combo', b5:'0D', b6:'01', b7:'01', b8:'24'},
  {kind:'maneuver', yandex:'context_ra_hard_turn_right', label:'Круто направо', gray:'только круто направо', status:'проверить combo', b5:'0D', b6:'00', b7:'10', b8:'15'},
  {kind:'maneuver', yandex:'context_ra_hard_turn_left', label:'Круто налево', gray:'только круто налево', status:'проверить combo', b5:'0D', b6:'04', b7:'00', b8:'1B'},
  {kind:'maneuver', yandex:'context_ra_exit_right', label:'Съезд направо', gray:'прямо + съезд направо', status:'проверить combo', b5:'1F', b6:'00', b7:'03', b8:'0C'},
  {kind:'maneuver', yandex:'context_ra_exit_left', label:'Съезд налево', gray:'прямо + съезд налево', status:'проверить combo', b5:'1F', b6:'01', b7:'01', b8:'24'},
  {kind:'maneuver', yandex:'context_ra_turn_back', label:'Разворот', gray:'разворот без серой', status:'проверить', b5:'0D', b6:'00', b7:'00', b8:'18'},

  {kind:'roundabout', yandex:'roundabout_exit=1', label:'Круг: 1 съезд', gray:'серый круг не найден', status:'жёлтый готов, серый круг искать', b5:'20', b6:'00', b7:'00', b8:'03'},
  {kind:'roundabout', yandex:'roundabout_exit=2', label:'Круг: 2 съезд', gray:'серый круг не найден', status:'жёлтый готов, серый круг искать', b5:'20', b6:'00', b7:'00', b8:'0C'},
  {kind:'roundabout', yandex:'roundabout_exit=3', label:'Круг: 3 съезд', gray:'серый круг не найден', status:'жёлтый готов, серый круг искать', b5:'20', b6:'00', b7:'00', b8:'18'},
  {kind:'roundabout', yandex:'roundabout_exit=4', label:'Круг: 4 съезд', gray:'серый круг не найден', status:'жёлтый готов, серый круг искать', b5:'20', b6:'00', b7:'00', b8:'24'},
];
const finishIconSearchRows = [
  {kind:'finish-icon', yandex:'context_ra_finish', label:'Финиш иконкой classic', status:'проверить', b5:'0D', b6:'00', b7:'00', b8:'02', distance:'0', progress:'9'},
  {kind:'finish-icon', yandex:'context_ra_finish + gray straight', label:'Финиш иконкой + серая прямо', status:'проверить', b5:'0D', b6:'00', b7:'01', b8:'02', distance:'0', progress:'9'},
  {kind:'finish-icon', yandex:'context_ra_finish TBT', label:'Финиш иконкой TBT', status:'проверить отдельно', b5:'70', b6:'00', b7:'00', b8:'00', distance:'0', progress:'9'},
];
const roundaboutExitB8 = [
  {exit:1, b8:'03'},
  {exit:2, b8:'0C'},
  {exit:3, b8:'18'},
  {exit:4, b8:'24'},
];
const roundaboutGrayShapes = [
  {name:'серый круг пустой', b6:'00', b7:'00'},
  {name:'дорога к съезду', b6:'00', b7:'01'},
  {name:'круг + правая ветка', b6:'00', b7:'03'},
  {name:'круг + две ветки', b6:'00', b7:'07'},
  {name:'круг + 4 съезда', b6:'00', b7:'0F'},
  {name:'круг + левая ветка', b6:'01', b7:'01'},
];
const roundaboutGraySearchRows = roundaboutExitB8.flatMap(exit =>
  roundaboutGrayShapes.map(shape => ({
    kind:'roundabout-gray',
    yandex:`roundabout_exit=${exit.exit} + gray road`,
    label:`Круг ${exit.exit} съезд: ${shape.name}`,
    status:'искать',
    b5:'20',
    b6:shape.b6,
    b7:shape.b7,
    b8:exit.b8,
  }))
);
const signIconSearchRows = [
  {kind:'sign', yandex:'speed_limit', label:'Знак скорости', status:'готово', cluster:'0x44 speed', payload:{action:'speed_quick'}},
  {kind:'sign', yandex:'camera / warning icon', label:'Камера / предупреждение иконкой', status:'пакет не найден', cluster:'нет подтверждённой UART-команды', payload:null},
  {kind:'sign', yandex:'camera / warning text fallback', label:'Камера текстом', status:'fallback, не иконка', cluster:'0x4A text', payload:{action:'text', text:'Камера'}},
];
const yandexMissingRows = [
  ...finishIconSearchRows,
  ...signIconSearchRows,
];
const yandexFillConfirmed = new Set();
let yandexFillActiveKey = '';
const yandexFillGenericGrayShapes = [
  {key:'none', label:'без серой дороги', b6:'00', b7:'00'},
  {key:'straight', label:'дорога прямо', b6:'00', b7:'01'},
  {key:'right_soft', label:'прямо + вправо', b6:'00', b7:'03'},
  {key:'right_multi', label:'прямо + несколько вправо', b6:'00', b7:'07'},
  {key:'right_hard', label:'только круто вправо', b6:'00', b7:'10'},
  {key:'left_soft', label:'прямо + влево', b6:'01', b7:'01'},
  {key:'left_only', label:'только круто влево', b6:'04', b7:'00'},
];
function yandexFillRowKey(row) {
  return `${row.kind}:${row.yandex}`;
}
function yandexFillRows() {
  return yandexSimpleRows
    .map((row, index) => ({...row, simpleIndex:index, mapKey:yandexFillRowKey(row)}))
    .filter(row => row.kind === 'maneuver' || row.kind === 'roundabout');
}
function yandexFillPendingRows() {
  return yandexFillRows().filter(row => !yandexFillConfirmed.has(row.mapKey));
}
function yandexFillActiveRow() {
  const rows = yandexFillRows();
  return rows.find(row => row.mapKey === yandexFillActiveKey) || yandexFillPendingRows()[0] || null;
}
function uniqueYandexFillShapes(shapes) {
  const seen = new Set();
  const out = [];
  for (const shape of shapes) {
    const key = `${shape.b6}-${shape.b7}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(shape);
  }
  return out;
}
function yandexFillGrayShapesFor(row) {
  if (!row) return [];
  if (row.kind === 'roundabout') {
    return roundaboutGrayShapes.map(shape => ({
      key: `round_${shape.b6}_${shape.b7}`,
      label: shape.name,
      b6: shape.b6,
      b7: shape.b7,
    }));
  }
  return uniqueYandexFillShapes([
    {key:'default', label:`из базы: ${row.gray || 'как сейчас'}`, b6:row.b6, b7:row.b7},
    ...yandexFillGenericGrayShapes,
  ]);
}
function yandexFillSelectedShape(row) {
  const shapes = yandexFillGrayShapesFor(row);
  if (!shapes.length) return null;
  const selected = $('yandexFillGray')?.value || '';
  return shapes.find(shape => shape.key === selected) || shapes[0];
}
function yandexFillYellowPayload(row) {
  if (!row) return null;
  return withRoute({
    action: 'lane_combo',
    b5: `0x${row.b5}`,
    b6: '0x00',
    b7: '0x00',
    b8: `0x${row.b8}`,
    label: `${row.label} yellow`,
  });
}
function yandexFillComboPayload(row, shape) {
  if (!row || !shape) return null;
  return withRoute({
    action: 'lane_combo',
    b5: `0x${row.b5}`,
    b6: `0x${shape.b6}`,
    b7: `0x${shape.b7}`,
    b8: `0x${row.b8}`,
    label: `${row.label} + ${shape.label}`,
  });
}
function renderYandexFillDetail() {
  const row = yandexFillActiveRow();
  const activeRoot = $('yandexFillActive');
  const graySelect = $('yandexFillGray');
  const txRoot = $('yandexFillTx');
  if (!activeRoot || !graySelect || !txRoot) return;
  if (!row) {
    activeRoot.textContent = 'все пункты Yandex map подтверждены';
    graySelect.innerHTML = '';
    txRoot.textContent = '-';
    return;
  }
  const previousGray = graySelect.value;
  const shapes = yandexFillGrayShapesFor(row);
  graySelect.innerHTML = '';
  shapes.forEach(shape => {
    const option = document.createElement('option');
    option.value = shape.key;
    option.textContent = `${shape.label}  b6=${shape.b6} b7=${shape.b7}`;
    graySelect.appendChild(option);
  });
  graySelect.value = shapes.some(shape => shape.key === previousGray) ? previousGray : (shapes[0]?.key || '');
  const shape = yandexFillSelectedShape(row);
  const yellowPayload = yandexFillYellowPayload(row);
  const comboPayload = yandexFillComboPayload(row, shape);
  const mode = row.kind === 'roundabout' ? 'круговое' : 'манёвр';
  activeRoot.innerHTML =
    `<b>${escapeHtml(row.label)}</b><br>` +
    `<span class="note">${escapeHtml(row.yandex)}</span><br>` +
    `<span class="found">${escapeHtml(mode)}</span>`;
  txRoot.innerHTML =
    `<b>Жёлтый:</b> <code>${escapeHtml(byteLine(yellowPayload))}</code><br>` +
    `<b>С серой:</b> <code>${escapeHtml(byteLine(comboPayload))}</code>`;
}
function renderYandexFillMap() {
  const select = $('yandexFillSelect');
  const counts = $('yandexFillCounts');
  if (!select || !counts) return;
  const rows = yandexFillRows();
  const pending = yandexFillPendingRows();
  const selectedStillPending = pending.some(row => row.mapKey === yandexFillActiveKey);
  select.innerHTML = '';
  if (!selectedStillPending) yandexFillActiveKey = pending[0]?.mapKey || '';
  if (!pending.length) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = 'всё подтверждено';
    select.appendChild(option);
    select.disabled = true;
  } else {
    select.disabled = false;
    pending.forEach(row => {
      const option = document.createElement('option');
      option.value = row.mapKey;
      option.textContent = `${row.yandex} — ${row.label}`;
      select.appendChild(option);
    });
    select.value = yandexFillActiveKey;
  }
  counts.textContent = `осталось ${pending.length} из ${rows.length}; OK скрывает пункт из списка`;
  renderYandexFillDetail();
}
async function sendYandexFillYellow() {
  const row = yandexFillActiveRow();
  if (!row) return log('yandex map: все пункты уже подтверждены');
  await sendPayload(yandexFillYellowPayload(row), `yandex yellow ${row.yandex} ${row.label}`);
}
async function sendYandexFillCombo() {
  const row = yandexFillActiveRow();
  const shape = yandexFillSelectedShape(row);
  if (!row || !shape) return log('yandex map: нечего отправлять');
  await sendPayload(yandexFillComboPayload(row, shape), `yandex combo ${row.yandex} ${row.label} + ${shape.label}`);
}
async function saveYandexFill(result) {
  const row = yandexFillActiveRow();
  const shape = yandexFillSelectedShape(row);
  if (!row || !shape) return;
  const yellowPayload = yandexFillYellowPayload(row);
  const comboPayload = yandexFillComboPayload(row, shape);
  const observation = ($('yandexFillNote')?.value || '').trim();
  const rows = yandexFillRows();
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'yandex-fill-map',
    index: rows.findIndex(item => item.mapKey === row.mapKey),
    total: rows.length,
    case: {
      id: `yandex-fill-${row.mapKey}`,
      title: row.label,
      group: row.kind,
      map_key: row.mapKey,
      yandex_key: row.yandex,
      gray: shape.label,
      tx_text: byteLine(comboPayload),
      yellow_tx_text: byteLine(yellowPayload),
      payload: comboPayload,
      yellow_payload: yellowPayload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `${row.yandex}: ${row.label} + ${shape.label}: ${observation || result}`,
    note: observation || $('note').value,
    photo: notePhoto(),
  })});
  log(`saved yandex fill ${row.label}: ${result} | ${data.path}`);
  if (result === 'ok') {
    yandexFillConfirmed.add(row.mapKey);
    yandexFillActiveKey = '';
    if ($('yandexFillNote')) $('yandexFillNote').value = '';
    renderYandexFillMap();
  }
}
function yandexFillSavedKey(noteItem) {
  const c = noteItem.case || {};
  if (c.map_key) return c.map_key;
  if (!c.yandex_key) return '';
  if (c.group === 'maneuver' || c.group === 'roundabout') return `${c.group}:${c.yandex_key}`;
  const row = yandexFillRows().find(item => item.yandex === c.yandex_key);
  return row ? row.mapKey : '';
}
function applyYandexFillSaved(notes) {
  yandexFillConfirmed.clear();
  for (const noteItem of notes || []) {
    if (noteItem.result !== 'ok') continue;
    if (noteItem.queue !== 'yandex-fill-map' && noteItem.queue !== 'yandex-simple-map') continue;
    const key = yandexFillSavedKey(noteItem);
    if (key) yandexFillConfirmed.add(key);
  }
}
async function loadYandexFillSaved() {
  const data = await api('/api/notes');
  applyYandexFillSaved(data.notes || []);
  renderYandexFillMap();
}
function handleYandexFillSelect() {
  yandexFillActiveKey = $('yandexFillSelect')?.value || '';
  if ($('yandexFillNote')) $('yandexFillNote').value = '';
  renderYandexFillDetail();
  sendYandexFillYellow().catch(e => log(`yandex yellow error: ${e.message}`));
}
function yandexTargetPayload(key) {
  const street = $('street')?.value || $('checkStreet')?.value || 'Текущая улица';
  const speed = $('speedLimit')?.value || $('checkSpeed')?.value || '60';
  const distance = $('finishKm')?.value || '1.0';
  const eta = $('finishEta')?.value || '18:30';
  if (key === 'route_boot') return {action:'route_start', text: street, speed};
  if (key === 'current_text') return {action:'text', text: street};
  if (key === 'main_maneuver') return maneuverPayload();
  if (key === 'eta') return {action:'eta_demo', distance, eta};
  if (key === 'finish_text') return {action:'text', text: `Финиш ${eta} ${distance} км`};
  if (key === 'speed_limit') return {action:'speed_quick', speed};
  return null;
}
function yandexSimplePayload(row) {
  const street = $('street')?.value || 'Текущая улица';
  const speed = $('speedLimit')?.value || $('checkSpeed')?.value || '60';
  const distance = $('finishKm')?.value || '1.0';
  const eta = $('finishEta')?.value || '18:30';
  if (row.payload) {
    if (row.payload.action === 'route_start') return {action:'route_start', text: street, speed};
    if (row.payload.action === 'text') {
      const text = row.payload.text === '$finish'
        ? `Финиш ${eta} ${distance} км`
        : (row.payload.text === '$street' ? street : (row.payload.text || street));
      return {action:'text', text};
    }
    if (row.payload.action === 'speed_quick') return {action:'speed_quick', speed};
    if (row.payload.action === 'eta_demo') return {action:'eta_demo', distance, eta};
  }
  if (!row.b5) return null;
  const payload = withRoute({
    action: 'lane_combo',
    b5: `0x${row.b5}`,
    b6: `0x${row.b6}`,
    b7: `0x${row.b7}`,
    b8: `0x${row.b8}`,
    label: row.label,
  });
  if (row.distance != null) payload.distance = String(row.distance);
  if (row.progress != null) payload.progress = String(row.progress);
  return payload;
}
function yandexSimpleTx(row) {
  const payload = yandexSimplePayload(row);
  if (!payload) return row.cluster || 'не найдено';
  if (payload.action !== 'lane_combo') return row.cluster || payload.action;
  return byteLine(payload);
}
function selectedRoundaboutExit() {
  const value = $('roundaboutYellowFixed')?.value || '2';
  return roundaboutExitB8.find(item => String(item.exit) === String(value)) || roundaboutExitB8[1];
}
function setupRoundaboutGrayControls() {
  const select = $('roundaboutYellowFixed');
  if (!select) return;
  const selected = select.value || '2';
  select.innerHTML = '';
  roundaboutExitB8.forEach(item => {
    const option = document.createElement('option');
    option.value = String(item.exit);
    option.textContent = `Круг: ${item.exit} съезд`;
    select.appendChild(option);
  });
  select.value = [...select.options].some(option => option.value === selected) ? selected : '2';
}
function roundaboutGrayRow(shape) {
  const exit = selectedRoundaboutExit();
  return {
    kind: 'roundabout-gray',
    yandex: `roundabout_exit=${exit.exit} + gray road`,
    label: `Круг ${exit.exit} съезд: ${shape.name}`,
    status: 'искать',
    b5: '20',
    b6: shape.b6,
    b7: shape.b7,
    b8: exit.b8,
    shape: shape.name,
  };
}
function roundaboutGrayRowsForUi() {
  return roundaboutGrayShapes.map(roundaboutGrayRow);
}
async function sendRoundaboutGray(index) {
  const row = roundaboutGrayRowsForUi()[index];
  const payload = yandexSimplePayload(row);
  await sendPayload(payload, `roundabout gray ${row.label}`);
}
async function saveRoundaboutGray(index, result) {
  const row = roundaboutGrayRowsForUi()[index];
  if (!row) return;
  const observation = ($('roundaboutGrayNote')?.value || '').trim();
  const payload = yandexSimplePayload(row);
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'roundabout-gray-picker',
    index,
    total: roundaboutGrayRowsForUi().length,
    case: {
      id: `roundabout-gray-${row.yandex}-${row.b6}-${row.b7}-${row.b8}`,
      title: row.label,
      group: row.kind,
      yandex_key: row.yandex,
      shape: row.shape,
      status: row.status,
      tx_text: yandexSimpleTx(row),
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `${row.label}: ${observation || result}`,
    note: observation || $('note').value,
    photo: notePhoto(),
  })});
  log(`saved roundabout gray ${row.label}: ${result} | ${data.path}`);
}
function renderRoundaboutGrayPicker() {
  const buttonsRoot = $('roundaboutGrayButtons');
  const tableRoot = $('roundaboutGrayTable');
  const summaryRoot = $('roundaboutGraySummary');
  if (!buttonsRoot || !tableRoot || !summaryRoot) return;
  setupRoundaboutGrayControls();
  const exit = selectedRoundaboutExit();
  const rows = roundaboutGrayRowsForUi();
  buttonsRoot.innerHTML = '';
  summaryRoot.textContent =
    `фиксирован жёлтый круг: ${exit.exit} съезд, b5=20 b8=${exit.b8}. Ниже меняем только серую форму b6/b7.`;
  rows.forEach((row, index) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'warn';
    btn.textContent = row.shape;
    btn.title = yandexSimpleTx(row);
    btn.onclick = () => sendRoundaboutGray(index).catch(e => log(`roundabout gray error: ${e.message}`));
    buttonsRoot.appendChild(btn);
  });
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Жёлтый фикс</th><th>Серая форма</th><th>TX</th><th>Фиксация</th></tr></thead>';
  const body = document.createElement('tbody');
  rows.forEach((row, index) => {
    const tr = document.createElement('tr');
    tr.innerHTML =
      `<td><b>Круг ${exit.exit} съезд</b><br><span class="note">b5=20 b8=${exit.b8}</span></td>` +
      `<td>${escapeHtml(row.shape)}</td>` +
      `<td><code>${escapeHtml(yandexSimpleTx(row))}</code></td>` +
      `<td><div class="buttons" style="grid-template-columns:repeat(3,1fr);margin-top:0"><button class="mini good" onclick="saveRoundaboutGray(${index}, 'ok')">OK</button><button class="mini warn" onclick="saveRoundaboutGray(${index}, 'bad')">Не то</button><button class="mini danger" onclick="saveRoundaboutGray(${index}, 'no_rx')">Нет</button></div></td>`;
    tr.onclick = event => {
      if (event.target.closest('button')) return;
      sendRoundaboutGray(index).catch(e => log(`roundabout gray row error: ${e.message}`));
    };
    body.appendChild(tr);
  });
  table.appendChild(body);
  tableRoot.innerHTML = '';
  tableRoot.appendChild(table);
}
async function sendYandexSimple(index) {
  const row = yandexSimpleRows[index];
  const payload = yandexSimplePayload(row);
  if (!payload) return log(`Yandex map ${row.label}: нет команды`);
  await sendPayload(payload, `yandex map ${row.yandex} ${row.label}`);
}
async function sendYandexMissing(index) {
  const row = yandexMissingRows[index];
  const payload = yandexSimplePayload(row);
  if (!payload) return log(`Yandex search ${row.label}: пакет ещё не найден`);
  await sendPayload(payload, `yandex search ${row.yandex} ${row.label}`);
}
async function saveYandexSimple(index, result) {
  const row = yandexSimpleRows[index];
  if (!row) return;
  const noteEl = $(`yandexSimpleNote_${index}`);
  const observation = (noteEl?.value || '').trim();
  const payload = yandexSimplePayload(row);
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'yandex-simple-map',
    index,
    total: yandexSimpleRows.length,
    case: {
      id: `yandex-${row.yandex}`,
      title: row.label,
      group: row.kind,
      yandex_key: row.yandex,
      gray: row.gray || '',
      status: row.status,
      tx_text: yandexSimpleTx(row),
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `${row.label}: ${observation || result}`,
    note: observation || $('note').value,
    photo: notePhoto(),
  })});
  log(`saved yandex map ${row.label}: ${result} | ${data.path}`);
}
async function saveYandexMissing(index, result) {
  const row = yandexMissingRows[index];
  if (!row) return;
  const noteEl = $(`yandexMissingNote_${index}`);
  const observation = (noteEl?.value || '').trim();
  const payload = yandexSimplePayload(row);
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'yandex-missing-map',
    index,
    total: yandexMissingRows.length,
    case: {
      id: `yandex-missing-${row.yandex}`,
      title: row.label,
      group: row.kind,
      yandex_key: row.yandex,
      status: row.status,
      tx_text: yandexSimpleTx(row),
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `${row.label}: ${observation || result}`,
    note: observation || $('note').value,
    photo: notePhoto(),
  })});
  log(`saved yandex missing ${row.label}: ${result} | ${data.path}`);
}
function renderYandexSimpleMap() {
  const buttonsRoot = $('yandexSimpleButtons');
  const tableRoot = $('yandexSimpleTable');
  const summaryRoot = $('yandexSimpleSummary');
  if (!buttonsRoot || !tableRoot || !summaryRoot) return;
  buttonsRoot.innerHTML = '';
  const maneuverCount = yandexSimpleRows.filter(row => row.kind === 'maneuver').length;
  const roundCount = yandexSimpleRows.filter(row => row.kind === 'roundabout').length;
  summaryRoot.textContent = `заполнено: ${maneuverCount} манёвров, ${roundCount} круговых, ${yandexSimpleRows.filter(row => row.kind === 'system').length} системных строк. Без лишних 24-часовых вариантов.`;
  yandexSimpleRows.forEach((row, index) => {
    if (!yandexSimplePayload(row)) return;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = row.kind === 'roundabout' ? 'good' : (row.kind === 'system' ? 'primary' : 'yellow');
    btn.textContent = row.label;
    btn.title = `${row.yandex}: ${yandexSimpleTx(row)}`;
    btn.onclick = () => sendYandexSimple(index).catch(e => log(`yandex map error: ${e.message}`));
    buttonsRoot.appendChild(btn);
  });
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Yandex</th><th>Что выводим</th><th>Серая дорога</th><th>TX</th><th>Проверка</th></tr></thead>';
  const body = document.createElement('tbody');
  yandexSimpleRows.forEach((row, index) => {
    const tr = document.createElement('tr');
    const statusClass = row.status.includes('готов') ? 'found' : 'note';
    tr.innerHTML =
      `<td><b>${escapeHtml(row.yandex)}</b><br><span class="${statusClass}">${escapeHtml(row.status)}</span></td>` +
      `<td>${escapeHtml(row.label)}</td>` +
      `<td class="note">${escapeHtml(row.gray || '-')}</td>` +
      `<td><code>${escapeHtml(yandexSimpleTx(row))}</code></td>` +
      `<td><input id="yandexSimpleNote_${index}" class="row-note" placeholder="правильно / что не так"><div class="buttons" style="grid-template-columns:repeat(3,1fr);margin-top:6px"><button class="mini good" onclick="saveYandexSimple(${index}, 'ok')">OK</button><button class="mini warn" onclick="saveYandexSimple(${index}, 'bad')">Не то</button><button class="mini danger" onclick="saveYandexSimple(${index}, 'no_rx')">Нет</button></div></td>`;
    tr.onclick = event => {
      if (event.target.closest('input,button')) return;
      sendYandexSimple(index).catch(e => log(`yandex map row error: ${e.message}`));
    };
    body.appendChild(tr);
  });
  table.appendChild(body);
  tableRoot.innerHTML = '';
  tableRoot.appendChild(table);
}
function renderYandexMissingMap() {
  const buttonsRoot = $('yandexMissingButtons');
  const tableRoot = $('yandexMissingTable');
  const summaryRoot = $('yandexMissingSummary');
  if (!buttonsRoot || !tableRoot || !summaryRoot) return;
  buttonsRoot.innerHTML = '';
  const finishCount = yandexMissingRows.filter(row => row.kind === 'finish-icon').length;
  const roundCount = yandexMissingRows.filter(row => row.kind === 'roundabout-gray').length;
  const signCount = yandexMissingRows.filter(row => row.kind === 'sign').length;
  summaryRoot.textContent =
    `короткий поиск: финиш ${finishCount}, серый круг ${roundCount}, знаки ${signCount}. Кликаем сверху вниз и отмечаем OK/не то.`;
  yandexMissingRows.forEach((row, index) => {
    const payload = yandexSimplePayload(row);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = row.kind === 'finish-icon' ? 'good' : (payload ? 'warn' : 'danger');
    btn.textContent = row.label;
    btn.title = yandexSimpleTx(row);
    btn.onclick = () => sendYandexMissing(index).catch(e => log(`yandex missing error: ${e.message}`));
    buttonsRoot.appendChild(btn);
  });
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Что ищем</th><th>Yandex</th><th>TX / статус</th><th>Фиксация</th></tr></thead>';
  const body = document.createElement('tbody');
  yandexMissingRows.forEach((row, index) => {
    const tr = document.createElement('tr');
    tr.innerHTML =
      `<td><b>${escapeHtml(row.label)}</b><br><span class="missing">${escapeHtml(row.status)}</span></td>` +
      `<td class="note">${escapeHtml(row.yandex)}</td>` +
      `<td><code>${escapeHtml(yandexSimpleTx(row))}</code></td>` +
      `<td><input id="yandexMissingNote_${index}" class="row-note" placeholder="что показала приборка"><div class="buttons" style="grid-template-columns:repeat(3,1fr);margin-top:6px"><button class="mini good" onclick="saveYandexMissing(${index}, 'ok')">OK</button><button class="mini warn" onclick="saveYandexMissing(${index}, 'bad')">Не то</button><button class="mini danger" onclick="saveYandexMissing(${index}, 'no_rx')">Нет</button></div></td>`;
    tr.onclick = event => {
      if (event.target.closest('input,button')) return;
      sendYandexMissing(index).catch(e => log(`yandex missing row error: ${e.message}`));
    };
    body.appendChild(tr);
  });
  table.appendChild(body);
  tableRoot.innerHTML = '';
  tableRoot.appendChild(table);
}
function yandexReadyPayload(item) {
  return withRoute({
    action: 'lane_combo',
    b5: `0x${item.b5}`,
    b6: `0x${item.b6}`,
    b7: `0x${item.b7}`,
    b8: `0x${item.b8}`,
    label: item.label,
  });
}
async function sendYandexReady(index) {
  const item = yandexReadyManeuvers[index];
  if (!item) return;
  await sendPayload(yandexReadyPayload(item), `ready ${item.group} ${item.label}`);
}
function renderYandexReadyManeuvers() {
  const buttonsRoot = $('yandexReadyButtons');
  const tableRoot = $('yandexReadyTable');
  const summaryRoot = $('yandexReadySummary');
  if (!buttonsRoot || !tableRoot || !summaryRoot) return;
  buttonsRoot.innerHTML = '';
  const counts = yandexReadyManeuvers.reduce((acc, item) => {
    acc[item.group] = (acc[item.group] || 0) + 1;
    return acc;
  }, {});
  summaryRoot.textContent =
    `готово: ${counts['Жёлтая стрелка'] || 0} стрелок, ${counts['Жёлтый круг'] || 0} кругов, ${counts['TBT отдельно'] || 0} TBT. Серые дороги только через exact combo после проверки.`;
  yandexReadyManeuvers.forEach((item, index) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = item.group === 'Жёлтая стрелка' ? 'yellow' : (item.group === 'Жёлтый круг' ? 'good' : 'warn');
    btn.textContent = item.label;
    btn.title = `${item.group}: b5=${item.b5} b6=${item.b6} b7=${item.b7} b8=${item.b8}`;
    btn.onclick = () => sendYandexReady(index).catch(e => log(`ready maneuver error: ${e.message}`));
    buttonsRoot.appendChild(btn);
  });
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Группа</th><th>Yandex ключ</th><th>Что показала приборка</th><th>Exact TX</th></tr></thead>';
  const body = document.createElement('tbody');
  yandexReadyManeuvers.forEach((item, index) => {
    const tr = document.createElement('tr');
    const tx = `b5=${item.b5} b6=${item.b6} b7=${item.b7} b8=${item.b8}`;
    tr.innerHTML =
      `<td><b>${escapeHtml(item.group)}</b></td>` +
      `<td class="note">${escapeHtml(item.yandex)}</td>` +
      `<td class="found">${escapeHtml(item.label)}</td>` +
      `<td><code>${escapeHtml(tx)}</code></td>`;
    tr.onclick = () => sendYandexReady(index).catch(e => log(`ready row error: ${e.message}`));
    body.appendChild(tr);
  });
  table.appendChild(body);
  tableRoot.innerHTML = '';
  tableRoot.appendChild(table);
}
let yandexComboRenderedRows = [];
function yandexComboYellowRows() {
  return yandexReadyManeuvers.filter(item => item.group === 'Жёлтая стрелка');
}
function yandexComboGrayRows(scope) {
  const rows = allGrayRows();
  const pool = scope === 'all'
    ? uniqueRowsByTx([...rows.b7Full, ...rows.lanesB6])
    : uniqueRowsByTx([...rows.b7Main, ...rows.lanesB6]);
  if (scope !== 'signed') return pool;
  return grayInventoryRows().filter(item => inventoryState(item, 'gray').useful);
}
function setupYandexComboControls() {
  const route = $('yandexComboRoute');
  if (!route) return;
  const selected = route.value || '0';
  route.innerHTML = '';
  const all = document.createElement('option');
  all.value = 'all';
  all.textContent = 'Все направления';
  route.appendChild(all);
  yandexComboYellowRows().forEach((item, index) => {
    const option = document.createElement('option');
    option.value = String(index);
    option.textContent = item.label;
    route.appendChild(option);
  });
  route.value = [...route.options].some(option => option.value === selected) ? selected : '0';
}
function yandexComboPayload(row) {
  return withRoute({
    action: 'lane_combo',
    b5: row.yellow.b5,
    b6: actualHexByte(row.gray.payload.b6),
    b7: actualHexByte(row.gray.payload.b7),
    b8: row.yellow.b8,
    label: row.label,
  });
}
async function sendYandexCombo(index) {
  const row = yandexComboRenderedRows[index];
  if (!row) return;
  await sendPayload(yandexComboPayload(row), `yandex combo ${row.label}`);
}
function yandexComboRowsForUi() {
  const routeValue = $('yandexComboRoute')?.value || '0';
  const scope = $('yandexComboScope')?.value || 'signed';
  const yellowRows = routeValue === 'all'
    ? yandexComboYellowRows()
    : yandexComboYellowRows().filter((_, index) => String(index) === routeValue);
  const grayRows = yandexComboGrayRows(scope);
  const rows = [];
  for (const yellow of yellowRows) {
    for (const gray of grayRows) {
      const grayState = inventoryState(gray, 'gray');
      const grayLabel = grayState.label || gray.hint || gray.inventoryName || gray.code;
      rows.push({
        yellow,
        gray,
        grayLabel,
        label: `${yellow.label} + ${grayLabel}`,
      });
    }
  }
  return rows;
}
function renderYandexComboMap() {
  const buttonsRoot = $('yandexComboButtons');
  const tableRoot = $('yandexComboTable');
  const summaryRoot = $('yandexComboSummary');
  if (!buttonsRoot || !tableRoot || !summaryRoot) return;
  setupYandexComboControls();
  yandexComboRenderedRows = yandexComboRowsForUi();
  buttonsRoot.innerHTML = '';
  const routeLabel = $('yandexComboRoute').selectedOptions[0]?.textContent || '-';
  const scopeLabel = $('yandexComboScope').selectedOptions[0]?.textContent || '-';
  summaryRoot.textContent =
    `проверка combo: ${routeLabel}, ${scopeLabel}; строк ${yandexComboRenderedRows.length}. Это один exact 0x45 кадр: жёлтый маршрут + серая дорога.`;
  if (!yandexComboRenderedRows.length) {
    const empty = document.createElement('span');
    empty.className = 'missing';
    empty.textContent = 'нет подписанных серых; переключи на основную карту B7/B6 или подпиши серые ниже';
    buttonsRoot.appendChild(empty);
  }
  yandexComboRenderedRows.slice(0, 80).forEach((row, index) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'gray';
    btn.textContent = row.label;
    btn.title = byteLine(yandexComboPayload(row));
    btn.onclick = () => sendYandexCombo(index).catch(e => log(`yandex combo error: ${e.message}`));
    buttonsRoot.appendChild(btn);
  });
  if (yandexComboRenderedRows.length > 80) {
    const more = document.createElement('span');
    more.className = 'note';
    more.textContent = `кнопками показаны первые 80, остальные доступны кликом в таблице`;
    buttonsRoot.appendChild(more);
  }
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Yandex маршрут</th><th>Серая дорога/полосы</th><th>Exact TX</th><th>Проверка</th></tr></thead>';
  const body = document.createElement('tbody');
  if (!yandexComboRenderedRows.length) {
    const tr = document.createElement('tr');
    tr.innerHTML = '<td colspan="4" class="missing">нет combo строк</td>';
    body.appendChild(tr);
  }
  yandexComboRenderedRows.forEach((row, index) => {
    const payload = yandexComboPayload(row);
    const tr = document.createElement('tr');
    tr.innerHTML =
      `<td><b>${escapeHtml(row.yellow.label)}</b><br><span class="note">${escapeHtml(row.yellow.yandex)}</span></td>` +
      `<td class="note">${escapeHtml(row.grayLabel)}</td>` +
      `<td><code>${escapeHtml(byteLine(payload))}</code></td>` +
      `<td class="found">клик = отправить combo</td>`;
    tr.onclick = () => sendYandexCombo(index).catch(e => log(`yandex combo row error: ${e.message}`));
    body.appendChild(tr);
  });
  table.appendChild(body);
  tableRoot.innerHTML = '';
  tableRoot.appendChild(table);
}
function renderYandexOutputPlan() {
  const root = $('yandexOutputPlan');
  if (!root) return;
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Реальная ситуация</th><th>Что берём из Yandex</th><th>Что шлём на приборку</th><th>Правило</th></tr></thead>';
  const body = document.createElement('tbody');
  for (const item of yandexOutputPlan) {
    const statusClass = item.status.startsWith('готово') ? 'found' : (item.status.includes('нужно') ? 'missing' : 'note');
    const tr = document.createElement('tr');
    tr.innerHTML =
      `<td><b>${escapeHtml(item.mode)}</b><br><span class="${statusClass}">${escapeHtml(item.status)}</span></td>` +
      `<td class="note">${escapeHtml(item.fromYandex)}</td>` +
      `<td><code>${escapeHtml(item.output)}</code></td>` +
      `<td class="note">${escapeHtml(item.rule)}</td>`;
    body.appendChild(tr);
  }
  table.appendChild(body);
  root.innerHTML = '';
  root.appendChild(table);
}
async function sendYandexTarget(key) {
  const payload = yandexTargetPayload(key);
  if (!payload) {
    log(`Yandex target ${key}: нет рабочей команды, это надо искать`);
    return;
  }
  await sendPayload(payload, `yandex target ${key}`);
}
function renderYandexTargets() {
  const buttons = $('yandexTargetButtons');
  const tableRoot = $('yandexTargetTable');
  if (!buttons || !tableRoot) return;
  buttons.innerHTML = '';
  const ready = yandexTargets.filter(item => yandexTargetPayload(item.key));
  for (const item of ready) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = item.status === 'ready' ? 'good' : 'warn';
    btn.textContent = item.name;
    btn.title = `${item.cluster}: ${item.why}`;
    btn.onclick = () => sendYandexTarget(item.key).catch(e => log(`yandex target error: ${e.message}`));
    buttons.appendChild(btn);
  }
  const counts = yandexTargets.reduce((acc, item) => {
    acc[item.status] = (acc[item.status] || 0) + 1;
    return acc;
  }, {});
  $('yandexTargetSummary').textContent =
    `готово ${counts.ready || 0}, в приложении ${counts['app-ready'] || 0}, искать ${counts['need-map'] || 0}, потом ${counts.later || 0}`;
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Что из Yandex</th><th>Команда приборки</th><th>Статус</th><th>Зачем</th></tr></thead>';
  const body = document.createElement('tbody');
  for (const item of yandexTargets) {
    const tr = document.createElement('tr');
    const cls = item.status === 'ready' ? 'found' : (item.status === 'need-map' ? 'missing' : 'note');
    tr.innerHTML =
      `<td><b>${escapeHtml(item.name)}</b><br><span class="note">${escapeHtml(item.yandex)}</span></td>` +
      `<td><code>${escapeHtml(item.cluster)}</code></td>` +
      `<td class="${cls}">${escapeHtml(item.status)}</td>` +
      `<td class="note">${escapeHtml(item.why)}</td>`;
    if (yandexTargetPayload(item.key)) {
      tr.onclick = () => sendYandexTarget(item.key).catch(e => log(`yandex row error: ${e.message}`));
    }
    body.appendChild(tr);
  }
  table.appendChild(body);
  tableRoot.innerHTML = '';
  tableRoot.appendChild(table);
}
function updateYellowLogic() {
  $('yellowLogic').innerHTML =
    `<b>Статус:</b> обычные жёлтые стрелки закрыты как циферблат: 12:00 прямо, 13:00..17:00 вправо, 19:00..23:00 влево.<br>` +
    `<b>Правило:</b> слева выбираем и отправляем exact TX-команду. Справа пишем только то, что реально показала приборка.<br>` +
    `<b>Сохранение:</b> запись привязана к байтам b5/b6/b7/b8, поэтому подпись не съедет и не перепутает лево/право.<br>` +
    `<b>Важно:</b> в жёлтом табе b7=00, без серой прямой подложки. Серые дороги/полосы проверяем отдельно во втором табе.<br>` +
    `<b>Осталось:</b> круги ищем отдельно от KIA seed: b5=20, b6=00, b7=00, меняем b8. b6 с 0D не трогаем: он даёт серые ветки.`;
}
function classifyCandidates() {
  const group = $('classifyGroup').value;
  const range = $('classifyRange').value;
  if (group === 'yellow') {
    const base = [
      {id:'yellow_00', label:'00', hint:'серая прямо + жёлтая прямо', payload:{b5:'0D', b6:'00', b7:'01', b8:'00'}},
      {id:'yellow_03', label:'03', hint:'съезд вправо', payload:{b5:'0D', b6:'00', b7:'01', b8:'03'}},
      {id:'yellow_06', label:'06', hint:'съезд грубее', payload:{b5:'0D', b6:'00', b7:'01', b8:'06'}},
      {id:'yellow_09', label:'09', hint:'ещё правее', payload:{b5:'0D', b6:'00', b7:'01', b8:'09'}},
      {id:'yellow_0c', label:'0C', hint:'жёлтая вправо', payload:{b5:'0D', b6:'00', b7:'01', b8:'0C'}},
      {id:'yellow_0f', label:'0F', hint:'крутой вправо', payload:{b5:'0D', b6:'00', b7:'01', b8:'0F'}},
      {id:'yellow_classic_right', label:'R', hint:'classic направо', payload:{b5:'0D', b6:'00', b7:'00', b8:'0C'}},
      {id:'yellow_classic_left', label:'L', hint:'classic налево', payload:{b5:'0D', b6:'00', b7:'00', b8:'24'}},
    ];
    if (range === 'known') return base;
    const values = range === 'next' ? hexRange(0x12, 0x24) : hexRange(0x00, 0x24);
    return values.map(value => ({
      id: `yellow_b8_${value}`,
      label: value,
      hint: grayB8Hints[value] || 'b8 жёлтый кандидат',
      payload: {b5:'0D', b6:'00', b7:'01', b8:value},
    }));
  }
  const values = range === 'next' ? hexRange(0x12, 0x23) : (range === 'all' ? hexRange(0x01, 0x23) : hexRange(0x01, 0x11));
  return values.map(value => ({
    id: `gray_b7_${value}`,
    label: value,
    hint: grayShapeB7Hints[value] || grayLeftB7Details[value] || 'серый кандидат',
    payload: {b5:'0D', b6:'00', b7:value, b8:'01'},
  }));
}
function classifyPayload(item) {
  return withRoute({action:'lane_combo', ...item.payload, label:item.hint || item.label});
}
function selectedVisualLabel() {
  const custom = $('visualCustom').value.trim();
  if (custom) return custom;
  const selected = $('visualPreset').selectedOptions[0];
  return selected ? selected.textContent : '';
}
function pathSvg(d, color, width = 8) {
  return `<path d="${d}" fill="none" stroke="${color}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round"/>`;
}
function circleSvg(cx, cy, r, color) {
  return `<circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="${color}" stroke-width="7"/>`;
}
function visualIconSvg(kind) {
  const gray = '#8d98a5';
  const yellow = '#facc15';
  const dark = '#1b232d';
  const road = (d, color = gray, width = 8) => pathSvg(d, color, width);
  const arrow = (d, color = yellow) => pathSvg(d, color, 7);
  const heads = {
    up: `<path d="M48 8 L39 21 L57 21 Z" fill="${yellow}"/>`,
    right: `<path d="M86 18 L71 11 L73 29 Z" fill="${yellow}"/>`,
    left: `<path d="M14 18 L29 11 L27 29 Z" fill="${yellow}"/>`,
  };
  const iconMap = {
    unknown: `${road('M48 64 L48 12')}<text x="48" y="43" text-anchor="middle" fill="#d7e2ec" font-size="24">?</text>`,
    none: `<path d="M24 22 L72 58 M72 22 L24 58" stroke="#ef4444" stroke-width="8" stroke-linecap="round"/>`,
    other: `${road('M25 60 C35 38 62 42 72 18')}<text x="72" y="59" text-anchor="middle" fill="#d7e2ec" font-size="18">...</text>`,
    straight: road('M48 66 L48 10'),
    gray_straight: road('M48 66 L48 10'),
    yellow_straight: `${road('M48 66 L48 12', gray, 5)}${arrow('M48 66 L48 14')}${heads.up}`,
    right_exit_small: `${road('M42 66 L42 12')}${road('M42 42 C58 40 69 32 78 20')}`,
    right_exit_mid: `${road('M39 66 L39 12')}${road('M39 46 C58 44 74 35 86 20')}`,
    right_exit_big: `${road('M36 66 L36 12')}${road('M36 50 C62 48 80 36 91 16')}`,
    right_turn: road('M30 66 C30 38 47 20 78 18'),
    right_turn_sharp: road('M24 66 C24 28 70 46 82 12'),
    right_main_exit: `${road('M27 66 C41 42 61 26 79 12')}${road('M57 33 C73 34 84 43 91 58')}`,
    right_split: `${road('M44 66 L44 34')}${road('M44 34 C58 28 70 22 82 12')}${road('M44 34 C60 40 74 49 87 62')}`,
    right_two_exits: `${road('M32 66 L32 12')}${road('M32 50 C50 49 64 43 77 32')}${road('M32 39 C57 37 76 27 90 12')}`,
    right_three_exits: `${road('M28 66 L28 12')}${road('M28 53 C45 53 58 49 70 40')}${road('M28 42 C53 40 69 33 82 22')}${road('M28 31 C58 29 78 20 92 10')}`,
    straight_right_exit: `${road('M39 66 L39 12')}${road('M39 43 C58 40 72 31 84 18')}`,
    straight_two_right: `${road('M35 66 L35 12')}${road('M35 48 C54 47 68 41 80 31')}${road('M35 36 C58 34 76 25 89 13')}`,
    straight_three_right: `${road('M31 66 L31 12')}${road('M31 52 C48 52 61 48 72 40')}${road('M31 41 C54 39 70 32 82 22')}${road('M31 30 C57 28 77 20 91 10')}`,
    left_exit: `${road('M58 66 L58 12')}${road('M58 42 C42 40 31 32 22 20')}`,
    left_turn: road('M70 66 C70 38 53 20 22 18'),
    left_turn_sharp: road('M76 66 C76 28 30 46 18 12'),
    left_split: `${road('M56 66 L56 34')}${road('M56 34 C42 28 30 22 18 12')}${road('M56 34 C40 40 26 49 13 62')}`,
    rotary: `${circleSvg(50, 38, 19, gray)}${road('M50 66 L50 56')}${road('M50 20 L50 10')}${road('M31 38 L14 38')}${road('M69 38 L86 38')}`,
  };
  const yellowMap = {
    right_exit_small: `${road('M42 66 L42 12', gray, 5)}${arrow('M42 42 C58 40 69 32 78 20')}${heads.right}`,
    right_exit_mid: `${road('M39 66 L39 12', gray, 5)}${arrow('M39 46 C58 44 74 35 86 20')}${heads.right}`,
    right_turn: `${road('M30 66 C30 38 47 20 78 18', gray, 5)}${arrow('M30 66 C30 38 47 20 78 18')}${heads.right}`,
    right_turn_sharp: `${road('M24 66 C24 28 70 46 82 12', gray, 5)}${arrow('M24 66 C24 28 70 46 82 12')}${heads.right}`,
    left_turn: `${road('M70 66 C70 38 53 20 22 18', gray, 5)}${arrow('M70 66 C70 38 53 20 22 18')}${heads.left}`,
    left_turn_sharp: `${road('M76 66 C76 28 30 46 18 12', gray, 5)}${arrow('M76 66 C76 28 30 46 18 12')}${heads.left}`,
  };
  const group = $('classifyGroup') ? $('classifyGroup').value : 'gray';
  const body = group === 'yellow' && yellowMap[kind] ? yellowMap[kind] : (iconMap[kind] || iconMap.other);
  return `<svg viewBox="0 0 100 76" aria-hidden="true"><rect x="1" y="1" width="98" height="74" rx="8" fill="${dark}" stroke="#344252"/>${body}</svg>`;
}
function renderVisualPresets() {
  const selectedValue = $('visualPreset').value || (visualPresets[0] ? visualPresets[0][0] : '');
  $('visualPreset').innerHTML = '';
  $('visualPresetGrid').innerHTML = '';
  for (const [value, label] of visualPresets) {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    $('visualPreset').appendChild(option);
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'visual-card';
    if (selectedValue === value) card.classList.add('active');
    card.innerHTML = `${visualIconSvg(value)}<span>${escapeHtml(label)}</span>`;
    card.onclick = () => {
      $('visualPreset').value = value;
      $('visualCustom').value = '';
      renderVisualPresets();
      updateClassifyReadout();
    };
    $('visualPresetGrid').appendChild(card);
  }
  $('visualPreset').value = selectedValue;
}
function renderClassifier() {
  const root = $('classifyCandidates');
  const items = classifyCandidates();
  root.innerHTML = '';
  if (!items.some(item => classifyCurrent && item.id === classifyCurrent.id)) {
    classifyCurrent = items[0] || null;
  }
  for (const item of items) {
    const btn = document.createElement('button');
    if (classifyCurrent && item.id === classifyCurrent.id) btn.classList.add('active');
    const code = document.createElement('strong');
    code.textContent = item.label;
    const hint = document.createElement('span');
    hint.textContent = item.hint;
    btn.appendChild(code);
    btn.appendChild(hint);
    btn.title = `${item.label}: ${byteLine(item.payload)}`;
    btn.onclick = () => {
      classifyCurrent = item;
      renderClassifier();
      sendClassifyCurrent().catch(e => log(`classify send error: ${e.message}`));
    };
    root.appendChild(btn);
  }
  updateClassifyReadout();
}
function updateClassifyReadout() {
  if (!classifyCurrent) {
    $('classifyReadout').innerHTML = 'нет кандидатов';
    return;
  }
  const payload = classifyPayload(classifyCurrent);
  $('classifyReadout').innerHTML =
    `<b>Группа:</b> ${escapeHtml($('classifyGroup').selectedOptions[0]?.textContent || '')}<br>` +
    `<b>Кандидат:</b> ${escapeHtml(classifyCurrent.label)} - ${escapeHtml(classifyCurrent.hint)}<br>` +
    `<b>TX:</b> ${escapeHtml(byteLine(payload))}<br>` +
    `<b>Подпись:</b> ${escapeHtml(selectedVisualLabel() || '-')}`;
}
function maneuverPayload() {
  const m = currentManeuver();
  return {
    action: 'lane_combo',
    b5: m.b5,
    b6: m.b6,
    b7: m.b7,
    b8: m.b8,
    distance: $('distanceM').value || '80',
    progress: $('progress').value || '0',
    label: m.name,
  };
}
function summary() {
  const m = currentManeuver();
  $('summary').innerHTML =
    `<b>Улица:</b> ${escapeHtml($('street').value || '-')}<br>` +
    `<b>Манёвр:</b> ${escapeHtml(m.name)} | b5=${m.b5} b6=${m.b6} b7=${m.b7} b8=${m.b8}<br>` +
    `<b>До манёвра:</b> ${escapeHtml($('distanceM').value || '-')} м<br>` +
    `<b>Финиш:</b> ${escapeHtml($('finishEta').value || '-')} | ${escapeHtml($('finishKm').value || '-')} км`;
}
const yellowSaved = {};
const graySaved = {};
const yellowSavedByTx = {};
const graySavedByTx = {};
let comboSavedRows = [];
let grayCurrent = null;
const yellowClockLabels = ['12:00 прямо', '1:00', '2:00', '3:00', '4:00', '5:00', '6:00', '7:00', '8:00', '9:00', '10:00', '11:00', 'круг', 'круг 1 съезд', 'круг 2 съезд', 'круг 3 съезд', 'круг 4 съезд', 'круг 5 съезд', 'разворот налево', 'разворот направо', 'нет жёлтой', 'не круг', 'непонятно'];
const grayQuickLabels = ['прямо', '13:00', '13:30', '14:00', '15:00', '16:00', '19:00', '19:30', '20:00', 'нет серой', 'непонятно'];
const yellowB8StepActual = ['00', '03', '06', '09', '0C', '0F', '12', '15', '18', '1B', '1E', '21', '24', '27', '2A', '2D'];
const yellowClockByB8 = {
  '00': '12:00',
  '03': '13:00',
  '06': '13:30',
  '09': '14:00',
  '0C': '15:00',
  '0F': '16:00',
  '12': '16:30',
  '15': '17:00',
  '18': '18:00',
  '1B': '19:00',
  '1E': '19:30',
  '21': '20:00',
  '24': '21:00',
  '27': '22:00',
  '2A': '22:30',
  '2D': '23:00',
};
function prefixedHex(value) {
  return `0x${normalizeByte(value)}`;
}
function decLabel(hexValue) {
  return String(parseInt(hexValue, 16)).padStart(2, '0');
}
function yellowPayload(b5, b6, b7, b8) {
  return {b5: prefixedHex(b5), b6: prefixedHex(b6), b7: prefixedHex(b7), b8: prefixedHex(b8)};
}
function yellowRow(id, group, code, payload, detail = '') {
  return {
    id,
    group,
    code,
    hint: detail || 'ожидает подпись',
    payload,
  };
}
function uniqueRows(rows) {
  const seen = new Set();
  const out = [];
  for (const row of rows) {
    if (seen.has(row.id)) continue;
    seen.add(row.id);
    out.push(row);
  }
  return out;
}
function payloadTxKey(payload) {
  return [payload.b5, payload.b6, payload.b7, payload.b8].map(value => actualHexByte(value)).join('-');
}
function caseTxKey(c) {
  const tx = c && c.exact_tx;
  if (tx && tx.b5 != null && tx.b6 != null && tx.b7 != null && tx.b8 != null) {
    return [tx.b5, tx.b6, tx.b7, tx.b8].map(value => normalizeByte(value)).join('-');
  }
  if (c && c.payload) return payloadTxKey(c.payload);
  return '';
}
function uniqueRowsByTx(rows) {
  const seen = new Set();
  const out = [];
  for (const row of rows) {
    const key = payloadTxKey(row.payload);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(row);
  }
  return out;
}
function savedByExactTx(item, keyMap, txMap) {
  const key = payloadTxKey(item.payload);
  return txMap[key] || keyMap[item.id] || '';
}
function allYellowRows() {
  const b8Step = yellowB8StepActual.map(value => yellowRow(
    `yellow-b8-step-${value}`,
    'B8 actual шаг 03',
    `b8=0x${value}`,
    yellowPayload('0D', '00', '00', value),
    `actual 0x${value}, dec ${decLabel(value)}`
  ));
  const b8All = hexRange(0x00, 0x3F).map(value => yellowRow(
    `yellow-b8-all-${value}`,
    'B8 полный 00..3F',
    `b8=0x${value}`,
    yellowPayload('0D', '00', '00', value),
    `actual 0x${value}, dec ${decLabel(value)}`
  ));
  const classic = [
    yellowRow('classic-0D-00-00-00', 'Classic exact', '0D 00 00 00', yellowPayload('0D', '00', '00', '00'), 'classic candidate'),
    yellowRow('classic-0D-00-00-0C', 'Classic exact', '0D 00 00 0C', yellowPayload('0D', '00', '00', '0C'), 'classic candidate'),
    yellowRow('classic-0D-00-00-24', 'Classic exact', '0D 00 00 24', yellowPayload('0D', '00', '00', '24'), 'classic candidate'),
    yellowRow('classic-1F-00-00-0C', 'Classic exact', '1F 00 00 0C', yellowPayload('1F', '00', '00', '0C'), 'classic candidate'),
    yellowRow('classic-1F-00-00-24', 'Classic exact', '1F 00 00 24', yellowPayload('1F', '00', '00', '24'), 'classic candidate'),
  ];
  const tbt = hexRange(0x40, 0x4F).map(value => yellowRow(
    `tbt-b5-${value}`,
    'TBT b5 exact',
    `b5=0x${value}`,
    yellowPayload(value, '00', '00', '00'),
    `actual b5 0x${value}`
  ));
  const rotaryKiaIn = yellowRow(
      'rotary-kia-app-in',
      'Круг KIA app seed',
      '20 00 00 00',
      yellowPayload('20', '00', '00', '00'),
      'из нашего KIA app: context_ra_in_circular_movement, nav_tbt=false'
  );
  const rotaryKiaOut = yellowRow(
      'rotary-kia-app-out',
      'Круг KIA app seed',
      '20 00 00 06',
      yellowPayload('20', '00', '00', '06'),
      'из нашего KIA app: context_ra_out_circular_movement, nav_tbt=false'
  );
  const rotaryKiaTbt = [
    yellowRow(
      'rotary-kia-app-tbt-in',
      'Круг KIA app seed TBT',
      '60 00 00 00',
      yellowPayload('60', '00', '00', '00'),
      'из нашего KIA app при nav_tbt=true: вход/движение по кругу'
    ),
    yellowRow(
      'rotary-kia-app-tbt-out',
      'Круг KIA app seed TBT',
      '61 00 00 00',
      yellowPayload('61', '00', '00', '00'),
      'из нашего KIA app при nav_tbt=true: выход из круга'
    ),
  ];
  function rotaryKiaB8Row(value) {
    if (value === '00') return rotaryKiaIn;
    if (value === '06') return rotaryKiaOut;
    return yellowRow(
      `rotary-kia-b8-${value}`,
      'Круг KIA b5=20',
      `20 00 00 ${value}`,
      yellowPayload('20', '00', '00', value),
      `чистый круг: b5=20 b6=00 b7=00, проверяем b8=0x${value}`
    );
  }
  const rotaryKiaB8 = yellowB8StepActual.map(rotaryKiaB8Row);
  const rotaryKiaB8Full = hexRange(0x00, 0x3F).map(rotaryKiaB8Row);
  const rotaryCore = uniqueRows([...rotaryKiaB8, ...rotaryKiaTbt]);
  return {b8Step, b8All, classic, tbt, rotaryCore, rotaryKiaB8, rotaryKiaB8Full, rotaryKiaTbt};
}
function isUsefulYellowObservation(value) {
  const text = String(value || '').trim().toLowerCase();
  if (!text) return false;
  return !(
    text.includes('нечего') ||
    text.includes('непонят') ||
    text.includes('кружочек') ||
    text.includes('не круг') ||
    text.includes('загруз') ||
    text.includes('кита') ||
    text.includes('нет реакции')
  );
}
function yellowCandidates() {
  const series = $('yellowSeries').value;
  const rows = allYellowRows();
  const logical = [...rows.b8Step, ...rows.classic, ...rows.tbt, ...rows.rotaryKiaB8Full];
  const savedPool = uniqueRows([...logical, ...rows.rotaryCore, ...rows.rotaryKiaB8Full]);
  if (series === 'rotary_kia_b8') return unknownRows(rows.rotaryKiaB8, 'yellow');
  if (series === 'rotary_kia_b8_full') return unknownRows(rows.rotaryKiaB8Full, 'yellow');
  if (series === 'rotary_kia_tbt') return unknownRows(rows.rotaryKiaTbt, 'yellow');
  if (series === 'working') return savedPool.filter(item => isUsefulYellowObservation(savedByExactTx(item, yellowSaved, yellowSavedByTx)));
  if (series === 'saved') return savedPool.filter(item => savedByExactTx(item, yellowSaved, yellowSavedByTx));
  if (series === 'step3') return unknownRows(rows.b8Step, 'yellow');
  if (series === 'b8_all') return unknownRows(rows.b8All, 'yellow');
  if (series === 'classic') return unknownRows(rows.classic, 'yellow');
  if (series === 'tbt') return unknownRows(rows.tbt, 'yellow');
  return unknownRows(logical, 'yellow');
}
function setYellowObservation(value) {
  $('yellowVisual').value = value;
  updateGrayReadout();
}
function renderYellowClockButtons() {
  const root = $('yellowClockButtons');
  if (!root) return;
  root.innerHTML = '';
  for (const label of yellowClockLabels) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = label;
    btn.onclick = () => setYellowObservation(label);
    root.appendChild(btn);
  }
}
function setYellowCurrent(item) {
  yellowCurrent = item;
  $('grayB8').value = item.payload.b8;
  if ($('yellowVisual')) $('yellowVisual').value = savedByExactTx(item, yellowSaved, yellowSavedByTx) || '';
  updateGrayReadout();
}
function grayPayload(value) {
  if (yellowCurrent && value == null) {
    const payload = withRoute({action:'lane_combo', ...yellowCurrent.payload, label:yellowCurrent.hint});
    return payload;
  }
  const b8 = normalizeByte(value ?? $('grayB8').value);
  const hint = grayB8Hints[b8] || '';
  return {
    action: 'lane_combo',
    b5: '0D',
    b6: '00',
    b7: '00',
    b8,
    distance: $('distanceM').value || '80',
    progress: $('progress').value || '0',
    label: `Жёлтая без серой b8=${b8}${hint ? ' ' + hint : ''}`,
  };
}
function updateGrayReadout() {
  const payload = grayPayload();
  const hint = yellowCurrent ? yellowCurrent.hint : 'manual';
  const visual = $('yellowVisual') ? $('yellowVisual').value : '';
  $('yellowSelected').innerHTML = yellowCurrent
    ? `<b>${escapeHtml(yellowCurrent.code)}</b><br>${escapeHtml(yellowCurrent.group)}<br><code>${escapeHtml(byteLine(payload))}</code>`
    : `manual b8=${escapeHtml(payload.b8)}<br>${escapeHtml(byteLine(payload))}`;
  $('grayReadout').innerHTML =
    `<b>TX:</b> ${escapeHtml(byteLine(payload))}<br>` +
    `<b>Группа:</b> ${escapeHtml(yellowCurrent ? yellowCurrent.group : ($('yellowSeries').selectedOptions[0]?.textContent || '-'))}<br>` +
    `<b>Деталь:</b> ${escapeHtml(hint)}<br>` +
    `<b>Твоя подпись:</b> ${escapeHtml(visual || '-')}<br>` +
    `<b>До манёвра:</b> ${escapeHtml(payload.distance)} м | progress=${escapeHtml(payload.progress)}`;
}
function selectGrayB8(value) {
  $('grayB8').value = normalizeByte(value);
  yellowCurrent = null;
  updateGrayReadout();
}
function renderGrayButtons() {
  const root = $('grayMatrix');
  root.innerHTML = '';
  const items = yellowCandidates();
  if (!yellowCurrent || !items.some(item => item.id === yellowCurrent.id)) {
    yellowCurrent = items[0] || null;
  }
  if (yellowCurrent) {
    $('grayB8').value = yellowCurrent.payload.b8;
    if ($('yellowVisual')) $('yellowVisual').value = savedByExactTx(yellowCurrent, yellowSaved, yellowSavedByTx) || '';
  }
  const table = document.createElement('table');
  table.className = 'yellow-table';
  table.innerHTML = '<thead><tr><th>Команда</th><th>Exact TX</th><th>Твоя подпись</th></tr></thead>';
  const body = document.createElement('tbody');
  if (!items.length) {
    const emptyRow = document.createElement('tr');
    emptyRow.innerHTML = '<td colspan="3" class="saved">В этой группе неизвестных не осталось</td>';
    body.appendChild(emptyRow);
  }
  for (const item of items) {
    const row = document.createElement('tr');
    if (yellowCurrent && item.id === yellowCurrent.id) row.classList.add('active');
    const saved = savedByExactTx(item, yellowSaved, yellowSavedByTx) || '';
    row.innerHTML =
      `<td><b>${escapeHtml(item.code)}</b><br><span class="muted">${escapeHtml(item.group)}</span><br><span class="muted">${escapeHtml(item.hint)}</span></td>` +
      `<td><code>${escapeHtml(byteLine(item.payload))}</code></td>` +
      `<td class="saved">${escapeHtml(saved || '-')}</td>`;
    row.onclick = () => {
      setYellowCurrent(item);
      renderGrayButtons();
      sendGrayUnderlay().catch(e => log(`yellow send error: ${e.message}`));
    };
    body.appendChild(row);
  }
  table.appendChild(body);
  root.appendChild(table);
  updateGrayReadout();
}
function updateGrayRoadLogic() {
  $('grayRoadLogic').innerHTML =
    `<b>Статус:</b> серые дороги тоже закрываются как циферблат/маска: правый сектор сидит в B7, левый сектор в B6.<br>` +
    `<b>Правило:</b> серый таб меняет дорогу/полосы, жёлтой стрелки тут нет: фиксируем b8=01.<br>` +
    `<b>B7:</b> это маска серых веток, не простой счётчик: 01=прямо, 02=13:00, 04=13:30, 08=14:00, 10=15:00, 20=16:00. Комбо типа 09/0A/0B/10/11 тоже есть и показываются сверху как B7 XX.<br>` +
    `<b>B6:</b> в связке с b7=01 добавляет левый веер: 01=19:00, 02=19:30, 04=20:00.<br>` +
    `<b>Сохранение:</b> подпись привязана к exact b5/b6/b7/b8, как в жёлтом табе.`;
}
function grayRoadRow(id, group, code, payload, detail = '') {
  return {id, group, code, payload, hint: detail || 'ожидает подпись'};
}
function maskSummary(value, bits, emptyLabel) {
  const mask = parseInt(normalizeByte(value), 16);
  const labels = bits.filter(([bit]) => (mask & bit) === bit).map(([, label]) => label);
  return labels.length ? labels.join(' + ') : emptyLabel;
}
const grayB7Bits = [
  [0x01, 'прямо'],
  [0x02, '13:00'],
  [0x04, '13:30'],
  [0x08, '14:00'],
  [0x10, '15:00'],
  [0x20, '16:00'],
];
const grayB6Bits = [
  [0x01, '19:00'],
  [0x02, '19:30'],
  [0x04, '20:00'],
];
function grayB7Summary(value) {
  return maskSummary(value, grayB7Bits, 'нет серой');
}
function grayB6Summary(value) {
  const side = maskSummary(value, grayB6Bits, '');
  return side ? `прямо + ${side}` : 'прямо';
}
function allGrayRows() {
  const b7Main = hexRange(0x00, 0x23).map(value => grayRoadRow(
    `gray-b7-${value}`,
    'B7 маска серых дорог',
    `b7=0x${value}`,
    yellowPayload('0D', '00', value, '01'),
    `маска: ${grayB7Summary(value)}`
  ));
  const b7Full = hexRange(0x00, 0x3F).map(value => grayRoadRow(
    `gray-b7-full-${value}`,
    'B7 полный поиск маски',
    `b7=0x${value}`,
    yellowPayload('0D', '00', value, '01'),
    `маска: ${grayB7Summary(value)}`
  ));
  const lanesB6 = hexRange(0x00, 0x07).map(value => grayRoadRow(
    `gray-lanes-b6-${value}`,
    'B6 левый веер',
    `b6=0x${value}`,
    yellowPayload('0D', value, '01', '01'),
    `маска: ${grayB6Summary(value)}`
  ));
  return {b7Main, b7Full, lanesB6};
}
function isUsefulGrayObservation(value) {
  const text = String(value || '').trim().toLowerCase();
  if (!text) return false;
  return !(
    text.includes('непонят') ||
    text.includes('нет реакции') ||
    text.includes('ничего')
  );
}
function cleanInventoryLabel(value) {
  return String(value || '')
    .replace(/\s+/g, ' ')
    .replace(/пряом/gi, 'прямо')
    .replace(/на право/gi, 'направо')
    .trim();
}
function yellowInventoryName(row, group) {
  const b8 = actualHexByte(row.payload.b8);
  const b5 = actualHexByte(row.payload.b5);
  if (group === 'Стрелки') return `стрелка ${yellowClockByB8[b8] || `b8=0x${b8}`}`;
  if (group === 'Круги') return `круг ${yellowClockByB8[b8] || `b8=0x${b8}`}`;
  if (group === 'Круги TBT') return b5 === '61' ? 'круг TBT выход' : 'круг TBT вход';
  return `TBT b5=0x${b5}`;
}
function decorateInventoryRows(rows, group, nameFn) {
  return rows.map(row => ({
    ...row,
    inventoryGroup: group,
    inventoryName: nameFn(row, group),
  }));
}
function yellowInventoryRows() {
  const rows = allYellowRows();
  return uniqueRowsByTx([
    ...decorateInventoryRows(rows.b8Step, 'Стрелки', yellowInventoryName),
    ...decorateInventoryRows(rows.rotaryKiaB8, 'Круги', yellowInventoryName),
    ...decorateInventoryRows(rows.rotaryKiaTbt, 'Круги TBT', yellowInventoryName),
    ...decorateInventoryRows(rows.tbt, 'TBT стрелки', yellowInventoryName),
  ]);
}
function grayInventoryRows() {
  const rows = allGrayRows();
  return uniqueRowsByTx([
    ...decorateInventoryRows(rows.b7Full, 'B7 полный поиск', row => `B7 ${actualHexByte(row.payload.b7)}: ${grayB7Summary(actualHexByte(row.payload.b7))}`),
    ...decorateInventoryRows(rows.lanesB6, 'B6 левый веер', row => `B6 ${actualHexByte(row.payload.b6)}: ${grayB6Summary(actualHexByte(row.payload.b6))}`),
  ]);
}
function isInventoryYellowObservation(value) {
  const text = cleanInventoryLabel(value).toLowerCase();
  if (!isUsefulYellowObservation(text)) return false;
  const grayMention = (
    text.includes('серый') ||
    text.includes('серая') ||
    text.includes('серой')
  ) && !text.includes('без сер');
  return !(
    text.includes('пуст') ||
    grayMention ||
    text.includes('нечего') ||
    text.includes('нет жёл') ||
    text.includes('китай') ||
    text.includes('кита')
  );
}
function isInventoryGrayObservation(value) {
  const text = cleanInventoryLabel(value).toLowerCase();
  if (!isUsefulGrayObservation(text)) return false;
  return !text.includes('нет серой');
}
function isGrayCircleObservation(value) {
  const text = cleanInventoryLabel(value).toLowerCase();
  if (!isUsefulYellowObservation(text) && !isUsefulGrayObservation(text)) return false;
  return text.includes('круг') && (
    text.includes('серый') ||
    text.includes('серая') ||
    text.includes('серой')
  );
}
function inventoryState(item, kind) {
  const sourceKind = item.inventorySource || kind;
  const saved = savedByExactTx(
    item,
    sourceKind === 'yellow' ? yellowSaved : graySaved,
    sourceKind === 'yellow' ? yellowSavedByTx : graySavedByTx
  );
  let useful = false;
  if (item.inventoryFamily === 'gray-circle') {
    useful = isGrayCircleObservation(saved);
  } else {
    useful = kind === 'yellow' ? isInventoryYellowObservation(saved) : isInventoryGrayObservation(saved);
  }
  const label = useful ? cleanInventoryLabel(saved) : item.inventoryName;
  return {saved: cleanInventoryLabel(saved), useful, label};
}
function unknownRows(rows, kind) {
  return rows.filter(item => !cleanInventoryLabel(savedByExactTx(
    item,
    kind === 'yellow' ? yellowSaved : graySaved,
    kind === 'yellow' ? yellowSavedByTx : graySavedByTx
  )));
}
function inventoryButtonLabel(item, state, kind) {
  if (kind === 'gray') {
    const b6 = actualHexByte(item.payload.b6);
    const b7 = actualHexByte(item.payload.b7);
    const prefix = b6 !== '00' ? `B6 ${b6}` : `B7 ${b7}`;
    return `${prefix} · ${state.label}`;
  }
  if (kind === 'yellow' && item.inventoryGroup === 'TBT стрелки' && !state.label.toLowerCase().includes('tbt')) {
    return `TBT ${state.label}`;
  }
  return state.label;
}
function isNormalArrowForCombo(item) {
  return item && item.inventoryGroup === 'Стрелки';
}
function selectedInventoryText(item, kind) {
  if (!item) return '-';
  const state = inventoryState(item, kind);
  return `${state.label} | ${byteLine(item.payload)}`;
}
function combinedInventoryPayload(yellowItem, grayItem, label) {
  return withRoute({
    action: 'lane_combo',
    b5: actualHexByte(yellowItem.payload.b5),
    b6: actualHexByte(grayItem.payload.b6),
    b7: actualHexByte(grayItem.payload.b7),
    b8: actualHexByte(yellowItem.payload.b8),
    label,
  });
}
function updateInventoryActive(extra = '') {
  if (!$('inventoryActive')) return;
  const yellowState = inventorySelectedYellow ? inventoryState(inventorySelectedYellow, 'yellow') : null;
  const grayState = inventorySelectedGray ? inventoryState(inventorySelectedGray, 'gray') : null;
  const comboState = inventorySelectedYellow && inventorySelectedGray && isNormalArrowForCombo(inventorySelectedYellow)
    ? `<br><span class="muted">Проверка связки доступна отдельно. После сохранения она появится в блоке exact-связок.</span>`
    : '';
  const activeText = yellowState
    ? `<b>Выбран жёлтый:</b> ${escapeHtml(yellowState.label)}<br><code>${escapeHtml(byteLine(inventorySelectedYellow.payload))}</code><br><b>Выбрана серая:</b> ${escapeHtml(grayState ? grayState.label : '-')}<br>${grayState ? `<code>${escapeHtml(byteLine(inventorySelectedGray.payload))}</code>` : ''}<br><span class="muted">Рабочие кнопки отправляют exact. TBT и круги не смешиваются с обычными серыми.</span>${comboState}`
    : 'выбери жёлтую кнопку слева; серые справа работают отдельными командами';
  $('inventoryActive').innerHTML = `${activeText}${extra ? `<br>${extra}` : ''}`;
}
async function sendInventoryItem(kind, item, label) {
  let payload = withRoute({action:'lane_combo', ...item.payload, label});
  let logLabel = `${kind === 'yellow' ? 'yellow' : 'gray'} ${label} ${byteLine(payload)}`;
  lastInventorySend = {kind, item, payload, label};
  if (kind === 'yellow') {
    inventorySelectedYellow = item;
    renderInventory();
    updateInventoryActive(`<span class="found">Отправлен жёлтый exact. Серые не смешиваем, пока не найдём отдельную карту связок.</span>`);
  } else {
    inventorySelectedGray = item;
    renderInventory();
    updateInventoryActive(`<span class="found">Отправлена серая exact отдельно.</span><br><code>${escapeHtml(byteLine(payload))}</code>`);
  }
  await sendPayload(payload, logLabel);
}
async function sendInventoryComboSearch() {
  if (!inventorySelectedYellow || !inventorySelectedGray) {
    log('combo search: выбери жёлтую и серую');
    updateInventoryActive('<span class="missing">Для проверки связки выбери жёлтую и серую.</span>');
    return;
  }
  if (!isNormalArrowForCombo(inventorySelectedYellow)) {
    log('combo search: TBT/круг не смешиваем с обычной серой');
    updateInventoryActive('<span class="missing">TBT и круги не смешиваем с обычной серой. Для них нужна отдельная карта круговых/TBT связок.</span>');
    return;
  }
  const yellowState = inventoryState(inventorySelectedYellow, 'yellow');
  const grayState = inventoryState(inventorySelectedGray, 'gray');
  const label = `${yellowState.label} + ${grayState.label}`;
  const payload = combinedInventoryPayload(inventorySelectedYellow, inventorySelectedGray, label);
  lastInventorySend = {
    kind: 'combo',
    yellowItem: inventorySelectedYellow,
    grayItem: inventorySelectedGray,
    payload,
    label,
  };
  updateInventoryActive(`<span class="missing">Проверяем НЕ рабочую пока связку:</span> ${escapeHtml(label)}<br><code>${escapeHtml(byteLine(payload))}</code>`);
  await sendPayload(payload, `combo search ${label} ${byteLine(payload)}`);
}
function comboInventoryRows() {
  return comboSavedRows.filter(row => isUsefulGrayObservation(row.observation || row.label));
}
function renderComboInventory() {
  const buttonRoot = $('comboInventoryButtons');
  const tableRoot = $('comboInventoryTable');
  const countsRoot = $('comboInventoryCounts');
  if (!buttonRoot || !tableRoot || !countsRoot) return;
  const rows = comboInventoryRows();
  buttonRoot.innerHTML = '';
  countsRoot.textContent = rows.length ? `рабочих exact-связок ${rows.length}` : 'рабочих exact-связок пока нет';
  if (!rows.length) {
    const empty = document.createElement('span');
    empty.className = 'muted';
    empty.textContent = 'сначала проверь связку и сохрани результат';
    buttonRoot.appendChild(empty);
  }
  for (const row of rows) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'good';
    btn.textContent = row.label;
    btn.title = byteLine(row.payload);
    btn.onclick = () => {
      lastInventorySend = {kind:'combo', payload: row.payload, label: row.label, yellowItem: row.yellowItem || null, grayItem: row.grayItem || null};
      sendPayload(withRoute({...row.payload, label: row.label}), `combo exact ${row.label} ${byteLine(row.payload)}`).catch(e => log(`combo send error: ${e.message}`));
    };
    buttonRoot.appendChild(btn);
  }
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Связка</th><th>Exact TX</th><th>Подпись</th></tr></thead>';
  const body = document.createElement('tbody');
  if (!rows.length) {
    const tr = document.createElement('tr');
    tr.innerHTML = '<td colspan="3" class="missing">нет сохранённых exact-связок</td>';
    body.appendChild(tr);
  }
  for (const row of rows) {
    const tr = document.createElement('tr');
    tr.innerHTML =
      `<td>${escapeHtml(row.yellowCode || '-') }<br>${escapeHtml(row.grayCode || '-')}</td>` +
      `<td><code>${escapeHtml(byteLine(row.payload))}</code></td>` +
      `<td class="note">${escapeHtml(row.observation || row.label)}</td>`;
    tr.onclick = () => sendPayload(withRoute({...row.payload, label: row.label}), `combo exact ${row.label} ${byteLine(row.payload)}`).catch(e => log(`combo row send error: ${e.message}`));
    body.appendChild(tr);
  }
  table.appendChild(body);
  tableRoot.innerHTML = '';
  tableRoot.appendChild(table);
}
function renderInventorySide(kind, rows, buttonsId, tableId, countsId) {
  const buttonRoot = $(buttonsId);
  const tableRoot = $(tableId);
  const countsRoot = $(countsId);
  if (!buttonRoot || !tableRoot || !countsRoot) return {found: 0, total: rows.length};
  buttonRoot.innerHTML = '';
  const states = rows.map(item => ({item, state: inventoryState(item, kind)}));
  const found = states.filter(entry => entry.state.useful).length;
  countsRoot.textContent = `есть ${found} из ${rows.length}, дубли exact TX убраны`;
  const foundRows = states.filter(entry => entry.state.useful);
  if (!foundRows.length) {
    const empty = document.createElement('span');
    empty.className = 'muted';
    empty.textContent = 'пока нет рабочих кнопок';
    buttonRoot.appendChild(empty);
  }
  for (const {item, state} of foundRows) {
    const label = inventoryButtonLabel(item, state, kind);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = kind === 'yellow' ? 'yellow' : 'gray';
    if (kind === 'yellow' && inventorySelectedYellow && payloadTxKey(inventorySelectedYellow.payload) === payloadTxKey(item.payload)) {
      btn.classList.add('active');
    }
    if (kind === 'gray' && inventorySelectedGray && payloadTxKey(inventorySelectedGray.payload) === payloadTxKey(item.payload)) {
      btn.classList.add('active');
    }
    btn.textContent = label;
    btn.title = `${item.inventoryGroup}: ${byteLine(item.payload)}`;
    btn.onclick = () => sendInventoryItem(kind, item, label).catch(e => log(`inventory send error: ${e.message}`));
    buttonRoot.appendChild(btn);
  }
  const table = document.createElement('table');
  table.className = 'inventory-table';
  table.innerHTML = '<thead><tr><th>Группа</th><th>Название</th><th>Exact TX</th><th>Статус</th></tr></thead>';
  const body = document.createElement('tbody');
  for (const {item, state} of states) {
    const tr = document.createElement('tr');
    const statusClass = state.useful ? 'found' : 'missing';
    const statusText = state.useful ? 'есть' : 'нет подписи';
    tr.innerHTML =
      `<td>${escapeHtml(item.inventoryGroup)}</td>` +
      `<td><b>${escapeHtml(state.label)}</b><br><span class="note">${escapeHtml(state.saved || item.hint || '')}</span></td>` +
      `<td><code>${escapeHtml(byteLine(item.payload))}</code></td>` +
      `<td class="${statusClass}">${statusText}</td>`;
    tr.onclick = () => sendInventoryItem(kind, item, inventoryButtonLabel(item, state, kind)).catch(e => log(`inventory row send error: ${e.message}`));
    body.appendChild(tr);
  }
  table.appendChild(body);
  tableRoot.innerHTML = '';
  tableRoot.appendChild(table);
  return {found, total: rows.length};
}
function renderInventory() {
  const yellowRows = yellowInventoryRows();
  if (!inventorySelectedYellow || !yellowRows.some(item => payloadTxKey(item.payload) === payloadTxKey(inventorySelectedYellow.payload))) {
    const firstKnown = yellowRows.find(item => inventoryState(item, 'yellow').useful);
    inventorySelectedYellow = firstKnown || yellowRows[0] || null;
  }
  const yellow = renderInventorySide('yellow', yellowRows, 'yellowInventoryButtons', 'yellowInventoryTable', 'yellowInventoryCounts');
  const gray = renderInventorySide('gray', grayInventoryRows(), 'grayInventoryButtons', 'grayInventoryTable', 'grayInventoryCounts');
  renderComboInventory();
  if ($('inventorySummary')) {
    $('inventorySummary').textContent =
      `Жёлтые: ${yellow.found}/${yellow.total}. Серые: ${gray.found}/${gray.total}. Нижние списки показывают только неизвестное.`;
  }
  updateInventoryActive();
}
function grayCandidates() {
  const series = $('graySeries').value;
  const rows = allGrayRows();
  const logical = uniqueRowsByTx([...rows.b7Full, ...rows.lanesB6]);
  if (series === 'working') return logical.filter(item => isUsefulGrayObservation(savedByExactTx(item, graySaved, graySavedByTx)));
  if (series === 'saved') return logical.filter(item => savedByExactTx(item, graySaved, graySavedByTx));
  if (series === 'b7_full') return unknownRows(rows.b7Full, 'gray');
  if (series === 'lanes_b6') return unknownRows(rows.lanesB6, 'gray');
  if (series === 'all') return unknownRows(logical, 'gray');
  return unknownRows(rows.b7Main, 'gray');
}
function setGrayObservation(value) {
  $('grayVisual').value = value;
  updateGrayCommandReadout();
}
function renderGrayQuickButtons() {
  const root = $('grayQuickButtons');
  if (!root) return;
  root.innerHTML = '';
  for (const label of grayQuickLabels) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = label;
    btn.onclick = () => setGrayObservation(label);
    root.appendChild(btn);
  }
}
function setGrayCurrent(item) {
  grayCurrent = item;
  if ($('grayVisual')) $('grayVisual').value = savedByExactTx(item, graySaved, graySavedByTx) || '';
  updateGrayCommandReadout();
}
function grayCommandPayload() {
  if (grayCurrent) {
    return withRoute({action:'lane_combo', ...grayCurrent.payload, label:grayCurrent.hint});
  }
  return withRoute({action:'lane_combo', ...yellowPayload('0D', '00', '01', '01'), label:'серый manual'});
}
function updateGrayCommandReadout() {
  const payload = grayCommandPayload();
  const visual = $('grayVisual') ? $('grayVisual').value : '';
  $('grayRoadSelected').innerHTML = grayCurrent
    ? `<b>${escapeHtml(grayCurrent.code)}</b><br>${escapeHtml(grayCurrent.group)}<br><code>${escapeHtml(byteLine(payload))}</code>`
    : `<code>${escapeHtml(byteLine(payload))}</code>`;
  $('grayRoadReadout').innerHTML =
    `<b>TX:</b> ${escapeHtml(byteLine(payload))}<br>` +
    `<b>Группа:</b> ${escapeHtml(grayCurrent ? grayCurrent.group : ($('graySeries').selectedOptions[0]?.textContent || '-'))}<br>` +
    `<b>Деталь:</b> ${escapeHtml(grayCurrent ? grayCurrent.hint : 'manual')}<br>` +
    `<b>Твоя подпись:</b> ${escapeHtml(visual || '-')}<br>` +
    `<b>До манёвра:</b> ${escapeHtml(payload.distance)} м | progress=${escapeHtml(payload.progress)}`;
}
function renderGrayCommandButtons() {
  const root = $('grayRoadMatrix');
  root.innerHTML = '';
  const items = grayCandidates();
  if (!grayCurrent || !items.some(item => item.id === grayCurrent.id)) {
    grayCurrent = items[0] || null;
  }
  if (grayCurrent && $('grayVisual')) $('grayVisual').value = savedByExactTx(grayCurrent, graySaved, graySavedByTx) || '';
  const table = document.createElement('table');
  table.className = 'yellow-table';
  table.innerHTML = '<thead><tr><th>Команда</th><th>Exact TX</th><th>Твоя подпись</th></tr></thead>';
  const body = document.createElement('tbody');
  if (!items.length) {
    const emptyRow = document.createElement('tr');
    emptyRow.innerHTML = '<td colspan="3" class="saved">В этой группе неизвестных не осталось</td>';
    body.appendChild(emptyRow);
  }
  for (const item of items) {
    const row = document.createElement('tr');
    if (grayCurrent && item.id === grayCurrent.id) row.classList.add('active');
    const saved = savedByExactTx(item, graySaved, graySavedByTx) || '';
    row.innerHTML =
      `<td><b>${escapeHtml(item.code)}</b><br><span class="muted">${escapeHtml(item.group)}</span><br><span class="muted">${escapeHtml(item.hint)}</span></td>` +
      `<td><code>${escapeHtml(byteLine(item.payload))}</code></td>` +
      `<td class="saved">${escapeHtml(saved || '-')}</td>`;
    row.onclick = () => {
      setGrayCurrent(item);
      renderGrayCommandButtons();
      sendGrayCommand().catch(e => log(`gray send error: ${e.message}`));
    };
    body.appendChild(row);
  }
  table.appendChild(body);
  root.appendChild(table);
  updateGrayCommandReadout();
}
async function sendGrayCommand() {
  const payload = grayCommandPayload();
  await sendPayload(payload, `gray ${byteLine(payload)}`);
}
function grayShapePayload(value) {
  const b7 = normalizeByte(value ?? $('grayShapeB7').value);
  const b8 = normalizeByte($('grayShapeYellowB8').value || '01');
  const hint = grayB8Hints[b8] || 'режим стабилен';
  const shape = grayShapeB7Hints[b7] || 'серую ещё не подписали';
  return {
    action: 'lane_combo',
    b5: '0D',
    b6: '00',
    b7,
    b8,
    distance: $('distanceM').value || '80',
    progress: $('progress').value || '0',
    label: `Серая b7=${b7} ${shape}; b8=${b8} ${hint}`,
  };
}
function updateGrayShapeReadout() {
  const payload = grayShapePayload($('grayShapeB7').value);
  const hint = grayB8Hints[payload.b8] || 'режим стабилен';
  const shape = grayShapeB7Details[payload.b7] || 'ещё не подписано';
  $('grayShapeReadout').innerHTML =
    `<b>TX:</b> b5=${payload.b5} b6=${payload.b6} | gray candidate b7=${payload.b7} | b8=${payload.b8}<br>` +
    `<b>Фиксируем:</b> b8=${payload.b8} ${escapeHtml(hint)}; меняем только b7<br>` +
    `<b>Серая:</b> ${escapeHtml(shape)}<br>` +
    `<b>До манёвра:</b> ${escapeHtml(payload.distance)} м | progress=${escapeHtml(payload.progress)}`;
}
function selectGrayShapeB7(value) {
  $('grayShapeB7').value = normalizeByte(value);
  updateGrayShapeReadout();
}
function renderGrayShapeButtons() {
  const root = $('grayShapeMatrix');
  root.innerHTML = '';
  for (const value of grayShapeB7Values) {
    const btn = document.createElement('button');
    const code = document.createElement('strong');
    code.textContent = value;
    btn.appendChild(code);
    if (grayShapeB7Hints[value]) {
      const hint = document.createElement('span');
      hint.textContent = grayShapeB7Hints[value];
      btn.appendChild(hint);
      btn.title = `${value}: ${grayShapeB7Details[value] || grayShapeB7Hints[value]}`;
    }
    btn.onclick = () => {
      selectGrayShapeB7(value);
      sendGrayShape(value).catch(e => log(`gray b7 error: ${e.message}`));
    };
    root.appendChild(btn);
  }
}
function grayLeftPayload(value) {
  const b7 = normalizeByte(value ?? $('grayLeftB7').value);
  const b8 = normalizeByte($('grayLeftModeB8').value || '01');
  const mode = grayB8Hints[b8] || (b8 === '24' ? 'жёлтая влево' : 'режим стабилен');
  const observed = grayLeftB7Details[b7] || 'следующий серый кандидат, ещё не подписан';
  return {
    action: 'lane_combo',
    b5: '0D',
    b6: '00',
    b7,
    b8,
    distance: $('distanceM').value || '80',
    progress: $('progress').value || '0',
    label: `Серая дальше b7=${b7}; b8=${b8} ${mode}; ${observed}`,
  };
}
function updateGrayLeftReadout() {
  const payload = grayLeftPayload($('grayLeftB7').value);
  const mode = grayB8Hints[payload.b8] || (payload.b8 === '24' ? 'жёлтая влево' : 'режим стабилен');
  const observed = grayLeftB7Details[payload.b7] || 'ещё не проверено';
  $('grayLeftReadout').innerHTML =
    `<b>TX:</b> b5=${payload.b5} b6=${payload.b6} | gray candidate b7=${payload.b7} | b8=${payload.b8}<br>` +
    `<b>Фиксируем:</b> b8=${payload.b8} ${escapeHtml(mode)}; меняем только b7<br>` +
    `<b>Гипотеза:</b> дальше могут быть серый крутой вправо и/или левая зеркальная таблица<br>` +
    `<b>Серая:</b> ${escapeHtml(observed)}<br>` +
    `<b>До манёвра:</b> ${escapeHtml(payload.distance)} м | progress=${escapeHtml(payload.progress)}`;
}
function selectGrayLeftB7(value) {
  $('grayLeftB7').value = normalizeByte(value);
  updateGrayLeftReadout();
}
function renderGrayLeftButtons() {
  const root = $('grayLeftMatrix');
  root.innerHTML = '';
  for (const value of grayLeftB7Values) {
    const btn = document.createElement('button');
    const code = document.createElement('strong');
    code.textContent = value;
    btn.appendChild(code);
    const hint = document.createElement('span');
    hint.textContent = 'следующий';
    btn.appendChild(hint);
    btn.title = `${value}: проверка следующей серой формы`;
    btn.onclick = () => {
      selectGrayLeftB7(value);
      sendGrayLeft(value).catch(e => log(`gray left b7 error: ${e.message}`));
    };
    root.appendChild(btn);
  }
}
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#039;'}[ch]));
}
function setPhoto(dataUrl, name) {
  currentPhoto = dataUrl ? {dataUrl, name: name || 'cluster-photo.jpg'} : null;
  $('photoName').textContent = currentPhoto ? currentPhoto.name : 'фото не выбрано';
  $('photoPreview').src = dataUrl || '';
  $('photoPreview').classList.toggle('active', Boolean(dataUrl));
}
function clearPhoto() {
  setPhoto('', '');
  $('photoFile').value = '';
}
function notePhoto() {
  return currentPhoto;
}
async function startCamera() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    log('camera error: browser has no camera access');
    return;
  }
  if (!cameraStream) {
    cameraStream = await navigator.mediaDevices.getUserMedia({video: {facingMode: 'environment'}, audio: false});
  }
  const video = $('cameraPreview');
  video.srcObject = cameraStream;
  video.classList.add('active');
  await video.play();
  log('camera ready');
}
function capturePhoto() {
  const video = $('cameraPreview');
  if (!video.srcObject || !video.videoWidth) {
    log('camera error: start camera first');
    return;
  }
  const canvas = $('photoCanvas');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  const ctx = canvas.getContext('2d');
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  setPhoto(canvas.toDataURL('image/jpeg', 0.88), `cluster_${new Date().toISOString().replace(/[:.]/g, '-')}.jpg`);
  log('photo captured');
}
function handlePhotoFile() {
  const file = $('photoFile').files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    setPhoto(String(reader.result || ''), file.name || 'cluster-photo.jpg');
    log(`photo loaded: ${file.name || 'file'}`);
  };
  reader.onerror = () => log('photo error: read failed');
  reader.readAsDataURL(file);
}
function afterNoteSaved(data) {
  if (data.photo_path) log(`photo saved: ${data.photo_path}`);
  clearPhoto();
}
async function refreshPorts() {
  const data = await api('/api/ports');
  $('port').innerHTML = '';
  for (const item of data.ports || []) {
    const opt = document.createElement('option');
    opt.value = item.path;
    opt.textContent = `${item.path}${item.likely ? ' * likely' : ''}`;
    $('port').appendChild(opt);
  }
  if (!(data.ports || []).length) {
    const opt = document.createElement('option');
    opt.value = '';
    opt.textContent = 'No serial ports';
    $('port').appendChild(opt);
  }
  log(`ports: ${(data.ports || []).map(p => p.path).join(', ') || 'none'}`);
}
async function refreshStatus() {
  const data = await api('/api/status');
  const s = data.serial || {};
  $('status').textContent = [
    `serial: ${s.open ? 'open' : 'closed'} ${s.port || s.last_port || ''}`,
    `baud: ${s.baud || 115200}`,
    `last tx: ${s.last_tx || '-'}`,
    `notes: ${data.notes_path || '-'}`
  ].join('\n');
}
async function openSelected() {
  const port = $('port').value;
  if (!port) return log('open error: no serial port');
  const data = await api('/api/open', {method:'POST', body: JSON.stringify({port, baud:115200})});
  log(`open: ${data.port}`);
  await refreshStatus();
}
async function autoOpen() {
  const ports = await api('/api/ports');
  const target = (ports.ports || []).find(p => p.likely) || (ports.ports || [])[0];
  if (!target) return log('auto open error: no serial port');
  const data = await api('/api/open', {method:'POST', body: JSON.stringify({port:target.path, baud:115200})});
  log(`auto open: ${data.port}`);
  await refreshStatus();
}
async function closePort() {
  await api('/api/close', {method:'POST', body:'{}'});
  log('closed');
  await refreshStatus();
}
async function sendPayload(payload, label) {
  const data = await api('/api/send', {method:'POST', body: JSON.stringify(payload)});
  const frames = data.frames || [];
  log(`${label}: ${frames.map(f => f.hex).join(' | ') || 'no frames'}`);
  await refreshStatus();
}
async function sendNavOn() {
  await sendPayload({action:'nav_on'}, 'nav on');
}
async function sendNavOff() {
  await sendPayload({action:'nav_off'}, 'nav off');
}
async function sendStreet() {
  await sendPayload({action:'text', text:$('street').value || 'Текущая улица'}, 'street');
}
async function sendManeuver() {
  await sendPayload(maneuverPayload(), 'maneuver');
}
async function sendGrayUnderlay(value) {
  const payload = grayPayload(value);
  $('grayB8').value = payload.b8;
  updateGrayReadout();
  await sendPayload(payload, `yellow ${byteLine(payload)}`);
}
async function sendGrayShape(value) {
  const payload = grayShapePayload(value);
  selectGrayShapeB7(payload.b7);
  await sendPayload(payload, `gray shape b7=${payload.b7} mode b8=${payload.b8}`);
}
async function sendGrayLeft(value) {
  const payload = grayLeftPayload(value);
  selectGrayLeftB7(payload.b7);
  await sendPayload(payload, `gray next b7=${payload.b7} mode b8=${payload.b8}`);
}
async function sendClassifyCurrent() {
  if (!classifyCurrent) return log('classify error: no candidate');
  const payload = classifyPayload(classifyCurrent);
  await sendPayload(payload, `classify ${$('classifyGroup').value} ${classifyCurrent.label}`);
}
async function sendBase() {
  await sendNavOn();
  await sendStreet();
  await sendManeuver();
}
function exactTxFromPayload(payload) {
  return {
    b5: actualHexByte(payload.b5),
    b6: actualHexByte(payload.b6),
    b7: actualHexByte(payload.b7),
    b8: actualHexByte(payload.b8),
  };
}
async function saveInventoryResult(result) {
  if (!lastInventorySend) {
    log('inventory save error: сначала нажми верхнюю кнопку');
    return;
  }
  const observation = ($('inventoryObservation').value || '').trim() || (result === 'no_rx' ? 'нет реакции' : '');
  const payload = lastInventorySend.payload;
  const exactTx = exactTxFromPayload(payload);
  let queue = 'inventory-combo';
  let casePayload = {
    id: `inventory-${payloadTxKey(payload)}`,
    title: `Верхняя проверка ${lastInventorySend.label}`,
    group: 'inventory',
    command_key: `inventory-${payloadTxKey(payload)}`,
    command_code: byteLine(payload),
    command_group: 'inventory',
    command_detail: lastInventorySend.label,
    exact_tx: exactTx,
    tx_text: byteLine(payload),
    inventory_observation: observation,
    current_street: $('street').value,
    finish_eta: $('finishEta').value,
    finish_km: $('finishKm').value,
    distance_to_maneuver_m: $('distanceM').value,
    payload,
  };
  if (lastInventorySend.kind === 'yellow') {
    const item = lastInventorySend.item;
    queue = 'yellow-command-table';
    casePayload = {
      ...casePayload,
      id: item.id,
      title: `Жёлтая команда ${item.code}`,
      group: 'yellow-only',
      command_key: item.id,
      command_code: item.code,
      command_group: item.group,
      command_detail: item.hint,
      filter: 'inventory-top',
      yellow_observation: observation,
    };
    yellowSaved[item.id] = observation;
    yellowSavedByTx[payloadTxKey(payload)] = observation;
  } else if (lastInventorySend.kind === 'gray') {
    const item = lastInventorySend.item;
    queue = 'gray-command-table';
    casePayload = {
      ...casePayload,
      id: item.id,
      title: `Серая команда ${item.code}`,
      group: 'gray-only',
      command_key: item.id,
      command_code: item.code,
      command_group: item.group,
      command_detail: item.hint,
      filter: 'inventory-top',
      gray_observation: observation,
    };
    graySaved[item.id] = observation;
    graySavedByTx[payloadTxKey(payload)] = observation;
  } else if (lastInventorySend.kind === 'combo' && lastInventorySend.yellowItem && lastInventorySend.grayItem) {
    const yellowItem = lastInventorySend.yellowItem;
    const grayItem = lastInventorySend.grayItem;
    queue = 'yellow-gray-combo';
    casePayload = {
      ...casePayload,
      id: `combo-${payloadTxKey(yellowItem.payload)}-${payloadTxKey(grayItem.payload)}`,
      title: `Связка ${lastInventorySend.label}`,
      group: 'yellow-gray-combo',
      command_key: `combo-${payloadTxKey(yellowItem.payload)}-${payloadTxKey(grayItem.payload)}`,
      command_group: 'верхняя связка жёлтый + серый',
      command_detail: lastInventorySend.label,
      combo_observation: observation,
      yellow_key: yellowItem.id,
      yellow_code: yellowItem.code,
      yellow_group: yellowItem.group,
      yellow_exact_tx: exactTxFromPayload(yellowItem.payload),
      gray_key: grayItem.id,
      gray_code: grayItem.code,
      gray_group: grayItem.group,
      gray_exact_tx: exactTxFromPayload(grayItem.payload),
    };
  }
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue,
    index: 0,
    total: 1,
    case: casePayload,
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `${lastInventorySend.label}: ${observation || '-'}`,
    note: $('note').value,
    photo: notePhoto(),
  })});
  if (queue === 'yellow-gray-combo') {
    comboSavedRows.push({
      id: casePayload.command_key,
      label: observation || lastInventorySend.label,
      observation,
      payload,
      yellowCode: casePayload.yellow_code,
      grayCode: casePayload.gray_code,
    });
  }
  $('inventoryObservation').value = '';
  renderGrayButtons();
  renderGrayCommandButtons();
  renderInventory();
  renderYandexReadyManeuvers();
  renderYandexComboMap();
  afterNoteSaved(data);
  log(`saved inventory ${queue}: ${observation || '-'} | ${data.path}`);
}
async function saveResult(result) {
  const payload = maneuverPayload();
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'base-nav',
    index: 0,
    total: 1,
    case: {
      id: 'base-nav',
      title: 'База навигации',
      group: '00 база',
      current_street: $('street').value,
      finish_eta: $('finishEta').value,
      finish_km: $('finishKm').value,
      distance_to_maneuver_m: $('distanceM').value,
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: currentManeuver().name,
    note: $('note').value,
    photo: notePhoto(),
  })});
  afterNoteSaved(data);
  log(`saved ${result}: ${data.path}`);
}
async function saveGrayResult(result) {
  const payload = grayPayload();
  const items = yellowCandidates();
  const selectedIndex = yellowCurrent ? items.findIndex(item => item.id === yellowCurrent.id) : -1;
  const commandId = yellowCurrent ? yellowCurrent.id : `yellow-manual-${byteLine(payload).replace(/\s+/g, '-')}`;
  const commandCode = yellowCurrent ? yellowCurrent.code : byteLine(payload);
  const commandGroup = yellowCurrent ? yellowCurrent.group : 'manual';
  const commandDetail = yellowCurrent ? yellowCurrent.hint : '';
  const observation = ($('yellowVisual').value || '').trim() || (result === 'no_rx' ? 'нет реакции' : '');
  const exactTx = {
    b5: actualHexByte(payload.b5),
    b6: actualHexByte(payload.b6),
    b7: actualHexByte(payload.b7),
    b8: actualHexByte(payload.b8),
  };
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'yellow-command-table',
    index: selectedIndex,
    total: items.length,
    case: {
      id: commandId,
      title: `Жёлтая команда ${commandCode}`,
      group: 'yellow-only',
      command_key: commandId,
      command_code: commandCode,
      command_group: commandGroup,
      command_detail: commandDetail,
      filter: $('yellowSeries').value,
      exact_tx: exactTx,
      tx_text: byteLine(payload),
      yellow_observation: observation,
      current_street: $('street').value,
      finish_eta: $('finishEta').value,
      finish_km: $('finishKm').value,
      distance_to_maneuver_m: $('distanceM').value,
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `${commandCode}: ${observation || '-'}`,
    note: $('note').value,
    photo: notePhoto(),
  })});
  if (yellowCurrent) {
    yellowSaved[yellowCurrent.id] = observation;
    yellowSavedByTx[payloadTxKey(payload)] = observation;
    renderGrayButtons();
  }
  renderInventory();
  renderYandexReadyManeuvers();
  renderYandexComboMap();
  afterNoteSaved(data);
  log(`saved yellow ${commandCode} ${result}: ${observation || '-'} | ${data.path}`);
}
async function saveGrayCommandResult(result) {
  const payload = grayCommandPayload();
  const items = grayCandidates();
  const selectedIndex = grayCurrent ? items.findIndex(item => item.id === grayCurrent.id) : -1;
  const commandId = grayCurrent ? grayCurrent.id : `gray-manual-${byteLine(payload).replace(/\s+/g, '-')}`;
  const commandCode = grayCurrent ? grayCurrent.code : byteLine(payload);
  const commandGroup = grayCurrent ? grayCurrent.group : 'manual';
  const commandDetail = grayCurrent ? grayCurrent.hint : '';
  const observation = ($('grayVisual').value || '').trim() || (result === 'no_rx' ? 'нет реакции' : '');
  const exactTx = {
    b5: actualHexByte(payload.b5),
    b6: actualHexByte(payload.b6),
    b7: actualHexByte(payload.b7),
    b8: actualHexByte(payload.b8),
  };
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'gray-command-table',
    index: selectedIndex,
    total: items.length,
    case: {
      id: commandId,
      title: `Серая команда ${commandCode}`,
      group: 'gray-only',
      command_key: commandId,
      command_code: commandCode,
      command_group: commandGroup,
      command_detail: commandDetail,
      filter: $('graySeries').value,
      exact_tx: exactTx,
      tx_text: byteLine(payload),
      gray_observation: observation,
      current_street: $('street').value,
      finish_eta: $('finishEta').value,
      finish_km: $('finishKm').value,
      distance_to_maneuver_m: $('distanceM').value,
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `${commandCode}: ${observation || '-'}`,
    note: $('note').value,
    photo: notePhoto(),
  })});
  if (grayCurrent) {
    graySaved[grayCurrent.id] = observation;
    graySavedByTx[payloadTxKey(payload)] = observation;
    renderGrayCommandButtons();
  }
  renderInventory();
  renderYandexComboMap();
  afterNoteSaved(data);
  log(`saved gray ${commandCode} ${result}: ${observation || '-'} | ${data.path}`);
}
async function saveGrayShapeResult(result) {
  const payload = grayShapePayload($('grayShapeB7').value);
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'gray-shape-b7',
    index: grayShapeB7Values.indexOf(payload.b7),
    total: grayShapeB7Values.length,
    case: {
      id: `gray-shape-b7-${payload.b7}-b8-${payload.b8}`,
      title: `Поиск серой дороги b7=${payload.b7}, b8=${payload.b8}`,
      group: '01 серые подложки b7',
      hypothesis: 'b8=01 убирает жёлтую подсказку, b7 может менять серую дорогу',
      gray_candidate_b7: payload.b7,
      gray_observed: grayShapeB7Details[payload.b7] || '',
      fixed_b8_mode: payload.b8,
      current_street: $('street').value,
      finish_eta: $('finishEta').value,
      finish_km: $('finishKm').value,
      distance_to_maneuver_m: $('distanceM').value,
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `Ищем серую: b7=${payload.b7}, b8=${payload.b8}`,
    note: $('note').value,
    photo: notePhoto(),
  })});
  afterNoteSaved(data);
  log(`saved gray shape b7=${payload.b7} ${result}: ${data.path}`);
}
async function saveGrayLeftResult(result) {
  const payload = grayLeftPayload($('grayLeftB7').value);
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: 'gray-next-shape-b7',
    index: grayLeftB7Values.indexOf(payload.b7),
    total: grayLeftB7Values.length,
    case: {
      id: `gray-next-shape-b7-${payload.b7}-b8-${payload.b8}`,
      title: `Поиск следующей серой дороги b7=${payload.b7}, b8=${payload.b8}`,
      group: '01 серые подложки b7 next',
      hypothesis: 'после правой таблицы b7=02..11 ищем недостающий серый крутой вправо и возможную левую таблицу',
      gray_next_candidate_b7: payload.b7,
      gray_next_observed: grayLeftB7Details[payload.b7] || '',
      fixed_b8_mode: payload.b8,
      current_street: $('street').value,
      finish_eta: $('finishEta').value,
      finish_km: $('finishKm').value,
      distance_to_maneuver_m: $('distanceM').value,
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `Ищем следующую серую: b7=${payload.b7}, b8=${payload.b8}`,
    note: $('note').value,
    photo: notePhoto(),
  })});
  afterNoteSaved(data);
  log(`saved gray next b7=${payload.b7} ${result}: ${data.path}`);
}
async function saveClassifyCurrent(result) {
  if (!classifyCurrent) return log('classify save error: no candidate');
  const payload = classifyPayload(classifyCurrent);
  const visualValue = $('visualPreset').value;
  const visualLabel = selectedVisualLabel();
  const group = $('classifyGroup').value;
  const data = await api('/api/note', {method:'POST', body: JSON.stringify({
    result,
    queue: `classify-${group}`,
    index: classifyCandidates().findIndex(item => item.id === classifyCurrent.id),
    total: classifyCandidates().length,
    case: {
      id: classifyCurrent.id,
      title: `${group === 'yellow' ? 'Жёлтая' : 'Серая'} разметка ${classifyCurrent.label}`,
      group: group === 'yellow' ? 'yellow-only' : 'gray-only',
      visual_value: visualValue,
      visual_label: visualLabel,
      visual_custom: $('visualCustom').value.trim(),
      candidate_label: classifyCurrent.label,
      candidate_hint: classifyCurrent.hint,
      current_street: $('street').value,
      finish_eta: $('finishEta').value,
      finish_km: $('finishKm').value,
      distance_to_maneuver_m: $('distanceM').value,
      payload,
    },
    distance: $('distanceM').value,
    progress: $('progress').value,
    human_label: `${classifyCurrent.label}: ${visualLabel}`,
    note: $('note').value,
    photo: notePhoto(),
  })});
  afterNoteSaved(data);
  log(`saved classify ${group} ${classifyCurrent.label}: ${visualLabel}`);
}
function clearObject(obj) {
  for (const key of Object.keys(obj)) delete obj[key];
}
function registerSavedObservation(c, keyMap, txMap, observation) {
  const value = cleanInventoryLabel(observation);
  if (!value) return;
  if (c.command_key) keyMap[c.command_key] = value;
  const tx = caseTxKey(c);
  if (tx) txMap[tx] = value;
}
async function loadYellowSaved() {
  const data = await api('/api/notes');
  clearObject(yellowSaved);
  clearObject(yellowSavedByTx);
  for (const noteItem of data.notes || []) {
    const c = noteItem.case || {};
    if (noteItem.queue !== 'yellow-command-table' || !c.command_key) continue;
    registerSavedObservation(c, yellowSaved, yellowSavedByTx, c.yellow_observation || noteItem.human_label || '');
  }
  renderGrayButtons();
  renderInventory();
  renderYandexReadyManeuvers();
  renderYandexComboMap();
}
async function loadGraySaved() {
  const data = await api('/api/notes');
  clearObject(graySaved);
  clearObject(graySavedByTx);
  for (const noteItem of data.notes || []) {
    const c = noteItem.case || {};
    if (noteItem.queue !== 'gray-command-table' || !c.command_key) continue;
    registerSavedObservation(c, graySaved, graySavedByTx, c.gray_observation || noteItem.human_label || '');
  }
  renderGrayCommandButtons();
  renderInventory();
  renderYandexComboMap();
}
async function loadComboSaved() {
  const data = await api('/api/notes');
  comboSavedRows = [];
  for (const noteItem of data.notes || []) {
    const c = noteItem.case || {};
    if (noteItem.queue !== 'yellow-gray-combo' || !c.payload || noteItem.result === 'no_rx') continue;
    const observation = cleanInventoryLabel(c.combo_observation || c.inventory_observation || noteItem.human_label || c.command_detail || '');
    if (!observation || observation.toLowerCase().includes('нет реакции')) continue;
    comboSavedRows.push({
      id: c.command_key || `combo-${payloadTxKey(c.payload)}`,
      label: observation,
      observation,
      payload: c.payload,
      yellowCode: c.yellow_code || '',
      grayCode: c.gray_code || '',
    });
  }
  renderInventory();
  renderYandexComboMap();
}
document.addEventListener('DOMContentLoaded', async () => {
  ['street','maneuver','distanceM','finishEta','finishKm','progress'].forEach(id => $(id).addEventListener('input', () => {
    summary();
    updateGrayReadout();
    updateGrayCommandReadout();
  }));
  $('maneuver').addEventListener('change', summary);
  $('grayB8').addEventListener('input', () => {
    yellowCurrent = null;
    updateGrayReadout();
  });
  $('yellowSeries').addEventListener('change', () => {
    yellowCurrent = null;
    renderGrayButtons();
  });
  $('yellowVisual').addEventListener('input', updateGrayReadout);
  $('graySeries').addEventListener('change', () => {
    grayCurrent = null;
    renderGrayCommandButtons();
  });
  $('grayVisual').addEventListener('input', updateGrayCommandReadout);
  $('photoFile').addEventListener('change', handlePhotoFile);
  $('classifyGroup').addEventListener('change', () => {
    renderVisualPresets();
    renderClassifier();
  });
  $('classifyRange').addEventListener('change', renderClassifier);
  $('visualPreset').addEventListener('change', updateClassifyReadout);
  $('visualCustom').addEventListener('input', updateClassifyReadout);
  if ($('yandexComboRoute')) $('yandexComboRoute').addEventListener('change', renderYandexComboMap);
  if ($('yandexComboScope')) $('yandexComboScope').addEventListener('change', renderYandexComboMap);
  if ($('yandexFillSelect')) $('yandexFillSelect').addEventListener('change', handleYandexFillSelect);
  if ($('yandexFillGray')) $('yandexFillGray').addEventListener('change', renderYandexFillDetail);
  ['splitB5','splitB6','splitB7','splitB8'].forEach(id => {
    const el = $(id);
    if (!el) return;
    el.addEventListener('change', () => {
      splitGrayId = 'manual';
      splitYellowId = 'manual';
      updateSplitReadout();
      sendSplitFrame('byte-change').catch(e => log(`split error: ${e.message}`));
    });
    el.addEventListener('keydown', event => {
      if (event.key !== 'Enter') return;
      event.preventDefault();
      updateSplitReadout();
      sendSplitFrame('enter').catch(e => log(`split error: ${e.message}`));
    });
  });
  renderVisualPresets();
  renderSplitButtons();
  renderGrayButtons();
  renderGrayCommandButtons();
  renderGrayQuickButtons();
  renderYandexFillMap();
  renderYandexSimpleMap();
  renderYandexMissingMap();
  renderYandexOutputPlan();
  renderYandexReadyManeuvers();
  renderYandexComboMap();
  renderYandexTargets();
  renderInventory();
  renderClassifier();
  renderYellowClockButtons();
  setPhase('yellow');
  updateYellowLogic();
  summary();
  updateGrayReadout();
  updateGrayRoadLogic();
  updateGrayCommandReadout();
  updateClassifyReadout();
  await refreshPorts().catch(e => log(`ports error: ${e.message}`));
  await refreshStatus().catch(e => log(`status error: ${e.message}`));
  await loadYellowSaved().catch(e => log(`yellow saved error: ${e.message}`));
  await loadGraySaved().catch(e => log(`gray saved error: ${e.message}`));
  await loadComboSaved().catch(e => log(`combo saved error: ${e.message}`));
  await loadYandexFillSaved().catch(e => log(`yandex fill saved error: ${e.message}`));
  setInterval(refreshStatus, 1500);
});
</script>
</body>
</html>
"""

BASIC_HTML = r"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Yandex map manual</title>
  <style>
    :root { color-scheme: dark; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    * { box-sizing: border-box; }
    body { margin: 0; background: #0d1117; color: #edf3f8; }
    main { width: 100vw; min-height: 100vh; padding: 12px; }
    header { display: grid; grid-template-columns: 1fr auto; gap: 10px; align-items: center; margin-bottom: 10px; }
    h1 { margin: 0; font-size: 22px; letter-spacing: 0; }
    p { margin: 3px 0 0; color: #9facba; font-size: 13px; }
    button, select, input {
      border: 1px solid #334155;
      border-radius: 7px;
      background: #151c25;
      color: #edf3f8;
      font: inherit;
    }
    button { min-height: 34px; padding: 7px 10px; cursor: pointer; }
    button.primary { border-color: #3b82f6; background: #1d4ed8; }
    button.good { border-color: #22c55e; background: #14532d; }
    button.warn { border-color: #f59e0b; background: #4a2d08; }
    button.danger { border-color: #ef4444; background: #5f1515; }
    input, select { width: 100%; padding: 7px 8px; }
    .toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: flex-end; }
    .toolbar select { width: 220px; }
    .status { color: #aebdca; font-size: 12px; }
    .byte-help { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 7px; color: #cbd5e1; font-size: 12px; }
    .byte-help span { border: 1px solid #263241; border-radius: 6px; background: #101722; padding: 4px 6px; }
    .split-lab { border: 1px solid #263241; border-radius: 8px; background: #0b0f14; padding: 10px; margin-bottom: 10px; }
    .split-lab h2 { margin: 0 0 6px; font-size: 16px; }
    .split-grid { display: grid; grid-template-columns: minmax(320px, .85fr) minmax(360px, 1fr) minmax(360px, 1fr); gap: 10px; align-items: start; }
    .split-card { border: 1px solid #263241; border-radius: 8px; background: #101722; padding: 10px; }
    .split-card h3 { margin: 0 0 8px; font-size: 14px; color: #f2f7fb; }
    .split-bytes { display: grid; grid-template-columns: repeat(4, 1fr); gap: 7px; }
    .split-buttons { display: grid; grid-template-columns: repeat(auto-fit, minmax(112px, 1fr)); gap: 7px; }
    .split-buttons button { min-height: 52px; text-align: left; line-height: 1.18; }
    .split-buttons button.active { border-color: #60a5fa; box-shadow: 0 0 0 2px #60a5fa inset; }
    .split-buttons strong { display: block; color: #f8fbff; font-size: 12px; }
    .split-buttons span { display: block; margin-top: 4px; color: #aebdca; font: 11px ui-monospace, SFMono-Regular, Menlo, monospace; }
    .split-current { margin-top: 8px; color: #cfe0ef; font-size: 12px; line-height: 1.4; }
    .split-note { margin-top: 8px; display: grid; grid-template-columns: 1fr auto auto auto auto; gap: 7px; align-items: center; }
    .current-frame { border: 1px solid #263241; border-radius: 8px; background: #0b0f14; padding: 10px; margin-bottom: 10px; }
    .current-grid { display: grid; grid-template-columns: minmax(230px, .8fr) minmax(430px, 1.4fr) minmax(330px, 1fr); gap: 10px; align-items: end; }
    .current-title { font-size: 15px; font-weight: 800; color: #f8fbff; }
    .current-actions { display: grid; grid-template-columns: 1fr auto auto auto; gap: 7px; align-items: end; }
    .table-wrap { height: calc(100vh - 255px); overflow: auto; border: 1px solid #263241; border-radius: 8px; background: #0b0f14; }
    table { width: 100%; border-collapse: collapse; table-layout: fixed; font-size: 13px; }
    th, td { border-bottom: 1px solid #202a34; padding: 8px; vertical-align: top; text-align: left; }
    th { position: sticky; top: 0; z-index: 2; background: #111923; color: #b8c7d5; }
    .group-row td { background: #121b26; color: #facc15; font-weight: 800; border-bottom-color: #334155; }
    .group-count { margin-left: 8px; color: #94a3b8; font-weight: 500; }
    tr.active { outline: 2px solid #3b82f6; outline-offset: -2px; }
    tr.saved td:first-child { box-shadow: inset 4px 0 #22c55e; }
    .name { font-weight: 700; color: #f2f7fb; }
    .source { color: #9facba; font-size: 12px; margin-top: 4px; line-height: 1.3; }
    .group { color: #facc15; font-size: 12px; margin-top: 4px; }
    .bytes { display: grid; grid-template-columns: repeat(4, minmax(58px, 1fr)); gap: 5px; }
    .bytes label { color: #9facba; font-size: 11px; display: block; margin-bottom: 3px; }
    .bytes input { text-transform: uppercase; text-align: center; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    code { color: #d7e2ec; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .save-buttons { display: grid; grid-template-columns: 1fr 1fr; gap: 5px; }
    .mini { min-height: 28px; padding: 4px 6px; font-size: 12px; }
    .last { color: #86efac; font-size: 12px; margin-top: 4px; min-height: 16px; }
    .log { height: 82px; overflow: auto; border: 1px solid #263241; border-radius: 8px; background: #090d12; padding: 8px; margin-top: 8px; color: #b8c7d5; font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; }
    @media (max-width: 980px) {
      header { grid-template-columns: 1fr; }
      .toolbar { justify-content: stretch; }
      .toolbar select { width: 100%; }
      .split-grid, .split-note, .split-bytes, .current-grid, .current-actions { grid-template-columns: 1fr; }
      .table-wrap { height: auto; max-height: none; }
      table { min-width: 980px; }
    }
  </style>
</head>
<body>
<main>
  <header>
    <div>
      <h1>Yandex map manual</h1>
      <p>Только то, что надо мапить глазами: манёвры, круговое, финиш/иконки. Улица, ETA, скорость и прочие рабочие данные здесь не мешают.</p>
      <div class="byte-help">
        <span><b>b5</b> семья: 0D обычный, 1F съезд, 20 круг</span>
        <span><b>b6</b> левая/доп. серая ветка</span>
        <span><b>b7</b> прямо/правая серая ветка</span>
        <span><b>b8</b> угол жёлтой / съезд круга / иконка</span>
        <span><b>↑↓</b> +1/-1, <b>PgUp/PgDn</b> +16/-16, <b>Enter</b> показать</span>
      </div>
      <div id="status" class="status">status...</div>
    </div>
    <div class="toolbar">
      <select id="port"></select>
      <button onclick="openPort()">Открыть порт</button>
      <button class="primary" onclick="sendActive()">Показать выбранное</button>
      <button onclick="refreshSaved()">Обновить сохранённое</button>
    </div>
  </header>

  <section class="current-frame">
    <div class="current-grid">
      <div>
        <div class="current-title">Текущий кадр 0x45</div>
        <div id="splitCurrent" class="split-current">-</div>
      </div>
      <div class="split-bytes">
        <div>
          <label for="splitB5">d5 / b5 семья</label>
          <select id="splitB5">
            <option value="0D" selected>0D обычный</option>
            <option value="1F">1F съезд/разворот</option>
            <option value="20">20 круговое</option>
            <option value="02">02 стрелка к флагу</option>
            <option value="03">03 финиш</option>
          </select>
        </div>
        <div><label>d6 серая левая</label><input id="splitB6" value="00"></div>
        <div><label>d7 серая прямо/правая</label><input id="splitB7" value="01"></div>
        <div><label>d8 жёлтая</label><input id="splitB8" value="00"></div>
        <div><label>progress</label><input id="splitProgress" value="0"></div>
      </div>
      <div class="current-actions">
        <input id="splitNote" placeholder="что показала приборка">
        <button class="primary" onclick="sendSplitFrame('manual')">Показать</button>
        <button class="good" onclick="saveSplitFrame('ok')">OK</button>
        <button class="warn" onclick="saveSplitFrame('bad')">Не то</button>
      </div>
    </div>
  </section>

  <div class="table-wrap">
    <table>
      <thead>
        <tr>
          <th style="width:22%">Нормальное имя</th>
          <th style="width:25%">Что можем получать от Yandex</th>
          <th style="width:22%">Какие байты меняет</th>
          <th style="width:11%">TX</th>
          <th style="width:20%">Показать / сохранить</th>
        </tr>
      </thead>
      <tbody id="mapRows"></tbody>
    </table>
  </div>
  <div id="log" class="log"></div>
</main>
<script>
const $ = id => document.getElementById(id);
const rows = [
  {id:'family_regular', group:'d5 / семья', name:'0D обычная навигация', yandex:'обычные манёвры Yandex', apply:'family', b5:'0D'},
  {id:'family_exit', group:'d5 / семья', name:'1F съезды / развороты', yandex:'exit / u-turn family', apply:'family', b5:'1F'},
  {id:'family_roundabout', group:'d5 / семья', name:'20 круговое', yandex:'roundabout family', apply:'family', b5:'20'},
  {id:'family_finish_arrow', group:'d5 / семья', name:'02 стрелка к флагу', yandex:'fallback direction / compass-like finish arrow', apply:'family', b5:'02'},
  {id:'family_finish', group:'d5 / семья', name:'03 финиш', yandex:'finish reached / destination icon', apply:'family', b5:'03'},

  {id:'gray_straight', group:'d6-d7 серая дорога', name:'прямо', yandex:'road graph: straight only', apply:'gray', b6:'00', b7:'01'},
  {id:'gray_left', group:'d6-d7 серая дорога', name:'налево', yandex:'road graph: left only', apply:'gray', b6:'08', b7:'00'},
  {id:'gray_right', group:'d6-d7 серая дорога', name:'направо', yandex:'road graph: right only', apply:'gray', b6:'00', b7:'10'},
  {id:'gray_straight_left', group:'d6-d7 серая дорога', name:'прямо + налево', yandex:'road graph: straight and left', apply:'gray', b6:'08', b7:'01'},
  {id:'gray_straight_right', group:'d6-d7 серая дорога', name:'прямо + направо', yandex:'road graph: straight and right', apply:'gray', b6:'00', b7:'11'},
  {id:'gray_left_right', group:'d6-d7 серая дорога', name:'налево + направо', yandex:'road graph: left and right', apply:'gray', b6:'08', b7:'10'},
  {id:'gray_all', group:'d6-d7 серая дорога', name:'прямо + налево + направо', yandex:'road graph: straight, left and right', apply:'gray', b6:'08', b7:'11'},
  {id:'gray_exit_right', group:'d6-d7 серая дорога', name:'прямо + съезд направо', yandex:'road graph: straight and right ramp', apply:'gray', b6:'00', b7:'03'},
  {id:'gray_exit_left', group:'d6-d7 серая дорога', name:'съезд налево', yandex:'road graph: left ramp', apply:'gray', b6:'20', b7:'00'},

  {id:'yellow_forward', group:'d8 жёлтая стрелка', name:'12:00 прямо', yandex:'context_ra_forward', apply:'yellow', b8:'00'},
  {id:'yellow_right_15', group:'d8 жёлтая стрелка', name:'15:00 направо', yandex:'context_ra_turn_right', apply:'yellow', b8:'0C'},
  {id:'yellow_right_16', group:'d8 жёлтая стрелка', name:'16:00 круто направо', yandex:'context_ra_hard_turn_right', apply:'yellow', b8:'0F'},
  {id:'yellow_left_21', group:'d8 жёлтая стрелка', name:'21:00 налево', yandex:'context_ra_turn_left', apply:'yellow', b8:'24'},
  {id:'yellow_left_22', group:'d8 жёлтая стрелка', name:'22:00 круто налево', yandex:'left candidate 22:00', apply:'yellow', b8:'27'},

  {id:'right_u_turn', group:'Отдельные события', name:'Разворот направо', yandex:'right U-turn / loop-right candidate', apply:'full', b5:'1F', b6:'00', b7:'03', b8:'0C', note:'отдельное событие, не обычная d8 18:00'},
  {id:'left_u_turn_search', group:'Отдельные события', name:'Разворот налево', yandex:'left U-turn / loop-left candidate', apply:'full', b5:'1F', b6:'03', b7:'01', b8:'24'},
  {id:'exit_right_search', group:'Отдельные события', name:'Съезд направо', yandex:'context_ra_exit_right / ramp right', apply:'full', b5:'1F', b6:'00', b7:'03', b8:'0C'},
  {id:'exit_left', group:'Отдельные события', name:'Съезд налево', yandex:'context_ra_exit_left / ramp left', apply:'full', b5:'1F', b6:'20', b7:'00', b8:'24'},

  {id:'roundabout_exit_1', group:'Круговое', name:'Круг: 1 съезд', yandex:'roundabout exit 1', apply:'full', b5:'20', b6:'08', b7:'11', b8:'0C'},
  {id:'roundabout_exit_2', group:'Круговое', name:'Круг: 2 съезд', yandex:'roundabout exit 2', apply:'full', b5:'20', b6:'08', b7:'11', b8:'00'},
  {id:'roundabout_exit_3', group:'Круговое', name:'Круг: 3 съезд', yandex:'roundabout exit 3', apply:'full', b5:'20', b6:'08', b7:'11', b8:'24'},
  {id:'roundabout_exit_4', group:'Круговое', name:'Круг: 4 съезд', yandex:'roundabout exit 4', apply:'full', b5:'20', b6:'08', b7:'11', b8:'18'},
  {id:'roundabout_gray_4_exits', group:'Круговое', name:'Серый круг + 4 съезда', yandex:'roundabout road with exits', apply:'full', b5:'20', b6:'08', b7:'11', b8:'FF'},

  {id:'direction_to_finish', group:'Финиш / иконки', name:'Стрелка к флагу / без манёвра', yandex:'fallback direction / compass-like finish arrow', apply:'full', b5:'02', b6:'00', b7:'00', b8:'06', note:'b8 задаёт угол'},
  {id:'finish_icon', group:'Финиш / иконки', name:'Финиш иконкой', yandex:'finish reached / destination icon', apply:'full', b5:'03', b6:'00', b7:'00', b8:'00'},
  {id:'custom_1', group:'Финиш / иконки', name:'Своя команда 1', yandex:'manual 0x45 candidate', apply:'full', b5:'0D', b6:'00', b7:'00', b8:'00'},
  {id:'custom_2', group:'Финиш / иконки', name:'Своя команда 2', yandex:'manual round/sign candidate', apply:'full', b5:'20', b6:'00', b7:'00', b8:'00'},
];
const state = {};
let activeId = rows.find(row => row.b5)?.id || rows[0].id;
let sendTimers = {};
let saved = {};
let splitGrayId = 'straight';
let splitYellowId = 'forward';
const splitGrayOptions = [
  {id:'straight', name:'прямо', b6:'00', b7:'01'},
  {id:'right', name:'направо', b6:'00', b7:'10'},
  {id:'straight_right', name:'прямо + право', b6:'00', b7:'11'},
  {id:'left', name:'налево', b6:'08', b7:'00'},
  {id:'straight_left', name:'прямо + лево', b6:'08', b7:'01'},
  {id:'left_right', name:'лево + право', b6:'08', b7:'10'},
  {id:'straight_left_right', name:'прямо + лево + право', b6:'08', b7:'11'},
  {id:'exit_right', name:'съезд справа', b6:'00', b7:'03'},
  {id:'exit_left', name:'съезд слева', b6:'20', b7:'00'},
];
const splitYellowOptions = [
  {id:'forward', name:'12:00 прямо', b8:'00'},
  {id:'right_15', name:'15:00 право', b8:'0C'},
  {id:'right_16', name:'16:00', b8:'0F'},
  {id:'left_21', name:'21:00 лево', b8:'24'},
  {id:'left_22', name:'22:00', b8:'27'},
];

function log(line) {
  const root = $('log');
  const div = document.createElement('div');
  div.textContent = `${new Date().toLocaleTimeString()} ${line}`;
  root.prepend(div);
}
async function api(path, options) {
  const res = await fetch(path, {headers:{'Content-Type':'application/json'}, ...(options || {})});
  const data = await res.json();
  if (!res.ok || data.ok === false) throw new Error(data.error || res.statusText);
  return data;
}
function normalizeByte(value) {
  const raw = String(value || '').trim().replace(/^0x/i, '').toUpperCase();
  if (!raw) return '';
  const clean = raw.replace(/[^0-9A-F]/g, '').slice(0, 2);
  if (!clean) return '';
  return clean.padStart(2, '0');
}
function rowApplyKeys(row) {
  if (row.apply === 'family') return ['b5'];
  if (row.apply === 'gray') return ['b6', 'b7'];
  if (row.apply === 'yellow') return ['b8'];
  return ['b5', 'b6', 'b7', 'b8'].filter(key => row[key] != null);
}
function rowState(id) {
  if (!state[id]) {
    const row = rows.find(item => item.id === id);
    state[id] = {b5:row.b5 || '', b6:row.b6 || '', b7:row.b7 || '', b8:row.b8 || ''};
  }
  return state[id];
}
function splitProgressValue() {
  const raw = String($('splitProgress')?.value || '0').trim();
  const value = parseInt(raw, 10);
  if (!Number.isFinite(value)) return '0';
  return String(Math.max(0, Math.min(255, value)));
}
function currentFrame() {
  return {
    b5: splitByte('splitB5', '0D'),
    b6: splitByte('splitB6', '00'),
    b7: splitByte('splitB7', '01'),
    b8: splitByte('splitB8', '00'),
    progress: splitProgressValue(),
  };
}
function writeCurrentFrame(frame) {
  if ($('splitB5')) $('splitB5').value = normalizeByte(frame.b5) || '0D';
  if ($('splitB6')) $('splitB6').value = normalizeByte(frame.b6) || '00';
  if ($('splitB7')) $('splitB7').value = normalizeByte(frame.b7) || '00';
  if ($('splitB8')) $('splitB8').value = normalizeByte(frame.b8) || '00';
  if ($('splitProgress')) $('splitProgress').value = String(frame.progress || '0');
  updateSplit();
  refreshTxLines();
}
function payloadFromFrame(frame, label) {
  const b5 = normalizeByte(frame.b5);
  const b6 = normalizeByte(frame.b6);
  const b7 = normalizeByte(frame.b7);
  const b8 = normalizeByte(frame.b8);
  if (!b5 || !b6 || !b7 || !b8) return null;
  return {
    action:'lane_combo',
    b5:`0x${b5}`,
    b6:`0x${b6}`,
    b7:`0x${b7}`,
    b8:`0x${b8}`,
    distance:'80',
    progress:String(frame.progress || '0'),
    label:label || 'current',
  };
}
function applyRowToFrame(row, frame) {
  const s = rowState(row.id);
  for (const key of rowApplyKeys(row)) {
    const value = normalizeByte(s[key]);
    if (value) frame[key] = value;
  }
  return frame;
}
function payloadFor(id, commit = false) {
  const frame = currentFrame();
  const row = rows.find(item => item.id === id);
  if (row) applyRowToFrame(row, frame);
  if (commit) writeCurrentFrame(frame);
  return payloadFromFrame(frame, id || 'current');
}
function splitByte(id, fallback = '00') {
  const value = normalizeByte($(id)?.value || fallback);
  return value || fallback;
}
function splitGrayOption() {
  return splitGrayOptions.find(item => item.id === splitGrayId) || {
    id:'manual',
    name:'ручная серая',
    b6:splitByte('splitB6'),
    b7:splitByte('splitB7'),
  };
}
function splitYellowOption() {
  return splitYellowOptions.find(item => item.id === splitYellowId) || {
    id:'manual',
    name:'ручная жёлтая',
    b8:splitByte('splitB8'),
  };
}
function splitPayload() {
  return payloadFromFrame(currentFrame(), 'manual');
}
function splitByteLine(payload) {
  return `b5=${payload.b5.slice(2)} b6=${payload.b6.slice(2)} b7=${payload.b7.slice(2)} b8=${payload.b8.slice(2)}`;
}
function renderSplitActiveButtons() {
  for (const item of splitGrayOptions) {
    const btn = $(`splitGray_${item.id}`);
    if (btn) btn.classList.toggle('active', item.id === splitGrayId);
  }
  for (const item of splitYellowOptions) {
    const btn = $(`splitYellow_${item.id}`);
    if (btn) btn.classList.toggle('active', item.id === splitYellowId);
  }
}
function updateSplit() {
  const payload = splitPayload();
  const root = $('splitCurrent');
  if (root) {
    root.innerHTML =
      `<b>TX:</b> <code>${escapeHtml(splitByteLine(payload))}</code><br>` +
      `<b>d6/d7 серая:</b> ${escapeHtml(splitByte('splitB6'))}/${escapeHtml(splitByte('splitB7', '01'))}<br>` +
      `<b>d8 жёлтая:</b> ${escapeHtml(splitByte('splitB8'))}`;
  }
  renderSplitActiveButtons();
}
function renderSplit() {
  const grayRoot = $('splitGrayButtons');
  const yellowRoot = $('splitYellowButtons');
  if (grayRoot) {
    grayRoot.innerHTML = splitGrayOptions.map(item => `
      <button id="splitGray_${escapeHtml(item.id)}" onclick="selectSplitGray('${escapeHtml(item.id)}')">
        <strong>${escapeHtml(item.name)}</strong>
        <span>d6=${escapeHtml(item.b6)} d7=${escapeHtml(item.b7)}</span>
      </button>
    `).join('');
  }
  if (yellowRoot) {
    yellowRoot.innerHTML = splitYellowOptions.map(item => `
      <button id="splitYellow_${escapeHtml(item.id)}" onclick="selectSplitYellow('${escapeHtml(item.id)}')">
        <strong>${escapeHtml(item.name)}</strong>
        <span>d8=${escapeHtml(item.b8)}</span>
      </button>
    `).join('');
  }
  updateSplit();
}
async function selectSplitGray(id) {
  const item = splitGrayOptions.find(option => option.id === id);
  if (!item) return;
  splitGrayId = item.id;
  $('splitB6').value = item.b6;
  $('splitB7').value = item.b7;
  updateSplit();
  await sendSplitFrame('gray');
}
async function selectSplitYellow(id) {
  const item = splitYellowOptions.find(option => option.id === id);
  if (!item) return;
  splitYellowId = item.id;
  $('splitB8').value = item.b8;
  updateSplit();
  await sendSplitFrame('yellow');
}
async function sendSplitFrame(reason = 'manual') {
  const payload = splitPayload();
  const data = await api('/api/send', {method:'POST', body:JSON.stringify(payload)});
  const frames = (data.frames || []).map(item => item.hex).join(' | ');
  log(`split ${reason}: ${splitByteLine(payload)} ${frames || 'sent'}`);
}
async function saveSplitFrame(result) {
  const payload = splitPayload();
  const note = $('splitNote').value || '';
  const txText = splitByteLine(payload);
  const data = await api('/api/note', {method:'POST', body:JSON.stringify({
    result,
    queue:'current-nav-frame',
    index:0,
    total:1,
    case:{
      id:`current-${payload.b5.slice(2)}-${payload.b6.slice(2)}-${payload.b7.slice(2)}-${payload.b8.slice(2)}`,
      title:'Текущий кадр 0x45',
      group:'current-nav-frame',
      tx_text:txText,
      payload,
    },
    distance:'80',
    progress:splitProgressValue(),
    human_label:note || `Текущий кадр: ${txText}`,
    note,
  })});
  log(`saved current ${result}: ${txText} | ${data.path}`);
}
function byteLine(id) {
  const p = payloadFor(id);
  if (!p) return '-';
  return `b5=${p.b5.slice(2)} b6=${p.b6.slice(2)} b7=${p.b7.slice(2)} b8=${p.b8.slice(2)}`;
}
function refreshTxLines() {
  for (const row of rows) {
    const tx = $(`tx_${row.id}`);
    if (tx) tx.textContent = byteLine(row.id);
  }
}
function rowActionLabel(row) {
  if (row.apply === 'family') return 'меняет только d5/b5';
  if (row.apply === 'gray') return 'меняет только d6/d7';
  if (row.apply === 'yellow') return 'меняет только d8';
  return 'готовое отдельное событие';
}
function rowByteInputs(id) {
  const row = rows.find(item => item.id === id);
  const keys = rowApplyKeys(row);
  return keys.map(key => byteInput(id, key)).join('');
}
function render() {
  const root = $('mapRows');
  root.innerHTML = '';
  let currentGroup = '';
  for (const row of rows) {
    if (row.group !== currentGroup) {
      currentGroup = row.group;
      const groupRow = document.createElement('tr');
      groupRow.className = 'group-row';
      const groupTotal = rows.filter(item => item.group === currentGroup).length;
      groupRow.innerHTML = `<td colspan="5">${escapeHtml(currentGroup)}<span class="group-count">(${groupTotal})</span></td>`;
      root.appendChild(groupRow);
    }
    rowState(row.id);
    const tr = document.createElement('tr');
    tr.id = `row_${row.id}`;
    if (row.id === activeId) tr.classList.add('active');
    if (saved[row.id]) tr.classList.add('saved');
    tr.innerHTML = `
      <td onclick="selectRow('${row.id}', true)">
        <div class="name">${escapeHtml(row.name)}</div>
        <div class="group">${escapeHtml(row.group)}</div>
        <div class="source">${escapeHtml(rowActionLabel(row))}</div>
        <div class="source">${escapeHtml(row.note || '')}</div>
      </td>
      <td onclick="selectRow('${row.id}', true)">
        <div>${escapeHtml(row.yandex)}</div>
        <div class="source">id: ${escapeHtml(row.id)}</div>
      </td>
      <td>
        <div class="bytes">
          ${rowByteInputs(row.id)}
        </div>
      </td>
      <td onclick="selectRow('${row.id}', true)"><code id="tx_${row.id}">${escapeHtml(byteLine(row.id))}</code></td>
      <td>
        <div class="save-buttons">
          <button class="primary mini" onclick="sendRow('${row.id}', 'button')">Показать</button>
          <button class="good mini" onclick="saveRow('${row.id}', 'ok')">Сохранить</button>
        </div>
        <div class="last" id="last_${row.id}">${saved[row.id] ? escapeHtml(saved[row.id]) : ''}</div>
      </td>`;
    root.appendChild(tr);
  }
}
function byteInput(id, key) {
  const labels = {b5:'b5 семья', b6:'b6 лев.', b7:'b7 прав.', b8:'b8 угол'};
  const titles = {
    b5:'семья команды: 0D обычный манёвр, 1F съезд, 20 круг',
    b6:'левая или дополнительная серая ветка/маска',
    b7:'прямо и правая серая ветка/маска',
    b8:'угол жёлтой стрелки, номер съезда круга или код иконки',
  };
  return `<div><label title="${escapeHtml(titles[key])}">${labels[key]}</label><input value="${escapeHtml(rowState(id)[key])}" maxlength="4" onfocus="selectRow('${id}', false)" oninput="setByte('${id}', '${key}', this.value, this)" onkeydown="handleByteKey(event, '${id}', '${key}', this)"></div>`;
}
function escapeHtml(value) {
  return String(value == null ? '' : value).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}
function selectRow(id, send) {
  activeId = id;
  document.querySelectorAll('tr.active').forEach(row => row.classList.remove('active'));
  const tr = $(`row_${id}`);
  if (tr) tr.classList.add('active');
  if (send) sendRow(id, 'select').catch(e => log(`send error: ${e.message}`));
}
function setByte(id, key, value, input) {
  rowState(id)[key] = normalizeByte(value);
  if (input) input.value = rowState(id)[key];
  const tx = $(`tx_${id}`);
  if (tx) tx.textContent = byteLine(id);
  queueSend(id);
}
function byteNumber(value) {
  const normalized = normalizeByte(value);
  return normalized ? parseInt(normalized, 16) : 0;
}
function setByteNumber(id, key, value, input) {
  const next = ((value % 256) + 256) % 256;
  const hex = next.toString(16).toUpperCase().padStart(2, '0');
  rowState(id)[key] = hex;
  if (input) input.value = hex;
  const tx = $(`tx_${id}`);
  if (tx) tx.textContent = byteLine(id);
  queueSend(id);
}
function handleByteKey(event, id, key, input) {
  let delta = 0;
  if (event.key === 'ArrowUp') delta = 1;
  if (event.key === 'ArrowDown') delta = -1;
  if (event.key === 'PageUp') delta = 16;
  if (event.key === 'PageDown') delta = -16;
  if (delta !== 0) {
    event.preventDefault();
    setByteNumber(id, key, byteNumber(rowState(id)[key]) + delta, input);
    return;
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    sendRow(id, 'enter').catch(e => log(`send error: ${e.message}`));
    return;
  }
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    saveRow(id, 'ok').catch(e => log(`save error: ${e.message}`));
  }
}
function queueSend(id) {
  clearTimeout(sendTimers[id]);
  sendTimers[id] = setTimeout(() => sendRow(id, 'change').catch(e => log(`send error: ${e.message}`)), 250);
}
async function sendRow(id, reason) {
  activeId = id;
  const payload = payloadFor(id, true);
  const row = rows.find(item => item.id === id);
  if (!payload) {
    log(`${row.name}: заполни все 4 байта`);
    return;
  }
  const data = await api('/api/send', {method:'POST', body:JSON.stringify(payload)});
  const frames = (data.frames || []).map(item => item.hex).join(' | ');
  log(`${row.name} ${byteLine(id)} ${frames || 'sent'}`);
}
async function saveRow(id, result) {
  const row = rows.find(item => item.id === id);
  const payload = payloadFor(id, true);
  if (!payload) {
    log(`${row.name}: нечего сохранить, заполни все 4 байта`);
    return;
  }
  const data = await api('/api/note', {method:'POST', body:JSON.stringify({
    result,
    queue:'yandex-manual-map',
    index: rows.findIndex(item => item.id === id),
    total: rows.length,
    case:{
      id:`yandex-manual-${id}`,
      title:row.name,
      group:row.group,
      apply:row.apply || 'full',
      yandex_key:row.id,
      yandex_source:row.yandex,
      tx_text:byteLine(id),
      payload,
    },
    distance:'80',
    progress:'0',
    human_label:`${row.name}: ${byteLine(id)}`,
    note:'',
  })});
  saved[id] = `${byteLine(id)}`;
  const tr = $(`row_${id}`);
  if (tr) tr.classList.add('saved');
  const last = $(`last_${id}`);
  if (last) last.textContent = saved[id];
  log(`saved ${row.name}: ${data.path}`);
}
function sendActive() {
  sendRow(activeId, 'active').catch(e => log(`send error: ${e.message}`));
}
function selectByOffset(delta) {
  const index = rows.findIndex(row => row.id === activeId);
  const nextIndex = Math.max(0, Math.min(rows.length - 1, index + delta));
  const next = rows[nextIndex];
  if (!next) return;
  selectRow(next.id, true);
  const tr = $(`row_${next.id}`);
  if (tr) tr.scrollIntoView({block:'nearest'});
}
document.addEventListener('keydown', event => {
  const tag = (event.target && event.target.tagName || '').toUpperCase();
  if (tag === 'INPUT' || tag === 'SELECT' || tag === 'BUTTON') {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      saveRow(activeId, 'ok').catch(e => log(`save error: ${e.message}`));
    }
    return;
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    selectByOffset(1);
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    selectByOffset(-1);
  } else if (event.key === 'Enter') {
    event.preventDefault();
    sendActive();
  } else if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    saveRow(activeId, 'ok').catch(e => log(`save error: ${e.message}`));
  }
});
async function refreshPorts() {
  const data = await api('/api/ports');
  const select = $('port');
  select.innerHTML = '';
  for (const port of data.ports || []) {
    const option = document.createElement('option');
    option.value = port.path;
    option.textContent = `${port.path}${port.likely ? ' *' : ''}`;
    select.appendChild(option);
  }
  if (!select.value && select.options.length) select.selectedIndex = 0;
  return data.ports || [];
}
async function openPort() {
  const port = $('port').value;
  if (!port) return log('порт не найден');
  const data = await api('/api/open', {method:'POST', body:JSON.stringify({port, baud:115200})});
  log(`opened ${data.port}`);
  await refreshStatus();
}
async function refreshStatus() {
  const data = await api('/api/status');
  const serial = data.serial || {};
  $('status').textContent = serial.open ? `порт открыт: ${serial.port} | notes: ${data.notes_path}` : `порт закрыт | notes: ${data.notes_path}`;
}
async function refreshSaved() {
  const data = await api('/api/notes');
  saved = {};
  for (const item of data.notes || []) {
    if (item.queue !== 'yandex-manual-map') continue;
    const c = item.case || {};
    const id = String(c.yandex_key || '').replace(/^yandex-manual-/, '');
    if (!rows.some(row => row.id === id)) continue;
    saved[id] = `${c.tx_text || item.human_label || ''}`;
    const payload = c.payload || {};
    const s = rowState(id);
    if (payload.b5) s.b5 = normalizeByte(payload.b5);
    if (payload.b6) s.b6 = normalizeByte(payload.b6);
    if (payload.b7) s.b7 = normalizeByte(payload.b7);
    if (payload.b8) s.b8 = normalizeByte(payload.b8);
  }
  render();
}
async function boot() {
  render();
  renderSplit();
  for (const id of ['splitB5', 'splitB6', 'splitB7', 'splitB8', 'splitProgress']) {
    const el = $(id);
    if (!el) continue;
    el.addEventListener('change', () => {
      if (id === 'splitB6' || id === 'splitB7') splitGrayId = 'manual';
      if (id === 'splitB8') splitYellowId = 'manual';
      updateSplit();
      refreshTxLines();
      sendSplitFrame(`byte ${id}`).catch(e => log(`split error: ${e.message}`));
    });
    el.addEventListener('keydown', event => {
      if (event.key !== 'Enter') return;
      event.preventDefault();
      if (id === 'splitB6' || id === 'splitB7') splitGrayId = 'manual';
      if (id === 'splitB8') splitYellowId = 'manual';
      updateSplit();
      refreshTxLines();
      sendSplitFrame(`enter ${id}`).catch(e => log(`split error: ${e.message}`));
    });
  }
  await refreshPorts().catch(e => log(`ports error: ${e.message}`));
  await refreshStatus().catch(e => log(`status error: ${e.message}`));
  await refreshSaved().catch(e => log(`saved error: ${e.message}`));
  const status = await api('/api/status').catch(() => null);
  if (status && !(status.serial || {}).open && $('port').value) {
    await openPort().catch(e => log(`open error: ${e.message}`));
  }
  setInterval(refreshStatus, 1500);
}
boot();
</script>
</body>
</html>
"""


def send_json(handler, status, payload, content_type="application/json"):
    body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.send_header("Access-Control-Allow-Headers", "Content-Type")
    handler.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
    handler.end_headers()
    handler.wfile.write(body)


def send_file(handler, path, content_type):
    with open(path, "rb") as fh:
        body = fh.read()
    handler.send_response(200)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Cache-Control", "public, max-age=86400")
    handler.end_headers()
    handler.wfile.write(body)


def read_body(handler):
    raw = handler.rfile.read(int(handler.headers.get("Content-Length", "0") or "0"))
    return json.loads(raw.decode("utf-8") or "{}")


def ensure_dirs():
    os.makedirs(LOG_ROOT, exist_ok=True)
    os.makedirs(PHOTO_DIR, exist_ok=True)


def send_frames(frames, delay_s=0.06):
    sent = []
    for label, frame in frames:
        bridge.write(frame)
        sent.append({"label": label, "hex": hex_bytes(frame), "bytes": len(frame)})
        time.sleep(delay_s)
    return sent


def route_start_frames(data):
    speed = int(float(str(data.get("speed") or "60").replace(",", ".")))
    speed = max(0, min(255, speed))
    text = str(data.get("text") or "Navi lane lab")
    return [
        ("nav on 0x48", nav_on_packet(True)),
        ("nav text 0x4A", nav_text_packet(text)),
        ("speed limit 0x44", speed_limit_packet(speed, 0x08 if speed else 0x04, 0x95)),
    ]


def eta_distance_packet(distance, km=True):
    text = str(distance or "0").strip().replace(",", ".")
    try:
        value = float(text)
    except ValueError:
        value = 0.0
    value = max(0.0, min(9999.9, value))
    whole = int(value)
    tenth = round((value - whole) * 10.0) & 0xFF
    frame = bytearray([0xBB, 0x41, 0xA1, 0x0B, 0x47, 0x00,
                       (whole >> 8) & 0xFF, whole & 0xFF, tenth,
                       0x01 if km else 0x00, 0x00])
    return checksum(frame)


def eta_time_packet(value):
    text = str(value or "").strip()
    match = re.search(r"(\d{1,2})[:.](\d{2})", text)
    hour = 18
    minute = 30
    if match:
        hour = int(match.group(1))
        minute = int(match.group(2))
    hour = max(0, min(23, hour))
    minute = max(0, min(59, minute))
    return checksum(bytearray([0xBB, 0x41, 0xA1, 0x08, 0x49, hour, minute, 0x00]))


def frames_for_request(data):
    action = str(data.get("action") or "").lower()
    if action == "route_start":
        return route_start_frames(data)
    if action == "eta_demo":
        distance = data.get("distance") or "1.0"
        eta = data.get("eta") or "18:30"
        return [
            ("eta distance 0x47", eta_distance_packet(distance, True)),
            ("eta time 0x49", eta_time_packet(eta)),
        ]
    return navi_frames(data)


def safe_name(value):
    text = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(value or "").strip())
    return text[:80] or "photo"


def save_photo(photo):
    if not isinstance(photo, dict):
        return ""
    data_url = str(photo.get("dataUrl") or "")
    if not data_url.startswith("data:") or "," not in data_url:
        return ""
    meta, encoded = data_url.split(",", 1)
    if ";base64" not in meta:
        return ""
    if len(encoded) > 18_000_000:
        raise ValueError("photo is too large")
    raw = base64.b64decode(encoded)
    ext = "jpg"
    if "image/png" in meta:
        ext = "png"
    elif "image/webp" in meta:
        ext = "webp"
    elif "image/heic" in meta or "image/heif" in meta:
        ext = "heic"
    original = safe_name(photo.get("name") or f"photo.{ext}")
    stem = os.path.splitext(original)[0] or "photo"
    filename = f"{time.strftime('%Y%m%d_%H%M%S')}_{stem}.{ext}"
    path = os.path.join(PHOTO_DIR, filename)
    with open(path, "wb") as fh:
        fh.write(raw)
    return path


def save_note(data):
    ensure_dirs()
    photo_path = save_photo(data.get("photo"))
    item = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "result": str(data.get("result") or ""),
        "queue": str(data.get("queue") or ""),
        "index": data.get("index"),
        "total": data.get("total"),
        "case": data.get("case") or {},
        "distance": str(data.get("distance") or ""),
        "progress": str(data.get("progress") or ""),
        "human_label": str(data.get("human_label") or "").strip(),
        "note": str(data.get("note") or "").strip(),
        "photo_path": photo_path,
        "session_id": str(data.get("session_id") or "").strip(),
        "guided": bool(data.get("guided")),
    }
    with open(NOTES_PATH, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(item, ensure_ascii=False) + "\n")
    return item, photo_path


def load_notes():
    ensure_dirs()
    if not os.path.exists(NOTES_PATH):
        return []
    with open(NOTES_PATH, "r", encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()][-500:]


def load_trc_analysis():
    if not os.path.exists(TRC_ANALYSIS_JSON):
        return []
    with open(TRC_ANALYSIS_JSON, "r", encoding="utf-8") as fh:
        return json.load(fh)


def load_trc_report_html():
    if not os.path.exists(TRC_ANALYSIS_MD):
        return "<!doctype html><meta charset='utf-8'><title>TRC report</title><pre>TRC report not found</pre>"
    with open(TRC_ANALYSIS_MD, "r", encoding="utf-8") as fh:
        text = fh.read()
    escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return (
        "<!doctype html><meta charset='utf-8'><title>TRC report</title>"
        "<style>body{font:14px/1.45 -apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;margin:24px;background:#f7f8fb;color:#172033}"
        "pre{white-space:pre-wrap;background:#fff;border:1px solid #d9deea;border-radius:8px;padding:16px;overflow:auto}"
        "a{color:#1457b8}</style>"
        "<p><a href='/'>Назад к Navi lab</a> · <a href='/api/trc-analysis'>JSON</a></p>"
        f"<pre>{escaped}</pre>"
    )


def image_url(filename):
    return "/official-images/png/" + os.path.basename(str(filename or ""))


def rows_with_images():
    rows = []
    for row in CHECK_TABLE_ROWS:
        item = dict(row)
        images = []
        for assigned in OFFICIAL_IMAGE_ASSIGNMENTS.get(row["key"], []):
            filename = os.path.basename(str(assigned.get("file") or ""))
            if not filename:
                continue
            images.append({
                "file": filename,
                "url": image_url(filename),
                "label": assigned.get("label") or filename,
                "exists": os.path.exists(os.path.join(OFFICIAL_IMAGE_PNG_DIR, filename)),
            })
        item["images"] = images
        rows.append(item)
    return rows


def load_official_images(q="", offset=0, limit=120):
    if not os.path.exists(OFFICIAL_IMAGE_MANIFEST):
        return {"total": 0, "offset": 0, "limit": limit, "images": []}
    q = str(q or "").strip().lower()
    offset = max(0, int(offset or 0))
    limit = max(1, min(300, int(limit or 120)))
    matched = []
    with open(OFFICIAL_IMAGE_MANIFEST, "r", encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            item = json.loads(line)
            filename = os.path.basename(item.get("png") or "")
            haystack = " ".join([
                str(item.get("index", "")),
                f"{int(item.get('index', 0)):04d}",
                str(item.get("resource_hash", "")),
                str(item.get("name_hash", "")),
                f"{item.get('width')}x{item.get('height')}",
                filename,
            ]).lower()
            if q and q not in haystack:
                continue
            matched.append({
                "index": item.get("index"),
                "resource_hash": item.get("resource_hash"),
                "name_hash": item.get("name_hash"),
                "type": item.get("type"),
                "width": item.get("width"),
                "height": item.get("height"),
                "file": filename,
                "url": image_url(filename),
            })
    return {
        "total": len(matched),
        "offset": min(offset, len(matched)),
        "limit": limit,
        "images": matched[offset:offset + limit],
    }


def guided_input_values(data=None):
    base = dict(GUIDED_STATE.get("inputs") or {})
    if isinstance(data, dict):
        incoming = data.get("inputs") if isinstance(data.get("inputs"), dict) else data
        for key, value in incoming.items():
            if value is not None:
                base[key] = value
    return {
        "currentStreet": str(base.get("currentStreet") or base.get("current_street") or "Текущая улица"),
        "nextStreet": str(base.get("nextStreet") or base.get("next_street") or "Следующая улица"),
        "distance": str(base.get("distance") or "80"),
        "progress": str(base.get("progress") or "0"),
        "speed": str(base.get("speed") or "60"),
    }


def resolve_check_value(value, inputs):
    mapping = {
        "$currentStreet": inputs["currentStreet"],
        "$nextStreet": inputs["nextStreet"],
        "$distance": inputs["distance"],
        "$progress": inputs["progress"],
        "$speed": inputs["speed"],
    }
    return mapping.get(value, value)


def resolve_check_payload(row, inputs):
    payload = row.get("payload")
    if not payload:
        return None
    resolved = {key: resolve_check_value(value, inputs) for key, value in payload.items()}
    action = str(resolved.get("action") or "").lower()
    if action in ("lane_combo", "lane_sweep", "maneuver"):
        resolved.setdefault("distance", inputs["distance"])
        resolved.setdefault("progress", inputs["progress"])
    if action == "route_start":
        resolved.setdefault("text", inputs["currentStreet"])
        resolved.setdefault("speed", inputs["speed"])
    if action == "speed_quick":
        resolved.setdefault("speed", inputs["speed"])
    return resolved


def guided_queue_for_mode(mode):
    mode = str(mode or "sendable")
    rows = rows_with_images()
    def in_group(row, prefix):
        return str(row.get("group") or "").startswith(prefix)
    if mode == "all":
        return [row["key"] for row in rows]
    if mode == "gray":
        return [row["key"] for row in rows if in_group(row, "01 ")]
    if mode == "yellow":
        return [row["key"] for row in rows if in_group(row, "02 ")]
    if mode == "signs":
        return [row["key"] for row in rows if in_group(row, "03 ")]
    if mode == "unknown":
        return [
            row["key"] for row in rows
            if not row.get("payload") or str(row.get("status") or "").startswith("need_") or row.get("status") == "future"
        ]
    if mode == "base":
        return [row["key"] for row in rows if in_group(row, "00 ")]
    return [row["key"] for row in rows if row.get("payload")]


def guided_row_by_key(key):
    for row in rows_with_images():
        if row["key"] == key:
            return row
    return None


def current_guided_row():
    queue = GUIDED_STATE.get("queue") or []
    if not queue:
        return None
    position = max(0, int(GUIDED_STATE.get("position") or 0))
    if position >= len(queue):
        GUIDED_STATE["position"] = len(queue)
        return None
    GUIDED_STATE["position"] = position
    return guided_row_by_key(queue[position])


def guided_payload():
    row = current_guided_row()
    if not row:
        return None, None
    inputs = guided_input_values()
    row = dict(row)
    row["resolved_payload"] = resolve_check_payload(row, inputs)
    return row, row["resolved_payload"]


def guided_status_payload(extra=None):
    row, _payload = guided_payload()
    queue = GUIDED_STATE.get("queue") or []
    payload = {
        "ok": True,
        "state": {
            "session_id": GUIDED_STATE.get("session_id"),
            "mode": GUIDED_STATE.get("mode"),
            "position": GUIDED_STATE.get("position") or 0,
            "total": len(queue),
            "awaiting": bool(GUIDED_STATE.get("awaiting")),
            "started_at": GUIDED_STATE.get("started_at"),
            "last_sent_at": GUIDED_STATE.get("last_sent_at"),
            "last_result_at": GUIDED_STATE.get("last_result_at"),
        },
        "current": row,
        "last_frames": GUIDED_STATE.get("last_frames") or [],
    }
    if extra:
        payload.update(extra)
    return payload


def guided_start(data):
    mode = str(data.get("mode") or "sendable")
    inputs = guided_input_values(data)
    queue = guided_queue_for_mode(mode)
    now = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    GUIDED_STATE.update({
        "session_id": "guided_" + time.strftime("%Y%m%d_%H%M%S"),
        "mode": mode,
        "queue": queue,
        "position": 0,
        "started_at": now,
        "last_sent_at": "",
        "last_result_at": "",
        "awaiting": False,
        "inputs": inputs,
        "last_frames": [],
    })
    return guided_status_payload()


def guided_move(data):
    inputs = guided_input_values(data)
    GUIDED_STATE["inputs"] = inputs
    queue = GUIDED_STATE.get("queue") or []
    if not queue:
        return guided_start({"mode": data.get("mode") or GUIDED_STATE.get("mode") or "sendable", "inputs": inputs})
    delta = int(data.get("delta") or 0)
    GUIDED_STATE["position"] = max(0, min(len(queue) - 1, int(GUIDED_STATE.get("position") or 0) + delta))
    GUIDED_STATE["awaiting"] = False
    return guided_status_payload()


def guided_send_current(data):
    inputs = guided_input_values(data)
    GUIDED_STATE["inputs"] = inputs
    row, payload = guided_payload()
    if not row:
        return guided_status_payload({"frames": []})
    sent = []
    if payload:
        action = str(payload.get("action") or "").lower()
        delay_s = 1.15 if action in ("sweep", "lane_sweep") else 0.06
        sent = send_frames(frames_for_request(payload), delay_s=delay_s)
    GUIDED_STATE["last_sent_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    GUIDED_STATE["awaiting"] = True
    GUIDED_STATE["last_frames"] = sent
    return guided_status_payload({"frames": sent})


def guided_save_result(data):
    inputs = guided_input_values(data)
    GUIDED_STATE["inputs"] = inputs
    row, payload = guided_payload()
    if not row:
        return guided_status_payload()
    result = str(data.get("result") or "").strip() or "unknown"
    note_text = str(data.get("note") or "").strip()
    position = int(GUIDED_STATE.get("position") or 0)
    save_note({
        "result": result,
        "queue": "guided:" + str(GUIDED_STATE.get("session_id") or ""),
        "index": position,
        "total": len(GUIDED_STATE.get("queue") or []),
        "case": {
            "id": "guided:" + row["key"],
            "title": row.get("check"),
            "group": row.get("group"),
            "status": row.get("status"),
            "official": row.get("official"),
            "teyes": row.get("teyes"),
            "expected": row.get("expected"),
            "payload": payload,
            "images": row.get("images") or [],
        },
        "distance": inputs["distance"],
        "progress": inputs["progress"],
        "human_label": row.get("check"),
        "note": note_text,
        "session_id": GUIDED_STATE.get("session_id"),
        "guided": True,
    })
    GUIDED_STATE["last_result_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    GUIDED_STATE["awaiting"] = False
    if bool(data.get("advance")):
        queue = GUIDED_STATE.get("queue") or []
        GUIDED_STATE["position"] = min(len(queue), position + 1)
    return guided_status_payload()


def safe_image_file(root, value):
    name = os.path.basename(unquote(str(value or "")))
    if not name or name.startswith("."):
        return ""
    path = os.path.abspath(os.path.join(root, name))
    root_abs = os.path.abspath(root)
    if not path.startswith(root_abs + os.sep):
        return ""
    return path


def status_payload():
    return {
        "ok": True,
        "serial": bridge.snapshot(),
        "notes_path": NOTES_PATH,
        "photo_dir": PHOTO_DIR,
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)

    def do_OPTIONS(self):
        send_json(self, 204, b"")

    def do_GET(self):
        try:
            parsed = urlparse(self.path)
            path = parsed.path
            if path == "/" or path.startswith("/index"):
                send_json(self, 200, BASIC_HTML.encode("utf-8"), "text/html; charset=utf-8")
            elif path.startswith("/trc-report"):
                send_json(self, 200, load_trc_report_html().encode("utf-8"), "text/html; charset=utf-8")
            elif path.startswith("/official-images/png/"):
                image_path = safe_image_file(OFFICIAL_IMAGE_PNG_DIR, path.rsplit("/", 1)[-1])
                if not image_path or not os.path.exists(image_path):
                    send_json(self, 404, {"ok": False, "error": "image not found"})
                else:
                    send_file(self, image_path, "image/png")
            elif path.startswith("/official-images/contact/"):
                image_path = safe_image_file(OFFICIAL_IMAGE_ROOT, path.rsplit("/", 1)[-1])
                if not image_path or not os.path.exists(image_path):
                    send_json(self, 404, {"ok": False, "error": "contact sheet not found"})
                else:
                    send_file(self, image_path, "image/jpeg")
            elif path.startswith("/api/ports"):
                send_json(self, 200, {"ok": True, "ports": list_ports()})
            elif path.startswith("/api/status"):
                send_json(self, 200, status_payload())
            elif path.startswith("/api/notes"):
                send_json(self, 200, {"ok": True, "path": NOTES_PATH, "photo_dir": PHOTO_DIR, "notes": load_notes()})
            elif path.startswith("/api/trc-analysis"):
                send_json(self, 200, {"ok": True, "path": TRC_ANALYSIS_JSON, "report": TRC_ANALYSIS_MD, "analysis": load_trc_analysis()})
            elif path.startswith("/api/check-rows"):
                send_json(self, 200, {"ok": True, "rows": rows_with_images()})
            elif path.startswith("/api/guided/status"):
                send_json(self, 200, guided_status_payload())
            elif path.startswith("/api/official-images"):
                query = parse_qs(parsed.query)
                payload = load_official_images(
                    q=(query.get("q") or [""])[0],
                    offset=(query.get("offset") or [0])[0],
                    limit=(query.get("limit") or [120])[0],
                )
                payload["ok"] = True
                payload["manifest"] = OFFICIAL_IMAGE_MANIFEST
                payload["root"] = OFFICIAL_IMAGE_ROOT
                send_json(self, 200, payload)
            elif path.startswith("/api/official"):
                payload = dict(OFFICIAL_NAV_SUMMARY)
                payload["ok"] = True
                payload["exists"] = os.path.isdir(OFFICIAL_NAV_ROOT)
                payload["image_root"] = OFFICIAL_IMAGE_ROOT
                payload["image_count"] = load_official_images(limit=1)["total"]
                send_json(self, 200, payload)
            elif path.startswith("/api/rx"):
                data = bridge.read_recent()
                send_json(self, 200, {"ok": True, "bytes": len(data), "hex": hex_bytes(data)})
            else:
                send_json(self, 404, {"ok": False, "error": "not found"})
        except Exception as exc:
            send_json(self, 500, {"ok": False, "error": str(exc)})

    def do_POST(self):
        try:
            parsed = urlparse(self.path)
            path = parsed.path
            data = read_body(self)
            if path.startswith("/api/open"):
                bridge.open(data.get("port"), int(data.get("baud") or 115200))
                send_json(self, 200, {"ok": True, "port": data.get("port"), "baud": int(data.get("baud") or 115200)})
            elif path.startswith("/api/close"):
                bridge.close()
                send_json(self, 200, {"ok": True})
            elif path.startswith("/api/guided/start"):
                send_json(self, 200, guided_start(data))
            elif path.startswith("/api/guided/move"):
                send_json(self, 200, guided_move(data))
            elif path.startswith("/api/guided/send"):
                send_json(self, 200, guided_send_current(data))
            elif path.startswith("/api/guided/result"):
                send_json(self, 200, guided_save_result(data))
            elif path.startswith("/api/send"):
                action = str(data.get("action") or "").lower()
                delay_s = 1.15 if action in ("sweep", "lane_sweep") else 0.06
                sent = send_frames(frames_for_request(data), delay_s=delay_s)
                send_json(self, 200, {"ok": True, "frames": sent})
            elif path.startswith("/api/note"):
                note, photo_path = save_note(data)
                send_json(self, 200, {"ok": True, "path": NOTES_PATH, "photo_path": photo_path, "note": note})
            else:
                send_json(self, 404, {"ok": False, "error": "not found"})
        except Exception as exc:
            send_json(self, 500, {"ok": False, "error": str(exc)})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8793)
    args = parser.parse_args()
    ensure_dirs()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"navi lane lab listening on http://{args.host}:{args.port}/", flush=True)
    try:
        server.serve_forever()
    finally:
        bridge.close()


if __name__ == "__main__":
    main()
