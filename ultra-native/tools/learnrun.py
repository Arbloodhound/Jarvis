#!/usr/bin/env python3
"""learnrun — does Ultra get better at real tasks on a real phone by learning from its own mistakes?

Drives the connected phone through a list of everyday tasks, each in a fresh chat, and
records per task what the brain did: tool calls, failed calls, gate blocks, lessons served,
lessons learned, and whether the run finished cleanly. Run the same list again and the
difference is what the phone's experience store bought.

    python3 tools/learnrun.py run --pass A tasks.txt     # one task per line
    python3 tools/learnrun.py report

Results: tools/learnrun/results.jsonl (gitignored with the rest of tools/*/ output).
Uses tools/uitap.sh; never uninstalls, never touches the app's data.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "learnrun"


def sh(cmd: str, timeout: int = 60) -> str:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout).stdout


def screen() -> str:
    return sh("adb shell uiautomator dump /sdcard/_ui.xml >/dev/null; adb shell cat /sdcard/_ui.xml")


def tap(text: str) -> bool:
    return subprocess.run(["bash", str(HERE / "uitap.sh"), text], capture_output=True, timeout=60).returncode == 0


def ask(task: str, timeout: int = 150) -> dict:
    sh("adb shell input keyevent KEYCODE_HOME")
    sh("adb shell am start -n com.agent.ultra/.MainActivity")
    time.sleep(2.5)
    # A gate card left open by the previous task hides the input; cancel it (never confirm).
    if "The policy gate paused this action" in screen():
        tap("Cancel")
        time.sleep(1.5)
    if tap("☰"):
        time.sleep(1)
        tap("+ New chat")
        time.sleep(1)
    sh("adb logcat -c")
    # Find the text field by its class, not its hint: leftover text hides the hint.
    pat = r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    m = re.search(pat, screen())
    if not m:                                  # a dialog or another app on top: back out, reopen, retry once
        sh("adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_HOME")
        sh("adb shell am start -n com.agent.ultra/.MainActivity")
        time.sleep(3)
        if "The policy gate paused this action" in screen():
            tap("Cancel"); time.sleep(1.5)
        m = re.search(pat, screen())
    if not m:
        return {"error": "input not found"}
    x1, y1, x2, y2 = map(int, m.groups())
    sh(f"adb shell input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(0.5)
    sh("adb shell input keyevent KEYCODE_MOVE_END " + " ".join(["KEYCODE_DEL"] * 120))
    time.sleep(0.5)
    esc = task.replace("'", "'\\''").replace(" ", "%s")
    sh(f"adb shell \"input text '{esc}'\"")
    time.sleep(0.8)
    if not tap("Send"):
        return {"error": "send not found"}
    t0 = time.time()
    log = ""
    while time.time() - t0 < timeout:
        time.sleep(3)
        log = sh("adb logcat -d -s UltraBrain:* UltraGate:* UltraLearn:*", timeout=30)
        if "RUN COMPLETE" in log or ("UltraGate: BLOCK" in log and "The policy gate paused" in screen()):
            time.sleep(1.5)
            log = sh("adb logcat -d -s UltraBrain:* UltraGate:* UltraLearn:*", timeout=30)
            break
    lines = [re.sub(r"^[0-9-]+ [0-9:.]+ +\d+ +\d+ [A-Z] ", "", l) for l in log.splitlines()]
    calls = [l.split("TOOL CALL: ", 1)[1] for l in lines if "TOOL CALL: " in l]
    fails = [l.split("TOOL RESULT (fail): ", 1)[1][:160] for l in lines if "TOOL RESULT (fail)" in l]
    blocks = [l.split("BLOCK ", 1)[1][:160] for l in lines if "UltraGate: BLOCK" in l]
    served = [l.split("LESSONS SERVED: ", 1)[1] for l in lines if "LESSONS SERVED" in l]
    hinted = any("MEMORY HINT injected" in l for l in lines)   # task memory: a past success for this request
    learned = [l.split("LEARNED ", 1)[1][:220] for l in lines if "LEARNED " in l]
    m = [l for l in lines if "MEMORY:" in l]
    ok = bool(m) and ("recorded \"" in m[-1] or "task succeeded=true" in m[-1])
    paused = "The policy gate paused this action" in screen()
    return {"secs": round(time.time() - t0), "complete": "RUN COMPLETE" in log, "clean": ok, "paused": paused,
            "calls": calls, "fails": fails, "blocks": blocks, "served": served, "hinted": hinted, "learned": learned,
            "final": next((l for l in reversed(lines) if "FINAL TEXT" in l), "")}


def run(tasks: list[str], pass_id: str):
    OUT.mkdir(exist_ok=True)
    for i, t in enumerate(tasks, 1):
        r = ask(t)
        r.update({"pass": pass_id, "task": t, "i": i})
        with (OUT / "results.jsonl").open("a") as f:
            f.write(json.dumps(r) + "\n")
        print(f"[{pass_id} {i}/{len(tasks)}] clean={r.get('clean')} calls={len(r.get('calls', []))} "
              f"fails={len(r.get('fails', []))} served={len(r.get('served', []))} learned={len(r.get('learned', []))}  {t}", flush=True)
    sh("adb shell input keyevent KEYCODE_HOME")


def report():
    rows = [json.loads(l) for l in (OUT / "results.jsonl").read_text().splitlines()]
    for p in sorted({r["pass"] for r in rows}):
        rs = [r for r in rows if r["pass"] == p]
        n = len(rs)
        print(f"pass {p}: {n} tasks | clean {sum(r.get('clean', False) for r in rs)}/{n} | "
              f"tool calls {sum(len(r.get('calls', [])) for r in rs)} | failed calls {sum(len(r.get('fails', [])) for r in rs)} | "
              f"lessons served in {sum(bool(r.get('served')) for r in rs)} | learned {sum(len(r.get('learned', [])) for r in rs)} | "
              f"secs {sum(r.get('secs', 0) for r in rs)}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run"); r.add_argument("--pass", dest="p", required=True); r.add_argument("file")
    sub.add_parser("report")
    a = ap.parse_args()
    if a.cmd == "run":
        run([l.strip() for l in Path(a.file).read_text().splitlines() if l.strip() and not l.startswith("#")], a.p)
        report()
    else:
        report()
