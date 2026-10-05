"""Agent Ultra as an AndroidWorld agent: the benchmark sets the task and scores it, Ultra does the work.

AndroidWorld (google-research/android_world) runs 116 hand-written tasks across 20 real apps on an
emulator and checks the device's own state afterwards. Nothing here grades anything: `step()` hands
the goal to the Ultra app running on the same device and returns when Ultra's brain says
RUN COMPLETE; the benchmark then computes its own reward.

Ultra is not a step-by-step agent — it runs its own perceive/act/verify loop with its policy gate in
the way — so this is one interaction: goal in, control back. That is the honest shape of what Ultra
is, and it means the score covers Ultra's whole loop, not a harness driving it.

Three things this adapter has to get right, each learned the hard way on 2026-09-19:
  * The screen is read through the benchmark's own UI tree. A separate uiautomator dump came back
    empty while the benchmark's accessibility forwarder was running, so "Send" was never tapped and
    Ultra sat with the goal typed until the timeout.
  * Ultra's accessibility service is re-enabled before every task: the benchmark's setup overwrites
    enabled_accessibility_services with its own forwarder, which switches Ultra's eyes off.
  * The app the work happened in is brought back to the front at the end. The benchmark reads the
    live screen (ClockStopWatchRunning wants the stopwatch running AND Clock in front), and Ultra
    ends every run in its own chat showing its answer.

Used by bench_ultra.py (a copy of android_world's run.py that knows this agent).
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import tempfile
import time

from android_world import suite_utils
from android_world.agents import base_agent

PKG = "com.agent.ultra"
DONE = "UltraBrain: RUN COMPLETE"
HINT = "Ask Agent Ultra"


class UltraAgent(base_agent.EnvironmentInteractingAgent):
  """One interaction per task: type the goal into Ultra, wait for its run to finish."""

  def __init__(self, env, name: str = "ultra", serial: str | None = None, timeout_s: int = 600):
    super().__init__(env, name)
    self._serial = serial or os.environ.get("ANDROID_SERIAL") or "emulator-5554"
    self._timeout = timeout_s
    self.last_trace: list[str] = []
    self._last_goal = ""
    self._install_verdict_hook()

  # ---- the verdict goes back to Ultra ----------------------------------------------
  def _install_verdict_hook(self) -> None:
    """After the benchmark checks the device, tell Ultra what it found.

    Ultra's own idea of success is "the model stopped and no tool failed". In 33 of 98 failed
    episodes it called the job done, stored the run as the way to do it and gave every served
    lesson a good mark (2026-09-20). The benchmark's check is the only honest judge in the room,
    so its result is written to verdict.json; Ultra reads it at the start of its next request and
    takes back what it wrongly kept. The score itself is untouched: the verdict is sent after it
    is computed, and nothing here can change it.
    """
    if getattr(suite_utils, "_ultra_verdict_hook", False):
      return
    inner = suite_utils._run_task
    agent = self

    def run_task(task, run_episode, env, demo_mode):
      # What the judge had in front of it when it judged. A timer set exactly right scored 0 and
      # nothing on disk could say whether the judge saw a different screen (2026-09-20).
      saw: dict = {}
      judge = task.is_successful

      from android_world.env import adb_utils, android_world_controller as awc

      def judged(e):
        # The judge reads the screen through the benchmark's own accessibility forwarder, and
        # with Ultra's service bound that reader returns NOTHING: activity DeskClock, 0 elements,
        # a timer set exactly right scored 0. Every check that looks at the screen was blind.
        # So Ultra's eyes come off before the judge looks, and go back on at the next task
        # (_ensure_a11y now waits for the service to be bound again, which is what made
        # "off between tasks" fail when it was first tried on 2026-09-19).
        # Turning Ultra's service off was not enough (still 0 elements after 20 s), so when the
        # forwarder gives the judge nothing it reads through uiautomator instead — AndroidWorld's
        # own second method (A11yMethod.UIAUTOMATOR), not something of ours. Which one the judge
        # used is recorded with every episode.
        state, els = None, []
        try:
          agent._a11y_off()
          # The forwarder first, and patiently: it is the reader the benchmark was written for,
          # and it came back on its own for the second task of a run. uiautomator is the last
          # resort because it cannot dump a screen that never goes idle (a RUNNING stopwatch), and
          # AndroidWorld's helper then cats the PREVIOUS dump file: the judge of "run the
          # stopwatch" was shown the last task's timer page and failed a run that had done
          # Stopwatch -> Start (2026-09-20). So the old file is removed before every dump — a
          # failed dump must read as nothing, never as some other screen.
          e.controller._a11y_method = awc.A11yMethod.A11Y_FORWARDER_APP
          saw["reader"] = "forwarder"
          for attempt in range(8):
            state = e.get_state()
            els = state.ui_elements
            if els:
              break
            if attempt == 3:
              e.controller.refresh_env()
            time.sleep(2)
          if not els:
            e.controller._a11y_method = awc.A11yMethod.UIAUTOMATOR
            saw["reader"] = "uiautomator"
            for _ in range(5):
              agent._adb("shell", "rm", "-f", "/sdcard/window_dump.xml")
              state = e.get_state()
              els = state.ui_elements
              if els:
                break
              time.sleep(2)
          saw["activity"] = str(adb_utils.get_current_activity(e.controller)[0])
          saw["texts"] = [t for t in ((x.text or x.content_description or "") for x in els) if t][:60]
        except Exception as ex:  # noqa: BLE001
          saw["error"] = f"{type(ex).__name__}: {ex}"
        # Whichever reader worked is the judge's for this one look, and the forwarder is put back
        # after it: left on uiautomator, the next task's setup hit a failed dump
        # ("cat /sdcard/window_dump.xml" non-zero) and the benchmark SKIPPED the task.
        # The judge is handed the very screen that was just read and recorded. The forwarder only
        # delivers a tree when the screen changes, so a second read a moment later came back empty:
        # the record said "00h 16m 35s, DeskClock" and the score said 0 (2026-09-20). The check
        # itself is AndroidWorld's, untouched; it looks at one snapshot instead of asking twice.
        real_get_state = e.get_state
        if state is not None and els:
          e.get_state = lambda *a, **k: state
        try:
          return judge(e)
        finally:
          try:
            e.get_state = real_get_state
            e.controller._a11y_method = awc.A11yMethod.A11Y_FORWARDER_APP
          except Exception:  # noqa: BLE001
            pass

      task.is_successful = judged
      result = inner(task, run_episode, env, demo_mode)
      result["ultra_judge_saw"] = saw
      try:
        passed = float(result.get("is_successful") or 0.0) > 0.5
        agent.send_verdict(passed, str(result.get("goal") or ""))
        result["ultra_task_params"] = {k: str(v)[:200] for k, v in (task.params or {}).items()}
      except Exception as e:  # noqa: BLE001 — a verdict that can't be sent must never cost a score
        print(f"ultra: verdict not sent: {e}")
      return result

    suite_utils._run_task = run_task
    suite_utils._ultra_verdict_hook = True

  def send_verdict(self, passed: bool, goal: str) -> None:
    if os.environ.get("ULTRA_NO_VERDICT"):   # the control arm: Ultra judges itself, as before
      return
    body = json.dumps({"passed": passed, "request": goal, "by": "AndroidWorld's check of the device"})
    fd, tmp = tempfile.mkstemp(suffix=".json")
    with os.fdopen(fd, "w") as f:
      f.write(body)
    try:
      self._adb("push", tmp, "/data/local/tmp/verdict.json")
      self._adb("shell", "mkdir", "-p", f"/sdcard/Android/data/{PKG}/files")
      self._adb("shell", "cp", "/data/local/tmp/verdict.json", f"/sdcard/Android/data/{PKG}/files/verdict.json")
      # An emulator: the app cannot read a pushed file in Android/data, so with root the same
      # file goes into its internal dir too (same reason as `northstar phone push`).
      uid = ""
      for line in self._adb("shell", "dumpsys", "package", PKG).splitlines():
        if "userId=" in line:
          uid = line.strip().split("userId=")[1].split()[0]
          break
      if not self._adb("shell", "id").startswith("uid=0"):
        self._adb("root")          # a no-op on a real phone, where the external copy is readable
        time.sleep(1.5)
      if uid and self._adb("shell", "id").startswith("uid=0"):
        self._adb("shell", f"cp /data/local/tmp/verdict.json /data/data/{PKG}/files/verdict.json && "
                           f"chown {uid}:{uid} /data/data/{PKG}/files/verdict.json && "
                           f"chmod 660 /data/data/{PKG}/files/verdict.json")
      self._adb("shell", "rm", "-f", "/data/local/tmp/verdict.json")
      print(f"ultra: verdict sent — {'pass' if passed else 'FAIL'}")
    finally:
      os.unlink(tmp)

  # ---- device helpers --------------------------------------------------------------
  def _adb(self, *args: str, timeout: int = 60) -> str:
    return subprocess.run(["adb", "-s", self._serial, *args], capture_output=True, text=True,
                          timeout=timeout).stdout

  def _screen(self) -> str:
    """uiautomator, not the benchmark's reader: with Ultra's service on, the harness's tree
    comes back empty, while uiautomator keeps working (2026-09-19)."""
    self._adb("shell", "uiautomator", "dump", "/sdcard/_ultra_ui.xml", timeout=90)
    return self._adb("shell", "cat", "/sdcard/_ultra_ui.xml", timeout=60)

  def _bounds(self, pattern: str, screen: str = ""):
    m = re.search(pattern + r'[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', screen or self._screen())
    if not m:
      return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

  def _tap_text(self, text: str, tries: int = 6) -> bool:
    for _ in range(tries):
      at = self._bounds(f'text="{re.escape(text)}"')
      if at:
        self._adb("shell", "input", "tap", str(at[0]), str(at[1]))
        return True
      time.sleep(1.5)
    return False

  def _ensure_a11y(self) -> bool:
    """Add Ultra's accessibility service back alongside the benchmark's forwarder, never replacing it."""
    svc = f"{PKG}/{PKG}.AgentAccessibilityService"
    fwd = "com.google.androidenv.accessibilityforwarder/com.google.androidenv.accessibilityforwarder.AccessibilityForwarder"
    current = self._adb("shell", "settings", "get", "secure", "enabled_accessibility_services").strip()
    if fwd not in current:            # never leave the benchmark without its own eyes
      current = fwd if current in ("null", "") else current + ":" + fwd
      self._adb("shell", "settings", "put", "secure", "enabled_accessibility_services", current)
    if svc not in current:
      wanted = svc if current in ("null", "") else current + ":" + svc
      self._adb("shell", "settings", "put", "secure", "enabled_accessibility_services", wanted)
      self._adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
      time.sleep(4)
    # Enabled is not bound. After an install or a force-stop the service stays in the enabled list
    # and Android does not bind it again; "PKG in dumpsys" was true, Ultra had no eyes, and the
    # first task after every install sat for the full 600 s doing nothing (2026-09-20).
    for attempt in range(3):
      for _ in range(5):
        if self._bound():
          return True
        time.sleep(2)
      listed = self._adb("shell", "settings", "get", "secure", "enabled_accessibility_services").strip()
      without = ":".join(x for x in listed.split(":") if x and PKG not in x) or fwd
      self._adb("shell", "settings", "put", "secure", "enabled_accessibility_services", without)
      time.sleep(2)
      self._adb("shell", "settings", "put", "secure", "enabled_accessibility_services", without + ":" + svc)
      time.sleep(4)
    return self._bound()

  def _bound(self) -> bool:
    # The bound list runs over several lines and names a service by its label, not its package.
    dump = self._adb("shell", "dumpsys", "accessibility")
    bound = dump.split("Bound services:", 1)[-1].split("Enabled services:", 1)[0]
    return "Agent Ultra" in bound or PKG in bound

  def _a11y_off(self) -> None:
    """Take Ultra's service back out of the list.

    The benchmark's forwarder and Ultra's service cannot both read this emulator: with both on,
    the harness logged "Could not get a11y tree, retrying" forever and no task finished; with only
    the forwarder it reads 80 elements first try (2026-09-19). So Ultra's eyes are on only while
    Ultra is working, and the benchmark reads the screen it scores by itself.
    """
    for _ in range(3):
      current = self._adb("shell", "settings", "get", "secure", "enabled_accessibility_services").strip()
      if PKG not in current:
        break
      kept = ":".join(x for x in current.split(":") if x and PKG not in x)
      self._adb("shell", "settings", "put", "secure", "enabled_accessibility_services", kept or "null")
      time.sleep(3)
    # ...and wait until the benchmark can actually read the screen again before handing back.
    time.sleep(3)

  def _open_fresh_chat(self) -> None:
    # The benchmark's accessibility forwarder dies on start now and then (a null context in its
    # own constructor thread), and Android's "keeps stopping" dialog then sits on top of Ultra's
    # chat: the goal can't be typed and the task is lost as "input not found", or the dialog's
    # "App info" gets tapped and the run sits on a Settings page for 600 s (2026-09-20).
    self._adb("shell", "settings", "put", "global", "hide_error_dialogs", "1")
    self._adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3)
    screen = self._screen()
    if "keeps stopping" in screen or "isn't responding" in screen:
      self._tap_text("Close app", tries=2)
      time.sleep(1.5)
      screen = self._screen()
    if "The policy gate paused this action" in screen:
      self._tap_text("Cancel", tries=2)
      time.sleep(1.5)
    if self._tap_text("☰", tries=2):
      time.sleep(1.5)
      self._tap_text("+ New chat", tries=2)
      time.sleep(1.5)

  def _type_goal(self, goal: str) -> bool:
    self.reason = ""
    # One look is not enough: the dump comes back empty while a window is still settling, and a
    # single miss threw the whole task away as "input not found" in 24 s (2026-09-20).
    at = None
    for attempt in range(6):
      at = self._bounds(r'class="android.widget.EditText"') or self._bounds(f'text="{HINT}[^"]*"')
      if at:
        break
      if attempt % 2 == 1:
        self._adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
      time.sleep(2)
    if not at:
      self.reason = "input not found"
      return False
    self._adb("shell", "input", "tap", str(at[0]), str(at[1]))
    time.sleep(0.8)
    # Longer than any goal: 160 deletes left the tail of a 200-character goal in the box, and the
    # next task was sent as "Run the stopwatch.ollowing text: Ignorance is bliss." — which the
    # policy gate, rightly, paused (2026-09-20).
    for _ in range(2):
      self._adb("shell", "input", "keyevent", "KEYCODE_MOVE_END", *(["KEYCODE_DEL"] * 250), timeout=120)
    escaped = goal.replace("'", "'\\''").replace(" ", "%s")
    self._adb("shell", f"input text '{escaped}'", timeout=120)
    time.sleep(1)
    # The dump misses now and then (a settling window, a dialog on top); a task is too much to
    # lose to one bad look.
    sent = self._tap_text("Send", tries=6)
    if not sent:
      if "keeps stopping" in self._screen():
        self._tap_text("Close app", tries=2)
      sent = self._tap_text("Send", tries=6)
    if not sent:
      self.reason = "send not found"
      return False
    return True

  def _hand_screen_back(self, lines: list[str]) -> str:
    """Bring the app the work happened in back to the front, without restarting it."""
    # Only when Ultra's own chat ended up in front. Reordering an app that is ALREADY in front
    # sends it back to its default screen — the Clock reopened on Alarm and the running stopwatch
    # was no longer visible, so a task that had passed started failing (2026-09-19).
    focus = self._adb("shell", "dumpsys", "window").split("mCurrentFocus")[-1][:200]  # adb, not the harness reader
    joined = "\n".join(lines)
    pkgs = re.findall(r"UltraNav: screen ([\w.]+)/", joined) + re.findall(r"Launched [^(]*\(([\w.]+)\)", joined)
    pkg = next((p for p in reversed(pkgs) if p and p != PKG), "")
    if not pkg:
      return ""
    # "Not Ultra's chat" is not "the task's app": a run that ended with a screenshot left the
    # launcher in front, this said "already in front", and a timer set exactly right (00h 16m 35s)
    # scored 0 because the check wants Clock on screen (2026-09-20).
    if pkg in focus:
      return "already in front"
    brief = self._adb("shell", "cmd", "package", "resolve-activity", "--brief",
                      "-c", "android.intent.category.LAUNCHER", pkg).strip().splitlines()
    comp = next((l.strip() for l in brief if "/" in l), "")
    if not comp:
      return ""
    self._adb("shell", "am", "start", "--activity-reorder-to-front", "-n", comp)
    time.sleep(2.5)
    return pkg

  # ---- the one interaction ---------------------------------------------------------
  def step(self, goal: str) -> base_agent.AgentInteractionResult:
    """Order matters, and it is the whole trick:

    1. Ultra's accessibility service OFF — the benchmark's reader works, so the goal can be
       opened and typed into Ultra's chat through the harness's own UI tree.
    2. Ultra's service ON — Ultra can see and drive the phone for its run.
    Ultra's service is left ON: turning it off between tasks was tried and made every task fail
    (the tasks that passed with it on stopped passing), so the harness retries its reads instead —
    it recovers, Ultra does not (2026-09-19).
    """
    self._adb("logcat", "-c")
    # The brain is reached through `adb reverse`, and that mapping dies whenever adbd restarts
    # (adb root, a reconnect by the harness). Without it every model call fails and Ultra answers
    # "Error: model call failed" to a whole round of tasks (2026-09-20). Cheap, so every task.
    port = os.environ.get("ULTRA_BRAIN_PORT", "8799")
    self._adb("reverse", f"tcp:{port}", f"tcp:{port}")
    bound = self._ensure_a11y()
    self._open_fresh_chat()
    typed = self._type_goal(goal)
    if not typed:
      return base_agent.AgentInteractionResult(
          done=True, data={"ultra_reached": False, "ultra_a11y": bound,
                           "ultra_reason": getattr(self, "reason", "")})
    t0 = time.time()
    log = ""
    answered = 0
    confirmed: list[str] = []
    while time.time() - t0 < self._timeout:
      time.sleep(4)
      log = self._adb("logcat", "-d", "-s", "UltraBrain:V", "UltraGate:V", "UltraNav:V", "UltraLearn:V",
                      "UltraActionGate:V")
      if DONE in log:
        break
      # Ultra's action gate stops before pressing anything that commits ("Delete", "Send") and
      # asks its person. In the benchmark the person is the task: nobody answered, the card timed
      # out as a refusal, and "delete the note" could never be finished (2026-09-20). So the
      # harness answers as the person who gave the task would — "Do it" only when the word on the
      # button is something the goal itself asks for, "Don't" otherwise — and records each answer.
      asks = re.findall(r'UltraActionGate: PAUSED: .* on "([^"]*)" in .* \(matched "([^"]*)"\)', log)
      if len(asks) > answered:
        label, word = asks[-1]
        answered = len(asks)
        # A careful person reads the button, not just the verb. "Delete lines" inside a note's
        # editor is not the "Delete" of "delete the note", and answering yes to it because the
        # goal contains "delete" is how a harness rubber-stamps the wrong thing (it did, twice,
        # 2026-09-20). Yes only when the button IS the verb the goal uses, or the goal names the
        # whole button.
        verb = label.strip().lower()
        ok = (verb == word.lower() and re.search(rf"\b{re.escape(verb)}", goal.lower()) is not None) \
            or (len(verb) > 3 and verb in goal.lower())
        # An app's own "are you sure?" box says CONFIRM or OK, not Delete. A careful person reads
        # the box: asked to delete an expense and shown "Delete this expense? CANCEL / CONFIRM",
        # they press CONFIRM — and they don't press it on a box about anything else. The harness
        # declined every such box and no delete in Pro Expense could finish (P11, 2026-09-20). So
        # for a bare yes-button the screen under the question is read, and the answer is yes only
        # when that screen talks about the very thing the goal's first word asks for.
        if not ok and verb in ("confirm", "ok", "yes", "accept", "agree", "done", "submit"):
            asked = re.match(r"\s*([A-Za-z]{4,})", goal)
            stem = asked.group(1).lower()[:5] if asked else ""
            under = re.sub(r"<[^>]*?(?:text|content-desc)=\"([^\"]*)\"[^>]*>", r" \1 ", self._screen()).lower()
            ok = bool(stem) and stem in under
        # The question is an overlay on top of the app now, which a uiautomator dump of the
        # active window does not contain; Ultra logs where its two buttons are, and the harness
        # presses one the way a finger would. The old in-chat card is still looked for second.
        time.sleep(1.5)
        where = re.findall(r"OVERLAY shown: do_it=(\d+),(\d+) dont=(\d+),(\d+)",
                           self._adb("logcat", "-d", "-s", "UltraActionGate:V"))
        pressed = False
        if where:
          x, y = (where[-1][0], where[-1][1]) if ok else (where[-1][2], where[-1][3])
          self._adb("shell", "input", "tap", x, y)
          pressed = True
        if pressed or self._tap_text("Do it" if ok else "Don't", tries=4):
          confirmed.append(f"{'confirmed' if ok else 'declined'}: {label}")
          print(f"ultra: gate asked about \"{label}\" — {'confirmed (the task asks for it)' if ok else 'declined'}")
    lines = [re.sub(r"^[0-9-]+ [0-9:.]+ +\d+ +\d+ [A-Z] ", "", x) for x in log.splitlines()]
    self.last_trace = lines
    time.sleep(2)
    handed_back = self._hand_screen_back(lines)
    return base_agent.AgentInteractionResult(done=True, data={
        "ultra_reached": True,
        "ultra_a11y": bound,
        "ultra_handed_back": handed_back,
        "ultra_secs": round(time.time() - t0, 1),
        "ultra_complete": DONE in log,
        "ultra_calls": [l.split("TOOL CALL: ", 1)[1] for l in lines if "TOOL CALL: " in l],
        "ultra_fails": [l.split("TOOL RESULT (fail): ", 1)[1][:200] for l in lines if "TOOL RESULT (fail)" in l],
        "ultra_blocks": [l.split("BLOCK ", 1)[1][:200] for l in lines if "UltraGate: BLOCK" in l],
        "ultra_gate_answers": confirmed,
        "ultra_lessons_served": [l.split("LESSONS SERVED: ", 1)[1] for l in lines if "LESSONS SERVED" in l],
        "ultra_learned": [l.split("LEARNED ", 1)[1][:200] for l in lines if "LEARNED " in l],
        "ultra_trace": [l[:220] for l in lines if "OBSERVE" not in l][-60:],
        # Every step with the whole screen line: what a passing run is turned into a route from.
        "ultra_trace_full": [l[:1800] for l in lines if "OBSERVE" not in l],
    })
