#!/usr/bin/env python3
"""Run actual TPMS scheduler, alerts and adapter-health regression fixtures.

Requires Python 3, JAVA_HOME or a JDK on PATH, cached JUnit dependencies from the
Gradle unit-test task, and an Android SDK for the alert fixture. Set ANDROID_HOME
or ANDROID_SDK_ROOT, or pass --android-jar. No device or network is used.

The fixture programs compile real production classes with replacements only for
Android and external-service boundaries. They do not establish physical sensor,
USB, sound, display or head-unit sleep behavior.
"""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--android-jar", type=Path)
    args = parser.parse_args()
    source = args.source.expanduser().resolve()
    output = (args.output.expanduser().resolve() if args.output
              else Path(tempfile.mkdtemp(prefix="kia-tpms-regression-")))
    output.mkdir(parents=True, exist_ok=True)
    programs = Path(__file__).resolve().parent / "tpms_regression"
    runs = []
    for name in ("scheduler", "alerts", "health"):
        destination = output / name
        destination.mkdir(exist_ok=True)
        command = [sys.executable, str(programs / (name + ".py")),
                   "--source", str(source), "--output", str(destination)]
        if name == "alerts" and args.android_jar is not None:
            command += ["--android-jar", str(args.android_jar.expanduser().resolve())]
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT)
        (destination / "runner.log").write_text(result.stdout, encoding="utf-8")
        receipt_path = destination / "receipt.json"
        receipt = json.loads(receipt_path.read_text()) if receipt_path.exists() else None
        runs.append({"name": name, "exit_code": result.returncode, "receipt": receipt})
        print(f"{name}: {'PASS' if result.returncode == 0 else 'FAIL'}", flush=True)
        if result.returncode:
            print(result.stdout, end="", flush=True)
            break
    passed = len(runs) == 3 and all(run["exit_code"] == 0 for run in runs)
    summary = {"status": "PASS" if passed else "FAIL", "source": str(source),
               "runs": runs, "physical_head_unit_tested": False}
    summary_path = output / "receipt.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Receipt: {summary_path}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
