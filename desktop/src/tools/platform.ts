import { execFile, spawn } from "node:child_process";
import { writeFile } from "node:fs/promises";
import { platform } from "node:os";

/**
 * The few things that differ per operating system, each done with the
 * system's own command and argv arrays (no shell), so a URL or an app name can
 * never be read as a command.
 */

export const OS = platform();

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
  if (OS === "darwin") return launch("open", [url]);
  // rundll32 opens a URL without going through cmd.exe's parser.
  if (OS === "win32") return launch("rundll32", ["url.dll,FileProtocolHandler", url]);
  return launch("xdg-open", [url]);
}

export async function openApp(name: string): Promise<void> {
  if (/[\\/]|\.\./.test(name) && OS !== "darwin") throw new Error("Give an app name, not a path.");
  if (OS === "darwin") return launch("open", ["-a", name]);
  if (OS === "win32") {
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
  if (OS === "darwin") return run("pbpaste", []);
  if (OS === "win32") return run("powershell", ["-NoProfile", "-Command", "Get-Clipboard -Raw"]);
  try { return await run("wl-paste", ["--no-newline"]); } catch { return run("xclip", ["-selection", "clipboard", "-o"]); }
}

export async function clipboardWrite(text: string): Promise<void> {
  if (native) return native.clipboardWrite(text);
  if (OS === "darwin") { await run("pbcopy", [], text); return; }
  if (OS === "win32") { await run("powershell", ["-NoProfile", "-Command", "$input | Set-Clipboard"], text); return; }
  try { await run("wl-copy", [], text); } catch { await run("xclip", ["-selection", "clipboard"], text); }
}

export async function notify(title: string, message: string): Promise<void> {
  if (native) return native.notify(title, message);
  if (OS === "darwin") {
    // Pass the strings as AppleScript arguments, never spliced into the script.
    await run("osascript", ["-e", "on run argv\ndisplay notification (item 2 of argv) with title (item 1 of argv)\nend run", title, message]);
    return;
  }
  if (OS === "win32") {
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
  if (OS === "darwin") { await run("screencapture", ["-x", file]); return; }
  if (OS === "win32") {
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

export async function screenType(text: string): Promise<void> {
  if (text.length > 2000) throw new Error("Too much text to type at once.");
  if (OS === "darwin") { await run("osascript", ["-e", "on run argv\ntell application \"System Events\" to keystroke (item 1 of argv)\nend run", text]); return; }
  if (OS === "win32") { await run("powershell", ["-NoProfile", "-NonInteractive", "-EncodedCommand", Buffer.from(`Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait(${psQuote(text).replace(/([+^%~(){}[\]])/g, "{$1}")})`, "utf16le").toString("base64")]); return; }
  await first([["xdotool", ["type", "--clearmodifiers", "--", text]], ["ydotool", ["type", text]]], LINUX_HINT);
}

export async function screenKey(combo: string): Promise<void> {
  if (!/^[A-Za-z0-9+_-]{1,40}$/.test(combo)) throw new Error("Key names may hold letters, digits, + and - only (e.g. ctrl+c, Return, Tab).");
  if (OS === "darwin") { await run("osascript", ["-e", `tell application "System Events" to key code 36`]).catch(() => {}); return; }
  if (OS === "win32") { await run("powershell", ["-NoProfile", "-NonInteractive", "-Command", "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')"]).catch(() => {}); return; }
  await first([["xdotool", ["key", "--clearmodifiers", combo]], ["ydotool", ["key", combo]]], LINUX_HINT);
}

export async function screenClick(x: number, y: number, button: "left" | "right" | "middle"): Promise<void> {
  const b = { left: "1", right: "3", middle: "2" }[button];
  if (OS === "darwin") { await run("osascript", ["-e", `tell application "System Events" to click at {${x}, ${y}}`]).catch(() => {}); return; }
  if (OS === "win32") throw new Error("Mouse clicking isn't wired up on Windows yet.");
  await first([["xdotool", ["mousemove", String(x), String(y), "click", b]], ["ydotool", ["mousemove", String(x), String(y)]]], LINUX_HINT);
}
