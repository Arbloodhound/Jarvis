import { execFile, spawn } from "node:child_process";
import { platform } from "node:os";

/**
 * The few things that differ per operating system, each done with the
 * system's own command and argv arrays (no shell), so a URL or an app name can
 * never be read as a command.
 */

export const OS = platform();

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
  if (OS === "darwin") return run("pbpaste", []);
  if (OS === "win32") return run("powershell", ["-NoProfile", "-Command", "Get-Clipboard -Raw"]);
  try { return await run("wl-paste", ["--no-newline"]); } catch { return run("xclip", ["-selection", "clipboard", "-o"]); }
}

export async function clipboardWrite(text: string): Promise<void> {
  if (OS === "darwin") { await run("pbcopy", [], text); return; }
  if (OS === "win32") { await run("powershell", ["-NoProfile", "-Command", "$input | Set-Clipboard"], text); return; }
  try { await run("wl-copy", [], text); } catch { await run("xclip", ["-selection", "clipboard"], text); }
}

export async function notify(title: string, message: string): Promise<void> {
  if (OS === "darwin") {
    // Pass the strings as AppleScript arguments, never spliced into the script.
    await run("osascript", ["-e", "on run argv\ndisplay notification (item 2 of argv) with title (item 1 of argv)\nend run", title, message]);
    return;
  }
  if (OS === "win32") {
    const ps = "param($t,$m) Add-Type -AssemblyName System.Windows.Forms; $n=New-Object System.Windows.Forms.NotifyIcon; $n.Icon=[System.Drawing.SystemIcons]::Information; $n.Visible=$true; $n.ShowBalloonTip(5000,$t,$m,'Info'); Start-Sleep 6; $n.Dispose()";
    await run("powershell", ["-NoProfile", "-Command", `& {${ps}}`, title, message], undefined, 20000);
    return;
  }
  await run("notify-send", [title, message]);
}

export async function screenshot(file: string): Promise<void> {
  if (OS === "darwin") { await run("screencapture", ["-x", file]); return; }
  if (OS === "win32") {
    const ps = "param($f) Add-Type -AssemblyName System.Windows.Forms,System.Drawing; $b=[System.Windows.Forms.SystemInformation]::VirtualScreen; $bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height; $g=[System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Left,$b.Top,0,0,$bmp.Size); $bmp.Save($f)";
    await run("powershell", ["-NoProfile", "-Command", `& {${ps}}`, file], undefined, 20000);
    return;
  }
  const tries: Array<[string, string[]]> = [["grim", [file]], ["gnome-screenshot", ["-f", file]], ["scrot", [file]], ["import", ["-window", "root", file]]];
  for (const [cmd, args] of tries) {
    try { await run(cmd, args); return; } catch { /* next */ }
  }
  throw new Error("No screenshot tool found (install grim, gnome-screenshot, scrot or ImageMagick).");
}
