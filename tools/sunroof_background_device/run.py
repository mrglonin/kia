#!/usr/bin/env python3
"""Build signed instrumentation and test the real background roof path on an Android emulator.

Replaces USB port hardware and native ACC input only. No runQaScenario is called.
Requires an already built target APK/classes JAR, JDK17, Android36 SDK/build-tools37.
Installs only the fixture unless --app-apk is supplied. Emulator use only.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5570")
    parser.add_argument("--app-apk", type=Path)
    parser.add_argument("--classes", type=Path)
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--phase", choices=("full", "startup", "teyes", "sleep_audio", "main", "restart", "ui", "audio", "voice_preroll"), default="full")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This fixture changes USB/settings boundaries and may run only on an emulator")
    root = args.source.resolve()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    fixture = root / "tools/sunroof_background_device"
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    jdk = Path(os.environ.get("JAVA_HOME", str(root.parent / ".build-runtime/jdk-17.0.21+9/Contents/Home")))
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
    bt = sdk / "build-tools/37.0.0"
    android = sdk / "platforms/android-36/android.jar"
    app_classes = (args.classes or root / "app/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar").resolve()
    logs = []

    def run(command, name, check=True):
        arguments = list(map(str, command))
        try:
            process = subprocess.run(arguments, capture_output=True, text=True, env=env,
                                     timeout=120 if name.endswith("instrumentation") else 60)
        except subprocess.TimeoutExpired as error:
            def decoded(value):
                return value.decode(errors="replace") if isinstance(value, bytes) else value or ""
            process = subprocess.CompletedProcess(arguments, 124, decoded(error.stdout),
                                                   decoded(error.stderr) + "\nFixture command timed out\n")
        (out / (name + ".log")).write_text(process.stdout + process.stderr)
        logs.append({"name": name, "returncode": process.returncode})
        if check and process.returncode:
            print(process.stdout + process.stderr, file=sys.stderr)
            raise SystemExit(process.returncode)
        return process

    classes = out / "classes"
    dex = out / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    resource_apk = out / "resources.apk"
    run([bt / "aapt2", "link", "-I", android, "--manifest", fixture / "AndroidManifest.xml", "-o", resource_apk], "aapt")
    run([jdk / "bin/javac", "-source", "17", "-target", "17", "-encoding", "UTF-8", "-cp", str(android) + os.pathsep + str(app_classes), "-d", classes, fixture / "BackgroundInstrumentation.java"], "javac")
    fixture_jar = out / "fixture.jar"
    run([jdk / "bin/jar", "cf", fixture_jar, "-C", classes, "."], "jar")
    run([bt / "d8", "--lib", android, "--classpath", app_classes, "--min-api", "29", "--output", dex, fixture_jar], "d8")
    run([jdk / "bin/jar", "uf", resource_apk, "-C", dex, "classes.dex"], "pack")
    aligned = out / "aligned.apk"
    apk = out / "sunroof-background-instrumentation.apk"
    run([bt / "zipalign", "-f", "4", resource_apk, aligned], "zipalign")
    run([bt / "apksigner", "sign", "--ks", root / "signing/kia-debug-release.keystore", "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk, aligned], "sign")
    run([bt / "apksigner", "verify", "--verbose", apk], "verify")
    receipt = {"fixtureApkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "source": str(root), "classesSha256": hashlib.sha256(app_classes.read_bytes()).hexdigest(), "serial": args.serial}
    if not args.build_only:
        adb = [sdk / "platform-tools/adb", "-s", args.serial]
        if args.app_apk:
            run(adb + ["install", "-r", args.app_apk.resolve()], "install-app")
            receipt["appApkSha256"] = hashlib.sha256(args.app_apk.read_bytes()).hexdigest()
        run(adb + ["install", "-r", apk], "install-fixture")
        run(adb + ["shell", "appops", "set", "kia.app", "SYSTEM_ALERT_WINDOW", "allow"], "overlay-permission")
        phases = ("startup", "teyes", "main", "restart") if args.phase == "full" else (args.phase,)
        phase_results = []
        for phase in phases:
            run(adb + ["shell", "am", "force-stop", "kia.app"], phase + "-force-stop")
            run(adb + ["shell", "rm", "-rf", "/sdcard/Android/data/kia.app/files/sunroof-background-regression"], phase + "-clear-prior-fixture-evidence")
            process = run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "phase", phase,
                                 "kia.app.sunroof.backgroundtest/kia.app.sunroof.backgroundtest.BackgroundInstrumentation"], phase + "-instrumentation", check=False)
            if process.returncode == 124:
                run(adb + ["shell", "am", "force-stop", "kia.app"], phase + "-timeout-cleanup", check=False)
            for line in (process.stdout + process.stderr).splitlines():
                if line.startswith("INSTRUMENTATION_STATUS: stream=") or line.startswith("INSTRUMENTATION_CODE:"):
                    print(phase, line)
            device_out = out / ("device" if phase == "main" else "device-" + phase)
            device_out.mkdir(parents=True, exist_ok=True)
            run(adb + ["pull", "/sdcard/Android/data/kia.app/files/sunroof-background-regression/.", device_out], phase + "-pull-evidence", check=False)
            report = device_out / "report.json"
            if report.is_file():
                phase_result = json.loads(report.read_text())
                print("App", phase_result.get("appVersionCode"), phase, "assertions", phase_result.get("assertions"),
                      "failures", phase_result.get("failures"))
            else:
                phase_result = {"phase": phase, "failures": 1, "missingReport": True}
            phase_results.append(phase_result)
        run(adb + ["logcat", "-d", "-s", "Kia:I", "AndroidRuntime:E", "WindowManager:E"], "device-log", check=False)
        receipt["phases"] = phase_results
        receipt["result"] = {"failures": sum(result.get("failures", 1) for result in phase_results),
                             "assertions": sum(result.get("assertions", 0) for result in phase_results)}
        run(adb + ["shell", "am", "force-stop", "kia.app"], "cleanup-target", check=False)
    receipt["commands"] = logs
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("Artifacts:", out)
    raise SystemExit(1 if receipt.get("result", {}).get("failures", 0) else 0)


if __name__ == "__main__":
    main()
