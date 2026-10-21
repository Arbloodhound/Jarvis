import { execFile, spawn } from "node:child_process";
import { writeFile } from "node:fs/promises";
import { platform } from "node:os";
import { planMousePath, planScroll, planTyping, type MouseStep, type ScrollStep, type TypingStep } from "./human.js";

/**
 * The few things that differ per operating system, each done with the
 * system's own command and argv arrays (no shell), so a URL or an app name can
 * never be read as a command.
 */

export const OS = platform();

/** Test seam: pretend to run on another OS (the screen-action tests use it). */
let osForTest: string | null = null;
export function useOsForTest(os: string | null): void {
  osForTest = os;
}
function currentOs(): string {
  return osForTest ?? OS;
}

/**
 * When Jarvis runs as the desktop app, Electron supplies these natively
 * (clipboard, notifications, screen capture, the default browser) and the
 * command-line fallbacks below are not used. `jarvis serve` and the CLI keep
 * the fallbacks.
 */
export interface Native {
  openUrl(url: string): Promise<void>;
  clipboardRead(): Promise<string>;
  clipboardWrite(text: string): Promise<void>;
  notify(title: string, message: string): Promise<void>;
  /** PNG bytes of the whole screen. */
  screenshot(): Promise<Buffer>;
}

let native: Native | null = null;

export function useNative(impl: Native | null): void {
  native = impl;
}

export function run(cmd: string, args: string[], input?: string, timeoutMs = 15000): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = execFile(cmd, args, { timeout: timeoutMs, maxBuffer: 4 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) reject(new Error(`${cmd}: ${stderr?.toString().trim() || err.message}`));
      else resolve(stdout.toString());
    });
    if (input !== undefined) {
      child.stdin?.end(input);
    }
  });
}

/**
 * PowerShell joins -Command arguments into one script, so text passed that way
 * can run as code. Values go in as single-quoted literals ('' escapes a quote)
 * inside a script sent with -EncodedCommand, which PowerShell never re-parses.
 */
export function psQuote(s: string): string {
  return `'${s.replace(/['\u2018\u2019\u201A\u201B]/g, "''")}'`;
}

function powershell(script: string, timeoutMs?: number): Promise<string> {
  return run("powershell", ["-NoProfile", "-NonInteractive", "-EncodedCommand", Buffer.from(script, "utf16le").toString("base64")], undefined, timeoutMs);
}

/** Start a program and leave it running (a browser, an app). */
function launch(cmd: string, args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { detached: true, stdio: "ignore" });
    child.once("error", reject);
    child.once("spawn", () => { child.unref(); resolve(); });
  });
}

export async function openUrl(url: string): Promise<void> {
  if (!/^https?:\/\//i.test(url)) throw new Error("Only http and https links are opened.");
  if (native) return native.openUrl(url);
  if (currentOs() === "darwin") return launch("open", [url]);
  // rundll32 opens a URL without going through cmd.exe's parser.
  if (currentOs() === "win32") return launch("rundll32", ["url.dll,FileProtocolHandler", url]);
  return launch("xdg-open", [url]);
}

export async function openApp(name: string): Promise<void> {
  if (/[\\/]|\.\./.test(name) && currentOs() !== "darwin") throw new Error("Give an app name, not a path.");
  if (currentOs() === "darwin") return launch("open", ["-a", name]);
  if (currentOs() === "win32") {
    // cmd's `start` finds apps by name; only plain names reach cmd's parser.
    if (!/^[\w .+-]+$/.test(name)) throw new Error("App names may hold letters, digits, spaces, dots, + and - only.");
    return launch("cmd.exe", ["/d", "/c", "start", "", name]);
  }
  // Linux: try the desktop launcher by id, then the binary.
  try { await run("gtk-launch", [name], undefined, 5000); return; } catch { /* fall through */ }
  return launch(name, []);
}

export async function clipboardRead(): Promise<string> {
  if (native) return native.clipboardRead();
  if (currentOs() === "darwin") return run("pbpaste", []);
  if (currentOs() === "win32") return run("powershell", ["-NoProfile", "-Command", "Get-Clipboard -Raw"]);
  try { return await run("wl-paste", ["--no-newline"]); } catch { return run("xclip", ["-selection", "clipboard", "-o"]); }
}

export async function clipboardWrite(text: string): Promise<void> {
  if (native) return native.clipboardWrite(text);
  if (currentOs() === "darwin") { await run("pbcopy", [], text); return; }
  if (currentOs() === "win32") { await run("powershell", ["-NoProfile", "-Command", "$input | Set-Clipboard"], text); return; }
  try { await run("wl-copy", [], text); } catch { await run("xclip", ["-selection", "clipboard"], text); }
}

export async function notify(title: string, message: string): Promise<void> {
  if (native) return native.notify(title, message);
  if (currentOs() === "darwin") {
    // Pass the strings as AppleScript arguments, never spliced into the script.
    await run("osascript", ["-e", "on run argv\ndisplay notification (item 2 of argv) with title (item 1 of argv)\nend run", title, message]);
    return;
  }
  if (currentOs() === "win32") {
    const ps = `Add-Type -AssemblyName System.Windows.Forms; $n=New-Object System.Windows.Forms.NotifyIcon; $n.Icon=[System.Drawing.SystemIcons]::Information; $n.Visible=$true; $n.ShowBalloonTip(5000,${psQuote(title)},${psQuote(message)},'Info'); Start-Sleep 6; $n.Dispose()`;
    await powershell(ps, 20000);
    return;
  }
  await run("notify-send", [title, message]);
}

/** The whole screen as PNG bytes (native in the app, a temp file via CLI tools otherwise). */
export async function screenshotBuffer(): Promise<Buffer> {
  if (native) return native.screenshot();
  const { tmpdir } = await import("node:os");
  const { join } = await import("node:path");
  const { readFile, unlink } = await import("node:fs/promises");
  const file = join(tmpdir(), `jarvis-shot-${Date.now()}.png`);
  await screenshot(file);
  try { return await readFile(file); } finally { void unlink(file).catch(() => {}); }
}

export async function screenshot(file: string): Promise<void> {
  if (native) { await writeFile(file, await native.screenshot()); return; }
  if (currentOs() === "darwin") { await run("screencapture", ["-x", file]); return; }
  if (currentOs() === "win32") {
    const ps = `Add-Type -AssemblyName System.Windows.Forms,System.Drawing; $b=[System.Windows.Forms.SystemInformation]::VirtualScreen; $bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height; $g=[System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Left,$b.Top,0,0,$bmp.Size); $bmp.Save(${psQuote(file)})`;
    await powershell(ps, 20000);
    return;
  }
  const tries: Array<[string, string[]]> = [["grim", [file]], ["gnome-screenshot", ["-f", file]], ["scrot", [file]], ["import", ["-window", "root", file]]];
  for (const [cmd, args] of tries) {
    try { await run(cmd, args); return; } catch { /* next */ }
  }
  throw new Error("No screenshot tool found (install grim, gnome-screenshot, scrot or ImageMagick).");
}

/**
 * Synthetic keyboard and mouse. Experimental and off by default: this can act
 * on any window, so every call is gated by a confirm card. Uses the OS's own
 * automation tool; when it isn't installed, the error says which one to get.
 * Nothing here is proven on a real desktop yet.
 */
async function first(tries: Array<[string, string[]]>, install: string): Promise<string> {
  for (const [cmd, args] of tries) {
    try { return await run(cmd, args, undefined, 10000); } catch { /* next backend */ }
  }
  throw new Error(install);
}

const LINUX_HINT = "Screen control needs 'xdotool' (X11) or 'ydotool' (Wayland). Install one, e.g. `sudo apt install xdotool`.";

/**
 * Human-quality execution. Screen actions are planned by the pure
 * planners in human.ts (eased cursor paths, natural typing cadence,
 * incremental scroll) and replayed here step by step: sleep the step's
 * delay, then emit the step. The Linux/xdotool executors below are the
 * real, live-proven path. Deliberate exceptions where a one-shot burst
 * survives, and why:
 * - ydotool-only Linux (no xdotool): ydotool has no per-step location
 *   or move reporting this code can drive, so typing/key fall back to
 *   the legacy one-shot ydotool call. xdotool systems never see it.
 * - macOS/Windows: typing replays the same plan through per-chunk
 *   backend calls (design parity), but those paths are UNPROVEN on real
 *   hardware; mouse-move/scroll have no steppable backend there yet
 *   and say so instead of faking a plan replay.
 */

export type RunFn = (cmd: string, args: string[]) => Promise<string>;
export type SleepFn = (ms: number) => Promise<void>;

export interface ScreenExecDeps {
  runFn: RunFn;
  sleepFn: SleepFn;
}

const realSleep: SleepFn = (ms) => new Promise((r) => setTimeout(r, ms));

/** Test seam: substitute the command runner and the clock. */
let screenDepsForTest: ScreenExecDeps | null = null;
let xdotoolKnown: boolean | null = null;
export function useScreenDepsForTest(deps: ScreenExecDeps | null): void {
  screenDepsForTest = deps;
  xdotoolKnown = null;
}
function screenDeps(): ScreenExecDeps {
  return screenDepsForTest ?? { runFn: (cmd, args) => run(cmd, args, undefined, 10000), sleepFn: realSleep };
}

async function xdotoolAvailable(deps: ScreenExecDeps): Promise<boolean> {
  if (xdotoolKnown !== null) return xdotoolKnown;
  try {
    await deps.runFn("xdotool", ["version"]);
    xdotoolKnown = true;
  } catch {
    xdotoolKnown = false;
  }
  return xdotoolKnown;
}

/** Replay a planned cursor path through xdotool, one move per step. */
export async function replayMousePath(steps: MouseStep[], deps: ScreenExecDeps): Promise<void> {
  for (const s of steps) {
    if (s.delayMs > 0) await deps.sleepFn(s.delayMs);
    // No `--sync`: on xdotool 3.2016 it can stall waiting for a motion
    // event; a plain mousemove is flushed when the command exits.
    await deps.runFn("xdotool", ["mousemove", String(s.x), String(s.y)]);
  }
}

/**
 * Replay a typing plan through xdotool, one `type` call per step.
 * xdotool's own `type --delay` is a single uniform delay, so it cannot
 * express the plan's variable cadence; the executor sleeps between
 * per-key calls instead. That is the default (and only) xdotool path.
 */
export async function replayTypingPlan(steps: TypingStep[], deps: ScreenExecDeps): Promise<void> {
  for (const s of steps) {
    if (s.delayMs > 0) await deps.sleepFn(s.delayMs);
    await deps.runFn("xdotool", ["type", "--clearmodifiers", "--", s.text]);
  }
}

/** Replay a scroll plan: each increment is its own burst of wheel clicks. */
export async function replayScrollPlan(steps: ScrollStep[], deps: ScreenExecDeps): Promise<void> {
  for (const s of steps) {
    if (s.delayMs > 0) await deps.sleepFn(s.delayMs);
    const button = s.steps < 0 ? "4" : "5"; // wheel up / wheel down
    for (let i = 0; i < Math.abs(s.steps); i++) {
      await deps.runFn("xdotool", ["click", button]);
    }
  }
}

/** Current cursor position on Linux/X11, or null when it can't be read. */
export async function screenMouseLocation(deps: ScreenExecDeps = screenDeps()): Promise<{ x: number; y: number } | null> {
  try {
    const out = await deps.runFn("xdotool", ["getmouselocation", "--shell"]);
    const x = /\bX=(\d+)/.exec(out);
    const y = /\bY=(\d+)/.exec(out);
    if (!x || !y) return null;
    return { x: Number(x[1]), y: Number(y[1]) };
  } catch {
    return null;
  }
}

/** Move the cursor to (x, y) along a planned eased path (no click). */
export async function screenMove(x: number, y: number): Promise<void> {
  if (currentOs() === "darwin") throw new Error("Moving the mouse without clicking isn't wired up on macOS yet (no steppable move backend).");
  if (currentOs() === "win32") throw new Error("Moving the mouse without clicking isn't wired up on Windows yet.");
  const deps = screenDeps();
  if (!(await xdotoolAvailable(deps))) throw new Error(LINUX_HINT);
  const from = (await screenMouseLocation(deps)) ?? { x, y };
  await replayMousePath(planMousePath(from, { x, y }), deps);
}

/**
 * Scroll by `amount` wheel clicks (positive = down, negative = up),
 * optionally after gliding the cursor to (x, y) first. The scroll is
 * emitted as planned increments, never one jump.
 */
export async function screenScroll(amount: number, x?: number, y?: number): Promise<void> {
  if (currentOs() === "darwin") throw new Error("Scrolling isn't wired up on macOS yet.");
  if (currentOs() === "win32") throw new Error("Scrolling isn't wired up on Windows yet.");
  const deps = screenDeps();
  if (!(await xdotoolAvailable(deps))) throw new Error(LINUX_HINT);
  if (x !== undefined && y !== undefined) {
    const from = (await screenMouseLocation(deps)) ?? { x, y };
    await replayMousePath(planMousePath(from, { x, y }), deps);
  }
  await replayScrollPlan(planScroll(amount), deps);
}

export async function screenType(text: string): Promise<void> {
  if (text.length > 2000) throw new Error("Too much text to type at once.");
  const plan = planTyping(text);
  if (currentOs() === "darwin") {
    // Same plan, per-chunk backend calls — design parity, unproven on a Mac.
    for (const s of plan) {
      if (s.delayMs > 0) await realSleep(s.delayMs);
      await run("osascript", ["-e", "on run argv\ntell application \"System Events\" to keystroke (item 1 of argv)\nend run", s.text]);
    }
    return;
  }
  if (currentOs() === "win32") {
    // Same plan, per-chunk SendKeys — design parity, unproven on Windows.
    for (const s of plan) {
      if (s.delayMs > 0) await realSleep(s.delayMs);
      await run("powershell", ["-NoProfile", "-NonInteractive", "-EncodedCommand", Buffer.from(`Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait(${psQuote(s.text).replace(/([+^%~(){}[\]])/g, "{$1}")})`, "utf16le").toString("base64")]);
    }
    return;
  }
  const deps = screenDeps();
  if (await xdotoolAvailable(deps)) {
    await replayTypingPlan(plan, deps);
    return;
  }
  // Documented exception: ydotool-only systems keep the one-shot burst.
  await first([["ydotool", ["type", text]]], LINUX_HINT);
}

/** Split "ctrl+shift+c" into its modifiers and the final key. */
function parseCombo(combo: string): { mods: string[]; key: string } {
  const parts = combo.split("+").map((s) => s.trim()).filter(Boolean);
  const key = parts.pop() ?? "";
  return { mods: parts.map((m) => m.toLowerCase()), key };
}

const MAC_KEY_CODES: Record<string, number> = {
  return: 36, enter: 36, tab: 48, space: 49, delete: 51, backspace: 51, escape: 53, esc: 53,
  home: 115, end: 119, pageup: 116, pagedown: 121,
  left: 123, right: 124, down: 125, up: 126,
  f1: 122, f2: 120, f3: 99, f4: 118, f5: 96, f6: 97, f7: 98, f8: 100, f9: 101, f10: 109, f11: 103, f12: 111,
};
const MAC_MODIFIERS: Record<string, string> = {
  ctrl: "control down", control: "control down", alt: "option down", option: "option down",
  shift: "shift down", cmd: "command down", command: "command down", meta: "command down",
};
const SENDKEYS_KEYS: Record<string, string> = {
  return: "{ENTER}", enter: "{ENTER}", tab: "{TAB}", escape: "{ESC}", esc: "{ESC}", space: " ",
  delete: "{DELETE}", backspace: "{BACKSPACE}", home: "{HOME}", end: "{END}",
  pageup: "{PGUP}", pagedown: "{PGDN}", left: "{LEFT}", right: "{RIGHT}", down: "{DOWN}", up: "{UP}",
  f1: "{F1}", f2: "{F2}", f3: "{F3}", f4: "{F4}", f5: "{F5}", f6: "{F6}",
  f7: "{F7}", f8: "{F8}", f9: "{F9}", f10: "{F10}", f11: "{F11}", f12: "{F12}",
};
const SENDKEYS_MODIFIERS: Record<string, string> = { ctrl: "^", control: "^", alt: "%", shift: "+" };

export async function screenKey(combo: string): Promise<void> {
  if (!/^[A-Za-z0-9+_-]{1,40}$/.test(combo)) throw new Error("Key names may hold letters, digits, + and - only (e.g. ctrl+c, Return, Tab).");
  if (currentOs() === "darwin") {
    // The old code sent Return's key code for EVERY combo and swallowed the
    // result, so any key press "succeeded". Now the requested combo is what
    // gets sent — or the call fails saying why.
    const { mods, key } = parseCombo(combo);
    const using: string[] = [];
    for (const m of mods) {
      const mac = MAC_MODIFIERS[m];
      if (!mac) throw new Error(`Key combo "${combo}": the "${m}" modifier isn't supported on macOS.`);
      using.push(mac);
    }
    const usingClause = using.length ? ` using {${using.join(", ")}}` : "";
    const code = MAC_KEY_CODES[key.toLowerCase()];
    let action: string;
    if (code !== undefined) action = `key code ${code}${usingClause}`;
    else if (key.length === 1) action = `keystroke ${JSON.stringify(key)}${usingClause}`;
    else throw new Error(`Key "${key}" isn't one macOS screen control can send (a single character, Return, Tab, Escape, space, Delete, arrows, Home/End, PageUp/PageDown, F1–F12).`);
    await run("osascript", ["-e", `tell application "System Events" to ${action}`]);
    return;
  }
  if (currentOs() === "win32") {
    // Same story: the old code sent {ENTER} for every combo and swallowed
    // the result. SendKeys spells the requested combo, or the call fails.
    const { mods, key } = parseCombo(combo);
    let prefix = "";
    for (const m of mods) {
      const c = SENDKEYS_MODIFIERS[m];
      if (!c) throw new Error(`Key combo "${combo}": the "${m}" modifier can't be sent with SendKeys (no Windows/meta key).`);
      prefix += c;
    }
    let body = SENDKEYS_KEYS[key.toLowerCase()];
    if (body === undefined) {
      if (key.length === 1) body = key;
      else throw new Error(`Key "${key}" isn't one Windows screen control can send (a single character, Return, Tab, Escape, space, Delete, arrows, Home/End, PageUp/PageDown, F1–F12).`);
    }
    const script = `Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait(${psQuote(prefix + body)})`;
    await run("powershell", ["-NoProfile", "-NonInteractive", "-EncodedCommand", Buffer.from(script, "utf16le").toString("base64")]);
    return;
  }
  await first([["xdotool", ["key", "--clearmodifiers", combo]], ["ydotool", ["key", combo]]], LINUX_HINT);
}

/** Like run(), but returns the raw stdout bytes (synthesized audio). */
function runBinary(cmd: string, args: string[], timeoutMs = 120000): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: timeoutMs, maxBuffer: 64 * 1024 * 1024, encoding: "buffer" }, (err, stdout, stderr) => {
      if (err) reject(new Error(`${cmd}: ${stderr?.toString().trim() || err.message}`));
      else resolve(stdout);
    });
  });
}

const TTS_HINT = "No text-to-speech voice found on this computer (Linux: install speech-dispatcher for spd-say, or espeak-ng; macOS has `say` built in; Windows uses SAPI).";

/**
 * Speak text with the operating system's own voice. Returns the backend
 * that actually spoke. Honest-failure discipline, same as the screen
 * actions: when no backend exists — or the one that exists fails — this
 * throws; it never reports speech that did not happen.
 */
export async function speakText(text: string): Promise<string> {
  if (currentOs() === "darwin") { await run("say", [text], undefined, 120000); return "say"; }
  if (currentOs() === "win32") {
    await powershell(`$v=New-Object -ComObject SAPI.SpVoice; [void]$v.Speak(${psQuote(text)})`, 120000);
    return "SAPI";
  }
  const tries: Array<[string, string[], string]> = [
    ["spd-say", ["-w", text], "spd-say"],
    ["espeak-ng", [text], "espeak-ng"],
    ["espeak", [text], "espeak"],
    ["flite", ["-t", text], "flite"],
  ];
  for (const [cmd, args, label] of tries) {
    try { await run(cmd, args, undefined, 120000); return label; } catch { /* next backend */ }
  }
  throw new Error(TTS_HINT);
}

/**
 * Synthesize speech into an audio file instead of playing it (WAV on
 * Linux/Windows, AIFF/WAV by extension on macOS). Returns the backend
 * that wrote it. spd-say cannot write a file, so on Linux this needs
 * espeak/espeak-ng (--stdout) or flite (-o); without one it throws.
 */
export async function speakToFile(text: string, file: string): Promise<string> {
  if (currentOs() === "darwin") { await run("say", ["-o", file, text], undefined, 120000); return "say"; }
  if (currentOs() === "win32") {
    const ps = `$v=New-Object -ComObject SAPI.SpVoice; $s=New-Object -ComObject SAPI.SpFileStream; $s.Open(${psQuote(file)},3); $v.AudioOutputStream=$s; [void]$v.Speak(${psQuote(text)}); $s.Close()`;
    await powershell(ps, 120000);
    return "SAPI";
  }
  for (const cmd of ["espeak-ng", "espeak"]) {
    try {
      const wav = await runBinary(cmd, ["--stdout", text]);
      if (wav.length === 0) throw new Error(`${cmd} produced no audio`);
      const { writeFile: wf } = await import("node:fs/promises");
      await wf(file, wav);
      return cmd;
    } catch (err) {
      if (err instanceof Error && /produced no audio/.test(err.message)) throw err;
      /* next backend */
    }
  }
  try { await run("flite", ["-t", text, "-o", file], undefined, 120000); return "flite"; } catch { /* no backend */ }
  throw new Error(`Writing speech to a file needs espeak/espeak-ng or flite on Linux (spd-say can only play aloud). ${TTS_HINT}`);
}

export async function screenClick(x: number, y: number, button: "left" | "right" | "middle"): Promise<void> {
  const b = { left: "1", right: "3", middle: "2" }[button];
  if (currentOs() === "darwin") { await run("osascript", ["-e", `tell application "System Events" to click at {${x}, ${y}}`]); return; }
  if (currentOs() === "win32") throw new Error("Mouse clicking isn't wired up on Windows yet.");
  const deps = screenDeps();
  if (await xdotoolAvailable(deps)) {
    // Glide to the target along a planned eased path, then click there.
    // When the current position can't be read, the path degrades to a
    // single step at the target (position unknown, distance unknown).
    const from = (await screenMouseLocation(deps)) ?? { x, y };
    await replayMousePath(planMousePath(from, { x, y }), deps);
    await deps.runFn("xdotool", ["click", b]);
    return;
  }
  // Documented exception: ydotool-only systems keep the one-shot move.
  await first([["ydotool", ["mousemove", String(x), String(y)]]], LINUX_HINT);
}
