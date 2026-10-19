#!/usr/bin/env python3
"""Reproduce TPMS alert fixes against actual source with deterministic Android boundaries.

Requires Python 3, a JDK, an Android SDK platform jar and cached JUnit 4.13.2 /
Hamcrest 1.3 jars (or explicit jar arguments). No device or APK build is used.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

FIXTURES = {'android/os/Build.java': 'package android.os; public class Build {public static class VERSION '
                          '{public static int SDK_INT=33;}}',
 'android/os/Looper.java': 'package android.os; public class Looper {private static final Looper '
                           'MAIN=new Looper(); public static boolean onMain=true; public static '
                           'Looper getMainLooper(){return MAIN;} public static Looper '
                           'myLooper(){return onMain?MAIN:null;}}',
 'android/os/Handler.java': 'package android.os; public class Handler {public static boolean '
                            'autoRun=true; public static final java.util.List<Runnable> '
                            'pending=new java.util.ArrayList<>(); public Handler(Looper l){} '
                            'public boolean post(Runnable r){if(autoRun)r.run();else '
                            'pending.add(r);return true;} public boolean postDelayed(Runnable '
                            'r,long d){return true;} public void removeCallbacks(Runnable '
                            'r){pending.removeIf(x->x==r);} public void '
                            'removeCallbacksAndMessages(Object token){pending.clear();} public '
                            'static void '
                            'drain(){while(!pending.isEmpty())pending.remove(0).run();}}',
 'android/text/TextUtils.java': 'package android.text; public class TextUtils {public static '
                                'boolean isEmpty(CharSequence c){return c==null||c.length()==0;}}',
 'android/content/Context.java': 'package android.content; public class Context {public static '
                                 'final String '
                                 'NOTIFICATION_SERVICE="notification",WINDOW_SERVICE="window"; '
                                 'public Context getApplicationContext(){return this;} public '
                                 'Object getSystemService(String s){return '
                                 's.equals(NOTIFICATION_SERVICE)?new '
                                 'android.app.NotificationManager():null;} public int '
                                 'checkSelfPermission(String p){return 0;} public '
                                 'android.content.pm.ApplicationInfo getApplicationInfo(){return '
                                 'new android.content.pm.ApplicationInfo();} public '
                                 'android.content.res.Resources getResources(){return null;} '
                                 'public Context createDisplayContext(android.view.Display '
                                 'd){return this;} public Context createWindowContext(int '
                                 't,android.os.Bundle b){return this;}}',
 'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class '
                                            'ApplicationInfo {public int icon=1;}',
 'android/content/Intent.java': 'package android.content; public class Intent {public static final '
                                'int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_SINGLE_TOP=2; public '
                                'Intent(Context c,Class<?> k){} public Intent addFlags(int '
                                'f){return this;}}',
 'android/app/NotificationManager.java': 'package android.app; public class NotificationManager '
                                         '{public static final int IMPORTANCE_LOW=2; public static '
                                         'int notifications,cancels; public void '
                                         'createNotificationChannel(NotificationChannel c){} '
                                         'public void notify(int id,Notification '
                                         'n){notifications++;} public void cancel(int '
                                         'id){cancels++;}}',
 'android/app/NotificationChannel.java': 'package android.app; public class NotificationChannel '
                                         '{public NotificationChannel(String id,CharSequence '
                                         'name,int importance){} public void '
                                         'enableVibration(boolean b){} public void '
                                         'setSound(android.net.Uri u,android.media.AudioAttributes '
                                         'a){}}',
 'android/app/PendingIntent.java': 'package android.app; public class PendingIntent {public static '
                                   'final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2; public '
                                   'static PendingIntent getActivity(android.content.Context c,int '
                                   'id,android.content.Intent i,int flags){return new '
                                   'PendingIntent();}}',
 'android/app/Notification.java': 'package android.app; public class Notification {public static '
                                  'final String CATEGORY_STATUS="status"; public static final int '
                                  'PRIORITY_LOW=-1; public static class BigTextStyle {public '
                                  'BigTextStyle bigText(CharSequence t){return this;}} public '
                                  'static class Builder {public Builder(android.content.Context '
                                  'c){} public Builder(android.content.Context c,String ch){} '
                                  'public Builder setSmallIcon(int i){return this;} public Builder '
                                  'setContentTitle(CharSequence c){return this;} public Builder '
                                  'setContentText(CharSequence c){return this;} public Builder '
                                  'setStyle(BigTextStyle s){return this;} public Builder '
                                  'setCategory(String s){return this;} public Builder '
                                  'setPriority(int p){return this;} public Builder setDefaults(int '
                                  'd){return this;} public Builder setOnlyAlertOnce(boolean '
                                  'b){return this;} public Builder setAutoCancel(boolean b){return '
                                  'this;} public Builder setShowWhen(boolean b){return this;} '
                                  'public Builder setWhen(long t){return this;} public Builder '
                                  'setContentIntent(PendingIntent p){return this;} public '
                                  'Notification build(){return new Notification();}}}',
 'android/provider/Settings.java': 'package android.provider; public class Settings {public static '
                                   'boolean canDrawOverlays(android.content.Context c){return '
                                   'false;}}',
 'android/media/MediaPlayer.java': 'package android.media; public class MediaPlayer {public static '
                                   'int creates,starts,stops; boolean playing; public static '
                                   'MediaPlayer create(android.content.Context c,int '
                                   'id){creates++;return new MediaPlayer();} public void '
                                   'setLooping(boolean l){} public void '
                                   'start(){starts++;playing=true;} public void '
                                   'stop(){stops++;playing=false;} public void release(){} public '
                                   'boolean isPlaying(){return playing;}}',
 'kia/app/R.java': 'package kia.app; public class R {public static class raw {public static int '
                   'tpms6_error=1;}}',
 'kia/app/entry/MainActivity.java': 'package kia.app.entry; public class MainActivity {}',
 'kia/app/core/AppLog.java': 'package kia.app.core; public class AppLog {public static final '
                             'java.util.List<String> lines=new java.util.ArrayList<>(); public '
                             'static void line(android.content.Context c,String s){lines.add(s);}}',
 'kia/app/core/settings/AppSettings.java': 'package kia.app.core.settings; public class '
                                           'AppSettings {public static boolean '
                                           'alerts=true,sound=true; public static int '
                                           'lowPressure=220,highPressure=280,lowTemp=-20,highTemp=70; '
                                           'public static boolean '
                                           'tpmsAlertsEnabled(android.content.Context c){return '
                                           'alerts;} public static boolean '
                                           'tpmsSoundAlertsEnabled(android.content.Context '
                                           'c){return sound;} public static int '
                                           'tpmsLowPressureKpa(android.content.Context c){return '
                                           'lowPressure;} public static int '
                                           'tpmsHighPressureKpa(android.content.Context c){return '
                                           'highPressure;} public static int '
                                           'tpmsLowTempC(android.content.Context c){return '
                                           'lowTemp;} public static int '
                                           'tpmsHighTempC(android.content.Context c){return '
                                           'highTemp;}}',
 'kia/app/tpms/TpmsAuditHarness.java': 'package kia.app.tpms;\n'
                                       'import android.content.Context;\n'
                                       'import android.app.NotificationManager;\n'
                                       'import android.media.MediaPlayer;\n'
                                       'import android.os.Handler;\n'
                                       'import android.os.Looper;\n'
                                       'import android.os.SystemClock;\n'
                                       'import kia.app.core.AppLog;\n'
                                       'import kia.app.core.model.TpmsState;\n'
                                       'import kia.app.core.settings.AppSettings;\n'
                                       'public final class TpmsAuditHarness {\n'
                                       ' static int passed;\n'
                                       ' static final Context app=new Context();\n'
                                       ' static void eq(String name,Object actual,Object '
                                       'expected){if(!actual.equals(expected))throw new '
                                       'AssertionError(name+" actual="+actual+" '
                                       'expected="+expected);passed++;System.out.println("PASS '
                                       '"+name+" -> "+actual);}\n'
                                       ' static TpmsState state(int pressure,int temp,int '
                                       'flags){return '
                                       'TpmsState.empty().withWheelAt(0,pressure,temp,flags,"test-hardware",System.currentTimeMillis());}\n'
                                       ' static void severity(String name,int p,int t,int f,int '
                                       'expected){eq(name,TpmsAlertController.warningSeverity(app,p,t,f),expected);}\n'
                                       ' static void reset(TpmsAlertController '
                                       'alerts){Handler.autoRun=true;Handler.drain();alerts.clear();MediaPlayer.creates=0;MediaPlayer.starts=0;MediaPlayer.stops=0;NotificationManager.notifications=0;AppLog.lines.clear();}\n'
                                       ' public static void main(String[] args){\n'
                                       '  severity("normal",240,25,0,0);\n'
                                       '  severity("critical pressure",180,25,0,2);\n'
                                       '  severity("critical temperature",240,90,0,2);\n'
                                       '  severity("battery cannot mask critical low '
                                       'pressure",180,25,0x10,2);\n'
                                       '  severity("battery cannot mask critical high '
                                       'pressure",320,25,0x10,2);\n'
                                       '  severity("battery cannot mask critical '
                                       'temperature",240,90,0x10,2);\n'
                                       '  severity("mild low pressure cannot mask critical high '
                                       'temperature",210,90,0,2);\n'
                                       '  severity("mild high pressure cannot mask critical '
                                       'cold",290,-40,0,2);\n'
                                       '  eq("critical temperature selected as displayed '
                                       'cause",TpmsAlertController.warningState(app,210,90,0),3);\n'
                                       '  TpmsState '
                                       'multiple=state(210,25,0).withWheelAt(1,240,90,0,"hardware",System.currentTimeMillis());\n'
                                       '  eq("global notification title selects critical later '
                                       'wheel",TpmsAlertController.firstWarning(app,multiple),3);\n'
                                       '  eq("identity independent of raw pressure and '
                                       'temperature",TpmsAlertController.warningKey(app,state(210,25,0)),TpmsAlertController.warningKey(app,state(211,26,0)));\n'
                                       '  eq("detail readings still '
                                       'update",TpmsAlertController.warningDetails(app,state(211,26,0)).contains("2.11 '
                                       'bar / 26C"),true);\n'
                                       '  TpmsAlertController '
                                       'alerts=TpmsAlertController.get(app);TpmsWarningOverlayController '
                                       'overlay=TpmsWarningOverlayController.get(app);\n'
                                       '  reset(alerts);alerts.apply(state(210,25,0));eq("first '
                                       'alarm starts sound",MediaPlayer.creates,1);eq("first alarm '
                                       'notification",NotificationManager.notifications,1);\n'
                                       '  overlay.dismissCurrent();eq("dismiss stops '
                                       'sound",MediaPlayer.stops,1);\n'
                                       '  alerts.apply(state(211,26,0));eq("1kPa and1C do not '
                                       'restart dismissed sound",MediaPlayer.creates,1);eq("1kPa '
                                       'and1C do not '
                                       'renotify",NotificationManager.notifications,1);eq("1kPa '
                                       'and1C do not repeat '
                                       'log",AppLog.lines.stream().filter(x->x.startsWith("TPMS '
                                       'alert:")).count(),1L);\n'
                                       '  '
                                       'alerts.apply(state(220,25,0));SystemClock.now+=1000;alerts.apply(state(219,25,0));eq("brief '
                                       'threshold recovery does not rearm '
                                       'sound",MediaPlayer.creates,1);eq("brief threshold recovery '
                                       'does not renotify",NotificationManager.notifications,1);\n'
                                       '  alerts.apply(state(180,25,0));eq("critical escalation '
                                       'reopens sound '
                                       'immediately",MediaPlayer.creates,2);eq("critical '
                                       'escalation renotifies '
                                       'immediately",NotificationManager.notifications,2);\n'
                                       '  '
                                       'overlay.dismissCurrent();alerts.apply(state(210,25,0));eq("critical '
                                       'deescalation stays '
                                       'dismissed",MediaPlayer.creates,2);eq("critical '
                                       'deescalation does not '
                                       'renotify",NotificationManager.notifications,2);\n'
                                       '  '
                                       'reset(alerts);alerts.apply(state(210,90,0));overlay.dismissCurrent();alerts.apply(state(210,25,0));eq("primary '
                                       'switches to preexisting mild cause without '
                                       'reopening",MediaPlayer.creates,1);eq("primary switch does '
                                       'not renotify",NotificationManager.notifications,1);\n'
                                       '  '
                                       'alerts.apply(state(210,25,0).withWheelAt(1,210,25,0,"hardware",System.currentTimeMillis()));eq("new '
                                       'warning wheel reopens",MediaPlayer.creates,2);\n'
                                       '  '
                                       'overlay.dismissCurrent();alerts.apply(state(210,25,0).withWheelAt(1,210,75,0,"hardware",System.currentTimeMillis()));eq("new '
                                       'temperature cause reopens",MediaPlayer.creates,3);\n'
                                       '  '
                                       'reset(alerts);alerts.apply(state(210,25,0));overlay.dismissCurrent();alerts.apply(state(240,25,0));SystemClock.now+=30000;alerts.apply(state(210,25,0));eq("30s '
                                       'genuine recovery rearms sound",MediaPlayer.creates,2);\n'
                                       '  '
                                       'reset(alerts);alerts.apply(state(210,25,0));SystemClock.now+=59999;alerts.apply(state(211,25,0));eq("repeat '
                                       'before60s '
                                       'suppressed",NotificationManager.notifications,1);SystemClock.now++;alerts.apply(state(211,25,0));eq("repeat '
                                       'at60s permitted",NotificationManager.notifications,2);\n'
                                       '  '
                                       'reset(alerts);alerts.apply(state(210,25,0));alerts.clear();eq("clear '
                                       'stops active sound",MediaPlayer.stops,1);eq("clear cancels '
                                       'notification",NotificationManager.cancels>0,true);\n'
                                       '  '
                                       'reset(alerts);Handler.autoRun=false;alerts.apply(state(210,25,0));Runnable '
                                       'stale=Handler.pending.remove(0);alerts.clear();stale.run();Handler.drain();eq("dequeued '
                                       'stale apply cannot restart sound after '
                                       'clear",MediaPlayer.creates,0);\n'
                                       '  '
                                       'Handler.autoRun=true;alerts.apply(state(210,25,0));eq("fresh '
                                       'alarm can restart after clear",MediaPlayer.creates,1);\n'
                                       '  '
                                       'reset(alerts);Handler.autoRun=false;Looper.onMain=false;alerts.apply(state(210,25,0));alerts.clear();eq("off-main '
                                       'clear leaves only cleanup '
                                       'queued",Handler.pending.size(),1);Looper.onMain=true;Handler.drain();eq("queued '
                                       'apply removed by off-main '
                                       'clear",MediaPlayer.creates,0);Handler.autoRun=true;\n'
                                       '  '
                                       'reset(alerts);AppSettings.sound=false;alerts.apply(state(210,25,0));eq("sound '
                                       'disabled '
                                       'respected",MediaPlayer.creates,0);AppSettings.sound=true;alerts.apply(state(210,25,0));eq("sound '
                                       'reenabled for undismissed '
                                       'warning",MediaPlayer.creates,1);\n'
                                       '  '
                                       'AppSettings.alerts=false;alerts.apply(state(210,25,0));eq("alerts '
                                       'disabled stops '
                                       'sound",MediaPlayer.stops,1);AppSettings.alerts=true;\n'
                                       '  '
                                       'reset(alerts);alerts.apply(state(210,25,0));overlay.dismissCurrent();AppSettings.alerts=false;alerts.apply(state(210,25,0));AppSettings.alerts=true;alerts.apply(state(210,25,0));eq("explicit '
                                       'alert disable and reenable resets '
                                       'dismissal",MediaPlayer.creates,2);eq("explicit alert '
                                       'reenable emits fresh '
                                       'notification",NotificationManager.notifications,2);\n'
                                       '  eq("explicit stale rejects '
                                       'warnings",TpmsAlertController.warningSeverity(app,state(180,90,0).withWheelStale(0,"empty",System.currentTimeMillis()),0),0);\n'
                                       '  alerts.clear();\n'
                                       '  System.out.println("RESULT passed="+passed+" '
                                       'failed=0");\n'
                                       ' }\n'
                                       '}\n',
 'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long '
                                'now=1000; public static long elapsedRealtime(){return now;}}'}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def existing_file(value, label):
    path = Path(value).expanduser().resolve()
    if not path.is_file():
        raise ValueError(f"{label} not found: {path}")
    return path


def find_android_jar(explicit):
    if explicit:
        return existing_file(explicit, "Android jar")
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(name)
        if not root:
            continue
        jars = list((Path(root).expanduser() / "platforms").glob("android-*/android.jar"))
        if jars:
            return max(jars, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.name)))).resolve()
    raise ValueError("Set ANDROID_HOME / ANDROID_SDK_ROOT or pass --android-jar.")


def find_cached_jar(explicit, filename):
    if explicit:
        return existing_file(explicit, filename)
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).expanduser()
    jars = sorted((cache / "caches").rglob(filename))
    if not jars:
        option = "--junit-jar" if filename.startswith("junit") else "--hamcrest-jar"
        raise ValueError(f"{filename} not cached. Run the unit tests once or pass {option}.")
    return jars[0].resolve()


def jdk_tools():
    suffix = ".exe" if os.name == "nt" else ""
    home = os.environ.get("JAVA_HOME")
    if home:
        java = Path(home).expanduser() / "bin" / ("java" + suffix)
        javac = Path(home).expanduser() / "bin" / ("javac" + suffix)
    else:
        java_path, javac_path = shutil.which("java"), shutil.which("javac")
        if not java_path or not javac_path:
            raise ValueError("Set JAVA_HOME to a JDK or put java and javac on PATH.")
        java, javac = Path(java_path), Path(javac_path)
    if not java.is_file() or not javac.is_file():
        raise ValueError("JAVA_HOME must point to a JDK containing bin/java and bin/javac.")
    return java, javac


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--android-jar")
    parser.add_argument("--junit-jar")
    parser.add_argument("--hamcrest-jar")
    args = parser.parse_args()
    source = args.source.expanduser().resolve()
    output = args.output.expanduser().resolve()
    java, javac = jdk_tools()
    android = find_android_jar(args.android_jar)
    junit = find_cached_jar(args.junit_jar, "junit-4.13.2.jar")
    hamcrest = find_cached_jar(args.hamcrest_jar, "hamcrest-core-1.3.jar")
    production = [source / "app/app/src/main/java" / name for name in (
        "kia/app/tpms/TpmsAlertController.java",
        "kia/app/tpms/TpmsWarningOverlayController.java",
        "kia/app/tpms/TpmsWarningPolicy.java",
        "kia/app/tpms/TpmsAlertLifecycle.java",
        "kia/app/core/model/TpmsState.java",
    )]
    tests = [source / "app/app/src/test/java/kia/app/tpms" / name for name in (
        "TpmsWarningPolicyTest.java", "TpmsAlertLifecycleTest.java",
    )]
    for path in production + tests:
        existing_file(path, "Source file")
    fixtures, classes = output / "fixture-src", output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    fixture_files = []
    for name, content in FIXTURES.items():
        path = fixtures / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content + "\n", encoding="utf-8")
        fixture_files.append(path)
    classpath = os.pathsep.join(map(str, (classes, android, junit, hamcrest)))
    command = [str(javac), "--release", "17", "-cp", classpath, "-d", str(classes)]
    compiled = subprocess.run(command + list(map(str, fixture_files + production + tests)),
                              text=True, capture_output=True)
    (output / "compile.txt").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
    if compiled.returncode:
        print(compiled.stdout + compiled.stderr, end="")
        return compiled.returncode
    runs, outputs = [], []
    entries = [
        ["org.junit.runner.JUnitCore", "kia.app.tpms.TpmsWarningPolicyTest",
         "kia.app.tpms.TpmsAlertLifecycleTest"],
        ["kia.app.tpms.TpmsAuditHarness"],
    ]
    for entry in entries:
        result = subprocess.run([str(java), "-cp", classpath] + entry, text=True, capture_output=True)
        text = result.stdout + result.stderr
        print(text, end="")
        outputs.append(text)
        runs.append({"entry": entry, "exit_code": result.returncode})
    combined = "\n".join(outputs)
    (output / "results.txt").write_text(combined, encoding="utf-8")
    count = re.search(r"RESULT passed=(\d+) failed=(\d+)", combined)
    receipt = {
        "source": str(source),
        "production_files": {str(p.relative_to(source)): sha256(p) for p in production},
        "test_files": {str(p.relative_to(source)): sha256(p) for p in tests},
        "runner_sha256": sha256(Path(__file__).resolve()),
        "android_jar": str(android),
        "runs": runs,
        "additional_checks": dict(zip(("passed", "failed"), map(int, count.groups()))) if count else None,
        "scope": "Actual production severity, stable identity, notification and overlay/audio lifecycle.",
        "limitations": [
            "Android APIs, settings, notification and audio are deterministic fixtures.",
            "No APK build, device, ADB, real notification/audio, network or USB actions.",
            "Queued and already-dequeued callbacks are exercised in a deterministic Handler fixture.",
        ],
    }
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return 1 if any(run["exit_code"] for run in runs) else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(f"TPMS alert regression setup failed: {error}", file=sys.stderr)
        sys.exit(2)
